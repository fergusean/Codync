package com.codync.android

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import com.codync.android.design.CodyncField
import kotlinx.coroutines.launch

/** Setup stays usable before private fork builds have a public download. Pairing secrets stay in memory. */
@Composable internal fun PairingSetup(pasted: String, edit: (String) -> Unit, pairing: Boolean,
    paired: Boolean, pair: () -> Unit, scan: () -> Unit, close: () -> Unit,
    startAtPairing: Boolean = false, closeLabel: String? = null) {
    var scanning by rememberSaveable { mutableStateOf(startAtPairing) }
    var linux by rememberSaveable { mutableStateOf(false) }
    CodyncSheetBackHandler(!pairing) { if (scanning && !startAtPairing) scanning = false else close() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (scanning || pairing) "Step 2 of 2" else "Step 1 of 2", style = MaterialTheme.typography.labelLarge)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (scanning || pairing) "Pair a computer" else "Install Codync on your computer", style = MaterialTheme.typography.headlineMedium)
            if (!scanning && !pairing) {
                Text("Your bots run on your computer. This phone lets you talk to them.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { linux = false }) { Text(if (linux) "Mac" else "✓ Mac") }
                    TextButton(onClick = { linux = true }) { Text(if (linux) "✓ Linux" else "Linux") }
                }
                Text("Install the ZC Codync build provided for your workspace. Public downloads for this fork aren't available yet.")
                if (linux) {
                    Text("After installing codync-host, run this to keep it running in the background:")
                    SetupCommand("codync-host install")
                } else Text("Open ZC Codync. It sets up the host in the background.")
                Text("Already installed? Continue to open the pairing code.")
            } else {
                Text(if (linux) "On your computer, run this in a terminal:" else "On your Mac, open ZC Codync in the menu bar and choose Pair iPhone. Its code also pairs this Android phone.")
                if (linux) SetupCommand("codync-host pair")
                TextButton(enabled = !pairing, onClick = scan) { Text("Scan pairing code") }
                CodyncField(pasted, edit, modifier = Modifier.fillMaxWidth(), label = { Text("Pairing link") },
                    placeholder = { Text("codync://pair…") }, enabled = !pairing)
                Text("If pairing fails, check that the phone and computer share Wi-Fi or Tailscale, or enable Reach from anywhere on the computer.")
                Text("End-to-end encrypted on Wi-Fi, Tailscale, or through your computer's cloud relay.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (scanning || pairing) {
                Button(enabled = pasted.isNotBlank() && !pairing, onClick = pair) { Text(if (pairing) "Pairing…" else "Pair") }
                TextButton(enabled = !pairing, onClick = { scanning = false }) { Text("Installation steps") }
            } else Button(onClick = { scanning = true }) { Text("Continue") }
            TextButton(enabled = !pairing, onClick = close) { Text(closeLabel ?: if (paired) "Back to bots" else "Skip for now") }
        }
    }
}

@Composable private fun SetupCommand(command: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            SelectionContainer { Text(command, style = MaterialTheme.typography.bodyMedium) }
            TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Computer setup", command))) } }) { Text("Copy command") }
        }
    }
}
