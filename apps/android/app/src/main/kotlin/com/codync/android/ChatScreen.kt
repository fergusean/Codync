package com.codync.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import com.codync.android.design.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.codync.android.core.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

@Composable fun ChatScreen(state: AppState, store: AppStore, edit: (Bot) -> Unit) {
    key(state.contextId, state.generation, state.selectedBot) {
        Box(if (state.thread != null) Modifier.clearAndSetSemantics {} else Modifier) {
            ConversationScreen(if (state.thread == null) state else state.copy(thread = null, trace = false), store, edit)
        }
        if (state.thread != null) key(state.thread) {
            RepliesPopover(close = { store.openBot(state.selectedBot) }, back = {
                if (state.trace) store.showTrace(false) else it()
            }) { close ->
                Box(if (state.trace) Modifier.clearAndSetSemantics {} else Modifier) {
                    ConversationScreen(state.copy(trace = false), store, edit, close)
                }
            }
            if (state.trace) RepliesPopover(title = "Full conversation", close = { store.showTrace(false) }, back = { it() }) {
                close -> ConversationScreen(state, store, edit, close)
            }
        }
    }
}

@Composable internal fun ConversationScreen(state: AppState, store: AppStore, edit: (Bot) -> Unit,
    closeThread: (() -> Unit)? = null) {
    val bot = state.bots.firstOrNull { it.id == state.selectedBot } ?: return
    val thread = state.thread
    val root = state.entries.firstOrNull { it.botId == bot.id && it.id == thread }
    var showRoutines by remember(bot.id) { mutableStateOf(false) }
    if (showRoutines) { RoutinesScreen(bot, state, store) { showRoutines = false }; return }
    var setupEntry by rememberSaveable(bot.id) { mutableStateOf<String?>(null) }
    var appSlug by rememberSaveable(bot.id) { mutableStateOf<String?>(null) }
    var appName by rememberSaveable(bot.id) { mutableStateOf("") }
    var connectorItem by remember(bot.id) { mutableStateOf<MarketConnector?>(null) }
    if (appSlug != null) { AppConnectScreen(ComposioApp(requireNotNull(appSlug), appName), state, store,
        { appSlug = null; setupEntry = null }, setupEntry); return }
    if (connectorItem != null) { ConnectorInstaller(requireNotNull(connectorItem), state, store, requestId = setupEntry) {
        connectorItem = null; setupEntry = null
    }; return }
    var botChat by remember(bot.id) { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val entries = state.entries.filter { it.botId == bot.id &&
        (if (thread != null) it.threadId == thread && (state.trace || it.isChat) else state.trace || it.threadId == null && it.isChat) }
    val items = remember(entries, thread, state.trace) {
        if (thread == null && !state.trace) groupBotExchanges(entries) else entries.map { ChatItem.Single(it) }
    }
    val pending = state.pending.filter { it.botId == bot.id && it.threadId == state.thread }
    val list = rememberLazyListState()
    var messageViewport by remember { mutableStateOf(Rect.Zero) }
    val scope = rememberCoroutineScope()
    var atBottom by remember(bot.id, state.thread) { mutableStateOf(true) }
    var following by remember(bot.id, state.thread, state.trace) { mutableStateOf(true) }
    var userScroll by remember(bot.id, state.thread, state.trace) { mutableIntStateOf(0) }
    val scrolling = remember(bot.id, state.thread, state.trace) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) { following = false; userScroll++ }
            return Offset.Zero
        }
    } }
    LaunchedEffect(userScroll) {
        if (userScroll > 0) {
            withFrameNanos { }
            snapshotFlow { list.isScrollInProgress }.first { !it }
            withFrameNanos { }
            following = atBottom
        }
    }
    LaunchedEffect(list, bot.id, state.thread) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 2 } ?: true }
            .distinctUntilChanged().collect { atBottom = it }
    }
    fun back() { if (closeThread != null) closeThread() else if (state.trace) store.showTrace(false) else store.openBot(null) }
    BackHandler(onBack = ::back)
    LaunchedEffect(bot.id, state.thread, state.trace) {
        if (entries.isNotEmpty() || pending.isNotEmpty()) list.scrollToItem(maxOf(0, items.size + pending.size))
    }
    LaunchedEffect(entries.lastOrNull()?.id, entries.lastOrNull()?.rev, pending.lastOrNull()?.nonce) {
        if (following && (entries.isNotEmpty() || pending.isNotEmpty())) list.animateScrollToItem(items.size + pending.size)
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(if (closeThread == null) Modifier.safeDrawingPadding().imePadding() else Modifier) {
            if (thread != null) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (state.trace) "Full conversation" else "Thread", style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold)
                        Text(bot.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    CodyncIconButton(if (state.trace) "Close full conversation" else "Close thread", Glyph.Close, ::back)
                }
                HorizontalDivider(thickness = .5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
            } else {
                CodyncTopBar(title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BotAvatar(bot, state.bots, 32.dp)
                        Column {
                            Text(if (state.trace) "Full conversation" else bot.name,
                                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (state.connection is LinkState.Ready) bot.activity.ifBlank { "Connected" } else "Connecting…",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }, leading = {
                    CodyncIconButton("Back", Glyph.Back, ::back)
                }, trailing = {
                    Row {
                        if (bot.status in listOf("working", "needsInput")) CodyncIconButton("Stop", Glyph.Stop,
                            { store.command("stop", body("botId" to bot.id)) }, enabled = "stop" !in state.busy)
                        Box {
                            CodyncIconButton("Conversation actions", Glyph.More, { menu = !menu })
                            CodyncPopupMenu(menu, { menu = false }) {
                                MenuAction("Edit") { menu = false; edit(bot) }
                                MenuAction(if (state.trace) "Chat" else "Full conversation") { menu = false; store.showTrace(!state.trace) }
                                if (bot.kind != "group") {
                                    MenuAction("Routines") { menu = false; showRoutines = true }
                                    MenuAction("New session") { menu = false; confirm = "New session" }
                                }
                            }
                        }
                    }
                })
            }
            if (state.connection !is LinkState.Ready) Text(when (val link = state.connection) {
                LinkState.Connecting -> "Connecting…"
                is LinkState.ComputerOffline -> "Computer offline · text can wait in the relay"
                is LinkState.Failed -> link.message
            }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.error?.let { InlineError(it, store::clearError) }
            if (thread == null) state.voice?.let { VoiceControls(it, state, store) }
            AnimatedContent(confirm, label = "confirmation") { action ->
                if (action != null) Column {
                    Text("Start a new agent session? Your conversation history is kept.")
                    Row { TextButton(onClick = { confirm = null; store.command("newSession", body("botId" to bot.id)) }) { Text("New session") }
                        TextButton(onClick = { confirm = null }) { Text("Cancel") } }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).nestedScroll(scrolling)
                .onGloballyPositioned { messageViewport = it.boundsInWindow() }, state = list,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, if (thread == null) Alignment.Bottom else Alignment.Top)) {
                item {
                    if (thread != null && !state.trace) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (root != null) EntryRow(root, state, store, messageViewport,
                            { id, app -> setupEntry = id; appName = app.name; appSlug = app.slug },
                            { id, item -> setupEntry = id; connectorItem = item }, { botChat = it })
                        else Text("The original message is not available in the saved history.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if ("thread:$thread" in state.busy && entries.isEmpty()) "Loading replies…"
                                else if (entries.isEmpty()) "No replies yet" else if (entries.size == 1) "1 reply" else "${entries.size} replies",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
                        }
                    }
                    if (bot.id !in state.historyComplete && (thread == null || root == null)) TextButton(
                    enabled = "history" !in state.busy, onClick = store::loadHistory) { Text(if ("history" in state.busy) "Loading…" else "Older messages") } }
                itemsIndexed(items, key = { _, item -> item.entry.id }) { index, item ->
                    val entry = item.entry
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (thread == null && entry.createdAt > 0 && (index == 0 || entry.createdAt - items[index - 1].entry.createdAt > 3_600_000))
                            Text(conversationDate(entry.createdAt), Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f))
                        if (item is ChatItem.Exchanges) BotMessageRow(item.group, state) { botChat = item.group.peer }
                        else EntryRow(entry, state, store, messageViewport,
                            { id, app -> setupEntry = id; appName = app.name; appSlug = app.slug },
                            { id, connector -> setupEntry = id; connectorItem = connector }, { botChat = it })
                    }
                }
                items(pending, key = { "local-" + it.nonce }) { send -> PendingRow(send, state, store) }
                item { if (thread == null && entries.isEmpty() && pending.isEmpty()) Text("Start a conversation", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (!following && !atBottom) TextButton(onClick = { following = true; scope.launch { list.animateScrollToItem(items.size + pending.size) } }) { Text("Latest messages") }
            if (!state.trace) key(bot.id, state.thread) { Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { MessageComposer(bot, state, store) } }
        }
    }
    botChat?.let { peer -> CodyncSheet(botPairTitle(bot.id, peer, state.bots), close = { botChat = null }) { close ->
        BotConversationScreen(bot.id, peer, state, store, close)
    } }
}

