package com.codync.android

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.codync.android.core.AgentModels
import com.codync.android.core.BotDraft
import com.codync.android.core.LinkState
import com.codync.android.design.CodyncIcon
import com.codync.android.design.CodyncIconButton
import com.codync.android.design.Glyph
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** The iPhone settings form, presented by the caller in the shared mobile sheet. */
@Composable fun BotEditor(state: AppState, initial: BotDraft, store: AppStore, close: () -> Unit, modal: Boolean = false) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var confirmDelete by remember(initial.id) { mutableStateOf(false) }
    var folderPicker by remember(initial.id) { mutableStateOf(false) }
    var defaultsApplied by remember(initial.id) { mutableStateOf(false) }
    val online = state.actualConnection is LinkState.Ready
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(initial.id, state.hello) {
        if (initial.id == null && !defaultsApplied && state.hello != null) {
            defaultsApplied = true
            val backends = state.hello["backends"]?.jsonArray.orEmpty().map { it.jsonObject }
            if (draft.backend == initial.backend && backends.none { it["id"]?.jsonPrimitive?.content == draft.backend && it["available"]?.jsonPrimitive?.booleanOrNull == true }) {
                backends.firstOrNull { it["available"]?.jsonPrimitive?.booleanOrNull == true }?.get("id")?.jsonPrimitive?.content?.let { draft = draft.copy(backend = it) }
            }
        }
    }
    LaunchedEffect(state.computers.firstOrNull()?.id, online) { if (online) store.refreshPlugins() }
    CodyncSheetBackHandler(onBack = {
        when {
            confirmDelete -> confirmDelete = false
            else -> close()
        }
    })
    Box(Modifier.fillMaxSize()) {
        Box(if (folderPicker) Modifier.clearAndSetSemantics {} else Modifier) { BotEditorForm(state, draft, { draft = it }, close, { store.saveBot(draft, close) },
            { folderPicker = true }, store::agentModels, store::refreshPlugins,
            { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Bot settings", draft.template()))) } },
            delete = { confirmDelete = true }, modal = modal,
            memory = { if (draft.id != null) MemoryCard(requireNotNull(draft.id), state, store) },
            deletion = {
                AnimatedVisibility(confirmDelete) { BotEditorCard {
                    Text("Delete this bot and its conversation history?")
                    Row {
                        TextButton(enabled = "delete:" + draft.id !in state.busy, onClick = {
                            state.bots.firstOrNull { it.id == draft.id }?.let { store.deleteBot(it, close) }
                        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                    }
                } }
            }) }
        if (folderPicker) {
            CodyncSheet("Folders", close = { folderPicker = false }) { dismissFolder ->
                BotEditorFolders(state.computers.firstOrNull()?.id,
                    draft.cwd.takeIf(String::isNotBlank) ?: state.hello?.get("home")?.jsonPrimitive?.contentOrNull,
                    online, store::listDirs, { draft = draft.copy(cwd = it); dismissFolder() }, dismissFolder, inPopover = true)
            }
        }
    }
}

/** Controlled form: presentation never changes the host's bot draft or save contract. */
@Composable internal fun BotEditorForm(state: AppState, draft: BotDraft, change: (BotDraft) -> Unit,
    close: () -> Unit, save: () -> Unit, chooseFolder: () -> Unit, loadModels: suspend (String) -> AgentModels,
    refreshPlugins: () -> Unit, copyTemplate: () -> Unit, delete: () -> Unit,
    modal: Boolean = false, memory: @Composable () -> Unit = {}, deletion: @Composable () -> Unit = {}) {
    val online = state.actualConnection is LinkState.Ready
    var advanced by remember(draft.id) { mutableStateOf(false) }
    val saving = "editor" in state.busy
    val screen = state.hello?.get("screen")?.takeUnless { it == JsonNull }?.jsonObject
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(if (modal) Modifier else Modifier.safeDrawingPadding().imePadding()) {
            BotEditorHeader(if (draft.id == null) "New bot" else "Settings", close) {
                if (saving) Text("Saving…", style = MaterialTheme.typography.bodySmall)
                else CodyncIconButton(if (draft.id == null) "Create" else "Save", Glyph.Check, save,
                    enabled = draft.isValid && online)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                BotEditorAvatar(draft.avatarShape, draft.avatarColor,
                    { change(draft.copy(avatarShape = it)) }, { change(draft.copy(avatarColor = it)) })
                if (draft.id != null) BotEditorField("Name", draft.name, { change(draft.copy(name = it)) }, "Name")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BotEditorField("Standing instructions", draft.description, { change(draft.copy(description = it)) },
                        "e.g. Reviews PRs. Never pushes without asking.", minLines = 3, maxLines = 8)
                    Text("Rules that always apply. Put task-specific requests in the chat instead.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BotEditorCard {
                    BotEditorOptionRow("Computer") {
                        Text(state.computers.firstOrNull()?.name ?: state.hello?.get("hostName")?.jsonPrimitive?.contentOrNull ?: "Your computer",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    if (!online) Text(if (draft.id == null) "Connect this computer to create the bot."
                        else "Reconnect to this computer to save bot settings.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    val backends = state.hello?.get("backends")?.jsonArray.orEmpty().map { it.jsonObject }
                    val agents = backends.map { info ->
                        val id = info.getValue("id").jsonPrimitive.content
                        id to ((info["name"]?.jsonPrimitive?.content ?: id) +
                            if (info["available"]?.jsonPrimitive?.booleanOrNull == false) " (not installed)" else "")
                    } + ("custom" to "Custom command")
                    BotEditorOptionRow("Agent") {
                        BotEditorChoice("Agent", draft.backend, agents) {
                            if (draft.backend != it) change(draft.copy(backend = it, model = null))
                        }
                    }
                    backends.firstOrNull { it["id"]?.jsonPrimitive?.content == draft.backend && it["available"]?.jsonPrimitive?.booleanOrNull == false }
                        ?.get("installHint")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    if (draft.backend == "custom") BotEditorField("ACP command", draft.command.orEmpty(),
                        { change(draft.copy(command = it.takeIf(String::isNotBlank))) }, "ACP command, e.g. my-agent --acp", monospace = true)
                    BotEditorModel(state.computers.firstOrNull()?.id, draft.backend, draft.model, online,
                        { change(draft.copy(model = it)) }, loadModels)
                    BotEditorOptionRow("Workspace") {
                        BotEditorChoice("Workspace", if (draft.cwd.isEmpty()) "personal" else "project",
                            listOf("personal" to "Personal workspace", "project" to "Choose project folder…"),
                            label = if (draft.cwd.isEmpty()) "Personal" else draft.cwd.trimEnd('/').substringAfterLast('/').ifEmpty { "/" },
                            enabled = true) { if (it == "personal") change(draft.copy(cwd = "")) else chooseFolder() }
                    }
                    Text(if (draft.cwd.isEmpty()) "This bot has its own space for files. It can work in other folders when you ask."
                        else "This project is the default starting folder. The bot can work elsewhere when you ask.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BotEditorOptionRow("Permissions") {
                        BotEditorChoice("Permissions", draft.permission, listOf("ask" to "Ask me", "auto" to "Approve automatically")) {
                            change(draft.copy(permission = it))
                        }
                    }
                    BotEditorToggle("Notifications", "Get notified when this bot finishes or needs you", draft.notify != false) {
                        change(draft.copy(notify = it))
                    }
                    if (screen != null) BotEditorToggle("Use the computer",
                        if (screen["enabled"]?.jsonPrimitive?.booleanOrNull == true)
                            "Let this bot see the screen and use the mouse and keyboard."
                        else "Let this bot see the screen and use the mouse and keyboard. Turn on Remote screen in Codync's menu on the computer first.",
                        draft.computer) { change(draft.copy(computer = it)) }
                }
                BotEditorPlugins(state, draft, change)
                memory()
                Text(if (draft.permission == "auto")
                    "Tool requests are approved automatically. Your agent's own settings (like Claude Code's permission rules) still apply."
                    else "You'll get an approval card and a notification whenever the agent asks.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { advanced = !advanced }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("More settings")
                    Spacer(Modifier.width(6.dp))
                    CodyncIcon(Glyph.Down, Modifier.size(16.dp))
                }
                AnimatedVisibility(advanced) { BotEditorCard {
                    BotEditorToggle("Pinned", "Keep this bot at the top of your list", draft.pinned) { change(draft.copy(pinned = it)) }
                    BotEditorToggle("Show in roster", "Include this bot in your main bot list", !draft.hidden) { change(draft.copy(hidden = !it)) }
                    if (screen == null) BotEditorToggle("Use the computer",
                        "Let this bot use screen, mouse and keyboard tools when Remote screen is available.", draft.computer) { change(draft.copy(computer = it)) }
                    BotEditorField("Working folder", draft.cwd, { change(draft.copy(cwd = it)) }, "Personal workspace")
                    TextButton(enabled = online && "plugins" !in state.busy, onClick = refreshPlugins) { Text("Refresh installed plugins") }
                    TextButton(onClick = copyTemplate) { Text("Copy settings template") }
                    if (draft.id != null) TextButton(onClick = delete) { Text("Delete bot", color = MaterialTheme.colorScheme.error) }
                } }
                deletion()
            }
        }
    }
}

@Composable internal fun BotEditorHeader(title: String, close: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing()
        CodyncIconButton("Close", Glyph.Close, close)
    }
}

@Composable internal fun BotEditorCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp))
        .padding(horizontal = 18.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
}
