import CryptoKit
import Foundation
import Testing
@testable import CodyncKit
@testable import CodyncUI

private func helloJSON(version: String, minApp: String? = nil, backends: String = "[]") -> String {
    let min = minApp.map { #","minApp":"\#($0)""# } ?? ""
    return #"{"hostId":"h1","name":"Mac","version":"\#(version)"\#(min),"os":"macos","backends":\#(backends),"rev":0}"#
}

@MainActor @Test func hostNeedingANewerAppStopsSyncAndAsksForTheUpdate() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.relay))
    await fake.setHello(helloJSON(version: "2.6.0", minApp: "2.5.0"))
    let store = BotStore(computer: randomComputer("Mac"), clientKind: "ios", storage: storage, appVersion: "2.4.0") { fake }
    defer { store.retire() }
    store.setActive(true)
    #expect(await until { store.mismatch == .updateApp(minimum: "2.5.0") })
    #expect(await until { store.connection == .online })
    try await Task.sleep(for: .milliseconds(200))
    // Nothing this app can't read is synced.
    #expect(await !fake.subscribed)
}

@MainActor @Test func updatedHostIsNoticedOnReconnect() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.relay))
    await fake.setHello(helloJSON(version: "2.2.0"))
    let store = BotStore(computer: randomComputer("Mac"), clientKind: "ios", storage: storage, appVersion: "2.4.0") { fake }
    defer { store.retire() }
    store.setActive(true)
    #expect(await until { store.mismatch == .updateHost(version: "2.2.0", minimum: VersionMismatch.minHost) })
    // The host updates and restarts: the link drops and comes back.
    await fake.setHello(helloJSON(version: "2.4.0", minApp: "2.3.0"))
    await fake.set(.connecting)
    await fake.set(.ready(.relay))
    #expect(await until { store.mismatch == nil })
    #expect(await until { await fake.subscribed })
    #expect(store.hostVersion == HostVersion(version: "2.4.0", minApp: "2.3.0"))
}

@MainActor @Test func unreadableHelloStillSaysWhichSideToUpdate() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    // A newer host whose hello this app can't decode, with or without a minApp that covers it.
    for minApp in ["2.6.0", "2.0.0"] {
        let fake = FakeRemote(.ready(.relay))
        await fake.setHello(helloJSON(version: "2.6.0", minApp: minApp, backends: #""changed shape""#))
        let store = BotStore(computer: randomComputer("Mac"), clientKind: "ios", storage: storage, appVersion: "2.4.0") { fake }
        store.setActive(true)
        #expect(await until { store.mismatch == .updateApp(minimum: "2.6.0") }, "minApp \(minApp)")
        #expect(store.hello == nil)
        store.retire()
    }
}

@MainActor @Test func unreadableEventsAskForAnAppUpdateOnlyFromANewerHost() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    for (hostVersion, appIsBehind) in [("2.6.0", true), ("2.4.0", false)] {
        let fake = FakeRemote(.ready(.relay))
        await fake.setHello(helloJSON(version: hostVersion, minApp: "2.3.0"))
        let store = BotStore(computer: randomComputer("Mac"), clientKind: "ios", storage: storage, appVersion: "2.4.0") { fake }
        store.setActive(true)
        let broken = #"{"type":"bot","bot":{"id":"b1","name":7}}"#
        #expect(await until { await fake.eventSubscriptionCount == 1 })
        await fake.emit(broken)
        // The first one rewinds and subscribes again; the second one is final.
        #expect(await until { await fake.eventSubscriptionCount == 2 })
        await fake.emit(broken)
        if appIsBehind {
            #expect(await until { store.mismatch == .updateApp(minimum: hostVersion) })
        } else {
            try await Task.sleep(for: .milliseconds(200))
            #expect(store.mismatch == nil)
        }
        store.retire()
    }
}

