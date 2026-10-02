import SwiftUI
import Testing
@testable import CodyncKit

#if os(macOS)
@Test func appFontsPreserveDefaultStyling() {
    let styles: [(AppFont, Font)] = [
        (.body, .body),
        (.title3, .title3),
        (.footnote, .footnote),
        (.headline, .headline),
        (.caption.bold(), .caption.bold()),
        (.footnote.monospaced(), .footnote.monospaced()),
        (.caption.monospacedDigit(), .caption.monospacedDigit()),
        (.system(size: 12), .system(size: 12)),
        (.system(size: 20, weight: .medium, design: .rounded), .system(size: 20, weight: .medium, design: .rounded)),
    ]
    for (appFont, original) in styles {
        #expect(appFont.scaled(by: 1) == original)
    }
}

@Test func appFontsPreserveRelativeSizesAndTraits() throws {
    guard #available(macOS 26, *) else { return }
    let context = EnvironmentValues().fontResolutionContext
    let styles: [AppFont] = [.compactBody, .compactSecondary, .headline, .caption.bold(), .footnote.monospaced(),
                             .system(size: 20, weight: .medium, design: .rounded)]
    for font in styles {
        let original = font.scaled(by: 1).resolve(in: context)
        for scale: CGFloat in [11.0 / 12, 1.5] {
            let enlarged = font.scaled(by: scale).resolve(in: context)
            #expect(abs(enlarged.pointSize / original.pointSize - scale) < 0.001)
            #expect(enlarged.weight == original.weight)
            #expect(enlarged.isBold == original.isBold)
            #expect(enlarged.isMonospaced == original.isMonospaced)
        }
        // Exercise the macOS 14–15 path even when this test runs on a newer Mac.
        let fallbackOriginal = font.fallbackScaled(by: 1).resolve(in: context)
        let fallbackEnlarged = font.fallbackScaled(by: 1.5).resolve(in: context)
        #expect(abs(fallbackEnlarged.pointSize / fallbackOriginal.pointSize - 1.5) < 0.001)
        #expect(fallbackEnlarged.weight == original.weight)
        #expect(fallbackEnlarged.isBold == original.isBold)
        #expect(fallbackEnlarged.isMonospaced == original.isMonospaced)
    }
}
#endif
