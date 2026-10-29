import CodyncKit
import Foundation
import Observation
import WatchKit

/// Everything the watch screens show. A shell over `WatchMirror` (pure state, tested in
/// CodyncKit): it talks to the iPhone through `WatchSessionLink`, keeps the phone's lease
/// alive while the app is in front, and caches the last chats for the next launch.
@MainActor @Observable
final class WatchStore {
    /// Why the watch can't talk to the phone's current build.
    enum Update: Equatable {
        case mismatch(WatchMismatch)
        /// The phone sent something this watch app doesn't know.
        case newerPhone
    }

    private(set) var mirror = WatchMirror()
    private(set) var update: Update?
    /// The last time the phone answered or published.
    private(set) var lastContact: Date?
    /// The phone app is running but had nothing to answer with (empty reply).
    private(set) var phoneNotReady = false
    /// Bot ids pushed onto the navigation stack.
    var path: [String] = []
    /// The chat on screen, reported to the phone so it marks that chat read.
    private(set) var openBotId: String?
    private(set) var isActive = false
    /// Failed messages the phone was asked to retry, shown as sending until its next chat.
    private(set) var retrying: Set<String> = []

    @ObservationIgnored let link = WatchSessionLink()
    @ObservationIgnored private var loop: Task<Void, Never>?
    @ObservationIgnored private var expiry: Task<Void, Never>?
    @ObservationIgnored private var saver: Task<Void, Never>?
    @ObservationIgnored private var reachable: Task<Void, Never>?
    /// When the phone last granted a lease; `close` is only worth sending while it holds.
    @ObservationIgnored private var leaseGrantedAt: Date?
    /// A tapped notification's bot, held until the snapshot says which account it belongs to.
    @ObservationIgnored private var pendingOpen: (context: String, computerId: String, botId: String)?
    @ObservationIgnored private var cache: ChatCache?

    static let renewEvery: Duration = .seconds(10)
    /// A lease younger than this is still held by the phone.
    static let leaseWindow: TimeInterval = 20
    /// A banner for the open chat is silenced only while the last lease answer is this young
    /// (renewals come every 10 s).
    static let silenceWindow: TimeInterval = 12

    init() {
        cache = ChatCache.load()
        link.store = self
        link.activate()
        if let data = link.receivedContext { receive(data) }
    }

    // MARK: Reading state

    var snapshot: WatchSnapshot? { mirror.snapshot }

