package com.codync.android

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.codync.android.core.*
import com.codync.android.design.CodyncField
import java.util.UUID
import kotlinx.coroutines.*

@Composable internal fun ConnectedAppsBrowser(query: String, online: Boolean, installed: Set<String>,
    status: suspend () -> ComposioStatus, load: suspend (String, String?) -> ComposioApps,
    setup: (ComposioStatus?) -> Unit, connect: (ComposioApp) -> Unit) {
    var configured by remember { mutableStateOf<ComposioStatus?>(null) }
    var page by remember { mutableStateOf(CatalogPage<ComposioApp>()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var ticket by remember { mutableStateOf(UUID.randomUUID()) }
    var more by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(query, online, retry) {
        more?.cancel(); more = null
        val request = UUID.randomUUID(); ticket = request
        page = CatalogPage(); cursor = null; error = null; loading = false
        if (!online) return@LaunchedEffect
        loading = true
        try {
            val answer = status()
            val response = if (answer.configured) load(query, null) else ComposioApps(emptyList())
            val task = currentCoroutineContext(); task.ensureActive()
            if (ticket == request) {
                configured = answer
                page = CatalogPage<ComposioApp>().append(response.items) { it.slug }
                cursor = response.nextCursor?.takeIf(String::isNotBlank)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (ticket == request) error = "Couldn't load connected apps. Retry when the computer is available." }
        finally { if (ticket == request) loading = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Apps", style = MaterialTheme.typography.titleLarge)
        if (!online) Text("Connect to this computer to browse apps.")
        if (loading && page.items.isEmpty()) Text("Loading apps…")
        if (!loading && online && error == null && configured?.configured == false) {
            Text("Composio connects services such as Gmail, Slack and GitHub. Its API key stays on your computer.")
            TextButton(onClick = { setup(configured) }) { Text("Set up Composio") }
        }
        page.visible.forEach { app ->
            val added = app.connected || "composio-${app.slug}" in installed
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(app.name, style = MaterialTheme.typography.titleMedium)
                    app.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    val connection = app.connection
                    if (connection != null && !added) Text("Connection: ${connection.status}", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = online && !added, onClick = { connect(app) }) { Text(if (added) "Connected to ${app.name}" else "Connect ${app.name}") }
                }
            }
        }
        if (online && !loading && error == null && configured?.configured == true && page.items.isEmpty())
            Text(if (query.isBlank()) "No apps available." else "No apps match “$query”.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (error != null) TextButton(enabled = online && !loading, onClick = { retry++ }) { Text("Retry apps") }
        if (page.hasHidden || cursor != null) TextButton(enabled = online && !loading, onClick = {
            if (page.hasHidden) page = page.reveal()
            else {
                val next = cursor ?: return@TextButton
                val request = ticket
                more = scope.launch {
                    loading = true; error = null
                    try {
                        val response = load(query, next)
                        val task = currentCoroutineContext(); task.ensureActive()
                        check(response.nextCursor != next)
                        if (ticket == request) {
                            page = page.append(response.items) { it.slug }.reveal()
                            cursor = response.nextCursor?.takeIf(String::isNotBlank)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (ticket == request) error = "Couldn't load more apps. Retry this page." }
                    finally { if (ticket == request) loading = false }
                }
            }
        }) { Text(if (loading) "Loading more apps…" else "Load more apps") }
        if (online && configured?.configured == true) TextButton(onClick = { setup(configured) }) { Text("Composio settings") }
    }
}

@Composable internal fun ComposioKeyForm(status: ComposioStatus?, online: Boolean, close: () -> Unit,
    save: suspend (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }; var removing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun submit(value: String) { scope.launch {
        busy = true; error = null
        try { save(value); val task = currentCoroutineContext(); task.ensureActive(); key = ""; close() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "The key wasn't confirmed. Check the computer's Composio settings before trying again." }
        finally { busy = false }
    } }
    BackHandler(onBack = close)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = close) { Text("Back") }
            Text("Composio", style = MaterialTheme.typography.titleLarge)
            Text("Composio signs you into apps and runs their tools for your bots. Its key stays on the connected computer.")
            CodyncField(key, { key = it }, label = { Text("Composio API key") }, secret = true, enabled = !busy,
                placeholder = { Text(if (status?.configured == true) "Saved (type to replace)" else "API key") }, modifier = Modifier.fillMaxWidth())
            OpenConnectionLink("Get a key from Composio", status?.keyUrl ?: "https://platform.composio.dev")
            Button(enabled = online && !busy && key.isNotBlank(), onClick = { submit(key) }) { Text(if (busy) "Saving…" else "Save Composio key") }
            if (status?.configured == true) {
                TextButton(enabled = online && !busy, onClick = { removing = true }) { Text("Remove Composio key") }
                if (removing) {
                    Text("Bots lose connected apps until you add a key again.")
                    TextButton(enabled = !busy, onClick = { submit("") }) { Text("Confirm remove Composio key") }
                    TextButton(onClick = { removing = false }) { Text("Keep Composio key") }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!online) Text("Reconnect to update the key.")
        }
    }
}

@Composable internal fun OpenConnectionLink(label: String, url: String) {
    val context = LocalContext.current
    var error by remember(url) { mutableStateOf(false) }
    val valid = remember(url) { runCatching { externalHttps(url) }.isSuccess }
    if (valid) TextButton(onClick = {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { error = true }
    }) { Text(label) }
    if (error) Text("Couldn't open the browser.", color = MaterialTheme.colorScheme.error)
}

@Composable internal fun AppConnectScreen(app: ComposioApp, state: AppState, store: AppStore,
    close: () -> Unit, requestId: String? = null) {
    val computerId = state.computers.firstOrNull()?.id.orEmpty()
    val owner = rememberSaveable { UUID.randomUUID().toString() }
    val savedComputer = rememberSaveable { computerId }
    val savedRuntime = rememberSaveable { store.activityOwnerId }
    var connectionId by rememberSaveable { mutableStateOf<String?>(null) }
    if (savedComputer != computerId || savedRuntime != store.activityOwnerId && connectionId == null) {
        BackHandler(onBack = close)
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = close) { Text("Back") }
                Text("Sign-in was interrupted. Check installed apps before starting another attempt.")
                TextButton(enabled = state.actualConnection is LinkState.Ready, onClick = store::refreshPlugins) { Text("Refresh installed apps") }
            }
        }
        return
    }
    val session = remember(owner) { store.appConnection(owner, app.slug, connectionId) }
    val status by session.state.collectAsState()
    var opened by rememberSaveable { mutableStateOf(false) }
    var finished by rememberSaveable { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    val activity = LocalActivity.current; val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val leave = { store.closeAppConnection(owner); close() }
    DisposableEffect(session) { onDispose { if (activity?.isChangingConfigurations != true) store.closeAppConnection(owner) } }
    LaunchedEffect(status.connectionId) { status.connectionId?.let { connectionId = it } }
    LaunchedEffect(status.plan?.url) {
        val url = status.plan?.url
        if (opened || url == null || savedComputer != computerId) return@LaunchedEffect
        opened = true
        try { externalHttps(url); context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { error = "Couldn't open the sign-in browser. Use the link below." }
    }
    fun finish() { scope.launch {
        finishing = true; error = null
        try {
            if (requestId != null) store.finishConnectionRequest(requestId, connectorId = "composio-${app.slug}")
            val task = currentCoroutineContext(); task.ensureActive(); finished = true; store.refreshPlugins()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "The app is connected, but the request wasn't confirmed. Finish the request when the computer is available." }
        finally { finishing = false }
    } }
    LaunchedEffect(status.connected) { if (status.connected && !finished && !finishing) finish() }
    BackHandler(onBack = leave)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = leave) { Text("Back") }
            Text("Connect ${app.name}", style = MaterialTheme.typography.titleLarge)
            app.description?.let { Text(it) }
            if (savedComputer != computerId) Text("This sign-in belongs to another computer. Go back and check its installed apps.")
            else if (status.connected) {
                Text("Connected", style = MaterialTheme.typography.titleMedium)
                Text("Enable ${app.name} for a bot under Connectors in its settings.")
                if (requestId != null && !finished) TextButton(enabled = !finishing, onClick = ::finish) { Text("Finish connection request") }
                TextButton(enabled = !finishing, onClick = leave) { Text("Done") }
            } else if (status.plan?.status == "needsFields") {
                AppConnectionFields(requireNotNull(status.plan), state.actualConnection is LinkState.Ready, status.saving, session::submit)
            } else if (status.loading) Text("Asking Composio…")
            else {
                Text("Waiting for you to finish signing in…")
                status.plan?.url?.let { OpenConnectionLink("Open the sign-in page", it) }
                if (status.plan?.url == null) Text("Return to the sign-in page already open in your browser. This checks the original connection.")
                status.status?.let { Text("Connection: $it", style = MaterialTheme.typography.bodySmall) }
            }
            status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.actualConnection !is LinkState.Ready) Text("Reconnect to check sign-in on this computer.")
        }
    }
}

@Composable internal fun AppConnectionFields(plan: ComposioConnect, online: Boolean, busy: Boolean,
    submit: (Map<String, String>) -> Unit) {
    var values by remember(plan) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val fields = remember(plan, values) { runCatching { plan.submitted(values) }.getOrNull() }
    Text("These fields go to Composio, which keeps them for this app.")
    plan.fields.orEmpty().forEach { field ->
        CodyncField(values[field.name].orEmpty(), { values = values + (field.name to it) },
            label = { Text(field.label + if (field.required) " (required)" else " (optional)") }, secret = field.secret,
            enabled = !busy, modifier = Modifier.fillMaxWidth())
        field.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    Button(enabled = online && !busy && fields != null, onClick = { fields?.let(submit) }) {
        Text(if (busy) "Connecting…" else "Connect app")
    }
}
