package com.codync.android.core

import java.io.File
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** The existing host file protocol, with memory bounded to one chunk. */
class HostFiles(private val call: suspend (String, JsonObject, Long) -> JsonElement) {
    constructor(channel: EncryptedChannel) : this({ method, body, timeout -> channel.call(method, body, timeout) })

    suspend fun upload(botId: String, id: String, file: OutgoingFile, progress: (Long) -> Unit = {}) = withContext(Dispatchers.IO) {
        val source = File(file.path)
        require(file.size in 0..MAX_FILE_BYTES && source.length() == file.size && source.isFile) { "The selected file is no longer available. Choose it again." }
        source.inputStream().use { input ->
            var offset = 0L
            do {
                val buffer = ByteArray(minOf(CHUNK_BYTES.toLong(), file.size - offset).toInt())
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                val chunk = if (count == buffer.size) buffer else buffer.copyOf(count)
                require(offset + chunk.size <= file.size) { "The selected file changed during upload." }
                val done = offset + chunk.size == file.size
                require(done || chunk.isNotEmpty()) { "The selected file changed during upload." }
                call("upload", buildJsonObject {
                    put("botId", botId); put("uploadId", id); put("name", file.name)
                    put("offset", offset); put("data", Base64.getEncoder().encodeToString(chunk)); put("done", done)
                }, 60_000)
                offset += chunk.size
                progress(offset)
            } while (offset < file.size)
            require(input.read() == -1) { "The selected file changed during upload." }
        }
    }

    suspend fun download(botId: String, file: Attachment, destination: File) = withContext(Dispatchers.IO) {
        require(file.size in 0..MAX_FILE_BYTES)
        val partial = File(destination.parentFile, destination.name + ".partial")
        try {
            partial.outputStream().use { output ->
                var offset = 0L
                do {
                    val result = call("readUpload", buildJsonObject { put("botId", botId); put("uploadId", file.id); put("offset", offset) }, 60_000).jsonObject
                    val size = result.getValue("size").jsonPrimitive.long
                    require(size == file.size) { "The computer returned a different file size." }
                    val bytes = Base64.getDecoder().decode(result.getValue("data").jsonPrimitive.content)
                    require(bytes.size <= CHUNK_BYTES && offset + bytes.size <= size && (bytes.isNotEmpty() || size == 0L)) {
                        "The computer returned an invalid file chunk."
                    }
                    output.write(bytes)
                    offset += bytes.size
                } while (offset < file.size)
                output.fd.sync()
            }
            if (!partial.renameTo(destination)) throw IOException("Couldn't save the downloaded file.")
        } finally { partial.delete() }
    }

    companion object { const val CHUNK_BYTES = 384 * 1024 }
}
