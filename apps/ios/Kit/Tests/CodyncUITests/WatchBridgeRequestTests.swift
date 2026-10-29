import Foundation
import Testing
@testable import CodyncKit
@testable import CodyncUI

@MainActor @Test func onlyRepliesAfterTheSentMessageAreForwarded() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    rig.link.isReachable = true
    await rig.connect()
    await rig.sendAndClose()
    // An earlier turn's reply (seq below the sent message's) is not the answer.
    await rig.fake.emit(watchTestReplyEvent("old", seq: 0, rev: 50, text: "old"))
    await watchTestPause()
    #expect(rig.link.messages.isEmpty)
    await rig.fake.emit(watchTestReplyEvent("a1", seq: 7, rev: 51))
    #expect(await until { rig.link.messages.count == 1 })
    #expect(rig.link.chats.first?.entries.last?.data.text == "answer")
    // A replay of the same final reply isn't forwarded again.
    await rig.fake.emit(watchTestReplyEvent("a1", seq: 7, rev: 52))
    await watchTestPause()
    #expect(rig.link.messages.count == 1)
    #expect(rig.link.transfers.isEmpty)
}

@MainActor @Test func unreachableWatchGetsATransferThatReplacesTheOlderOne() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    await rig.sendAndClose()
    await rig.fake.emit(watchTestReplyEvent("a1", seq: 7, rev: 51))
    #expect(await until { rig.link.transfers.count == 1 })
    #expect(rig.link.messages.isEmpty)
    #expect(rig.link.transfers.map(\.botId) == ["b1"])
    await rig.fake.emit(watchTestReplyEvent("a2", seq: 8, rev: 52))
    #expect(await until { rig.link.chats.last?.entries.last?.id == "a2" })
    #expect(rig.link.transfers.count == 1)
}

@MainActor @Test func approvalRequestIsPushedToTheWatch() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    await rig.sendAndClose()
    await rig.fake.emit(watchTestCardEvent("p1", seq: 6, rev: 60))
    await rig.fake.emit(watchTestBotEvent("b1", rev: 61, status: "needsInput"))
    #expect(await until { rig.link.chats.contains { $0.entries.contains { $0.id == "p1" } } })
}

@MainActor @Test func chatRequestReturnsTheCacheAndMarksItRead() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    rig.link.isReachable = true
    await rig.connect()
    await rig.fake.emit(watchTestReplyEvent("a1", seq: 3, rev: 5, text: "hello"))
    #expect(await until { rig.store.chat("b1").count == 1 })
    guard case let .chat(chat)? = rig.request(.chat(rig.scope, botId: "b1")) else {
        Issue.record("no chat")
        return
    }
    #expect(chat.entries.map(\.id) == ["a1"] && chat.fresh)
    #expect(await until { await rig.fake.readAttempts >= 1 })
    // The open chat follows changes by message.
    await rig.fake.emit(watchTestReplyEvent("a2", seq: 4, rev: 6))
    #expect(await until { rig.link.chats.last?.entries.map(\.id) == ["a1", "a2"] })
    #expect(rig.request(.chat(rig.scope, botId: "nope")) == .failed(.notFound))
}

@MainActor @Test func approvalIsAnsweredOnceAndAnExchangeStarts() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    await rig.fake.emit(watchTestCardEvent("p1", seq: 5, rev: 5))
    #expect(await until { rig.store.chat("b1").contains { $0.id == "p1" } })
    #expect(rig.request(.respond(rig.scope, botId: "b1", entryId: "p1", optionId: "a")) == .accepted)
    #expect(rig.request(.respond(rig.scope, botId: "b1", entryId: "p1", optionId: "a")) == .accepted)
    rig.request(.close(rig.scope))
    await watchTestPause()
    #expect(await rig.fake.calls.filter { $0 == "respondPermission" }.count == 1)
    #expect(await rig.fake.shutdownCount == 0) // the answer's exchange holds the link
    #expect(rig.request(.respond(rig.scope, botId: "b1", entryId: "zzz", optionId: "a")) == .failed(.notFound))
}

@MainActor @Test func approvalForACardNotCaughtUpYetWaitsForIt() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    rig.request(.close(rig.scope))
    #expect(await until { await rig.fake.shutdownCount == 1 })
    #expect(!rig.store.isLive)
    #expect(rig.request(.respond(rig.scope, botId: "b1", entryId: "p1", optionId: "a")) == .accepted)
    #expect(await until { await rig.fake.subscribed })
    // Connected again but not caught up: the card isn't missing yet, so the answer waits.
    #expect(!rig.store.isLive)
    #expect(await rig.fake.calls.filter { $0 == "respondPermission" }.isEmpty)
    await rig.fake.emit(#"{"type":"hello","hostId":"h1","rev":9}"#)
    await rig.fake.emit(watchTestCardEvent("p1", seq: 5, rev: 9))
    #expect(await until { await rig.fake.calls.contains("respondPermission") })
}