@Composable private fun EntryRow(entry: Entry, state: AppState, store: AppStore, viewport: Rect,
    openApp: (String, ComposioApp) -> Unit, install: (String, MarketConnector) -> Unit,
    openBotChat: (String) -> Unit) {
    entry.botExchange?.let { exchange ->
        BotMessageRow(BotExchangeGroup(exchange, 1, exchange.outcome is BotOutcome.Failed), state) { openBotChat(exchange.peer) }
        return
    }
    val user = entry.kind == "user"
    val notice = entry.kind == "notice"
    val actionable = entry.isChat && entry.kind != "permission"
    val context = LocalContext.current
    val reactions = entry.data["reactions"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    Box(Modifier.fillMaxWidth().padding(start = if (user) 48.dp else 0.dp, end = if (user || notice) 0.dp else 24.dp),
        contentAlignment = if (user) Alignment.CenterEnd else if (notice) Alignment.Center else Alignment.CenterStart) {
        key(state.contextId, state.generation, state.thread, state.trace, entry.id) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MessageActionBubble(viewport, enabled = actionable, reactions = reactions, reacting = "react:" + entry.id in state.busy,
                react = { emoji -> store.command("react", body("entryId" to entry.id, "emoji" to emoji), "react:" + entry.id) },
                reply = if (state.thread == null && entry.threadId == null) ({ store.openBot(entry.botId, entry.id) }) else null,
                trace = if (entry.kind == "agent") ({ store.showTrace(true) }) else null,
                copy = { context.getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Message", entry.text)) },
                modifier = Modifier.widthIn(max = 680.dp)
                .background(if (notice) androidx.compose.ui.graphics.Color.Transparent else if (user) MaterialTheme.colorScheme.surfaceContainerHighest
                    else MaterialTheme.colorScheme.surfaceContainer,
                    androidx.compose.foundation.shape.RoundedCornerShape(18.dp)),
                padding = if (notice) 6.dp else 14.dp) {
                val author = entry.data["author"]?.jsonPrimitive?.contentOrNull
                val group = state.bots.firstOrNull { it.id == entry.botId }?.kind == "group"
                val label = if (user) null else if (group) author?.let { id -> state.bots.firstOrNull { it.id == id }?.name ?: "A deleted bot" }
                    else if (state.trace) entry.kind else null
                label?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (entry.data["connectionRequest"] is JsonObject) ConnectionRequestCard(entry, state, store, openApp, install)
                else if (entry.kind == "permission") PermissionRow(entry, state, store)
                else if (entry.kind == "notice" && entry.data["callSeconds"]?.jsonPrimitive?.longOrNull != null)
                    Text("Voice chat · " + callDuration(requireNotNull(entry.data["callSeconds"]?.jsonPrimitive?.longOrNull)))
                else if (entry.kind in listOf("user", "agent", "notice")) MarkdownText(entry.text, selectable = !actionable)
                else {
                    Text(entry.data["title"]?.jsonPrimitive?.contentOrNull ?: entry.kind)
                    if (entry.text.isNotBlank()) MarkdownText(entry.text)
                    entry.data["output"]?.jsonPrimitive?.contentOrNull?.let { CodeText(it) }
                    if (entry.text.isBlank() && entry.data["output"] == null) CodeText(entry.data.toString())
                }
                val attachments = entry.data["attachments"]?.jsonArray?.map { WireJson.decodeFromJsonElement<Attachment>(it) }.orEmpty()
                for (file in attachments) AttachmentRow(entry.botId, file, state, store)
                if (entry.isChat && entry.kind != "permission") {
                    if (reactions.isNotEmpty()) Text(reactions.joinToString(" "))
                    if (state.thread == null && entry.threadId == null) {
                        val count = entry.data["thread"]?.jsonObject?.get("count")?.jsonPrimitive?.intOrNull ?: 0
                        if (count > 0) TextButton(onClick = { store.openBot(entry.botId, entry.id) }) { Text("$count replies") }
                    }
                }
            }
            }
        }
    }
}

