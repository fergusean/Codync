#if os(macOS)
import AppKit
import SwiftUI
import Testing
import Vision
@testable import CodyncKit
@testable import CodyncUI

@MainActor @Test func rapidlyCompletedReplyUpdatesInTheLiveThreadView() async throws {
    guard #available(macOS 15.0, *) else { return }
    _ = NSApplication.shared
    let suite = "StreamingRenderingTests.\(UUID())"
    let storage = SharedStore.Context(accountID: suite, suite: suite)
    defer { storage.erase(); UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.direct))
    let computer = Computer(id: "streaming-fixture", name: "Fixture", signKey: "")
    let store = BotStore(computer: computer, route: .channel, clientKind: "macos", storage: storage) { fake }
    defer { store.retire() }
    store.setActive(true)
    try await waitFor { await fake.subscribed }
    await fake.emit(botEvent(status: "idle", rev: 1))
    try await waitFor { store.bots["b"] != nil }
    for seq in 1...40 {
        await fake.emit(try entryEvent(id: "history-\(seq)", seq: Int64(seq), rev: Int64(seq + 1),
                                     text: "Earlier reply \(seq). " + String(repeating: "Conversation history with several wrapped lines. ", count: 4), final: true))
    }
    try await waitFor { store.allEntries("b").count == 40 }

    let hosting = NSHostingView(rootView: ThreadView(botId: "b")
        .environment(store)
        .environment(\.conversationTypography, ConversationTypography(pointSize: 14)))
    let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 620, height: 560), styleMask: [.borderless], backing: .buffered, defer: false)
    window.isReleasedWhenClosed = false
    window.appearance = NSAppearance(named: .aqua)
    window.contentView = hosting
    defer { window.close() }
    window.orderBack(nil)
    hosting.layoutSubtreeIfNeeded()
    hosting.displayIfNeeded()
    try await Task.sleep(for: .seconds(1))

    let complete = "`example-project` was already checked out at `/srv/git/example-project`, so I pulled `_dev` there and started the agent in that same directory."
    for (index, delay) in [16, 57, 150, 450].enumerated() {
        let seq = Int64(index + 41)
        let rev = seq * 10
        await fake.emit(botEvent(status: "working", rev: rev))
        await fake.emit(try entryEvent(id: "reply-\(seq)", seq: seq, rev: rev + 1, text: "`", final: false))
        try await waitFor { store.allEntries("b").last?.data.text == "`" }
        try await Task.sleep(for: .milliseconds(delay))
        let marker = "Reply \(index + 1) is complete."
        // The host flushes the completed text before marking the same entry final.
        await fake.emit(try entryEvent(id: "reply-\(seq)", seq: seq, rev: rev + 2, text: complete + " " + marker, final: false))
        await fake.emit(try entryEvent(id: "reply-\(seq)", seq: seq, rev: rev + 3, text: complete + " " + marker, final: true))
        await fake.emit(botEvent(status: "idle", rev: rev + 4))
        try await waitFor { store.allEntries("b").last?.data.final == true }
        try await Task.sleep(for: .seconds(1))
        hosting.layoutSubtreeIfNeeded()
        hosting.displayIfNeeded()
        let bitmap = try #require(hosting.bitmapImageRepForCachingDisplay(in: hosting.bounds))
        hosting.cacheDisplay(in: hosting.bounds, to: bitmap)
        let png = try #require(bitmap.representation(using: .png, properties: [:]))
        let rendered = try await recognizeText(png)
        #expect(rendered.contains(marker),
                "The live conversation retained an earlier reply revision after a \(delay)ms completion: \(rendered)")
    }
}

private func botEvent(status: String, rev: Int64) -> String {
    #"{"type":"bot","bot":{"id":"b","name":"Fixture","status":"\#(status)","rev":\#(rev),"lastAt":1}}"#
}