@MainActor @Test func composerDraftsPersistAcrossNavigationAndReopening() {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let computer = randomComputer("Mac")
    let store = BotStore(computer: computer, clientKind: "ios", storage: storage) { FakeRemote(.connecting) }
    let text = "  中文\nunfinished 🐱\n"
    store.setComposerDraft(text, for: "first")
    store.setComposerDraft("Other draft", for: "second")
    store.setComposerDraft("Reply", for: "first", thread: "root")
    store.selection = "second"
    #expect(store.composerDraft(for: "first") == text)
    store.retire()
    store.setComposerDraft("late edit", for: "first")
    let reopened = BotStore(computer: computer, clientKind: "ios", storage: storage) { FakeRemote(.connecting) }
    defer { reopened.retire() }
    #expect(reopened.composerDraft(for: "first") == text)
    #expect(reopened.composerDraft(for: "first", thread: "root") == "Reply")
    reopened.setComposerDraft("", for: "first")
    #expect(reopened.composerDraft(for: "first").isEmpty)
    #expect(reopened.composerDraft(for: "second") == "Other draft")
    #expect(reopened.composerDraft(for: "first", thread: "root") == "Reply")
    let other = BotStore(computer: randomComputer("Other"), clientKind: "ios", storage: storage) { FakeRemote(.connecting) }
    defer { other.retire() }
    #expect(other.composerDraft(for: "first").isEmpty)
    storage.erase()
    #expect(storage.composerDrafts.isEmpty)
}

@MainActor @Test func sendingDraftNeverClearsAnotherConversationOrLaterText() async {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.direct))
    let store = BotStore(computer: randomComputer("Mac"), clientKind: "ios", storage: storage) { fake }
    defer { store.retire() }
    store.setActive(true)
    #expect(await until { await fake.subscribed })
    await fake.emit(botEvent("first", name: "First", rev: 1))
    #expect(await until { store.connection == .online })
    store.setComposerDraft("First message", for: "first")
    store.setComposerDraft("Other draft", for: "second")
    store.setComposerDraft("Reply", for: "first", thread: "root")
    #expect(store.sendComposerDraft(to: "first"))
    #expect(store.composerDraft(for: "first").isEmpty)
    store.setComposerDraft("Next unfinished message", for: "first")
    #expect(await until { store.chat("first").last?.id == "e1" })
    #expect(store.composerDraft(for: "first") == "Next unfinished message")
    #expect(store.composerDraft(for: "second") == "Other draft")
    #expect(store.composerDraft(for: "first", thread: "root") == "Reply")
    let anotherAccount = SharedStore.Context(accountID: "another-user", suite: suite)
    #expect(anotherAccount.composerDrafts.isEmpty)
}