@MainActor @Test func staleScopeIsRejectedAndTheSnapshotRepublished() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    let before = rig.link.contexts.count
    let stale = WatchScope(context: "other", computer: rig.computer.id)
    #expect(rig.request(.send(stale, botId: "b1", text: "hi", nonce: "n1")) == .failed(.staleScope))
    #expect(rig.request(.respond(WatchScope(context: rig.storage.id, computer: "gone"), botId: "b1", entryId: "p", optionId: "a")) == .failed(.staleScope))
    #expect(rig.link.contexts.count == before + 2)
    #expect(await rig.fake.sent.isEmpty)
}

@MainActor @Test func olderWatchGetsNeedsUpdate() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    let old = WatchEnvelope(version: "1.0.0", minPeer: "1.0.0", message: .request(id: UUID(), .hello))
    let response = try WatchEnvelope.decode(rig.bridge.answer(try old.encode()))
    guard case .response(_, .failed(.needsUpdate)) = response.message else {
        Issue.record("not refused")
        return
    }
}

@MainActor @Test func nothingIsEncodedWithoutAnInstalledWatchApp() async throws {
    let rig = WatchBridgeTestRig(installed: false)
    defer { rig.finish() }
    #expect(rig.request(.hello) == nil)
    await watchTestPause()
    #expect(rig.link.contexts.isEmpty && rig.link.messages.isEmpty)
    #expect(rig.system.began.isEmpty)
    // Installing it starts publishing.
    rig.link.isWatchAppInstalled = true
    rig.link.onChange?()
    #expect(rig.link.snapshots.count == 1)
}

@MainActor @Test func snapshotIsPublishedOnlyWhenItsContentChanges() async throws {
    let rig = WatchBridgeTestRig(coalesce: .milliseconds(200))
    defer { rig.finish() }
    await rig.connect()
    #expect(await until { rig.link.snapshots.last?.bots.map(\.id) == ["b1"] })
    await watchTestPause(500)
    let stable = rig.link.contexts.count
    let snapshot = try #require(rig.link.snapshots.last)
    #expect(snapshot.state == .online && snapshot.fresh && snapshot.computerName == "Mac")
    #expect(snapshot.scope == rig.scope)
    // The same bot again: observers fire, the payload doesn't change.
    await rig.fake.emit(watchTestBotEvent("b1", rev: 1))
    await watchTestPause(500)
    #expect(rig.link.contexts.count == stable)
    // Three quick changes are one publish.
    for rev in 2...4 { await rig.fake.emit(watchTestBotEvent("b1", rev: rev, status: "working")) }
    #expect(await until { rig.link.contexts.count > stable })
    await watchTestPause(500)
    #expect(rig.link.contexts.count == stable + 1)
}

@MainActor @Test func bindDropsExchangesPostsNothingForTheOldStoreAndPublishesTheNewScope() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    let call = UUID()
    rig.store.beginHold(call, botId: "b1") // keeps the old link open: only the bridge's guards stop the reply
    await rig.sendAndClose()

    // Sign-out: an empty account.
    let suite = "CodyncUITests.\(UUID().uuidString)"
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let empty = AccountStore(storage: SharedStore.Context(accountID: nil, suite: suite), cloud: nil, active: false)
    defer { empty.retire() }
    rig.bridge.bind(empty)
    #expect(rig.link.cancelCount == 1)
    #expect(rig.link.snapshots.last?.scope == nil && rig.link.snapshots.last?.state == .notPaired)
    // The old exchange is gone: its reply, arriving on the still-open link, goes nowhere.
    await rig.fake.emit(watchTestReplyEvent("a1", seq: 7, rev: 51))
    #expect(await until { rig.store.chat("b1").contains { $0.id == "a1" } })
    await watchTestPause()
    #expect(await rig.fake.shutdownCount == 0)
    await rig.store.endHold(call)
    #expect(rig.link.messages.isEmpty && rig.link.transfers.isEmpty)
    #expect(rig.request(.send(rig.scope, botId: "b1", text: "hi", nonce: "n2")) == .failed(.noComputer))

    // Switching to an account with a computer.
    let other = WatchBridgeTestRig()
    defer { other.finish() }
    rig.bridge.bind(other.accounts)
    #expect(rig.link.snapshots.last?.scope == other.scope)
    #expect(rig.link.cancelCount == 2)
}

