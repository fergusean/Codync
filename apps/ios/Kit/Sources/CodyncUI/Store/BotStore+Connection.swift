import CodyncKit
import Foundation

extension BotStore {
    /// Permanently detach a store (account switch, computer removed). Late async
    /// callbacks still hold it, but it writes nothing and calls no hooks anymore.
    public func retire() {
        let ending = Array(holds.values)
        holds.removeAll()
        setActive(false)
        retired = true
        fileDownloads.retire()
        for hold in ending { hold.end() }
        saveTask?.cancel()
        dropTimer?.cancel()
        onConnected = nil
        onBotUpdated = nil
        onUsageChanged = nil
        onSent = nil
        onRosterChanged = nil
        onComputerChanged = nil
        client = nil
        selection = nil
        screenRequest = nil
        showProfile = false
        showPlugins = false
    }

    public func setActive(_ active: Bool) {
        guard !retired else { return }
        isActive = active
        if active {
            // Returning to the foreground must not interrupt a hold's sends.
            if holds.isEmpty || streamTask == nil { restartStream() } else { syncEventsClient() }
        } else if holds.isEmpty {
            // Disconnect so the host knows we're gone and sends pushes instead.
            stopTransport()
            saveCache()
        } else {
            syncEventsClient()
        }
    }

    public func restartStream() {
        guard isActive || !holds.isEmpty, !retired else { return }
        stopTransport()
        if connection != .online { setConnection(.connecting) }
        streamTask = Task { [weak self] in await self?.runStream() }
    }

    /// A hold keeps the link (and, in the background, its events) alive on behalf of its owner,
    /// which gets the held bot's replies, approval requests and idle moments straight from the
    /// store's incoming events instead of waiting for a SwiftUI update. Holds stack: the link is
    /// released in the background only after the last one ends.
    /// `mutesPushes`: while inactive, the events stream still counts as a phone for the host, which
    /// holds its pushes back (a voice call speaks the replies itself). Other holds keep the link
    /// without that, so the host keeps pushing.
    func beginHold(_ id: UUID, botId: String?, mutesPushes: Bool = false,
                   reply: @escaping @MainActor (Entry) -> Void = { _ in },
                   needsInput: @escaping @MainActor (Bot) -> Void = { _ in },
                   announce: @escaping @MainActor (String) -> Void = { _ in },
                   settled: @escaping @MainActor (String) -> Void = { _ in },
                   end: @escaping @MainActor () -> Void = {}) {
        guard !retired, holds[id] == nil else { return }
        holds[id] = Hold(botId: botId, startRev: rev, reply: reply, needsInput: needsInput, announce: announce, settled: settled, end: end, mutesPushes: mutesPushes)
        if streamTask == nil { restartStream() } else { syncEventsClient() }
    }

    /// Returns once the released link's transport has closed (`shutdown` waits, about a second at
    /// most, for the close frame to leave), so a caller about to be suspended can finish its
    /// background task after it.
    func endHold(_ id: UUID) async {
        await releaseHold(id)?.value
    }

    /// `endHold` without the wait, for a caller that can't suspend (an expiring background task):
    /// the hold is gone and the shutdown started; the returned task finishes with it.
    @discardableResult
    func releaseHold(_ id: UUID) -> Task<Void, Never>? {
        guard holds.removeValue(forKey: id) != nil, !isActive else { return nil }
        guard holds.isEmpty else {
            syncEventsClient()
            return nil
        }
        let closing = stopTransport()
        saveCache()
        return closing
    }

    /// What the events subscription tells the host it is: a phone while the app is active or a
    /// muting hold needs it, otherwise nothing the host counts.
    var eventsClient: String? {
        isActive || holds.values.contains(where: \.mutesPushes) ? clientKind : nil
    }

    /// Resubscribes (same link) when the host should see a different client.
    func syncEventsClient() {
        guard eventsTask != nil, eventsClient != subscribedClient else { return }
        restartEvents()
    }

    /// The link is up and the first catch-up has arrived: what the store shows is current.
    var isLive: Bool { live != nil && linkCaughtUp }

    /// Returns the task that closes a remote transport, for callers that must await it.
    @discardableResult
    func stopTransport() -> Task<Void, Never>? {
        streamTask?.cancel()
        streamTask = nil
        eventsTask?.cancel()
        eventsTask = nil
        linkCaughtUp = false
        stateFromThisTransport = false
        catchUpIdleTask?.cancel()
        catchUpIdleTask = nil
        catchUpRev = .max
        reconnecting = true
        dropTimer?.cancel()
        dropTimer = nil
        heldDrop = nil
        defer { transport = nil }
        guard let remote = transport as? any RemoteTransport else { return nil }
        let task = Task { await remote.shutdown() }
        closing = task
        return task
    }

