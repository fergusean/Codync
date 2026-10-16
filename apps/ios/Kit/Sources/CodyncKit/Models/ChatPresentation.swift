import Foundation

/// The words chat rows use for approvals and send states, shared by the iPhone and the watch.
public enum ChatPresentation {
    /// What an approval card says the agent wants.
    public static func permissionHeadline(toolKind: String?) -> String {
        switch toolKind {
        case "execute": "Wants to run a command"
        case "edit", "delete", "move": "Wants to change files"
        case "fetch": "Wants to access the web"
        case "read", "search": "Wants to read files"
        default: "Wants to use a tool"
        }
    }

    /// Ordered like Grok Bot: Allow once, Always allow, then Deny.
    public static func orderedOptions(_ options: [PermissionOption]?) -> [PermissionOption] {
        let rank = ["allow_once": 0, "allow_always": 1, "reject_once": 2, "reject_always": 3]
        return (options ?? []).sorted { (rank[$0.kind] ?? 9) < (rank[$1.kind] ?? 9) }
    }

    public static func optionLabel(_ option: PermissionOption) -> String {
        switch option.kind {
        case "allow_once": "Allow once"
        case "allow_always": "Always allow"
        case "reject_once": "Deny"
        case "reject_always": "Never"
        default: option.name
        }
    }

    /// The line an approval card shows once it's no longer pending.
    public static func permissionOutcome(status: String?, options: [PermissionOption]?, selected: String?) -> String {
        switch status {
        case "answered":
            let chosen = options?.first { $0.optionId == selected }
            return switch chosen?.kind {
            case "allow_once": "Allowed once"
            case "allow_always": "Always allowed"
            case "reject_once": "Denied"
            case "reject_always": "Never allowed"
            default: chosen?.name ?? "Answered"
            }
        case "cancelled": return "Cancelled"
        default: return "Expired — the agent moved on"
        }
    }
}

/// Where a user message is on its way to the computer.
public enum SendState: Equatable, Sendable {
    case sending, queued, failed, cancelled, waiting, delivering

    /// nil once the host has the message (`sent`) or for a status this app doesn't know.
    public init?(status: String?) {
        switch status {
        case "sending": self = .sending
        case "queued": self = .queued
        case "failed": self = .failed
        case "cancelled": self = .cancelled
        case "waiting": self = .waiting
        case "delivering": self = .delivering
        default: return nil
        }
    }

    /// `botWorking`: the bot is busy, so a queued message waits for its turn to end.
    public func label(botWorking: Bool = false) -> String {
        switch self {
        case .sending: "Sending…"
        case .queued: botWorking ? "Queued until this response finishes" : "Queued"
        case .failed: "Failed to send"
        case .cancelled: "Not sent — stopped"
        case .waiting: "Waiting for the computer to come online"
        case .delivering: "Delivered to the computer"
        }
    }
}
