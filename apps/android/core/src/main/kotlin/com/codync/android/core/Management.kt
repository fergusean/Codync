package com.codync.android.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** A group uses the existing bot endpoints; it never owns an agent or workspace. */
data class GroupDraft(
    val id: String? = null,
    val name: String = "",
    val description: String = "",
    val members: List<String> = emptyList(),
    val pinned: Boolean = false,
) {
    fun body(roster: List<Bot>): JsonObject {
        val picked = members.distinct().map { id ->
            requireNotNull(roster.firstOrNull { it.id == id && !it.deleted && it.kind != "group" }) {
                "A member is no longer available. Remove it before saving."
            }
        }
        require(picked.isNotEmpty()) { "Choose at least one bot." }
        return buildJsonObject {
            id?.let { put("id", it) }
            put("kind", "group")
            put("name", name.trim().ifEmpty { picked.joinToString(", ") { it.name } })
            put("description", description.trim())
            put("members", JsonArray(picked.map { JsonPrimitive(it.id) }))
            put("pinned", pinned)
        }
    }

    companion object {
        fun from(bot: Bot): GroupDraft {
            require(bot.kind == "group")
            return GroupDraft(bot.id, bot.name, bot.description, bot.members, bot.pinned)
        }
    }
}

/** One Engram memory, as the host lists it; unknown kinds and fields pass through. */
@Serializable data class MemoryFact(
    val id: String, val title: String = "", val content: String, val createdAt: Long = 0, val kind: String = "",
    val memoryType: String = "", val scope: String = "project", val topicKey: String? = null, val pinned: Boolean = false,
    val reviewAfter: String? = null, val revisionCount: Int = 0, val source: String? = null,
)

@Serializable data class MemoryListing(val facts: List<MemoryFact> = emptyList(), val total: Int = 0, val nextOffset: Int? = null) {
    companion object {
        fun decode(value: JsonElement): MemoryListing {
            val listing = WireJson.decodeFromJsonElement<MemoryListing>(value)
            require(listing.facts.all { it.id.isNotBlank() && it.content.isNotBlank() }) { "The computer sent invalid memory." }
            return listing
        }
    }
}

/** A memory's past versions and the session it came from. */
@Serializable data class MemoryDetail(val fact: MemoryFact, val history: History, val timeline: Timeline) {
    @Serializable data class History(val result: String, @SerialName("history_cursor") val cursor: Int? = null)
    @Serializable data class Timeline(val result: String)
}

data class MemoryDraft(val botId: String, val id: String? = null, val title: String = "", val content: String = "",
    val scope: String = "project", val memoryType: String = "learning", val topicKey: String = "") {
    fun body(): JsonObject = buildJsonObject {
        put("botId", botId); id?.let { put("id", it) }; put("title", title); put("content", content)
        put("scope", scope); put("memoryType", memoryType); put("topicKey", topicKey)
    }

    companion object {
        fun of(botId: String, fact: MemoryFact?) = MemoryDraft(botId, fact?.id, fact?.title.orEmpty(), fact?.content.orEmpty(),
            fact?.scope ?: "project", fact?.memoryType ?: "learning", fact?.topicKey.orEmpty())
    }
}
