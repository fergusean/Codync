package com.codync.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codync.android.core.AgentModels
import com.codync.android.design.CodyncField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Composable internal fun AgentModelPicker(computerId: String?, backend: String, selection: String?, online: Boolean,
    choose: (String?) -> Unit, load: suspend (String) -> AgentModels) {
    var catalog by remember(computerId, backend) { mutableStateOf<AgentModels?>(null) }
    var loading by remember(computerId, backend) { mutableStateOf(false) }
    var failure by remember(computerId, backend) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(computerId, backend, online, retry) {
        failure = null; loading = false
        if (backend == "custom") return@LaunchedEffect
        if (!online) { failure = "Connect to this computer to load its models. You can still enter a model ID."; return@LaunchedEffect }
        loading = true
        try {
            val response = load(backend)
            val task = currentCoroutineContext()
            task.ensureActive()
            catalog = response
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = "Couldn't load models: ${error.message ?: "Try again."}" }
        finally { loading = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Model", style = MaterialTheme.typography.titleMedium)
        if (backend != "custom") TextButton(enabled = online && !loading, onClick = { retry++ }) {
            Text(if (loading) "Loading available models…" else if (failure != null) "Retry model list" else "Refresh model list")
        }
        val current = catalog
        if (backend == "custom" || current?.models.isNullOrEmpty()) {
            CodyncField(selection.orEmpty(), { choose(it.takeIf(String::isNotBlank)) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Model ID (blank uses agent default)") })
        } else {
            for (option in current.options(selection)) TextButton(onClick = { choose(option.id) }) {
                Column(Modifier.fillMaxWidth()) {
                    Text((if (current.normalize(selection) == option.id) "✓ " else "") + option.name)
                    option.description?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (!loading && failure == null && backend != "custom" && current?.models?.isEmpty() == true)
            Text("This agent doesn't advertise a model list. Leave Default or enter a model ID.", style = MaterialTheme.typography.bodySmall)
        Text("Changing the model starts a new agent session. Chat history is kept.", style = MaterialTheme.typography.bodySmall)
    }
}
