#if os(macOS)
import Foundation
import AppKit
import SwiftUI
import Testing
@testable import CodyncUI

@Test func prependingHistoryKeepsTheSamePixelInsideTheMessage() {
    var layout = MacChatLayout()
    layout.reset(ids: ["a", "b"], heights: [200, 600])
    let anchor = layout.anchor(at: 278)!
    layout.reset(ids: ["older", "a", "b"], heights: [350, 200, 600])
    #expect(layout.offset(for: anchor) == 628)
    #expect(layout.anchor(at: 628) == anchor)
}

@Test func measuringRowsAboveTheReaderPreservesTheirAnchor() {
    var layout = MacChatLayout()
    layout.reset(ids: ["a", "b", "c"], heights: [100, 100, 100])
    let anchor = layout.anchor(at: 230)!
    let changed = layout.measure(800, at: 0)
    #expect(changed)
    #expect(layout.offset(for: anchor) == 930)
    #expect(layout.anchor(at: 930) == anchor)
}

@Test func longHistoryMountsOnlyTheViewportAndOverscan() {
    var layout = MacChatLayout()
    layout.reset(ids: (0..<10_000).map(String.init), heights: Array(repeating: 100, count: 10_000))
    let range = layout.visible(in: CGRect(x: 0, y: 500_008, width: 700, height: 600), overscan: 600)
    #expect(range == 4994..<5013)
    #expect(range.count < 20)
}

@Test func oneTallMessageDoesNotMountThousandsOfOtherRows() {
    var layout = MacChatLayout()
    layout.reset(ids: ["before", "article", "after"], heights: [100, 20_000, 100])
    #expect(layout.visible(in: CGRect(x: 0, y: 5000, width: 700, height: 600), overscan: 600) == 1..<2)
}

@Test func emptyHistoryAndDeletedAnchorsHaveNoInvalidIndices() {
    var layout = MacChatLayout()
    #expect(layout.index(at: 0) == nil)
    #expect(layout.visible(in: .zero, overscan: 100).isEmpty)
    layout.reset(ids: ["a"], heights: [100])
    let anchor = layout.anchor(at: 10)!
    layout.reset(ids: [], heights: [])
    #expect(layout.offset(for: anchor) == nil)
}

@Test @MainActor func nativeScrollingReplacesOffscreenHostingViews() async {
    struct Message: Identifiable, Equatable { let id: String }
    let items = (0..<200).map { Message(id: String($0)) }
    let list = MacChatList(items: items, following: .constant(false)) { item in
        Text(item.id).frame(height: 100)
    }
    let coordinator = list.makeCoordinator()
    let scroll = ChatScrollView()
    scroll.frame = CGRect(x: 0, y: 0, width: 700, height: 600)
    coordinator.attach(scroll)
    coordinator.update(list, environment: EnvironmentValues())
    coordinator.arrange()
    let before = Set(coordinator.cells.keys)
    scroll.contentView.scroll(to: CGPoint(x: 0, y: 5000))
    // No empty frame is exposed while waiting for a dispatch turn after a large jump.
    #expect(coordinator.cells["50"] != nil)
    #expect(coordinator.cells.values.allSatisfy { !$0.controller.view.isHidden && $0.controller.view.frame.height > 0 })
    // Bounds notifications schedule mounting on the next main run-loop turn.
    try? await Task.sleep(for: .milliseconds(50))
    #expect(!before.isEmpty)
    #expect(Set(coordinator.cells.keys).isDisjoint(with: before))
    #expect(coordinator.cells.count < 25)
    #expect(coordinator.cells["50"] != nil)
    #expect(scroll.contentView.bounds.minY == 5000)
}
#endif
