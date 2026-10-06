package com.codync.android

import android.content.Intent
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
import kotlinx.serialization.json.decodeFromJsonElement

@Composable internal fun AgentSetupScreen(initial: AgentBackend, state: AppState, store: AppStore, close: () -> Unit) {
    val backend = state.hello?.get("backends")?.let { WireJson.decodeFromJsonElement<List<AgentBackend>>(it) }
        ?.firstOrNull { it.id == initial.id } ?: initial
    var auth by remember { mutableStateOf<AgentAuth?>(null) }
    var checking by remember { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }; var refresh by remember { mutableIntStateOf(0) }
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    var methodId by rememberSaveable { mutableStateOf<String?>(null) }
    var owner by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    val scope = rememberCoroutineScope()
    val online = state.actualConnection is LinkState.Ready
    val signedIn = auth?.signedIn ?: backend.signedIn
    LaunchedEffect(backend.id, refresh, online, route) {
        if (!online || route != null || refresh == 0 && backend.signedIn == true) return@LaunchedEffect
        checking = true
        try { val response = store.agentAuth(backend.id); val task = currentCoroutineContext(); task.ensureActive(); auth = response; error = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Couldn't check this agent. Check again when the computer is connected." }
        finally { checking = false }
    }
    fun back() { route = null; methodId = null; refresh++ }
    fun terminal(step: String, method: String?) { owner = UUID.randomUUID().toString(); methodId = method; route = step }
    if (route in listOf("install", "login")) {
        SetupTerminalScreen(backend, requireNotNull(route), methodId, owner, state, store, ::back); return
    }
    if (route == "keys") {
        val method = auth?.methods?.firstOrNull { it.id == methodId }
        if (method != null) {
            AgentKeysForm(backend.name, method, auth?.savedEnv.orEmpty(), online, ::back) { values ->
                val response = store.setAgentEnv(backend.id, values); auth = response
            }; return
        }
        // Keys and discovery are memory-only. A cold launch checks the agent again.
        LaunchedEffect(Unit) { route = null; refresh++ }
        return
    }
    BackHandler(onBack = close)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = close) { Text("Back") }
            Text(backend.name, style = MaterialTheme.typography.headlineMedium)
            backend.description?.takeIf(String::isNotBlank)?.let { Text(it) }
            if (!online) Text("Reconnect to this computer to set up this agent.")
            if (backend.curated) {
                Text("Install", style = MaterialTheme.typography.titleLarge)
                Text(if (backend.installed) "Installed on ${state.computers.firstOrNull()?.name ?: "your computer"}." else backend.installHint)
                if (!backend.installed && backend.canInstall) Button(enabled = online && !checking && !busy,
                    onClick = { terminal("install", null) }) { Text("Install ${backend.name}") }
            }
            Text("Sign in", style = MaterialTheme.typography.titleLarge)
            Text(when { checking -> "Checking with ${backend.name}… The first check can download it."
                signedIn == true -> "Signed in."; signedIn == false -> "Not signed in yet."
                else -> "Couldn't tell. Skip this if you've already signed in on the computer." })
            TextButton(enabled = online && !checking && !busy, onClick = { refresh++ }) { Text("Check again") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (signedIn != true) {
                auth?.detail?.let { Text(it) }
                if (!checking) {
                    if (auth?.login == true) Button(enabled = online && !busy, onClick = { terminal("login", null) }) { Text("Sign in in terminal") }
                    auth?.methods.orEmpty().forEach { method ->
                        method.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Button(enabled = online && !busy, onClick = {
                            when (method.kind) {
                                "terminal" -> terminal("login", method.id)
                                "envVar" -> { methodId = method.id; route = "keys" }
                                "agent", null -> scope.launch {
                                    busy = true; error = null
                                    try { val response = store.agentAuthenticate(backend.id, method.id); val task = currentCoroutineContext(); task.ensureActive(); auth = response }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { error = "Sign-in didn't finish. Check the agent before trying again." }
                                    finally { busy = false }
                                }
                                else -> error = "This sign-in method needs a newer app. Sign in on the computer for now."
                            }
                        }) { Text(method.name) }
                    }
                    if (auth?.methods?.any { it.kind == "agent" || it.kind == null } == true)
                        Text("Browser sign-ins open on the computer itself. Finish there, then check again.")
                    if (busy) Text("Finish signing in in the browser on the computer…")
                }
            }
        }
    }
}

