import Foundation

/// What the watch knows: the phone's latest snapshot and chats, plus the sends and approval
/// taps it made itself that the phone hasn't confirmed yet. Pure state; `WatchStore` wraps it.
public struct WatchMirror: Sendable, Equatable {
    /// A message the watch sent: shown at once as a local entry (`local-<nonce>`) until the host's own arrives.
    public struct PendingSend: Sendable, Equatable {
        public var botId: String
        public var entry: Entry

        public var nonce: String { entry.data.clientNonce ?? "" }
    }

    /// How long a tapped option waits for the card to settle.
    public static let answeringTimeout: TimeInterval = 25

    public private(set) var scope: WatchScope?
    public private(set) var snapshot: WatchSnapshot?
    public private(set) var chats: [String: WatchChat] = [:]
    public private(set) var pending: [PendingSend] = []
    private var tapped: [String: Tap] = [:]

    private struct Tap: Sendable, Equatable {
        var optionId: String
        var at: Date
    }

    public init() {}

    // MARK: Phone state

    /// Applies a snapshot unless it is older than the held one (all `builtAt` come from the
    /// phone's clock, so this orders scopes too). Another scope replaces everything.
    public mutating func apply(snapshot new: WatchSnapshot) {
        if let held = snapshot, new.builtAt < held.builtAt { return }
        if new.scope != scope { reset(to: new.scope) }
        snapshot = new
    }

    /// Applies a chat for the current scope unless older than the held one. Only a snapshot
    /// changes the scope, so a chat queued before a sign-out or account switch is dropped.
    public mutating func apply(chat new: WatchChat) {
        guard scope != nil, new.scope == scope else { return }
        if let held = chats[new.botId], new.builtAt < held.builtAt { return }
        chats[new.botId] = new
        let confirmed = Set(new.entries.compactMap(\.data.clientNonce)).union(new.recentNonces)
        pending.removeAll { $0.botId == new.botId && confirmed.contains($0.nonce) }
        for entry in new.entries where entry.kind == "permission" && entry.data.status != "pending" {
            tapped[entry.id] = nil
        }
    }

    private mutating func reset(to scope: WatchScope?) {
        self = WatchMirror()
        self.scope = scope
    }

    // MARK: Sends

    /// Shows `text` as sending. Repeating a nonce changes nothing.
    public mutating func addPending(botId: String, text: String, nonce: String, now: Date) {
        guard !pending.contains(where: { $0.nonce == nonce }) else { return }
        let entry = Entry(
            id: "local-\(nonce)", seq: Int64.max, botId: botId, rev: 0, kind: "user", turn: 0,
            data: EntryData(text: text, status: "sending", clientNonce: nonce),
            createdAt: Int64(now.timeIntervalSince1970 * 1000), updatedAt: 0
        )
        pending.append(PendingSend(botId: botId, entry: entry))
    }

    public mutating func markFailed(nonce: String) { setStatus("failed", nonce: nonce) }

    /// Back to sending with the same nonce, so the phone and host can drop a repeat.
    public mutating func resend(nonce: String) { setStatus("sending", nonce: nonce) }

    private mutating func setStatus(_ status: String, nonce: String) {
        guard let i = pending.firstIndex(where: { $0.nonce == nonce }) else { return }
        pending[i].entry.data.status = status
    }

    /// The chat as shown: the host's entries, then sends it hasn't confirmed.
    public func entries(for botId: String) -> [Entry] {
        let host = chats[botId]?.entries ?? []
        let confirmed = Set(host.compactMap(\.data.clientNonce)).union(chats[botId]?.recentNonces ?? [])
        return host + pending.filter { $0.botId == botId && !confirmed.contains($0.nonce) }.map(\.entry)
    }

    // MARK: Approvals

    /// The option answered for `entryId`: the watch's own tap, else the phone's in-flight one.
    public func answering(entryId: String, botId: String) -> String? {
        tapped[entryId]?.optionId ?? chats[botId]?.answering[entryId]
    }

    /// Records a tap. One answer per card: later taps while one is out change nothing.
    public mutating func setAnswering(entryId: String, optionId: String, now: Date) {
        guard tapped[entryId] == nil else { return }
        tapped[entryId] = Tap(optionId: optionId, at: now)
    }

    /// Forgets a tap the phone never took on, so the card can be answered again.
    public mutating func clearAnswering(entryId: String) { tapped[entryId] = nil }

    /// Some tap is old enough for `expireAnswering` to forget it.
    public func hasExpiredAnswering(now: Date) -> Bool {
        tapped.values.contains { now.timeIntervalSince($0.at) >= Self.answeringTimeout }
    }

    /// Forgets taps whose card never settled.
    public mutating func expireAnswering(now: Date) {
        tapped = tapped.filter { now.timeIntervalSince($0.value.at) < Self.answeringTimeout }
    }
}
