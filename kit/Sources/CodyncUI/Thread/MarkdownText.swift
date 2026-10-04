#if os(macOS)
import CodyncKit
import SwiftUI

/// The Mac's Markdown renderer for messages (`MarkdownBlocks`: tables, rules, nested lists
/// too); inline styling comes from `AttributedString(markdown:)`.
/// Equatable so a reply that didn't change skips its body; each block is equatable too, so
/// while text streams only the block being written renders again.
public struct MarkdownText: View, Equatable {
    let source: String
    /// Text is still arriving: the open end hides half-written syntax.
    let streaming: Bool

    public init(_ source: String, streaming: Bool = false) {
        self.source = source
        self.streaming = streaming
    }

    public var body: some View {
        let blocks = MarkdownBlocks.parse(source, streaming: streaming)
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                MarkdownBlock(block: block).equatable()
            }
        }
        .textSelection(.enabled)
    }

    /// Inline styling; inline code gets a soft tint behind it.
    static func inline(_ s: String) -> AttributedString {
        guard var text = try? AttributedString(markdown: s, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))
        else { return AttributedString(s) }
        for run in text.runs where run.inlinePresentationIntent?.contains(.code) == true {
            text[run.range].backgroundColor = Palette.text.opacity(0.08)
        }
        return text
    }
}

/// Completed blocks keep their attributed text while the open block changes.
private struct MarkdownBlock: View, @MainActor Equatable {
    let block: MarkdownBlocks.Block
    @Environment(\.conversationTypography) private var typography

    nonisolated static func == (a: Self, b: Self) -> Bool { a.block == b.block }

    var body: some View {
        content.lineSpacing(4 * typography.scale)
    }

    @ViewBuilder private var content: some View {
        switch block {
        case let .paragraph(t):
            Text(MarkdownText.inline(t))
                .font(typography.body)
                .foregroundStyle(Palette.text)
        case let .heading(t, level):
            Text(MarkdownText.inline(t))
                .font(typography.heading(level: level))
                .foregroundStyle(Palette.text)
        case let .bullet(t, marker, depth):
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(marker).foregroundStyle(Palette.secondary).monospacedDigit()
                Text(MarkdownText.inline(t)).foregroundStyle(Palette.text)
            }
            .font(typography.body)
            .padding(.leading, CGFloat(depth) * 16)
        case .rule:
            Rectangle().fill(Palette.border).frame(height: 1).padding(.vertical, 4)
        case let .table(header, rows):
            MarkdownTable(header: header, rows: rows)
        case let .quote(t):
            Text(MarkdownText.inline(t))
                .font(typography.body)
                .foregroundStyle(Palette.secondary)
                .padding(.leading, 10)
                .overlay(alignment: .leading) { Rectangle().fill(Palette.border).frame(width: 3) }
        case let .code(t, _):
            ScrollView(.horizontal, showsIndicators: false) {
                Text(t)
                    .font(typography.code)
                    .foregroundStyle(Palette.text)
                    .padding(10)
            }
            .background(Palette.codeBackground, in: RoundedRectangle(cornerRadius: 8))
        }
    }
}

/// A Markdown table: a bold header and hairlines between rows, scrolling sideways when wide.
private struct MarkdownTable: View {
    let header: [String]
    let rows: [[String]]
    @Environment(\.conversationTypography) private var typography

    var body: some View {
        let columns = max(header.count, rows.map(\.count).max() ?? 0)
        ScrollView(.horizontal, showsIndicators: false) {
            Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 7) {
                GridRow {
                    ForEach(0 ..< columns, id: \.self) { i in
                        Text(MarkdownText.inline(i < header.count ? header[i] : "")).fontWeight(.semibold)
                    }
                }
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    Rectangle().fill(Palette.border).frame(height: 1).gridCellUnsizedAxes(.horizontal)
                    GridRow {
                        ForEach(0 ..< columns, id: \.self) { i in
                            Text(MarkdownText.inline(i < row.count ? row[i] : ""))
                        }
                    }
                }
            }
            .font(typography.body)
            .foregroundStyle(Palette.text)
            .fixedSize()
            .padding(.vertical, 2)
        }
    }
}

#endif
