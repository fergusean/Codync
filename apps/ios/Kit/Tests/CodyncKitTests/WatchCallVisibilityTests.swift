import Foundation
import Testing
@testable import CodyncKit

@Test func watchChatHidesCallMessagesBeforeTakingItsWindow() throws {
    func make(_ id: String, seq: Int, kind: String, call: Bool = false, failed: Bool = false) throws -> Entry {
        var data: [String: Any] = ["text": id, "final": true, "clientNonce": id]
        if call {
            data["call"] = "private-call"
            data["transcript"] = [["from": "user", "text": "private transcript"]]
        }
        if failed { data["status"] = "failed" }
        let object: [String: Any] = [
            "id": id, "seq": seq, "botId": "b1", "rev": seq, "kind": kind,
            "turn": 1, "data": data, "createdAt": seq, "updatedAt": seq,
        ]
        return try JSONDecoder().decode(Entry.self, from: JSONSerialization.data(withJSONObject: object))
    }

    let bot = try JSONDecoder().decode(Bot.self, from: Data(#"{"id":"b1","name":"Bot"}"#.utf8))
    let ordinary = try make("ordinary", seq: 1, kind: "user")
    let callMessages = try (2...31).map { seq in
        try make("call-\(seq)", seq: seq, kind: seq.isMultiple(of: 2) ? "user" : "agent", call: true)
    }
    let ended = try make("call-ended", seq: 32, kind: "notice", call: true)
    let failed = try make("failed-send", seq: 33, kind: "user", call: true, failed: true)
    let latest = try make("latest", seq: 34, kind: "agent")
    let chat = WatchChat.build(scope: WatchScope(context: "local", computer: "c1"), bot: bot,
                               entries: [ordinary] + callMessages + [ended, failed, latest],
                               answering: [:], fresh: true, now: Date(timeIntervalSince1970: 1_800_000_000))

    #expect(chat.entries.map(\.id) == ["ordinary", "call-ended", "failed-send", "latest"])
    #expect(chat.recentNonces.contains("call-30"), "hidden entries still acknowledge sent nonces")
    #expect(chat.entries.allSatisfy { $0.data.call == nil && $0.data.transcript == nil })
    let encoded = try WatchEnvelope.fromPhone(.chat(chat)).encode()
    let text = String(decoding: encoded, as: UTF8.self)
    #expect(!text.contains("private-call") && !text.contains("private transcript"))
}
