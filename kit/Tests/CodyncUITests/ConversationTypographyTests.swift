import SwiftUI
import Testing
@testable import CodyncUI

@Test func conversationTypographySanitizesImportedPreferences() throws {
    let defaultSize = ConversationTypography().pointSize
    for invalid in [Double.nan, .infinity, -.infinity] {
        #expect(ConversationTypography(pointSize: invalid).pointSize == defaultSize)
    }
    #expect(ConversationTypography(pointSize: -100).pointSize == ConversationTypography.sizes.first)
    #expect(ConversationTypography(pointSize: 100).pointSize == ConversationTypography.sizes.last)
    #expect(ConversationTypography(pointSize: 12.5).pointSize == 12.5)
}

#if os(macOS)
@Test func conversationTypographyPreservesTextHierarchyWhenResized() throws {
    guard #available(macOS 26, *) else { return }
    let context = EnvironmentValues().fontResolutionContext
    let original = ConversationTypography()
    let resized = ConversationTypography(pointSize: 18)
    let originalFonts = [original.body, original.code, original.heading(level: 1), original.heading(level: 2), original.heading(level: 3)]
    let resizedFonts = [resized.body, resized.code, resized.heading(level: 1), resized.heading(level: 2), resized.heading(level: 3)]
    for (originalFont, resizedFont) in zip(originalFonts, resizedFonts) {
        let base = originalFont.resolve(in: context)
        let changed = resizedFont.resolve(in: context)
        #expect(abs(changed.pointSize / base.pointSize - resized.scale) < 0.001)
        #expect(base.weight == changed.weight)
        #expect(base.isMonospaced == changed.isMonospaced)
    }
}
#endif
