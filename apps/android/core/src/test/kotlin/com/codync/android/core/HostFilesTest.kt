package com.codync.android.core

import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HostFilesTest {
    @Test fun `uploads stream bounded ordered chunks including an empty file`() = runBlocking {
        val root = Files.createTempDirectory("codync-files").toFile()
        try {
            for (size in listOf(0, HostFiles.CHUNK_BYTES + 13)) {
                val source = root.resolve("source").apply { writeBytes(ByteArray(size) { (it % 255).toByte() }) }
                var offset = 0L
                var calls = 0
                val files = HostFiles { method, body, timeout ->
                    assertEquals("upload", method); assertEquals(60_000, timeout)
                    assertEquals(offset, body.getValue("offset").jsonPrimitive.long)
                    val bytes = Base64.getDecoder().decode(body.getValue("data").jsonPrimitive.content)
                    assertTrue(bytes.size <= HostFiles.CHUNK_BYTES)
                    for (i in bytes.indices) assertEquals(((offset + i) % 255).toByte(), bytes[i])
                    offset += bytes.size; calls++
                    assertEquals(offset == size.toLong(), body.getValue("done").jsonPrimitive.boolean)
                    JsonObject(emptyMap())
                }
                files.upload("bot", "upload", OutgoingFile(source.path, "source", size.toLong()))
                assertEquals(size.toLong(), offset)
                assertEquals(if (size == 0) 1 else 2, calls)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun `truncated downloads cannot publish a partial cache file`() = runBlocking {
        val root = Files.createTempDirectory("codync-files").toFile()
        try {
            var calls = 0
            val files = HostFiles { _, _, _ ->
                calls++
                buildJsonObject { put("size", 4); put("data", if (calls == 1) "YWI=" else "") }
            }
            val destination = root.resolve("saved")
            assertFails<IllegalArgumentException> { files.download("bot", Attachment("id", "file", 4), destination) }
            assertFalse(destination.exists())
            assertFalse(root.resolve("saved.partial").exists())
        } finally { root.deleteRecursively() }
    }
}
