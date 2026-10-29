import Foundation
import Testing
@testable import CodyncKit
@testable import CodyncUI

@MainActor @Test func holdSendsAndReceivesInBackgroundWithoutViewUpdates() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.direct))
    let store = BotStore(computer: randomComputer("Mac"), storage: storage) { _ in fake }
    defer { store.retire() }
    store.setActive(true)
    #expect(await until { await fake.subscribed })
    await fake.emit(watchHoldBotEvent("b1", name: "Bot", rev: 1))
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
    let store = BotStore(computer: randomComputer("Mac"), storage: storage) { _ in fake }
    defer { store.retire() }
    store.setActive(true)
    #expect(await until { await fake.subscribed })
    await fake.emit(watchHoldBotEvent("b1", name: "Bot", rev: 1))
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
    let store = BotStore(computer: randomComputer("Mac"), storage: storage) { _ in fake }
    defer { store.retire() }
    store.setActive(true)
    #expect(await until { await fake.subscribed })
    await fake.emit(#"{"type":"hello","hostId":"h1","rev":1}"#)
    await fake.emit(watchHoldBotEvent("b1", name: "Bot", rev: 1))
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
    let store = BotStore(computer: randomComputer("Mac"), storage: storage) { _ in fake }
    defer { store.retire() }
    store.setActive(true)
    #expect(await until { await fake.subscribed })
    await fake.emit(watchHoldBotEvent("b1", name: "Bot", rev: 1))
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
    let store = BotStore(computer: randomComputer("Mac"), storage: storage) { _ in fake }
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
    let store = BotStore(computer: randomComputer("Mac"), storage: storage) { _ in fake }
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
    let store = BotStore(computer: randomComputer("Mac"), storage: storage) { _ in fake }
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
    let store = BotStore(computer: randomComputer("Mac"), storage: storage) { _ in fake }
    store.setActive(true)
    #expect(await until { await fake.subscribed })
    await fake.emit(watchHoldBotEvent("b1", name: "Bot", rev: 1))
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



@MainActor @Test func holdGetsApprovalsSeparatelyFromReplies() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.direct))
    let store = BotStore(computer: randomComputer("Mac"), storage: storage) { _ in fake }
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

private func watchHoldBotEvent(_ id: String, name: String, rev: Int) -> String {
    #"{"type":"bot","bot":{"id":"\#(id)","name":"\#(name)","rev":\#(rev),"lastAt":\#(rev)}}"#
}

@MainActor @Test func voiceCallEndRestoresPushesWhileTheWatchKeepsItsLink() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    #expect(await rig.fake.eventClients == [nil])
    let call = UUID()
    rig.store.beginVoiceCall(call, hear: { _ in }, end: {})
    #expect(await until { await rig.fake.eventClients == [nil, "ios"] })
    rig.store.beginCallEnd()
    rig.store.endVoiceCall(call)
    // The end must reach the computer before push suppression is released.
    #expect(await rig.fake.eventClients == [nil, "ios"])
    #expect(await rig.fake.shutdownCount == 0)
    rig.store.finishCallEnd()
    #expect(await until { await rig.fake.eventClients == [nil, "ios", nil] })
    #expect(await rig.fake.shutdownCount == 0)
    rig.request(.close(rig.scope))
    #expect(await until { await rig.fake.shutdownCount == 1 })
}

@MainActor @Test func restartingAWatchHeldTransportAcknowledgesItsVisibleChatAgain() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    #expect(rig.request(.chat(rig.scope, botId: "b1")) != nil)
    #expect(await until { await rig.fake.readAttempts == 1 })
    let closing = rig.store.stopTransport()
    await closing?.value
    // The cached state still reads online; the new transport must re-acknowledge the open chat.
    #expect(rig.store.connection == .online)
    rig.store.restartStream()
    #expect(await until { await rig.fake.eventClients.count == 2 })
    await rig.fake.emit(#"{"type":"hello","hostId":"h1","rev":1}"#)
    #expect(await until { await rig.fake.readAttempts == 2 })
    let received = await rig.fake.readReceipts
    let bots = try received.map { try JSONSerialization.jsonObject(with: $0) as? [String: String] }
    #expect(bots.allSatisfy { $0?["botId"] == "b1" })
}
