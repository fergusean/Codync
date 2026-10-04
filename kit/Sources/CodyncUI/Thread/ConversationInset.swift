import CodyncKit
import SwiftUI

extension View {
    /// SwiftUI scroll views understand safe-area underlap. The native Mac transcript
    /// instead reserves document padding while keeping its viewport behind the chrome.
    @ViewBuilder func conversationInset<Chrome: View>(edge: VerticalEdge, spacing: CGFloat? = nil,
                                                       @ViewBuilder content: () -> Chrome) -> some View {
        #if os(macOS)
        modifier(MacConversationInset(edge: edge, chrome: content()))
        #else
        safeAreaInset(edge: edge, spacing: spacing, content: content)
        #endif
    }
}

#if os(macOS)
private struct MacConversationInset<Chrome: View>: ViewModifier {
    let edge: VerticalEdge
    let chrome: Chrome
    @State private var height: CGFloat = 0
    @Environment(\.macChatInsets) private var inherited

    private var insets: EdgeInsets {
        var result = inherited
        if edge == .top { result.top += height }
        else { result.bottom += height }
        return result
    }

    func body(content: Content) -> some View {
        content
            .environment(\.macChatInsets, insets)
            .overlay(alignment: edge == .top ? .top : .bottom) {
                chrome
                    .onGeometryChange(for: CGFloat.self) { ceil($0.size.height) } action: { height = $0 }
                    .background {
                        ConversationEdgeFade(edge: edge)
                            .padding(edge == .top ? .bottom : .top, -24)
                    }
            }
    }
}

/// The material samples the transcript below it; the gradient hides only the outer
/// edge, leaving text visible through the transition into the floating controls.
private struct ConversationEdgeFade: View {
    let edge: VerticalEdge

    var body: some View {
        let start: UnitPoint = edge == .top ? .top : .bottom
        let end: UnitPoint = edge == .top ? .bottom : .top
        ZStack {
            Rectangle().fill(.ultraThinMaterial)
                .mask(LinearGradient(colors: [.black, .clear], startPoint: start, endPoint: end))
            LinearGradient(stops: [.init(color: Palette.background.opacity(0.9), location: 0),
                                   .init(color: Palette.background.opacity(0.45), location: 0.4),
                                   .init(color: .clear, location: 1)],
                           startPoint: start, endPoint: end)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

extension EnvironmentValues {
    @Entry var macChatInsets = EdgeInsets()
}
#endif
