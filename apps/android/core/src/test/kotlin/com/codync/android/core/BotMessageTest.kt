package com.codync.android.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BotMessageTest {
    @Test fun `full recipient replies replace excerpts only in the same conversation turn`() {
        val request = Entry("request", 1, "dex", rev = 1, kind = "notice", turn = 8, data = buildJsonObject {
            put("delegationId", "request"); put("heading", "Request from Miles: Investigate.")
        })
        val reply = Entry("reply", 2, "dex", rev = 2, kind = "agent", turn = 8, data = buildJsonObject {
            put("text", "Full answer. ".repeat(500)); put("final", true)
        })
        assertTrue(reply.isRecipientReply(listOf(request, reply)))
        assertTrue(request.hasRecipientReply(listOf(request, reply)))
        assertFalse(request.hasRecipientReply(listOf(request)))
        assertFalse(reply.isRecipientReply(listOf(request.copy(turn = 7), reply)))
        assertFalse(reply.isRecipientReply(listOf(request.copy(threadId = "thread"), reply)))
        assertFalse(reply.isRecipientReply(listOf(request.copy(botId = "other"), reply)))
    }

    @Test fun `existing requests preserve multiline message and failure detail`() {
        val heading = "Request from Miles: Urgent.\n\nError: connection aborted"
        val entry = Entry("e", 1, "dex", rev = 1, kind = "notice", data = buildJsonObject {
            put("delegationId", "request"); put("heading", heading)
            put("text", heading + "\nbot request cancelled"); put("style", "error")
        })
        assertEquals(BotMessage("Message from Miles", "Urgent.\n\nError: connection aborted", "bot request cancelled"), entry.botMessage)
        assertNull(entry.copy(kind = "user").botMessage)
        assertNull(entry.copy(data = buildJsonObject { put("text", "Other notice") }).botMessage)
    }

    @Test fun `requests describe each bot role and replies become bubbles`() {
        for ((prefix, expected) in listOf("Asked Dex" to "Waiting for a reply…", "Request from Miles" to "Working on a reply…")) {
            val heading = "$prefix: Investigate."
            fun entry(outcome: String, status: String) = Entry("e", 1, "dex", rev = 1, kind = "notice", data = buildJsonObject {
                put("delegationId", "request"); put("heading", heading); put("text", "$heading\n$outcome"); put("status", status)
            })
            assertEquals(expected, entry("Waiting for a reply…", "queued").botMessage?.detail)
            val message = entry("Reply from Dex:\nInvestigated.\n\nNo changes needed.", "completed").botMessage!!
            assertEquals("", message.detail)
            assertEquals(BotReply("Reply from Dex", "Investigated.\n\nNo changes needed."), message.reply)
        }
    }

    @Test fun `one-way messages never show status lines`() {
        for (prefix in listOf("Messaged Dex", "Message from Miles")) {
            val heading = "$prefix: Investigate."
            for (outcome in listOf("Queued.", "Running in Dex's chat…", "Completed. Outcome reported in Dex's chat.", "Recipient stopped.")) {
                val entry = Entry("e", 1, "dex", rev = 1, kind = "notice", data = buildJsonObject {
                    put("delegationId", "message"); put("heading", heading); put("text", "$heading\n$outcome")
                })
                assertEquals("", entry.botMessage?.detail)
                assertNull(entry.botMessage?.reply)
            }
        }
    }
}
