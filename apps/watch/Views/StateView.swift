import CodyncKit
import SwiftUI

/// A full-screen explanation: why there is nothing to show and what to do about it.
struct StateView: View {
    enum Kind: Equatable {
        case openPhone, unlockPhone, unreachable, notPaired, noBots, noMessages, loading
        case needsUpdate(WatchStore.Update)

        var title: String {
            switch self {
            case .openPhone: "Open Codync on your iPhone"
            case .unlockPhone: "Unlock your iPhone"
            case .unreachable: "iPhone not reachable"
            case .notPaired: WatchComputerState.notPaired.label
            case .noBots: "No bots yet"
            case .noMessages: "No messages yet"
            case .loading: "Loading…"
            case .needsUpdate: WatchComputerState.needsUpdate.label
            }
        }

        var message: String? {
            switch self {
            case .notPaired: "Pair a computer in Codync on your iPhone"
            case .noBots: "Create one in Codync on your iPhone"
            case let .needsUpdate(update):
                switch update {
                case let .mismatch(.updatePhone(minimum)): "Update Codync on your iPhone to \(minimum) or later"
                case let .mismatch(.updateWatch(minimum)): "Update Codync on Apple Watch to \(minimum) or later"
                case .newerPhone: WatchComputerState.unknown.label
                }
            default: nil
            }
        }
    }

    let kind: Kind
    /// Fill the screen and show the avatar; inline states sit inside a chat.
    var fullScreen = true

    var body: some View {
        VStack(spacing: 8) {
            if kind == .loading {
                ThinkingOrb(state: .connecting, size: 20, color: Palette.secondary)
            } else if fullScreen {
                CharacterAvatar(shape: "blob", color: "blue", size: 48)
            }
            Text(kind.title)
                .font(.headline)
                .foregroundStyle(kind == .loading ? Palette.secondary : Palette.text)
                .multilineTextAlignment(.center)
            if let message = kind.message {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(Palette.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: fullScreen ? .infinity : nil)
        .padding(.vertical, fullScreen ? 0 : 16)
        .accessibilityElement(children: .combine)
    }
}

/// A one-line status above the content: the phone is out of reach, or what's shown is old.
struct StatusLine: View {
    let text: String
    var warning = false

    var body: some View {
        Text(text)
            .font(.caption2)
            .foregroundStyle(warning ? Palette.warning : Palette.tertiary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
    }
}

extension WatchStore {
    /// "Unlock your iPhone" or "iPhone not reachable · <time>" when the phone can't be asked,
    /// else "Updated <time>" when what's shown isn't current; nil when all is well.
    func statusText(freshAt builtAt: Date?, fresh: Bool) -> String? {
        let stamp = (lastContact ?? builtAt).map { RelativeTime.day($0) }
        if link.needsUnlock && !link.isReachable { return "Unlock your iPhone" }
        if phoneOut { return ["iPhone not reachable", stamp].compactMap { $0 }.joined(separator: " · ") }
        if !fresh, let builtAt { return "Updated \(RelativeTime.day(builtAt))" }
        return nil
    }
}
