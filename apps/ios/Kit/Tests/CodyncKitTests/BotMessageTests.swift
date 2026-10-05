import Foundation
import Testing
@testable import CodyncKit

@Test(arguments: ["Message from Miles", "Request from Miles", "Messaged Miles", "Asked Miles"])
func botMessagesSeparateAttributionAndMultilineBody(_ attribution: String) throws {
    let body = "Urgent. Check the call.\n\nError: connection aborted"
    let heading = attribution + ": " + body
    var data = EntryData(text: heading + "\nWaiting for a reply…", status: "queued")
    data.heading = heading
    data.delegationId = "request"
    let message = try #require(BotMessage(data: data))
    #expect(message.label == (attribution.hasPrefix("Asked") || attribution.hasPrefix("Messaged") ? "Message to Miles" : "Message from Miles"))
    #expect(message.body == body)
    #expect(message.detail == (attribution.hasPrefix("Request") ? "Working on a reply…"
        : attribution.hasPrefix("Asked") ? "Waiting for a reply…" : ""))
    #expect(message.reply == nil)
}

@Test(arguments: ["Message from Miles", "Messaged Dex"],
      ["Queued. Outcome will appear in Dex's chat.", "Running in Dex's chat…",
       "Completed. Outcome reported in Dex's chat.", "Recipient stopped. Partial work may have happened."])
func oneWayBotMessagesHaveNoStatusLine(_ attribution: String, _ outcome: String) throws {
    let heading = attribution + ": Investigate."
    var data = EntryData(text: heading + "\n" + outcome)
    data.heading = heading
    data.delegationId = "request"
    let message = try #require(BotMessage(data: data))
    #expect(message.detail.isEmpty)
    #expect(message.reply == nil)
}

@Test(arguments: ["Request from Miles", "Asked Dex"])
func botRepliesBecomeSeparateBubbles(_ attribution: String) throws {
    let heading = attribution + ": Investigate."
    let answer = "Investigated.\n\n**No changes needed.**"
    var data = EntryData(text: heading + "\nReply from Dex:\n" + answer, status: "completed")
    data.heading = heading
    data.delegationId = "request"
    let message = try #require(BotMessage(data: data))
    #expect(message.detail.isEmpty)
    #expect(message.reply?.label == "Reply from Dex")
    #expect(message.reply?.body == answer)
}

@Test func failedBotRequestsKeepTheRequestAndFailureSeparate() throws {
    let heading = "Request from Miles: URGENT. Investigate."
    var data = EntryData(text: heading + "\nbot request cancelled", status: "failed")
    data.heading = heading
    data.delegationId = "request"
    data.style = "error"
    let message = try #require(BotMessage(data: data))
    #expect(message.body == "URGENT. Investigate.")
    #expect(message.detail == "bot request cancelled")
}

@Test func unrelatedAndIncompleteNoticesRetainTheirNormalPresentation() {
    var data = EntryData(text: "Routine finished")
    #expect(BotMessage(data: data) == nil)
    data.heading = "Message from Miles: Check this"
    #expect(BotMessage(data: data) == nil)
    data.delegationId = "request"
    #expect(BotMessage(data: data) == nil)
    data.text = data.heading
    #expect(BotMessage(data: data)?.detail == "")
}

@Test func recipientRepliesUseTheFullAgentMessageWithoutDuplicatingItsNotice() {
    let heading = "Request from Miles: Investigate."
    var data = EntryData(text: heading + "\nReply from Dex:\nShort excerpt", status: "completed")
    data.heading = heading
    data.delegationId = "request"
    var request = Entry(id: "request", seq: 1, botId: "dex", rev: 1, kind: "notice", turn: 8,
                        data: data, createdAt: 1, updatedAt: 1)
    var replyData = EntryData(text: String(repeating: "Full answer. ", count: 500))
    replyData.final = true
    let reply = Entry(id: "reply", seq: 2, botId: "dex", rev: 2, kind: "agent", turn: 8,
                      data: replyData, createdAt: 2, updatedAt: 2)
    #expect(BotMessage.isRecipientReply(reply, in: [request, reply]))
    #expect(BotMessage.hasRecipientReply(request, in: [request, reply]))
    #expect(!BotMessage.hasRecipientReply(request, in: [request]))
    request.turn = 7
    #expect(!BotMessage.isRecipientReply(reply, in: [request, reply]))
    request.turn = 8
    request.threadId = "thread"
    #expect(!BotMessage.isRecipientReply(reply, in: [request, reply]))
    request.threadId = nil
    request.botId = "other"
    #expect(!BotMessage.isRecipientReply(reply, in: [request, reply]))
    request.botId = "dex"
    request.data.heading = "Asked Dex: Investigate."
    #expect(!BotMessage.isRecipientReply(reply, in: [request, reply]))
}
