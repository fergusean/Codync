package com.codync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codync.android.core.*
import com.codync.android.design.AvatarPalette
import com.codync.android.design.CodyncIconButton
import com.codync.android.design.Glyph
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private const val DELETED_BOT = "A deleted bot"

/** Older exchanges between two bots, fetched read-only; never merged into the chat mirror, which would break history paging. */
internal suspend fun AppStore.botConversation(botId: String, peerId: String): List<Entry> = try {
    val response = hostQuery("botConversation", body("botId" to botId, "peerId" to peerId))
    response.jsonObject["entries"]?.jsonArray.orEmpty().map { Entry.decode(it.jsonObject) }
} catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { emptyList() }

internal fun botPairTitle(botId: String, peerId: String, bots: List<Bot>): String =
    (bots.firstOrNull { it.id == botId }?.name ?: DELETED_BOT) + " and " + (bots.firstOrNull { it.id == peerId }?.name ?: DELETED_BOT)

/** Compact centered row in the chat (one exchange, or "N messages with" a run of them); the text lives only in the sheet it opens. */
@Composable internal fun BotMessageRow(group: BotExchangeGroup, state: AppState, open: () -> Unit) {
    val peer = state.bots.firstOrNull { it.id == group.peer }
    val name = peer?.name ?: DELETED_BOT
    val failed = group.failed
    val lead = if (group.count > 1) "${group.count} messages with" else group.first.verb
    val tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    TextButton(onClick = open, Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        contentDescription = (if (group.count > 1) "${group.count} messages with " else group.first.verb + " ") + name + if (failed) ", failed" else ""
    }) {
        if (failed) { Text("!", color = tint, fontWeight = FontWeight.Bold); Spacer(Modifier.width(6.dp)) }
        Text(lead, color = tint, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(6.dp))
        if (peer != null) { BotAvatar(peer, state.bots, 18.dp); Spacer(Modifier.width(6.dp)) }
        Text(name, color = if (failed) tint else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The sheet's header, laid out like "Full conversation": the pair on the left, close on the right. */
@Composable private fun BotPairHeader(botId: String, peerId: String, bots: List<Bot>, close: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = botPairTitle(botId, peerId, bots) },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PairMember(botId, bots)
            Text("⇄", Modifier.clearAndSetSemantics {}, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PairMember(peerId, bots)
        }
        CodyncIconButton("Close conversation", Glyph.Close, close)
    }
    HorizontalDivider(thickness = .5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
}

@Composable private fun PairMember(id: String, bots: List<Bot>) {
    val bot = bots.firstOrNull { it.id == id }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (bot != null) BotAvatar(bot, bots, 22.dp)
        Text(bot?.name ?: DELETED_BOT, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Read-only history between two bots: pair header with close, time separators, author-labelled bubbles. */
@Composable internal fun BotConversationScreen(botId: String, peerId: String, state: AppState, store: AppStore, close: () -> Unit) {
    var fetched by remember(botId, peerId) { mutableStateOf<List<Entry>?>(null) }
    LaunchedEffect(botId, peerId, state.generation) { fetched = store.botConversation(botId, peerId) }
    val live = state.entries.filter { it.botId == botId && it.threadId == null }
    val rows = remember(fetched, live) { botConversationRows(botConversation(botId, fetched.orEmpty(), live, peerId)) }
    val list = rememberLazyListState()
    LaunchedEffect(rows.lastOrNull()?.id) { if (rows.isNotEmpty()) list.scrollToItem(rows.size - 1) }
    Column(Modifier.fillMaxSize()) {
        BotPairHeader(botId, peerId, state.bots, close)
        Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            if (rows.isEmpty() && fetched != null) Text("No messages yet.", Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.id }) { row -> ConversationRow(row, state.bots) }
            }
        }
    }
}

@Composable private fun ConversationRow(row: BotConversationRow, bots: List<Bot>) {
    when (row) {
        is BotConversationRow.Separator -> if (row.createdAt > 0) Text(conversationDate(row.createdAt),
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f))
        is BotConversationRow.Message -> Column(Modifier.fillMaxWidth().padding(end = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (row.showsAuthor) AuthorLabel(row.author, bots)
            Column(Modifier.widthIn(max = 680.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp))
                .padding(14.dp)) { MarkdownText(row.text) }
        }
        is BotConversationRow.Status -> {
            val failed = row.outcome as? BotOutcome.Failed
            Text(if (failed == null) "Waiting for " + (bots.firstOrNull { it.id == row.awaiting }?.name ?: DELETED_BOT) + "…"
                else failed.detail.ifEmpty { "Didn't complete." }, Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = if (failed == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
    }
}

@Composable private fun AuthorLabel(id: String, bots: List<Bot>) {
    val bot = bots.firstOrNull { it.id == id }
    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (bot != null) BotAvatar(bot, bots, 22.dp)
        Text(bot?.name ?: DELETED_BOT, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = bot?.let { AvatarPalette.tint(it.avatarColor) } ?: MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