@Composable private fun PermissionRow(entry: Entry, state: AppState, store: AppStore) {
    var expanded by remember { mutableStateOf(false) }
    val data = entry.data
    Text(data["title"]?.jsonPrimitive?.contentOrNull ?: "Wants to use a tool", style = MaterialTheme.typography.titleMedium)
    Text("Runs on " + state.computers.firstOrNull()?.name.orEmpty(), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { expanded = !expanded }) { Text("Details") }
    AnimatedContent(expanded, label = "permission details") { show -> if (show) SelectionContainer {
        CodeText(listOfNotNull(data["command"]?.jsonPrimitive?.contentOrNull, data["detail"]?.jsonPrimitive?.contentOrNull,
            data["diffs"]?.toString()).joinToString("\n"))
    } }
    if (entry.status == "pending") {
        val busy = "permission:" + entry.id in state.busy
        val rank = mapOf("allow_once" to 0, "allow_always" to 1, "reject_once" to 2, "reject_always" to 3)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (option in data["options"]?.jsonArray.orEmpty().sortedBy { rank[it.jsonObject["kind"]?.jsonPrimitive?.content] ?: 9 }) {
            val value = option.jsonObject
            val id = value.getValue("optionId").jsonPrimitive.content
            val label = when (value["kind"]?.jsonPrimitive?.content) {
                "allow_once" -> "Allow once"
                "allow_always" -> "Always allow"
                "reject_once" -> "Deny"
                "reject_always" -> "Never"
                else -> value["name"]?.jsonPrimitive?.content ?: "Respond"
            }
            Button(enabled = !busy, onClick = { store.respond(entry, id) }) { Text(if (busy) "Answering…" else label) }
        }
        }
    } else Text(when (entry.status) { "answered" -> "Answered"; "cancelled" -> "Cancelled"; else -> "Expired — the agent moved on" })
}

