package com.codync.android.core

import kotlinx.serialization.json.*

/** How a bot-to-bot message ended, from the notice's `status` and outcome text. */
sealed interface BotOutcome {
    data object Pending : BotOutcome
    data object Done : BotOutcome
    data class Failed(val detail: String) : BotOutcome
}

/** One bot-to-bot message seen from the chat that holds its notice. */
data class BotExchange(
    val id: String, val chat: String, val seq: Long, val rev: Long, val source: String, val target: String,
    val text: String, val reply: String?, val createdAt: Long, val outcome: BotOutcome,
) {
    val outgoing: Boolean get() = chat == source
    val peer: String get() = if (outgoing) target else source
    val verb: String get() = if (outgoing) "Messaged" else "Message from"
}

private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A notice with structured message data is an exchange; existing plain notices stay plain. */
val Entry.botExchange: BotExchange?
    get() {
        if (kind != "notice") return null
        val message = data["botMessage"] as? JsonObject ?: return null
        val source = message.string("sourceBotId") ?: return null
        val target = message.string("targetBotId") ?: return null
        val request = message.string("text") ?: return null
        val outcome = when (status) {
            "completed" -> BotOutcome.Done
            "failed", "cancelled" -> BotOutcome.Failed(message.string("detail") ?: "")
            else -> BotOutcome.Pending
        }
        return BotExchange(id, botId, seq, rev, source, target, request, message.string("reply"), createdAt, outcome)
    }

/** Fetched history merged with the live chat (higher rev wins), limited to one peer, oldest first. */
fun botConversation(chat: String, fetched: List<Entry>, live: List<Entry>, peer: String): List<BotExchange> {
    val merged = LinkedHashMap<String, Entry>()
    for (entry in fetched + live) {
        val known = merged[entry.id]
        if (known == null || entry.rev >= known.rev) merged[entry.id] = entry
    }
    return merged.values.filter { it.botId == chat }.mapNotNull { it.botExchange }
        .filter { it.peer == peer }.sortedBy { it.seq }
}

const val BOT_CONVERSATION_GAP_MILLIS = 3_600_000L

sealed interface BotConversationRow {
    val id: String
    data class Separator(override val id: String, val createdAt: Long) : BotConversationRow
    data class Message(override val id: String, val author: String, val text: String, val showsAuthor: Boolean) : BotConversationRow
    data class Status(override val id: String, val outcome: BotOutcome, val awaiting: String) : BotConversationRow
}

/** Sheet rows for exchanges oldest first: time separators, grouped authors, then pending or failed status. */
fun botConversationRows(exchanges: List<BotExchange>): List<BotConversationRow> {
    val rows = mutableListOf<BotConversationRow>()
    var previousAuthor: String? = null
    var previousTime: Long? = null
    for (x in exchanges) {
        if (previousTime == null || x.createdAt - previousTime > BOT_CONVERSATION_GAP_MILLIS) {
            rows += BotConversationRow.Separator("sep-${x.id}", x.createdAt)
            previousAuthor = null
        }
        previousTime = x.createdAt
        rows += BotConversationRow.Message("${x.id}-request", x.source, x.text, x.source != previousAuthor)
        previousAuthor = x.source
        if (!x.reply.isNullOrEmpty()) {
            rows += BotConversationRow.Message("${x.id}-reply", x.target, x.reply, x.target != previousAuthor)
            previousAuthor = x.target
        }
        if (x.outcome != BotOutcome.Done) {
            rows += BotConversationRow.Status("${x.id}-status", x.outcome, x.target)
            previousAuthor = null
        }
    }
    return rows
}

/** A run of consecutive exchanges with one peer; sits where its first exchange sits. */
data class BotExchangeGroup(val first: BotExchange, val count: Int, val failed: Boolean) {
    val id: String get() = first.id
    val peer: String get() = first.peer
}

/** One chat row: a plain entry or a run of bot exchanges. */
sealed interface ChatItem {
    val entry: Entry
    data class Single(override val entry: Entry) : ChatItem
    data class Exchanges(override val entry: Entry, val group: BotExchangeGroup) : ChatItem
}

/**
 * Collapses runs of same-peer exchanges (either direction) in a main transcript. Any other chat-visible entry,
 * or an exchange with another peer, ends the run; entries that are not chat-visible do not.
 */
fun groupBotExchanges(entries: List<Entry>, visible: (Entry) -> Boolean = Entry::isChat): List<ChatItem> {
    val items = mutableListOf<ChatItem>()
    var open: Int? = null
    for (entry in entries) {
        val exchange = entry.botExchange
        if (exchange == null) {
            if (visible(entry)) open = null
            items += ChatItem.Single(entry)
            continue
        }
        val current = open?.let { items[it] as ChatItem.Exchanges }
        if (current != null && current.group.peer == exchange.peer) {
            val group = current.group
            items[open!!] = ChatItem.Exchanges(current.entry,
                group.copy(count = group.count + 1, failed = group.failed || exchange.outcome is BotOutcome.Failed))
        } else {
            items += ChatItem.Exchanges(entry, BotExchangeGroup(exchange, 1, exchange.outcome is BotOutcome.Failed))
            open = items.lastIndex
        }
    }
    return items
}
