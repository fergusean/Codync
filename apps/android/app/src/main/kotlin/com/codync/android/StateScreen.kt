package com.codync.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.appwidget.AppWidgetManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codync.android.core.*
import com.codync.android.design.*

@Composable internal fun StateScreen(state: AppState, store: AppStore?, usage: () -> Unit) {
    var page by rememberSaveable { mutableStateOf("Widgets") }
    Column(Modifier.fillMaxSize()) {
        CodyncTopBar(title = { Text("State", style = MaterialTheme.typography.titleMedium) },
            trailing = { CodyncIconButton("Usage", Glyph.Chart, usage) })
        CodyncChoices(page, listOf("Widgets" to "Widgets", "Task status" to "Task status"), { page = it },
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), role = Role.Tab)
        AnimatedContent(page, Modifier.weight(1f), label = "State page") { selected ->
        if (selected == "Widgets") WidgetGallery(state)
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val active = state.bots.filter { !it.deleted && it.status in setOf("working", "needsInput", "error") }
            item { Text("Bot activity", style = MaterialTheme.typography.titleMedium) }
            if (active.isEmpty()) item { Text("No bots need attention right now.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(active, key = { it.id }) { BotRosterRow(it, state, store) { store?.openBot(it.id) } }
            item {
                Text("Notifications", style = MaterialTheme.typography.titleMedium)
                Text("Ongoing task notifications keep delegated tasks in view. Tap one to open its conversation.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { store?.notifications(!state.notifications) }) { Text(if (state.notifications) "Bot alerts · On" else "Bot alerts · Off") }
                TextButton(onClick = { store?.taskNotifications(!state.tasks) }) { Text(if (state.tasks) "Delegated task status · On" else "Delegated task status · Off") }
            }
        }
        }
    }
}

@Composable private fun WidgetGallery(state: AppState) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var count by remember { mutableIntStateOf(CodyncWidgets.installed(context)) }
    var checkError by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf(WidgetKind.Provider) }
    var provider by rememberSaveable { mutableStateOf("claude") }
    var size by rememberSaveable { mutableStateOf("Medium") }
    val report = remember(state.usage) { runCatching { UsageReport.decode(state.usage) }.getOrDefault(UsageReport()) }
    val providers = (report.providers.map { it.id to it.name } + listOf("claude" to "Claude", "codex" to "Codex")).distinctBy { it.first }
    fun check() { try { count = CodyncWidgets.installed(context); checkError = false } catch (_: Exception) { checkError = true } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) check() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            GallerySection {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center) { CharacterAvatar("hex", "gray", 38.dp) }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Codync, at a glance.", style = MaterialTheme.typography.titleMedium)
                        Text("Your bots and usage, on your Home Screen.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Text("Get set up", style = MaterialTheme.typography.titleMedium)
            GallerySection {
                SetupStatus("Connect a computer", state.computers.isNotEmpty())
                SetupStatus("Add a Codync widget", count > 0, if (checkError) "Unable to check" else null)
            }
            if (checkError) TextButton(onClick = { check() }) { Text("Check again") }
            Text(if (count > 0) "$count installed · Checks update when you return to Codync." else "Steps check themselves as you finish them.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Text("Choose your widget", style = MaterialTheme.typography.titleMedium)
            Text("Sample data", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CodyncChoices(kind.name, listOf(WidgetKind.Bots.name to "Bots", WidgetKind.Usage.name to "Usage limits",
                WidgetKind.Provider.name to "Provider usage"), { kind = WidgetKind.valueOf(it) })
            if (kind == WidgetKind.Provider) CodyncChoices(provider, providers, { provider = it })
            CodyncChoices(size, listOf("Small" to "Small", "Medium" to "Medium", "Large" to "Large"), { size = it })
            val sample = widgetSample()
            // A custom provider without preview data stays honestly unavailable.
            val content = sample.present(kind, provider, limit = when (size) { "Small" -> 1; "Large" -> 6; else -> 3 })
            WidgetPreview(content, size)
        }
        item {
            Text("Add a widget", style = MaterialTheme.typography.titleMedium)
            val supported = AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported
            if (supported) Button(onClick = { pinError = !CodyncWidgets.pin(context, kind) }) { Text("Add to Home Screen") }
            if (pinError) Text("Your launcher couldn't open the pin request. Add a widget from the Home Screen instead.")
            Text("1. Touch and hold an empty space on your Home Screen.\n2. Choose Widgets, then ZC Codync.\n3. Drag a widget onto your Home Screen.\n4. Touch and hold a provider widget to choose its provider.",
                style = MaterialTheme.typography.bodySmall)
            Text("The launcher chooses the initial size. Resize or reconfigure it on the Home Screen. Provider widgets start with Claude; edit the installed widget to choose another provider.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Text("Widgets follow the account selected in Codync and show the latest saved report. Background refresh is scheduled about every 30 minutes and may be delayed by Android or your launcher. Open Codync for live updates and approvals.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Lock Screen widget availability depends on your Android version and launcher. Delegated tasks use ongoing notifications.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun SetupStatus(title: String, complete: Boolean, error: String? = null) {
    Row(Modifier.fillMaxWidth().clearAndSetSemantics {
        contentDescription = title
        stateDescription = error ?: if (complete) "Complete" else "Not complete"
    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (complete) "✓" else "○", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = if (complete) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        if (error != null) Text(error, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
    }
}

@Composable private fun GallerySection(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable private fun WidgetPreview(content: WidgetPresentation, size: String) {
    val width = if (size == "Small") Modifier.widthIn(max = 190.dp) else Modifier.fillMaxWidth()
    Column(width.semantics { contentDescription = "$size widget preview · Sample data" }
        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(22.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(content.title, style = MaterialTheme.typography.titleMedium)
        Text(content.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content.empty?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        for (line in content.lines) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                line.avatarShape?.let { CharacterAvatar(it, line.avatarColor, 22.dp); Spacer(Modifier.width(8.dp)) }
                Text(line.title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(line.value, style = MaterialTheme.typography.bodySmall, color = if (line.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            line.fraction?.let { fraction -> Box(Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                if (fraction > 0) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(if (line.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary))
            } }
            if (size == "Large") resetLabel(line)?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        }
        Text("Sample data", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
