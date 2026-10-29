import Foundation
import Testing
@testable import CodyncKit
@testable import CodyncUI

@MainActor @Test func backgroundSendHoldsTheLinkAndSendsOnce() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    #expect(rig.send() == .accepted)
    #expect(await until { await rig.fake.sent == ["hi"] })
    #expect(rig.send() == .accepted) // WC retry
    rig.request(.close(rig.scope))
    await watchTestPause()
    // The host's entry exists: even a later replay is a no-op.
    #expect(rig.send() == .accepted)
    await watchTestPause()
    #expect(await rig.fake.sent == ["hi"])
    #expect(await rig.fake.shutdownCount == 0)
    #expect(rig.system.began == [1])
    #expect(rig.system.ended.isEmpty)
}

@MainActor @Test func settlingReleasesTheLinkThenEndsTheAssertion() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.fake.setShutdownDelay(.milliseconds(500))
    await rig.connect()
    await rig.sendAndClose()
    await rig.fake.emit(watchTestBotEvent("b1", rev: 60, status: "working"))
    await rig.fake.emit(watchTestReplyEvent("a1", seq: 7, rev: 61))
    await rig.fake.emit(watchTestBotEvent("b1", rev: 62, status: "idle"))
    #expect(await until { await rig.fake.shutdownCount == 1 })
    #expect(rig.system.ended.isEmpty)
    #expect(await until { !rig.system.ended.isEmpty })
    // The assertion outlived the shutdown.
    #expect(await rig.fake.shutdownFinished == 1)
    #expect(rig.system.ended == rig.system.began)
}

@MainActor @Test func deadlineReleasesTheLinkThenEndsTheAssertion() async throws {
    let rig = WatchBridgeTestRig(exchange: .milliseconds(250))
    defer { rig.finish() }
    await rig.fake.setShutdownDelay(.milliseconds(500))
    await rig.connect()
    await rig.sendAndClose()
    #expect(await until { await rig.fake.shutdownCount == 1 })
    #expect(rig.system.ended.isEmpty)
    #expect(await until { !rig.system.ended.isEmpty })
    #expect(await rig.fake.shutdownFinished == 1)
}

@MainActor @Test func deadlineStaysWithinTheBackgroundTimeLeft() async throws {
    let rig = WatchBridgeTestRig(exchange: .seconds(30), margin: .seconds(5))
    defer { rig.finish() }
    await rig.connect()
    rig.system.backgroundTimeRemaining = 5.1
    await rig.sendAndClose()
    #expect(await until { await rig.fake.shutdownCount == 1 })
    #expect(await until { !rig.system.ended.isEmpty })
}

@MainActor @Test func expirationReleasesSynchronously() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    // A separate hold keeps the link open, so only the bridge's guards stop the reply.
    let call = UUID()
    rig.store.beginHold(call, botId: "b1")
    await rig.sendAndClose()
    rig.system.expire()
    // Nothing awaited: the assertion is already over.
    #expect(rig.system.ended == [1])
    await rig.fake.emit(watchTestReplyEvent("a1", seq: 7, rev: 51))
    #expect(await until { rig.store.chat("b1").contains { $0.id == "a1" } })
    await watchTestPause()
    #expect(rig.link.messages.isEmpty && rig.link.transfers.isEmpty)
    #expect(rig.system.ended == [1])
    #expect(await rig.fake.shutdownCount == 0)
    await rig.store.endHold(call)
    #expect(await rig.fake.shutdownCount == 1)
}

@MainActor @Test func unauthorizedStoreEndsTheExchange() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    await rig.sendAndClose()
    await rig.fake.set(.unauthorized("revoked"))
    #expect(await until { rig.store.connection != .online })
    #expect(await until { await rig.fake.shutdownCount == 1 })
}

@MainActor @Test func voiceCallAndWatchHoldCoexist() async throws {
    let rig = WatchBridgeTestRig(exchange: .milliseconds(250))
    defer { rig.finish() }
    await rig.connect()
    let call = UUID()
    rig.store.beginVoiceCall(call, hear: { _ in }, end: {})
    await rig.sendAndClose()
    // The exchange's deadline passes; the call still has the link.
    #expect(await until { !rig.system.ended.isEmpty })
    await watchTestPause(200)
    #expect(await rig.fake.shutdownCount == 0)
    rig.store.endVoiceCall(call)
    #expect(await until { await rig.fake.shutdownCount == 1 })
}

