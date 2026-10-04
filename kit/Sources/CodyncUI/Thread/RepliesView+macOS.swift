#if os(macOS)
import CodyncKit
import SwiftUI

extension RepliesView {
    /// The Mac's replies: a lazy stack that opens at the newest.
    @ViewBuilder var messages: some View {
        let replies = model.replies(botId, root: rootId)
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if let root {
                    ChatRow(entry: root, groupStart: true, chat: chat) { showTrace = true }
                    HStack(spacing: 10) {
                        Text(replies.isEmpty ? "No replies yet" : replies.count == 1 ? "1 reply" : "\(replies.count) replies")
                            .appFont(.caption)
                            .foregroundStyle(Palette.tertiary)
                            .fixedSize()
                        Rectangle().fill(Palette.border).frame(height: 1)
                    }
                    .padding(.vertical, 12)
                }
                ForEach(ChatItem.build(replies, streaming: chat?.isWorking(in: botId, thread: rootId) == true && !model.isOffline)) { item in
                    if case let .entry(e, groupStart) = item.kind {
                        ChatRow(entry: e, groupStart: groupStart, chat: chat) { showTrace = true }
                    }
                }
                if let chat, chat.isWorking(in: botId, thread: rootId), !model.isOffline {
                    WorkingIndicator(bot: chat, thinking: model.currentThinking(botId, thread: rootId)).padding(.top, 6)
                }
                Color.clear.frame(height: 8)
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
        }
        .scrollIndicators(.never)
        .defaultScrollAnchor(.bottom)
        .scrollDismissesKeyboard(.interactively)
    }
}
#endif
