package com.codync.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.codync.android.core.MemoryDetail
import com.codync.android.core.MemoryDraft
import com.codync.android.core.MemoryFact
import com.codync.android.core.MemoryListing
import com.codync.android.core.WireJson
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One bot's Engram memory on the computer (iOS `MemoryStore`): search, edit, history and backups. */
internal class MemoryModel(private val botId: String, private val store: AppStore, private val scope: CoroutineScope) {
    var facts by mutableStateOf(emptyList<MemoryFact>()); private set
    var query by mutableStateOf(""); private set
    var filter by mutableStateOf("all"); private set
    var total by mutableIntStateOf(0); private set
    var nextOffset by mutableStateOf<Int?>(null); private set
    var loaded by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var detail by mutableStateOf<MemoryDetail?>(null); private set
    var backup by mutableStateOf(""); private set
    private var request = 0
    private var detailRequest = 0
    private var searching: Job? = null

    suspend fun load(more: Boolean = false) {
        val asked = ++request
        val offset = (if (more) nextOffset else 0) ?: return
        error = null
        try {
            val listing = MemoryListing.decode(store.hostCall("memory", buildJsonObject {
                put("botId", botId); put("query", query); put("filter", filter); put("offset", offset)
            }))
            if (asked != request) return
            facts = if (more) facts + listing.facts else listing.facts
            total = listing.total; nextOffset = listing.nextOffset; loaded = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (asked == request) error = failure.message ?: "Couldn't load memory." }
    }

    fun refresh(more: Boolean = false) = scope.launch { load(more) }

    fun search(text: String, filter: String = this.filter) {
        query = text; this.filter = filter; request++
        searching?.cancel()
        searching = scope.launch { delay(250); load() }
    }

    fun action(method: String, fact: MemoryFact? = null) = perform {
        store.hostCall(method, buildJsonObject { put("botId", botId); fact?.let { put("id", it.id); put("pinned", !it.pinned) } })
    }

    fun save(draft: MemoryDraft, done: () -> Unit) = perform(done) { store.hostCall("saveMemory", draft.body()) }

    fun inspect(fact: MemoryFact, cursor: Int? = null) = scope.launch {
        val asked = ++detailRequest
        error = null
        if (cursor == null) detail = null
        try {
            val value = WireJson.decodeFromJsonElement<MemoryDetail>(store.hostCall("memoryDetail", buildJsonObject {
                put("botId", botId); put("id", fact.id); cursor?.let { put("historyCursor", it) }
            }))
            if (asked == detailRequest) detail = value
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (asked == detailRequest) error = failure.message ?: "Couldn't load the history." }
    }

    fun export() = scope.launch {
        backup = ""; error = null
        try {
            val result = store.hostCall("exportMemory", buildJsonObject { put("botId", botId) }).jsonObject
            // A big backup comes back as a file to read.
            val upload = result["uploadId"]?.jsonPrimitive?.contentOrNull
            backup = if (upload != null) store.hostFiles().readText(botId, upload) else result["json"]?.jsonPrimitive?.contentOrNull.orEmpty()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Couldn't export memory." }
    }

    fun importBackup(text: String, done: () -> Unit) = perform(done) {
        if (text.toByteArray().size > 384 * 1024) {
            val id = UUID.randomUUID().toString()
            store.hostFiles().uploadText(botId, id, "engram-backup.json", text)
            store.hostCall("importMemory", buildJsonObject { put("botId", botId); put("uploadId", id) })
        } else store.hostCall("importMemory", buildJsonObject { put("botId", botId); put("json", text) })
    }

    private fun perform(done: () -> Unit = {}, work: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { work(); load(); done() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Something went wrong." }
            finally { busy = false }
        }
    }
}
