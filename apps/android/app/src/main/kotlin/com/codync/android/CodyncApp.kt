package com.codync.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.codync.android.core.BotDraft
import com.codync.android.core.CloudComputer
import com.codync.android.core.GroupDraft
import com.codync.android.design.*

private enum class Root { Loading, Welcome, Pairing, Tabs }

/** The app's root, as iOS `RootView`: the welcome and pairing until set up, then the tabs, with every sheet on top. */
@Composable fun CodyncApp(state: AppState, store: AppStore) {
    var welcomed by rememberSaveable { mutableStateOf(false) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    var accountSettings by rememberSaveable { mutableStateOf(false) }
    var showComputers by rememberSaveable { mutableStateOf(false) }
    var showPairing by rememberSaveable { mutableStateOf(false) }
    var showUsage by rememberSaveable { mutableStateOf(false) }
    var showMarketplace by rememberSaveable { mutableStateOf(false) }
    var access by remember { mutableStateOf<CloudComputer?>(null) }
    var editor by remember { mutableStateOf<BotDraft?>(null) }
    var groupEditor by remember { mutableStateOf<GroupDraft?>(null) }
    var handledNavigation by rememberSaveable { mutableIntStateOf(state.navigationRequest) }
    var handledGeneration by rememberSaveable { mutableIntStateOf(state.generation) }
    fun closeAll() {
        showAccount = false; accountSettings = false; showComputers = false; showPairing = false; showMarketplace = false
        access = null; editor = null; groupEditor = null
    }
    // A new account or a fresh start drops whatever was open for the old one.
    LaunchedEffect(state.generation) { if (handledGeneration != state.generation) { closeAll(); showUsage = false; handledGeneration = state.generation } }
    // A notification, widget or link: close everything, then maybe show usage.
    LaunchedEffect(state.navigationRequest) {
        if (handledNavigation != state.navigationRequest) { closeAll(); showUsage = state.navigationUsage; handledNavigation = state.navigationRequest }
    }
    val signedIn = state.account.userId != null
    val onboarding = !state.setupComplete && state.computers.isEmpty() && state.cloudComputers.isEmpty()
    // Signing in to an account that has computers completes setup, as pairing or skipping does.
    LaunchedEffect(state.cloudComputers.isNotEmpty(), state.loading) {
        if (!state.loading && !state.setupComplete && state.cloudComputers.isNotEmpty()) store.skipSetup()
    }
    val root = when {
        state.loading -> Root.Loading
        onboarding && !welcomed && !signedIn -> Root.Welcome
        onboarding -> Root.Pairing
        else -> Root.Tabs
    }
    BackHandler(root == Root.Pairing && !signedIn) { welcomed = false }
    val openMarketplace = { showAccount = false; accountSettings = false; showComputers = false; showMarketplace = true }
    Box(Modifier.fillMaxSize().background(Palette.background)) {
        AnimatedContent(root, transitionSpec = {
            if (initialState == Root.Welcome && targetState == Root.Pairing)
                (slideInHorizontally(Motion.layoutOffset) { it } + fadeIn(Motion.fade())) togetherWith (slideOutHorizontally(Motion.layoutOffset) { -it } + fadeOut(Motion.fade()))
            else if (initialState == Root.Pairing && targetState == Root.Welcome)
                (slideInHorizontally(Motion.layoutOffset) { -it } + fadeIn(Motion.fade())) togetherWith (slideOutHorizontally(Motion.layoutOffset) { it } + fadeOut(Motion.fade()))
            else fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
        }, label = "Root") { shown ->
            when (shown) {
                Root.Loading -> Box(Modifier.fillMaxSize())
                Root.Welcome -> WelcomeView(state, store) { welcomed = true }
                Root.Pairing -> Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    PairingView(state, store, onSkip = store::skipSetup) {
                        if (signedIn) AccountSwitcherButton(state) { showAccount = true } else BackButton { welcomed = false }
                    }
                }
                Root.Tabs -> MainTabs(state, store, account = { showAccount = true }, manage = { showComputers = true }, usage = { showUsage = true },
                    newBot = { editor = BotDraft() }, newGroup = { groupEditor = GroupDraft() },
                    edit = { bot -> if (bot.kind == "group") groupEditor = GroupDraft.from(bot) else editor = BotDraft.from(bot) }) {
                    ChatScreen(state, store) { bot -> if (bot.kind == "group") groupEditor = GroupDraft.from(bot) else editor = BotDraft.from(bot) }
                }
            }
        }
        if (root == Root.Tabs) {
            groupEditor?.let { draft -> GroupEditor(state, draft, store) { groupEditor = null } }
            if (showMarketplace) MarketplaceScreen(state, store) { showMarketplace = false }
        }
    }
    // The account sheet holds its own push (Computers & settings), as the iOS sheet's NavigationStack.
    CodyncSheet(showAccount && (state.setupComplete || signedIn) && !state.loading, { showAccount = false; accountSettings = false }) {
        NavStack(listOfNotNull("account", "settings".takeIf { accountSettings }), key = { it }, pop = { accountSettings = false }) { page ->
            if (page == "account") AccountSwitcherView(state, store, { accountSettings = true }) { showPairing = true }
            else SettingsView(state, store, { accountSettings = false }, { showPairing = true }, { access = it }, openMarketplace)
        }
    }
    CodyncSheet(showComputers && !state.loading, { showComputers = false }) {
        SettingsView(state, store, null, { showPairing = true }, { access = it }, openMarketplace)
    }
    CodyncSheet(access, { access = null }) { AccessRequestView(state, store, it) }
    CodyncSheet(showPairing && !state.loading, { showPairing = false }) { PairingView(state, store, inModal = true) }
    CodyncSheet(showUsage && !state.loading, { showUsage = false }) { UsageScreen(state) { store.refreshUsage(it) } }
    CodyncSheet(editor.takeIf { !state.loading }, { editor = null }) { draft ->
        key(state.contextId, state.generation, draft.id) { BotEditor(state, draft, store) }
    }
    // One place for errors the screen showing them doesn't handle itself (pairing, access, the editors do).
    val inline = root == Root.Pairing || showPairing || access != null || editor != null || groupEditor != null || showMarketplace
    CodyncDialog("Something went wrong", state.error != null && !inline, store::clearError, state.error, cancel = "OK") { emptyList() }
}
