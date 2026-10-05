import Foundation

/// Presentation of a persisted bot request, including messages from older hosts.
public struct BotMessage: Sendable, Equatable {
    public let label: String
    public let body: String
    public let detail: String
    public let reply: Reply?

    public struct Reply: Sendable, Equatable {
        public let label: String
        public let body: String
    }

    public static func isRecipientReply(_ entry: Entry, in entries: [Entry]) -> Bool {
        entry.kind == "agent" && entries.contains { matchesRecipientRequest($0, reply: entry) }
    }

    public static func hasRecipientReply(_ entry: Entry, in entries: [Entry]) -> Bool {
        entries.contains { $0.data.final == true && $0.kind == "agent" && matchesRecipientRequest(entry, reply: $0) }
    }

    private static func matchesRecipientRequest(_ request: Entry, reply: Entry) -> Bool {
        request.kind == "notice" && request.data.delegationId?.isEmpty == false
            && request.data.heading?.hasPrefix("Request from ") == true
            && request.botId == reply.botId && request.turn == reply.turn && request.threadId == reply.threadId
    }

    public init?(data: EntryData) {
        guard let id = data.delegationId, !id.isEmpty,
              let heading = data.heading,
              let separator = heading.range(of: ": "),
              let text = data.text,
              text == heading || text.hasPrefix(heading + "\n") else { return nil }
        let attribution = String(heading[..<separator.lowerBound])
        let prefixes = ["Message from ", "Request from ", "Messaged ", "Asked "]
        guard let prefix = prefixes.first(where: { attribution.hasPrefix($0) }) else { return nil }
        let name = String(attribution.dropFirst(prefix.count))
        guard !name.isEmpty else { return nil }
        label = (prefix == "Messaged " || prefix == "Asked " ? "Message to " : "Message from ") + name
        body = String(heading[separator.upperBound...])
        let outcome = text == heading ? "" : String(text.dropFirst(heading.count + 1))
        if prefix == "Messaged " || prefix == "Message from " {
            detail = ""
            reply = nil
        } else if data.status == "completed", outcome.hasPrefix("Reply from "),
                  let separator = outcome.range(of: ":\n") {
            detail = ""
            reply = Reply(label: String(outcome[..<separator.lowerBound]),
                          body: String(outcome[separator.upperBound...]))
        } else {
            detail = prefix == "Request from " && outcome == "Waiting for a reply…"
                ? "Working on a reply…" : outcome
            reply = nil
        }
    }
}