    private func runStream() async {
        var backoff: Double = 1
        var transport: any HostTransport
        while true {
            do {
                transport = try await makeTransport()
                break
            } catch HostError.unauthorized(let message) {
                if !Task.isCancelled, !retired { setConnection(.unauthorized(message)) }
                return
            } catch {
                guard !Task.isCancelled, !retired else { return }
                setConnection(.offline(error.localizedDescription))
                try? await Task.sleep(for: .seconds(backoff))
                backoff = min(backoff * 2, 30)
                guard !Task.isCancelled else { return }
            }
        }
        guard !Task.isCancelled, !retired else {
            if let remote = transport as? any RemoteTransport { await remote.shutdown() }
            return
        }
        self.transport = transport
        let client = HostClient(transport: transport)
        self.client = client
        if let remote = transport as? any RemoteTransport { watch(remote) }
        for await state in transport.states() {
            guard !Task.isCancelled, !retired else { return }
            switch state {
            case let .ready(route):
                setHostRoute(route)
                if eventsTask == nil {
                    if connection != .online { setConnection(.connecting) }
                    eventsTask = Task { [weak self] in await self?.runEvents(client) }
                    // Mailbox outcomes that happened while this app wasn't listening (§10.2).
                    if let remote = transport as? any RemoteTransport {
                        Task { [weak self] in await self?.reconcileQueued(remote) }
                    }
                }
                onConnected?(self)
            case .connecting:
                stopEvents()
                setConnection(.connecting)
            case let .hostOffline(lastSeen):
                stopEvents()
                setConnection(.computerOffline(lastSeen: lastSeen))
                if let remote = transport as? any RemoteTransport {
                    Task { [weak self] in await self?.reconcileQueued(remote) }
                }
            case let .unauthorized(message):
                stopEvents()
                setConnection(.unauthorized(message))
            case let .failed(message):
                stopEvents()
                setConnection(.offline(message))
            }
        }
    }

    /// Connection changes animate wherever they show (banners, headers, captions, rows).
    /// Give initial connection failures a second to recover and online drops five seconds.
    /// Relay presence can lag a reconnect, so "computer offline" gets the same grace.
    /// Authorization failures still show immediately.
    func setConnection(_ new: Connection) {
        let transient = switch new {
        case .connecting, .offline, .computerOffline: true
        default: false
        }
        let recovering = new == .online && (connection != .online || heldDrop != nil || reconnecting)
        let initialFailure = connection == .connecting && transient && new != .connecting
        if transient && (connection == .online || initialFailure) {
            let grace = connection == .online ? Self.dropGrace : Self.initialConnectionGrace
            heldDrop = new
            if dropTimer == nil {
                dropTimer = Task { [weak self] in
                    try? await Task.sleep(for: grace)
                    guard !Task.isCancelled, let self, let held = self.heldDrop else { return }
                    self.dropTimer = nil
                    self.heldDrop = nil
                    Motion.animate { self.connection = held }
                }
            }
            return
        }
        dropTimer?.cancel()
        dropTimer = nil
        heldDrop = nil
        if new != connection { Motion.animate { connection = new } }
        if new == .online { reconnecting = false }
        if recovering {
            for scope in Set(readingViews.values) { markRead(scope.botId, thread: scope.thread) }
        }
    }

    private func setHostRoute(_ new: HostRoute?) {
        guard new != hostRoute else { return }
        Motion.animate { hostRoute = new }
    }

    private func stopEvents() {
        eventsTask?.cancel()
        eventsTask = nil
        linkCaughtUp = false
        catchUpIdleTask?.cancel()
        catchUpIdleTask = nil
        catchUpRev = .max
        setHostRoute(nil)
    }

    /// The channel's side streams: merged computer info and mailbox outcomes.
    private func watch(_ remote: any RemoteTransport) {
        let updates = remote.computerUpdates()
        let mailbox = remote.mailboxEvents()
        Task { [weak self] in
            for await computer in updates {
                guard let self, !self.retired else { return }
                // The color is picked on this device; the transport's copy may be older.
                self.updateComputer { current in
                    let color = current.color
                    current = computer
                    current.color = color
                }
            }
        }
        Task { [weak self] in
            for await event in mailbox {
                guard let self, !self.retired else { return }
                self.applyMailbox(event)
            }
        }
    }
}