@MainActor @Test func reconnectedStoreIsNotLiveUntilItsCatchUpArrives() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    rig.request(.close(rig.scope))
    #expect(await until { await rig.fake.shutdownCount == 1 })
    rig.store.beginHold(UUID(), botId: nil)
    #expect(await until { await rig.fake.subscribed })
    await watchTestPause(50)
    #expect(!rig.store.isLive)
    await rig.fake.emit(#"{"type":"hello","hostId":"h1","rev":1}"#)
    #expect(await until { rig.store.isLive })
}

@MainActor @Test func heldStoreAcknowledgesOnlyTheWatchsOpenChat() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    await rig.fake.emit(watchTestBotEvent("b2", name: "Bo", rev: 2, extra: #","unread":1"#))
    #expect(rig.request(.chat(rig.scope, botId: "b1")) != nil)
    #expect(await until { await rig.fake.readAttempts >= 1 })
    await rig.fake.emit(watchTestReplyEvent("a1", seq: 3, rev: 5))
    await rig.fake.emit(#"{"type":"entry","entry":{"id":"x1","seq":4,"botId":"b2","rev":6,"kind":"agent","turn":1,"data":{"text":"hi","final":true},"createdAt":1,"updatedAt":1}}"#)
    await watchTestPause()
    let received = await rig.fake.readReceipts
    let receipts = received.map { String(decoding: $0, as: UTF8.self) }
    #expect(!receipts.isEmpty && receipts.allSatisfy { $0.contains("b1") && !$0.contains("b2") })
}

@MainActor @Test func failedPushFallsBackToTransfer() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    rig.link.isReachable = true
    await rig.connect()
    await rig.sendAndClose()
    rig.link.failPushes = true
    await rig.fake.emit(watchTestReplyEvent("a1", seq: 7, rev: 51))
    #expect(await until { rig.link.transfers.count == 1 })
    #expect(rig.link.messages.isEmpty)
}

@MainActor @Test func approvalRequestGoesByMessageWhenReachable() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    rig.link.isReachable = true
    await rig.connect()
    await rig.sendAndClose()
    await rig.fake.emit(watchTestCardEvent("p1", seq: 6, rev: 60))
    await rig.fake.emit(watchTestBotEvent("b1", rev: 61, status: "needsInput"))
    #expect(await until { rig.link.chats.contains { $0.entries.contains { $0.id == "p1" } } })
    #expect(rig.link.transfers.isEmpty)
}

@MainActor @Test func replyInAResyncCatchUpIsStillForwarded() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    await rig.sendAndClose()
    await rig.fake.emit(#"{"type":"resync"}"#)
    #expect(await until { await rig.fake.eventSubscriptionCount == 2 })
    #expect(await until { !rig.store.isLive })
    await rig.fake.emit(#"{"type":"hello","hostId":"h1","rev":70}"#)
    await rig.fake.emit(watchTestReplyEvent("a1", seq: 7, rev: 62))
    #expect(await until { rig.link.chats.contains { $0.entries.contains { $0.id == "a1" } } })
}

@MainActor @Test func eventStreamRestartIsNotLiveUntilItsCatchUp() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    #expect(rig.store.isLive)
    await rig.fake.emit(#"{"type":"resync"}"#)
    #expect(await until { await rig.fake.eventSubscriptionCount == 2 })
    #expect(!rig.store.isLive)
    await rig.fake.emit(#"{"type":"hello","hostId":"h1","rev":1}"#)
    #expect(await until { rig.store.isLive })
}

@MainActor @Test func answeredCardForwardsTheTurnsFinalTextEvenBeforeTheCard() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    await rig.fake.emit(watchTestCardEvent("p1", seq: 5, rev: 5))
    #expect(await until { rig.store.chat("b1").contains { $0.id == "p1" } })
    await rig.fake.emit(watchTestBotEvent("b1", rev: 6, status: "needsInput"))
    #expect(rig.request(.respond(rig.scope, botId: "b1", entryId: "p1", optionId: "a")) == .accepted)
    rig.request(.close(rig.scope))
    // The turn's final text has a lower seq than the card; the turn number is what counts.
    await rig.fake.emit(watchTestReplyEvent("a1", seq: 4, rev: 7))
    #expect(await until { rig.link.chats.contains { $0.entries.contains { $0.id == "a1" } } })
    // The first idle after the answer settles it.
    await rig.fake.emit(watchTestBotEvent("b1", rev: 8, status: "working"))
    await rig.fake.emit(watchTestBotEvent("b1", rev: 9, status: "idle"))
    #expect(await until { await rig.fake.shutdownCount == 1 })
}

@MainActor @Test func closeWithAStaleScopeStillEndsTheLease() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    let stale = WatchScope(context: "other", computer: "gone")
    #expect(rig.request(.close(stale)) == .failed(.staleScope))
    #expect(await until { await rig.fake.shutdownCount == 1 })
}
