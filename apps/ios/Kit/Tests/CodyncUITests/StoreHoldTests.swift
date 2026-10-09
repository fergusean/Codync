import CryptoKit
import Foundation
import Testing
@testable import CodyncKit
@testable import CodyncUI

@MainActor @Test func holdSendsAndReceivesInBackgroundWithoutViewUpdates() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.direct))
    let store = BotStore(computer: randomComputer("Mac"), clientKind: "ios", storage: storage) { fake }
    defer { store.retire() }
    store.setActive(true)
    #expect(await until { await fake.subscribed })
    await fake.emit(botEvent("b1", name: "Bot", rev: 1))
    #expect(await until { store.connection == .online })

    let call = UUID()
    var spoken: [String] = []
    store.beginHold(call, botId: "b1", reply: { spoken.append($0.data.text ?? "") })
    store.setActive(false)
    // Allow shutdown to run if backgrounding incorrectly scheduled one.
    try await Task.sleep(for: .milliseconds(30))
    store.send("spoken while backgrounded", to: "b1")
    #expect(await until { store.chat("b1").first?.id == "e1" })
    #expect(await fake.sent == ["spoken while backgrounded"])
    #expect(await fake.shutdownCount == 0)

    let reply = #"{"type":"entry","entry":{"id":"reply","seq":2,"botId":"b1","rev":2,"kind":"agent","turn":1,"data":{"text":"The answer","final":true},"createdAt":1,"updatedAt":1}}"#
    await fake.emit(reply)
    #expect(await until { spoken == ["The answer"] })
    await fake.emit(reply) // Event replays must not read the same reply twice.
    await fake.emit(#"{"type":"entry","entry":{"id":"thread","seq":3,"botId":"b1","threadId":"root","rev":3,"kind":"agent","turn":1,"data":{"text":"Thread answer","final":true},"createdAt":1,"updatedAt":1}}"#)
    await fake.emit(#"{"type":"entry","entry":{"id":"other","seq":4,"botId":"b2","rev":4,"kind":"agent","turn":1,"data":{"text":"Other bot","final":true},"createdAt":1,"updatedAt":1}}"#)
    #expect(await until { store.chat("b2").last?.id == "other" })
    #expect(spoken == ["The answer"])

    store.setActive(true)
    #expect(await fake.shutdownCount == 0)
    await store.endHold(call)
    #expect(await fake.shutdownCount == 0) // Foreground keeps the ordinary connection.
    store.setActive(false)
    #expect(await until { await fake.shutdownCount == 1 })
}

@MainActor @Test func lastHoldEndingDisconnectsBackgroundStore() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.relay))
    let store = BotStore(computer: randomComputer("Mac"), clientKind: "ios", storage: storage) { fake }
    defer { store.retire() }
    store.setActive(true)
    #expect(await until { await fake.subscribed })
    await fake.emit(botEvent("b1", name: "Bot", rev: 1))
    #expect(await until { store.connection == .online })
    let first = UUID(), second = UUID()
    store.beginHold(first, botId: "b1")
    store.beginHold(second, botId: nil) // A link-only hold coexists with a call.
    store.setActive(false)
    await store.endHold(first)
    try await Task.sleep(for: .milliseconds(30))
    #expect(await fake.shutdownCount == 0)
    await store.endHold(second)
    #expect(await fake.shutdownCount == 1)
}

