package com.codync.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codync.android.core.*
import com.codync.android.design.CodyncField
import kotlinx.coroutines.*

@Composable internal fun CredentialsScreen(state: AppState, store: AppStore, close: () -> Unit) {
    var status by remember { mutableStateOf<CredentialStatus?>(null) }
    var logins by remember { mutableStateOf<List<SavedLogin>?>(null) }
    var selected by remember { mutableStateOf<InstalledConnector?>(null) }
    var token by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<SavedLogin?>(null) }
    var disconnecting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }; var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }; var error by remember { mutableStateOf<String?>(null) }
    val online = state.actualConnection is LinkState.Ready
    val scope = rememberCoroutineScope()
    LaunchedEffect(online, refresh) {
        if (!online) return@LaunchedEffect
        loading = true; error = null; store.refreshPlugins()
        try {
            val provider = store.credentialStatus()
            val rows = store.savedLogins()
            val task = currentCoroutineContext(); task.ensureActive()
            status = provider; logins = rows
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Couldn't load credentials. Unlock the computer's credential store and refresh." }
        finally { loading = false }
    }
    fun saveToken(value: String) { scope.launch {
        busy = true; error = null
        try {
            val response = store.onePasswordToken(value)
            val task = currentCoroutineContext(); task.ensureActive()
            status = response; token = ""; disconnecting = false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "1Password wasn't confirmed. Check the computer's CLI and shared-vault access before trying again." }
        finally { busy = false }
    } }
    if (selected != null) {
        ConnectorCredentialsForm(requireNotNull(selected), online, { selected = null }) { fields ->
            store.updateConnectorCredentials(requireNotNull(selected), fields)
        }; return
    }
    BackHandler(onBack = close)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow { TextButton(onClick = close) { Text("Back") }; Text("Credentials", style = MaterialTheme.typography.titleLarge) }
            TextButton(enabled = online && !loading && !busy, onClick = { refresh++ }) { Text("Refresh credentials") }
            Text("Storage", style = MaterialTheme.typography.titleMedium)
            Text(status?.provider ?: if (loading) "Loading credential storage…" else "Storage unavailable")
            Text("Credentials are encrypted on ${state.computers.firstOrNull()?.name ?: "the connected computer"}. Their values stay hidden.")
            Text("Saved connections", style = MaterialTheme.typography.titleMedium)
            val connectors = state.plugins?.connectors?.filter { it.keys.isNotEmpty() }.orEmpty()
            if (connectors.isEmpty() && state.plugins != null) Text("No saved connector fields.")
            connectors.forEach { connector -> TextButton(enabled = online && !busy, onClick = { selected = connector }) { Text("Edit credentials for ${connector.name}") } }
            Text("Sign-ins", style = MaterialTheme.typography.titleMedium)
            Text("Bots type these into the matching website or app and never see the password. You can use an op:// reference.")
            if (logins == null) Text(if (loading) "Loading sign-ins…" else "Refresh to load saved sign-ins.")
            if (logins?.isEmpty() == true) Text("No sign-ins saved.")
            logins.orEmpty().forEach { login ->
                Text(login.site, style = MaterialTheme.typography.titleSmall)
                if (login.username.isNotEmpty()) Text(login.username, style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = online && !busy, onClick = { removing = login }) { Text("Remove sign-in for ${login.site}") }
            }
            removing?.let { login ->
                Text("Remove the saved sign-in for ${login.site} from this computer?")
                TextButton(enabled = online && !busy, onClick = { scope.launch {
                    busy = true; error = null
                    try { store.removeLogin(login.id); val task = currentCoroutineContext(); task.ensureActive(); removing = null; refresh++ }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "Removal wasn't confirmed. Refresh sign-ins before trying again." }
                    finally { busy = false }
                } }) { Text("Confirm remove sign-in") }
                TextButton(onClick = { removing = null }) { Text("Keep sign-in") }
            }
            LoginCredentialsForm(online && !busy, store::saveLogin, { busy = it }) { refresh++ }
            Text("1Password", style = MaterialTheme.typography.titleMedium)
            Text(if (status?.onePasswordConnected == true) "Connected to a shared vault" else "Connect a shared vault")
            Text("Install the 1Password CLI on the computer. Use a service account with read access only to a dedicated shared vault, then use op://vault/item/field in credential fields.")
            CodyncField(token, { token = it }, label = { Text("1Password service account token") }, secret = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            Button(enabled = online && !busy && token.isNotEmpty(), onClick = { saveToken(token) }) { Text(if (busy) "Saving…" else "Connect 1Password") }
            if (status?.onePasswordConnected == true) {
                TextButton(enabled = online && !busy, onClick = { disconnecting = true }) { Text("Disconnect 1Password") }
                if (disconnecting) {
                    Text("Disconnect the shared vault? Connections using op:// references will need access restored.")
                    TextButton(enabled = !busy, onClick = { saveToken("") }) { Text("Confirm disconnect 1Password") }
                    TextButton(onClick = { disconnecting = false }) { Text("Keep 1Password") }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!online) Text("Reconnect to manage credentials on this computer.")
        }
    }
}

