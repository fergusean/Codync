import Foundation
import Testing
@testable import CodyncKit

private let scope = WatchScope(context: "local", computer: "c1")
private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

private func bot(_ id: String, kind: String = "agent", members: [String] = [], hidden: Bool = false, long: Int = 0) throws -> Bot {
    let text = String(repeating: "x", count: long)
    let json: [String: Any] = [
        "id": id, "kind": kind, "members": members, "name": "Bot \(id)" + text, "hidden": hidden,
        "description": "secret description", "cwd": "/Users/me/secret", "command": "run --token",
        "model": "opus", "connectors": ["gh"], "skills": ["s"],
        "status": "working", "activity": text.isEmpty ? "Reading" : text,
        "lastMessage": text.isEmpty ? "hi" : text, "unread": 2,
    ]
    return try JSONDecoder().decode(Bot.self, from: JSONSerialization.data(withJSONObject: json))
}

private func entry(_ id: String, seq: Int64 = 1, kind: String = "agent", final: Bool? = true, text: String = "hello") -> Entry {
    var data = EntryData(text: text)
    data.final = final
    return Entry(id: id, seq: seq, botId: "b1", rev: seq, kind: kind, turn: 1, data: data, createdAt: seq * 1000, updatedAt: seq * 1000)
}

private func roundTrip(_ message: WatchMessage) throws -> WatchMessage {
    let envelope = WatchEnvelope(version: "2.8.0", minPeer: "2.7.1", message: message)
    let decoded = try WatchEnvelope.decode(envelope.encode())
    #expect(decoded.version == "2.8.0" && decoded.minPeer == "2.7.1")
    return decoded.message
}

// MARK: Envelope

