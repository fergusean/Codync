import CodyncKit
import Foundation
import Observation

// The iPhone side of the Apple Watch link (docs/plans/watch-app.md): answers the watch's requests
// from the current computer's `BotStore`, publishes what the watch shows, and keeps the store's
// link alive (holds) just long enough to forward a reply. WatchConnectivity and UIKit stay in
// `apps/ios/App/WatchCompanion.swift`, behind the two protocols.

/// The WatchConnectivity session, as the bridge needs it.
@MainActor
public protocol WatchLink: AnyObject {
    var isActivated: Bool { get }
    var isPaired: Bool { get }
    var isWatchAppInstalled: Bool { get }
    var isReachable: Bool { get }
    /// Called when reachability, pairing or installation changes.
    var onChange: (@MainActor () -> Void)? { get set }
    /// The latest snapshot (latest wins, delivered in the background).
    func updateApplicationContext(_ data: Data)
    /// A chat push for a reachable watch; no reply expected.
    /// `onFailure` runs when delivery fails (reachability lags: a wrist that just dropped still reads reachable).
    func sendMessage(_ data: Data, onFailure: @escaping @MainActor () -> Void)
    /// Queues a chat for the watch's next launch; replaces the bot's earlier outstanding transfer.
    func transferUserInfo(_ data: Data, botId: String)
    func cancelTransfers()
}

/// App state and effects the bridge needs from the OS.
@MainActor
public protocol WatchSystem: AnyObject {
    var isAppActive: Bool { get }
    /// `.infinity` in the foreground.
    var backgroundTimeRemaining: TimeInterval { get }
    /// nil when the system refuses. `expiration` runs when the time is up; it must release at once.
    func beginBackgroundTask(expiration: @escaping @MainActor () -> Void) -> Int?
    func endBackgroundTask(_ token: Int)
}

@MainActor
public final class WatchBridge {
    public struct Durations: Sendable {
        /// A watch lease lives this long without a renewal.
        public var lease: Duration = .seconds(20)
        /// A send or answer keeps the link this long waiting for the reply.
        public var exchange: Duration = .seconds(25)
        /// Changes within this window become one publish.
        public var coalesce: Duration = .milliseconds(300)
        /// An answer for a card the phone hasn't caught up to waits this long for it.
        public var respondRetry: Duration = .seconds(20)
        /// Held back from `backgroundTimeRemaining` so the link closes before suspension.
        public var margin: Duration = .seconds(5)
        /// A group's status follows its busy member, so it goes idle between members: settle only after this.
        public var groupGrace: Duration = .seconds(3)

        public init() {}
    }

    /// The watch app is open: the store's link is held, and its open chat counts as read.
    private struct Lease {
        let id: UUID
        let store: BotStore
        var expiry: Task<Void, Never>?
        var open: (token: UUID, botId: String)?
    }

    /// A send or answer waiting for the bot's reply.
    private struct Exchange {
        let id: UUID
        let store: BotStore
        let botId: String
        /// The nonce or card id: one exchange per key.
        let key: String
        let nonce: String?
        /// The card a respond exchange answers (nil until found). A send exchange looks its message up
        /// in the store instead: its status changes.
        var card: (seq: Int64, turn: Int64)?
        var expiry: Task<Void, Never>?
        var retry: Task<Void, Never>?
        var grace: Task<Void, Never>?
    }

    private var accounts: AccountStore
    private let link: WatchLink
    private let system: WatchSystem
    private let durations: Durations

    private var lease: Lease?
    private var exchanges: [UUID: Exchange] = [:]
    private var backgroundTask: Int?
    /// Releases whose shutdown is still in flight: the background task outlives them.
    private var releasing = 0

    private var lastSnapshot: WatchSnapshot?
    private var lastChat: WatchChat?
    private var generation = 0
    private var pending: Task<Void, Never>?

    public init(accounts: AccountStore, link: WatchLink, system: WatchSystem, durations: Durations = Durations()) {
        self.accounts = accounts
        self.link = link
        self.system = system
        self.durations = durations
        link.onChange = { [weak self] in self?.refresh() }
        refresh()
    }

    /// The account changed (switch, sign-out, start over): nothing from the old stores is
    /// forwarded again, and the watch gets the new scope.
    public func bind(_ accounts: AccountStore) {
        pending?.cancel()
        pending = nil
        endAll()
        link.cancelTransfers()
        self.accounts = accounts
        lastSnapshot = nil
        lastChat = nil
        refresh()
    }

