package com.codync.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.codync.android.core.*
import com.codync.android.design.*
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable fun UsageScreen(state: AppState, refresh: () -> Unit, close: () -> Unit) {
    val report = remember(state.usage) { runCatching { UsageReport.decode(state.usage) }.getOrNull() }
    val busy = "usage" in state.busy
    val online = state.actualConnection is LinkState.Ready
    BackHandler(onBack = close)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            CodyncTopBar(title = { Text("Usage", style = MaterialTheme.typography.titleMedium) },
                leading = { CodyncIconButton("Back", Glyph.Back, close) },
                trailing = { CodyncIconButton(if (busy) "Refreshing…" else "Refresh usage", Glyph.Refresh, refresh,
                    enabled = online && !busy && state.computers.isNotEmpty()) })
            Column(Modifier.weight(1f).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                state.computers.firstOrNull()?.let { Text(it.name, style = MaterialTheme.typography.titleMedium) }
                if (!online && state.computers.isNotEmpty()) Text("Saved reports. Connect to this computer to refresh.")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (report == null) Text("Couldn't read this usage report. Refresh to try again.")
                else if (report.providers.isEmpty()) {
                    Text("No usage yet", style = MaterialTheme.typography.titleLarge)
                    Text(if (state.computers.isEmpty()) "Usage shows up once a computer is connected." else "Your computer reads the limits from Claude Code and Codex.")
                }
                report?.providers?.forEach { provider -> key(provider.id) { ProviderUsageCard(provider) } }
            }
        }
    }
}

@Composable private fun ProviderUsageCard(provider: UsageProvider) {
    var collapsed by rememberSaveable(provider.id) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                Text(provider.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { collapsed = !collapsed }) { Text(if (collapsed) "Expand" else "Collapse") }
            }
            provider.tightest?.let { top -> UsageLimit(top, provider.id) }
            AnimatedContent(!collapsed, label = "provider usage details") { expanded -> if (expanded) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (provider.windows.isEmpty()) Text("Usage unavailable")
                provider.windows.forEach { window -> UsageLimit(window, provider.id) }
                Text("Source · ${provider.sourceLabel}", style = MaterialTheme.typography.bodySmall)
                Text("Last reported · " + (provider.updatedAt?.takeIf { it > 0 }?.let(::usageTime) ?: "Time unavailable"), style = MaterialTheme.typography.bodySmall)
            } }
        }
    }
}

@Composable private fun UsageLimit(window: UsageWindow, providerId: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(window.label, Modifier.weight(1f))
            Text(window.reportedPercent?.let { "${it.roundToInt()}%" } ?: "Usage unavailable")
        }
        window.barFraction?.let { fraction ->
            val percent = requireNotNull(window.reportedPercent)
            val color = when {
                percent >= 90 -> MaterialTheme.colorScheme.error
                percent >= 70 -> Color(0xFFBF791F)
                providerId == "claude" -> Color(0xFFD97757)
                else -> MaterialTheme.colorScheme.primary
            }
            Box(Modifier.fillMaxWidth().height(6.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(3.dp))
                .semantics { contentDescription = "${window.label}: ${percent.roundToInt()} percent used" }) {
                if (fraction > 0) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color, RoundedCornerShape(3.dp)))
            }
        }
        window.resetsAt?.let { Text("Resets ${usageTime(it)}", style = MaterialTheme.typography.bodySmall) }
            ?: window.resetsText?.takeIf { it.isNotBlank() }?.let { Text("Resets $it", style = MaterialTheme.typography.bodySmall) }
    }
}

private fun usageTime(milliseconds: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(milliseconds))