@Composable private fun PendingRow(send: PendingSend, state: AppState, store: AppStore) {
    Box(Modifier.fillMaxWidth().padding(start = 48.dp), contentAlignment = Alignment.CenterEnd) {
    Column(Modifier.widthIn(max = 680.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest,
        androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).padding(14.dp)) {
        SelectionContainer { Text(send.text) }
        send.files.forEach { Text(it.name + " · " + formatBytes(it.size)) }
        Text(when (send.status) {
            SendStatus.Sending -> state.uploadProgress[send.nonce]?.let { "Uploading files · $it%" } ?: "Sending…"
            SendStatus.Waiting -> "Waiting for the computer"
            SendStatus.Delivering -> "Delivery in progress"
            SendStatus.Failed -> send.error ?: "Failed to send"
        }, style = MaterialTheme.typography.bodySmall)
        Row {
            if (send.status == SendStatus.Failed) {
                TextButton(enabled = "send:" + send.nonce !in state.busy, onClick = { store.retry(send) }) { Text("Resend") }
                TextButton(onClick = { store.discard(send) }) { Text("Discard") }
            } else if (send.status == SendStatus.Waiting) TextButton(enabled = "cancel:" + send.nonce !in state.busy,
                onClick = { store.cancelQueued(send) }) { Text("Take back") }
        }
    }
    }
}

internal fun conversationDate(milliseconds: Long): String {
    val date = Instant.ofEpochMilli(milliseconds).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    val day = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(milliseconds))
    }
    return day + " " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(milliseconds))
}
