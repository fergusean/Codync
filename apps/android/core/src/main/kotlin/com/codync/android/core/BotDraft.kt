package com.codync.android.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

@Serializable
data class BotDraft(
    val id: String? = null,
    val name: String = "",
    val description: String = "",
    val avatarColor: String = "blue",
    val avatarShape: String = "blob",
    val backend: String = "claude",
    val command: String? = null,
    val cwd: String = "",
    val permission: String = "ask",
    val model: String? = null,
    val pinned: Boolean = false,
    val hidden: Boolean = false,
    val notify: Boolean? = null,
    val connectors: List<String>? = null,
    val skills: List<String> = emptyList(),
    val computer: Boolean = false,
) {
    val isValid: Boolean get() = id == null || name.isNotBlank()
    fun toggleNotifications(): BotDraft = copy(notify = !(notify ?: true))
    fun body(): JsonObject {
        val normalized = if (backend == "custom") this else copy(command = null)
        val encoded = WireJson.encodeToJsonElement(normalized)
        return encoded.jsonObject
    }
    fun template(): String = JsonObject(body().filterKeys { it !in setOf("id", "pinned", "hidden") }).toString()

    companion object {
        fun from(bot: Bot): BotDraft = BotDraft(bot.id, bot.name, bot.description, bot.avatarColor, bot.avatarShape,
            bot.backend, bot.command, if (bot.managedWorkspace) "" else bot.cwd, bot.permission, bot.model,
            bot.pinned, bot.hidden, bot.notify, bot.connectors, bot.skills, bot.computer)
    }
}
