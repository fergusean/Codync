package com.codync.android.core

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Private summaries only: widget storage never contains chat text or credentials. */
@Serializable data class WidgetBot(val id: String, val name: String, val status: String,
    val unread: Int = 0, val avatarColor: String = "blue", val avatarShape: String = "blob")

@Serializable data class WidgetFeed(val scope: String = "local", val computerId: String? = null,
    val computerName: String = "", val bots: List<WidgetBot> = emptyList(), val botsAt: Long = 0,
    val usage: UsageReport = UsageReport(), val usageAt: Long = 0)

enum class WidgetKind { Bots, Usage, Provider }

data class WidgetLine(val title: String, val value: String, val fraction: Float? = null,
    val warning: Boolean = false, val botId: String? = null, val resetsAt: Long? = null,
    val resetsText: String? = null, val avatarShape: String? = null, val avatarColor: String = "blue")

data class WidgetPresentation(val title: String, val subtitle: String, val empty: String? = null,
    val lines: List<WidgetLine> = emptyList(), val updatedAt: Long = 0, val stale: Boolean = false)

/** Both the in-app gallery and the system widgets use this presentation. */
fun WidgetFeed.present(kind: WidgetKind, providerId: String = "claude", now: Long = System.currentTimeMillis(),
    limit: Int = 3): WidgetPresentation {
    val capacity = limit.coerceIn(1, 6)
    val title = when (kind) { WidgetKind.Bots -> "Bots"; WidgetKind.Usage -> "Usage limits";
        WidgetKind.Provider -> usage.providers.firstOrNull { it.id == providerId }?.name
            ?: when (providerId) { "claude" -> "Claude"; "codex" -> "Codex"; else -> providerId } }
    if (computerId == null) return WidgetPresentation(title, "ZC Codync", "Connect a computer in ZC Codync.")
    val selectedProviders = if (kind == WidgetKind.Provider) usage.providers.filter { it.id == providerId } else usage.providers
    val updated = if (kind == WidgetKind.Bots) botsAt else selectedProviders.mapNotNull { it.updatedAt?.takeIf { time -> time > 0 } }.minOrNull() ?: usageAt
    val stale = updated <= 0 || updated > now + 60_000 || now - updated > 30 * 60_000
    val subtitle = if (stale) "Update delayed · $computerName" else computerName
    if (kind == WidgetKind.Bots) {
        val needed = bots.count { it.status == "needsInput" }
        val working = bots.count { it.status == "working" }
        val lines = bots.take(capacity).map { bot -> WidgetLine(bot.name, if (stale) "Update delayed" else when (bot.status) {
            "needsInput" -> "Needs you"; "working" -> "Working"; "error" -> "Failed"
            "idle" -> if (bot.unread > 0) "${bot.unread} unread" else "Idle"
            else -> "Update delayed"
        }, warning = !stale && bot.status in setOf("needsInput", "error"), botId = bot.id, avatarShape = bot.avatarShape, avatarColor = bot.avatarColor) }
        return WidgetPresentation(title, if (stale) subtitle else "$needed needs you · $working working",
            if (lines.isEmpty()) if (updated == 0L) "Open ZC Codync to sync your bots." else "No visible bots." else null,
            lines, updated, stale)
    }
    val providers = selectedProviders
    val windows = if (kind == WidgetKind.Provider) providers.flatMap { p -> p.windows.map { p to it } }
        else providers.mapNotNull { p -> (p.tightest ?: p.windows.firstOrNull())?.let { p to it } }
    val lines = windows.take(capacity).map { (provider, window) -> WidgetLine(
        if (kind == WidgetKind.Provider) window.label else provider.name,
        window.reportedPercent?.let { "${it.roundToInt()}%" } ?: "Unavailable", window.barFraction,
        (window.reportedPercent ?: 0.0) >= 90, resetsAt = window.resetsAt, resetsText = window.resetsText) }
    return WidgetPresentation(title, subtitle, if (lines.isEmpty()) "No saved usage report. Open ZC Codync to refresh." else null,
        lines, updated, stale)
}

fun widgetBots(bots: List<Bot>): List<WidgetBot> = bots.filter { !it.hidden && !it.deleted }
    .sortedWith(compareBy<Bot> { when (it.status) { "needsInput" -> 0; "working" -> 1; "error" -> 2; else -> 3 } }
        .thenByDescending { it.pinned }.thenByDescending { it.lastAt }.thenBy { it.name })
    .take(256).map { WidgetBot(it.id, it.name.take(256), it.status, it.unread, it.avatarColor, it.avatarShape) }
