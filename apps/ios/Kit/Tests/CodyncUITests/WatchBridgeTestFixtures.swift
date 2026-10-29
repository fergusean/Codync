import CryptoKit
import Foundation
import Testing
@testable import CodyncKit
@testable import CodyncUI

// WatchBridge against a real `AccountStore` / `BotStore` on `FakeRemote` (StoreTests.swift), with a
// fake WatchConnectivity link and fake system effects.

@MainActor
final class FakeWatchLink: WatchLink {
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
final class FakeWatchSystem: WatchSystem {
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

private func watchTestComputer(_ name: String) -> Computer {
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

func watchTestPause(_ milliseconds: Int = 100) async { try? await Task.sleep(for: .milliseconds(milliseconds)) }

func watchTestBotEvent(_ id: String, name: String = "Ada", rev: Int, status: String = "idle", extra: String = "") -> String {
    #"{"type":"bot","bot":{"id":"\#(id)","name":"\#(name)","rev":\#(rev),"lastAt":\#(rev),"status":"\#(status)"\#(extra)}}"#
}

func watchTestReplyEvent(_ id: String, seq: Int, rev: Int, text: String = "answer", chat: String = "b1", author: String? = nil, turn: Int = 1) -> String {
    let by = author.map { #","author":"\#($0)""# } ?? ""
    return #"{"type":"entry","entry":{"id":"\#(id)","seq":\#(seq),"botId":"\#(chat)","rev":\#(rev),"kind":"agent","turn":\#(turn),"data":{"text":"\#(text)","final":true\#(by)},"createdAt":1,"updatedAt":1}}"#
}

func watchTestCardEvent(_ id: String, seq: Int, rev: Int) -> String {
    #"{"type":"entry","entry":{"id":"\#(id)","seq":\#(seq),"botId":"b1","rev":\#(rev),"kind":"permission","turn":1,"data":{"title":"Run npm test","status":"pending","options":[{"optionId":"a","name":"Allow once","kind":"allow_once"}]},"createdAt":1,"updatedAt":1}}"#
}

/// One account with one computer (bot `b1`, "Ada") behind a `FakeRemote`, and a bridge on it.
@MainActor
final class WatchBridgeTestRig {
    let fake = FakeRemote(.ready(.direct))
    let link = FakeWatchLink()
    let system = FakeWatchSystem()
    let computer = watchTestComputer("Mac")
    let storage: SharedStore.Context
    private let suite: String
    let accounts: AccountStore
    let bridge: WatchBridge
    var store: BotStore { accounts.currentStore! }
    var scope: WatchScope { WatchScope(context: storage.id, computer: computer.id) }

    init(appActive: Bool = false, installed: Bool = true, exchange: Duration = .seconds(10), lease: Duration = .seconds(10),
         coalesce: Duration = .milliseconds(20), margin: Duration = .seconds(5),
         groupGrace: Duration = .milliseconds(300), timerSleep: (@Sendable (Duration) async throws -> Void)? = nil) {
        suite = "CodyncUITests.\(UUID().uuidString)"
        storage = SharedStore.Context(accountID: "user_\(UUID().uuidString)", suite: suite)
        storage.computers = [computer]
        let (computer, fake, storage) = (computer, fake, storage)
        accounts = AccountStore(storage: storage, cloud: nil, active: appActive) { _ in
            BotStore(computer: computer, storage: storage) { _ in fake }
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
        if let timerSleep { durations.timerSleep = timerSleep }
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
        await fake.emit(watchTestBotEvent("b1", rev: 1))
        #expect(await until { store.connection == .online && store.isLive })
    }

    func send(_ text: String = "hi", nonce: String = "n1", to botId: String = "b1") -> WatchResponse? {
        request(.send(scope, botId: botId, text: text, nonce: nonce))
    }

    /// The host starts the turn for the message: its entry gets `status: sent`.
    func markStarted(_ botId: String = "b1", rev: Int = 40) async {
        await fake.emit(#"{"type":"entry","entry":{"id":"e1","seq":1,"botId":"\#(botId)","rev":\#(rev),"kind":"user","turn":1,"data":{"text":"hi","status":"sent","clientNonce":"n1"},"createdAt":1,"updatedAt":1}}"#)
        #expect(await until { store.chat(botId).first { $0.id == "e1" }?.data.status == "sent" })
    }

    /// A send answered by the host, the watch closed: only the exchange holds the link.
    func sendAndClose(to botId: String = "b1", started: Bool = true) async {
        #expect(send(to: botId) == .accepted)
        #expect(await until { store.chat(botId).contains { $0.id == "e1" } })
        if started { await markStarted(botId) }
        request(.close(scope))
    }
}
