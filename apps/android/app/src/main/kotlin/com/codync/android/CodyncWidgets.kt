package com.codync.android

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.glance.color.ColorProvider
import androidx.glance.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.codync.android.core.*
import com.codync.android.design.characterAvatarBitmap
import java.text.DateFormat
import java.util.Date

class BotsWidget : CodyncWidget(WidgetKind.Bots)
class UsageWidget : CodyncWidget(WidgetKind.Usage)
class ProviderWidget : CodyncWidget(WidgetKind.Provider)

abstract class CodyncWidget(private val kind: WidgetKind) : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(160.dp, 120.dp), DpSize(300.dp, 160.dp), DpSize(300.dp, 300.dp)))
    override val previewSizeMode = sizeMode
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widget = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            val revision by WidgetStore.changes.collectAsState()
            val feed = remember(revision) { WidgetStore.feed(context) }
            val provider = remember(revision) { WidgetStore.provider(context, widget) }
            WidgetCard(context, feed, kind, provider)
        }
    }
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { WidgetCard(context, widgetSample(), kind, "claude", sample = true) }
    }
}

@Composable private fun WidgetCard(context: Context, feed: WidgetFeed, kind: WidgetKind, provider: String, sample: Boolean = false) {
    val size = LocalSize.current
    val limit = if (size.height >= 280.dp) 6 else if (size.width >= 280.dp) 3 else 1
    val content = feed.present(kind, provider, limit = if (kind == WidgetKind.Provider) limit.coerceAtMost(4) else limit)
    val foreground = ColorProvider(day = Color(0xFF111111), night = Color(0xFFEEEEEE))
    val secondary = ColorProvider(day = Color(0xFF666666), night = Color(0xFFA5A5A5))
    val warning = ColorProvider(day = Color(0xFFBF791F), night = Color(0xFFF0A030))
    val intent = widgetIntent(context, feed, usage = kind != WidgetKind.Bots)
    Column(GlanceModifier.fillMaxSize().appWidgetBackground().background(ImageProvider(R.drawable.widget_background))
        .padding(14.dp).then(if (sample) GlanceModifier else GlanceModifier.clickable(actionStartActivity(intent)))) {
        Column {
            Text(if (sample) "${content.title} · Sample data" else content.title,
                style = TextStyle(color = foreground, fontSize = 17.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            Text(content.subtitle, style = TextStyle(color = secondary, fontSize = 11.sp), maxLines = 1)
        }
        Spacer(GlanceModifier.height(10.dp))
        content.empty?.let { Text(it, style = TextStyle(color = secondary, fontSize = 13.sp), maxLines = 3) }
        for (line in content.lines) {
            val row = if (!sample && line.botId != null) GlanceModifier.clickable(actionStartActivity(widgetIntent(context, feed, line.botId)))
                else GlanceModifier
            Column(row.fillMaxWidth().padding(vertical = 5.dp)) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                    line.avatarShape?.let { shape ->
                        val pixels = (22 * context.resources.displayMetrics.density).toInt().coerceIn(1, 256)
                        val face = remember(shape, line.avatarColor, foreground.getColor(context), pixels) {
                            characterAvatarBitmap(shape, line.avatarColor, pixels, foreground.getColor(context).toArgb())
                        }
                        Image(ImageProvider(face), null, GlanceModifier.size(22.dp))
                        Spacer(GlanceModifier.width(8.dp))
                    }
                    Text(line.title, GlanceModifier.defaultWeight(), style = TextStyle(color = foreground, fontSize = 13.sp), maxLines = 1)
                    Text(line.value, style = TextStyle(color = if (line.warning) warning else secondary,
                        fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                }
                line.fraction?.let { fraction ->
                    val ink = (if (line.warning) warning else foreground).getColor(context).toArgb()
                    val track = ColorProvider(day = Color(0xFFDDDDDD), night = Color(0xFF444444)).getColor(context).toArgb()
                    val bar = remember(fraction, ink, track) { widgetBar(fraction, ink, track) }
                    Image(ImageProvider(bar), "${line.title}: ${line.value} used", GlanceModifier.fillMaxWidth().height(4.dp),
                        contentScale = ContentScale.FillBounds)
                }
                if (size.height >= 280.dp) resetLabel(line)?.let { Text(it, style = TextStyle(color = secondary, fontSize = 11.sp), maxLines = 1) }
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        Text(if (sample) "Sample data" else widgetAge(content.updatedAt), style = TextStyle(color = secondary, fontSize = 11.sp), maxLines = 1)
    }
}

/** A bounded raster bar preserves the palette on API 29, where Glance cannot tint ProgressBar. */
private fun widgetBar(fraction: Float, ink: Int, track: Int): android.graphics.Bitmap {
    val bitmap = android.graphics.Bitmap.createBitmap(400, 6, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(track)
    canvas.drawRect(0f, 0f, 400 * fraction.coerceIn(0f, 1f), 6f, android.graphics.Paint().apply { color = ink })
    return bitmap
}

internal fun widgetIntent(context: Context, feed: WidgetFeed, bot: String? = null, usage: Boolean = false): Intent {
    val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    val computer = feed.computerId ?: return intent
    if (bot != null || usage) intent.data = Uri.Builder().scheme("codync").authority(if (usage) "usage" else "bot")
        .apply { if (bot != null) appendPath(bot) }.appendQueryParameter("scope", feed.scope).appendQueryParameter("computer", computer).build()
    return intent
}

internal fun widgetAge(updated: Long): String = if (updated <= 0) "No report yet" else "Updated " +
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(updated))

internal fun resetLabel(line: WidgetLine): String? = line.resetsAt?.takeIf { it > 0 }?.let {
    "Resets " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
} ?: line.resetsText?.takeIf { it.isNotBlank() }?.let { "Resets $it" }

internal fun widgetSample(): WidgetFeed = WidgetFeed(computerId = "sample", computerName = "Sample computer",
    bots = listOf(WidgetBot("sample-scout", "Scout", "needsInput", avatarColor = "green", avatarShape = "cloud"),
        WidgetBot("sample-dex", "Dex", "working", avatarColor = "black", avatarShape = "hex"),
        WidgetBot("sample-moss", "Moss", "idle", 2, "orange", "teardrop")), botsAt = System.currentTimeMillis(), usageAt = System.currentTimeMillis(),
    usage = UsageReport(listOf(UsageProvider("claude", "Claude", listOf(UsageWindow("session", "Session", 38.0),
        UsageWindow("week", "Weekly", 62.0, resetsText = "Monday"))), UsageProvider("codex", "Codex", listOf(UsageWindow("daily", "Daily", 24.0))))))

class BotsWidgetReceiver : CodyncWidgetReceiver() { override val glanceAppWidget = BotsWidget() }
class UsageWidgetReceiver : CodyncWidgetReceiver() { override val glanceAppWidget = UsageWidget() }
class ProviderWidgetReceiver : CodyncWidgetReceiver() { override val glanceAppWidget = ProviderWidget() }

abstract class CodyncWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        super.onUpdate(context, manager, ids)
        WidgetRefreshWorker.schedule(context, WidgetStore.feed(context).computerId != null)
    }
    override fun onDeleted(context: Context, ids: IntArray) { WidgetStore.remove(context, ids); super.onDeleted(context, ids) }
    override fun onDisabled(context: Context) { super.onDisabled(context); WidgetRefreshWorker.schedule(context, WidgetStore.feed(context).computerId != null) }
}

internal object CodyncWidgets {
    val receivers = listOf(BotsWidgetReceiver::class.java, UsageWidgetReceiver::class.java, ProviderWidgetReceiver::class.java)
    fun installed(context: Context): Int = receivers.sumOf { AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, it)).size }
    suspend fun update(context: Context) {
        if (installed(context) == 0) return
        BotsWidget().updateAll(context); UsageWidget().updateAll(context); ProviderWidget().updateAll(context)
    }
    fun pin(context: Context, kind: WidgetKind): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return false
        val receiver = when (kind) { WidgetKind.Bots -> BotsWidgetReceiver::class.java; WidgetKind.Usage -> UsageWidgetReceiver::class.java;
            WidgetKind.Provider -> ProviderWidgetReceiver::class.java }
        return manager.requestPinAppWidget(ComponentName(context, receiver), null, null)
    }
}
