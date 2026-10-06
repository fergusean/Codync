package com.codync.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.codync.android.core.*
import com.codync.android.design.*

/** Match the existing iOS roster hierarchy: compact chrome, flat rows, actions on demand. */
@Composable internal fun BotHomeScreen(state: AppState, store: AppStore?, accounts: () -> Unit, pair: () -> Unit,
    marketplace: () -> Unit, usage: () -> Unit, newBot: () -> Unit, newGroup: () -> Unit, edit: (Bot) -> Unit,
    retry: () -> Unit, clearError: () -> Unit) {
    var tab by rememberSaveable(state.contextId) { mutableStateOf("Bots") }
    var computers by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var hidden by rememberSaveable { mutableStateOf(false) }
    BackHandler(tab == "State" || search) { if (search) { search = false; query = "" } else tab = "Bots" }
    val computer = state.computers.firstOrNull()
    val connected = state.connection is LinkState.Ready
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            AnimatedContent(tab, Modifier.weight(1f), label = "primary tab") { destination ->
                if (destination == "State") StateScreen(state, store, usage)
                else Column(Modifier.fillMaxSize()) {
                    CodyncTopBar(title = {
                        Box {
                            Row(Modifier.clip(RoundedCornerShape(20.dp)).clickable { computers = !computers }.semantics { contentDescription = "Computers" }.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(when {
                                    computer == null -> "Computers"
                                    connected -> "${state.computers.size} connected"
                                    state.connection is LinkState.Connecting -> "Connecting…"
                                    state.connection is LinkState.ComputerOffline -> "Computer offline"
                                    else -> "Disconnected"
                                }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                CodyncIcon(Glyph.Down, Modifier.size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            CodyncPopupMenu(computers, { computers = false }) {
                                computer?.let { Text(it.name, Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium) }
                                MenuAction("Computers & accounts") { computers = false; accounts() }
                                MenuAction("Pair computer") { computers = false; pair() }
                                if (computer != null) {
                                    MenuAction("Marketplace") { computers = false; marketplace() }
                                    MenuAction("Usage") { computers = false; usage() }
                                    MenuAction("Search bots") { computers = false; search = true }
                                    MenuAction(if (hidden) "Show visible bots" else "Hidden bots") { computers = false; hidden = !hidden }
                                    MenuAction("Refresh") { computers = false; retry() }
                                }
                            }
                        }
                    }, leading = {
                        Row(Modifier.height(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = accounts)
                            .semantics { contentDescription = "Accounts" }.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                                CodyncIcon(Glyph.Person, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                                val avatar = state.account.accounts.firstOrNull { it.userId == state.account.userId }?.avatar
                                if (avatar != null) AsyncImage(avatar, contentDescription = null, imageLoader = LocalNetworkImages.current,
                                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            CodyncIcon(Glyph.Down, Modifier.size(12.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }, trailing = {
                        Box {
                            CodyncIconButton("New", Glyph.Plus, { creating = !creating }, enabled = computer != null)
                            CodyncPopupMenu(creating, { creating = false }) {
                                MenuAction("New bot") { creating = false; newBot() }
                                MenuAction("New group chat", enabled = state.bots.any { it.kind != "group" && !it.deleted }) { creating = false; newGroup() }
                            }
                        }
                    })
                    state.error?.let { InlineError(it, clearError) }
                    state.access?.let { ticket ->
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Compare ${ticket.code.chunked(3).joinToString(" ")} with your computer.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = accounts) { Text("View request") }
                        }
                    }
                    AnimatedVisibility(search) {
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CodyncField(query, { query = it }, Modifier.weight(1f), placeholder = { Text("Search bots") }, singleLine = true)
                            CodyncIconButton("Close search", Glyph.Close, { search = false; query = "" })
                        }
                    }
                    if (store != null) state.voice?.let { VoiceControls(it, state, store) }
                    val bots = state.bots.filter { !it.deleted && it.hidden == hidden && it.name.contains(query.trim(), ignoreCase = true) }
                        .sortedWith(compareByDescending<Bot> { it.pinned }.thenByDescending { it.lastAt })
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                        if (hidden) item { Text("Hidden bots", Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.titleSmall) }
                        if (bots.isEmpty()) item {
                            Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                CharacterAvatar("cloud", "green", 72.dp)
                                Text(when {
                                    computer == null -> "No computer yet"
                                    query.isNotBlank() -> "No matching bots"
                                    connected && !state.rosterLoaded -> "Syncing bots…"
                                    else -> "No bots yet"
                                }, style = MaterialTheme.typography.titleMedium)
                                if (computer == null) Button(onClick = pair) { Text("Pair computer") }
                                else if (query.isBlank() && state.rosterLoaded) TextButton(onClick = newBot) { Text("Create your first bot") }
                                if (state.connection is LinkState.Failed) TextButton(onClick = retry) { Text("Retry") }
                            }
                        }
                        items(bots, key = Bot::id) { bot -> BotRosterRow(bot, state, store) { edit(bot) } }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), contentAlignment = Alignment.Center) { CodyncFloatingTabs(tab) { tab = it } }
        }
    }
}

@Composable internal fun CodyncPopupMenu(expanded: Boolean, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val transition = remember { MutableTransitionState(false) }; transition.targetState = expanded
    val offset = with(LocalDensity.current) { 48.dp.roundToPx() }
    if (transition.currentState || transition.targetState) Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, offset),
        onDismissRequest = close, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(transition, enter = fadeIn() + scaleIn(initialScale = .96f), exit = fadeOut() + scaleOut(targetScale = .96f)) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 8.dp) {
                Column(Modifier.widthIn(min = 210.dp, max = 280.dp).padding(6.dp), content = content)
            }
        }
    }
}

@Composable internal fun MenuAction(label: String, enabled: Boolean = true, action: () -> Unit) {
    Text(label, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled, role = Role.Button, onClick = action)
        .padding(horizontal = 12.dp, vertical = 13.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .35f), style = MaterialTheme.typography.bodyMedium)
}

@Composable internal fun InlineError(message: String, clear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        CodyncIconButton("Dismiss", Glyph.Close, clear)
    }
}
