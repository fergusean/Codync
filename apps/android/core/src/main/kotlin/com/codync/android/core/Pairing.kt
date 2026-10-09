package com.codync.android.core

import java.net.URI
import java.net.URLDecoder

/** A short-lived pairing URL. Only the resulting Computer may be persisted. */
data class Pairing(val computer: Computer, val code: String) {
    internal val offerId: String get() = RelayCrypto.offerId(code.decodeBase64Url(16))

    companion object {
        fun parse(text: String): Pairing {
            require(text.length <= 16 * 1024) { "This pairing code is damaged. Show a new one on your computer." }
            val url = try { URI(text.trim()) } catch (error: Exception) {
                throw IllegalArgumentException("That isn't a Codync pairing code.", error)
            }
            require(url.scheme == "codync" && url.host == "pair" && url.rawQuery != null) { "That isn't a Codync pairing code." }
            val params = linkedMapOf<String, String>()
            try {
                url.rawQuery.split('&').forEach { part ->
                    val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
                    val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                    require(params.put(key, value) == null) { "Duplicate pairing field" }
                }
                require(params["v"] == "3") { "Update Codync on your computer, then show a new pairing code." }
                val code = requireNotNull(params["code"])
                code.decodeBase64Url(16)
                val computer = Computer(
                    id = requireNotNull(params["id"]),
                    name = params["name"]?.takeIf(String::isNotBlank) ?: "My computer",
                    signKey = requireNotNull(params["sk"]),
                    boxKey = requireNotNull(params["bk"]),
                    urls = params["urls"].orEmpty().split(',').filter { it.isNotEmpty() && isDirectUrl(it) }.distinct(),
                    cloud = params["cloud"]?.takeIf(String::isNotEmpty),
                )
                computer.validate()
                require(computer.urls.isNotEmpty() || computer.cloud != null) { "No valid computer address" }
                return Pairing(computer, code)
            } catch (error: IllegalArgumentException) {
                if (params["v"] != "3") throw IllegalArgumentException("Update Codync on your computer, then show a new pairing code.", error)
                throw IllegalArgumentException("This pairing code is damaged. Show a new one on your computer.", error)
            }
        }
    }
}

