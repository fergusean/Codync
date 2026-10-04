#if os(macOS)
import CodyncKit
import SwiftUI

extension RepliesView {
    /// Replies share the native virtualized transcript with the main conversation.
    @ViewBuilder var messages: some View {
        let replies = model.replies(botId, root: rootId)
        let items = ChatItem.build(replies, streaming: chat?.isWorking(in: botId, thread: rootId) == true && !model.isOffline, steady: true)
        let rows: [MacReplyItem] = (root.map { [.root($0, replies.count)] } ?? [])
            + items.compactMap { item in
                if case .entry = item.kind { return .message(item) }
                return nil
            }
            + (chat?.isWorking(in: botId, thread: rootId) == true && !model.isOffline
               ? [.working(model.currentThinking(botId, thread: rootId))] : [])
        MacChatList(items: rows, following: $following, revision: chat.map(AnyHashable.init)) { item in
            switch item {
            case .root(let entry, let count):
                VStack(alignment: .leading, spacing: 0) {
                    ChatRow(entry: entry, groupStart: true, chat: chat) { showTrace = true }.equatable()
                    HStack(spacing: 10) {
                        Text(count == 0 ? "No replies yet" : count == 1 ? "1 reply" : "\(count) replies")
                            .appFont(.caption).foregroundStyle(Palette.tertiary).fixedSize()
                        Rectangle().fill(Palette.border).frame(height: 1)
                    }
                    .padding(.vertical, 12)
                }
            case .message(let message):
                if case let .entry(entry, groupStart) = message.kind {
                    ChatRow(entry: entry, groupStart: groupStart, chat: chat) { showTrace = true }.equatable()
                }
            case .working(let thinking):
                if let chat { WorkingIndicator(bot: chat, thinking: thinking).padding(.top, 6) }
            }
        }
        .onChange(of: items.last?.id) { _, _ in
            if items.last?.isUserMessage == true { following = true }
        }
        .overlay(alignment: .bottom) {
            MacJumpToLatest(visible: !following && !items.isEmpty) { following = true }
        }
    }
}
private enum MacReplyItem: Identifiable, Equatable {
    case root(Entry, Int)
    case message(ChatItem)
    case working(String?)

    var id: String {
        switch self {
        case .root(let entry, _): "root-\(entry.id)"
        case .message(let item): "message-\(item.id)"
        case .working: "working"
        }
    }
}
#endif
