import CodyncKit
import SwiftUI
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

enum Pasteboard {
    static func copy(_ text: String?) {
        guard let text else { return }
        #if canImport(UIKit)
        UIPasteboard.general.string = text
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        #endif
    }
}

extension View {
    func plainTextInput() -> some View {
        #if os(iOS)
        textInputAutocapitalization(.never).autocorrectionDisabled()
        #else
        autocorrectionDisabled()
        #endif
    }
}

extension View {
    /// Liquid Glass where the OS has it (content scrolls visibly underneath), a material before that.
    @ViewBuilder func glass(in shape: some Shape) -> some View {
        if #available(iOS 26, macOS 26, *) {
            glassEffect(.regular, in: shape)
        } else {
            background(.ultraThinMaterial, in: shape)
        }
    }
}

extension View {
    /// Grok's desktop floating surfaces (title pill, call bar): a dark frosted fill that lets the chat
    /// show through faintly, not Liquid Glass's clear lens. No border: the fill carries the edge.
    func frosted(in shape: some Shape) -> some View {
        background(Color(light: 0xF4F4F4, dark: 0x232323).opacity(0.82), in: shape)
            .background(.ultraThinMaterial, in: shape)
    }
}

extension View {
    /// The message box: Liquid Glass on iPhone; on the Mac a filled box with a
    /// soft shadow (as in Grok Bot's desktop app).
    @ViewBuilder func composerSurface(in shape: some Shape) -> some View {
        #if os(macOS)
        background(Palette.bubbleUser, in: shape)
            .shadow(color: .black.opacity(0.06), radius: 10, y: 2)
        #else
        glass(in: shape)
        #endif
    }

    /// Mac: Return sends, Shift-Return adds a new line. iPhone keeps Return as a new line.
    /// While an input method (Zhuyin, Pinyin, Japanese…) is composing, Return picks the candidate instead.
    @ViewBuilder func sendOnReturn(_ send: @escaping () -> Void) -> some View {
        #if os(macOS)
        onKeyPress(.return, phases: .down) { press in
            let editor = NSApp.keyWindow?.firstResponder as? NSTextView
            if editor?.hasMarkedText() == true {
                return .ignored
            }
            if press.modifiers.contains(.shift) {
                guard let editor else { return .ignored }
                // The multiline TextField treats Return as submission, even with Shift.
                editor.insertNewlineIgnoringFieldEditor(nil)
                return .handled
            }
            send()
            return .handled
        }
        #else
        self
        #endif
    }
}

/// Compact desktop metrics while preserving the phone's touch layout and Dynamic Type.
enum InterfaceMetrics {
    static func value(mac: CGFloat, mobile: CGFloat) -> CGFloat {
        #if os(macOS)
        mac
        #else
        mobile
        #endif
    }

    static var body: Font {
        #if os(macOS)
        .system(size: 12)
        #else
        .body
        #endif
    }

    static var secondary: Font {
        #if os(macOS)
        .system(size: 11)
        #else
        .subheadline
        #endif
    }
}
