#if os(macOS)
import AppKit
import SwiftUI
import Testing
@testable import CodyncUI

@MainActor @Observable private final class MenuState {
    var open = false
}

@Test @MainActor func firstMenuPresentationBuildsItsChoices() async throws {
    let host = ModalHost()
    let state = MenuState()
    var menuBuilt = false
    let window = NSWindow(contentRect: CGRect(x: 0, y: 0, width: 600, height: 500),
                          styleMask: [.borderless], backing: .buffered, defer: false)
    let trigger = NSHostingView(rootView: Text("React")
        .codyncMenu(isPresented: Binding(get: { state.open }, set: { state.open = $0 })) {
            menuBuilt = true
            return [MenuItem("Test reaction") {}]
        }
        .environment(\.modalHost, host))
    trigger.frame = CGRect(x: 0, y: 0, width: 100, height: 40)
    window.contentView = trigger
    trigger.layoutSubtreeIfNeeded()
    try await Task.sleep(for: .milliseconds(100))
    #expect(host.entries.isEmpty)
    state.open = true
    trigger.layoutSubtreeIfNeeded()
    try await Task.sleep(for: .milliseconds(100))
    trigger.layoutSubtreeIfNeeded()
    let entry = try #require(host.entries.first)
    let panel = NSHostingView(rootView: entry.content)
    panel.frame = CGRect(x: 0, y: 0, width: 600, height: 500)
    window.contentView = panel
    panel.layoutSubtreeIfNeeded()
    try await Task.sleep(for: .milliseconds(100))
    panel.layoutSubtreeIfNeeded()
    #expect(menuBuilt)
}

@Test @MainActor func reactionOnlyMenuUsesOneCompactRow() {
    let pick = ReactionPick(emoji: ["👍", "❤️", "😂", "🎉", "👀", "✅"], chosen: []) { _ in }
    let controller = NSHostingController(rootView: MenuPanel(items: [], reactions: pick, dismiss: {}))
    let size = controller.sizeThatFits(in: CGSize(width: 600, height: 500))
    #expect(size.width <= 180)
    #expect(size.height <= 50)
}
#endif
