#if os(macOS)
import CodyncKit
import SwiftUI

/// Scrolled away from the newest messages: a round button back down to them.
struct MacJumpToLatest: View {
    let visible: Bool
    let action: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.macChatInsets) private var insets

    var body: some View {
        ZStack {
            if visible {
                Button("Jump to latest", systemImage: "arrow.down", action: action)
                    .labelStyle(.iconOnly)
                    .buttonStyle(MacChatButtonStyle(direction: CGSize(width: 0, height: 1)))
                    .shadow(color: .black.opacity(0.12), radius: 10, y: 3)
                    .help("Jump to latest")
                    .padding(.bottom, 10)
                    .transition(.offset(y: 6).combined(with: .opacity))
            }
        }
        .animation(Motion.reduced(Motion.morph, reduceMotion), value: visible)
        .padding(.bottom, insets.bottom)
    }
}

#endif
