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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun ConnectorInstaller(item: MarketConnector, state: AppState, store: AppStore, requestId: String? = null, close: () -> Unit) {
    var selected by remember(item.name) { mutableStateOf(item.options.firstOrNull()?.id) }
    var values by remember(item.name) { mutableStateOf(emptyMap<String, String>()) }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf<InstalledConnector?>(null) }
    val scope = rememberCoroutineScope()
    val option = item.options.firstOrNull { it.id == selected }
    val online = state.actualConnection is LinkState.Ready
    BackHandler(onBack = close)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow {
                TextButton(onClick = close) { Text("Back") }
                Text("Add connector", style = MaterialTheme.typography.titleLarge)
            }
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            item.description?.let { Text(it) }
            Text("Keys are saved on ${state.computers.firstOrNull()?.name ?: "your computer"} only.")
            if (added == null) {
                item.options.forEach { candidate -> TextButton(enabled = !saving, onClick = { selected = candidate.id; values = emptyMap() }) {
                    Text((if (candidate.id == selected) "✓ " else "") + candidate.label)
                } }
                if (option == null) Text("This connector has no supported install option.")
                option?.inputs?.forEach { input ->
                    CodyncField(values[input.name].orEmpty(), { values = values + (input.name to it) }, label = { Text(input.name + if (input.required) " (required)" else " (optional)") },
                        placeholder = { Text(input.defaultValue ?: input.placeholder.orEmpty()) }, secret = input.secret, enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
                    input.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(enabled = online && !saving && option?.complete(values) == true, onClick = {
                    val installOption = option ?: return@Button
                    val submitted = values
                    scope.launch {
                        saving = true; failure = null
                        try {
                            val connector = store.installConnector(item, installOption, submitted)
                            values = emptyMap(); added = connector
                            if (!connector.needsSignIn) {
                                requestId?.let { store.finishConnectionRequest(it, connectorId = connector.id) }
                                close()
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failure = "Couldn't add this connector. Check the required fields and try again." }
                        finally { saving = false }
                    }
                }) { Text(if (saving) "Adding…" else "Add connector") }
            } else {
                val initial = requireNotNull(added)
                val installed = state.plugins?.connectors?.firstOrNull { it.id == initial.id } ?: initial
                Text(when (installed.auth) { "signedOut" -> "Installed · Sign-in needed"; "signedIn" -> "Installed · Signed in"; else -> "Installed" })
                if (installed.needsSignIn) {
                    TextButton(enabled = online && "oauth" !in state.busy, onClick = { store.connectorSignIn(installed.id, requestId) }) { Text("Sign in to ${installed.name}") }
                    TextButton(onClick = { store.cancelConnectorSignIn(); close() }) { Text("Keep installed and sign in later") }
                } else if (requestId == null) TextButton(onClick = close) { Text("Done") }
                else TextButton(enabled = online && !saving, onClick = { scope.launch {
                    saving = true; failure = null
                    try { store.finishConnectionRequest(requestId, connectorId = installed.id); close() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failure = "The connector is installed, but the request wasn't confirmed. Reconnect and try finishing it again." }
                    finally { saving = false }
                } }) { Text("Finish connection request") }
            }
            if (added != null) failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!online) Text("Connect to this computer to add a connector.")
        }
    }
}
