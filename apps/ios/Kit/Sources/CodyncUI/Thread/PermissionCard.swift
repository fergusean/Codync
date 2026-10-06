import CodyncKit
import SwiftUI

/// Approval card in Grok Bot's choice-card style: what the agent wants, where
/// it runs, and the answers as a list of rows.
struct PermissionCard: View {
    let entry: Entry
    let hostName: String
    /// The option whose answer is on its way to the computer, if any.
    var answering: String?
    let respond: (String?) -> Void
    @State private var expanded = false

    private var d: EntryData { entry.data }
    private var pending: Bool { d.status == "pending" }

    private var headline: String { ChatPresentation.permissionHeadline(toolKind: d.toolKind) }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Text(headline).font(.headline).foregroundStyle(Palette.text)
                if pending {
                    Circle().fill(Palette.warning).frame(width: 7, height: 7)
                }
            }
            Text(d.title ?? "")
                .font(.subheadline.monospaced())
                .foregroundStyle(Palette.secondary)
                .lineLimit(expanded ? nil : 3)
            Label("Runs on \(hostName)\(d.cwd.map { " · \(($0 as NSString).lastPathComponent)" } ?? "")", systemImage: "desktopcomputer")
                .font(.caption)
                .foregroundStyle(Palette.tertiary)

            if hasDetail {
                Disclosure(isExpanded: $expanded) {
                    VStack(alignment: .leading, spacing: 8) {
                        if let command = d.command, !command.isEmpty {
                            CodeBox(text: command)
                        }
                        if let detail = d.detail, !detail.isEmpty {
                            CodeBox(text: String(detail.prefix(2000)))
                        }
                        ForEach(d.diffs ?? [], id: \.self) { DiffView(diff: $0) }
                    }
                    .padding(.top, 6)
                } label: {
                    Text("Details").font(.caption.weight(.medium)).foregroundStyle(Palette.secondary)
                }
            }

            if pending {
                buttons
            } else {
                Text(outcome)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(Palette.secondary)
            }
        }
        .padding(16)
        .background(Palette.bubbleAgent, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .padding(.trailing, 40)
    }

    private var hasDetail: Bool {
        !(d.command ?? "").isEmpty || !(d.detail ?? "").isEmpty || !(d.diffs ?? []).isEmpty
    }

    private var buttons: some View {
        let options = ChatPresentation.orderedOptions(d.options)
        return VStack(spacing: 0) {
            ForEach(Array(options.enumerated()), id: \.element.id) { i, o in
                if i > 0 { Rectangle().fill(Palette.border).frame(height: 0.5) }
                Button { respond(o.optionId) } label: {
                    HStack(spacing: 8) {
                        Text(ChatPresentation.optionLabel(o))
                            .font(.body.weight(o.kind == "allow_once" ? .semibold : .regular))
                            .foregroundStyle(o.kind.hasPrefix("allow") ? Palette.text : Palette.danger)
                        Spacer(minLength: 0)
                        if answering == o.optionId { Spinner(size: 14) }
                    }
                    .frame(maxWidth: .infinity, minHeight: 46, alignment: .leading)
                    .padding(.horizontal, 14)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(answering != nil)
                .opacity(answering == nil || answering == o.optionId ? 1 : 0.4)
            }
        }
        .background(Palette.background, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .padding(.top, 4)
    }

    private var outcome: String {
        ChatPresentation.permissionOutcome(status: d.status, options: d.options, selected: d.selected)
    }
}

struct CodeBox: View {
    let text: String

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            Text(text)
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(Palette.text)
                .textSelection(.enabled)
                .padding(8)
        }
        .frame(maxHeight: 240)
        .background(Palette.codeBackground, in: RoundedRectangle(cornerRadius: 8))
    }
}

struct DiffView: View {
    let diff: FileDiff

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Image(systemName: diff.isNew ? "doc.badge.plus" : "doc.text")
                Text((diff.path as NSString).lastPathComponent).lineLimit(1)
                Spacer()
                Text("+\(diff.added)").foregroundStyle(Palette.added)
                Text("−\(diff.removed)").foregroundStyle(Palette.removed)
            }
            .font(.caption.monospaced())
            .foregroundStyle(Palette.secondary)
            ScrollView(.horizontal, showsIndicators: false) {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(diff.patch.split(separator: "\n", omittingEmptySubsequences: false).prefix(80).enumerated()), id: \.offset) { _, line in
                        Text(line.isEmpty ? " " : String(line))
                            .foregroundStyle(line.hasPrefix("+") ? Palette.added : line.hasPrefix("-") ? Palette.removed : Palette.secondary)
                    }
                }
                .font(.system(.caption2, design: .monospaced))
                .padding(8)
            }
            .frame(maxHeight: 260)
            .background(Palette.codeBackground, in: RoundedRectangle(cornerRadius: 8))
        }
    }
}
