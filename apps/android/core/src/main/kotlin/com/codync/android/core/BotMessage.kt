package com.codync.android.core

/** Separates persisted bot requests from their delivery or completion status. */
data class BotMessage(val label: String, val body: String, val detail: String, val reply: BotReply? = null)
data class BotReply(val label: String, val body: String)

fun Entry.isRecipientReply(entries: List<Entry>): Boolean = kind == "agent" && entries.any { it.matchesRecipientRequest(this) }

fun Entry.hasRecipientReply(entries: List<Entry>): Boolean = entries.any { it.isChat && it.kind == "agent" && matchesRecipientRequest(it) }

private fun Entry.matchesRecipientRequest(reply: Entry): Boolean = kind == "notice" && reply.kind == "agent"
    && !data.text("delegationId").isNullOrEmpty() && data.text("heading")?.startsWith("Request from ") == true
    && botId == reply.botId && turn == reply.turn && threadId == reply.threadId

val Entry.botMessage: BotMessage?
    get() {
        if (kind != "notice" || data.text("delegationId").isNullOrEmpty()) return null
        val heading = data.text("heading") ?: return null
        val separator = heading.indexOf(": ")
        if (separator < 0) return null
        val attribution = heading.substring(0, separator)
        val prefix = listOf("Message from ", "Request from ", "Messaged ", "Asked ")
            .firstOrNull { attribution.startsWith(it) } ?: return null
        val name = attribution.removePrefix(prefix)
        if (name.isEmpty() || text != heading && !text.startsWith(heading + "\n")) return null
        val label = if (prefix == "Messaged " || prefix == "Asked ") "Message to " else "Message from "
        val outcome = if (text == heading) "" else text.removePrefix(heading + "\n")
        val ask = prefix == "Asked " || prefix == "Request from "
        val replySeparator = outcome.indexOf(":\n")
        val reply = if (ask && status == "completed" && outcome.startsWith("Reply from ") && replySeparator >= 0)
            BotReply(outcome.substring(0, replySeparator), outcome.substring(replySeparator + 2)) else null
        val detail = if (!ask || reply != null) ""
            else if (prefix == "Request from " && outcome == "Waiting for a reply…") "Working on a reply…" else outcome
        return BotMessage(label + name, heading.substring(separator + 2), detail, reply)
    }
