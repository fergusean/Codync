#if os(macOS)
import CodyncKit
import SwiftUI

/// One circular surface for floating chat controls, including hover/press feedback.
struct MacChatButtonStyle: ButtonStyle {
    var size: CGFloat = 32
    var direction: CGSize = .zero

    func makeBody(configuration: Configuration) -> some View {
        ButtonContent(configuration: configuration, size: size, direction: direction)
    }

    private struct ButtonContent: View {
        let configuration: Configuration
        let size: CGFloat
        let direction: CGSize
        @State private var hovering = false
        @Environment(\.isEnabled) private var enabled
        @Environment(\.accessibilityReduceMotion) private var reduceMotion

        var body: some View {
            configuration.label
                .appFont(.system(size: size / 2, weight: .medium))
                .foregroundStyle(hovering ? Palette.text : Palette.secondary)
                .offset(x: reduceMotion ? 0 : direction.width * (configuration.isPressed ? 1.5 : hovering ? 1 : 0),
                        y: reduceMotion ? 0 : direction.height * (configuration.isPressed ? 1.5 : hovering ? 1 : 0))
                .frame(width: size, height: size)
                .background {
                    Circle().fill(Color(light: 0xEEEEEE, dark: 0x303030))
                        .overlay {
                            Circle().fill(Palette.text.opacity(configuration.isPressed ? 0.12 : hovering ? 0.06 : 0))
                        }
                }
                .clipShape(Circle())
                .contentShape(Circle())
                .opacity(enabled ? 1 : 0.35)
                .scaleEffect(configuration.isPressed && !reduceMotion ? 0.94 : 1)
                .animation(Motion.reduced(configuration.isPressed ? Motion.press : Motion.morph, reduceMotion),
                           value: configuration.isPressed)
                .animation(Motion.reduced(Motion.hover, reduceMotion), value: hovering)
                .onHover { hovering = $0 }
        }
    }
}
#endif