@MainActor @Test func leaseExpiresWithoutRenewalAndRenewalKeepsIt() async throws {
    let scheduler = WatchTestScheduler()
    let rig = WatchBridgeTestRig(lease: .milliseconds(800), timerSleep: { duration in
        try await scheduler.sleep(for: duration)
    })
    defer {
        rig.finish()
        scheduler.cancelAll()
    }
    await rig.connect()
    let initiallyArmed = await until { scheduler.deadlines.count == 1 && scheduler.pendingCount == 1 }
    try #require(initiallyArmed)
    #expect(scheduler.deadlines.last == .milliseconds(800))
    for renewal in 1...4 {
        scheduler.advance(by: .milliseconds(150))
        guard case .snapshot? = rig.request(.renew(rig.scope, open: nil)) else {
            Issue.record("renew must answer a snapshot")
            return
        }
        // The replacement is armed and the cancelled timer has released its continuation.
        let renewalArmed = await until {
            scheduler.deadlines.count == renewal + 1 && scheduler.pendingCount == 1
        }
        try #require(renewalArmed)
        #expect(scheduler.deadlines.last == .milliseconds(800 + renewal * 150))
    }
    // Every old lease deadline has passed; the last renewal still has one millisecond left.
    scheduler.advance(by: .milliseconds(799))
    #expect(await rig.fake.shutdownCount == 0)
    #expect(rig.system.began == [1] && rig.system.ended.isEmpty)
    scheduler.advance(by: .milliseconds(1))
    #expect(await until { await rig.fake.shutdownCount == 1 })
    #expect(await until { rig.system.ended == rig.system.began })
}

@MainActor @Test func closeEndsTheLeaseOnItsOwn() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    #expect(rig.request(.close(rig.scope)) == .accepted)
    #expect(await until { await rig.fake.shutdownCount == 1 })
}

@MainActor @Test func needsInputThenIdleSettlesOnce() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    var settled = 0
    rig.store.beginHold(UUID(), botId: "b1", settled: { _ in settled += 1 })
    await rig.fake.emit(watchTestBotEvent("b1", rev: 2, status: "working"))
    await rig.fake.emit(watchTestBotEvent("b1", rev: 3, status: "needsInput"))
    await rig.fake.emit(watchTestBotEvent("b1", rev: 4, status: "idle"))
    #expect(await until { settled == 1 })
    await watchTestPause()
    #expect(settled == 1)
}

@MainActor @Test func idleInsideTheCatchUpDoesNotSettle() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    await rig.fake.emit(watchTestBotEvent("b1", rev: 2, status: "working"))
    #expect(await until { rig.store.bots["b1"]?.isWorking == true })
    rig.request(.close(rig.scope))
    #expect(await until { await rig.fake.shutdownCount == 1 })
    var settled = 0
    rig.store.beginHold(UUID(), botId: "b1", settled: { _ in settled += 1 })
    #expect(await until { await rig.fake.subscribed })
    // The cached "working" is old news: the catch-up says idle.
    await rig.fake.emit(#"{"type":"hello","hostId":"h1","rev":5}"#)
    await rig.fake.emit(watchTestBotEvent("b1", rev: 5, status: "idle"))
    #expect(await until { rig.store.isLive })
    await watchTestPause()
    #expect(settled == 0)
    await rig.fake.emit(watchTestBotEvent("b1", rev: 6, status: "working"))
    await rig.fake.emit(watchTestBotEvent("b1", rev: 7, status: "idle"))
    #expect(await until { settled == 1 })
}

@MainActor @Test func retiringAStoreEndsEveryHold() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    var ended = 0
    for _ in 0..<3 { rig.store.beginHold(UUID(), botId: "b1", end: { ended += 1 }) }
    rig.store.retire()
    #expect(ended == 3)
}

