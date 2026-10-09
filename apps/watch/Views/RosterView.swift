import CodyncKit
import SwiftUI

/// The bots on the phone's current computer, in the phone's order.
struct RosterView: View {
    @Environment(WatchStore.self) private var store

    var body: some View {
        Group {
            if let update = store.update {
                StateView(kind: .needsUpdate(update))
            } else if let snapshot = store.snapshot {
                if snapshot.scope == nil || snapshot.state == .notPaired {
                    StateView(kind: .notPaired)
                } else {
                    list(snapshot)
                }
            } else if store.link.needsUnlock && !store.link.isReachable {
                StateView(kind: .unlockPhone)
            } else {
                StateView(kind: .openPhone)
            }
        }
        .navigationTitle("Codync")
    }

    private func list(_ snapshot: WatchSnapshot) -> some View {
        List {
            header(snapshot)
                .listRowBackground(Color.clear)
            if snapshot.bots.isEmpty {
                StateView(kind: .noBots, fullScreen: false)
                    .listRowBackground(Color.clear)
            }
            ForEach(snapshot.bots) { bot in
                NavigationLink(value: bot.id) { BotRow(bot: bot, members: store.members(of: bot)) }
                    .listRowBackground(Palette.bubbleAgent.clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)))
            }
        }
    }

    /// The computer and how the phone is connected to it.
    private func header(_ snapshot: WatchSnapshot) -> some View {
        VStack(spacing: 2) {
            if let name = snapshot.computerName {
                Text(name).font(.footnote.weight(.semibold)).foregroundStyle(Palette.text).lineLimit(1)
            }
            Text(snapshot.state.label)
                .font(.caption2)
                .foregroundStyle(snapshot.state == .online ? Palette.tertiary : Palette.secondary)
            if let status = store.statusText(freshAt: snapshot.builtAt, fresh: snapshot.fresh) {
                StatusLine(text: status, warning: store.phoneOut)
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }
}

private struct BotRow: View {
    let bot: Bot
    let members: [Bot]

    var body: some View {
        HStack(spacing: 8) {
            AvatarWithStatus(bot: bot, members: members, size: 32)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(bot.name)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Palette.text)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                    if bot.lastAt > 0 {
                        Text(RelativeTime.short(Date(milliseconds: bot.lastAt)))
                            .font(.caption2)
                            .foregroundStyle(Palette.tertiary)
                    }
                }
                preview
            }
        }
        .accessibilityElement(children: .combine)
    }

    /// Live activity while working, otherwise the last message (the iPhone row's wording).
    @ViewBuilder private var preview: some View {
        if bot.needsInput {
            line(bot.activity.isEmpty ? "Needs your approval" : bot.activity, color: Palette.warning, orb: .listening)
        } else if bot.isWorking {
            line(bot.activity.isEmpty ? "Working…" : bot.activity, color: Palette.secondary, orb: .working)
        } else if bot.status == "error" {
            line(bot.lastMessage ?? "Something went wrong", color: Palette.danger, orb: nil)
        } else {
            line(bot.lastMessage ?? BackendInfo.name(bot.backend), color: Palette.secondary, orb: nil, lines: 2)
        }
    }

    private func line(_ text: String, color: Color, orb: ThinkingOrb.State?, lines: Int = 1) -> some View {
        HStack(spacing: 4) {
            if let orb { ThinkingOrb(state: orb, size: 10, color: color) }
            Text(text).lineLimit(lines)
        }
        .font(.caption2)
        .foregroundStyle(color)
    }
}
