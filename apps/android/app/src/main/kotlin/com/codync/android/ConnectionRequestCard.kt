package com.codync.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.codync.android.core.*
import com.codync.android.design.CodyncField
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable internal fun ConnectionRequestCard(entry: Entry, state: AppState, store: AppStore,
    openApp: (String, ComposioApp) -> Unit, install: (String, MarketConnector) -> Unit) {
    val request = entry.data["connectionRequest"]?.let { runCatching { WireJson.decodeFromJsonElement<ConnectionRequest>(it) }.getOrNull() }
    if (request == null || request.botId != entry.botId && state.bots.none { it.id == request.botId }) {
        Text("This connection request needs a newer app."); return
    }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.testTag("connection:${entry.id}")) {
        ConnectionRequestFields(request, entry.text, state.actualConnection is LinkState.Ready, busy || "connection:${entry.id}" in state.busy,
            submit = { value, username -> scope.launch {
                busy = true; error = null
                try {
                    when (request.kind) {
                        "login", "secret" -> store.completeConnectionRequest(entry.id, value = value, username = username)
                        else -> {
                            val connectors = store.installedConnectors()
                            val installed = connectors.firstOrNull {
                                it.id == request.connectorId || request.registryName != null && it.registryName == request.registryName
                            }
                            if (installed != null) {
                                if (installed.needsSignIn) store.connectorSignIn(installed.id, entry.id)
                                else store.completeConnectionRequest(entry.id, connectorId = installed.id)
                            } else if (request.kind == "app") {
                                openApp(entry.id, ComposioApp(requireNotNull(request.toolkit), request.title))
                            } else {
                                val item = store.connectorInfo(requireNotNull(request.registryName))
                                val task = currentCoroutineContext(); task.ensureActive()
                                install(entry.id, item)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "Connection wasn't confirmed. Check its setup and refresh before trying again." }
                finally { busy = false }
            } }, cancel = { error = null; store.completeConnectionRequest(entry.id, cancel = true) })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if ("oauth" in state.busy) Text("Finish signing in in the browser, then return here. Cancel stops waiting and keeps the connector installed.")
    }
}

@Composable internal fun ConnectionRequestFields(request: ConnectionRequest, reason: String, online: Boolean,
    busy: Boolean, submit: (String?, String?) -> Unit, cancel: () -> Unit) {
    var value by remember(request) { mutableStateOf("") }
    var username by remember(request) { mutableStateOf("") }
    Text(request.title, style = MaterialTheme.typography.titleMedium)
    if (reason.isNotBlank()) Text(reason)
    if (request.status == "pending") {
        when (request.kind) {
            "login" -> {
                CodyncField(username, { username = it }, label = { Text("Username or email") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                CodyncField(value, { value = it }, label = { Text("Password or op:// reference") }, secret = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Text("Saved securely on the computer. The bot types it into the sign-in page but never sees it.")
            }
            "secret" -> {
                Text("${request.field ?: "Credential"} · ${request.location.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                CodyncField(value, { value = it }, label = { Text("Credential or op:// reference") }, secret = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Text("Saved securely on the computer. Never added to the conversation.")
            }
        }
        if (!request.canConnect) Text("This request needs a newer app or complete setup information.")
        FlowRow {
            Button(enabled = online && !busy && request.canConnect && (request.kind !in listOf("login", "secret") || value.isNotEmpty()),
                onClick = { submit(value.takeIf { request.kind in listOf("login", "secret") }, username.takeIf { request.kind == "login" }) }) {
                Text(if (busy) "Connecting…" else when (request.kind) { "login" -> "Save login"; "secret" -> "Save and connect"; else -> "Connect" })
            }
            TextButton(enabled = online && !busy, onClick = { value = ""; username = ""; cancel() }) { Text("Cancel connection request") }
        }
        if (!online) Text("Reconnect to complete this request.")
    } else Text(when (request.status) { "ready" -> if (request.kind == "login") "Saved" else "Connected"; "cancelled" -> "Cancelled"; else -> "Request status: ${request.status}" })
}
