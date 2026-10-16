package com.codync.android.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class Bot(
    val id: String,
    val name: String = "",
    val kind: String = "agent",
    val members: List<String> = emptyList(),
    val description: String = "",
    val avatarColor: String = "blue",
    val avatarShape: String = "blob",
    val command: String? = null,
    val cwd: String = "",
    val managedWorkspace: Boolean = false,
    val permission: String = "ask",
    val model: String? = null,
    val notify: Boolean? = null,
    val connectors: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val computer: Boolean = false,
    val autoName: Boolean = false,
    val createdAt: Long = 0,
    val startedAt: Long? = null,
    val workingChat: String? = null,
    val workingThread: String? = null,
    val lastMessage: String? = null,
    val lastAt: Long = 0,
    val status: String = "idle",
    val activity: String = "",
    val backend: String = "",
    val pinned: Boolean = false,
    val hidden: Boolean = false,
    val unread: Int = 0,
    val rev: Long = 0,
    val deleted: Boolean = false,
)

object HostClient {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun hello(channel: EncryptedChannel, computer: Computer): Computer {
        val response = channel.call("hello")
        val hello = response.jsonObject
        require(hello.text("computerId") == computer.id && hello.text("signKey") == computer.signKey) {
            "This computer's identity changed. Pair it again."
        }
        val updated = computer.copy(
            name = hello.text("name")?.takeIf(String::isNotBlank) ?: computer.name,
            boxKey = hello.text("boxKey") ?: computer.boxKey,
            urls = hello["urls"]?.jsonArray?.map { it.jsonPrimitive.content }?.filter(::isDirectUrl) ?: computer.urls,
            cloud = hello.text("cloud"),
        )
        updated.validate()
        return updated
    }

    suspend fun bots(channel: EncryptedChannel): List<Bot> {
        val response = channel.call("sync")
        val bots = response.jsonObject["bots"]?.jsonArray ?: return emptyList()
        return bots.map { decode(it.jsonObject) }
    }

    fun decodeBot(event: JsonObject): Bot? {
        if (event.text("type") != "bot") return null
        return decode(event.getValue("bot").jsonObject)
    }

    fun decode(value: JsonObject): Bot {
        val bot = json.decodeFromJsonElement<Bot>(value)
        require(bot.id.isNotBlank() && bot.rev >= 0 && (bot.deleted || bot.name.isNotBlank())) { "The computer sent an invalid bot update." }
        return bot
    }
}