@MainActor @Test func incomingBotMessagesWithEmptyNoncesRemainVisible() async throws {
    _ = NSApplication.shared
    let suite = "IncomingBotMessageTests.\(UUID())"
    let storage = SharedStore.Context(accountID: suite, suite: suite)
    defer { storage.erase(); UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.direct))
    let store = BotStore(computer: Computer(id: "incoming-fixture", name: "Fixture", signKey: ""),
                         route: .channel, clientKind: "macos", storage: storage) { fake }
    defer { store.retire() }
    store.setActive(true)
    try await waitFor { await fake.subscribed }
    await fake.emit(botEvent(status: "idle", rev: 1))
    let oldMarker = "Old incoming message remains visible."
    await fake.emit(try entryEvent(id: "old-incoming", seq: 1, rev: 2, text: oldMarker + "\n\n" + String(repeating: "An earlier bot submitted this request. ", count: 5), final: false, kind: "user", nonce: ""))
    await fake.emit(try entryEvent(id: "old-reply", seq: 2, rev: 3, text: "Earlier response before the next incoming alert.", final: true))
    try await waitFor { store.allEntries("b").count == 2 }
    let hosting = NSHostingView(rootView: ThreadView(botId: "b")
        .environment(store)
        .environment(\.conversationTypography, ConversationTypography(pointSize: 14)))
    let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 620, height: 1080), styleMask: [.borderless], backing: .buffered, defer: false)
    window.isReleasedWhenClosed = false
    window.appearance = NSAppearance(named: .aqua)
    window.contentView = hosting
    defer { window.close() }
    hosting.layoutSubtreeIfNeeded()
    hosting.displayIfNeeded()
    try await Task.sleep(for: .milliseconds(600))
    let newMarker = "New incoming message fills this space."
    await fake.emit(botEvent(status: "working", rev: 4))
    await fake.emit(try entryEvent(id: "new-incoming", seq: 3, rev: 5,
                                 text: newMarker + "\n\n" + String(repeating: "The latest incoming alert must render between the preceding response and its final reply. ", count: 15),
                                 final: false, kind: "user", nonce: ""))
    try await waitFor { store.allEntries("b").count == 3 }
    try await Task.sleep(for: .milliseconds(600))
    await fake.emit(try entryEvent(id: "new-reply", seq: 4, rev: 6, text: "Final response after the incoming alert.", final: true))
    await fake.emit(botEvent(status: "idle", rev: 7))
    try await waitFor { store.allEntries("b").count == 4 }
    try await Task.sleep(for: .seconds(1))
    hosting.layoutSubtreeIfNeeded()
    hosting.displayIfNeeded()
    let bitmap = try #require(hosting.bitmapImageRepForCachingDisplay(in: hosting.bounds))
    hosting.cacheDisplay(in: hosting.bounds, to: bitmap)
    let png = try #require(bitmap.representation(using: .png, properties: [:]))
    let rendered = try await recognizeText(png)
    #expect(rendered.contains(oldMarker), "Earlier incoming message disappeared: \(rendered)")
    #expect(rendered.contains(newMarker), "Latest incoming message left a blank space: \(rendered)")
}

private func entryEvent(id: String, seq: Int64, rev: Int64, text: String, final: Bool, kind: String = "agent", nonce: String? = nil) throws -> String {
    var data = EntryData(text: text, clientNonce: nonce)
    data.final = final
    let entry = Entry(id: id, seq: seq, botId: "b", rev: rev, kind: kind, turn: seq,
                      data: data, createdAt: seq, updatedAt: rev)
    let encoded = try JSONEncoder().encode(entry)
    return "{\"type\":\"entry\",\"entry\":\(String(decoding: encoded, as: UTF8.self))}"
}

@MainActor private func waitFor(_ condition: @MainActor () async -> Bool) async throws {
    for _ in 0..<200 {
        if await condition() { return }
        try await Task.sleep(for: .milliseconds(10))
    }
    Issue.record("Timed out waiting for the scripted event")
}

private func recognizeText(_ png: Data) async throws -> String {
    let task = Task.detached {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        let handler = VNImageRequestHandler(data: png)
        try handler.perform([request])
        return (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }.joined(separator: " ")
    }
    return try await task.value
}
#endif
