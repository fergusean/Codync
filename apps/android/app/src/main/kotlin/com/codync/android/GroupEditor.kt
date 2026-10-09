package com.codync.android

import com.codync.android.design.CodyncField

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codync.android.core.GroupDraft

@Composable fun GroupEditor(state: AppState, initial: GroupDraft, store: AppStore, close: () -> Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf(false) }
    val saving = "editor" in state.busy
    val picked = draft.members.map { id -> id to state.bots.firstOrNull { it.id == id } }
    BackHandler(onBack = close)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = close) { Text("Cancel") }
                Text(if (draft.id == null) "New group chat" else "Group chat", style = MaterialTheme.typography.titleLarge)
                Button(enabled = !saving && picked.isNotEmpty(), onClick = { store.saveGroup(draft, close) }) { Text(if (saving) "Saving…" else "Save") }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Bots · ${picked.size}", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    picked.forEach { (id, bot) -> TextButton(onClick = { draft = draft.copy(members = draft.members - id) }) {
                        Text((bot?.name ?: "Unavailable bot") + " ×")
                    } }
                }
                CodyncField(query, { query = it }, label = { Text("Search bots") }, modifier = Modifier.fillMaxWidth())
                state.bots.filter { !it.deleted && it.kind != "group" && it.id !in draft.members && it.name.contains(query.trim(), ignoreCase = true) }
                    .sortedBy { it.name.lowercase() }.forEach { bot ->
                        TextButton(onClick = { draft = draft.copy(members = draft.members + bot.id); query = "" }) { Text("Add ${bot.name}") }
                    }
                Text("Everyone answers in turn unless you @mention someone. Each bot works in its own folder with its own tools.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CodyncField(draft.name, { draft = draft.copy(name = it) }, label = { Text("Name") },
                    placeholder = { Text(picked.mapNotNull { it.second?.name }.joinToString(", ")) }, modifier = Modifier.fillMaxWidth())
                CodyncField(draft.description, { draft = draft.copy(description = it) }, label = { Text("About") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                if (draft.id != null) {
                    TextButton(onClick = { delete = !delete }) { Text("Delete group chat") }
                    AnimatedContent(delete, label = "delete group") { confirming -> if (confirming) Column {
                        Text("Delete this group chat? Its bots and their own chats stay.")
                        Row {
                            TextButton(enabled = "delete:" + draft.id !in state.busy, onClick = {
                                state.bots.firstOrNull { it.id == draft.id }?.let { store.deleteBot(it, close) }
                            }) { Text("Delete") }
                            TextButton(onClick = { delete = false }) { Text("Cancel") }
                        }
                    } }
                }
            }
        }
    }
}
