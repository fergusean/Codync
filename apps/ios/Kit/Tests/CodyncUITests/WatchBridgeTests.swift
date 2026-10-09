import CryptoKit
import Foundation
import Testing
@testable import CodyncKit
@testable import CodyncUI

// WatchBridge against a real `AccountStore` / `BotStore` on `FakeRemote` (StoreTests.swift), with a
// fake WatchConnectivity link and fake system effects.

@MainActor
private final class FakeWatchLink: WatchLink {
    var isActivated = true
    var isPaired = true
    var isWatchAppInstalled = true
    var isReachable = false
    var onChange: (@MainActor () -> Void)?
    private(set) var contexts: [Data] = []
    private(set) var messages: [Data] = []
    /// Outstanding transfers: a bot's newer one replaces its older.
    private(set) var transfers: [(botId: String, data: Data)] = []
    private(set) var cancelCount = 0

    func updateApplicationContext(_ data: Data) { contexts.append(data) }
    /// Makes the next pushes fail, as when the wrist dropped but `isReachable` still reads true.
    var failPushes = false
    func sendMessage(_ data: Data, onFailure: @escaping @MainActor () -> Void) {
        if failPushes { onFailure() } else { messages.append(data) }
    }
    func transferUserInfo(_ data: Data, botId: String) {
        transfers.removeAll { $0.botId == botId }
        transfers.append((botId, data))
    }
    func cancelTransfers() {
        cancelCount += 1
        transfers = []
    }

    var snapshots: [WatchSnapshot] { contexts.compactMap { Self.snapshot($0) } }
    var chats: [WatchChat] { (messages + transfers.map(\.data)).compactMap { Self.chat($0) } }

    static func snapshot(_ data: Data) -> WatchSnapshot? {
        guard case let .snapshot(s)? = try? WatchEnvelope.decode(data).message else { return nil }
        return s
    }

    static func chat(_ data: Data) -> WatchChat? {
        guard case let .chat(c)? = try? WatchEnvelope.decode(data).message else { return nil }
        return c
    }
}

@MainActor
private final class FakeWatchSystem: WatchSystem {
    var isAppActive = false
    var denyTasks = false
    var backgroundTimeRemaining: TimeInterval = .infinity
    private(set) var began: [Int] = []
    private(set) var ended: [Int] = []
    private var expirations: [@MainActor () -> Void] = []

    func beginBackgroundTask(expiration: @escaping @MainActor () -> Void) -> Int? {
        guard !denyTasks else { return nil }
        began.append(began.count + 1)
        expirations.append(expiration)
        return began.count
    }
    func endBackgroundTask(_ token: Int) { ended.append(token) }
    /// The system's time is up for the newest task.
    func expire() { expirations.last?() }
}

private func randomComputer(_ name: String) -> Computer {
    let sk = Curve25519.Signing.PrivateKey().publicKey.rawRepresentation
    return Computer(id: RelayCrypto.computerId(signKey: sk), name: name, signKey: sk.base64URL,
                    boxKey: Curve25519.KeyAgreement.PrivateKey().publicKey.rawRepresentation.base64URL,
                    cloud: URL(string: "https://cloud.example.dev"))
}

private extension Data {
    var base64URL: String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}

