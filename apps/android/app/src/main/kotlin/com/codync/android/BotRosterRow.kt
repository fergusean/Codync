package com.codync.android

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codync.android.core.Bot
import com.codync.android.design.CodyncIcon
import com.codync.android.design.Glyph
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable fun BotRosterRow(bot: Bot, state: AppState, store: AppStore?, edit: () -> Unit) {
    var actions by remember(bot.id) { mutableStateOf(false) }
    var deleting by remember(bot.id) { mutableStateOf(false) }
    val busy = "roster:" + bot.id in state.busy || "delete:" + bot.id in state.busy
    Column(Modifier.fillMaxWidth()
        .combinedClickable(onClick = { store?.openBot(bot.id) }, onLongClick = { actions = !actions }, onLongClickLabel = "Bot actions")
        .semantics { customActions = listOf(CustomAccessibilityAction("Bot actions") { actions = !actions; true }) }) {
        val preview = when (bot.status) {
            "needsInput" -> bot.activity.ifEmpty { "Needs your approval" }
            "working" -> bot.activity.ifEmpty { "Working…" }
            "error" -> bot.lastMessage ?: "Something went wrong"
            else -> bot.lastMessage ?: if (bot.kind == "group") "Start a group conversation" else {
                val backend = state.hello?.get("backends")?.jsonArray?.firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == bot.backend }
                    ?.jsonObject?.get("name")?.jsonPrimitive?.content ?: bot.backend
                backend + " · " + if (bot.managedWorkspace) "Personal workspace" else bot.cwd.trimEnd('/').substringAfterLast('/')
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BotAvatar(bot, state.bots, 46.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (bot.pinned) CodyncIcon(Glyph.Pin, Modifier.size(12.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(bot.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    if (bot.lastAt > 0) Text(activityDate(bot.lastAt), style = MaterialTheme.typography.bodyMedium,
                        color = if (bot.unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(preview, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                        color = when (bot.status) { "error" -> MaterialTheme.colorScheme.error; "needsInput" -> androidx.compose.ui.graphics.Color(0xFFF0A030); else -> MaterialTheme.colorScheme.onSurfaceVariant })
                    if (bot.unread > 0) Box(Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)).heightIn(min = 18.dp)
                        .widthIn(min = 18.dp).semantics { contentDescription = "${bot.unread} unread messages" }.padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
                        Text(bot.unread.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
        AnimatedContent(actions, label = "bot actions") { expanded -> if (expanded && store != null) Column {
            FlowRow {
                TextButton(enabled = !busy, onClick = {
                    store.command("updateBot", buildJsonObject { put("id", bot.id); put("pinned", !bot.pinned) }, "roster:" + bot.id) { actions = false }
                }) { Text(if (bot.pinned) "Unpin" else "Pin") }
                TextButton(enabled = !busy, onClick = { actions = false; edit() }) { Text(if (bot.kind == "group") "Edit group" else "Edit profile") }
                TextButton(enabled = !busy && bot.unread > 0, onClick = {
                    store.command("markRead", buildJsonObject { put("botId", bot.id); put("all", true) }, "roster:" + bot.id) { actions = false }
                }) { Text("Mark as read") }
                TextButton(enabled = !busy, onClick = {
                    store.command("updateBot", buildJsonObject { put("id", bot.id); put("hidden", !bot.hidden) }, "roster:" + bot.id) { actions = false }
                }) { Text(if (bot.hidden) "Show in roster" else "Hide from list") }
                TextButton(enabled = !busy, onClick = { deleting = !deleting }) { Text("Delete") }
            }
            AnimatedContent(deleting, label = "delete roster bot") { confirming -> if (confirming) Column {
                Text(if (bot.kind == "group") "Delete this group chat? Its bots and their own chats stay."
                    else "Delete this bot and its conversation? Files it changed on your computer stay as they are.")
                Row {
                    TextButton(enabled = !busy, onClick = { store.deleteBot(bot) { actions = false; deleting = false } }) {
                        Text(if (bot.kind == "group") "Delete group chat" else "Delete bot and conversation")
                    }
                    TextButton(onClick = { deleting = false }) { Text("Cancel") }
                }
            } }
        } }
    }
}

private fun activityDate(milliseconds: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(milliseconds).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    val pattern = if (date.toLocalDate() == today) "HH:mm" else if (date.toLocalDate().year == today.year) "MMM d" else "MMM d, yyyy"
    return date.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}