@MainActor @Test func linkOnlyHoldKeepsTheTransportWhileInactive() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.relay))
    let store = BotStore(computer: randomComputer("Mac"), clientKind: "ios", storage: storage) { fake }
    defer { store.retire() }
    store.setActive(true)
    #expect(await until { await fake.subscribed })
    await fake.emit(#"{"type":"hello","hostId":"h1","rev":1}"#)
    await fake.emit(botEvent("b1", name: "Bot", rev: 1))
    #expect(await until { store.connection == .online && store.isLive })
    let hold = UUID()
    store.beginHold(hold, botId: nil)
    store.setActive(false)
    // Still connected; the events stream is resubscribed without a client the host counts as a
    // phone, so it is live again once that catch-up arrives.
    #expect(await until { await fake.eventClients == ["ios", nil] })
    #expect(await fake.shutdownCount == 0)
    #expect(!store.isLive)
    await fake.emit(#"{"type":"hello","hostId":"h1","rev":1}"#)
    #expect(await until { store.isLive })
    await store.endHold(hold)
    #expect(await fake.shutdownCount == 1)
    #expect(!store.isLive)
}

@MainActor @Test func endHoldWaitsForTheCloseFrame() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.relay))
    await fake.setShutdownDelay(.milliseconds(80))
    let store = BotStore(computer: randomComputer("Mac"), clientKind: "ios", storage: storage) { fake }
    defer { store.retire() }
    store.setActive(true)
    #expect(await until { await fake.subscribed })
    await fake.emit(botEvent("b1", name: "Bot", rev: 1))
    #expect(await until { store.connection == .online })
    let hold = UUID()
    store.beginHold(hold, botId: nil)
    store.setActive(false)
    await store.endHold(hold)
    #expect(await fake.shutdownFinished == 1)
}