/// Polls until `condition` holds (2 s at most).
@MainActor
private func until(_ condition: @MainActor () async -> Bool) async -> Bool {
    for _ in 0..<200 {
        if await condition() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return false
}

private func pause(_ milliseconds: Int = 100) async { try? await Task.sleep(for: .milliseconds(milliseconds)) }

private func bot(_ id: String, name: String = "Ada", rev: Int, status: String = "idle", extra: String = "") -> String {
    #"{"type":"bot","bot":{"id":"\#(id)","name":"\#(name)","rev":\#(rev),"lastAt":\#(rev),"status":"\#(status)"\#(extra)}}"#
}

private func reply(_ id: String, seq: Int, rev: Int, text: String = "answer", chat: String = "b1", author: String? = nil, turn: Int = 1) -> String {
    let by = author.map { #","author":"\#($0)""# } ?? ""
    return #"{"type":"entry","entry":{"id":"\#(id)","seq":\#(seq),"botId":"\#(chat)","rev":\#(rev),"kind":"agent","turn":\#(turn),"data":{"text":"\#(text)","final":true\#(by)},"createdAt":1,"updatedAt":1}}"#
}

private func card(_ id: String, seq: Int, rev: Int) -> String {
    #"{"type":"entry","entry":{"id":"\#(id)","seq":\#(seq),"botId":"b1","rev":\#(rev),"kind":"permission","turn":1,"data":{"title":"Run npm test","status":"pending","options":[{"optionId":"a","name":"Allow once","kind":"allow_once"}]},"createdAt":1,"updatedAt":1}}"#
}

/// One account with one computer (bot `b1`, "Ada") behind a `FakeRemote`, and a bridge on it.
@MainActor
private final class Rig {
    let fake = FakeRemote(.ready(.direct))
    let link = FakeWatchLink()
    let system = FakeWatchSystem()
    let computer = randomComputer("Mac")
    let storage: SharedStore.Context
    private let suite: String
    let accounts: AccountStore
    let bridge: WatchBridge
    var store: BotStore { accounts.currentStore! }
    var scope: WatchScope { WatchScope(context: storage.id, computer: computer.id) }

    init(appActive: Bool = false, installed: Bool = true, exchange: Duration = .seconds(10), lease: Duration = .seconds(10),
         coalesce: Duration = .milliseconds(20), margin: Duration = .seconds(5),
         groupGrace: Duration = .milliseconds(300)) {
        suite = "CodyncUITests.\(UUID().uuidString)"
        storage = SharedStore.Context(accountID: "user_\(UUID().uuidString)", suite: suite)
        storage.computers = [computer]
        let (computer, fake, storage) = (computer, fake, storage)
        accounts = AccountStore(storage: storage, clientKind: "ios", cloud: nil, active: appActive) { _ in
            BotStore(computer: computer, clientKind: "ios", storage: storage) { fake }
        }
        system.isAppActive = appActive
        link.isWatchAppInstalled = installed
        var durations = WatchBridge.Durations()
        durations.exchange = exchange
        durations.lease = lease
        durations.coalesce = coalesce
        durations.margin = margin
        durations.respondRetry = .seconds(3)
        durations.groupGrace = groupGrace
        bridge = WatchBridge(accounts: accounts, link: link, system: system, durations: durations)
    }

    func finish() {
        accounts.retire()
        UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite)
    }

    @discardableResult
    func request(_ request: WatchRequest) -> WatchResponse? {
        let id = UUID()
        guard let data = try? WatchEnvelope.fromWatch(.request(id: id, request)).encode(),
              case let .response(echoed, response)? = try? WatchEnvelope.decode(bridge.answer(data)).message, echoed == id
        else { return nil }
        return response
    }

    /// The watch opens: `hello` holds the link; the computer answers and `b1` is known and caught up.
    func connect() async {
        request(.hello)
        #expect(await until { await fake.subscribed })
        await fake.emit(#"{"type":"hello","hostId":"h1","rev":1}"#)
        await fake.emit(bot("b1", rev: 1))
        #expect(await until { store.connection == .online && store.isLive })
    }

    func send(_ text: String = "hi", nonce: String = "n1", to botId: String = "b1") -> WatchResponse? {
        request(.send(scope, botId: botId, text: text, nonce: nonce))
    }

    /// The host starts the turn for the message: its entry gets `status: sent`.
    func markStarted(_ botId: String = "b1", rev: Int = 40) async {
        await fake.emit(#"{"type":"entry","entry":{"id":"e1","seq":1,"botId":"\#(botId)","rev":\#(rev),"kind":"user","turn":1,"data":{"text":"hi","status":"sent","clientNonce":"n1"},"createdAt":1,"updatedAt":1}}"#)
        #expect(await until { store.allEntries(botId).first { $0.id == "e1" }?.data.status == "sent" })
    }

    /// A send answered by the host, the watch closed: only the exchange holds the link.
    func sendAndClose(to botId: String = "b1", started: Bool = true) async {
        #expect(send(to: botId) == .accepted)
        #expect(await until { store.allEntries(botId).contains { $0.id == "e1" } })
        if started { await markStarted(botId) }
        request(.close(scope))
    }
}

// MARK: - Exchanges

@MainActor @Test func backgroundSendHoldsTheLinkAndSendsOnce() async throws {
    let rig = Rig()
    defer { rig.finish() }
    await rig.connect()
    #expect(rig.send() == .accepted)
    #expect(await until { await rig.fake.sent == ["hi"] })
    #expect(rig.send() == .accepted) // WC retry
    rig.request(.close(rig.scope))
    await pause()
    // The host's entry exists: even a later replay is a no-op.
    #expect(rig.send() == .accepted)
    await pause()
    #expect(await rig.fake.sent == ["hi"])
    #expect(await rig.fake.shutdownCount == 0)
    #expect(rig.system.began == [1])
    #expect(rig.system.ended.isEmpty)
}

@MainActor @Test func onlyRepliesAfterTheSentMessageAreForwarded() async throws {
    let rig = Rig()
    defer { rig.finish() }
    rig.link.isReachable = true
    await rig.connect()
    await rig.sendAndClose()
    // An earlier turn's reply (seq below the sent message's) is not the answer.
    await rig.fake.emit(reply("old", seq: 0, rev: 50, text: "old"))
    await pause()
    #expect(rig.link.messages.isEmpty)
    await rig.fake.emit(reply("a1", seq: 7, rev: 51))
    #expect(await until { rig.link.messages.count == 1 })
    #expect(rig.link.chats.first?.entries.last?.data.text == "answer")
    // A replay of the same final reply isn't forwarded again.
    await rig.fake.emit(reply("a1", seq: 7, rev: 52))
    await pause()
    #expect(rig.link.messages.count == 1)
    #expect(rig.link.transfers.isEmpty)
}

@MainActor @Test func unreachableWatchGetsATransferThatReplacesTheOlderOne() async throws {
    let rig = Rig()
    defer { rig.finish() }
    await rig.connect()
    await rig.sendAndClose()
    await rig.fake.emit(reply("a1", seq: 7, rev: 51))
    #expect(await until { rig.link.transfers.count == 1 })
    #expect(rig.link.messages.isEmpty)
    #expect(rig.link.transfers.map(\.botId) == ["b1"])
    await rig.fake.emit(reply("a2", seq: 8, rev: 52))
    #expect(await until { rig.link.chats.last?.entries.last?.id == "a2" })
    #expect(rig.link.transfers.count == 1)
}

@MainActor @Test func approvalRequestIsPushedToTheWatch() async throws {
    let rig = Rig()
    defer { rig.finish() }
    await rig.connect()
    await rig.sendAndClose()
    await rig.fake.emit(card("p1", seq: 6, rev: 60))
    await rig.fake.emit(bot("b1", rev: 61, status: "needsInput"))
    #expect(await until { rig.link.chats.contains { $0.entries.contains { $0.id == "p1" } } })
}

@MainActor @Test func settlingReleasesTheLinkThenEndsTheAssertion() async throws {
    let rig = Rig()
    defer { rig.finish() }
    await rig.fake.setShutdownDelay(.milliseconds(500))
    await rig.connect()
    await rig.sendAndClose()
    await rig.fake.emit(bot("b1", rev: 60, status: "working"))
    await rig.fake.emit(reply("a1", seq: 7, rev: 61))
    await rig.fake.emit(bot("b1", rev: 62, status: "idle"))
    #expect(await until { await rig.fake.shutdownCount == 1 })
    #expect(rig.system.ended.isEmpty)
    #expect(await until { !rig.system.ended.isEmpty })
    // The assertion outlived the shutdown.
    #expect(await rig.fake.shutdownFinished == 1)
    #expect(rig.system.ended == rig.system.began)
}

@MainActor @Test func deadlineReleasesTheLinkThenEndsTheAssertion() async throws {
    let rig = Rig(exchange: .milliseconds(250))
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
    let rig = Rig(exchange: .seconds(30), margin: .seconds(5))
    defer { rig.finish() }
    await rig.connect()
    rig.system.backgroundTimeRemaining = 5.1
    await rig.sendAndClose()
    #expect(await until { await rig.fake.shutdownCount == 1 })
    #expect(await until { !rig.system.ended.isEmpty })
}

@MainActor @Test func expirationReleasesSynchronously() async throws {
    let rig = Rig()
    defer { rig.finish() }
    await rig.connect()
    // A separate hold keeps the link open, so only the bridge's guards stop the reply.
    let call = UUID()
    rig.store.beginHold(call, botId: "b1")
    await rig.sendAndClose()
    rig.system.expire()
    // Nothing awaited: the assertion is already over.
    #expect(rig.system.ended == [1])
    await rig.fake.emit(reply("a1", seq: 7, rev: 51))
    #expect(await until { rig.store.chat("b1").contains { $0.id == "a1" } })
    await pause()
    #expect(rig.link.messages.isEmpty && rig.link.transfers.isEmpty)
    #expect(rig.system.ended == [1])
    #expect(await rig.fake.shutdownCount == 0)
    await rig.store.endHold(call)
    #expect(await rig.fake.shutdownCount == 1)
}

@MainActor @Test func unauthorizedStoreEndsTheExchange() async throws {
    let rig = Rig()
    defer { rig.finish() }
    await rig.connect()
    await rig.sendAndClose()
    await rig.fake.set(.unauthorized("revoked"))
    #expect(await until { rig.store.connection != .online })
    #expect(await until { await rig.fake.shutdownCount == 1 })
}

@MainActor @Test func voiceCallAndWatchHoldCoexist() async throws {
    let rig = Rig(exchange: .milliseconds(250))
    defer { rig.finish() }
    await rig.connect()
    let call = UUID()
    rig.store.beginHold(call, botId: "b1")
    await rig.sendAndClose()
    // The exchange's deadline passes; the call still has the link.
    #expect(await until { !rig.system.ended.isEmpty })
    await pause(200)
    #expect(await rig.fake.shutdownCount == 0)
    await rig.store.endHold(call)
    #expect(await rig.fake.shutdownCount == 1)
}

// MARK: - Lease

@MainActor @Test func leaseExpiresWithoutRenewalAndRenewalKeepsIt() async throws {
    let rig = Rig(lease: .milliseconds(800))
    defer { rig.finish() }
    await rig.connect()
    for _ in 0..<4 {
        await pause(150)
        #expect(rig.request(.renew(rig.scope, open: nil)) != nil)
    }
    #expect(await rig.fake.shutdownCount == 0)
    #expect(await until { await rig.fake.shutdownCount == 1 })
    #expect(await until { rig.system.ended == rig.system.began })
}

@MainActor @Test func closeEndsTheLeaseOnItsOwn() async throws {
    let rig = Rig()
    defer { rig.finish() }
    await rig.connect()
    #expect(rig.request(.close(rig.scope)) == .accepted)
    #expect(await until { await rig.fake.shutdownCount == 1 })
}

@MainActor @Test func chatRequestReturnsTheCacheAndMarksItRead() async throws {
    let rig = Rig()
    defer { rig.finish() }
    rig.link.isReachable = true
    await rig.connect()
    await rig.fake.emit(reply("a1", seq: 3, rev: 5, text: "hello"))
    #expect(await until { rig.store.chat("b1").count == 1 })
    guard case let .chat(chat)? = rig.request(.chat(rig.scope, botId: "b1")) else {
        Issue.record("no chat")
        return
    }
    #expect(chat.entries.map(\.id) == ["a1"] && chat.fresh)
    #expect(await until { await rig.fake.readAttempts >= 1 })
    // The open chat follows changes by message.
    await rig.fake.emit(reply("a2", seq: 4, rev: 6))
    #expect(await until { rig.link.chats.last?.entries.map(\.id) == ["a1", "a2"] })
    #expect(rig.request(.chat(rig.scope, botId: "nope")) == .failed(.notFound))
}

// MARK: - Respond

@MainActor @Test func approvalIsAnsweredOnceAndAnExchangeStarts() async throws {
    let rig = Rig()
    defer { rig.finish() }
    await rig.connect()
    await rig.fake.emit(card("p1", seq: 5, rev: 5))
    #expect(await until { rig.store.chat("b1").contains { $0.id == "p1" } })
    #expect(rig.request(.respond(rig.scope, botId: "b1", entryId: "p1", optionId: "a")) == .accepted)
    #expect(rig.request(.respond(rig.scope, botId: "b1", entryId: "p1", optionId: "a")) == .accepted)
    rig.request(.close(rig.scope))
    await pause()
    #expect(await rig.fake.calls.filter { $0 == "respondPermission" }.count == 1)
    #expect(await rig.fake.shutdownCount == 0) // the answer's exchange holds the link
    #expect(rig.request(.respond(rig.scope, botId: "b1", entryId: "zzz", optionId: "a")) == .failed(.notFound))
}

@MainActor @Test func approvalForACardNotCaughtUpYetWaitsForIt() async throws {
    let rig = Rig()
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
    await rig.fake.emit(card("p1", seq: 5, rev: 9))
    #expect(await until { await rig.fake.calls.contains("respondPermission") })
}

// MARK: - Scope, versions, gating

@MainActor @Test func staleScopeIsRejectedAndTheSnapshotRepublished() async throws {
    let rig = Rig()
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
    let rig = Rig()
    defer { rig.finish() }
    let old = WatchEnvelope(version: "1.0.0", minPeer: "1.0.0", message: .request(id: UUID(), .hello))
    let response = try WatchEnvelope.decode(rig.bridge.answer(try old.encode()))
    guard case .response(_, .failed(.needsUpdate)) = response.message else {
        Issue.record("not refused")
        return
    }
}

@MainActor @Test func nothingIsEncodedWithoutAnInstalledWatchApp() async throws {
    let rig = Rig(installed: false)
    defer { rig.finish() }
    #expect(rig.request(.hello) == nil)
    await pause()
    #expect(rig.link.contexts.isEmpty && rig.link.messages.isEmpty)
    #expect(rig.system.began.isEmpty)
    // Installing it starts publishing.
    rig.link.isWatchAppInstalled = true
    rig.link.onChange?()
    #expect(rig.link.snapshots.count == 1)
}

// MARK: - Observation

@MainActor @Test func snapshotIsPublishedOnlyWhenItsContentChanges() async throws {
    let rig = Rig(coalesce: .milliseconds(200))
    defer { rig.finish() }
    await rig.connect()
    #expect(await until { rig.link.snapshots.last?.bots.map(\.id) == ["b1"] })
    await pause(500)
    let stable = rig.link.contexts.count
    let snapshot = try #require(rig.link.snapshots.last)
    #expect(snapshot.state == .online && snapshot.fresh && snapshot.computerName == "Mac")
    #expect(snapshot.scope == rig.scope)
    // The same bot again: observers fire, the payload doesn't change.
    await rig.fake.emit(bot("b1", rev: 1))
    await pause(500)
    #expect(rig.link.contexts.count == stable)
    // Three quick changes are one publish.
    for rev in 2...4 { await rig.fake.emit(bot("b1", rev: rev, status: "working")) }
    #expect(await until { rig.link.contexts.count > stable })
    await pause(500)
    #expect(rig.link.contexts.count == stable + 1)
}

// MARK: - Accounts

@MainActor @Test func bindDropsExchangesPostsNothingForTheOldStoreAndPublishesTheNewScope() async throws {
    let rig = Rig()
    defer { rig.finish() }
    await rig.connect()
    let call = UUID()
    rig.store.beginHold(call, botId: "b1") // keeps the old link open: only the bridge's guards stop the reply
    await rig.sendAndClose()

    // Sign-out: an empty account.
    let suite = "CodyncUITests.\(UUID().uuidString)"
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let empty = AccountStore(storage: SharedStore.Context(accountID: nil, suite: suite), clientKind: "ios", cloud: nil, active: false)
    defer { empty.retire() }
    rig.bridge.bind(empty)
    #expect(rig.link.cancelCount == 1)
    #expect(rig.link.snapshots.last?.scope == nil && rig.link.snapshots.last?.state == .notPaired)
    // The old exchange is gone: its reply, arriving on the still-open link, goes nowhere.
    await rig.fake.emit(reply("a1", seq: 7, rev: 51))
    #expect(await until { rig.store.chat("b1").contains { $0.id == "a1" } })
    await pause()
    #expect(await rig.fake.shutdownCount == 0)
    await rig.store.endHold(call)
    #expect(rig.link.messages.isEmpty && rig.link.transfers.isEmpty)
    #expect(rig.request(.send(rig.scope, botId: "b1", text: "hi", nonce: "n2")) == .failed(.noComputer))

    // Switching to an account with a computer.
    let other = Rig()
    defer { other.finish() }
    rig.bridge.bind(other.accounts)
    #expect(rig.link.snapshots.last?.scope == other.scope)
    #expect(rig.link.cancelCount == 2)
}

// MARK: - Holds (BotStore)

@MainActor @Test func reconnectedStoreIsNotLiveUntilItsCatchUpArrives() async throws {
    let rig = Rig()
    defer { rig.finish() }
    await rig.connect()
    rig.request(.close(rig.scope))
    #expect(await until { await rig.fake.shutdownCount == 1 })
    rig.store.beginHold(UUID(), botId: nil)
    #expect(await until { await rig.fake.subscribed })
    await pause(50)
    #expect(!rig.store.isLive)
    await rig.fake.emit(#"{"type":"hello","hostId":"h1","rev":1}"#)
    #expect(await until { rig.store.isLive })
}

@MainActor @Test func heldStoreAcknowledgesOnlyTheWatchsOpenChat() async throws {
    let rig = Rig()
    defer { rig.finish() }
    await rig.connect()
    await rig.fake.emit(bot("b2", name: "Bo", rev: 2, extra: #","unread":1"#))
    #expect(rig.request(.chat(rig.scope, botId: "b1")) != nil)
    #expect(await until { await rig.fake.readAttempts >= 1 })
    await rig.fake.emit(reply("a1", seq: 3, rev: 5))
    await rig.fake.emit(#"{"type":"entry","entry":{"id":"x1","seq":4,"botId":"b2","rev":6,"kind":"agent","turn":1,"data":{"text":"hi","final":true},"createdAt":1,"updatedAt":1}}"#)
    await pause()
    let receipts = await rig.fake.readReceipts.map { String(decoding: $0, as: UTF8.self) }
    #expect(!receipts.isEmpty && receipts.allSatisfy { $0.contains("b1") && !$0.contains("b2") })
}

@MainActor @Test func needsInputThenIdleSettlesOnce() async throws {
    let rig = Rig()
    defer { rig.finish() }
    await rig.connect()
    var settled = 0
    rig.store.beginHold(UUID(), botId: "b1", settled: { _ in settled += 1 })
    await rig.fake.emit(bot("b1", rev: 2, status: "working"))
    await rig.fake.emit(bot("b1", rev: 3, status: "needsInput"))
    await rig.fake.emit(bot("b1", rev: 4, status: "idle"))
    #expect(await until { settled == 1 })
    await pause()
    #expect(settled == 1)
}

@MainActor @Test func idleInsideTheCatchUpDoesNotSettle() async throws {
    let rig = Rig()
    defer { rig.finish() }
    await rig.connect()
    await rig.fake.emit(bot("b1", rev: 2, status: "working"))
    #expect(await until { rig.store.bots["b1"]?.isWorking == true })
    rig.request(.close(rig.scope))
    #expect(await until { await rig.fake.shutdownCount == 1 })
    var settled = 0
    rig.store.beginHold(UUID(), botId: "b1", settled: { _ in settled += 1 })
    #expect(await until { await rig.fake.subscribed })
    // The cached "working" is old news: the catch-up says idle.
    await rig.fake.emit(#"{"type":"hello","hostId":"h1","rev":5}"#)
    await rig.fake.emit(bot("b1", rev: 5, status: "idle"))
    #expect(await until { rig.store.isLive })
    await pause()
    #expect(settled == 0)
    await rig.fake.emit(bot("b1", rev: 6, status: "working"))
    await rig.fake.emit(bot("b1", rev: 7, status: "idle"))
    #expect(await until { settled == 1 })
}

@MainActor @Test func retiringAStoreEndsEveryHold() async throws {
    let rig = Rig()
    defer { rig.finish() }
    await rig.connect()
    var ended = 0
    for _ in 0..<3 { rig.store.beginHold(UUID(), botId: "b1", end: { ended += 1 }) }
    rig.store.retire()
    #expect(ended == 3)
}

// MARK: - Hardening

@MainActor @Test func unlimitedBackgroundTimeDoesNotTrap() async throws {
    let rig = Rig()
    defer { rig.finish() }
    await rig.connect()
    rig.system.backgroundTimeRemaining = .greatestFiniteMagnitude
    #expect(rig.send() == .accepted)
    #expect(rig.request(.renew(rig.scope, open: nil)) != nil)
    #expect(await until { await rig.fake.sent == ["hi"] })
}

@MainActor @Test func failedPushFallsBackToTransfer() async throws {
    let rig = Rig()
    defer { rig.finish() }
    rig.link.isReachable = true
    await rig.connect()
    await rig.sendAndClose()
    rig.link.failPushes = true
    await rig.fake.emit(reply("a1", seq: 7, rev: 51))
    #expect(await until { rig.link.transfers.count == 1 })
    #expect(rig.link.messages.isEmpty)
}

@MainActor @Test func approvalRequestGoesByMessageWhenReachable() async throws {
    let rig = Rig()
    defer { rig.finish() }
    rig.link.isReachable = true
    await rig.connect()
    await rig.sendAndClose()
    await rig.fake.emit(card("p1", seq: 6, rev: 60))
    await rig.fake.emit(bot("b1", rev: 61, status: "needsInput"))
    #expect(await until { rig.link.chats.contains { $0.entries.contains { $0.id == "p1" } } })
    #expect(rig.link.transfers.isEmpty)
}

@MainActor @Test func idleOfAnEarlierTurnDoesNotEndTheExchange() async throws {
    let rig = Rig()
    defer { rig.finish() }
    await rig.connect()
    await rig.fake.emit(bot("b1", rev: 10, status: "working")) // the previous turn
    #expect(await until { rig.store.bots["b1"]?.isWorking == true })
    await rig.sendAndClose(started: false)
    // The previous turn's reply lands after the watch's message (turn 0 < the message's turn 1), then its idle.
    await rig.fake.emit(reply("a0", seq: 5, rev: 11, turn: 0))
    await rig.fake.emit(bot("b1", rev: 12, status: "idle"))
    #expect(await until { rig.store.bots["b1"]?.isWorking == false })
    await pause(150)
    #expect(await rig.fake.shutdownCount == 0)
    #expect(rig.link.transfers.isEmpty)
    // The watch's own turn starts (its message is `sent`), answers, and goes idle.
    await rig.markStarted(rev: 13)
    await rig.fake.emit(bot("b1", rev: 14, status: "working"))
    await rig.fake.emit(reply("a1", seq: 7, rev: 15))
    await rig.fake.emit(bot("b1", rev: 16, status: "idle"))
    #expect(await until { await rig.fake.shutdownCount == 1 })
    #expect(rig.link.transfers.count == 1)
}

@MainActor @Test func groupSettlesOnlyAfterItStaysIdle() async throws {
    let rig = Rig(groupGrace: .milliseconds(400))
    defer { rig.finish() }
    await rig.connect()
    await rig.fake.emit(bot("g1", name: "Team", rev: 2, extra: #","kind":"group","members":["b1","b2"]"#))
    #expect(await until { rig.store.bots["g1"] != nil })
    await rig.sendAndClose(to: "g1")
    await rig.fake.emit(bot("g1", name: "Team", rev: 60, status: "working", extra: #","kind":"group","members":["b1","b2"]"#))
    await rig.fake.emit(reply("a1", seq: 7, rev: 61, chat: "g1", author: "b1"))
    // Idle between members, then the second member starts within the grace.
    await rig.fake.emit(bot("g1", name: "Team", rev: 62, extra: #","kind":"group","members":["b1","b2"]"#))
    await rig.fake.emit(bot("g1", name: "Team", rev: 63, status: "working", extra: #","kind":"group","members":["b1","b2"]"#))
    await pause(700)
    #expect(await rig.fake.shutdownCount == 0)
    await rig.fake.emit(reply("a2", seq: 8, rev: 64, chat: "g1", author: "b2"))
    await rig.fake.emit(bot("g1", name: "Team", rev: 65, extra: #","kind":"group","members":["b1","b2"]"#))
    #expect(await until { await rig.fake.shutdownCount == 1 })
}

@MainActor @Test func replyInAResyncCatchUpIsStillForwarded() async throws {
    let rig = Rig()
    defer { rig.finish() }
    await rig.connect()
    await rig.sendAndClose()
    await rig.fake.emit(#"{"type":"resync"}"#)
    #expect(await until { await rig.fake.eventSubscriptionCount == 2 })
    #expect(await until { !rig.store.isLive })
    await rig.fake.emit(#"{"type":"hello","hostId":"h1","rev":70}"#)
    await rig.fake.emit(reply("a1", seq: 7, rev: 62))
    #expect(await until { rig.link.chats.contains { $0.entries.contains { $0.id == "a1" } } })
}

@MainActor @Test func eventStreamRestartIsNotLiveUntilItsCatchUp() async throws {
    let rig = Rig()
    defer { rig.finish() }
    await rig.connect()
    #expect(rig.store.isLive)
    await rig.fake.emit(#"{"type":"resync"}"#)
    #expect(await until { await rig.fake.eventSubscriptionCount == 2 })
    #expect(!rig.store.isLive)
    await rig.fake.emit(#"{"type":"hello","hostId":"h1","rev":1}"#)
    #expect(await until { rig.store.isLive })
}

@MainActor @Test func backgroundingBeginsTheAssertionAndReclampsTimers() async throws {
    let rig = Rig(appActive: true)
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
    let rig = Rig(appActive: true)
    defer { rig.finish() }
    await rig.connect()
    rig.accounts.setActive(false)
    rig.system.isAppActive = false
    rig.system.denyTasks = true
    rig.bridge.appActiveChanged()
    #expect(await until { await rig.fake.shutdownCount == 1 })
}

@MainActor @Test func sendIsRefusedWhenTheBackgroundAssertionIsDenied() async throws {
    let rig = Rig(appActive: true)
    defer { rig.finish() }
    await rig.connect()
    rig.system.isAppActive = false
    rig.system.denyTasks = true
    #expect(rig.send() == .failed(.unavailable))
    #expect(rig.request(.respond(rig.scope, botId: "b1", entryId: "p1", optionId: "a")) == .failed(.unavailable))
    await pause()
    #expect(await rig.fake.sent.isEmpty)
    // No time to hold for either.
    rig.system.denyTasks = false
    rig.system.backgroundTimeRemaining = 4
    #expect(rig.send(nonce: "n2") == .failed(.unavailable))
}

@MainActor @Test func renewAndChatAreUnavailableWhenNoLeaseCanBeHeld() async throws {
    let rig = Rig()
    defer { rig.finish() }
    rig.system.denyTasks = true
    // hello still answers, so the watch can show the cached state.
    guard case .snapshot? = rig.request(.hello) else { Issue.record("hello must answer a snapshot"); return }
    #expect(rig.request(.renew(rig.scope, open: nil)) == .failed(.unavailable))
    #expect(rig.request(.renew(rig.scope, open: "b1")) == .failed(.unavailable))
    await pause()
    #expect(await !rig.fake.subscribed)
    // Once the phone can hold the link, both answer again.
    rig.system.denyTasks = false
    guard case .snapshot? = rig.request(.renew(rig.scope, open: nil)) else { Issue.record("renew must answer a snapshot"); return }
    #expect(await until { await rig.fake.subscribed })
}

@MainActor @Test func retiredStoreClosesBeforeTheAssertionEnds() async throws {
    let rig = Rig()
    defer { rig.finish() }
    await rig.fake.setShutdownDelay(.milliseconds(500))
    await rig.connect()
    await rig.sendAndClose()
    rig.store.retire()
    #expect(rig.system.ended.isEmpty)
    #expect(await until { !rig.system.ended.isEmpty })
    #expect(await rig.fake.shutdownFinished == 1)
}

// MARK: - Exchanges: cards, resend, stale close

@MainActor @Test func answeredCardForwardsTheTurnsFinalTextEvenBeforeTheCard() async throws {
    let rig = Rig()
    defer { rig.finish() }
    await rig.connect()
    await rig.fake.emit(card("p1", seq: 5, rev: 5))
    #expect(await until { rig.store.chat("b1").contains { $0.id == "p1" } })
    await rig.fake.emit(bot("b1", rev: 6, status: "needsInput"))
    #expect(rig.request(.respond(rig.scope, botId: "b1", entryId: "p1", optionId: "a")) == .accepted)
    rig.request(.close(rig.scope))
    // The turn's final text has a lower seq than the card; the turn number is what counts.
    await rig.fake.emit(reply("a1", seq: 4, rev: 7))
    #expect(await until { rig.link.chats.contains { $0.entries.contains { $0.id == "a1" } } })
    // The first idle after the answer settles it.
    await rig.fake.emit(bot("b1", rev: 8, status: "working"))
    await rig.fake.emit(bot("b1", rev: 9, status: "idle"))
    #expect(await until { await rig.fake.shutdownCount == 1 })
}

@MainActor @Test func sendSettlesOnlyOnceItsMessageStartedATurn() async throws {
    let rig = Rig()
    defer { rig.finish() }
    await rig.connect()
    await rig.sendAndClose(started: false)
    await rig.fake.emit(bot("b1", rev: 50, status: "working"))
    await rig.fake.emit(bot("b1", rev: 51, status: "idle"))
    await rig.fake.emit(reply("x1", seq: 9, rev: 52, turn: 2)) // someone else's later entry
    await pause(150)
    #expect(await rig.fake.shutdownCount == 0)
}

@MainActor @Test func closeWithAStaleScopeStillEndsTheLease() async throws {
    let rig = Rig()
    defer { rig.finish() }
    await rig.connect()
    let stale = WatchScope(context: "other", computer: "gone")
    #expect(rig.request(.close(stale)) == .failed(.staleScope))
    #expect(await until { await rig.fake.shutdownCount == 1 })
}

// MARK: - Which stream the host sees

@MainActor @Test func inactiveWatchHoldDoesNotCountAsAPhone() async throws {
    let rig = Rig()
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
    let rig = Rig()
    defer { rig.finish() }
    await rig.connect()
    #expect(await rig.fake.eventClients == [nil])
    let call = UUID()
    rig.store.beginHold(call, botId: "b1", mutesPushes: true)
    #expect(await until { await rig.fake.eventClients == [nil, "ios"] })
    // The call ends: only the watch lease remains, which is not a phone.
    await rig.store.endHold(call)
    #expect(await until { await rig.fake.eventClients == [nil, "ios", nil] })
    await pause(100)
    #expect(await rig.fake.eventClients.count == 3)
}

@MainActor @Test func activeAppSubscribesAsAPhone() async throws {
    let rig = Rig(appActive: true)
    defer { rig.finish() }
    await rig.connect()
    #expect(await rig.fake.eventClients == ["ios"])
}