    /// The app moved between foreground and background.
    public func appActiveChanged() {
        if system.isAppActive {
            if releasing == 0, let task = backgroundTask {
                backgroundTask = nil
                system.endBackgroundTask(task)
            }
        } else if lease != nil || !exchanges.isEmpty {
            guard prepareBackground() else { return expired() }
            // Timers armed in the foreground didn't know about the background limit.
            if lease != nil { lease?.expiry?.cancel(); lease?.expiry = timer(durations.lease) { [weak self] in self?.endLease() } }
            for id in Array(exchanges.keys) {
                exchanges[id]?.expiry?.cancel()
                exchanges[id]?.expiry = timer(durations.exchange) { [weak self] in self?.endExchange(id) }
            }
        }
    }

    // MARK: requests

    private var isAvailable: Bool { link.isActivated && link.isPaired && link.isWatchAppInstalled }

    /// The request id, readable even when the request itself isn't.
    private struct IDProbe: Decodable {
        struct Message: Decodable { var id: UUID? }
        var message: Message
    }

    /// Answers a request at once, from cache or with `accepted`; never waits on the network.
    public func answer(_ data: Data) -> Data {
        guard isAvailable, let header = WatchEnvelope.header(of: data) else { return Data() }
        let id = (try? JSONDecoder().decode(IDProbe.self, from: data))?.message.id ?? UUID()
        func reply(_ response: WatchResponse) -> Data { encode(.response(id: id, response)) ?? Data() }
        guard WatchCompatibility.check(header, on: .phone) == nil,
              let envelope = try? WatchEnvelope.decode(data), case let .request(_, request) = envelope.message
        else { return reply(.failed(.needsUpdate)) }
        return reply(handle(request))
    }

    private func handle(_ request: WatchRequest) -> WatchResponse {
        let scope: WatchScope
        switch request {
        case .hello:
            if let store = accounts.currentStore { startLease(on: store) }
            return .snapshot(makeSnapshot())
        case let .renew(s, _), let .chat(s, _), let .send(s, _, _, _), let .respond(s, _, _, _):
            scope = s
        case let .close(s):
            endLease() // whatever the scope: the watch is leaving
            scope = s
        }
        guard let store = accounts.currentStore else {
            publish(makeSnapshot(), force: true)
            return .failed(.noComputer)
        }
        guard scope == self.scope(of: store) else {
            publish(makeSnapshot(), force: true)
            return .failed(.staleScope)
        }
        switch request {
        case .hello:
            return .accepted
        case let .renew(_, open):
            startLease(on: store)
            // No lease: the link isn't being held, so nothing here would stay current.
            guard lease != nil else { return .failed(.unavailable) }
            setOpen(open.flatMap { store.bots[$0] == nil ? nil : $0 }, on: store)
            refresh()
            return .snapshot(makeSnapshot())
        case let .chat(_, botId):
            guard let bot = store.bots[botId] else { return .failed(.notFound) }
            startLease(on: store)
            guard lease != nil else { return .failed(.unavailable) }
            setOpen(botId, on: store)
            let chat = buildChat(store, bot)
            lastChat = chat
            refresh() // track the open chat's entries from now on
            return .chat(chat)
        case .close:
            endLease()
            return .accepted
        case let .send(_, botId, text, nonce):
            guard store.bots[botId] != nil else { return .failed(.notFound) }
            return send(text, nonce: nonce, botId: botId, on: store) ? .accepted : .failed(.unavailable)
        case let .respond(_, botId, entryId, optionId):
            guard store.bots[botId] != nil else { return .failed(.notFound) }
            switch respond(entryId: entryId, option: optionId, botId: botId, on: store) {
            case .done: return .accepted
            case .unknownCard: return .failed(.notFound)
            case .cannotHold: return .failed(.unavailable)
            }
        }
    }

