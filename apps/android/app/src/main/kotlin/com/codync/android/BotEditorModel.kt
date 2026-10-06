package com.codync.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codync.android.core.AgentModels
import com.codync.android.design.CodyncIconButton
import com.codync.android.design.Glyph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Compact model row; discovery remains scoped to the computer and selected agent. */
@Composable internal fun BotEditorModel(computerId: String?, backend: String, selection: String?, online: Boolean,
    choose: (String?) -> Unit, load: suspend (String) -> AgentModels) {
    var catalog by remember(computerId, backend) { mutableStateOf<AgentModels?>(null) }
    var loading by remember(computerId, backend) { mutableStateOf(false) }
    var failure by remember(computerId, backend) { mutableStateOf<String?>(null) }
    var retry by remember(computerId, backend) { mutableIntStateOf(0) }
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
        BotEditorOptionRow("Model") {
            if (backend != "custom") {
                if (loading) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else CodyncIconButton(if (failure == null) "Refresh model list" else "Retry model list",
                    Glyph.Refresh, { retry++ }, enabled = online)
            }
            val current = catalog
            if (backend == "custom" || !loading && current?.models.isNullOrEmpty()) {
                BotEditorModelField(selection.orEmpty()) { choose(it.takeIf(String::isNotBlank)) }
            } else {
                val selected = current?.normalize(selection).orEmpty()
                val options = current?.options(selection)?.map { it.id.orEmpty() to it.name } ?: listOf("" to "Default")
                BotEditorChoice("Model", selected, options, enabled = !loading) { choose(it.takeIf(String::isNotBlank)) }
            }
        }
        if (!loading) {
            failure?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (failure == null && backend != "custom" && catalog?.models?.isEmpty() == true)
                Text("This agent doesn't advertise a model list. Leave Default or enter a model ID.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Changing the model starts a new agent session. Chat history is kept.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
