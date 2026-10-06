package com.codync.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.codync.android.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun AttachmentRow(botId: String, file: Attachment, state: AppState, store: AppStore) {
    var full by remember(file.id) { mutableStateOf(false) }
    var attempt by remember(file.id) { mutableIntStateOf(0) }
    var image by remember(file.id) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(file.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(file.id, full, attempt, state.actualConnection) {
        if (file.isImage) try {
            error = null
            val source = store.attachment(botId, file)
            image = withContext(Dispatchers.IO) { AttachmentFiles.thumbnail(source, if (full) 4096 else 720).asImageBitmap() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Couldn't load this image." }
    }
    Column {
        Text(file.name + " · " + formatBytes(file.size))
        image?.let { bitmap -> Image(bitmap, contentDescription = file.name,
            modifier = Modifier.fillMaxWidth().heightIn(max = if (full) 800.dp else 260.dp).clickable { full = !full }) }
        if (file.isImage && image == null && error == null) Text("Loading image…", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall); TextButton(onClick = { attempt++ }) { Text("Retry") } }
        TextButton(enabled = "share:" + file.id !in state.busy, onClick = { store.shareAttachment(botId, file) }) {
            Text(if ("share:" + file.id in state.busy) "Downloading…" else "Download / share")
        }
    }
}
