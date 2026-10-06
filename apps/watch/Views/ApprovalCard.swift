import CodyncKit
import SwiftUI

/// An approval: what the agent wants and the answers as text rows, in the iPhone's wording and order.
struct ApprovalCard: View {
    let entry: Entry
    /// The option whose answer is on its way to the computer, if any.
    let answering: String?
    let respond: (String) -> Void

    private var data: EntryData { entry.data }
    private var pending: Bool { data.status == "pending" }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Text(ChatPresentation.permissionHeadline(toolKind: data.toolKind))
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Palette.text)
                if pending {
                    Circle().fill(Palette.warning).frame(width: 6, height: 6)
                }
            }
            if let title = data.title, !title.isEmpty {
                Text(title)
                    .font(.caption2.monospaced())
                    .foregroundStyle(Palette.secondary)
                    .lineLimit(3)
            }
            if pending {
                VStack(spacing: 4) {
                    ForEach(ChatPresentation.orderedOptions(data.options)) { option in
                        optionRow(option)
                    }
                }
            } else {
                Text(ChatPresentation.permissionOutcome(status: data.status, options: data.options, selected: data.selected))
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(Palette.secondary)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.bubbleAgent, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private func optionRow(_ option: PermissionOption) -> some View {
        Button { respond(option.optionId) } label: {
            HStack(spacing: 6) {
                Text(ChatPresentation.optionLabel(option))
                    .font(.footnote.weight(option.kind == "allow_once" ? .semibold : .regular))
                    .foregroundStyle(option.kind.hasPrefix("allow") ? Palette.text : Palette.danger)
                Spacer(minLength: 0)
                if answering == option.optionId {
                    ThinkingOrb(state: .working, size: 14, color: Palette.text)
                }
            }
            .padding(.horizontal, 10)
            .frame(maxWidth: .infinity, minHeight: 34, alignment: .leading)
            .background(Palette.background, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(answering != nil)
        .opacity(answering == nil || answering == option.optionId ? 1 : 0.4)
    }
}
