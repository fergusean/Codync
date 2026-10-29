import Foundation
import Testing
@testable import CodyncKit
@testable import CodyncUI

@MainActor @Test func watchRosterFollowsPhoneSectionsBeforeItsBotLimit() throws {
    let rig = WatchBridgeTestRig()
    defer { rig.finish() }
    func bot(_ id: String, position: Int, extra: String = "") throws -> Bot {
        let json = #"{"id":"\#(id)","name":"\#(id)","rev":1,"lastAt":1,"status":"idle","position":\#(position)\#(extra)}"#
        return try JSONDecoder().decode(Bot.self, from: Data(json.utf8))
    }
    var bots = try [
        bot("lead", position: 99, extra: #", "lead":true"#),
        bot("pin-group", position: 1, extra: #", "pinned":true,"kind":"group","members":["a-0"],"section":"Z""#),
        bot("pin", position: 2, extra: #", "pinned":true,"section":"A""#),
        bot("loose-first", position: 0),
        bot("loose-second", position: 1),
        bot("z", position: 2, extra: #", "section":"Z""#),
    ]
    for index in 0..<36 {
        bots.append(try bot("a-\(index)", position: index + 10, extra: #", "section":"A""#))
    }
    rig.store.bots = Dictionary(uniqueKeysWithValues: bots.map { ($0.id, $0) })
    let snapshot = rig.bridge.makeSnapshot()
    let expected = ["lead", "pin-group", "pin"] + (0..<36).map { "a-\($0)" } + ["z"]
    #expect(snapshot.bots.count == 40)
    #expect(snapshot.bots.map(\.id) == expected)
}
