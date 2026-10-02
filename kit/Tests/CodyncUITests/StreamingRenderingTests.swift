#if os(macOS)
import AppKit
import Observation
import SwiftUI
import Testing
@testable import CodyncKit
@testable import CodyncUI

@MainActor @Observable
private final class StreamedReply {
    var text = "The"
    var streaming = true
}

@available(macOS 15.0, *)
private struct StreamingConversation: View {
    let reply: StreamedReply
    let store: BotStore

    private var entry: Entry {
        var data = EntryData(text: reply.text)
        data.final = !reply.streaming
        return Entry(id: "reply", seq: 1, botId: "b", rev: 1, kind: "agent", turn: 1,
                     data: data, createdAt: 1, updatedAt: 1)
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ChatRow(entry: entry, groupStart: true, chat: nil, openTrace: {})
                        .id("reply")
                    Color.clear.frame(height: 8).id("bottom")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .defaultScrollAnchor(.bottom, for: .initialOffset)
            .onChange(of: reply.text) { _, _ in
                withAnimation(Motion.conversation) { proxy.scrollTo("bottom", anchor: .bottom) }
            }
        }
        .background(Color.white)
        .environment(store)
        .environment(\.conversationTypography, ConversationTypography(pointSize: 14))
    }
}

@MainActor @Test func streamedReplyShowsItsCompletedTextWithoutReopening() async throws {
    guard #available(macOS 15.0, *) else { return }
    _ = NSApplication.shared
    let suite = "StreamingRenderingTests.\(UUID())"
    let storage = SharedStore.Context(accountID: suite, suite: suite)
    defer { storage.erase(); UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.direct))
    let computer = Computer(id: "streaming-fixture", name: "Fixture", signKey: "")
    let store = BotStore(computer: computer, route: .channel, clientKind: "macos", storage: storage) { fake }
    defer { store.retire() }
    let reply = StreamedReply()
    let hosting = NSHostingView(rootView: StreamingConversation(reply: reply, store: store))
    let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 620, height: 560), styleMask: [.borderless], backing: .buffered, defer: false)
    window.isReleasedWhenClosed = false
    window.appearance = NSAppearance(named: .aqua)
    window.contentView = hosting
    defer { window.close() }

    try await settle(hosting)
    let first = try textPixels(hosting)
    #expect(first > 20)
    let complete = """
    The nightly backup succeeded this morning. The response must grow beyond its first streamed word.

    **Backup status.** The schedule is enabled, storage is available, and there are no errors. The metrics outage is separate from the backup result.

    - The first item is visible.
    - The second item is visible.

    Nothing on the cluster was changed.
    """
    for length in [16, 60, 140, 240, complete.count] {
        reply.text = String(complete.prefix(length))
        try await Task.sleep(for: .milliseconds(300))
    }
    reply.streaming = false
    try await settle(hosting)
    let final = try textPixels(hosting)
    #expect(final > first * 8, "Completed reply rendered only \(final) text pixels; first word rendered \(first)")
}

@MainActor private func settle(_ hosting: NSView) async throws {
    for _ in 0..<40 {
        hosting.layoutSubtreeIfNeeded()
        hosting.displayIfNeeded()
        try await Task.sleep(for: .milliseconds(20))
    }
}

@MainActor private func textPixels(_ hosting: NSView) throws -> Int {
    let bitmap = try #require(hosting.bitmapImageRepForCachingDisplay(in: hosting.bounds))
    hosting.cacheDisplay(in: hosting.bounds, to: bitmap)
    var count = 0
    for y in stride(from: 0, to: bitmap.pixelsHigh, by: 2) {
        for x in stride(from: 0, to: bitmap.pixelsWide, by: 2) {
            guard let color = bitmap.colorAt(x: x, y: y)?.usingColorSpace(.deviceRGB) else { continue }
            if color.alphaComponent > 0.9 && color.redComponent < 0.5
                && color.greenComponent < 0.5 && color.blueComponent < 0.5 {
                count += 1
            }
        }
    }
    return count
}
#endif
