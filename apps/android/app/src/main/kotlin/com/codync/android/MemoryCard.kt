package com.codync.android

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable fun MemoryCard(botId: String, state: AppState, store: AppStore) {
    val memory = state.memories[botId]
    val busy = "memory:$botId" in state.busy
    var clearing by remember(botId) { mutableStateOf(false) }
    LaunchedEffect(botId) { store.loadMemory(botId) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Memory", style = MaterialTheme.typography.titleMedium)
            TextButton(enabled = !busy, onClick = { store.loadMemory(botId) }) { Text("Refresh memory") }
        }
        if (memory == null) Text(if (busy) "Loading…" else "Couldn't load memory. Retry to see what the bot remembers.")
        else if (memory.facts.isEmpty()) Text("Nothing yet. The bot remembers who you are and what you work on as you chat.")
        memory?.facts?.forEach { fact -> Row {
            Column(Modifier.weight(1f)) {
                Text(if (fact.kind == "profile") "About you" else "History", style = MaterialTheme.typography.labelSmall)
                SelectionContainer { Text(fact.content) }
            }
            TextButton(modifier = Modifier.semantics { contentDescription = "Forget ${fact.content}" },
                enabled = !busy, onClick = { store.forgetMemory(botId, fact.id) }) { Text("Forget") }
        } }
        if (memory?.facts?.isNotEmpty() == true) TextButton(enabled = !busy, onClick = { clearing = !clearing }) { Text("Forget everything") }
        AnimatedContent(clearing, label = "clear memory") { confirm -> if (confirm) Column {
            Text("Forget everything this bot remembers? Your chat stays.")
            Row {
                TextButton(enabled = !busy, onClick = { clearing = false; store.forgetMemory(botId) }) { Text("Forget everything") }
                TextButton(onClick = { clearing = false }) { Text("Cancel") }
            }
        } }
    }
}