@Composable internal fun AgentKeysForm(backendName: String, method: AgentAuthMethod, saved: List<String>, online: Boolean,
    close: () -> Unit, save: suspend (Map<String, String>) -> Unit) {
    var values by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }; var removing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    fun submit(remove: Boolean) { scope.launch {
        busy = true; error = null
        try {
            val fields = if (remove) method.vars.associate { it.name to "" } else values.filterValues(String::isNotBlank)
            save(fields); val task = currentCoroutineContext(); task.ensureActive(); values = emptyMap(); close()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Couldn't update these keys. Check again before retrying." }
        finally { busy = false }
    } }
    BackHandler(onBack = close)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = close) { Text("Back") }
            Text(method.name, style = MaterialTheme.typography.titleLarge)
            method.vars.forEach { field ->
                CodyncField(values[field.name].orEmpty(), { value -> values = values + (field.name to value) },
                    label = { Text(field.label + if (field.optional) " (optional)" else "") }, secret = field.secret,
                    placeholder = { Text(if (field.name in saved) "Saved (type to replace)" else field.name) },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy)
            }
            Text("Saved on the computer only and given to $backendName when it starts.")
            method.link?.let { link -> val uri = android.net.Uri.parse(link)
                if (uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null)
                    TextButton(onClick = { try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) { error = "Couldn't open the key page." } }) { Text("Get a key") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = online && !busy && method.complete(values, saved), onClick = { submit(false) }) { Text(if (busy) "Saving…" else "Save keys") }
            if (saved.any { it in method.vars.map(AgentAuthVar::name) }) {
                TextButton(enabled = online && !busy, onClick = { removing = true }) { Text("Remove saved keys") }
                if (removing) {
                    Text("Remove this method's saved keys from the computer?")
                    TextButton(enabled = !busy, onClick = { submit(true) }) { Text("Confirm remove keys") }
                    TextButton(enabled = !busy, onClick = { removing = false }) { Text("Keep keys") }
                }
            }
        }
    }
}

@Composable private fun SetupTerminalScreen(backend: AgentBackend, step: String, method: String?, owner: String,
    state: AppState, store: AppStore, close: () -> Unit) {
    val session = remember(owner) { store.setupTerminal(owner, backend.id, step, method) }
    val status by session.state.collectAsState()
    var renderer by remember { mutableStateOf<TerminalRenderer?>(null) }
    var renderError by remember { mutableStateOf(false) }
    val activity = LocalActivity.current; val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(android.content.ClipboardManager::class.java) }
    val leave = { store.closeSetupTerminal(owner); close() }
    DisposableEffect(session) { onDispose {
        renderer?.let(session::detach)
        if (activity?.isChangingConfigurations != true) store.closeSetupTerminal(owner)
    } }
    BackHandler(onBack = leave)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = leave) { Text("Back") }
                Text(if (step == "install") "Install ${backend.name}" else "Sign in to ${backend.name}", Modifier.weight(1f).padding(top = 12.dp))
                TextButton(onClick = { renderer?.copySelection() }) { Text("Copy selection") }
            }
            if (renderError) Spacer(Modifier.weight(1f)) else TerminalSurface(Modifier.fillMaxWidth().weight(1f), { target -> renderer = target; session.attach(target) },
                session::type, session::resized, { renderError = true; store.closeSetupTerminal(owner); renderer = null },
                openLink = { uri -> try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {
                    renderError = true; store.closeSetupTerminal(owner); renderer = null
                } },
                copy = { text -> val clip = android.content.ClipData.newPlainText("Agent setup terminal", text)
                    if (android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
                    clipboard.setPrimaryClip(clip) }, created = { renderer = it })
            FlowRow(Modifier.padding(horizontal = 8.dp)) {
                TextButton(enabled = status.connected && status.exitCode == null && status.error == null, onClick = { session.type(byteArrayOf(3)) }) { Text("Ctrl+C") }
                TextButton(enabled = status.connected && status.exitCode == null && status.error == null, onClick = { session.type(byteArrayOf(9)) }) { Text("Tab") }
                TextButton(enabled = status.connected && status.exitCode == null && status.error == null, onClick = { session.type(byteArrayOf(27)) }) { Text("Esc") }
                TextButton(onClick = { renderer?.focus(showKeyboard = true) }) { Text("Keyboard") }
                if (status.exitCode != null) TextButton(onClick = leave) { Text("Done") }
            }
            Text(when {
                renderError -> "The terminal couldn't load. Update Android System WebView, then return to the agent to try again."
                status.error != null -> requireNotNull(status.error)
                status.starting -> "Starting setup on the computer…"
                status.exitCode == 0 -> "Finished."
                status.exitCode != null -> "Ended with an error (${status.exitCode})."
                !status.connected -> "Reconnecting to the setup terminal…"
                else -> "Running on ${state.computers.firstOrNull()?.name ?: "the computer"}. Links open on this device."
            }, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}
