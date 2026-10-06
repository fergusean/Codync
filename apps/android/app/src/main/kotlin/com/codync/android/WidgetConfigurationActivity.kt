package com.codync.android

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import com.codync.android.design.*
import kotlinx.coroutines.launch

/** Native launcher configuration, kept separate from account authentication/navigation. */
class WidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        setResult(RESULT_CANCELED, result)
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
        if (info?.provider != ComponentName(this, ProviderWidgetReceiver::class.java)) { finish(); return }
        enableEdgeToEdge()
        setContent {
            val revision by WidgetStore.changes.collectAsState()
            val feed = remember(revision) { WidgetStore.feed(this) }
            val choices = (feed.usage.providers.map { it.id to it.name } + listOf("claude" to "Claude", "codex" to "Codex")).distinctBy { it.first }
            var provider by rememberSaveable { mutableStateOf(WidgetStore.provider(this, id)) }
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            CodyncTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.safeDrawingPadding()) {
                        CodyncTopBar(title = { Text("Provider widget", style = MaterialTheme.typography.titleMedium) },
                            leading = { CodyncIconButton("Cancel", Glyph.Close, { finish() }) })
                        Column(Modifier.weight(1f).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Follows the account selected in ZC Codync.")
                            Text(feed.computerName.ifBlank { "Connect a computer in ZC Codync to see reports." })
                            Text("Provider", style = MaterialTheme.typography.titleMedium)
                            CodyncChoices(provider, choices, { provider = it })
                            if (choices.none { it.first == provider }) Text("Saved provider · $provider")
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Button(enabled = !busy, onClick = {
                                busy = true
                                lifecycleScope.launch {
                                    try {
                                        WidgetStore.selectProvider(this@WidgetConfigurationActivity, id, provider)
                                        val manager = GlanceAppWidgetManager(this@WidgetConfigurationActivity)
                                        val glanceId = manager.getGlanceIdBy(id)
                                        ProviderWidget().update(this@WidgetConfigurationActivity, glanceId)
                                        setResult(RESULT_OK, result); finish()
                                    } catch (_: Exception) { error = "Couldn't update the widget. Try again."; busy = false }
                                }
                            }) { Text(if (busy) "Saving…" else "Save widget") }
                        }
                    }
                }
            }
        }
    }
}
