package com.codync.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.codync.android.design.*
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.codync.android.core.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable fun MessageComposer(bot: Bot, state: AppState, store: AppStore) {
    val lane = ComposerDraft.lane(bot.id, state.thread)
    val draft = state.drafts[lane]
    val text = rememberTextFieldState(initialText = draft?.text.orEmpty())
    val files = draft?.files.orEmpty()
    val queuing = "draft-send:" + lane in state.busy
    LaunchedEffect(text, bot.id, state.thread) {
        snapshotFlow { text.text.toString() }.collect { store.editDraft(bot.id, state.thread, it) }
    }
    var picker by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val importing = "attachments" in state.busy
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) store.importFiles(uris) { store.addDraftFiles(bot.id, state.thread, it) }
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) store.importFiles(uris) { store.addDraftFiles(bot.id, state.thread, it) }
    }
    val receiver = remember(bot.id, importing, queuing) { ReceiveContentListener { content ->
        if (bot.kind == "group" || importing || queuing) content else {
            val uris = mutableListOf<android.net.Uri>()
            val remaining = content.consume { item -> item.uri?.takeIf { it.scheme == "content" }?.let { uris.add(it); true } ?: false }
            if (uris.isNotEmpty()) store.importFiles(uris) { store.addDraftFiles(bot.id, state.thread, it) }
            remaining
        }
    } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (file in files) Row {
            Text(file.name + " · " + formatBytes(file.size), Modifier.weight(1f).semantics { contentDescription = "Draft attachment: ${file.name}" })
            TextButton(enabled = !queuing, onClick = { store.removeDraftFile(bot.id, state.thread, file) }) { Text("Remove") }
        }
        if (importing) Text("Preparing files…", style = MaterialTheme.typography.bodySmall)
        AnimatedContent(picker, label = "attachment choices") { open -> if (open) Row {
            TextButton(enabled = !importing && !queuing, onClick = { picker = false; photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Photos") }
            TextButton(enabled = !importing && !queuing, onClick = { picker = false; documents.launch(arrayOf("*/*")) }) { Text("Files") }
            TextButton(enabled = !importing && !queuing, onClick = { scope.launch {
                val clip = clipboard.getClipEntry()
                val data = clip?.clipData
                val uris = if (data == null) emptyList() else (0 until data.itemCount).mapNotNull { data.getItemAt(it).uri }
                if (uris.isNotEmpty()) store.importFiles(uris) { store.addDraftFiles(bot.id, state.thread, it) }
                picker = false
            } }) { Text("Paste image") }
        } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            if (bot.kind != "group") CodyncIconButton("Attachments", Glyph.Plus, { picker = !picker }, enabled = !queuing)
            Row(Modifier.weight(1f).clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(start = 16.dp, end = 3.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
                BasicTextField(state = text, enabled = !queuing, modifier = Modifier.weight(1f).contentReceiver(receiver)
                    .padding(vertical = 12.dp).heightIn(min = 24.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                    decorator = { input -> Box { if (text.text.isEmpty()) Text(if (state.thread != null) "Reply…" else "Ask " + bot.name,
                        color = MaterialTheme.colorScheme.onSurfaceVariant); input() } })
                if (text.text.isBlank() && files.isEmpty() && bot.kind != "group" && state.thread == null)
                    StartVoiceButton(bot.id, state, store, compact = true)
                else CodyncIconButton("Send", Glyph.Send, {
                    store.sendDraft(bot.id, state.thread, text.text.toString()) { text.edit { replace(0, length, "") } }
                }, enabled = !importing && !queuing && (text.text.isNotBlank() || files.isNotEmpty()) &&
                    (files.isEmpty() || state.actualConnection is LinkState.Ready), filled = true)
            }
        }
        if (files.isNotEmpty() && state.actualConnection !is LinkState.Ready) Text("Connect to the computer to send files.", style = MaterialTheme.typography.bodySmall)
    }
}

fun formatBytes(size: Long): String = when {
    size >= 1024 * 1024 -> "%.1f MiB".format(size / (1024.0 * 1024))
    size >= 1024 -> "%.1f KiB".format(size / 1024.0)
    else -> "$size B"
}
