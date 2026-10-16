package com.codync.android

import android.content.ClipData
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import com.codync.android.core.*
import com.codync.android.design.*
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable fun RoutinesScreen(bot: Bot, state: AppState, store: AppStore, close: () -> Unit) {
    var editor by remember(bot.id) { mutableStateOf<RoutineDraft?>(null) }
    var editing by remember(bot.id) { mutableStateOf<Routine?>(null) }
    var menu by remember { mutableStateOf(false) }
    val listing = state.routines[bot.id]
    val busy = "routines:${bot.id}" in state.busy
    val online = state.actualConnection is LinkState.Ready
    LaunchedEffect(bot.id, online, editor != null) {
        if (online && editor == null) while (true) { store.loadRoutines(bot.id); delay(3_000) }
    }
    if (editor != null) {
        RoutineEditor(bot.id, editing, state, store, { editor = null }) { saved ->
            if (editing == null && saved.hasWebhook) { editing = saved; editor = RoutineDraft(id = saved.id) }
            else editor = null
        }
        return
    }
    BackHandler(onBack = close)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            CodyncTopBar(title = { Text("Routines", style = MaterialTheme.typography.titleMedium) },
                leading = { CodyncIconButton("Back", Glyph.Back, close) }, trailing = {
                    Row {
                        CodyncIconButton("Set up a routine", Glyph.Plus, { editing = null; editor = RoutineDraft() }, enabled = online && !busy)
                        Box {
                            CodyncIconButton("Routine actions", Glyph.More, { menu = !menu })
                            CodyncPopupMenu(menu, { menu = false }) {
                                MenuAction("Ask the bot for a routine", enabled = online && !busy) {
                                    menu = false; store.editDraft(bot.id, null, "I want a routine that "); store.openBot(bot.id); close()
                                }
                                MenuAction("Refresh routines", enabled = online && !busy) { menu = false; store.loadRoutines(bot.id) }
                            }
                        }
                    }
                })
            Column(Modifier.weight(1f).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!online) Text("Connect to this computer to manage routines.")
                if (listing == null) Text(if (busy) "Loading routines…" else "Refresh to load routines.")
                else if (listing.routines.isEmpty()) Text("No routines yet. Ask the bot for one, or set it up yourself.")
                listing?.routines?.forEach { routine -> Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { editing = routine; editor = RoutineDraft(id = routine.id) }) { Text(routine.name) }
                        Text(listing.summary(routine))
                        routine.nextRunAt?.let { Text("Next ${routineTime(it)}", style = MaterialTheme.typography.bodySmall) }
                        TextButton(enabled = online && !busy, onClick = { store.routineAction(bot.id, routine.id, "setRoutineEnabled", !routine.enabled) }) {
                            Text(if (routine.enabled) "Pause routine" else "Resume routine")
                        }
                        listing.runs.firstOrNull { it.routineId == routine.id }?.let { run ->
                            Text("${run.label} · ${routineTime(run.createdAt)}", style = MaterialTheme.typography.bodySmall)
                            if (run.rootId != null) TextButton(onClick = { store.openBot(bot.id, run.rootId); close() }) { Text("View run results") }
                        }
                    }
                } }
            }
        }
    }
}

