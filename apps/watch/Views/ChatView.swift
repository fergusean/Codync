import CodyncKit
import SwiftUI

/// One bot's recent main chat, bottom-anchored like the iPhone's.
struct ChatView: View {
    let botId: String
    @Environment(WatchStore.self) private var store
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var bot: Bot? { store.bot(botId) }
    private var entries: [Entry] { store.entries(for: botId) }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 6) {
                    if let chat = store.mirror.chats[botId],
                       let status = store.statusText(freshAt: chat.builtAt, fresh: chat.fresh) {
                        StatusLine(text: status, warning: store.phoneOut)
                    }
                    content
                    if let bot, bot.isWorking(in: botId, thread: nil) {
                        WorkingLine(bot: bot).padding(.top, 4)
                    }
                    Color.clear.frame(height: 1).id(Self.bottom)
                }
                .padding(.horizontal, 4)
            }
            .defaultScrollAnchor(.bottom)
            .onChange(of: entries.last?.id) {
                withAnimation(Motion.reduced(Motion.conversation, reduceMotion)) { proxy.scrollTo(Self.bottom, anchor: .bottom) }
            }
        }
        .navigationTitle(bot?.name ?? "")
        .toolbar {
            ToolbarItem(placement: .bottomBar) {
                DictationButton { store.send($0, to: botId) }
            }
        }
        .onAppear { store.openChat(botId) }
        .onDisappear { store.closeChat(botId) }
    }

    private static let bottom = "bottom"

    @ViewBuilder private var content: some View {
        if store.mirror.chats[botId] == nil && entries.isEmpty {
            // Nothing cached: say why it isn't coming, rather than that the chat is empty.
            StateView(kind: store.phoneOut ? (store.link.needsUnlock && !store.link.isReachable ? .unlockPhone : .unreachable) : .loading,
                      fullScreen: false)
                .transition(.opacity)
        } else if entries.isEmpty {
            StateView(kind: .noMessages, fullScreen: false).transition(.opacity)
        } else {
            ForEach(entries) { entry in
                ChatRow(entry: entry, isGroup: bot?.isGroup == true)
                    .transition(.opacity)
            }
        }
    }
}

/// Renders one chat-visible entry the way the iPhone does, scaled for the wrist.
private struct ChatRow: View {
    let entry: Entry
    let isGroup: Bool
    @Environment(WatchStore.self) private var store

    var body: some View {
        switch entry.kind {
        case "user": UserBubble(entry: entry)
        case "agent":
            VStack(alignment: .leading, spacing: 4) {
                if isGroup { AuthorLabel(botId: entry.data.author) }
                bubble(markdown(entry.data.text ?? ""), fill: Palette.bubbleAgent)
            }
        case "permission":
            VStack(alignment: .leading, spacing: 4) {
                if isGroup { AuthorLabel(botId: entry.data.author) }
                ApprovalCard(entry: entry, answering: store.answering(entry)) { store.respond(to: entry, optionId: $0) }
            }
        default:
            NoticeRow(entry: entry)
        }
    }

    private func bubble(_ text: Text, fill: Color) -> some View {
        text
            .font(.footnote)
            .foregroundStyle(Palette.text)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(fill, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

/// Inline markdown (bold, italics, code, links); text that doesn't parse stays plain.
private func markdown(_ text: String) -> Text {
    let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
    if let parsed = try? AttributedString(markdown: text, options: options) { return Text(parsed) }
    return Text(text)
}

/// The user's message, right-aligned, with where it is on its way and Resend when it failed.
private struct UserBubble: View {
    let entry: Entry
    @Environment(WatchStore.self) private var store

    var body: some View {
        VStack(alignment: .trailing, spacing: 3) {
            Text(entry.data.text ?? "")
                .font(.footnote)
                .foregroundStyle(Palette.text)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Palette.bubbleUser, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .padding(.leading, 20)
            if let state = store.sendState(entry) {
                HStack(spacing: 8) {
                    Text(state.label(botWorking: store.bot(entry.botId)?.isWorking == true))
                        .foregroundStyle(state == .failed ? Palette.danger : Palette.tertiary)
                    if state == .failed {
                        Button { store.resend(entry) } label: { Image(systemName: "arrow.clockwise") }
                            .buttonStyle(.plain)
                            .foregroundStyle(Palette.text)
                            .accessibilityLabel("Resend")
                    }
                }
                .font(.caption2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
    }
}

/// A group message's author: their face and name in their color.
private struct AuthorLabel: View {
    let botId: String?
    @Environment(WatchStore.self) private var store

    var body: some View {
        let author = botId.flatMap { store.bot($0) }
        HStack(spacing: 6) {
            if let author { CharacterAvatar(bot: author, size: 16, animated: false) }
            Text(author?.name ?? "Bot")
                .font(.caption2.weight(.medium))
                .foregroundStyle(author.map { AvatarPalette.color($0.avatarColor) } ?? Palette.secondary)
        }
    }
}

/// Voice-call logs, dividers, errors and plain notices, as on the iPhone.
private struct NoticeRow: View {
    let entry: Entry

    var body: some View {
        let text = entry.data.text ?? ""
        if let seconds = entry.data.callSeconds {
            Label("\(text) · \(String(format: "%02d:%02d", seconds / 60, seconds % 60))", systemImage: "waveform")
                .font(.caption2.monospacedDigit())
                .foregroundStyle(Palette.secondary)
                .frame(maxWidth: .infinity)
        } else if entry.data.style == "error" {
            Label {
                Text(text).font(.caption2).foregroundStyle(Palette.text)
            } icon: {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Palette.danger)
            }
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.danger.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        } else {
            Text(text)
                .font(.caption2)
                .foregroundStyle(entry.data.style == "divider" ? Palette.tertiary : Palette.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
        }
    }
}

/// What the bot is doing right now: a turning orb, its activity and for how long.
private struct WorkingLine: View {
    let bot: Bot

    var body: some View {
        HStack(spacing: 6) {
            ThinkingOrb(state: bot.needsInput ? .listening : .working, size: 14, color: bot.needsInput ? Palette.warning : Palette.secondary)
            Text(bot.activity.isEmpty ? "Working…" : bot.activity)
                .font(.caption2)
                .foregroundStyle(bot.needsInput ? Palette.warning : Palette.secondary)
                .lineLimit(1)
            if let started = bot.startedAt {
                Text(Date(milliseconds: started), style: .timer)
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(Palette.tertiary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}
