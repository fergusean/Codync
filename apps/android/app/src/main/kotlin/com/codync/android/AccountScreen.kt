package com.codync.android

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.codync.android.core.*
import com.codync.android.design.*

/** UI callbacks keep fixture previews from touching accounts or computer credentials. */
internal data class AccountScreenActions(
    val signIn: (apple: Boolean, hosted: Boolean) -> Unit = { _, _ -> },
    val switchAccount: (SavedAccount) -> Unit = {},
    val retryAccounts: () -> Unit = {},
    val signOut: () -> Unit = {},
    val refresh: () -> Unit = {},
    val clearError: () -> Unit = {},
    val cancelAccess: () -> Unit = {},
    val setRoute: (ConnectionRoute) -> Unit = {},
    val forgetComputer: () -> Unit = {},
    val requestAccess: (CloudComputer) -> Unit = {},
    val loadGrants: (String) -> Unit = {},
    val revokeGrant: (computerId: String, grantId: String) -> Unit = { _, _ -> },
    val removeComputer: (String) -> Unit = {},
    val revokeDevice: (String) -> Unit = {},
    val startOver: () -> Unit = {},
    val notifications: (Boolean) -> Unit = {},
    val taskNotifications: (Boolean) -> Unit = {},
    val notificationSettings: () -> Unit = {},
)

@Composable
fun SignInButtons(state: AppState, store: AppStore) {
    AccountSignInButtons(state.account, "session" in state.busy,
        { apple, hosted -> store.signIn(apple, hosted) }, store::retryAccounts)
}

@Composable
fun AccountScreen(state: AppState, store: AppStore, close: () -> Unit,
    scan: () -> Unit = {}, pair: () -> Unit = scan, inPopover: Boolean = false) {
    val context = LocalContext.current
    LaunchedEffect(state.contextId) { store.refreshCloud(); store.refreshDevices() }
    AccountScreenContent(state, AccountScreenActions(
        signIn = { apple, hosted -> store.signIn(apple, hosted) },
        switchAccount = store::switchAccount,
        retryAccounts = store::retryAccounts,
        signOut = store::signOut,
        refresh = { store.refreshCloud(); store.refreshDevices() },
        clearError = store::clearError,
        cancelAccess = store::cancelAccess,
        setRoute = store::setRoute,
        forgetComputer = store::forgetComputer,
        requestAccess = store::requestAccess,
        loadGrants = store::loadGrants,
        revokeGrant = store::revokeGrant,
        removeComputer = store::removeCloudComputer,
        revokeDevice = store::revokeCloudDevice,
        startOver = store::startOver,
        notifications = store::notifications,
        taskNotifications = store::taskNotifications,
        notificationSettings = {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }), close, scan, pair, inPopover)
}

private data class AccountConfirmation(val title: String, val message: String,
    val actionLabel: String, val action: () -> Unit)