    /// false: nothing was done because the phone can't hold the link in the background.
    private func send(_ text: String, nonce: String, botId: String, on store: BotStore) -> Bool {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return true }
        let entries = store.allEntries(botId)
        // Delivered, or on its way: a replayed request changes nothing. Only a failed send retries.
        if entries.contains(where: { $0.data.clientNonce == nonce && !$0.id.hasPrefix("local-") }) { return true }
        let local = entries.first(where: { $0.id == "local-\(nonce)" })
        if let local, local.data.status != "failed" { return true }
        // The assertion comes first: a send the phone can't stay awake for isn't started.
        if let existing = exchanges.values.first(where: { $0.key == nonce }) {
            // A Resend while the first attempt's exchange lives: send again and wait the full time.
            guard let local else { return true }
            guard canHold(for: durations.exchange) else { return false }
            store.retry(local)
            exchanges[existing.id]?.expiry?.cancel()
            exchanges[existing.id]?.expiry = timer(durations.exchange) { [weak self] in self?.endExchange(existing.id) }
            return true
        }
        guard canHold(for: durations.exchange) else { return false }
        if let local { store.retry(local) } else { store.send(text, to: botId, thread: nil, nonce: nonce) }
        startExchange(on: store, botId: botId, key: nonce, nonce: nonce, card: nil)
        return true
    }

    private enum RespondResult { case done, unknownCard, cannotHold }

    private func respond(entryId: String, option: String, botId: String, on store: BotStore) -> RespondResult {
        guard !exchanges.values.contains(where: { $0.key == entryId }) else { return .done }
        guard canHold(for: durations.exchange) else { return .cannotHold }
        if let card = store.chat(botId).first(where: { $0.id == entryId }) {
            store.respond(card, option: option)
            startExchange(on: store, botId: botId, key: entryId, nonce: nil, card: (card.seq, card.turn))
            return .done
        }
        guard !store.isLive else { return .unknownCard }
        // The card may be in the catch-up still on its way.
        guard let id = startExchange(on: store, botId: botId, key: entryId, nonce: nil, card: nil) else { return .cannotHold }
        exchanges[id]?.retry = Task { [weak self, wait = durations.respondRetry] in
            let end = ContinuousClock.now + wait
            while ContinuousClock.now < end {
                try? await Task.sleep(for: .milliseconds(20))
                guard !Task.isCancelled, let self, let exchange = exchanges[id] else { return }
                guard exchange.store.isLive else { continue }
                if let card = exchange.store.chat(botId).first(where: { $0.id == entryId }) {
                    exchange.store.respond(card, option: option)
                    exchanges[id]?.card = (card.seq, card.turn)
                } else {
                    endExchange(id)
                }
                return
            }
            self?.endExchange(id)
        }
        return .done
    }

    // MARK: lease

    private func startLease(on store: BotStore) {
        if let current = lease, current.store !== store { endLease() }
        if lease == nil {
            guard canHold(for: durations.lease) else { return }
            let id = UUID()
            store.beginHold(id, botId: nil, end: { [weak self] in self?.leaseEnded(id) })
            lease = Lease(id: id, store: store)
        }
        lease?.expiry?.cancel()
        lease?.expiry = timer(durations.lease) { [weak self] in self?.endLease() }
    }

    private func setOpen(_ botId: String?, on store: BotStore) {
        guard var current = lease, current.open?.botId != botId else { return }
        if let open = current.open { store.setReading(open.token, botId: open.botId, thread: nil, active: false) }
        current.open = botId.map { (UUID(), $0) }
        if let open = current.open { store.setReading(open.token, botId: open.botId, thread: nil, active: true) }
        lease = current
        lastChat = nil
    }

    private func endLease() {
        guard let current = lease else { return }
        lease = nil
        current.expiry?.cancel()
        if let open = current.open { current.store.setReading(open.token, botId: open.botId, thread: nil, active: false) }
        release(current.id, on: current.store)
    }

    /// The store was retired: its hold is already gone.
    private func leaseEnded(_ id: UUID) {
        guard lease?.id == id else { return }
        lease?.expiry?.cancel()
        let store = lease?.store
        lease = nil
        if let store { awaitClosing(store) }
    }

    // MARK: exchanges

    @discardableResult
    private func startExchange(on store: BotStore, botId: String, key: String, nonce: String?, card: (seq: Int64, turn: Int64)?) -> UUID? {
        guard !exchanges.values.contains(where: { $0.key == key }), canHold(for: durations.exchange) else { return nil }
        let id = UUID()
        store.beginHold(
            id, botId: botId,
            reply: { [weak self] entry in self?.replied(id, entry) },
            needsInput: { [weak self] bot in self?.needsInput(id, bot) },
            settled: { [weak self] _ in self?.settled(id) },
            end: { [weak self] in self?.exchangeEnded(id) }
        )
        let expiry = timer(durations.exchange) { [weak self] in self?.endExchange(id) }
        exchanges[id] = Exchange(id: id, store: store, botId: botId, key: key, nonce: nonce, card: card, expiry: expiry)
        return id
    }

    private func endExchange(_ id: UUID) {
        guard let exchange = exchanges.removeValue(forKey: id) else { return }
        exchange.expiry?.cancel()
        exchange.retry?.cancel()
        exchange.grace?.cancel()
        release(id, on: exchange.store)
    }

    /// The store was retired: its hold is already gone.
    private func exchangeEnded(_ id: UUID) {
        guard let exchange = exchanges.removeValue(forKey: id) else { return }
        exchange.expiry?.cancel()
        exchange.retry?.cancel()
        exchange.grace?.cancel()
        awaitClosing(exchange.store)
    }

    /// The host's entry of a send exchange's message, looked up fresh: its status changes.
    private func message(of exchange: Exchange) -> Entry? {
        guard let nonce = exchange.nonce else { return nil }
        return exchange.store.allEntries(exchange.botId).first { $0.data.clientNonce == nonce && !$0.id.hasPrefix("local-") }
    }

    /// A reply to a send: after the message and not from an earlier turn still running (a message
    /// sent to a busy bot gets the next turn number). A reply to an answered card: its turn or later
    /// (the turn's final text can precede the card).
    private func answers(_ entry: Entry, _ exchange: Exchange) -> Bool {
        if let card = exchange.card { return entry.turn >= card.turn }
        guard let message = message(of: exchange) else { return false }
        return entry.seq > message.seq && entry.turn >= message.turn
    }

    private func replied(_ id: UUID, _ entry: Entry) {
        guard let exchange = exchanges[id], let bot = exchange.store.bots[exchange.botId], answers(entry, exchange) else { return }
        forward(bot: bot, store: exchange.store)
    }

    private func needsInput(_ id: UUID, _ bot: Bot) {
        guard let exchange = exchanges[id] else { return }
        forward(bot: bot, store: exchange.store)
    }

    /// The bot went idle. The host does that after every turn, so a send only settles once its
    /// message has started a turn (`status == "sent"`); a respond, once the card was answered.
    /// A group's status follows its busy member and goes idle between members: it settles after a grace.
    private func settled(_ id: UUID) {
        guard let exchange = exchanges[id] else { return }
        if exchange.nonce != nil { guard message(of: exchange)?.data.status == "sent" else { return } }
        else { guard exchange.card != nil else { return } }
        guard exchange.store.bots[exchange.botId]?.isGroup == true else { return endExchange(id) }
        exchanges[id]?.grace?.cancel()
        exchanges[id]?.grace = Task { [weak self, grace = durations.groupGrace] in
            try? await Task.sleep(for: grace)
            guard !Task.isCancelled, let self, let exchange = exchanges[id] else { return }
            if exchange.store.bots[exchange.botId]?.isWorking != true { endExchange(id) }
        }
    }

    /// Reachable: push the chat. Otherwise (or when the push fails) queue it for the watch's next
    /// launch. No alert of our own: while only a watch holds the link the host keeps pushing, and
    /// iOS mirrors that to the watch while the phone is locked.
    private func forward(bot: Bot, store: BotStore) {
        guard accounts.currentStore === store, let data = encode(.chat(buildChat(store, bot))) else { return }
        push(data, botId: bot.id, store: store)
    }

    private func push(_ data: Data, botId: String, store: BotStore) {
        let queue: @MainActor () -> Void = { [weak self] in
            guard let self, accounts.currentStore === store else { return }
            link.transferUserInfo(data, botId: botId)
        }
        if link.isReachable { link.sendMessage(data, onFailure: queue) } else { queue() }
    }

    private func endAll() {
        endLease()
        for id in Array(exchanges.keys) { endExchange(id) }
    }

    // MARK: background time

    private func prepareBackground() -> Bool {
        guard !system.isAppActive, backgroundTask == nil else { return true }
        backgroundTask = system.beginBackgroundTask { [weak self] in self?.expired() }
        return backgroundTask != nil
    }

    /// Ends the assertion once nothing holds a link and every shutdown has left.
    private func finishBackground() {
        guard lease == nil, exchanges.isEmpty, releasing == 0, let task = backgroundTask else { return }
        backgroundTask = nil
        system.endBackgroundTask(task)
    }

    /// A retired store closed its link on its own: the assertion waits for that too.
    private func awaitClosing(_ store: BotStore) {
        guard let closing = store.closing else { return finishBackground() }
        releasing += 1
        Task {
            await closing.value
            releasing -= 1
            finishBackground()
        }
    }

    private func release(_ id: UUID, on store: BotStore) {
        guard let closing = store.releaseHold(id) else { return finishBackground() }
        releasing += 1
        Task {
            await closing.value
            releasing -= 1
            finishBackground()
        }
    }

    /// The system's time is up: release everything now (no awaiting), then end the assertion.
    private func expired() {
        let ending = lease
        lease = nil
        if let ending {
            ending.expiry?.cancel()
            if let open = ending.open { ending.store.setReading(open.token, botId: open.botId, thread: nil, active: false) }
            ending.store.releaseHold(ending.id)
        }
        for exchange in exchanges.values {
            exchange.expiry?.cancel()
            exchange.retry?.cancel()
            exchange.grace?.cancel()
            exchange.store.releaseHold(exchange.id)
        }
        exchanges = [:]
        if let task = backgroundTask {
            backgroundTask = nil
            system.endBackgroundTask(task)
        }
    }

    /// `base`, or less in the background: the system's remaining time minus the margin.
    private func budget(_ base: Duration) -> Duration {
        // UIKit reports DBL_MAX (not infinity) while it has no limit: clamp in Double.
        let seconds = min(base.seconds, system.backgroundTimeRemaining - durations.margin.seconds)
        return seconds < base.seconds ? .seconds(max(0, seconds)) : base
    }

    /// The assertion is secured and there is time left to hold for `base`.
    private func canHold(for base: Duration) -> Bool { prepareBackground() && budget(base) > .zero }

    private func timer(_ base: Duration, _ action: @escaping @MainActor () -> Void) -> Task<Void, Never> {
        let wait = budget(base)
        return Task {
            try? await Task.sleep(for: wait)
            if !Task.isCancelled { action() }
        }
    }

    // MARK: state

    private func scope(of store: BotStore) -> WatchScope {
        WatchScope(context: accounts.storage.id, computer: store.computer.id)
    }

    /// The iPhone's `connectionLabel` wording as a state.
    private func state(of store: BotStore) -> WatchComputerState {
        switch store.shownConnection {
        case .online: store.mismatch != nil ? .needsUpdate : .online
        case .connecting: .connecting
        case .computerOffline, .offline: .offline
        case .unauthorized: .noAccess
        case .unpaired: .notPaired
        }
    }

    private func makeSnapshot() -> WatchSnapshot {
        _ = accounts.computers
        _ = accounts.selection
        guard let store = accounts.currentStore else {
            return .build(scope: nil, computerName: nil, state: .notPaired, fresh: false, roster: [], all: [:], now: .now)
        }
        return .build(scope: scope(of: store), computerName: store.hostName, state: state(of: store), fresh: store.isLive,
                      roster: store.roster, all: store.bots, now: .now)
    }

    private func buildChat(_ store: BotStore, _ bot: Bot) -> WatchChat {
        .build(scope: scope(of: store), bot: bot, entries: store.chat(bot.id), answering: store.answering, fresh: store.isLive, now: .now)
    }

    /// The chat open on the watch.
    private func makeChat() -> WatchChat? {
        guard let open = lease?.open, let store = accounts.currentStore, lease?.store === store, let bot = store.bots[open.botId] else { return nil }
        return buildChat(store, bot)
    }

    private func encode(_ message: WatchMessage) -> Data? { try? WatchEnvelope.fromPhone(message).encode() }

    // MARK: observation

    /// Rebuilds what the watch shows under observation tracking and sends what changed.
    private func refresh() {
        guard isAvailable else {
            lastSnapshot = nil
            lastChat = nil
            endAll()
            return
        }
        generation += 1
        let current = generation
        let (snapshot, chat) = withObservationTracking {
            (makeSnapshot(), makeChat())
        } onChange: { [weak self] in
            Task { @MainActor in self?.changed(current) }
        }
        publish(snapshot)
        if let chat, link.isReachable, !Self.same(lastChat, chat), let data = encode(.chat(chat)) {
            lastChat = chat
            if let store = accounts.currentStore { push(data, botId: chat.botId, store: store) }
        }
        for exchange in Array(exchanges.values) where exchange.store.isOffline { endExchange(exchange.id) }
    }

    private func changed(_ observed: Int) {
        guard observed == generation, pending == nil else { return }
        pending = Task { [coalesce = durations.coalesce] in
            try? await Task.sleep(for: coalesce)
            guard !Task.isCancelled else { return }
            pending = nil
            refresh()
        }
    }

    private func publish(_ snapshot: WatchSnapshot, force: Bool = false) {
        guard force || !Self.same(lastSnapshot, snapshot), let data = encode(.snapshot(snapshot)) else { return }
        lastSnapshot = snapshot
        link.updateApplicationContext(data)
    }

    /// Equal apart from `builtAt`, which always differs.
    private static func same(_ old: WatchSnapshot?, _ new: WatchSnapshot) -> Bool {
        guard var old else { return false }
        old.builtAt = new.builtAt
        return old == new
    }

    private static func same(_ old: WatchChat?, _ new: WatchChat) -> Bool {
        guard var old else { return false }
        old.builtAt = new.builtAt
        return old == new
    }
}

private extension Duration {
    var seconds: Double { Double(components.seconds) + Double(components.attoseconds) / 1e18 }
}
