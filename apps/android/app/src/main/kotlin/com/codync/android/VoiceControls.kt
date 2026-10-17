package com.codync.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.codync.android.core.*
import com.codync.android.design.*

/** Starts a call with `botId`, asking for the microphone first; `denied` reports a refusal. */
@Composable internal fun rememberStartVoice(botId: String, state: AppState, store: AppStore, denied: () -> Unit): () -> Unit {
    val context = LocalContext.current
    var requested by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val owner = requested; requested = null
        if (owner != null && store.state.value.generation == owner.second && store.state.value.selectedBot == owner.first) {
            if (granted) store.startVoice(owner.first, owner.second) else denied()
        }
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) store.startVoice(botId, state.generation)
        else { requested = botId to state.generation; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }
}

/** Opens this app's settings, where the microphone can be allowed. */
internal fun openAppSettings(context: android.content.Context) =
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))

@Composable internal fun VoiceControls(call: VoiceCallViewState, state: AppState, store: AppStore) {
    var settings by remember(call.id) { mutableStateOf(false) }
    val voice = call.state
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val bot = state.bots.firstOrNull { it.id == call.botId }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Voice · ${bot?.name ?: "Bot"}", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { store.endVoice() }) { Text("End call") }
            }
            Text(when (voice.phase) {
                VoicePhase.Starting -> "Starting offline speech…"
                VoicePhase.Listening -> if (voice.muted) "Muted" else if (bot?.status == "working") "Working, listening" else "Listening"
                VoicePhase.Speaking -> "Reading a reply"
                VoicePhase.Interrupted -> "Audio interrupted. Resume when ready."
                VoicePhase.Failed -> "Voice audio unavailable"
                VoicePhase.Ended -> "Ending call…"
            }, style = MaterialTheme.typography.bodyMedium)
            if (voice.engine.isNotBlank()) Text(voice.engine, style = MaterialTheme.typography.bodySmall)
            if (voice.heard.isNotBlank()) Text(voice.heard, maxLines = 2)
            voice.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.selectedBot != call.botId) TextButton(onClick = { store.openBot(call.botId) }) { Text("Open call conversation") }
            FlowRow {
                TextButton(enabled = voice.phase in listOf(VoicePhase.Listening, VoicePhase.Speaking, VoicePhase.Interrupted), onClick = store::muteVoice) { Text(if (voice.muted) "Unmute" else "Mute") }
                if (voice.phase == VoicePhase.Speaking) TextButton(onClick = store::interruptVoice) { Text("Interrupt reply") }
                if (voice.phase == VoicePhase.Interrupted) TextButton(onClick = store::resumeVoice) { Text("Resume voice") }
                TextButton(onClick = { settings = !settings }) { Text(if (settings) "Close voice settings" else "Voice settings") }
            }
            AnimatedVisibility(settings) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Send after a pause of", style = MaterialTheme.typography.titleSmall)
                FlowRow { for ((pause, label) in listOf(1_000L to "1 s", 1_500L to "1.5 s", 2_500L to "2.5 s"))
                    TextButton(enabled = voice.settings.pauseMillis != pause, onClick = { store.voiceSettings(voice.settings.copy(pauseMillis = pause)) }) { Text(label) }
                }
                Text("Reading speed", style = MaterialTheme.typography.titleSmall)
                FlowRow { for ((rate, label) in listOf(.84f to "Slower", 1f to "Normal", 1.12f to "Faster"))
                    TextButton(enabled = voice.settings.rate != rate, onClick = { store.voiceSettings(voice.settings.copy(rate = rate)) }) { Text(label) }
                }
                Text("Speech is recognized in English on this phone. Only text reaches the computer. Approve tool requests in the conversation.", style = MaterialTheme.typography.bodySmall)
            } }
        }
    }
}