@Composable internal fun LoginCredentialsForm(online: Boolean, save: suspend (String, String, String) -> SavedLogin,
    busyChange: (Boolean) -> Unit = {}, saved: () -> Unit) {
    var site by remember { mutableStateOf("") }; var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    CodyncField(site, { site = it }, label = { Text("Website or app") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
    CodyncField(username, { username = it }, label = { Text("Username or email") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
    CodyncField(password, { password = it }, label = { Text("Password or op:// reference") }, secret = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
    Button(enabled = online && !busy && site.isNotBlank() && password.isNotEmpty() && password.toByteArray().size <= 4096 && username.toByteArray().size <= 512,
        onClick = { scope.launch {
            busy = true; busyChange(true); error = null
            try {
                save(site, username, password)
                val task = currentCoroutineContext(); task.ensureActive()
                site = ""; username = ""; password = ""; saved()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Sign-in wasn't confirmed. Refresh saved sign-ins before trying again." }
            finally { busy = false; busyChange(false) }
        } }) { Text(if (busy) "Saving sign-in…" else "Save sign-in") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable internal fun ConnectorCredentialsForm(connector: InstalledConnector, online: Boolean,
    close: () -> Unit, save: suspend (Map<String, String>) -> Unit) {
    var values by remember(connector.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = close)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = close) { Text("Back") }
            Text(connector.name, style = MaterialTheme.typography.titleLarge)
            Text("Enter only the values you want to replace. Blank fields keep saved values on the computer. You can use an op:// reference.")
            connector.keys.forEach { field -> CodyncField(values[field].orEmpty(), { values = values + (field to it) },
                label = { Text(field) }, placeholder = { Text("Saved (type to replace)") }, secret = true, enabled = !busy, modifier = Modifier.fillMaxWidth()) }
            Button(enabled = online && !busy && values.values.any(String::isNotEmpty), onClick = { scope.launch {
                busy = true; error = null
                try { save(credentialReplacements(connector, values)); val task = currentCoroutineContext(); task.ensureActive(); values = emptyMap(); close() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "The updated credentials weren't verified. Check the connection before trying again." }
                finally { busy = false }
            } }) { Text(if (busy) "Verifying…" else "Save credentials") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!online) Text("Reconnect to update credentials.")
        }
    }
}

@Composable internal fun ImportConnectorsForm(online: Boolean, close: () -> Unit,
    importConfig: suspend (String) -> List<InstalledConnector>) {
    var config by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = close)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = close) { Text("Back") }
            Text("Import connectors", style = MaterialTheme.typography.titleLarge)
            Text("Paste an MCP config containing mcpServers, servers, or one server entry. Commands and credentials are saved on the computer.")
            CodyncField(config, { config = it }, label = { Text("MCP config JSON") }, secret = true, minLines = 5, enabled = !busy, modifier = Modifier.fillMaxWidth())
            Button(enabled = online && !busy && config.isNotBlank(), onClick = { scope.launch {
                busy = true; error = null
                try { importConfig(config); val task = currentCoroutineContext(); task.ensureActive(); config = ""; close() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "Import wasn't confirmed. Check installed plugins before trying again." }
                finally { busy = false }
            } }) { Text(if (busy) "Importing…" else "Import MCP config") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