@MainActor @Test func settledFiresOnceWhenAHeldBotGoesIdle() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.direct))
    let store = BotStore(computer: randomComputer("Mac"), clientKind: "ios", storage: storage) { fake }
    defer { store.retire() }
    store.setActive(true)
    #expect(await until { await fake.subscribed })
    await fake.emit(#"{"type":"hello","hostId":"h1","rev":1}"#)
    await fake.emit(#"{"type":"bot","bot":{"id":"b1","name":"Ada","rev":1,"lastAt":1}}"#)
    #expect(await until { store.connection == .online })
    var settled: [String] = []
    store.beginHold(UUID(), botId: "b1", settled: { settled.append($0) })
    await fake.emit(#"{"type":"bot","bot":{"id":"b1","name":"Ada","rev":2,"lastAt":2,"status":"working"}}"#)
    await fake.emit(#"{"type":"bot","bot":{"id":"b1","name":"Ada","rev":3,"lastAt":3,"status":"needsInput"}}"#)
    await fake.emit(#"{"type":"bot","bot":{"id":"b1","name":"Ada","rev":4,"lastAt":4,"status":"working"}}"#)
    await fake.emit(#"{"type":"bot","bot":{"id":"b1","name":"Ada","rev":5,"lastAt":5,"status":"idle"}}"#)
    await fake.emit(#"{"type":"bot","bot":{"id":"b1","name":"Ada","rev":6,"lastAt":6,"status":"idle"}}"#)
    await fake.emit(#"{"type":"bot","bot":{"id":"b2","name":"Other","rev":7,"lastAt":7,"status":"working"}}"#)
    await fake.emit(#"{"type":"bot","bot":{"id":"b2","name":"Other","rev":8,"lastAt":8,"status":"idle"}}"#)
    #expect(await until { store.bots["b2"]?.isWorking == false && store.bots["b1"]?.status == "idle" })
    #expect(settled == ["b1"])
}

@MainActor @Test func settleInsideAnEventsOnlyRestartCatchUpFires() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.direct))
    let store = BotStore(computer: randomComputer("Mac"), clientKind: "ios", storage: storage) { fake }
    defer { store.retire() }
    store.setActive(true)
    #expect(await until { await fake.subscribed })
    await fake.emit(#"{"type":"hello","hostId":"h1","rev":1}"#)
    await fake.emit(#"{"type":"bot","bot":{"id":"b1","name":"Ada","rev":1,"lastAt":1}}"#)
    #expect(await until { store.isLive })
    var settled: [String] = []
    store.beginHold(UUID(), botId: "b1", settled: { settled.append($0) })
    await fake.emit(#"{"type":"bot","bot":{"id":"b1","name":"Ada","rev":2,"lastAt":2,"status":"working"}}"#)
    #expect(await until { store.bots["b1"]?.isWorking == true })
    // Events-only restart: the working state came from this transport, so the new catch-up's idle counts.
    await fake.emit(#"{"type":"resync"}"#)
    #expect(await until { await fake.eventSubscriptionCount == 2 })
    #expect(!store.isLive)
    await fake.emit(#"{"type":"hello","hostId":"h1","rev":3}"#)
    await fake.emit(#"{"type":"bot","bot":{"id":"b1","name":"Ada","rev":3,"lastAt":3,"status":"idle"}}"#)
    #expect(await until { settled == ["b1"] })
}

@MainActor @Test func catchUpThatNeverReachesTheHelloRevStillGoesLiveWhenTheStreamGoesQuiet() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.direct))
    let store = BotStore(computer: randomComputer("Mac"), clientKind: "ios", storage: storage) { fake }
    defer { store.retire() }
    store.catchUpIdle = .milliseconds(150)
    store.setActive(true)
    #expect(await until { await fake.subscribed })
    // The host's rev is 10, but the bot that held it was deleted: a catch-up from 0 stops at 3.
    await fake.emit(#"{"type":"hello","hostId":"h1","rev":10}"#)
    await fake.emit(#"{"type":"bot","bot":{"id":"b1","name":"Ada","rev":3,"lastAt":3}}"#)
    #expect(await until { store.connection == .online })
    #expect(!store.isLive)
    #expect(await until { store.isLive && store.caughtUp })
}

@MainActor @Test func retiringStoreEndsHoldsAndPreventsLaterSends() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.direct))
    let store = BotStore(computer: randomComputer("Mac"), clientKind: "ios", storage: storage) { fake }
    store.setActive(true)
    #expect(await until { await fake.subscribed })
    await fake.emit(botEvent("b1", name: "Bot", rev: 1))
    #expect(await until { store.connection == .online })
    let call = UUID()
    var ended = false
    store.beginHold(call, botId: "b1", reply: { _ in Issue.record("Retired hold received a reply") }, end: { ended = true })
    store.setActive(false)
    store.retire()
    #expect(ended)
    #expect(await until { await fake.shutdownCount == 1 })
    store.send("must not send", to: "b1")
    store.beginHold(UUID(), botId: "b1")
    #expect(store.chat("b1").isEmpty)
    #expect(await fake.sent.isEmpty)
}

@MainActor @Test func offlineSendsWaitInTheMailbox() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.hostOffline(lastSeen: nil))
    let store = BotStore(computer: randomComputer("Mac"), clientKind: "ios", storage: storage) { fake }
    store.setActive(true)
    #expect(await until { store.connection == .computerOffline(lastSeen: nil) })
    #expect(store.canQueue && store.isOffline)

    store.send("hi", to: "b1")
    #expect(await until { await fake.enqueued.count == 1 })
    let waiting = try #require(store.chat("b1").first)
    #expect(waiting.data.status == "waiting")
    #expect(await fake.sent.isEmpty)

    // Cancelled before the computer saw it: gone.
    store.cancelQueued(waiting)
    #expect(await until { store.chat("b1").isEmpty })

    // Already handed over: it stays, marked delivered.
    store.send("second", to: "b1")
    #expect(await until { await fake.enqueued.count == 2 })
    await fake.setCancelResult(.delivering)
    let second = try #require(store.chat("b1").first)
    store.cancelQueued(second)
    #expect(await until { store.chat("b1").first?.data.status == "delivering" })

    // Expired in the mailbox: failed, can be resent.
    store.send("third", to: "b2")
    #expect(await until { await fake.enqueued.count == 3 })
    let third = try #require(store.chat("b2").first?.data.clientNonce)
    await fake.emit(.expired(nonce: third))
    #expect(await until { store.chat("b2").first?.data.status == "failed" })

    // Back online: sends go straight to the computer, and its copy replaces the local one.
    await fake.set(.ready(.relay))
    #expect(await until { await fake.subscribed })
    await fake.emit(botEvent("b1", name: "Rex", rev: 1))
    #expect(await until { store.connection == .online })
    store.send("live", to: "b3")
    #expect(await until { store.chat("b3").first?.id == "e1" })
    #expect(await fake.sent == ["live"])
    store.retire()
}

@MainActor @Test func callOperatorToolsDriveTheBot() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.direct))
    let store = BotStore(computer: randomComputer("Mac"), clientKind: "ios", storage: storage) { fake }
    defer { store.retire() }
    store.setActive(true)
    #expect(await until { await fake.subscribed })
    await fake.emit(#"{"type":"bot","bot":{"id":"b1","name":"Ada","rev":1,"lastAt":1}}"#)
    #expect(await until { store.connection == .online })
    let op = CallOperator(model: store, botId: "b1")

    func json(_ s: String) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: Data(s.utf8))) as? [String: Any] ?? [:]
    }

    #expect(json(op.call("send_to_bot", arguments: ["text": "  run the tests "]))["sent"] as? Bool == true)
    #expect(await until { await fake.sent == ["run the tests"] })
    #expect(json(op.call("send_to_bot", arguments: [:]))["error"] != nil)
    #expect(json(op.call("answer_approval", arguments: ["option": "yes"]))["error"] as? String == "Nothing is waiting for approval.")

    await fake.emit(#"{"type":"entry","entry":{"id":"p1","seq":5,"botId":"b1","rev":5,"kind":"permission","turn":1,"data":{"title":"Run npm test","status":"pending","options":[{"optionId":"a","name":"Allow once","kind":"allow_once"},{"optionId":"r","name":"Reject","kind":"reject_once"}]},"createdAt":1,"updatedAt":1}}"#)
    #expect(await until { store.chat("b1").contains { $0.id == "p1" } })
    let status = json(op.call("bot_status", arguments: [:]))
    #expect((status["approval"] as? [String: Any])?["options"] as? [String] == ["Allow once", "Reject"])
    #expect(json(op.call("answer_approval", arguments: ["option": "maybe"]))["error"] as? String == "No such option.")
    #expect(json(op.call("answer_approval", arguments: ["option": "allow"]))["answered"] as? String == "Allow once")
    #expect(store.answering["p1"] == "a")

    let recent = json(op.call("recent_messages", arguments: ["count": 1]))["messages"] as? [[String: String]]
    #expect(recent?.count == 1)
    #expect(recent?.first?["from"] == "approval request")
    #expect(op.reply("**Done**, see `x`").hasPrefix("[Ada replied] "))
}

/// Approvals reach a hold through `needsInput`, replies through `reply` (a call words them differently).
@MainActor @Test func holdGetsApprovalsSeparatelyFromReplies() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.direct))
    let store = BotStore(computer: randomComputer("Mac"), clientKind: "ios", storage: storage) { fake }
    defer { store.retire() }
    store.setActive(true)
    #expect(await until { await fake.subscribed })
    await fake.emit(#"{"type":"bot","bot":{"id":"b1","name":"Ada","rev":1,"lastAt":1}}"#)
    #expect(await until { store.connection == .online })
    var replies: [String] = []
    var notices: [String] = []
    store.beginHold(UUID(), botId: "b1", reply: { replies.append($0.data.text ?? "") }, needsInput: { notices.append($0.name) })
    await fake.emit(#"{"type":"bot","bot":{"id":"b1","name":"Ada","rev":2,"lastAt":2,"status":"needsInput"}}"#)
    #expect(await until { notices == ["Ada"] })
    #expect(replies.isEmpty)
}

// MARK: - Version compatibility (docs/reference/compatibility.md)
