package com.codync.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codync.android.core.*
import com.codync.android.design.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable internal fun MarketplaceScreen(state: AppState, store: AppStore, close: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var skills by remember { mutableStateOf<List<MarketSkill>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var item by remember { mutableStateOf<MarketConnector?>(null) }
    var installed by remember { mutableStateOf(false) }
    var agentId by rememberSaveable { mutableStateOf<String?>(null) }
    var credentials by rememberSaveable { mutableStateOf(false) }
    var composioKey by remember { mutableStateOf(false) }
    var composioStatus by remember { mutableStateOf<ComposioStatus?>(null) }
    var appSlug by rememberSaveable { mutableStateOf<String?>(null) }
    var appName by rememberSaveable { mutableStateOf("") }
    val agents = state.hello?.get("backends")?.let { WireJson.decodeFromJsonElement<List<AgentBackend>>(it) }.orEmpty()
    val matchingAgents = agents.filter { query.isBlank() || it.name.contains(query, true) || it.description?.contains(query, true) == true }
    var agentPage by remember(query, matchingAgents) { mutableStateOf(CatalogPage(matchingAgents)) }
    val online = state.actualConnection is LinkState.Ready
    val computer = state.computers.firstOrNull()
    LaunchedEffect(computer?.id, online, refresh) {
        if (!online) return@LaunchedEffect
        store.refreshPlugins(); store.refreshBackends()
        try {
            error = null
            val response = store.marketSkills()
            val task = currentCoroutineContext()
            task.ensureActive()
            skills = response
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Couldn't load skills. Refresh the marketplace to try again." }
    }
    if (item != null) { ConnectorInstaller(requireNotNull(item), state, store) { item = null }; return }
    if (credentials) { CredentialsScreen(state, store) { credentials = false }; return }
    if (composioKey) { ComposioKeyForm(composioStatus, online, { composioKey = false }) { value ->
        val response = store.composioKey(value); composioStatus = response
    }; return }
    if (appSlug != null) { AppConnectScreen(ComposioApp(requireNotNull(appSlug), appName), state, store, { appSlug = null }); return }
    if (installed) { InstalledPlugins(state, store) { installed = false }; return }
    val agent = agents.firstOrNull { it.id == agentId }
    if (agent != null) { AgentSetupScreen(agent, state, store) { agentId = null }; return }
    BackHandler(onBack = close)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            CodyncTopBar(title = { Text("Marketplace", style = MaterialTheme.typography.titleMedium) },
                leading = { CodyncIconButton("Back", Glyph.Back, close) },
                trailing = { CodyncIconButton("Refresh marketplace", Glyph.Refresh, { refresh++ }, enabled = online) })
            Column(Modifier.weight(1f).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Everything installs on ${computer?.name ?: "your computer"}.")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!online) Text("Connect to this computer to browse and manage plugins.")
                TextButton(onClick = { installed = true }) { Text("Installed plugins") }
                TextButton(onClick = { credentials = true }) { Text("Credentials") }
                CodyncField(typed, { typed = it }, label = { Text("Search marketplace") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { query = typed.trim() }) { Text("Search") }
                Text("Agents", style = MaterialTheme.typography.titleLarge)
                if (agents.isEmpty()) Text("Refresh to load this computer's agents.")
                agentPage.visible.forEach { backend ->
                    TextButton(enabled = online, onClick = { agentId = backend.id }) { Text("Set up ${backend.name}" + if (backend.signedIn == true) " · Signed in" else if (backend.installed) " · Installed" else "") }
                }
                if (agentPage.hasHidden) TextButton(onClick = { agentPage = agentPage.reveal() }) { Text("Show more agents") }
                key(computer?.id, refresh) {
                    ConnectedAppsBrowser(query, online, state.plugins?.connectors?.filter { it.kind == "composio" }?.map { it.id }?.toSet().orEmpty(),
                        store::composioStatus, store::composioApps, { status -> composioStatus = status; composioKey = true },
                        { app -> appName = app.name; appSlug = app.slug })
                }
                key(computer?.id, refresh) { MarketConnectorBrowser(query, online, state.plugins?.connectors?.mapNotNull { it.registryName }?.toSet().orEmpty(), store::marketConnectors) { selected ->
                    if (selected.installed || state.plugins?.connectors?.any { it.registryName == selected.name } == true) installed = true else item = selected
                } }
                Text("Skills", style = MaterialTheme.typography.titleLarge)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val available = skills?.filter { query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true) }
                if (available == null && online && error == null) Text("Loading skills…")
                else if (available?.isEmpty() == true) Text(if (query.isBlank()) "No skills available." else "No matching skills.")
                available?.forEach { skill -> Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(skill.name, style = MaterialTheme.typography.titleMedium)
                        Text(skill.description, style = MaterialTheme.typography.bodySmall)
                        val added = skill.installed || state.plugins?.skills?.any { it.id == skill.source.lowercase() } == true
                        TextButton(enabled = online && !added && "plugin" !in state.busy, onClick = { store.pluginAction("installSkill", body("source" to skill.source)) }) {
                            Text(if (added) "Added" else "Add ${skill.name}")
                        }
                    }
                } }
                Text("Connectors come from the official MCP Registry, apps through Composio, and skills from Anthropic. Each bot enables them in its profile.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun InstalledPlugins(state: AppState, store: AppStore, close: () -> Unit) {
    var remove by remember { mutableStateOf<Pair<String, String>?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }
    var signingOut by remember { mutableStateOf<InstalledConnector?>(null) }
    var verifying by remember { mutableStateOf<String?>(null) }
    var verification by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val online = state.actualConnection is LinkState.Ready
    val editable = online && verifying == null && "plugin" !in state.busy && "oauth" !in state.busy
    LaunchedEffect(online) { if (online) store.refreshPlugins() }
    if (adding == "import") { ImportConnectorsForm(online, { adding = null }, store::importConnectors); return }
    if (adding == "credentials") { CredentialsScreen(state, store) { adding = null }; return }
    if (adding != null) { CustomPluginForm(requireNotNull(adding), state, store) { adding = null }; return }
    BackHandler(onBack = close)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow { TextButton(onClick = close) { Text("Back") }; Text("Installed plugins", style = MaterialTheme.typography.titleLarge) }
            FlowRow {
                TextButton(enabled = editable, onClick = { adding = "connector" }) { Text("Custom connector") }
                TextButton(enabled = editable, onClick = { adding = "skill" }) { Text("Write a skill") }
                TextButton(enabled = editable, onClick = { adding = "import" }) { Text("Import connectors") }
                TextButton(enabled = editable, onClick = { adding = "credentials" }) { Text("Credentials") }
                TextButton(enabled = online && "plugins" !in state.busy, onClick = store::refreshPlugins) { Text("Refresh installed plugins") }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!online) Text("Reconnect to manage this computer's plugins.")
            val plugins = state.plugins
            if (plugins == null) Text(if ("plugins" in state.busy) "Loading installed plugins…" else "Refresh to load installed plugins.")
            else {
                Text("Connectors", style = MaterialTheme.typography.titleMedium)
                if (plugins.connectors.isEmpty()) Text("No connectors installed.")
                plugins.connectors.forEach { connector ->
                    Text(connector.name)
                    Text(connector.description, style = MaterialTheme.typography.bodySmall)
                    Text(when (connector.auth) { "signedOut" -> "Sign-in needed"; "signedIn" -> "Signed in"; "none", null -> connector.kind; else -> "Authorization status unavailable" })
                    if (connector.keys.isNotEmpty()) Text("Saved fields: " + connector.keys.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                    FlowRow {
                        if (connector.needsSignIn) TextButton(enabled = editable, onClick = { store.connectorSignIn(connector.id) }) { Text("Sign in to ${connector.name}") }
                        if (connector.auth == "signedIn") TextButton(enabled = editable, onClick = { signingOut = connector }) { Text("Sign out of ${connector.name}") }
                        TextButton(enabled = editable, onClick = { scope.launch {
                            verifying = connector.id; verification = null
                            try { store.verifyConnector(connector.id); verification = "${connector.name} verified." }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { verification = "Couldn't verify ${connector.name}. Check its credentials and the computer." }
                            finally { verifying = null }
                        } }) { Text(if (verifying == connector.id) "Verifying…" else "Verify ${connector.name}") }
                        TextButton(enabled = editable, onClick = { remove = "removeConnector" to connector.id }) { Text("Remove ${connector.name}") }
                    }
                }
                verification?.let { Text(it) }
                signingOut?.let { connector ->
                    Text("Sign out of ${connector.name} on this computer? Bots will need it reconnected.")
                    TextButton(enabled = editable, onClick = { store.pluginAction("connectorSignOut", body("id" to connector.id)) { signingOut = null } }) { Text("Confirm sign out of connector") }
                    TextButton(onClick = { signingOut = null }) { Text("Keep connector signed in") }
                }
                Text("Skills", style = MaterialTheme.typography.titleMedium)
                if (plugins.skills.isEmpty()) Text("No skills installed.")
                plugins.skills.forEach { skill ->
                    Text(skill.name)
                    Text(skill.description, style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = editable, onClick = { remove = "removeSkill" to skill.id }) { Text("Remove ${skill.name}") }
                }
                remove?.let { (method, id) ->
                    Text("Remove this plugin from the computer? Bots will no longer be able to use it.")
                    FlowRow {
                        TextButton(enabled = editable, onClick = { store.pluginAction(method, body("id" to id)) { remove = null } }) { Text("Confirm remove plugin") }
                        TextButton(onClick = { remove = null }) { Text("Keep plugin") }
                    }
                }
                TextButton(onClick = store::cancelConnectorSignIn) { Text("Cancel pending connector sign-in") }
            }
        }
    }
}

@Composable private fun CustomPluginForm(kind: String, state: AppState, store: AppStore, close: () -> Unit) {
    var name by remember { mutableStateOf("") }; var body by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }; var remote by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
    val busy = "plugin" in state.busy
    BackHandler(onBack = close)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = close) { Text("Cancel") }
            Text(if (kind == "skill") "Write a skill" else "Custom connector", style = MaterialTheme.typography.titleLarge)
            CodyncField(name, { name = it }, label = { Text("Name") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            if (kind == "connector") {
                FlowRow {
                    TextButton(enabled = !busy, onClick = { remote = false; settings = "" }) { Text(if (remote) "Command" else "✓ Command") }
                    TextButton(enabled = !busy, onClick = { remote = true; settings = "" }) { Text(if (remote) "✓ HTTPS URL" else "HTTPS URL") }
                }
                CodyncField(body, { body = it }, label = { Text(if (remote) "HTTPS URL" else "Command line") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                CodyncField(settings, { settings = it }, label = { Text(if (remote) "Headers JSON (optional)" else "Environment JSON (optional)") }, enabled = !busy, secret = true, modifier = Modifier.fillMaxWidth())
                Text("Keys are saved on the computer only.")
            } else {
                CodyncField(description, { description = it }, label = { Text("Description") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                CodyncField(body, { body = it }, label = { Text("Instructions") }, minLines = 5, enabled = !busy, modifier = Modifier.fillMaxWidth())
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !busy && state.actualConnection is LinkState.Ready && name.isNotBlank() && body.isNotBlank(), onClick = {
                try {
                    val fields = if (settings.isBlank()) emptyMap() else WireJson.decodeFromString<Map<String, String>>(settings)
                    val payload = buildJsonObject {
                        put("name", name)
                        if (kind == "skill") { put("description", description); put("instructions", body) }
                        else { put(if (remote) "url" else "command", body); put(if (remote) "headers" else "env", WireJson.encodeToJsonElement(fields)) }
                    }
                    store.pluginAction(if (kind == "skill") "installSkill" else "installConnector", payload) { settings = ""; close() }
                } catch (_: Exception) { error = "Use a JSON object of field names and text values." }
            }) { Text(if (busy) "Saving…" else "Save plugin") }
        }
    }
}