@Composable private fun RoutineEditor(botId: String, initial: Routine?, state: AppState, store: AppStore, close: () -> Unit, saved: (Routine) -> Unit) {
    var draft by remember(initial?.id) { mutableStateOf(RoutineDraft(initial?.id, initial?.name.orEmpty(), initial?.instruction.orEmpty(), timeoutSeconds = (initial?.timeoutSeconds ?: 3600).toString())) }
    var loaded by remember(initial?.id) { mutableStateOf(false) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var checkAttempt by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<RoutineSchedulePreview?>(null) }
    var checked by remember { mutableStateOf<RoutineScheduleDraft?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    val busy = "routines:$botId" in state.busy
    val online = state.actualConnection is LinkState.Ready
    BackHandler(enabled = !busy, onBack = close)
    LaunchedEffect(initial?.id, loadAttempt) {
        try {
            val result = store.routineSchedule(initial?.triggers.orEmpty(), TimeZone.getDefault().id)
            draft = draft.copy(schedule = result.draft.forMobileEditor())
            loaded = true; error = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Couldn't load this schedule." }
    }
    LaunchedEffect(draft.schedule, loaded, checkAttempt) {
        if (!loaded) return@LaunchedEffect
        checked = null; preview = null; error = null
        delay(300)
        val requested = draft.schedule
        try {
            val result = store.routineSchedule(requested)
            if (draft.schedule == requested) { preview = result; checked = requested }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (draft.schedule == requested) error = failure.message ?: "Couldn't check this schedule." }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = close) { Text("Cancel") }
                Text(if (initial == null) "Set up a routine" else "Edit routine", style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CodyncField(draft.name, { draft = draft.copy(name = it) }, label = { Text("Name") }, enabled = loaded && !busy, modifier = Modifier.fillMaxWidth())
                CodyncField(draft.instruction, { draft = draft.copy(instruction = it) }, label = { Text("Instruction") }, enabled = loaded && !busy, minLines = 3, modifier = Modifier.fillMaxWidth())
                Text("When to run")
                if (!loaded) Text("Loading schedule…")
                else {
                    FlowRow {
                        val choices = if (initial?.keepsOriginal == true) listOf("keep" to "Keep existing triggers", "cron" to "Schedule", "webhook" to "Webhook") else listOf("cron" to "Schedule", "webhook" to "Webhook")
                        choices.forEach { (kind, label) -> TextButton(enabled = !busy, onClick = { draft = draft.copy(schedule = draft.schedule.copy(kind = kind)) }) {
                            Text((if (draft.schedule.kind == kind) "✓ " else "") + label)
                        } }
                    }
                    when (draft.schedule.kind) {
                        "cron" -> {
                            CodyncField(draft.schedule.expression, { draft = draft.copy(schedule = draft.schedule.copy(expression = it)) }, label = { Text("Cron") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                            CodyncField(draft.schedule.zone, { draft = draft.copy(schedule = draft.schedule.copy(zone = it)) }, label = { Text("Time zone") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                        }
                        "webhook" -> if (initial?.hasWebhook == true) RoutineWebhookPanel(botId, initial.id, online, store)
                            else Text("Runs each time something is posted to its URL. Save to get the URL and key.")
                        else -> Text("Set up by the bot. Pick Schedule or Webhook to replace it.")
                    }
                    if (checked == draft.schedule) preview?.let { result ->
                        Text(result.summary)
                        result.nextRunAt?.let { Text("Next ${routineTime(it)}", style = MaterialTheme.typography.bodySmall) }
                        result.warning?.let { Text(it) }
                    } else if (error == null) Text("Checking schedule…")
                }
                CodyncField(draft.timeoutSeconds, { draft = draft.copy(timeoutSeconds = it) }, label = { Text("Timeout (seconds)") }, enabled = loaded && !busy, modifier = Modifier.fillMaxWidth())
                Text("A run is stopped after this long. 1 second to 24 hours.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (error != null) TextButton(enabled = online && !busy, onClick = { if (loaded) checkAttempt++ else loadAttempt++ }) {
                    Text(if (loaded) "Check schedule again" else "Retry loading schedule")
                }
                if (!online) Text("Connect to this computer to save your routine.")
                if (initial != null) FlowRow {
                    TextButton(enabled = online && !busy && !started, onClick = { store.routineAction(botId, initial.id, "runRoutine") { started = true } }) { Text(if (started) "Started" else "Test run") }
                    TextButton(enabled = online && !busy, onClick = { confirmDelete = true }) { Text("Delete routine") }
                }
                AnimatedContent(confirmDelete, label = "delete routine") { confirming -> if (confirming && initial != null) Column {
                    Text("Delete this routine and stop future runs? This can't be undone.")
                    FlowRow {
                        TextButton(enabled = online && !busy, onClick = { store.routineAction(botId, initial.id, "deleteRoutine", done = close) }) { Text("Confirm delete routine") }
                        TextButton(enabled = !busy, onClick = { confirmDelete = false }) { Text("Keep routine") }
                    }
                } }
            }
            Button(enabled = online && !busy && loaded && checked == draft.schedule && error == null && draft.name.isNotBlank() && draft.instruction.isNotBlank(),
                onClick = { store.saveRoutine(botId, draft, saved) }) { Text(if (busy) "Saving…" else if (initial == null) "Create routine" else "Save changes") }
        }
    }
}

@Composable private fun RoutineWebhookPanel(botId: String, id: String, online: Boolean, store: AppStore) {
    var webhook by remember(id) { mutableStateOf<RoutineWebhook?>(null) }
    var showKey by remember(id) { mutableStateOf(false) }
    var confirm by remember(id) { mutableStateOf(false) }
    var busy by remember(id) { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var copied by remember(id) { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    suspend fun load(rotate: Boolean) {
        busy = true
        try {
            val result = store.routineWebhook(botId, id, rotate)
            webhook = result; error = null
            if (rotate) { copied = null; showKey = true }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Couldn't load the webhook." }
        finally { busy = false }
    }
    LaunchedEffect(id) { load(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        webhook?.let { value ->
            Text(if (value.url == null) "Local URL" else "URL")
            SelectionContainer { Text(value.url ?: value.localUrl) }
            FlowRow { for (field in listOf("url", "key")) TextButton(onClick = { scope.launch {
                val clip = ClipData.newPlainText("Routine $field", if (field == "key") value.key else value.url ?: value.localUrl)
                if (field == "key" && Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
                clipboard.setClipEntry(ClipEntry(clip)); copied = field
            } }) { Text(if (copied == field) "Copied $field" else "Copy $field") } }
            Text("Key")
            SelectionContainer { Text(if (showKey) value.key else "••••••••••••••••••••••••") }
            FlowRow {
                TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide key" else "Show key") }
                TextButton(enabled = online && !busy, onClick = { confirm = true }) { Text("Replace key") }
            }
            Text(if (value.url == null) "The Codync cloud is off, so only this computer can send to it." else
                "POST with Authorization: Bearer <key>. For GitHub, use application/json and the key as its secret. Deliveries wait up to 72 hours while this computer is off. They pass through the Codync cloud, which can read them.", style = MaterialTheme.typography.bodySmall)
        } ?: Text(if (busy) "Loading webhook…" else "Couldn't load webhook.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(enabled = online && !busy, onClick = { scope.launch { load(false) } }) { Text("Retry webhook") } }
        AnimatedContent(confirm, label = "replace webhook key") { confirming -> if (confirming) Column {
            Text("Senders using the current key stop working until they get the new one.")
            FlowRow {
                TextButton(enabled = online && !busy, onClick = { confirm = false; busy = true; scope.launch { load(true) } }) { Text("Confirm replace key") }
                TextButton(enabled = !busy, onClick = { confirm = false }) { Text("Keep key") }
            }
        } }
    }
}

private fun routineTime(milliseconds: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(milliseconds))