/** The iPhone's account sheet, with computer management one level inside it. */
@Composable internal fun AccountScreenContent(state: AppState, actions: AccountScreenActions,
    close: () -> Unit, scan: () -> Unit = {}, pair: () -> Unit = scan, inPopover: Boolean = false) {
    var computers by rememberSaveable(state.contextId) { mutableStateOf(state.access != null || "access" in state.busy) }
    var addingAccount by rememberSaveable(state.contextId) { mutableStateOf(false) }
    var confirmation by remember(state.contextId) { mutableStateOf<AccountConfirmation?>(null) }
    val account = state.account.accounts.firstOrNull { it.userId == state.account.userId }
    val sessionBusy = "session" in state.busy
    LaunchedEffect(state.account.userId) { addingAccount = false }
    CodyncSheetBackHandler {
        when {
            confirmation != null -> confirmation = null
            computers -> computers = false
            else -> close()
        }
    }
    val confirm: (String, String, String, () -> Unit) -> Unit = { title, message, label, action ->
        confirmation = AccountConfirmation(title, message, label, action)
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box {
            Column(Modifier.fillMaxSize().then(if (inPopover) Modifier else Modifier.safeDrawingPadding())
                .then(if (confirmation != null) Modifier.clearAndSetSemantics {} else Modifier)) {
                AccountHeader(if (computers) "Computers & settings" else "Account", computers,
                    { computers = false }, close, sessionBusy)
                AnimatedContent(computers, Modifier.weight(1f), label = "Account navigation") { detail ->
                    if (detail) ComputerSettings(state, actions, pair, confirm)
                    else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        AccountActionRow("Scan a computer's code", Glyph.QR, scan, chevron = true,
                            modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface))
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(if (state.account.userId == null) 16.dp else 10.dp)) {
                            AccountAvatar(account?.avatar, account?.email, 76.dp)
                            if (state.account.userId != null) {
                                Text(account?.email ?: "Codync account", style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            } else if (state.account.configured) {
                                AccountSignInButtons(state.account, sessionBusy, actions.signIn, actions.retryAccounts, showError = false)
                            } else {
                                Text("QR pairing only in this build", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (state.account.userId != null && (state.account.multiple || state.account.accounts.size > 1)) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically) {
                                state.account.accounts.forEach { item ->
                                    val current = item.userId == state.account.userId
                                    Box(Modifier.size(50.dp).clip(CircleShape)
                                        .then(if (current) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                                        .clickable(enabled = !sessionBusy && !current, role = Role.Button) { actions.switchAccount(item) }
                                        .semantics { contentDescription = item.email; selected = current }
                                        .padding(3.dp), contentAlignment = Alignment.Center) {
                                        AccountAvatar(item.avatar, item.email, 44.dp)
                                    }
                                }
                                if (state.account.multiple) CodyncIconButton("Add account", Glyph.Plus,
                                    { addingAccount = !addingAccount }, Modifier.size(50.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
                                    enabled = !sessionBusy)
                            }
                            AnimatedVisibility(addingAccount, enter = fadeIn(), exit = fadeOut()) {
                                AccountSignInButtons(state.account, sessionBusy, actions.signIn, actions.retryAccounts, showError = false)
                            }
                        }
                        AccountCard {
                            AccountActionRow("Computers", Glyph.Computer, { computers = true }, chevron = true)
                            if (state.account.userId != null) AccountActionRow("Sign out", Glyph.SignOut, {
                                confirm("Sign out of ${account?.email ?: "this account"}?",
                                    "This phone forgets the account's computers. Your bots stay on them.", "Sign out", actions.signOut)
                            }, tint = MaterialTheme.colorScheme.error, enabled = !sessionBusy)
                        }
                        AccountError(state.account.error ?: state.error, actions.clearError, actions.retryAccounts,
                            retry = state.account.error != null)
                    }
                }
            }
            AnimatedContent(confirmation, label = "Account confirmation") { current ->
                if (current != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .35f))
                            .clickable(role = Role.Button) { confirmation = null }
                            .semantics { contentDescription = "Dismiss confirmation" })
                        Column(Modifier.padding(24.dp).widthIn(max = 400.dp).fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surface)
                            .pointerInput(Unit) { detectTapGestures {} }.padding(22.dp).semantics { paneTitle = current.title },
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(current.title, style = MaterialTheme.typography.titleMedium)
                            Text(current.message, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                                TextButton(onClick = { confirmation = null }) { Text("Cancel") }
                                Button(onClick = { confirmation = null; current.action() },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError)) { Text(current.actionLabel) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun AccountHeader(title: String, computers: Boolean, back: () -> Unit, close: () -> Unit, busy: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = if (computers) 8.dp else 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (computers) CodyncIconButton("Back to account", Glyph.Back, back)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (busy) Text("Signing in…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CodyncIconButton("Close account", Glyph.Close, close)
    }
}

@Composable internal fun AccountAvatar(url: String?, email: String?, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer).clearAndSetSemantics {},
        contentAlignment = Alignment.Center) {
        if (!email.isNullOrBlank()) Text(email.take(1).uppercase(), style = MaterialTheme.typography.titleLarge.copy(fontSize = (size.value * .42f).sp))
        else CodyncIcon(Glyph.Person, Modifier.size(size * .42f), MaterialTheme.colorScheme.onSurfaceVariant)
        if (url != null) AsyncImage(url, contentDescription = null, imageLoader = LocalNetworkImages.current,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable private fun AccountSignInButtons(account: AccountState, busy: Boolean,
    signIn: (Boolean, Boolean) -> Unit, retry: () -> Unit, showError: Boolean = true) {
    if (!account.configured) return
    val enabled = account.ready && !busy
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!account.ready) Text("Restoring sign-in…", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { signIn(true, false) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp)) {
            Text("Continue with Apple", style = MaterialTheme.typography.titleMedium)
        }
        Button(onClick = { signIn(false, false) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface)) {
            Text("Continue with Google", style = MaterialTheme.typography.titleMedium)
        }
        TextButton(onClick = { signIn(false, true) }, enabled = enabled) { Text("Other sign-in options") }
        if (showError) AccountError(account.error, {}, retry, retry = true)
    }
}

@Composable private fun AccountCard(title: String? = null, footer: String? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) Text(title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 6.dp), content = content)
        if (footer != null) Text(footer, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun AccountActionRow(title: String, glyph: Glyph?, action: () -> Unit,
    modifier: Modifier = Modifier, chevron: Boolean = false, enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface, detail: String? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = action).padding(vertical = 12.dp, horizontal = if (glyph == Glyph.QR) 16.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (glyph != null) CodyncIcon(glyph, Modifier.width(26.dp), tint.copy(alpha = if (enabled) 1f else .35f))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = tint.copy(alpha = if (enabled) 1f else .35f))
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (chevron) CodyncIcon(Glyph.Forward, Modifier.size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun AccountError(message: String?, clear: () -> Unit, retryAction: () -> Unit, retry: Boolean) {
    if (message == null) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (retry) TextButton(onClick = retryAction) { Text("Retry sign-in") }
        else TextButton(onClick = clear) { Text("Dismiss") }
    }
}

@Composable private fun ComputerSettings(state: AppState, actions: AccountScreenActions, pair: () -> Unit,
    confirm: (String, String, String, () -> Unit) -> Unit) {
    val saved = state.computers.firstOrNull()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)) {
        AccountError(state.error, actions.clearError, actions.retryAccounts, retry = false)
        state.access?.let { ticket ->
            AccountCard("Access request") {
                Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Compare this code with your computer", style = MaterialTheme.typography.titleMedium)
                    Text(ticket.code.chunked(3).joinToString(" "), style = MaterialTheme.typography.headlineLarge)
                    Text("Approve there only if the codes match. Waiting for your computer…", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = actions.cancelAccess) { Text("Cancel request") }
                }
            }
        }
        if ("access" in state.busy && state.access == null) AccountCard {
            Text("Waiting for your computer to prepare the code…", Modifier.padding(vertical = 10.dp))
            TextButton(onClick = actions.cancelAccess) { Text("Cancel request") }
        }
        AccountCard("Computers", if (state.account.userId != null)
            "Computers in your account need your OK on the computer before this phone can use them."
            else "Each bot runs on its own computer. Sign in to see the computers in your account.") {
            if (saved != null) {
                PairedComputerRow(saved, state, actions, confirm)
                state.cloudComputers.firstOrNull { it.computerId == saved.id }?.let { cloud ->
                    CloudComputerActions(cloud, state, actions, confirm)
                }
            }
            state.cloudComputers.filter { it.computerId != saved?.id }.forEach { computer ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ComputerAccountBadge()
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(computer.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text((if (computer.online == true) "Online" else "Offline") + " · In your account",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(enabled = "access" !in state.busy && saved == null,
                        onClick = { actions.requestAccess(computer) }) { Text("Ask for access") }
                }
                CloudComputerActions(computer, state, actions, confirm)
            }
            if (saved == null && state.cloudComputers.isEmpty()) Text(
                if ("cloud" in state.busy) "Loading computers…" else "No computers yet.",
                Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            AccountActionRow("Pair a computer", Glyph.QR, pair)
        }
        if (state.account.userId != null) TextButton(onClick = actions.refresh, enabled = "cloud" !in state.busy && "devices" !in state.busy) {
            CodyncIcon(Glyph.Refresh, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Refresh")
        }
        AccountCard("Notifications", "Get a result summary, a request for input, or a failure notice. Notification previews follow your Android settings.") {
            AccountToggle("Push notifications", state.notifications, actions.notifications)
            AccountToggle("Delegated task status", state.tasks, actions.taskNotifications)
            AccountActionRow("Android notification settings", Glyph.Bell, actions.notificationSettings, chevron = true)
        }
        if (state.devices.isNotEmpty()) AccountCard("Account devices") {
            state.devices.forEach { device ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(device.name, style = MaterialTheme.typography.bodyLarge)
                        Text(if (device.revoked == true) "Revoked" else device.platform ?: "Device",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (device.revoked != true) TextButton(modifier = Modifier.semantics { contentDescription = "Revoke ${device.name} from account" }, onClick = {
                        confirm("Revoke ${device.name}?", "This device loses access to your account.", "Revoke") { actions.revokeDevice(device.deviceId) }
                    }) { Text("Revoke") }
                }
            }
        }
        AccountCard {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("App version", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AccountCard {
            AccountActionRow("Start over", Glyph.Refresh, {
                confirm("Start over?", "Signs out of every account and forgets every computer on this phone, then shows the welcome again. Your computers keep their bots and chats; pair again to use them.",
                    "Start over", actions.startOver)
            }, tint = MaterialTheme.colorScheme.error, enabled = "session" !in state.busy)
        }
    }
}

@Composable private fun ComputerAccountBadge() {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center) { CodyncIcon(Glyph.Computer, Modifier.size(24.dp), MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable private fun PairedComputerRow(computer: Computer, state: AppState, actions: AccountScreenActions,
    confirm: (String, String, String, () -> Unit) -> Unit) {
    var menu by remember(computer.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ComputerAccountBadge()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(computer.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(when (val status = state.connection) {
                is LinkState.Ready -> "${state.bots.count { !it.deleted }} bots · " + if (status.route == HostRoute.Direct) "Wi-Fi/Tailscale" else "Cloudflare"
                is LinkState.Connecting -> "Connecting…"
                is LinkState.ComputerOffline -> "Computer offline"
                else -> "Disconnected"
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            CodyncIconButton("Connection", Glyph.More, { menu = !menu })
            CodyncPopupMenu(menu, { menu = false }) {
                ConnectionRoute.entries.forEach { route ->
                    MenuAction((if (route == computer.route) "✓ " else "") + when (route) {
                        ConnectionRoute.Automatic -> "Auto: Wi-Fi or Tailscale, then Cloudflare"
                        ConnectionRoute.CloudflareFirst -> "Cloudflare, then Wi-Fi or Tailscale"
                        ConnectionRoute.DirectOnly -> "Wi-Fi or Tailscale only, never Cloudflare"
                    }) { menu = false; actions.setRoute(route) }
                }
                MenuAction("Remove") {
                    menu = false
                    confirm("Remove ${computer.name}?", "Its bots and conversations stay on that computer. You can pair again any time.", "Remove", actions.forgetComputer)
                }
            }
        }
    }
}

@Composable private fun CloudComputerActions(computer: CloudComputer, state: AppState, actions: AccountScreenActions,
    confirm: (String, String, String, () -> Unit) -> Unit) {
    var showingGrants by remember(computer.computerId) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { showingGrants = !showingGrants; if (showingGrants) actions.loadGrants(computer.computerId) }) { Text("Authorized devices") }
        CodyncIconButton("Remove ${computer.name} from account", Glyph.Trash, {
            confirm("Remove ${computer.name} from your account?", "All account device grants end. Your computer keeps its bots and chats.", "Remove") {
                actions.removeComputer(computer.computerId)
            }
        })
    }
    AnimatedVisibility(showingGrants) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp)) {
            if ("grants:${computer.computerId}" in state.busy) Text("Loading authorized devices…", style = MaterialTheme.typography.bodySmall)
            else if (state.grants.containsKey(computer.computerId) && state.grants[computer.computerId].isNullOrEmpty())
                Text("No authorized devices.", Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.grants[computer.computerId]?.forEach { grant ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(grant.deviceName ?: "Device", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(modifier = Modifier.semantics { contentDescription = "Revoke ${grant.deviceName ?: "this device"}'s access to ${computer.name}" }, onClick = {
                        confirm("Revoke ${grant.deviceName ?: "this device"}'s access?", "This device loses access to ${computer.name}.", "Revoke") {
                            actions.revokeGrant(computer.computerId, grant.grantId)
                        }
                    }) { Text("Revoke") }
                }
            }
        }
    }
}

@Composable private fun AccountToggle(title: String, checked: Boolean, set: (Boolean) -> Unit) {
    val progress = animateFloatAsState(if (checked) 1f else 0f, label = "Account toggle")
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Switch, onValueChange = set)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Box(Modifier.size(46.dp, 28.dp).clip(CircleShape).background(if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.padding(start = (4f + progress.value * 18f).dp, top = 4.dp).size(20.dp).background(
                if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        }
    }
}
