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

@Composable internal fun StartVoiceButton(botId: String, state: AppState, store: AppStore, compact: Boolean = false) {
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val owner = requested; requested = null
        if (owner != null && store.state.value.generation == owner.second && store.state.value.selectedBot == owner.first) {
            denied = !granted
            if (granted) store.startVoice(owner.first, owner.second)
        }
    }
    val start = {
        denied = false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) store.startVoice(botId, state.generation)
        else { requested = botId to state.generation; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    if (compact) Box {
        CodyncIconButton("Voice chat", Glyph.Microphone, start,
            enabled = state.voice == null && state.actualConnection is LinkState.Ready)
        CodyncPopupMenu(denied, { denied = false }) {
        Text("Allow microphone access in Android settings to talk to your bot.")
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Microphone settings") }
        }
    } else {
        TextButton(enabled = state.voice == null && state.actualConnection is LinkState.Ready, onClick = start) { Text("Voice chat") }
        AnimatedVisibility(denied) { Text("Allow microphone access in Android settings to talk to your bot.") }
    }
}

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