@MainActor @Test func unlimitedBackgroundTimeDoesNotTrap() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    rig.system.backgroundTimeRemaining = .greatestFiniteMagnitude
    #expect(rig.send() == .accepted)
    #expect(rig.request(.renew(rig.scope, open: nil)) != nil)
    #expect(await until { await rig.fake.sent == ["hi"] })
}

@MainActor @Test func idleOfAnEarlierTurnDoesNotEndTheExchange() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    await rig.fake.emit(watchTestBotEvent("b1", rev: 10, status: "working")) // the previous turn
    #expect(await until { rig.store.bots["b1"]?.isWorking == true })
    await rig.sendAndClose(started: false)
    // The previous turn's reply lands after the watch's message (turn 0 < the message's turn 1), then its idle.
    await rig.fake.emit(watchTestReplyEvent("a0", seq: 5, rev: 11, turn: 0))
    await rig.fake.emit(watchTestBotEvent("b1", rev: 12, status: "idle"))
    #expect(await until { rig.store.bots["b1"]?.isWorking == false })
    await watchTestPause(150)
    #expect(await rig.fake.shutdownCount == 0)
    #expect(rig.link.transfers.isEmpty)
    // The watch's own turn starts (its message is `sent`), answers, and goes idle.
    await rig.markStarted(rev: 13)
    await rig.fake.emit(watchTestBotEvent("b1", rev: 14, status: "working"))
    await rig.fake.emit(watchTestReplyEvent("a1", seq: 7, rev: 15))
    await rig.fake.emit(watchTestBotEvent("b1", rev: 16, status: "idle"))
    #expect(await until { await rig.fake.shutdownCount == 1 })
    #expect(rig.link.transfers.count == 1)
}

