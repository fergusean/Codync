#if os(macOS)
import AppKit
import SwiftUI
import Testing
@testable import CodyncKit

@MainActor
private struct AvatarFrame: View {
    let phase: ScenePhase

    var body: some View {
        CharacterAvatar(shape: "blob", color: "blue", size: 72, mood: .working)
            .padding(8)
            .background(Color.white)
            .environment(\.scenePhase, phase)
    }
}

@MainActor
private func settle(_ view: NSView, for seconds: Double) async throws {
    let deadline = Date.now.addingTimeInterval(seconds)
    repeat {
        view.layoutSubtreeIfNeeded()
        view.displayIfNeeded()
        try await Task.sleep(for: .milliseconds(10))
    } while Date.now < deadline
}

@MainActor
private func pixels(_ view: NSView) throws -> Data {
    let bitmap = try #require(view.bitmapImageRepForCachingDisplay(in: view.bounds))
    view.cacheDisplay(in: view.bounds, to: bitmap)
    return try #require(bitmap.representation(using: .png, properties: [:]))
}

@Test @MainActor
func workingAvatarPausesAndResumesWithItsScene() async throws {
    _ = NSApplication.shared
    let view = NSHostingView(rootView: AvatarFrame(phase: .active))
    let window = NSWindow(contentRect: NSRect(x: -10000, y: 0, width: 88, height: 88),
                          styleMask: [.borderless], backing: .buffered, defer: false)
    window.isReleasedWhenClosed = false
    window.contentView = view
    window.orderFront(nil)
    defer { window.close() }

    try await settle(view, for: 0.15)
    let active = try pixels(view)
    try await settle(view, for: 0.5)
    let laterActive = try pixels(view)
    #expect(active != laterActive)

    view.rootView = AvatarFrame(phase: .inactive)
    try await settle(view, for: 0.15)
    let inactive = try pixels(view)
    try await settle(view, for: 0.5)
    let laterInactive = try pixels(view)
    #expect(inactive == laterInactive)

    view.rootView = AvatarFrame(phase: .active)
    try await settle(view, for: 0.15)
    let resumed = try pixels(view)
    try await settle(view, for: 0.5)
    let laterResumed = try pixels(view)
    #expect(resumed != laterResumed)
}
#endif