@Test func envelopeRoundTripsEveryCase() throws {
    let id = UUID()
    let snapshot = WatchSnapshot(scope: scope, computerName: "Mac", state: .online, fresh: true, bots: [try bot("a")], builtAt: t0)
    let chat = WatchChat(scope: scope, botId: "a", bot: try bot("a"), entries: [entry("e1")], answering: ["e1": "o1"], fresh: false, builtAt: t0)
    let requests: [WatchRequest] = [
        .hello, .renew(scope, open: nil), .renew(scope, open: "a"), .chat(scope, botId: "a"),
        .send(scope, botId: "a", text: "hi", nonce: "n"), .respond(scope, botId: "a", entryId: "e", optionId: "o"), .close(scope),
    ]
    var messages: [WatchMessage] = requests.map { .request(id: id, $0) }
    messages += [.response(id: id, .snapshot(snapshot)), .response(id: id, .chat(chat)), .response(id: id, .accepted)]
    messages += WatchFailureCases.all.map { .response(id: id, .failed($0)) }
    messages += [.snapshot(snapshot), .chat(chat)]
    for message in messages { #expect(try roundTrip(message) == message) }
}

private enum WatchFailureCases {
    static let all: [WatchFailure] = [.staleScope, .noComputer, .notFound, .needsUpdate, .unavailable, .unknown]
}

@Test func unknownFieldsAreIgnoredAndUnknownCasesFail() throws {
    let json = """
    {"version":"3.0.0","minPeer":"2.7.1","future":1,
     "message":{"type":"request","id":"\(UUID())","future":true,"request":{"type":"chat","botId":"a","extra":[1],"scope":{"context":"local","computer":"c1","x":0}}}}
    """
    let decoded = try WatchEnvelope.decode(Data(json.utf8))
    guard case let .request(_, .chat(s, botId)) = decoded.message else { Issue.record("wrong message"); return }
    #expect(s == scope && botId == "a")

    let unknown = #"{"version":"3.0.0","minPeer":"2.7.1","message":{"type":"teleport"}}"#
    #expect(throws: DecodingError.self) { try WatchEnvelope.decode(Data(unknown.utf8)) }
    // The header is still readable, so the receiver can say which side must update.
    #expect(WatchEnvelope.header(of: Data(unknown.utf8)) == .init(version: "3.0.0", minPeer: "2.7.1"))
}

@Test func unknownStatesReadAsUnknownNotNeedsUpdate() throws {
    let json = #"{"state":"quantum","fresh":true,"bots":[],"faces":[],"builtAt":0}"#
    #expect(try JSONDecoder().decode(WatchSnapshot.self, from: Data(json.utf8)).state == .unknown)
    #expect(try JSONDecoder().decode(WatchFailure.self, from: Data(#""wat""#.utf8)) == .unknown)
}

@Test func versionFloorsNameTheOlderSide() {
    let floor = WatchCompatibility.minWatch
    // Phone receives from a too-old watch.
    #expect(WatchCompatibility.check(.init(version: "2.0.0", minPeer: "2.0.0"), on: .phone, version: "2.7.1") == .updateWatch(minimum: floor))
    // Phone receives from a watch that needs a newer phone.
    #expect(WatchCompatibility.check(.init(version: "9.0.0", minPeer: "9.0.0"), on: .phone, version: "2.7.1") == .updatePhone(minimum: "9.0.0"))
    // Watch receives from a too-old phone, and from a phone that wants a newer watch.
    #expect(WatchCompatibility.check(.init(version: "2.0.0", minPeer: "2.0.0"), on: .watch, version: "2.7.1") == .updatePhone(minimum: WatchCompatibility.minPhone))
    #expect(WatchCompatibility.check(.init(version: "9.0.0", minPeer: "9.0.0"), on: .watch, version: "2.7.1") == .updateWatch(minimum: "9.0.0"))
    #expect(WatchCompatibility.check(.init(version: "2.7.1", minPeer: "2.7.1"), on: .phone, version: "2.7.1") == nil)
    // An unreadable own version (tests, previews) never blocks.
    #expect(WatchCompatibility.check(.init(version: "2.7.1", minPeer: "2.7.1"), on: .watch, version: "") == nil)
}

// MARK: Snapshot

@Test func snapshotKeepsOrderCapsAndScrubs() throws {
    let roster = try (0..<60).map { try bot("b\($0)") }
    let snapshot = WatchSnapshot.build(scope: scope, computerName: "Mac", state: .online, fresh: true,
                                       roster: roster, all: [:], now: t0)
    #expect(snapshot.bots.map(\.id) == roster.prefix(40).map(\.id))
    for b in snapshot.bots {
        #expect(b.cwd.isEmpty && b.description.isEmpty && b.command == nil && b.model == nil)
        #expect(b.connectors.isEmpty && b.skills.isEmpty)
        #expect(b.unread == 2 && b.status == "working")
    }
    let encoded = try WatchEnvelope.fromPhone(.snapshot(snapshot)).encode()
    for secret in ["secret description", "/Users/me/secret", "run --token"] {
        #expect(!String(decoding: encoded, as: UTF8.self).contains(secret))
    }
}

@Test func snapshotTruncatesLongText() throws {
    let snapshot = WatchSnapshot.build(scope: scope, computerName: nil, state: .online, fresh: true,
                                       roster: [try bot("a", long: 500)], all: [:], now: t0)
    #expect(snapshot.bots[0].lastMessage?.count == 140)
    #expect(snapshot.bots[0].activity.count == 80)
}

@Test func snapshotIncludesHiddenGroupMembersAsFaces() throws {
    let group = try bot("g", kind: "group", members: ["h1", "h2", "h3", "a"])
    let a = try bot("a")
    let all = Dictionary(uniqueKeysWithValues: try [a, group, bot("h1", hidden: true), bot("h2", hidden: true), bot("h3", hidden: true)].map { ($0.id, $0) })
    let snapshot = WatchSnapshot.build(scope: scope, computerName: nil, state: .online, fresh: true,
                                       roster: [group, a], all: all, now: t0)
    #expect(snapshot.bots.map(\.id) == ["g", "a"])
    #expect(snapshot.faces.map(\.id) == ["h1", "h2", "h3"])
}

@Test func budgetDropsFacesBeforeRoster() throws {
    let members = (0..<60).map { "h\($0)" }
    let group = try bot("g", kind: "group", members: members)
    let faces = try members.map { try bot($0, hidden: true, long: 2_000) }
    let all = Dictionary(uniqueKeysWithValues: (faces + [group]).map { ($0.id, $0) })
    let snapshot = WatchSnapshot.build(scope: scope, computerName: nil, state: .online, fresh: true,
                                       roster: [group], all: all, now: t0)
    #expect(snapshot.bots.map(\.id) == ["g"])
    #expect(snapshot.faces.count < 60 && snapshot.faces.map(\.id) == members.prefix(snapshot.faces.count).map { $0 })
    #expect(try WatchEnvelope.fromPhone(.snapshot(snapshot)).encode().count <= WatchLimits.envelopeBytes)
}

// MARK: Chat

@Test func chatKeepsOnlyChatEntriesAndTruncates() throws {
    var diffed = entry("perm", seq: 4, kind: "permission", final: nil)
    diffed.data.status = "pending"
    diffed.data.title = String(repeating: "t", count: 400)
    diffed.data.detail = "big"; diffed.data.output = "big"; diffed.data.command = "rm"; diffed.data.cwd = "/x"
    diffed.data.options = [try JSONDecoder().decode(PermissionOption.self, from: Data(#"{"optionId":"o","name":"Allow","kind":"allow_once"}"#.utf8))]
    var user = entry("u", seq: 5, kind: "user", final: nil, text: String(repeating: "y", count: 2000))
    user.data.attachments = [Attachment(id: "a1", name: "log.txt", size: 999)]
    let entries = [entry("t", kind: "thought", final: nil), entry("tool", kind: "tool", final: nil), entry("plan", kind: "plan", final: nil),
                   entry("streaming", seq: 2, final: false), entry("final", seq: 3), diffed, user]
    let chat = WatchChat.build(scope: scope, bot: try bot("b1"), entries: entries, answering: [:], fresh: true, now: t0)
    #expect(chat.entries.map(\.id) == ["final", "perm", "u"])
    let perm = chat.entries[1].data
    #expect(perm.title?.count == 300 && perm.detail == nil && perm.output == nil && perm.command == nil && perm.cwd == nil)
    #expect(perm.status == "pending" && perm.options?.first?.optionId == "o")
    #expect(chat.entries[2].data.text?.count == 1500)
    #expect(chat.entries[2].data.attachments == [Attachment(id: "a1", name: "log.txt", size: 0)])
    #expect(chat.bot.cwd.isEmpty)
}

@Test func botExchangeKeepsItsBotsWithoutItsText() throws {
    var notice = entry("m", seq: 6, kind: "notice", final: nil)
    let heading = "Asked Owen: " + String(repeating: "h", count: 3_000)
    notice.data.text = heading + "\nReply from Owen:\nreply"
    notice.data.heading = heading
    notice.data.delegationId = "d1"
    notice.data.sourceBotId = "b1"
    notice.data.targetBotId = "b2"
    notice.data.botMessage = BotMessage(sourceBotId: "b1", targetBotId: "b2", text: heading, reply: "reply")
    notice.data.status = "completed"
    let chat = WatchChat.build(scope: scope, bot: try bot("b1"), entries: [notice], answering: [:], fresh: true, now: t0)
    let kept = try #require(chat.entries.first?.data)
    #expect(kept.text == nil)
    #expect(kept.botMessage == BotMessage(sourceBotId: "b1", targetBotId: "b2", text: ""))
    #expect(kept.status == "completed")
    let exchange = try #require(BotExchange(chat.entries[0]))
    #expect(exchange.outcome == .done && exchange.reply == nil && exchange.peerId == "b2")
}

@Test func chatKeepsNewest25() throws {
    let entries = (1...40).map { entry("e\($0)", seq: Int64($0)) }
    let chat = WatchChat.build(scope: scope, bot: try bot("b1"), entries: entries, answering: [:], fresh: true, now: t0)
    #expect(chat.entries.map(\.id) == (16...40).map { "e\($0)" })
}

// MARK: Budget

@Test func envelopesStayWithinBudget() throws {
    let roster = try (0..<200).map { try bot("b\($0)", long: 2_000) }
    let snapshot = WatchSnapshot.build(scope: scope, computerName: "Mac", state: .online, fresh: true, roster: roster, all: [:], now: t0)
    #expect(try WatchEnvelope.fromPhone(.snapshot(snapshot)).encode().count <= WatchLimits.envelopeBytes)
    #expect(snapshot.bots.count < 40 && snapshot.bots.map(\.id) == roster.prefix(snapshot.bots.count).map(\.id))

    let entries = (1...25).map { i -> Entry in
        var e = entry("e\(i)", seq: Int64(i), text: String(repeating: "z", count: 5_000))
        e.data.title = String(repeating: "t", count: 5_000)
        return e
    }
    let chat = WatchChat.build(scope: scope, bot: try bot("b1"), entries: entries, answering: [:], fresh: true, now: t0)
    #expect(try WatchEnvelope.fromPhone(.chat(chat)).encode().count <= WatchLimits.envelopeBytes)
    #expect(chat.entries.count < 25 && chat.entries.last?.id == "e25")
}

// MARK: Mirror

private func snapshot(_ scope: WatchScope?, at seconds: Double) -> WatchSnapshot {
    WatchSnapshot(scope: scope, computerName: "Mac", state: .online, fresh: true, bots: [], builtAt: t0.addingTimeInterval(seconds))
}

private func chat(_ botId: String = "b1", scope: WatchScope = scope, entries: [Entry] = [], answering: [String: String] = [:], at seconds: Double = 0) throws -> WatchChat {
    WatchChat(scope: scope, botId: botId, bot: try bot(botId), entries: entries, answering: answering, fresh: true, builtAt: t0.addingTimeInterval(seconds))
}

@Test func mirrorResetsOnScopeChange() throws {
    var mirror = WatchMirror()
    mirror.apply(snapshot: snapshot(scope, at: 0))
    mirror.apply(chat: try chat())
    mirror.addPending(botId: "b1", text: "hi", nonce: "n", now: t0)
    var card = entry("p", kind: "permission", final: nil)
    card.data.status = "pending"
    mirror.apply(chat: try chat(entries: [card], at: 1))
    mirror.setAnswering(entryId: "p", optionId: "o", now: t0)

    let other = WatchScope(context: "abc", computer: "c1")
    // A reply built before the switch arrives late: older, so it can't bring the old scope back.
    mirror.apply(snapshot: snapshot(other, at: -100))
    #expect(mirror.scope == scope)

    mirror.apply(snapshot: snapshot(other, at: 100))
    #expect(mirror.scope == other)
    #expect(mirror.chats.isEmpty && mirror.pending.isEmpty)
    #expect(mirror.answering(entryId: "p", botId: "b1") == nil)

    // A chat queued for the old scope is dropped; only snapshots change the scope.
    mirror.apply(chat: try chat(scope: scope, at: 200))
    #expect(mirror.scope == other && mirror.snapshot?.scope == other && mirror.chats.isEmpty)

    mirror.apply(snapshot: snapshot(nil, at: 300))
    mirror.apply(chat: try chat(scope: other, at: 400))
    #expect(mirror.scope == nil && mirror.chats.isEmpty)
}

@Test func mirrorConfirmsSendsOlderThanTheWindow() throws {
    var mirror = WatchMirror()
    mirror.apply(snapshot: snapshot(scope, at: 0))
    mirror.addPending(botId: "b1", text: "hi", nonce: "n1", now: t0)
    var update = try chat(entries: [entry("e9")], at: 1)
    update.recentNonces = ["n1"]
    mirror.apply(chat: update)
    #expect(mirror.pending.isEmpty && mirror.entries(for: "b1").map(\.id) == ["e9"])
}

@Test func chatCarriesRecentNoncesBeyondItsEntries() throws {
    let users = (1...60).map { i -> Entry in
        var e = entry("u\(i)", seq: Int64(i), kind: "user", final: nil, text: "x")
        e.data.clientNonce = "n\(i)"
        return e
    }
    let built = WatchChat.build(scope: scope, bot: try bot("b1"), entries: users, answering: [:], fresh: true, now: t0)
    #expect(built.entries.count == 25)
    #expect(built.recentNonces == (11...60).map { "n\($0)" })
}

@Test func mirrorDropsStaleBuilds() throws {
    var mirror = WatchMirror()
    mirror.apply(snapshot: snapshot(scope, at: 10))
    mirror.apply(snapshot: snapshot(scope, at: 5))
    #expect(mirror.snapshot?.builtAt == t0.addingTimeInterval(10))
    mirror.apply(snapshot: snapshot(scope, at: 10))
    mirror.apply(chat: try chat(entries: [entry("new")], at: 10))
    mirror.apply(chat: try chat(entries: [entry("old")], at: 9))
    #expect(mirror.chats["b1"]?.entries.map(\.id) == ["new"])
    mirror.apply(chat: try chat(entries: [entry("same")], at: 10))
    #expect(mirror.chats["b1"]?.entries.map(\.id) == ["same"])
}

@Test func mirrorReplacesOptimisticEntryByNonce() throws {
    var mirror = WatchMirror()
    mirror.apply(snapshot: snapshot(scope, at: 0))
    mirror.apply(chat: try chat(entries: [entry("e1")]))
    mirror.addPending(botId: "b1", text: "hi", nonce: "n1", now: t0)
    mirror.addPending(botId: "b1", text: "hi", nonce: "n1", now: t0)
    #expect(mirror.entries(for: "b1").map(\.id) == ["e1", "local-n1"])
    #expect(mirror.entries(for: "b1").last?.data.status == "sending")

    var host = entry("e2", seq: 2, kind: "user", final: nil, text: "hi")
    host.data.clientNonce = "n1"
    mirror.apply(chat: try chat(entries: [entry("e1"), host], at: 1))
    #expect(mirror.entries(for: "b1").map(\.id) == ["e1", "e2"])
    #expect(mirror.pending.isEmpty)
}

@Test func mirrorResendKeepsNonce() throws {
    var mirror = WatchMirror()
    mirror.addPending(botId: "b1", text: "hi", nonce: "n1", now: t0)
    mirror.markFailed(nonce: "n1")
    #expect(mirror.entries(for: "b1").first?.data.status == "failed")
    mirror.resend(nonce: "n1")
    let resent = try #require(mirror.entries(for: "b1").first)
    #expect(resent.data.status == "sending" && resent.id == "local-n1" && resent.data.clientNonce == "n1")
    #expect(mirror.pending.count == 1)
}

@Test func mirrorClearsAnsweringWhenCardSettlesOrTimesOut() throws {
    var mirror = WatchMirror()
    mirror.apply(snapshot: snapshot(scope, at: 0))
    var card = entry("p", kind: "permission", final: nil)
    card.data.status = "pending"
    mirror.apply(chat: try chat(entries: [card]))
    mirror.setAnswering(entryId: "p", optionId: "o1", now: t0)
    mirror.setAnswering(entryId: "p", optionId: "o2", now: t0.addingTimeInterval(1))
    #expect(mirror.answering(entryId: "p", botId: "b1") == "o1")

    mirror.expireAnswering(now: t0.addingTimeInterval(24))
    #expect(mirror.answering(entryId: "p", botId: "b1") == "o1")
    mirror.expireAnswering(now: t0.addingTimeInterval(25))
    #expect(mirror.answering(entryId: "p", botId: "b1") == nil)

    mirror.setAnswering(entryId: "p", optionId: "o1", now: t0)
    card.data.status = "answered"
    mirror.apply(chat: try chat(entries: [card], at: 1))
    #expect(mirror.answering(entryId: "p", botId: "b1") == nil)
}

// MARK: Wording

@Test func chatPresentationWording() throws {
    #expect(ChatPresentation.permissionHeadline(toolKind: "execute") == "Wants to run a command")
    #expect(ChatPresentation.permissionHeadline(toolKind: "delete") == "Wants to change files")
    #expect(ChatPresentation.permissionHeadline(toolKind: "fetch") == "Wants to access the web")
    #expect(ChatPresentation.permissionHeadline(toolKind: "search") == "Wants to read files")
    #expect(ChatPresentation.permissionHeadline(toolKind: nil) == "Wants to use a tool")

    let kinds = ["reject_always", "allow_always", "custom", "reject_once", "allow_once"]
    let options = try kinds.map { try JSONDecoder().decode(PermissionOption.self, from: Data(#"{"optionId":"\#($0)","name":"Mine","kind":"\#($0)"}"#.utf8)) }
    let ordered = ChatPresentation.orderedOptions(options)
    #expect(ordered.map(\.kind) == ["allow_once", "allow_always", "reject_once", "reject_always", "custom"])
    #expect(ordered.map(ChatPresentation.optionLabel) == ["Allow once", "Always allow", "Deny", "Never", "Mine"])

    #expect(ChatPresentation.permissionOutcome(status: "answered", options: options, selected: "allow_once") == "Allowed once")
    #expect(ChatPresentation.permissionOutcome(status: "answered", options: options, selected: "allow_always") == "Always allowed")
    #expect(ChatPresentation.permissionOutcome(status: "answered", options: options, selected: "reject_once") == "Denied")
    #expect(ChatPresentation.permissionOutcome(status: "answered", options: options, selected: "reject_always") == "Never allowed")
    #expect(ChatPresentation.permissionOutcome(status: "answered", options: options, selected: "custom") == "Mine")
    #expect(ChatPresentation.permissionOutcome(status: "answered", options: nil, selected: nil) == "Answered")
    #expect(ChatPresentation.permissionOutcome(status: "cancelled", options: nil, selected: nil) == "Cancelled")
    #expect(ChatPresentation.permissionOutcome(status: "expired", options: nil, selected: nil) == "Expired — the agent moved on")

    #expect(SendState(status: "sending")?.label() == "Sending…")
    #expect(SendState(status: "queued")?.label() == "Queued")
    #expect(SendState(status: "queued")?.label(botWorking: true) == "Queued until this response finishes")
    #expect(SendState(status: "failed")?.label() == "Failed to send")
    #expect(SendState(status: "cancelled")?.label() == "Not sent — stopped")
    #expect(SendState(status: "waiting")?.label() == "Waiting for the computer to come online")
    #expect(SendState(status: "delivering")?.label() == "Delivered to the computer")
    #expect(SendState(status: "sent") == nil && SendState(status: nil) == nil)

    #expect([WatchComputerState.connecting, .online, .offline, .noAccess, .notPaired, .needsUpdate, .unknown].map(\.label)
        == ["Connecting…", "Connected", "Offline", "No access", "Not paired", "Needs update", "Update Codync on Apple Watch"])
}

@Test func mirrorForgetsATapThePhoneNeverTookOn() throws {
    var mirror = WatchMirror()
    mirror.apply(snapshot: snapshot(scope, at: 0))
    var card = entry("p", kind: "permission", final: nil)
    card.data.status = "pending"
    mirror.apply(chat: try chat(entries: [card]))
    mirror.setAnswering(entryId: "p", optionId: "o1", now: t0)
    #expect(!mirror.hasExpiredAnswering(now: t0.addingTimeInterval(24)))
    #expect(mirror.hasExpiredAnswering(now: t0.addingTimeInterval(25)))
    mirror.clearAnswering(entryId: "p")
    #expect(mirror.answering(entryId: "p", botId: "b1") == nil)
    mirror.setAnswering(entryId: "p", optionId: "o2", now: t0)
    #expect(mirror.answering(entryId: "p", botId: "b1") == "o2")
}