@MainActor @Test func groupSettlesOnlyAfterItStaysIdle() async throws {
    let rig = WatchBridgeTestRig(groupGrace: .milliseconds(400))
    defer { rig.finish() }
    await rig.connect()
    await rig.fake.emit(watchTestBotEvent("g1", name: "Team", rev: 2, extra: #","kind":"group","members":["b1","b2"]"#))
    #expect(await until { rig.store.bots["g1"] != nil })
    await rig.sendAndClose(to: "g1")
    await rig.fake.emit(watchTestBotEvent("g1", name: "Team", rev: 60, status: "working", extra: #","kind":"group","members":["b1","b2"]"#))
    await rig.fake.emit(watchTestReplyEvent("a1", seq: 7, rev: 61, chat: "g1", author: "b1"))
    // Idle between members, then the second member starts within the grace.
    await rig.fake.emit(watchTestBotEvent("g1", name: "Team", rev: 62, extra: #","kind":"group","members":["b1","b2"]"#))
    await rig.fake.emit(watchTestBotEvent("g1", name: "Team", rev: 63, status: "working", extra: #","kind":"group","members":["b1","b2"]"#))
    await watchTestPause(700)
    #expect(await rig.fake.shutdownCount == 0)
    await rig.fake.emit(watchTestReplyEvent("a2", seq: 8, rev: 64, chat: "g1", author: "b2"))
    await rig.fake.emit(watchTestBotEvent("g1", name: "Team", rev: 65, extra: #","kind":"group","members":["b1","b2"]"#))
    #expect(await until { await rig.fake.shutdownCount == 1 })
}

@MainActor @Test func backgroundingBeginsTheAssertionAndReclampsTimers() async throws {
    let rig = WatchBridgeTestRig(appActive: true)
    defer { rig.finish() }
    await rig.connect()
    #expect(rig.system.began.isEmpty)
    rig.system.isAppActive = false
    rig.system.backgroundTimeRemaining = 5.2
    rig.bridge.appActiveChanged()
    #expect(rig.system.began == [1])
    // The lease (10 s in the foreground) is now cut to the 0.2 s left.
    #expect(await until { !rig.system.ended.isEmpty })
}

@MainActor @Test func deniedAssertionWhenBackgroundingReleasesEverything() async throws {
    let rig = WatchBridgeTestRig(appActive: true)
    defer { rig.finish() }
    await rig.connect()
    rig.accounts.setActive(false)
    rig.system.isAppActive = false
    rig.system.denyTasks = true
    rig.bridge.appActiveChanged()
    #expect(await until { await rig.fake.shutdownCount == 1 })
}

@MainActor @Test func sendIsRefusedWhenTheBackgroundAssertionIsDenied() async throws {
    let rig = WatchBridgeTestRig(appActive: true)
    defer { rig.finish() }
    await rig.connect()
    rig.system.isAppActive = false
    rig.system.denyTasks = true
    #expect(rig.send() == .failed(.unavailable))
    #expect(rig.request(.respond(rig.scope, botId: "b1", entryId: "p1", optionId: "a")) == .failed(.unavailable))
    await watchTestPause()
    #expect(await rig.fake.sent.isEmpty)
    // No time to hold for either.
    rig.system.denyTasks = false
    rig.system.backgroundTimeRemaining = 4
    #expect(rig.send(nonce: "n2") == .failed(.unavailable))
}

@MainActor @Test func renewAndChatAreUnavailableWhenNoLeaseCanBeHeld() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    rig.system.denyTasks = true
    // hello still answers, so the watch can show the cached state.
    guard case .snapshot? = rig.request(.hello) else { Issue.record("hello must answer a snapshot"); return }
    #expect(rig.request(.renew(rig.scope, open: nil)) == .failed(.unavailable))
    #expect(rig.request(.renew(rig.scope, open: "b1")) == .failed(.unavailable))
    await watchTestPause()
    #expect(await !rig.fake.subscribed)
    // Once the phone can hold the link, both answer again.
    rig.system.denyTasks = false
    guard case .snapshot? = rig.request(.renew(rig.scope, open: nil)) else { Issue.record("renew must answer a snapshot"); return }
    #expect(await until { await rig.fake.subscribed })
}

@MainActor @Test func retiredStoreClosesBeforeTheAssertionEnds() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.fake.setShutdownDelay(.milliseconds(500))
    await rig.connect()
    await rig.sendAndClose()
    rig.store.retire()
    #expect(rig.system.ended.isEmpty)
    #expect(await until { !rig.system.ended.isEmpty })
    #expect(await rig.fake.shutdownFinished == 1)
}

@MainActor @Test func sendSettlesOnlyOnceItsMessageStartedATurn() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    await rig.sendAndClose(started: false)
    await rig.fake.emit(watchTestBotEvent("b1", rev: 50, status: "working"))
    await rig.fake.emit(watchTestBotEvent("b1", rev: 51, status: "idle"))
    await rig.fake.emit(watchTestReplyEvent("x1", seq: 9, rev: 52, turn: 2)) // someone else's later entry
    await watchTestPause(150)
    #expect(await rig.fake.shutdownCount == 0)
}

@MainActor @Test func inactiveWatchHoldDoesNotCountAsAPhone() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    #expect(await rig.fake.eventClients == [nil])
    // The app is active: the stream is a phone's, and goes back to being one when it returns.
    rig.accounts.setActive(true)
    #expect(await until { await rig.fake.eventClients == [nil, "ios"] })
    rig.accounts.setActive(false)
    #expect(await until { await rig.fake.eventClients == [nil, "ios", nil] })
}

@MainActor @Test func callHoldKeepsTheStreamAPhoneInTheBackground() async throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    await rig.connect()
    #expect(await rig.fake.eventClients == [nil])
    let call = UUID()
    rig.store.beginVoiceCall(call, hear: { _ in }, end: {})
    #expect(await until { await rig.fake.eventClients == [nil, "ios"] })
    // The call ends: only the watch lease remains, which is not a phone.
    rig.store.endVoiceCall(call)
    #expect(await until { await rig.fake.eventClients == [nil, "ios", nil] })
    await watchTestPause(100)
    #expect(await rig.fake.eventClients.count == 3)
}

@MainActor @Test func activeAppSubscribesAsAPhone() async throws {
    let rig = WatchBridgeTestRig(appActive: true)
    defer { rig.finish() }
    await rig.connect()
    #expect(await rig.fake.eventClients == ["ios"])
}
