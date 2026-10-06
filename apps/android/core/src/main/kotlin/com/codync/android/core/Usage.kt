package com.codync.android.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable data class UsageReport(val providers: List<UsageProvider> = emptyList()) {
    companion object { fun decode(value: JsonElement): UsageReport = WireJson.decodeFromJsonElement(value) }
}

@Serializable data class UsageProvider(val id: String, val name: String, val windows: List<UsageWindow> = emptyList(),
    val source: String = "", val updatedAt: Long? = null) {
    val tightest: UsageWindow? get() = windows.filter { it.percent?.isFinite() == true }.maxByOrNull { requireNotNull(it.percent) }
    val sourceLabel: String get() = when (source) {
        "claude" -> "Claude Code"; "statusline" -> "Claude Code status line"
        "agent" -> "A running bot"; "sessions" -> "Codex sessions"; "" -> "Unknown source"; else -> source
    }
}

@Serializable data class UsageWindow(val id: String, val label: String, val percent: Double? = null,
    val resetsAt: Long? = null, val resetsText: String? = null) {
    val reportedPercent: Double? get() = percent?.takeIf { it.isFinite() }
    val barFraction: Float? get() = reportedPercent?.coerceIn(0.0, 100.0)?.div(100)?.toFloat()
}
