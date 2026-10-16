package com.codync.android


import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.codync.android.core.LinkState
import com.codync.android.design.*

@Composable
fun CodyncApp(state: AppState, pair: (String) -> Unit, skip: () -> Unit, retry: () -> Unit, clearError: () -> Unit, reset: () -> Unit, store: AppStore? = null) {
    var welcomePassed by rememberSaveable { mutableStateOf(false) }
    var showPairing by rememberSaveable { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var confirmsReset by rememberSaveable { mutableStateOf(false) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    var showUsage by rememberSaveable { mutableStateOf(false) }
    var showScanner by rememberSaveable { mutableStateOf(false) }
    var showAccountPairing by rememberSaveable { mutableStateOf(false) }
    var showMarketplace by rememberSaveable { mutableStateOf(false) }
    val setupState = rememberSaveableStateHolder()
    var editor by remember { mutableStateOf<com.codync.android.core.BotDraft?>(null) }
    var groupEditor by remember { mutableStateOf<com.codync.android.core.GroupDraft?>(null) }
    var handledNavigation by rememberSaveable { mutableIntStateOf(0) }
    var handledGeneration by rememberSaveable { mutableIntStateOf(state.generation) }
    LaunchedEffect(state.generation) {
        if (handledGeneration != state.generation) {
            editor = null; groupEditor = null; showUsage = false; showPairing = false; showScanner = false; showAccountPairing = false; showMarketplace = false
            pasted = ""; confirmsReset = false; handledGeneration = state.generation
        }
    }
    LaunchedEffect(state.navigationRequest) {
        if (handledNavigation != state.navigationRequest) {
            showAccount = false; showUsage = state.navigationUsage; showPairing = false; showScanner = false; showAccountPairing = false; showMarketplace = false; confirmsReset = false
            editor = null; groupEditor = null; handledNavigation = state.navigationRequest
        }
    }
    if (showScanner && !showAccount) {
        PairingScanner({ link -> showScanner = false; pair(link) }, close = { showScanner = false })
        return
    }
    if (store != null && showMarketplace && !state.loading) {
        MarketplaceScreen(state, store) { showMarketplace = false }
        return
    }
    if (store != null && showUsage && !state.loading) {
        LaunchedEffect(state.computers.firstOrNull()?.id) { if (state.actualConnection is LinkState.Ready) store.refreshUsage(false) }
        UsageScreen(state, { store.refreshUsage() }) { showUsage = false }
        return
    }
    if (store != null && groupEditor != null && !state.loading) {
        GroupEditor(state, requireNotNull(groupEditor), store) { groupEditor = null }
        return
    }
    val accountOpen = store != null && showAccount
    val editorOpen = store != null && editor != null && !state.loading
    Box(if (accountOpen || editorOpen) Modifier.clearAndSetSemantics {} else Modifier) {
        if (store != null && state.selectedBot != null && !state.loading) {
            ChatScreen(state, store, { bot ->
                if (bot.kind == "group") groupEditor = com.codync.android.core.GroupDraft.from(bot)
                else editor = com.codync.android.core.BotDraft.from(bot)
            })
            return@Box
        }
        LaunchedEffect(state.pairing) {
            if (!state.pairing && state.error == null && state.computers.isNotEmpty()) {
                showPairing = false
                showAccountPairing = false
                pasted = ""
            }
        }
        val page = when {
            state.loading -> "loading"
            state.pairing -> "pair"
            !state.setupComplete && !welcomePassed -> "welcome"
            !state.setupComplete || showPairing -> "pair"
            else -> "bots"
        }
        BackHandler(page == "pair" && !state.pairing) {
            if (state.setupComplete) showPairing = false else welcomePassed = false
        }
        if (page == "bots") {
            BotHomeScreen(state, store, accounts = { showAccount = true }, pair = { showPairing = true },
                marketplace = { showMarketplace = true }, usage = { showUsage = true },
                newBot = { editor = com.codync.android.core.BotDraft() },
                newGroup = { groupEditor = com.codync.android.core.GroupDraft() },
                edit = { bot ->
                    if (bot.kind == "group") groupEditor = com.codync.android.core.GroupDraft.from(bot)
                    else editor = com.codync.android.core.BotDraft.from(bot)
                }, retry = retry, clearError = clearError)
            return@Box
        }
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Codync", style = MaterialTheme.typography.titleLarge)
                    if (store != null) TextButton(onClick = { showAccount = true }) { Text("Accounts") }
                    if (store != null && state.setupComplete) TextButton(onClick = { showUsage = true }) { Text("Usage") }
                    if (store != null && state.computers.isNotEmpty()) TextButton(onClick = { showMarketplace = true }) { Text("Marketplace") }
                    if (state.setupComplete && page != "pair") TextButton(onClick = { showPairing = true }) { Text("Pair computer") }
                }
                state.error?.let { error ->
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(16.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = clearError) { Text("Dismiss") }
                    }
                }
                if (store != null) state.voice?.let { VoiceControls(it, state, store) }
                AnimatedContent(targetState = confirmsReset, label = "reset") { confirming ->
                    if (confirming) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Forget this phone’s pairing and settings? Your computer keeps its bots and chats.")
                        Row {
                            TextButton(onClick = { confirmsReset = false; welcomePassed = false; pasted = ""; reset() }) { Text("Start over") }
                            TextButton(onClick = { confirmsReset = false }) { Text("Cancel") }
                        }
                    }
                }
                AnimatedContent(targetState = page, modifier = Modifier.weight(1f), label = "navigation") { destination ->
                    when (destination) {
                        "loading" -> Text("Loading…")
                        "welcome" -> Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Spacer(Modifier.height(48.dp))
                            Text("Your coding agents, wherever you are.", style = MaterialTheme.typography.headlineLarge)
                            Text("Connect your computer to see your bots and their activity.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (store != null) SignInButtons(state, store)
                            Button(onClick = { welcomePassed = true }) { Text("Get started") }
                        }
                        "pair" -> setupState.SaveableStateProvider("pairing") {
                            PairingSetup(pasted, { pasted = it }, state.pairing, state.setupComplete,
                                { pair(pasted) }, { showScanner = true }, { if (state.setupComplete) showPairing = false else skip() })
                        }

                    }
                }
                if (state.setupComplete || state.error != null) TextButton(onClick = { confirmsReset = true }) { Text("Start over") }
            }
        }
    }
    if (accountOpen) {
        CodyncSheet("Account", close = { showAccount = false; showAccountPairing = false; showScanner = false }) { close ->
            Box(if (showAccountPairing || showScanner) Modifier.clearAndSetSemantics {} else Modifier) {
                AccountScreen(state, store, close, scan = { showScanner = true }, pair = { showAccountPairing = true }, inPopover = true)
            }
        }
        if (showAccountPairing) CodyncSheet("Pair a computer", close = { showAccountPairing = false },
            back = { if (!state.pairing) it() }) { close ->
            Box(if (showScanner) Modifier.clearAndSetSemantics {} else Modifier) {
                Column {
                    CodyncTopBar(title = { Text("Pair a computer", style = MaterialTheme.typography.titleMedium) },
                        trailing = { CodyncIconButton("Close pairing", Glyph.Close, close, enabled = !state.pairing) })
                    state.error?.let { InlineError(it, clearError) }
                    setupState.SaveableStateProvider("account-pairing") {
                        Box(Modifier.padding(16.dp)) {
                            PairingSetup(pasted, { pasted = it }, state.pairing, state.setupComplete,
                                { pair(pasted) }, { showScanner = true }, close,
                                startAtPairing = true, closeLabel = "Back to account")
                        }
                    }
                }
            }
        }
        if (showScanner) CodyncSheet("Scan a computer's code", close = { showScanner = false }) { close ->
            PairingScanner({ link -> showScanner = false; pair(link) }, close, inPopover = true)
        }
    }
    if (editorOpen) key(state.contextId, state.generation, editor?.id) {
        val draft = requireNotNull(editor)
        CodyncSheet(if (draft.id == null) "New bot" else "Settings", close = { editor = null }) { close ->
            BotEditor(state, draft, store, close, modal = true)
        }
    }
}
