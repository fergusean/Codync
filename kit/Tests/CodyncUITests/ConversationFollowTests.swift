#if os(macOS)
import AppKit
import SwiftUI
import Testing
@testable import CodyncUI

@MainActor private final class NativeChatHarness {
    struct Message: Identifiable, Equatable {
        let id: String
        let height: CGFloat
    }
    typealias List = MacChatList<Message, AnyView>
    let scroll = ChatScrollView()
    let coordinator = List.Coordinator()
    var following = true
    var renders = 0
    var insets = EdgeInsets()

    init() {
        scroll.frame = CGRect(x: 0, y: 0, width: 700, height: 600)
        coordinator.attach(scroll)
    }

    func show(_ messages: [Message]) {
        let list = List(items: messages, following: Binding(get: { self.following }, set: { self.following = $0 })) { item in
            self.render(item)
        }
        var environment = EnvironmentValues()
        environment.macChatInsets = insets
        coordinator.update(list, environment: environment)
        coordinator.arrange()
    }

    func render(_ item: Message) -> AnyView {
        renders += 1
        return AnyView(Text(item.id).frame(height: item.height))
    }
}

@Test @MainActor func contentGrowthFollowsOnlyWhenTheReaderIsAtTheEnd() {
    let chat = NativeChatHarness()
    chat.show([.init(id: "reply", height: 2000)])
    #expect(chat.scroll.contentView.bounds.minY == 1420)
    chat.show([.init(id: "reply", height: 2500)])
    #expect(chat.scroll.contentView.bounds.minY == 1920)

    chat.following = false
    chat.show([.init(id: "reply", height: 2500)])
    chat.scroll.contentView.scroll(to: CGPoint(x: 0, y: 400))
    chat.show([.init(id: "reply", height: 3000)])
    #expect(chat.scroll.contentView.bounds.minY == 400)
    #expect(!chat.coordinator.follows)
}

@Test @MainActor func jumpToLatestRestoresFollowingAfterReadingHistory() {
    let chat = NativeChatHarness()
    chat.following = false
    chat.show([.init(id: "reply", height: 3000)])
    chat.scroll.contentView.scroll(to: CGPoint(x: 0, y: 400))
    chat.following = true
    chat.show([.init(id: "reply", height: 3000)])
    #expect(chat.scroll.contentView.bounds.minY == 2420)
    #expect(chat.coordinator.follows)
}

@Test @MainActor func scrollingInsideAMountedMessageDoesNotRebuildItsSwiftUIRoot() {
    let chat = NativeChatHarness()
    chat.following = false
    chat.show([.init(id: "article", height: 20_000)])
    let before = chat.renders
    for y in stride(from: 100, through: 2000, by: 100) {
        chat.scroll.contentView.scroll(to: CGPoint(x: 0, y: CGFloat(y)))
        chat.coordinator.arrange()
    }
    #expect(chat.renders == before)
    #expect(chat.coordinator.cells.count == 1)
}

@Test @MainActor func resizingTheViewportKeepsTheNewestMessageAtTheBottom() {
    let chat = NativeChatHarness()
    chat.show([.init(id: "article", height: 2000)])
    chat.scroll.setFrameSize(CGSize(width: 700, height: 800))
    chat.scroll.layoutSubtreeIfNeeded()
    chat.coordinator.arrange()
    #expect(chat.scroll.contentView.bounds.maxY == chat.coordinator.layout.total)
}

@Test @MainActor func pageCommandsReleaseAndRestoreFollowing() {
    let chat = NativeChatHarness()
    chat.show([.init(id: "article", height: 2000)])
    chat.scroll.pageUp(nil)
    chat.coordinator.arrange()
    #expect(!chat.coordinator.follows)
    #expect(chat.scroll.contentView.bounds.minY < 1420)
    chat.scroll.scrollToEndOfDocument(nil)
    chat.coordinator.arrange()
    #expect(chat.coordinator.follows)
    #expect(chat.scroll.contentView.bounds.minY == 1420)
}

@Test @MainActor func anOldBindingUpdateCannotPullTheReaderBackToTheBottom() {
    let chat = NativeChatHarness()
    let messages: [NativeChatHarness.Message] = [.init(id: "article", height: 3000)]
    chat.show(messages)
    let renders = chat.renders
    chat.coordinator.setFollowing(false)
    chat.scroll.contentView.scroll(to: CGPoint(x: 0, y: 400))
    // The representable can update before the deferred binding report is delivered.
    #expect(chat.following)
    chat.show(messages)
    #expect(!chat.coordinator.follows)
    #expect(chat.scroll.contentView.bounds.minY == 400)
    #expect(chat.renders == renders)
}

@Test @MainActor func aReplacedRowsHeightReportCannotMoveTheCurrentConversation() {
    let chat = NativeChatHarness()
    chat.following = false
    chat.show([.init(id: "article", height: 3000)])
    let retiredGeneration = chat.coordinator.cells["article"]!.generation
    chat.scroll.contentView.scroll(to: CGPoint(x: 0, y: 400))
    chat.show([.init(id: "article", height: 3200)])
    chat.coordinator.heightChanged(6000, id: "article", width: chat.coordinator.width, generation: retiredGeneration)
    chat.coordinator.arrange()
    #expect(chat.coordinator.layout.heights == [3200])
    #expect(chat.scroll.contentView.bounds.minY == 400)
}

@Test @MainActor func floatingChromeReservesDocumentSpaceWithoutShrinkingTheViewport() {
    let chat = NativeChatHarness()
    chat.insets = EdgeInsets(top: 70, leading: 0, bottom: 80, trailing: 0)
    chat.show([.init(id: "article", height: 2000)])
    #expect(chat.scroll.contentSize.height == 600)
    #expect(chat.coordinator.layout.starts == [78])
    let messageEnd = chat.coordinator.layout.starts[0] + chat.coordinator.layout.heights[0]
    #expect(chat.scroll.contentView.bounds.maxY - messageEnd == 92)
    chat.coordinator.setFollowing(false)
    chat.scroll.contentView.scroll(to: CGPoint(x: 0, y: 100))
    chat.coordinator.arrange()
    #expect(chat.scroll.contentView.bounds.minY > chat.coordinator.layout.starts[0])
}
#endif
