package com.codync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.codync.android.core.DirListing
import com.codync.android.design.CodyncField
import com.codync.android.design.CodyncIcon
import com.codync.android.design.CodyncIconButton
import com.codync.android.design.Glyph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Remote folders drill down in their own sheet; settings retain the draft underneath. */
@Composable internal fun BotEditorFolders(computerId: String?, initial: String?, online: Boolean,
    load: suspend (String?) -> DirListing, choose: (String) -> Unit, close: () -> Unit, inPopover: Boolean = false) {
    var stack by remember(computerId) { mutableStateOf(listOf(initial?.takeIf(String::isNotBlank))) }
    var filter by remember(computerId) { mutableStateOf("") }
    var listing by remember(computerId) { mutableStateOf<DirListing?>(null) }
    var loading by remember(computerId) { mutableStateOf(false) }
    var failure by remember(computerId) { mutableStateOf<String?>(null) }
    var retry by remember(computerId) { mutableIntStateOf(0) }
    val path = stack.last()
    fun back() { if (stack.size > 1) { stack = stack.dropLast(1); filter = "" } else close() }
    CodyncSheetBackHandler(onBack = ::back)
    LaunchedEffect(computerId, path, online, retry) {
        listing = null; failure = null; loading = false
        if (!online) { failure = "Connect to this computer to browse its folders."; return@LaunchedEffect }
        loading = true
        try {
            val response = load(path)
            val task = currentCoroutineContext()
            task.ensureActive()
            listing = response
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error.message ?: "Couldn't open this folder." }
        finally { loading = false }
    }
    Column(if (inPopover) Modifier.fillMaxSize() else Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (stack.size > 1) CodyncIconButton("Back to parent folder", Glyph.Back, ::back)
            Text(path?.trimEnd('/')?.substringAfterLast('/')?.takeIf(String::isNotEmpty) ?: "Folders",
                Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            CodyncIconButton("Close folder picker", Glyph.Close, close)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { CodyncField(filter, { filter = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search folders") }, singleLine = true) }
            failure?.let { message -> item {
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(enabled = online && !loading, onClick = { retry++ }) { Text("Retry folder list") }
            } }
            if (loading) item { Text("Loading folders…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            listing?.let { current ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth().clipFolder().clickable(role = Role.Button) { choose(current.path) }.padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CodyncIcon(Glyph.Check)
                            Text("Use “${current.path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }}”", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(current.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val dirs = current.dirs.filter { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) }
                if (dirs.isEmpty()) item { Text(if (filter.isBlank()) "No subfolders." else "No matching folders.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(dirs, key = { it.path }) { dir ->
                    Row(Modifier.fillMaxWidth().clipFolder().clickable(role = Role.Button) { stack = stack + dir.path; filter = "" }.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(dir.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        if (dir.isGit) Text("Git", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        CodyncIcon(Glyph.Down, Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable private fun Modifier.clipFolder(): Modifier = clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
