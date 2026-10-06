package com.codync.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codync.android.core.DirListing
import com.codync.android.design.CodyncField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Lists directories on the selected host. It never browses the phone's filesystem. */
@Composable internal fun FolderPicker(computerId: String?, name: String, initial: String?, online: Boolean,
    load: suspend (String?) -> DirListing, choose: (String) -> Unit, close: () -> Unit) {
    var path by remember(computerId) { mutableStateOf(initial?.takeIf(String::isNotBlank)) }
    var typed by remember(computerId) { mutableStateOf(path.orEmpty()) }
    var filter by remember(computerId) { mutableStateOf("") }
    var listing by remember(computerId) { mutableStateOf<DirListing?>(null) }
    var loading by remember(computerId) { mutableStateOf(false) }
    var failure by remember(computerId) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(computerId, path, online, retry) {
        listing = null; failure = null; loading = false
        if (!online) { failure = "Connect to this computer to browse its folders."; return@LaunchedEffect }
        loading = true
        try {
            val response = load(path)
            val task = currentCoroutineContext()
            task.ensureActive()
            listing = response; typed = response.path
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error.message ?: "Couldn't open this folder." }
        finally { loading = false }
    }
    BackHandler(onBack = close)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = close) { Text("Cancel") }
                Text("Working folder", style = MaterialTheme.typography.titleLarge)
            }
            Text("Folders on $name", style = MaterialTheme.typography.bodySmall)
            CodyncField(typed, { typed = it }, label = { Text("Computer folder path") }, modifier = Modifier.fillMaxWidth())
            FlowRow {
                TextButton(enabled = online && !loading, onClick = { path = typed.takeIf(String::isNotBlank); retry++ }) { Text("Open folder") }
                TextButton(onClick = { choose("") }) { Text("Use personal workspace") }
            }
            failure?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(enabled = online && !loading, onClick = { retry++ }) { Text("Retry folder list") }
            }
            if (loading) Text("Loading folders…")
            listing?.let { current ->
                Text(current.path)
                FlowRow {
                    TextButton(onClick = { choose(current.path) }) { Text("Use this folder") }
                    if (current.parent != null) TextButton(onClick = { path = current.parent; filter = "" }) { Text("Parent folder") }
                }
                CodyncField(filter, { filter = it }, label = { Text("Filter folders") }, modifier = Modifier.fillMaxWidth())
                val dirs = current.dirs.filter { it.name.contains(filter.trim(), ignoreCase = true) }
                if (dirs.isEmpty()) Text(if (filter.isBlank()) "No subfolders." else "No matching folders.")
                LazyColumn(Modifier.weight(1f)) {
                    items(dirs, key = { it.path }) { dir -> TextButton(onClick = { path = dir.path; filter = "" }) {
                        Text(dir.name + if (dir.isGit) " · Git repository" else "", Modifier.fillMaxWidth())
                    } }
                }
            }
        }
    }
}
