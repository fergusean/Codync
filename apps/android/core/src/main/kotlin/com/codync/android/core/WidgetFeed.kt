package com.codync.android.core

import kotlinx.serialization.Serializable

/** Private summaries only: widget storage never contains chat text or credentials. */
@Serializable data class WidgetBot(val id: String, val name: String, val status: String,
    val unread: Int = 0, val avatarColor: String = "blue", val avatarShape: String = "blob")

@Serializable data class WidgetFeed(val scope: String = "local", val computerId: String? = null,
    val computerName: String = "", val bots: List<WidgetBot> = emptyList(), val botsAt: Long = 0,
    val usage: UsageReport = UsageReport(), val usageAt: Long = 0)

enum class WidgetKind { Provider, Bots, Team }

/** The Team widget's four spots: the picked bots first (a deleted pick falls back), then the feed's order (who needs you). */
fun teamSlots(bots: List<WidgetBot>, picks: List<String?>): List<WidgetBot?> {
    val picked = List(4) { i -> picks.getOrNull(i)?.let { id -> bots.firstOrNull { it.id == id } } }
    val queue = ArrayDeque(bots.filter { bot -> picked.none { it?.id == bot.id } })
    return picked.map { it ?: queue.removeFirstOrNull() }
}

fun widgetBots(bots: List<Bot>): List<WidgetBot> = bots.filter { !it.hidden && !it.deleted }
    .sortedWith(compareBy<Bot> { when (it.status) { "needsInput" -> 0; "working" -> 1; "error" -> 2; else -> 3 } }
        .thenByDescending { it.pinned }.thenByDescending { it.lastAt }.thenBy { it.name })
    .take(256).map { WidgetBot(it.id, it.name.take(256), it.status, it.unread, it.avatarColor, it.avatarShape) }
