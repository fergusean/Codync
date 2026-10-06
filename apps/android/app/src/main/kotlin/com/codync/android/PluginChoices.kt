package com.codync.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.codync.android.core.BotDraft

@Composable internal fun PluginChoices(state: AppState, draft: BotDraft, change: (BotDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connectors", style = MaterialTheme.typography.titleMedium)
        val plugins = state.plugins
        if (plugins == null) Text(if ("plugins" in state.busy) "Loading installed plugins…" else "Connect to this computer to load its plugins.")
        else {
            if (plugins.connectors.isEmpty()) Text("No connectors yet. Add them from Marketplace.")
            val selected = draft.connectors ?: plugins.connectors.map { it.id }
            plugins.connectors.forEach { connector -> TextButton(onClick = {
                change(draft.copy(connectors = if (connector.id in selected) selected - connector.id else selected + connector.id))
            }) { Column {
                Text((if (connector.id in selected) "✓ " else "") + connector.name)
                if (connector.needsSignIn) Text("Sign-in needed", style = MaterialTheme.typography.bodySmall)
            } } }
            Text("Skills", style = MaterialTheme.typography.titleMedium)
            if (plugins.skills.isEmpty()) Text("No skills yet. Get some from Marketplace, or write your own.")
            plugins.skills.forEach { skill -> TextButton(onClick = {
                change(draft.copy(skills = if (skill.id in draft.skills) draft.skills - skill.id else draft.skills + skill.id))
            }) { Text((if (skill.id in draft.skills) "✓ " else "") + skill.name) } }
        }
    }
}