    /// Listed bots and group faces by id.
    var bots: [String: Bot] {
        guard let snapshot else { return [:] }
        return Dictionary((snapshot.faces + snapshot.bots).map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    }

    func bot(_ id: String) -> Bot? { bots[id] ?? mirror.chats[id]?.bot }

    func members(of bot: Bot) -> [Bot] {
        let lookup = bots
        return bot.members.compactMap { lookup[$0] }
    }

    func entries(for botId: String) -> [Entry] { mirror.entries(for: botId) }

    func answering(_ entry: Entry) -> String? { mirror.answering(entryId: entry.id, botId: entry.botId) }

    /// The phone can't be asked right now.
    var phoneOut: Bool { !link.isReachable || phoneNotReady }

    // MARK: Lifecycle

    /// The app came to the front or left it. In front: `hello`, then a renewal every 10 s.
    func setActive(_ active: Bool) {
        guard active != isActive else { return }
        isActive = active
        loop?.cancel()
        expiry?.cancel()
        reachable?.cancel()
        if active {
            loop = Task { [weak self] in
                await self?.hello()
                while !Task.isCancelled {
                    try? await Task.sleep(for: Self.renewEvery)
                    guard !Task.isCancelled else { return }
                    await self?.renew()
                }
            }
            expiry = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(5))
                    self?.expireAnswering()
                }
            }
        } else {
            closeLease()
        }
    }

    func linkBecameReachable() {
        guard isActive else { return }
        reachable?.cancel()
        reachable = Task { await hello() }
    }

    private func expireAnswering() {
        guard mirror.hasExpiredAnswering(now: .now) else { return }
        Motion.animate { mirror.expireAnswering(now: .now) }
    }

    /// The phone granted a lease. If the app left the front meanwhile, give it straight back.
    private func leaseGranted() {
        if isActive {
            leaseGrantedAt = .now
        } else if let scope = mirror.scope {
            Task { _ = await perform(.close(scope)) }
        }
    }

    private func closeLease() {
        defer { leaseGrantedAt = nil }
        guard let granted = leaseGrantedAt, Date.now.timeIntervalSince(granted) < Self.leaseWindow,
              let scope = mirror.scope else { return }
        Task { _ = await perform(.close(scope)) }
    }

    // MARK: Requests

    private func hello() async {
        let response = await perform(.hello)
        if case .snapshot? = response { leaseGranted() }
        guard isActive, !Task.isCancelled, let id = openBotId else { return }
        await loadChat(id)
    }

    private func renew() async {
        guard let scope = mirror.scope else { return await hello() }
        switch await perform(.renew(scope, open: openBotId)) {
        case .accepted?, .snapshot?: leaseGranted()
        default: break
        }
    }

    func loadChat(_ botId: String) async {
        guard let scope = mirror.scope else { return }
        switch await perform(.chat(scope, botId: botId)) {
        case .chat?: leaseGranted()
        case .failed(.notFound)?:
            // The bot is gone (deleted since the roster was built): leave its chat.
            path.removeAll { $0 == botId }
            if openBotId == botId { openBotId = nil }
        // `.unavailable`: the phone can't hold the link; the cached chat stays, marked out of date.
        default: break
        }
    }

    /// Sends one request and takes in what comes back. Nil when the phone gave no usable answer.
    private func perform(_ request: WatchRequest) async -> WatchResponse? {
        do {
            let response = try await link.request(request)
            phoneNotReady = false
            lastContact = .now
            switch response {
            case let .snapshot(snapshot): apply(snapshot: snapshot)
            case let .chat(chat): apply(chat: chat)
            case .accepted: break
            case let .failed(failure): handle(failure)
            }
            return response
        } catch LinkError.notReady {
            phoneNotReady = true
        } catch let LinkError.mismatch(mismatch) {
            update = .mismatch(mismatch)
        } catch {
            // Unreachable, timed out or garbled: the caller sees nil.
        }
        return nil
    }

    private func handle(_ failure: WatchFailure) {
        switch failure {
        case .staleScope, .noComputer: if isActive { Task { await hello() } }
        // The phone couldn't decode the request: the phone is the older side.
        case .needsUpdate: update = .mismatch(.updatePhone(minimum: WatchCompatibility.minPhone))
        case .unknown: update = .newerPhone
        case .notFound, .unavailable: break
        }
    }

    /// Whether `response` is the phone taking the request on.
    private func delivered(_ response: WatchResponse?) -> Bool {
        switch response {
        case .accepted?, .snapshot?, .chat?: true
        default: false
        }
    }

    // MARK: Actions

    /// Sends dictated text. It shows as sending at once; the nonce identifies it end to end,
    /// so a retry (Resend) can never create a second message.
    func send(_ text: String, to botId: String) {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        let nonce = UUID().uuidString
        Motion.animate { mirror.addPending(botId: botId, text: text, nonce: nonce, now: .now) }
        Task { await deliver(botId: botId, text: text, nonce: nonce) }
    }

    /// Retries a failed message with its own nonce. The failure may be the watch's (the phone never
    /// got it) or the phone's (its entry failed after the watch's send was confirmed); the phone
    /// retries an entry it already has and ignores one the host has.
    func resend(_ entry: Entry) {
        guard let nonce = entry.data.clientNonce else { return }
        Motion.animate {
            if mirror.pending.contains(where: { $0.nonce == nonce }) {
                mirror.resend(nonce: nonce)
            } else {
                retrying.insert(nonce)
            }
        }
        Task { await deliver(botId: entry.botId, text: entry.data.text ?? "", nonce: nonce) }
    }

    /// What a user message shows: the phone's status, or sending while a retry is out.
    func sendState(_ entry: Entry) -> SendState? {
        if let nonce = entry.data.clientNonce, retrying.contains(nonce) { return .sending }
        return SendState(status: entry.data.status)
    }

    private func deliver(botId: String, text: String, nonce: String) async {
        guard let scope = mirror.scope else { return fail(nonce) }
        let response = await perform(.send(scope, botId: botId, text: text, nonce: nonce))
        if !delivered(response) { fail(nonce) }
    }

    private func fail(_ nonce: String) {
        Motion.animate {
            retrying.remove(nonce)
            mirror.markFailed(nonce: nonce)
        }
    }

    /// Answers an approval card. One answer per card: the orb shows on the chosen option until it settles.
    func respond(to entry: Entry, optionId: String) {
        guard let scope = mirror.scope, answering(entry) == nil else { return }
        Motion.animate { mirror.setAnswering(entryId: entry.id, optionId: optionId, now: .now) }
        Task {
            let response = await perform(.respond(scope, botId: entry.botId, entryId: entry.id, optionId: optionId))
            // Not taken on: the card can be answered again.
            if !delivered(response) { Motion.animate { mirror.clearAnswering(entryId: entry.id) } }
        }
    }

    func openChat(_ botId: String) {
        openBotId = botId
        Task { await loadChat(botId) }
    }

    func closeChat(_ botId: String) {
        if openBotId == botId { openBotId = nil }
    }

    /// A tapped notification: open that bot if it belongs to the account and computer on screen.
    func open(context: String, computerId: String, botId: String) {
        guard let scope = mirror.scope else {
            pendingOpen = (context, computerId, botId)
            return
        }
        // A bot past the snapshot's cap is opened too: its chat is requested on appearing.
        guard scope.context == context, scope.computer == computerId else { return }
        path = [botId]
    }

    /// The chat of `botId` on `computerId` is on screen and the phone is keeping it current (it holds
    /// the link, answered a renewal lately, and both the roster and the chat are live), so its alerts
    /// needn't show. Otherwise the banner is the only way to hear about it.
    func isChatOpen(computerId: String, botId: String) -> Bool {
        guard isActive, openBotId == botId, mirror.scope?.computer == computerId,
              link.isReachable, !phoneNotReady,
              snapshot?.fresh == true, mirror.chats[botId]?.fresh == true,
              let granted = leaseGrantedAt else { return false }
        return Date.now.timeIntervalSince(granted) < Self.silenceWindow
    }

    // MARK: Phone state

    /// Takes in raw envelope data from the phone (snapshot or chat).
    func receive(_ data: Data) {
        guard let header = WatchEnvelope.header(of: data) else { return }
        if let mismatch = WatchCompatibility.check(header, on: .watch) {
            update = .mismatch(mismatch)
            return
        }
        guard let envelope = try? WatchEnvelope.decode(data) else {
            update = .newerPhone
            return
        }
        update = nil
        lastContact = .now
        switch envelope.message {
        case let .snapshot(snapshot): apply(snapshot: snapshot)
        case let .chat(chat): apply(chat: chat, pushed: true)
        case .request, .response: break
        }
    }

    private func apply(snapshot: WatchSnapshot) {
        let before = mirror.scope
        Motion.animate { mirror.apply(snapshot: snapshot) }
        if mirror.scope != before {
            saver?.cancel()
            retrying = []
            path = []
            openBotId = nil
            // Another account or computer: nothing of the old one stays on screen or on disk.
            // (The first snapshot after launch has no old scope; the cache is checked below.)
            if before != nil { ChatCache.wipe() }
        }
        if let held = cache {
            cache = nil
            if held.scope == mirror.scope {
                for chat in held.chats { mirror.apply(chat: chat) }
            } else {
                ChatCache.wipe()
            }
        }
        if let pending = pendingOpen {
            pendingOpen = nil
            open(context: pending.context, computerId: pending.computerId, botId: pending.botId)
        }
    }

    /// `pushed`: the phone sent it on its own, as opposed to answering a request.
    private func apply(chat: WatchChat, pushed: Bool = false) {
        let known = Set(finalReplies(in: chat.botId))
        let held = mirror.chats[chat.botId]
        Motion.animate { mirror.apply(chat: chat) }
        retrying = retrying.filter { nonce in !chat.entries.contains { $0.data.clientNonce == nonce } }
        // A new final reply in the chat on screen taps the wrist; catching up after a gap doesn't.
        if pushed, isActive, openBotId == chat.botId, held?.fresh == true, chat.fresh,
           finalReplies(in: chat.botId).contains(where: { !known.contains($0) }) {
            WKInterfaceDevice.current().play(.notification)
        }
        scheduleSave()
    }

    private func finalReplies(in botId: String) -> [String] {
        (mirror.chats[botId]?.entries ?? []).filter { $0.kind == "agent" && $0.data.final == true }.map(\.id)
    }

    private func scheduleSave() {
        guard let scope = mirror.scope else { return }
        saver?.cancel()
        saver = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            // The scope may have changed (and the file been wiped) while waiting.
            guard !Task.isCancelled, let self, mirror.scope == scope else { return }
            let chats = mirror.chats.values.sorted { $0.builtAt > $1.builtAt }.prefix(ChatCache.limit)
            ChatCache(scope: scope, chats: Array(chats)).save()
        }
    }
}

/// The last chats, kept in Caches as one JSON file so a cold launch shows something while
/// the phone is out of reach. Tied to one scope; another scope's chats are never shown.
struct ChatCache: Codable, Sendable {
    var scope: WatchScope
    var chats: [WatchChat]

    static let limit = 10

    private static var url: URL {
        URL.cachesDirectory.appending(path: "watch-chats.json", directoryHint: .notDirectory)
    }

    static func load() -> ChatCache? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        return try? decoder.decode(ChatCache.self, from: data)
    }

    func save() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        guard let data = try? encoder.encode(self) else { return }
        try? data.write(to: Self.url, options: .atomic)
    }

    static func wipe() { try? FileManager.default.removeItem(at: url) }
}
