import SwiftUI

/// Conversation text can grow on the Mac without resizing the app's controls.
public struct ConversationTypography: Sendable {
    public static let preferenceKey = "conversationFontSize"
    public static let defaultSize = 12.0
    public static let sizes: [Double] = [11, 12, 13, 14, 15, 16, 18]

    public let pointSize: Double

    public init(pointSize: Double = ConversationTypography.defaultSize) {
        self.pointSize = pointSize.isFinite ? min(max(pointSize, 11), 18) : Self.defaultSize
    }

    var body: Font {
        #if os(macOS)
        .system(size: pointSize)
        #else
        .body
        #endif
    }

    func heading(level: Int) -> Font {
        #if os(macOS)
        .system(size: level == 1 ? pointSize + 3 : level == 2 ? pointSize + 1 : pointSize - 1,
                weight: level == 2 ? .semibold : .bold)
        #else
        level == 1 ? .title3.bold() : level == 2 ? .headline : .subheadline.bold()
        #endif
    }

    var code: Font {
        #if os(macOS)
        .system(size: pointSize - 2, design: .monospaced)
        #else
        .system(.footnote, design: .monospaced)
        #endif
    }
}

private struct ConversationTypographyKey: EnvironmentKey {
    static let defaultValue = ConversationTypography()
}

public extension EnvironmentValues {
    var conversationTypography: ConversationTypography {
        get { self[ConversationTypographyKey.self] }
        set { self[ConversationTypographyKey.self] = newValue }
    }
}
