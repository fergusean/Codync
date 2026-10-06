package com.codync.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codync.android.core.*
import kotlinx.coroutines.*
import java.util.UUID

/** One submitted query. Loading another page always requires a button press. */
@Composable internal fun MarketConnectorBrowser(query: String, online: Boolean, installed: Set<String>,
    load: suspend (String, String?) -> MarketConnectors, pick: (MarketConnector) -> Unit) {
    var page by remember { mutableStateOf(CatalogPage<MarketConnector>()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var ticket by remember { mutableStateOf(UUID.randomUUID()) }
    var moreJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(query, online, retry) {
        moreJob?.cancel(); moreJob = null
        val request = UUID.randomUUID(); ticket = request
        page = CatalogPage(); cursor = null; failure = null; loading = false
        if (!online) { failure = "Connect to this computer to browse connectors."; return@LaunchedEffect }
        loading = true
        try {
            val response = load(query, null)
            val task = currentCoroutineContext()
            task.ensureActive()
            if (ticket == request) { page = CatalogPage<MarketConnector>().append(response.items) { it.name }; cursor = response.nextCursor?.takeIf(String::isNotBlank) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (ticket == request) failure = error.message ?: "Couldn't load connectors." }
        finally { if (ticket == request) loading = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connectors", style = MaterialTheme.typography.titleLarge)
        if (page.items.isEmpty()) {
            if (loading) Text("Loading connectors…")
            else if (failure == null) Text(if (query.isBlank()) "No connectors available." else "No connectors match “$query”.")
        }
        page.visible.forEach { item -> Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                item.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                TextButton(enabled = online, onClick = { pick(item) }) { Text(if (item.installed || item.name in installed) "Configure ${item.title}" else "Add ${item.title}") }
            }
        } }
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (failure != null && page.items.isEmpty()) TextButton(enabled = online && !loading, onClick = { retry++ }) { Text("Retry connectors") }
        if (page.hasHidden || cursor != null) TextButton(enabled = !loading && online, onClick = {
            if (page.hasHidden) page = page.reveal()
            else {
                val next = cursor ?: return@TextButton
                val request = ticket
                moreJob = scope.launch {
                    loading = true; failure = null
                    try {
                        val response = load(query, next)
                        val task = currentCoroutineContext()
                        task.ensureActive()
                        check(response.nextCursor != next) { "The registry repeated a page. Refresh your search." }
                        if (ticket == request) { page = page.append(response.items) { it.name }.reveal(); cursor = response.nextCursor?.takeIf(String::isNotBlank) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { if (ticket == request) failure = error.message ?: "Couldn't load more connectors." }
                    finally { if (ticket == request) loading = false }
                }
            }
        }) { Text(if (loading) "Loading more connectors…" else "Load more connectors") }
    }
}