@MainActor @Test func existingCachesKeepTheirMessagesAndLocalSends() throws {
    let (storage, suite) = context()
    defer { storage.erase(); UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let computer = randomComputer("Fixture")
    let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "0"
    let url = URL.cachesDirectory.appending(path: "codync-mirror-\(Bundle.main.bundleIdentifier ?? "app")-\(storage.id)-\(computer.id).json")
    let cached = #"""
    {"stamp":"\#(build)/3.0.0","hostId":"h1","rev":77,"bots":[],"entries":[
      {"id":"notice","seq":1,"botId":"dex","rev":77,"kind":"notice","turn":1,"createdAt":1,"updatedAt":1,
       "data":{"text":"Message from Miles","status":"completed"}},
      {"id":"local-pending","seq":2,"botId":"dex","rev":0,"kind":"user","turn":1,"createdAt":2,"updatedAt":2,
       "data":{"text":"My unsent message","status":"failed"}}
    ]}
    """#
    try Data(cached.utf8).write(to: url)
    let store = BotStore(computer: computer, clientKind: "ios", storage: storage) { FakeRemote(.ready(.direct)) }
    defer { store.retire() }
    // Adding conversation metadata does not invalidate the existing base cache.
    #expect(store.allEntries("dex").map(\.id) == ["notice", "local-pending"])
    #expect(store.allEntries("dex").last?.data.text == "My unsent message")
    store.saveCache()
    let bytes = try Data(contentsOf: url)
    let cache = try #require(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
    #expect(cache["rev"] as? Int == 77)
}

private func entryEvent(_ id: String, seq: Int, rev: Int, turn: Int = 5, text: String = "hi") -> String {
    #"{"type":"entry","entry":{"id":"\#(id)","seq":\#(seq),"botId":"b1","rev":\#(rev),"kind":"agent","turn":\#(turn),"data":{"text":"\#(text)","final":true},"createdAt":1,"updatedAt":1}}"#
}

private func windowEntry(_ id: String, seq: Int64, rev: Int64 = 1, threadId: String? = nil) -> Entry {
    var data = EntryData(text: id)
    data.final = true
    return Entry(id: id, seq: seq, botId: "b1", threadId: threadId, rev: rev, kind: "agent", turn: 0, data: data, createdAt: 1, updatedAt: 1)
}

@MainActor @Test func windowRuleDropsOnlyUnknownMainChatEntriesBelowTheFloor() {
    let below = windowEntry("a", seq: 5)
    #expect(BotStore.isOutsideLoadedWindow(below, floor: 100, held: false))
    #expect(!BotStore.isOutsideLoadedWindow(below, floor: 100, held: true))
    #expect(!BotStore.isOutsideLoadedWindow(windowEntry("a", seq: 100), floor: 100, held: false))
    #expect(!BotStore.isOutsideLoadedWindow(windowEntry("a", seq: 150), floor: 100, held: false))
    #expect(!BotStore.isOutsideLoadedWindow(below, floor: nil, held: false))
    #expect(!BotStore.isOutsideLoadedWindow(windowEntry("a", seq: 5, threadId: "t"), floor: 100, held: false))
}

/// A connection that resumes from a rev > 0 leaves a rewritten old entry to history paging.
@MainActor @Test func resumedConnectionDropsRewrittenEntriesBelowTheMirrorsFloor() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.direct))
    let store = BotStore(computer: randomComputer("Mac"), clientKind: "ios", storage: storage) { fake }
    defer { store.retire() }
    store.upsert(windowEntry("e100", seq: 100))
    store.upsert(windowEntry("local-1", seq: 0)) // optimistic: not a floor
    store.rev = 10
    store.setActive(true)
    #expect(await until { await fake.subscribed })
    await fake.emit(#"{"type":"hello","hostId":"h1","rev":20}"#)
    await fake.emit(botEvent("b1", name: "Bot", rev: 11))
    // Rewritten old entry: dropped, though its rev is consumed.
    await fake.emit(entryEvent("old", seq: 5, rev: 12, turn: 0))
    // New entry above the floor and an update to a held one apply.
    await fake.emit(entryEvent("e101", seq: 101, rev: 13))
    await fake.emit(entryEvent("e100", seq: 100, rev: 14, text: "edited"))
    #expect(await until { store.chat("b1").first { $0.id == "e100" }?.data.text == "edited" && store.chat("b1").contains { $0.id == "e101" } })
    #expect(!store.chat("b1").contains { $0.id == "old" })
    #expect(store.rev == 14)
    // History results still insert older entries.
    store.upsert(windowEntry("old", seq: 5))
    #expect(store.chat("b1").contains { $0.id == "old" })
}

/// From rev 0 the catch-up is rev-ordered, not seq-ordered, and a sent message's RPC response may
/// land first: nothing is below a floor.
@MainActor @Test func freshCatchUpAcceptsEntriesInRevOrder() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.direct))
    let store = BotStore(computer: randomComputer("Mac"), clientKind: "ios", storage: storage) { fake }
    defer { store.retire() }
    store.upsert(windowEntry("sent", seq: 100)) // RPC response before the catch-up
    store.setActive(true)
    #expect(await until { await fake.subscribed })
    await fake.emit(#"{"type":"hello","hostId":"h1","rev":20}"#)
    await fake.emit(botEvent("b1", name: "Bot", rev: 1))
    await fake.emit(entryEvent("e50", seq: 50, rev: 2, turn: 0))
    await fake.emit(entryEvent("e5", seq: 5, rev: 3, turn: 0))
    #expect(await until { store.chat("b1").map(\.id) == ["e5", "e50", "sent"] })
}
