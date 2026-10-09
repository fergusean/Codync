package com.codync.android.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BotExchangeTest {
    private fun notice(id: String, chat: String, seq: Long, source: String = "egan", target: String = "owen", status: String = "completed",
        outcome: String? = null, at: Long = 1_000, rev: Long = seq, request: String = "Do it", intent: String = "message"): Entry {
        return Entry(id, seq, chat, rev = rev, kind = "notice", createdAt = at, data = buildJsonObject {
            put("text", "Existing notice text"); put("status", status)
            put("botMessage", buildJsonObject {
                put("sourceBotId", source); put("targetBotId", target); put("text", request)
                if (status == "completed" && intent == "ask" && outcome?.startsWith("Reply from ") == true)
                    put("reply", outcome.substringAfter(":\n"))
                if (status == "failed" || status == "cancelled") put("detail", outcome ?: "")
            })
        })
    }

    @Test fun `structured fields set direction without parsing notice text`() {
        val sent = notice("a", "egan", 1, request = "Check a: b\nand c: d").botExchange!!
        assertTrue(sent.outgoing); assertEquals("owen", sent.peer); assertEquals("Messaged", sent.verb)
        assertEquals("Check a: b\nand c: d", sent.text)
        val incoming = notice("a", "owen", 1).botExchange!!
        assertFalse(incoming.outgoing); assertEquals("egan", incoming.peer); assertEquals("Message from", incoming.verb)
    }

    @Test fun `reply and failure detail come from structured data`() {
        val ask = notice("a", "egan", 1, intent = "ask", outcome = "Reply from Owen:\nLine one.\nLine two: ok.").botExchange!!
        assertEquals("Line one.\nLine two: ok.", ask.reply)
        assertEquals(BotOutcome.Done, ask.outcome)
        assertEquals(BotOutcome.Failed("Stopped"), notice("b", "egan", 2, status = "failed", outcome = "Stopped").botExchange?.outcome)
        assertEquals(BotOutcome.Pending, notice("c", "egan", 3, status = "queued").botExchange?.outcome)
    }

    @Test fun `plain historical notices and malformed structured fields stay plain`() {
        val entry = notice("a", "egan", 1)
        assertNull(entry.copy(kind = "agent").botExchange)
        assertNull(entry.copy(data = buildJsonObject { put("heading", "Asked Owen: request"); put("intent", "ask"); put("text", "request") }).botExchange)
        assertNull(entry.copy(data = buildJsonObject { put("botMessage", buildJsonObject { put("sourceBotId", 1); put("targetBotId", "owen"); put("text", "request") }) }).botExchange)
    }

    @Test fun `merge keeps the higher rev and filters the peer`() {
        val fetched = listOf(notice("a", "egan", 1, status = "queued", rev = 1), notice("b", "egan", 2, target = "other"),
            notice("c", "dex", 3))
        val live = listOf(notice("a", "egan", 1, status = "completed", rev = 5), notice("d", "egan", 4, source = "owen", target = "egan"))
        val result = botConversation("egan", fetched, live, "owen")
        assertEquals(listOf("a", "d"), result.map { it.id })
        assertEquals(BotOutcome.Done, result[0].outcome)
        val stale = botConversation("egan", listOf(notice("a", "egan", 1, rev = 9)), listOf(notice("a", "egan", 1, status = "queued", rev = 2)), "owen")
        assertEquals(BotOutcome.Done, stale.single().outcome)
    }

    @Test fun `rows add separators author grouping replies and status`() {
        val xs = listOf(
            notice("a", "egan", 1, intent = "ask", outcome = "Reply from Owen:\nDone", at = 1_000).botExchange!!,
            notice("b", "egan", 2, status = "queued", at = 2_000).botExchange!!,
            notice("c", "egan", 3, status = "failed", outcome = "Stopped", at = 2_000 + BOT_CONVERSATION_GAP_MILLIS + 1).botExchange!!,
        )
        val rows = botConversationRows(xs)
        assertEquals(listOf("sep-a", "a-request", "a-reply", "b-request", "b-status", "sep-c", "c-request", "c-status"), rows.map { it.id })
        assertEquals(true, (rows[1] as BotConversationRow.Message).showsAuthor)
        assertEquals("owen", (rows[2] as BotConversationRow.Message).author)
        assertEquals(true, (rows[3] as BotConversationRow.Message).showsAuthor)
        assertEquals(BotConversationRow.Status("b-status", BotOutcome.Pending, "owen"), rows[4])
        assertEquals(BotOutcome.Failed("Stopped"), (rows[7] as BotConversationRow.Status).outcome)
    }

    @Test fun `consecutive done requests by one author show the label once`() {
        val rows = botConversationRows(listOf(notice("a", "egan", 1).botExchange!!, notice("b", "egan", 2).botExchange!!))
        assertEquals(false, (rows[2] as BotConversationRow.Message).showsAuthor)
    }

    private fun kinds(items: List<ChatItem>) = items.map { (it as? ChatItem.Exchanges)?.group?.count ?: 0 }

    @Test fun `same peer run groups with count and first id, direction mixed`() {
        val items = groupBotExchanges(listOf(notice("a", "egan", 1), notice("b", "egan", 2, source = "owen", target = "egan"),
            notice("c", "egan", 3)))
        val group = (items.single() as ChatItem.Exchanges).group
        assertEquals(3, group.count); assertEquals("a", group.id); assertEquals("owen", group.peer); assertFalse(group.failed)
    }

    @Test fun `different peer splits`() {
        assertEquals(listOf(2, 1), kinds(groupBotExchanges(listOf(notice("a", "egan", 1), notice("b", "egan", 2),
            notice("c", "egan", 3, target = "dex")))))
    }

    @Test fun `visible entry between splits but trace entries do not`() {
        val user = Entry("u", 2, "egan", rev = 2, kind = "user")
        assertEquals(listOf(1, 0, 1), kinds(groupBotExchanges(listOf(notice("a", "egan", 1), user, notice("b", "egan", 3)))))
        val trace = Entry("t", 2, "egan", rev = 2, kind = "tool")
        val items = groupBotExchanges(listOf(notice("a", "egan", 1), trace, notice("b", "egan", 3)))
        assertEquals(listOf(2, 0), kinds(items))
        val plainNotice = notice("n", "egan", 2).copy(data = buildJsonObject { put("text", "Hi") })
        assertEquals(listOf(1, 0, 1), kinds(groupBotExchanges(listOf(notice("a", "egan", 1), plainNotice, notice("b", "egan", 3)))))
    }

    @Test fun `any failure marks the group failed`() {
        val group = (groupBotExchanges(listOf(notice("a", "egan", 1), notice("b", "egan", 2, status = "failed"),
            notice("c", "egan", 3))).single() as ChatItem.Exchanges).group
        assertTrue(group.failed); assertEquals(3, group.count)
    }
}
