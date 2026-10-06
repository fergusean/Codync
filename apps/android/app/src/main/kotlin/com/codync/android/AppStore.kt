package com.codync.android

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codync.android.core.*
import java.io.IOException
import java.util.UUID
import java.io.File
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class AppState(
    val contextId: String = "local",
    val generation: Int = 0,
    val account: AccountState = AccountState(),
    val cloudComputers: List<CloudComputer> = emptyList(),
    val devices: List<CloudDevice> = emptyList(),
    val grants: Map<String, List<CloudGrant>> = emptyMap(),
    val access: AccessTicket? = null,
    val notifications: Boolean = true,
    val tasks: Boolean = true,
    val setupComplete: Boolean = false,
    val loading: Boolean = true,
    val pairing: Boolean = false,
    val computers: List<Computer> = emptyList(),
    val bots: List<Bot> = emptyList(),
    val rosterLoaded: Boolean = false,
    val mirrorRevision: Long = 0,
    val entries: List<Entry> = emptyList(),
    val pending: List<PendingSend> = emptyList(),
    val drafts: Map<String, ComposerDraft> = emptyMap(),
    val uploadProgress: Map<String, Int> = emptyMap(),
    val usage: JsonObject = JsonObject(emptyMap()),
    val hello: JsonObject? = null,
    val connection: LinkState = LinkState.Connecting,
    val actualConnection: LinkState = LinkState.Connecting,
    val selectedBot: String? = null,
    val navigationRequest: Int = 0,
    val navigationUsage: Boolean = false,
    val thread: String? = null,
    val trace: Boolean = false,
    val busy: Set<String> = emptySet(),
    val historyComplete: Set<String> = emptySet(),
    val memories: Map<String, MemoryListing> = emptyMap(),
    val routines: Map<String, RoutineListing> = emptyMap(),
    val plugins: PluginListing? = null,
    val voice: VoiceCallViewState? = null,
    val error: String? = null,
) {
    /** Connection alone cannot make a cached roster current before event catch-up. */
    internal val widgetBotsCurrent: Boolean get() {
        val head = hello?.get("rev")?.jsonPrimitive?.longOrNull ?: return false
        return !loading && rosterLoaded && actualConnection is LinkState.Ready && mirrorRevision >= head
    }
}

class AppStore(application: Application) : AndroidViewModel(application) {
    internal val activityOwnerId: String = UUID.randomUUID().toString()
    private var local = LocalStore(application)
    private var contextJob = SupervisorJob(viewModelScope.coroutineContext[Job])
    private var contextScope = CoroutineScope(contextJob + Dispatchers.Main.immediate)
    private val transitions = Mutex()
    private var cloud: CloudClient? = null
    private var sessionJob: Job? = null
    private var linkJob: Job? = null
    private val asked = mutableSetOf<String>()
    private var attachments = AttachmentFiles(application, local.directory)
    private val downloadLocks = ConcurrentHashMap<String, Mutex>()
    private val mutableState = MutableStateFlow(AppState())
    val state = mutableState.asStateFlow()
    private var database: MirrorDatabase? = null
    private var draftBook: DraftBook? = null
    private var draftWriter: Job? = null
    private var savedDrafts: Map<String, ComposerDraft> = emptyMap()
    private var connectionJob: Job? = null
    private var pairingJob: Job? = null
    private var graceJob: Job? = null
    private var channel: EncryptedChannel? = null
    private var foreground = false
    private val foregroundState = MutableStateFlow(false)
    private val setupTerminals = mutableMapOf<String, SetupTerminalSession>()
    private val appConnections = mutableMapOf<String, AppConnectionSession>()
    private var oauthRequestId: String? = null
    private data class VoiceBinding(val id: String, val botId: String, val generation: Int,
        val startedAt: Long, val session: VoiceSession, val replies: VoiceReplyGate)
    private var voiceBinding: VoiceBinding? = null
    private var voiceGeneration: Int? = null
    private val transportRequired: Boolean get() = foreground || state.value.voice != null
    private val sends = mutableSetOf<String>()
    private val actions = mutableMapOf<String, Job>()
    private var pluginsRefreshAgain = false
    private var widgetOwner: WidgetOwner? = null

    init {
        viewModelScope.launch {
            transitions.withLock { loadContext(null) }
            NativeAccounts.observe(viewModelScope) { account ->
                mutableState.update { it.copy(account = account) }
                if (account.ready) transitions.withLock {
                    // Read the latest state after a concurrent logout/reset finishes.
                    val current = state.value.account
                    val desired = current.userId ?: "local"
                    if (desired != local.contextId) {
                        retireContext()
                        loadContext(current.userId)
                    } else if (cloud == null && current.userId != null) {
                        configureCloud(current.userId)
                        refreshCloud()
                    }
                }
            }
        }
    }

    private suspend fun loadContext(userId: String?) {
        try {
            WidgetRefreshWorker.stop(getApplication())
            local = LocalStore(getApplication(), userId ?: "local")
            widgetOwner = withContext(Dispatchers.IO) { WidgetStore.activate(getApplication(), local.contextId) }
            PushDelivery.activate(getApplication(), userId)
            attachments = AttachmentFiles(getApplication(), local.directory)
            val computers = withContext(Dispatchers.IO) { local.computers() }
            val complete = withContext(Dispatchers.IO) { local.setupComplete() }
            mutableState.update { AppState(contextId = local.referenceId, generation = it.generation + 1,
                account = it.account, computers = computers, setupComplete = complete || computers.isNotEmpty() || userId != null) }
            mutableState.update { it.copy(notifications = PushPreferences.enabled(getApplication()), tasks = PushPreferences.tasks(getApplication())) }
            openMirror()
            if (userId != null) configureCloud(userId)
            mutableState.update { it.copy(loading = false) }
            observeWidgets(requireNotNull(widgetOwner))
            if (foreground) connect()
            refreshCloud()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { showError(error) }
    }

    private suspend fun configureCloud(userId: String) {
        val identity = withContext(Dispatchers.IO) { local.identity() }
        cloud = CloudClient(BuildConfig.CLOUD_URL, identity) { NativeAccounts.token(userId) }
    }

    /** Join every writer before closing a context's database or replacing its identity. */
    private suspend fun retireContext() = withContext(NonCancellable) {
        mutableState.update { it.copy(loading = true) }
        withContext(Dispatchers.IO) { WidgetStore.retire(getApplication(), widgetOwner) }
        widgetOwner = null
        WidgetRefreshWorker.stop(getApplication())
        try { CodyncWidgets.update(getApplication()) } catch (_: Exception) { }
        val call = voiceBinding; voiceBinding = null; voiceGeneration = null
        val callId = state.value.voice?.id
        mutableState.update { it.copy(voice = null) }
        if (callId != null) VoiceService.stop(getApplication(), callId)
        call?.session?.end(); call?.session?.awaitEnd()
        val writers = actions.values.toList()
        writers.forEach { it.cancel() }; writers.forEach { it.join() }
        PushDelivery.retire(getApplication())
        val retiringChannel = channel?.takeIf { it.state.value is LinkState.Ready }
        val feed = TaskFeed(local.directory)
        if (retiringChannel != null) withTimeoutOrNull(2_000) {
            try {
                retiringChannel.call("unregisterDevice")
                for (watch in withContext(Dispatchers.IO) { feed.all() }) retiringChannel.call("unregisterActivity", body("botId" to watch.botId))
            } catch (_: Exception) { }
        }
        // Editing is disabled during retirement; let its last coalesced write finish.
        draftWriter?.join()
        contextJob.cancelAndJoin()
        setupTerminals.clear()
        appConnections.clear()
        oauthRequestId = null
        AccountImages.retire()
        channel?.close(); channel = null
        connectionJob = null; pairingJob = null; graceJob = null
        actions.clear(); sends.clear(); downloadLocks.clear(); asked.clear(); cloud = null
        pluginsRefreshAgain = false
        draftWriter = null; draftBook = null; savedDrafts = emptyMap()
        withContext(Dispatchers.IO) { feed.clear(); database?.close(); database = null }
        withContext(Dispatchers.IO) { ConnectorOAuthBook(local.directory).clear() }
        contextJob = SupervisorJob(viewModelScope.coroutineContext[Job])
        contextScope = CoroutineScope(contextJob + Dispatchers.Main.immediate)
    }

    fun signIn(apple: Boolean = false, hosted: Boolean = false) = sessionAction {
        if (hosted) NativeAccounts.hostedSignIn() else NativeAccounts.signIn(apple)
    }
    fun switchAccount(account: SavedAccount) = sessionAction { NativeAccounts.switch(account) }
    fun retryAccounts() { NativeAccounts.retry() }
    fun signOut() = sessionAction {
        val old = state.value.account.accounts.firstOrNull { it.userId == state.value.account.userId } ?: return@sessionAction
        NativeAccounts.signOut(old.sessionId)
        transitions.withLock {
            // The SDK updates its session synchronously; its observer may be queued behind us.
            val userId = com.clerk.api.Clerk.activeSession?.user?.id
            retireContext()
            loadContext(userId)
            val remaining = com.clerk.api.Clerk.auth.sessions.any { it.user?.id == old.userId && it.status == com.clerk.api.session.Session.SessionStatus.ACTIVE }
            if (!remaining) withContext(Dispatchers.IO) {
                LocalStore(getApplication(), old.userId).erase()
                File(getApplication<Application>().cacheDir, "codync-images-${LocalStore.referenceId(old.userId)}").deleteRecursively()
            }
        }
    }

    private fun sessionAction(operation: suspend () -> Unit) {
        if (sessionJob?.isActive == true) return
        sessionJob = viewModelScope.launch {
            mutableState.update { it.copy(busy = it.busy + "session", error = null) }
            try { operation() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { showError(error) }
            finally { mutableState.update { it.copy(busy = it.busy - "session") } }
        }
    }

    fun refreshCloud() = action("cloud") {
        val client = cloud ?: return@action
        try {
            client.registerDevice(Build.MODEL.take(100))
            val computers = client.computers()
            mutableState.update { it.copy(cloudComputers = computers) }
            val target = computers.firstOrNull { remote -> state.value.computers.isEmpty() && remote.computerId !in asked }
            if (target != null) requestAccess(target)
        } catch (error: CloudError) { if (!error.isTransient) throw error }
    }

    fun requestAccess(target: CloudComputer) = action("access") {
        val client = requireNotNull(cloud) { "Sign in to reach your account." }
        require(state.value.computers.isEmpty() || state.value.computers.first().id == target.computerId) { "Forget the current computer before connecting a different one." }
        asked.add(target.computerId)
        val ticket = client.requestAccess(target.computerId, target.signKey)
        mutableState.update { it.copy(access = ticket) }
        try {
            while (currentCoroutineContext().isActive) {
                if (System.currentTimeMillis() >= ticket.expiresAt) throw CloudError(410, "requestExpired")
                delay(2_000)
                val status = try { client.accessStatus(ticket.requestId) }
                catch (error: CloudError) { if (error.isTransient) continue else throw error }
                require(status.requestId == ticket.requestId && (status.computerId == null || status.computerId == ticket.computerId)) { "Invalid access response" }
                when (status.state) {
                    "pending" -> continue
                    "approved" -> {
                        // Pin only the key used in the SAS. The first authenticated hello supplies addresses and box key.
                        val computer = Computer(ticket.computerId, target.name, ticket.signKey, cloud = BuildConfig.CLOUD_URL)
                        computer.validate()
                        withContext(Dispatchers.IO) { local.saveComputers(listOf(computer)); local.completeSetup() }
                        mutableState.update { it.copy(computers = listOf(computer), setupComplete = true) }
                        openMirror(); connect(); return@action
                    }
                    "denied" -> throw IOException("${target.name} declined the request.")
                    else -> throw CloudError(410, "requestExpired")
                }
            }
        } finally { mutableState.update { it.copy(access = null) } }
    }

    fun cancelAccess() {
        val ticket = state.value.access
        val client = cloud
        val request = actions["access"]
        action("cancelAccess") {
            request?.cancelAndJoin()
            if (ticket != null && client != null) client.cancelAccess(ticket.requestId)
        }
    }

    fun refreshDevices() = action("devices") {
        val client = cloud ?: return@action
        val devices = client.devices()
        mutableState.update { it.copy(devices = devices) }
    }
    fun loadGrants(id: String) = action("grants:$id") {
        val grants = requireNotNull(cloud).grants(id)
        mutableState.update { it.copy(grants = it.grants + (id to grants)) }
    }
    fun revokeCloudDevice(id: String) = action("revokeDevice") { requireNotNull(cloud).revokeDevice(id); refreshDevices(); refreshCloud() }
    fun revokeGrant(id: String, grantId: String) = action("revokeGrant") { requireNotNull(cloud).revokeGrant(id, grantId); loadGrants(id) }
    fun removeCloudComputer(id: String) = action("removeComputer") { requireNotNull(cloud).removeComputer(id); refreshCloud() }

    fun setRoute(route: ConnectionRoute) = action("route") {
        val computer = state.value.computers.firstOrNull() ?: return@action
        val changed = computer.copy(route = route)
        withContext(Dispatchers.IO) { local.saveComputers(listOf(changed)) }
        mutableState.update { it.copy(computers = listOf(changed)) }
        connectionJob?.cancelAndJoin(); connect()
    }

    fun forgetComputer() = sessionAction {
        transitions.withLock {
            val userId = state.value.account.userId
            val id = state.value.computers.firstOrNull()?.id
            retireContext()
            if (id != null) asked.add(id)
            withContext(Dispatchers.IO) {
                local.saveComputers(emptyList())
                local.directory.listFiles()?.filter { it.name.startsWith("mirror-") || it.name.startsWith("drafts-") }?.forEach { it.delete() }
            }
            loadContext(userId)
        }
    }

    private suspend fun openMirror() {
        val computer = state.value.computers.firstOrNull() ?: return
        val book = DraftBook(local.directory, computer.id)
        withContext(Dispatchers.IO) {
            database?.close()
            database = MirrorDatabase(getApplication(), local.directory, computer.id)
            database?.recoverInterrupted()
            val snapshot = requireNotNull(database).snapshot()
            val restored = book.recover(snapshot)
            savedDrafts = restored.associateBy { it.lane }
            mutableState.update { it.copy(drafts = restored.associateBy { draft -> draft.lane }) }
            attachments.retainPending(snapshot.pending.flatMap { it.files } + restored.flatMap { it.files })
        }
        draftBook = book
        refresh()
    }

    private suspend fun refresh() {
        val mirror = database ?: return
        val snapshot = withContext(Dispatchers.IO) { mirror.snapshot() }
        val retainedDraftFiles = state.value.drafts.values.flatMap { it.files }.map { it.path }.toSet()
        val retired = state.value.pending.filter { old -> snapshot.pending.none { it.nonce == old.nonce } }.flatMap { it.files }.filter { it.path !in retainedDraftFiles }
        withContext(Dispatchers.IO) { retired.forEach(attachments::discard) }
        mutableState.update { current -> current.copy(bots = snapshot.bots, entries = snapshot.entries,
            pending = snapshot.pending, usage = snapshot.usage, mirrorRevision = snapshot.rev,
            selectedBot = current.selectedBot?.takeIf { id -> snapshot.bots.any { it.id == id } }) }
        voiceBinding?.let { call ->
            if (snapshot.bots.none { it.id == call.botId && !it.deleted }) endVoice("Voice chat ended because its bot was removed.")
            else for (reply in call.replies.observe(snapshot.entries)) call.session.speak(reply)
        }
        if (PushPreferences.tasks(getApplication())) {
            val feed = TaskFeed(local.directory)
            val watched = withContext(Dispatchers.IO) { feed.all().filter { !it.finished }.map { it.botId }.toSet() }
            for (bot in snapshot.bots.filter { it.id in watched }) {
                val watch = withContext(Dispatchers.IO) { feed.local(bot) }
                if (watch != null) NotificationDisplay.task(getApplication(), local.referenceId, state.value.computers.first().id, watch)
            }
        }
    }

    fun setForeground(active: Boolean) {
        foreground = active
        foregroundState.value = active
        PushDelivery.foreground = transportRequired
        if (active && !state.value.loading) {
            connect()
            acknowledgeVisible()
            refreshCloud()
        }
        if (!active) {
            pairingJob?.cancel()
            if (!transportRequired) {
                connectionJob?.cancel()
                graceJob?.cancel()
                channel?.close()
            }
        }
    }

    fun skipSetup() = action("setup") {
        withContext(Dispatchers.IO) { local.completeSetup() }
        mutableState.update { it.copy(setupComplete = true) }
    }

    fun pair(text: String) {
        if (pairingJob?.isActive == true) return
        pairingJob = contextScope.launch {
            state.first { !it.loading }
            mutableState.update { it.copy(pairing = true, error = null) }
            try {
                val pairing = Pairing.parse(text)
                val old = state.value.computers.firstOrNull()
                require(old == null || old.id == pairing.computer.id) { "Use Start over to pair a different computer." }
                require(old == null || old.signKey == pairing.computer.signKey) { "This computer's identity changed. Start over before pairing again." }
                val identity = withContext(Dispatchers.IO) { local.identity() }
                val computer = HostConnector.pair(pairing, identity, Build.MODEL.take(100))
                withContext(Dispatchers.IO) { local.saveComputers(listOf(computer)); local.completeSetup() }
                mutableState.update { it.copy(computers = listOf(computer), setupComplete = true) }
                if (database == null) openMirror()
                connect()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { showError(error) }
            finally { mutableState.update { it.copy(pairing = false) } }
        }
    }

    fun clearError() { mutableState.update { it.copy(error = null) } }

    fun startOver() = sessionAction {
        transitions.withLock {
            retireContext()
            withContext(NonCancellable) {
                NativeAccounts.reset()
                withContext(Dispatchers.IO) {
                    LocalStore.eraseAll(getApplication())
                    WidgetStore.reset(getApplication())
                    PushPreferences.reset(getApplication())
                    val cache = getApplication<Application>().cacheDir
                    File(cache, "shared-attachments").deleteRecursively()
                    cache.listFiles()?.filter { it.name.startsWith("codync-images") }?.forEach { it.deleteRecursively() }
                }
                mutableState.update { it.copy(account = AccountState(ready = true), error = null) }
                loadContext(null)
            }
        }
    }

    fun connect() {
        if (connectionJob?.isActive == true || !transportRequired) return
        val computer = state.value.computers.firstOrNull() ?: return
        val previous = connectionJob
        connectionJob = contextScope.launch {
            previous?.cancelAndJoin()
            var backoff = 1_000L
            while (transportRequired) {
                var connected: EncryptedChannel? = null
                try {
                    showConnection(LinkState.Connecting)
                    val identity = withContext(Dispatchers.IO) { local.identity() }
                    val target = state.value.computers.firstOrNull { it.id == computer.id } ?: return@launch
                    val active = HostConnector.connect(target, identity)
                    connected = active
                    channel = active
                    coroutineScope {
                        launch {
                            active.mailbox.collect { event ->
                                val nonce = event["nonce"]?.jsonPrimitive?.content ?: return@collect
                                val send = state.value.pending.firstOrNull { it.nonce == nonce } ?: return@collect
                                val status = if (event["t"]?.jsonPrimitive?.content == "mbox.delivered") SendStatus.Delivering else SendStatus.Failed
                                savePending(send.copy(status = status))
                            }
                        }
                        launch {
                            active.state.collectLatest { link ->
                                showConnection(link)
                                if (link is LinkState.ComputerOffline) {
                                    reconcileMailbox(active)
                                }
                                if (link is LinkState.Ready) {
                                    val updated = HostClient.hello(active, target)
                                    val hello = active.call("hello").jsonObject
                                    withContext(Dispatchers.IO) {
                                        local.saveComputers(listOf(updated))
                                        requireNotNull(database).setStamp(requireNotNull(hello["hostId"]?.jsonPrimitive?.content),
                                            BuildConfig.VERSION_NAME + "/" + hello["version"]?.jsonPrimitive?.content)
                                    }
                                    mutableState.update { it.copy(computers = listOf(updated), hello = hello) }
                                    // The event catch-up is ordered by revision; bot revisions can
                                    // follow hundreds of historical entries. Load the current roster
                                    // without moving the event cursor before consuming that stream.
                                    if (state.value.bots.isEmpty()) {
                                        val roster = HostClient.bots(active)
                                        if (BuildConfig.DEBUG) android.util.Log.d("CodyncSync", "Initial roster: ${roster.size} bots")
                                        withContext(Dispatchers.IO) { requireNotNull(database).receive(roster) }
                                    }
                                    mutableState.update { it.copy(rosterLoaded = true) }
                                    refresh()
                                    backoff = 1_000
                                    reconcileMailbox(active)
                                    acknowledgeVisible()
                                    syncPush()
                                    while (isActive) {
                                        val cursor = withContext(Dispatchers.IO) { requireNotNull(database).revision() }
                                        try {
                                            consumeEvents(active, cursor)
                                        } catch (_: Resync) { /* The cursor remains the last committed event. */ }
                                    }
                                }
                            }
                        }
                        val end = active.state.first { it is LinkState.Failed } as LinkState.Failed
                        val status = end.status
                        if (status != null) throw HostError(status, end.message)
                        throw IOException(end.message)
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    if (BuildConfig.DEBUG) android.util.Log.d("CodyncSync", "Sync failed: ${error.javaClass.simpleName}")
                    showConnection(LinkState.Failed(error.message ?: "Can't reach the computer.", (error as? HostError)?.status))
                    if (error is SecurityException || error is HostError && error.status in listOf(403, 426)) return@launch
                } finally {
                    connected?.close()
                    if (channel === connected) channel = null
                }
                delay(backoff)
                backoff = minOf(backoff * 2, 20_000)
            }
        }
    }

    /** Drain available catch-up in bounded transactions, without delaying a lone live event. */
    private suspend fun consumeEvents(active: EncryptedChannel, cursor: Long): Unit = coroutineScope {
        val source = active.events(cursor).produceIn(this)
        try {
            while (isActive) {
                val batch = mutableListOf<MirrorEvent>()
                var next: JsonObject? = source.receiveCatching().getOrThrow()
                var resync = false
                while (next != null && batch.size < 64) {
                    val event = try { MirrorEvent.decode(next) }
                    catch (error: Exception) {
                        withContext(Dispatchers.IO) { requireNotNull(database).rewind() }
                        throw IOException("Couldn't decode a computer update; refreshing from the beginning.", error)
                    }
                    if (event == MirrorEvent.Resync) { resync = true; break }
                    batch.add(event)
                    next = if (batch.size < 64) source.tryReceive().getOrNull() else null
                }
                withContext(Dispatchers.IO) { requireNotNull(database).apply(batch) }
                refresh()
                if (batch.any { it is MirrorEvent.EntryChanged && it.entry.isChat }) acknowledgeVisible()
                if (resync) throw Resync()
            }
        } finally { source.cancel() }
        throw IOException("The event subscription ended.")
    }

    private fun showConnection(link: LinkState) {
        mutableState.update { it.copy(actualConnection = link) }
        val immediate = link is LinkState.Ready || link is LinkState.Failed && link.status in listOf(403, 426)
        if (immediate) {
            graceJob?.cancel()
            mutableState.update { it.copy(connection = link) }
        } else if (graceJob?.isActive != true) {
            val grace = if (state.value.connection is LinkState.Ready) 5_000L else 1_000L
            graceJob = contextScope.launch {
                delay(grace)
                mutableState.update { it.copy(connection = it.actualConnection) }
            }
        }
    }

    private suspend fun ready(): EncryptedChannel = withTimeout(20_000) {
        while (true) {
            val active = channel
            if (active?.state?.value is LinkState.Ready) return@withTimeout active
            val link = active?.state?.value
            if (link is LinkState.ComputerOffline) throw IOException("The computer is offline.")
            if (link is LinkState.Failed && link.status in listOf(403, 426)) throw HostError(requireNotNull(link.status), link.message)
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") error("Unreachable")
    }

    fun openBot(id: String?, thread: String? = null) {
        mutableState.update { it.copy(selectedBot = id, thread = thread, trace = false) }
        if (id != null) {
            acknowledgeVisible()
            if (thread != null) action("thread:" + thread) {
                val active = ready()
                val response = active.call("thread", body("botId" to id, "rootId" to thread))
                receiveEntries(response)
            }
        }
    }
    fun showTrace(show: Boolean) { mutableState.update { it.copy(trace = show) } }

    private fun acknowledgeVisible() {
        val current = state.value
        val id = current.selectedBot ?: return
        if (!foreground || current.trace || channel?.state?.value !is LinkState.Ready) return
        action("read:" + id + ":" + current.thread) {
            val response = body("botId" to id, "threadId" to current.thread)
            channel?.call("markRead", response)
        }
    }

    fun loadHistory() {
        val id = state.value.selectedBot ?: return
        action("history") {
            val mirror = requireNotNull(database)
            if (withContext(Dispatchers.IO) { mirror.historyComplete(id) }) {
                mutableState.update { it.copy(historyComplete = it.historyComplete + id) }
                return@action
            }
            val before = state.value.entries.filter { it.botId == id }.minOfOrNull(Entry::seq) ?: Long.MAX_VALUE
            val active = ready()
            val response = active.call("history", buildJsonObject { put("botId", id); put("beforeSeq", before); put("limit", 100) })
            val entries = response.jsonObject.getValue("entries").jsonArray.map { Entry.decode(it.jsonObject) }
            withContext(Dispatchers.IO) {
                mirror.receive(entries)
                if (entries.size < 100) mirror.finishHistory(id)
            }
            if (entries.size < 100) mutableState.update { it.copy(historyComplete = it.historyComplete + id) }
            refresh()
        }
    }

    fun send(text: String, files: List<OutgoingFile> = emptyList()) {
        val id = state.value.selectedBot ?: return
        if (text.isBlank() && files.isEmpty()) return
        val send = PendingSend(UUID.randomUUID().toString(), id, state.value.thread, text.trim(), System.currentTimeMillis(), files = files)
        action("save:" + send.nonce) { savePending(send); deliver(send) }
    }

    fun startVoice(botId: String, expectedGeneration: Int = state.value.generation) {
        if (state.value.generation != expectedGeneration || state.value.loading || state.value.voice != null) return
        if (!foreground || state.value.actualConnection !is LinkState.Ready || state.value.bots.none { it.id == botId && !it.deleted && it.kind != "group" }) {
            mutableState.update { it.copy(error = "Open the connected bot's conversation to start voice chat.") }; return
        }
        val id = UUID.randomUUID().toString()
        voiceGeneration = expectedGeneration
        mutableState.update { it.copy(voice = VoiceCallViewState(id, botId, VoiceState(settings = VoicePreferences.load(getApplication())))) }
        try { VoiceService.start(getApplication(), this, id, botId); PushDelivery.foreground = true }
        catch (_: Exception) { finishVoice(id, "Couldn't start voice chat. Allow microphone access and wait for any previous call to finish.") }
    }
    internal fun attachVoice(id: String, botId: String, audio: VoiceAudio): VoiceSession? {
        val call = state.value.voice ?: return null
        if (call.id != id || call.botId != botId || voiceGeneration != state.value.generation || state.value.loading || voiceBinding != null) return null
        val generation = state.value.generation
        val entries = state.value.entries
        val head = maxOf(state.value.hello?.get("rev")?.jsonPrimitive?.longOrNull ?: 0, entries.maxOfOrNull(Entry::rev) ?: 0)
        val session = VoiceSession(contextScope, audio, call.state.settings) { text ->
            if (state.value.generation == generation && state.value.voice?.id == id && !state.value.loading) {
                val send = PendingSend(UUID.randomUUID().toString(), botId, null, text, System.currentTimeMillis())
                action("save:" + send.nonce) { savePending(send); deliver(send) }
            }
        }
        voiceBinding = VoiceBinding(id, botId, generation, android.os.SystemClock.elapsedRealtime(), session, VoiceReplyGate(botId, head, entries))
        contextScope.launch { session.state.takeWhile { it.phase != VoicePhase.Ended }.collect { status ->
            mutableState.update { current -> if (current.voice?.id == id) current.copy(voice = current.voice.copy(state = status)) else current }
        } }
        connect()
        return session
    }
    fun endVoice(error: String? = null) {
        val id = state.value.voice?.id ?: return
        finishVoice(id, error); VoiceService.stop(getApplication(), id)
    }
    internal fun finishVoice(id: String, error: String? = null) {
        if (state.value.voice?.id != id) return
        val call = voiceBinding?.takeIf { it.id == id }; voiceBinding = null; voiceGeneration = null
        call?.session?.end()
        mutableState.update { it.copy(voice = null, error = error ?: it.error) }
        PushDelivery.foreground = foreground
        contextScope.launch {
            try {
                call?.session?.awaitEnd()
                if (call != null && state.value.generation == call.generation && channel?.state?.value is LinkState.Ready) {
                    val seconds = ((android.os.SystemClock.elapsedRealtime() - call.startedAt) / 1_000).coerceAtLeast(0)
                    withTimeoutOrNull(3_000) { hostQuery("logCall", buildJsonObject { put("botId", call.botId); put("seconds", seconds) }, 3_000, call.generation) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Duration is best effort; an uncertain log is never retried. */ }
            finally { if (!transportRequired) { connectionJob?.cancel(); graceJob?.cancel(); channel?.close() } }
        }
    }
    fun muteVoice() { voiceBinding?.session?.let { it.mute(!it.state.value.muted) } }
    fun interruptVoice() { voiceBinding?.session?.interrupt() }
    fun resumeVoice() { voiceBinding?.session?.focus(true) }
    fun voiceSettings(settings: VoiceSettings) { VoicePreferences.save(getApplication(), settings); voiceBinding?.session?.settings(settings) }

    fun editDraft(botId: String, thread: String?, text: String) {
        if (state.value.loading) return
        val lane = ComposerDraft.lane(botId, thread)
        val current = state.value.drafts[lane]
        if (current?.text == text || current == null && text.isEmpty()) return
        try { updateDraft((current ?: ComposerDraft(botId, thread)).copy(text = text)) }
        catch (error: Exception) { showError(error) }
    }

    fun addDraftFiles(botId: String, thread: String?, files: List<OutgoingFile>) {
        check(!state.value.loading) { "Wait for the account to finish opening before adding files." }
        val current = state.value.drafts[ComposerDraft.lane(botId, thread)] ?: ComposerDraft(botId, thread)
        updateDraft(current.copy(files = current.files + files))
    }

    fun removeDraftFile(botId: String, thread: String?, file: OutgoingFile) = action("draft-file:" + file.path) {
        val current = state.value.drafts[ComposerDraft.lane(botId, thread)] ?: return@action
        updateDraft(current.copy(files = current.files - file))
        draftWriter?.join()
        check(savedDrafts.values.none { draft -> draft.files.any { it.path == file.path } }) { "Couldn't save the attachment removal. Retry before leaving this chat." }
        if (state.value.pending.none { send -> send.files.any { it.path == file.path } }) withContext(Dispatchers.IO) { attachments.discard(file) }
    }

    private fun updateDraft(draft: ComposerDraft) {
        val book = draftBook ?: return
        val drafts = if (draft.text.isEmpty() && draft.files.isEmpty()) state.value.drafts - draft.lane else state.value.drafts + (draft.lane to draft)
        book.validate(drafts.values, checkPaths = false)
        mutableState.update { it.copy(drafts = drafts) }
        persistDrafts()
    }

    private fun persistDrafts() {
        if (draftWriter?.isActive == true) return
        val book = draftBook ?: return
        draftWriter = contextScope.launch {
            try {
                while (isActive) {
                    val saving = state.value.drafts.values.toList()
                    withContext(Dispatchers.IO) { book.save(saving) }
                    savedDrafts = saving.associateBy { it.lane }
                    if (state.value.drafts.values.toList() == saving) break
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableState.update { it.copy(error = "Couldn't save this draft. Keep the app open and retry.") } }
            finally { draftWriter = null }
        }
    }

    fun sendDraft(botId: String, thread: String?, text: String, onQueued: () -> Unit) {
        editDraft(botId, thread, text)
        val lane = ComposerDraft.lane(botId, thread)
        val draft = state.value.drafts[lane] ?: return
        if (draft.text != text) return
        if (draft.text.isBlank() && draft.files.isEmpty()) return
        action("draft-send:" + lane) {
            // Persist its nonce before the pending commit. Recovery removes a draft
            // already represented by a pending or confirmed send with this nonce.
            persistDrafts()
            draftWriter?.join()
            check(savedDrafts[lane] == draft) { "Couldn't save this draft. Retry to send it." }
            val send = PendingSend(draft.nonce, botId, thread, draft.text.trim(), System.currentTimeMillis(), files = draft.files)
            savePending(send)
            mutableState.update { it.copy(drafts = it.drafts - lane) }
            persistDrafts()
            onQueued()
            action("send:" + send.nonce) { deliver(send) }
        }
    }

    fun retry(send: PendingSend) = action("send:" + send.nonce) { deliver(send.copy(status = SendStatus.Sending, error = null)) }
    fun discard(send: PendingSend) = action("discard:" + send.nonce) {
        require(send.status == SendStatus.Failed) { "Cancel a queued message before discarding it." }
        withContext(Dispatchers.IO) { requireNotNull(database).discard(send.nonce) }
        refresh()
    }
    fun cancelQueued(send: PendingSend) = action("cancel:" + send.nonce) {
        val active = requireNotNull(channel) { "Reconnect to the relay to cancel this message." }
        when (active.cancelQueued(send.nonce)) {
            "cancelled" -> { withContext(Dispatchers.IO) { requireNotNull(database).discard(send.nonce) }; refresh() }
            "delivering" -> savePending(send.copy(status = SendStatus.Delivering))
            else -> throw IOException("Couldn't take the message back. It may have been delivered already.")
        }
    }

    private suspend fun deliver(send: PendingSend) {
        if (!sends.add(send.nonce)) return
        try {
            if (state.value.entries.any { it.botId == send.botId && it.clientNonce == send.nonce }) return
            savePending(send)
            val active = channel
            if (active?.state?.value is LinkState.ComputerOffline && send.files.isEmpty()) {
                savePending(send.copy(status = SendStatus.Waiting))
                try { active.enqueue(send); return }
                catch (error: IOException) {
                    if (error.message != "hostOnline") throw error
                    // Relay presence can lag; use the same nonce after the channel becomes ready.
                }
            }
            val connection = ready()
            val ids = mutableListOf<String>()
            var completed = 0L
            val total = send.files.sumOf { it.size }
            for (file in send.files) {
                val id = UUID.randomUUID().toString()
                HostFiles(connection).upload(send.botId, id, file) { offset ->
                    val percent = if (total == 0L) 100 else ((completed + offset) * 100 / total).toInt()
                    mutableState.update { it.copy(uploadProgress = it.uploadProgress + (send.nonce to percent)) }
                }
                completed += file.size
                ids.add(id)
                withContext(Dispatchers.IO) { attachments.cache(id, file) }
            }
            val result = connection.call("send", buildJsonObject {
                put("botId", send.botId); put("text", send.text); put("clientNonce", send.nonce)
                send.threadId?.let { put("threadId", it) }
                if (ids.isNotEmpty()) put("attachments", JsonArray(ids.map(::JsonPrimitive)))
            })
            val entry = Entry.decode(result.jsonObject.getValue("entry").jsonObject)
            withContext(Dispatchers.IO) { requireNotNull(database).receive(listOf(entry)) }
            refresh()
            val bot = state.value.bots.firstOrNull { it.id == send.botId }
            if (bot != null && PushPreferences.tasks(getApplication()) && getApplication<Application>().getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) {
                val watch = withContext(Dispatchers.IO) { TaskFeed(local.directory).start(bot) }
                NotificationDisplay.task(getApplication(), local.referenceId, state.value.computers.first().id, watch)
                syncPush(); PushDelivery.schedule(getApplication())
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) { savePending(send.copy(status = SendStatus.Failed, error = "Sending was interrupted. Retry uses the same message ID.")) }
            throw error
        } catch (error: Exception) {
            savePending(send.copy(status = SendStatus.Failed, error = error.message ?: "Couldn't send."))
        } finally {
            sends.remove(send.nonce)
            mutableState.update { it.copy(uploadProgress = it.uploadProgress - send.nonce) }
        }
    }

    fun importFiles(uris: List<Uri>, onImported: (List<OutgoingFile>) -> Unit) = action("attachments") {
        val imported = mutableListOf<OutgoingFile>()
        try {
            for (uri in uris) {
                require(uri.scheme == "content") { "Choose a file using Photos or Files." }
                imported.add(attachments.import(uri))
            }
            onImported(imported.toList())
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { imported.forEach(attachments::discard) }
            throw error
        }
    }
    fun removeFile(file: OutgoingFile) = action("remove:" + file.path) { withContext(Dispatchers.IO) { attachments.discard(file) } }

    suspend fun attachment(botId: String, file: Attachment): File {
        val request = contextScope.async { downloadLocks.getOrPut(file.id) { Mutex() }.withLock {
        val destination = withContext(Dispatchers.IO) { attachments.cached(file.id) }
        if (!destination.exists() || destination.length() != file.size) {
            val active = ready()
            HostFiles(active).download(botId, file, destination)
            withContext(Dispatchers.IO) { attachments.trim() }
        }
        destination
        } }
        try { return request.await() } finally { request.cancel() }
    }
    fun shareAttachment(botId: String, file: Attachment) = action("share:" + file.id) {
        val source = attachment(botId, file)
        attachments.share(file, source)
    }

    private suspend fun savePending(send: PendingSend) {
        withContext(Dispatchers.IO) { requireNotNull(database).save(send) }
        refresh()
    }
    private suspend fun reconcileMailbox(active: EncryptedChannel) {
        if (active.route != HostRoute.Relay || state.value.pending.none { it.status in listOf(SendStatus.Waiting, SendStatus.Delivering) }) return
        try {
            val response = active.queued()
            val held = response.getValue("items").jsonArray.associate { it.jsonObject.getValue("nonce").jsonPrimitive.content to it.jsonObject.getValue("state").jsonPrimitive.content }
            for (send in state.value.pending.filter { it.status in listOf(SendStatus.Waiting, SendStatus.Delivering) }) {
                savePending(send.copy(status = when (held[send.nonce]) {
                    "queued" -> SendStatus.Waiting
                    "delivering" -> SendStatus.Delivering
                    else -> SendStatus.Failed
                }))
            }
        } catch (error: CancellationException) { throw error }
        catch (_: IOException) { /* Reconcile when the relay connection recovers. */ }
    }

    fun command(method: String, body: JsonObject = JsonObject(emptyMap()), key: String = method, onSuccess: () -> Unit = {}) = action(key) {
        val active = ready()
        val response = active.call(method, body)
        response.jsonObject["bot"]?.let { value ->
            withContext(Dispatchers.IO) { requireNotNull(database).receive(HostClient.decode(value.jsonObject)) }
        }
        response.jsonObject["entry"]?.let { value ->
            withContext(Dispatchers.IO) { requireNotNull(database).receive(listOf(Entry.decode(value.jsonObject))) }
        }
        refresh()
        onSuccess()
    }

    fun saveBot(draft: BotDraft, onSaved: () -> Unit) = action("editor") {
        val active = ready()
        val result = active.call(if (draft.id == null) "createBot" else "updateBot", draft.body())
        val bot = HostClient.decode(result.jsonObject.getValue("bot").jsonObject)
        withContext(Dispatchers.IO) { requireNotNull(database).receive(bot) }
        refresh()
        openBot(bot.id)
        onSaved()
    }

    fun saveGroup(draft: GroupDraft, onSaved: () -> Unit) = action("editor") {
        val payload = draft.body(state.value.bots)
        val active = ready()
        val result = active.call(if (draft.id == null) "createBot" else "updateBot", payload)
        val bot = HostClient.decode(result.jsonObject.getValue("bot").jsonObject)
        withContext(Dispatchers.IO) { requireNotNull(database).receive(bot) }
        refresh()
        openBot(bot.id)
        onSaved()
    }

    fun deleteBot(bot: Bot, onDeleted: () -> Unit) = action("delete:" + bot.id) {
        val active = ready()
        active.call("deleteBot", body("botId" to bot.id))
        mutableState.update { it.copy(selectedBot = it.selectedBot.takeUnless { id -> id == bot.id }, memories = it.memories - bot.id,
            drafts = it.drafts.filterValues { draft -> draft.botId != bot.id }) }
        persistDrafts()
        onDeleted()
    }

    fun loadMemory(botId: String) = action("memory:" + botId) { receiveMemory(botId) }
    fun forgetMemory(botId: String, factId: String? = null) = action("memory:" + botId) {
        val active = ready()
        active.call(if (factId == null) "clearMemory" else "forgetMemory", buildJsonObject {
            put("botId", botId); factId?.let { put("id", it) }
        })
        receiveMemory(botId)
    }
    private suspend fun receiveMemory(botId: String) {
        val active = ready()
        val response = active.call("memory", body("botId" to botId))
        val memory = MemoryListing.decode(response)
        mutableState.update { it.copy(memories = it.memories + (botId to memory)) }
    }

    fun loadRoutines(botId: String) = action("routines:$botId") { receiveRoutines(botId) }
    fun refreshUsage(force: Boolean = true) = action("usage") {
        val active = ready()
        // The existing host can spend up to a minute reading an installed provider.
        val result = active.call("usage", buildJsonObject { put("refresh", force) }, timeoutMillis = 75_000)
        val response = result.jsonObject
        UsageReport.decode(response)
        withContext(Dispatchers.IO) { requireNotNull(database).apply(MirrorEvent.Usage(response)) }
        refresh()
        widgetOwner?.let { owner ->
            val current = state.value
            withContext(Dispatchers.IO) { WidgetStore.publish(getApplication(), owner, current.computers.firstOrNull(),
                current.bots, UsageReport.decode(current.usage), false, true) }
            CodyncWidgets.update(getApplication())
        }
    }
    private suspend fun receiveRoutines(botId: String) {
        val active = ready()
        val response = active.call("routines", body("botId" to botId))
        val listing = WireJson.decodeFromJsonElement<RoutineListing>(response)
        mutableState.update { it.copy(routines = it.routines + (botId to listing)) }
    }
    fun saveRoutine(botId: String, draft: RoutineDraft, saved: (Routine) -> Unit) = action("routines:$botId") {
        val active = ready()
        val response = active.call("saveRoutine", draft.body(botId))
        val routine = WireJson.decodeFromJsonElement<Routine>(response.jsonObject.getValue("routine"))
        receiveRoutines(botId)
        saved(routine)
    }
    fun routineAction(botId: String, id: String, method: String, enabled: Boolean? = null, done: () -> Unit = {}) = action("routines:$botId") {
        require(method in listOf("setRoutineEnabled", "deleteRoutine", "runRoutine"))
        val active = ready()
        active.call(method, buildJsonObject { put("botId", botId); put("id", id); enabled?.let { put("enabled", it) } })
        receiveRoutines(botId)
        done()
    }
    /** Queries belong to the account runtime as well as their screen; retiring either cancels them. */
    private suspend fun hostQuery(method: String, payload: JsonObject, timeoutMillis: Long = 20_000,
        expectedGeneration: Int = state.value.generation): JsonElement {
        val request = contextScope.async {
            check(state.value.generation == expectedGeneration) { "The active account changed." }
            val active = ready()
            check(state.value.generation == expectedGeneration) { "The active account changed." }
            active.call(method, payload, timeoutMillis = timeoutMillis)
        }
        try { return request.await() } finally { request.cancel() }
    }
    suspend fun agentModels(backend: String): AgentModels {
        val response = hostQuery("agentModels", body("backend" to backend), timeoutMillis = 300_000)
        return WireJson.decodeFromJsonElement(response)
    }
    suspend fun agentAuth(backend: String): AgentAuth {
        val response = hostQuery("agentAuth", body("backend" to backend), 300_000)
        refreshBackends()
        return WireJson.decodeFromJsonElement(response)
    }
    suspend fun agentAuthenticate(backend: String, method: String): AgentAuth {
        val response = hostQuery("agentAuthenticate", body("backend" to backend, "method" to method), 660_000)
        refreshBackends()
        return WireJson.decodeFromJsonElement(response)
    }
    suspend fun setAgentEnv(backend: String, values: Map<String, String>): AgentAuth {
        val response = hostQuery("setAgentEnv", buildJsonObject { put("backend", backend); put("vars", WireJson.encodeToJsonElement(values)) }, 300_000)
        refreshBackends()
        return WireJson.decodeFromJsonElement(response)
    }
    internal fun setupTerminal(owner: String, backend: String, step: String, method: String?): SetupTerminalSession {
        require(step in listOf("install", "login"))
        return setupTerminals.getOrPut(owner) {
            val generation = state.value.generation
            val transport = object : SetupTerminalTransport {
                var last: EncryptedChannel? = null
                suspend fun connection(): EncryptedChannel {
                    check(state.value.generation == generation) { "The active account changed." }
                    val active = ready()
                    last = active
                    return active
                }
                override suspend fun setup(cols: Int, rows: Int): String {
                    val active = connection()
                    val response = active.call("agentSetup", buildJsonObject { put("backend", backend); put("step", step);
                        method?.let { put("method", it) }; put("cols", cols); put("rows", rows) }, 300_000)
                    return response.jsonObject.getValue("term").jsonPrimitive.content
                }
                override suspend fun awaitActive() { foregroundState.first { it } }
                override fun output(term: String): Flow<TerminalEvent> = flow {
                    val active = connection()
                    emitAll(active.subscribe("term", body("term" to term)).map(TerminalEvent::decode))
                }
                override suspend fun input(term: String, bytes: ByteArray) {
                    val active = connection()
                    active.call("termInput", body("term" to term, "data" to java.util.Base64.getEncoder().encodeToString(bytes)), 10_000)
                }
                override suspend fun resize(term: String, cols: Int, rows: Int) {
                    val active = connection()
                    active.call("termResize", buildJsonObject { put("term", term); put("cols", cols); put("rows", rows) }, 10_000)
                }
                override suspend fun close(term: String) { last?.takeIf { it.state.value is LinkState.Ready }?.call("termClose", body("term" to term), 2_000) }
            }
            SetupTerminalSession(contextScope, transport)
        }
    }
    internal fun closeSetupTerminal(owner: String) { setupTerminals.remove(owner)?.close(); refreshBackends() }
    suspend fun listDirs(path: String?): DirListing {
        val response = hostQuery("listDirs", body("path" to path))
        return WireJson.decodeFromJsonElement(response)
    }
    fun refreshPlugins() {
        if ("plugins" in state.value.busy) { pluginsRefreshAgain = true; return }
        action("plugins") {
            do {
                pluginsRefreshAgain = false
                val connectors = hostQuery("connectors", JsonObject(emptyMap()))
                val skills = hostQuery("skills", JsonObject(emptyMap()))
                val listing = PluginListing(WireJson.decodeFromJsonElement(connectors.jsonObject.getValue("items")),
                    WireJson.decodeFromJsonElement(skills.jsonObject.getValue("items")))
                mutableState.update { it.copy(plugins = listing) }
            } while (pluginsRefreshAgain)
        }
    }
    suspend fun marketConnectors(search: String, cursor: String?): MarketConnectors {
        val response = hostQuery("marketConnectors", body("search" to search, "cursor" to cursor.orEmpty()), 60_000)
        return WireJson.decodeFromJsonElement(response)
    }
    suspend fun marketSkills(): List<MarketSkill> {
        val response = hostQuery("marketSkills", JsonObject(emptyMap()), 60_000)
        return WireJson.decodeFromJsonElement(response.jsonObject.getValue("items"))
    }
    suspend fun connectorInfo(name: String): MarketConnector {
        val response = hostQuery("connectorInfo", body("registryName" to name), 60_000)
        return WireJson.decodeFromJsonElement(response)
    }
    suspend fun installedConnectors(): List<InstalledConnector> {
        val response = hostQuery("connectors", JsonObject(emptyMap()), 60_000)
        return WireJson.decodeFromJsonElement(response.jsonObject.getValue("items"))
    }
    suspend fun verifyConnector(id: String) {
        hostQuery("connectorVerify", body("id" to id), 120_000)
        refreshPlugins()
    }
    suspend fun importConnectors(config: String): List<InstalledConnector> {
        val response = hostQuery("importConnectors", body("config" to config), 60_000)
        refreshPlugins()
        return WireJson.decodeFromJsonElement(response.jsonObject.getValue("items"))
    }
    suspend fun credentialStatus(): CredentialStatus {
        val response = hostQuery("credentialStatus", JsonObject(emptyMap()), 60_000)
        return WireJson.decodeFromJsonElement(response)
    }
    suspend fun savedLogins(): List<SavedLogin> {
        val response = hostQuery("credentialLogins", JsonObject(emptyMap()), 60_000)
        return WireJson.decodeFromJsonElement(response.jsonObject.getValue("items"))
    }
    suspend fun saveLogin(site: String, username: String, password: String): SavedLogin {
        val response = hostQuery("credentialSaveLogin", body("site" to site, "username" to username, "password" to password), 60_000)
        return WireJson.decodeFromJsonElement(response.jsonObject.getValue("login"))
    }
    suspend fun removeLogin(id: String) { hostQuery("credentialRemoveLogin", body("id" to id), 60_000) }
    suspend fun onePasswordToken(token: String): CredentialStatus {
        val response = hostQuery("credentialSetOnePassword", body("token" to token), 60_000)
        return WireJson.decodeFromJsonElement(response)
    }
    suspend fun updateConnectorCredentials(connector: InstalledConnector, values: Map<String, String>) {
        val fields = credentialReplacements(connector, values)
        require(fields.isNotEmpty()) { "Enter a value to replace." }
        hostQuery("credentialUpdateConnector", buildJsonObject { put("id", connector.id); put("fields", WireJson.encodeToJsonElement(fields)) }, 120_000)
        refreshPlugins()
    }
    suspend fun composioStatus(): ComposioStatus {
        val response = hostQuery("composioStatus", JsonObject(emptyMap()), 60_000)
        return WireJson.decodeFromJsonElement(response)
    }
    suspend fun composioKey(key: String): ComposioStatus {
        val response = hostQuery("setComposioKey", body("key" to key), 60_000)
        refreshPlugins()
        return WireJson.decodeFromJsonElement(response)
    }
    suspend fun composioApps(search: String, cursor: String?): ComposioApps {
        val response = hostQuery("composioToolkits", body("search" to search, "cursor" to cursor.orEmpty()), 60_000)
        return WireJson.decodeFromJsonElement(response)
    }
    internal fun appConnection(owner: String, slug: String, resumeId: String? = null): AppConnectionSession = appConnections.getOrPut(owner) {
        val generation = state.value.generation
        val transport = object : AppConnectionTransport {
            override suspend fun awaitActive() {
                combine(foregroundState, state) { visible, current ->
                    visible && current.generation == generation && current.actualConnection is LinkState.Ready
                }.first { it }
            }
            override suspend fun start(): ComposioConnect {
                val response = hostQuery("composioConnect", body("toolkit" to slug), 60_000, generation)
                return WireJson.decodeFromJsonElement(response)
            }
            override suspend fun state(id: String): ComposioConnectionState {
                val response = hostQuery("composioConnection", body("id" to id), 30_000, generation)
                return WireJson.decodeFromJsonElement(response)
            }
            override suspend fun submit(mode: String, fields: Map<String, String>): ComposioConnectionState {
                val response = hostQuery("composioConnectFields", buildJsonObject { put("toolkit", slug); put("mode", mode); put("fields", WireJson.encodeToJsonElement(fields)) }, 60_000, generation)
                return WireJson.decodeFromJsonElement(response)
            }
        }
        AppConnectionSession(contextScope, transport, resumeId)
    }
    internal fun closeAppConnection(owner: String) { appConnections.remove(owner)?.close(); refreshPlugins() }
    fun completeConnectionRequest(entryId: String, connectorId: String? = null, value: String? = null,
        username: String? = null, cancel: Boolean = false) = action("connection:$entryId") {
        try {
            finishConnectionRequest(entryId, connectorId, value, username, cancel)
            if (cancel) cancelOAuthPending(entryId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw IOException(if (cancel) "Cancellation wasn't confirmed. Reconnect and check this request."
            else "Connection wasn't confirmed. Check its setup and refresh before trying again.") }
    }
    suspend fun finishConnectionRequest(entryId: String, connectorId: String? = null, value: String? = null,
        username: String? = null, cancel: Boolean = false, expectedGeneration: Int = state.value.generation) {
        // Own the API reply and its mirror write together; retirement joins both.
        val request = contextScope.async {
            check(state.value.generation == expectedGeneration) { "The active account changed." }
            val active = ready()
            check(state.value.generation == expectedGeneration) { "The active account changed." }
            val payload = buildJsonObject { put("entryId", entryId); put("cancel", cancel)
                connectorId?.let { put("connectorId", it) }; value?.let { put("value", it) }; username?.let { put("username", it) } }
            val response = active.call("connectorRequestFinish", payload, 120_000)
            val entry = Entry.decode(response.jsonObject.getValue("entry").jsonObject)
            withContext(Dispatchers.IO) { requireNotNull(database).receive(listOf(entry)) }
            refresh()
        }
        try { request.await() } finally { request.cancel() }
    }
    fun refreshBackends() = action("backends") {
        val response = hostQuery("refreshBackends", JsonObject(emptyMap()), 60_000)
        mutableState.update { it.copy(hello = JsonObject(it.hello.orEmpty() + ("backends" to response.jsonObject.getValue("backends")))) }
    }
    suspend fun installConnector(item: MarketConnector, option: InstallOption, inputs: Map<String, String>): InstalledConnector {
        require(option in item.options && option.complete(inputs)) { "Complete the required connector fields." }
        val response = hostQuery("installConnector", buildJsonObject { put("registryName", item.name); put("option", option.id); put("inputs", WireJson.encodeToJsonElement(inputs)) }, 45_000)
        val connector = WireJson.decodeFromJsonElement<InstalledConnector>(response.jsonObject.getValue("connector"))
        refreshPlugins()
        return connector
    }
    fun pluginAction(method: String, payload: JsonObject, done: () -> Unit = {}) = action("plugin") {
        require(method in listOf("installConnector", "importConnectors", "removeConnector", "installSkill", "removeSkill", "connectorSignOut", "credentialUpdateConnector"))
        hostQuery(method, payload, 120_000)
        refreshPlugins()
        done()
    }
    fun connectorSignIn(id: String, requestId: String? = null) = action("oauth") {
        val generation = state.value.generation
        oauthRequestId = requestId
        val response = hostQuery("connectorSignIn", body("id" to id), 60_000)
        val plan = WireJson.decodeFromJsonElement<ConnectorSignIn>(response)
        val computer = requireNotNull(state.value.computers.firstOrNull())
        externalHttps(plan.url)
        if (plan.callback == "app") {
            val pending = PendingConnectorOAuth.create(plan, id, computer.id, System.currentTimeMillis()).copy(requestId = requestId)
            withContext(Dispatchers.IO) { ConnectorOAuthBook(local.directory).save(pending) }
        } else {
            require(plan.callback == "host") { "This sign-in callback needs a newer app." }
            withContext(Dispatchers.IO) { ConnectorOAuthBook(local.directory).clear() }
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(plan.url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try { getApplication<Application>().startActivity(intent) }
        catch (error: Exception) { withContext(Dispatchers.IO) { ConnectorOAuthBook(local.directory).clear() }; throw IOException("Couldn't open the sign-in browser.", error) }
        if (plan.callback == "host") {
            val complete = withTimeoutOrNull(600_000) {
                while (true) {
                    delay(2_000)
                    combine(foregroundState, state) { visible, current -> visible && current.generation == generation && current.actualConnection is LinkState.Ready }.first { it }
                    val listing = hostQuery("connectors", JsonObject(emptyMap()), 60_000, generation)
                    val connectors = WireJson.decodeFromJsonElement<List<InstalledConnector>>(listing.jsonObject.getValue("items"))
                    if (connectors.any { it.id == id && it.auth == "signedIn" }) {
                        requestId?.let { finishConnectionRequest(it, connectorId = id, expectedGeneration = generation) }
                        refreshPlugins()
                        return@withTimeoutOrNull true
                    }
                }
                @Suppress("UNREACHABLE_CODE") false
            }
            if (complete == null) throw IOException("Sign-in wasn't confirmed. Check the computer and installed connections before trying again.")
        }
    }
    fun cancelConnectorSignIn() = action("cancelOAuth") { cancelOAuthPending(null) }
    fun cancelConnectionRequestSignIn(entryId: String) = action("cancelOAuth") { cancelOAuthPending(entryId) }
    private suspend fun cancelOAuthPending(requestId: String?) {
        val pending = withContext(Dispatchers.IO) { ConnectorOAuthBook(local.directory).read() }
        if (requestId != null && pending?.requestId != requestId && oauthRequestId != requestId) return
        val old = actions["oauth"]
        old?.cancelAndJoin()
        withContext(Dispatchers.IO) { ConnectorOAuthBook(local.directory).clear() }
        oauthRequestId = null
        refreshPlugins()
    }
    fun finishConnectorSignIn(uri: Uri) {
        linkJob?.cancel()
        linkJob = viewModelScope.launch {
            var expectedGeneration: Int? = null
            try {
                val back = OAuthReturn.parse(uri.toString())
                val activated = withTimeout(35_000) { state.first { !it.loading && it.account.ready &&
                    it.contextId == LocalStore.referenceId(it.account.userId ?: "local") } }
                val generation = activated.generation
                expectedGeneration = generation
                val directory = local.directory
                val pending = withContext(Dispatchers.IO) { ConnectorOAuthBook(directory).read() }
                    ?: throw IOException("No connector sign-in is pending for this account. Start sign-in again.")
                pending.validate(back, activated.computers.firstOrNull()?.id.orEmpty(), System.currentTimeMillis())
                val response = hostQuery("connectorSignInFinish", body("state" to back.state, "code" to back.code, "error" to back.error), 60_000, generation)
                val connector = WireJson.decodeFromJsonElement<InstalledConnector>(response.jsonObject.getValue("connector"))
                pending.requestId?.let { finishConnectionRequest(it, connectorId = connector.id, expectedGeneration = generation) }
                withContext(Dispatchers.IO) { ConnectorOAuthBook(directory).clear() }
                if (state.value.generation == generation) { oauthRequestId = null; refreshPlugins(); mutableState.update { it.copy(error = null) } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.update {
                    if (expectedGeneration != null && it.generation != expectedGeneration) it
                    else it.copy(error = "Connector sign-in didn't complete. Reconnect and start sign-in again; the connector remains installed.")
                }
            }
        }
    }
    suspend fun routineSchedule(triggers: List<JsonObject>, zone: String): RoutineSchedulePreview {
        val response = hostQuery("routineSchedule", buildJsonObject { put("triggers", JsonArray(triggers)); put("timeZone", zone) })
        return WireJson.decodeFromJsonElement(response)
    }
    suspend fun routineSchedule(draft: RoutineScheduleDraft): RoutineSchedulePreview {
        val response = hostQuery("routineSchedule", buildJsonObject { put("draft", WireJson.encodeToJsonElement(draft)) })
        return WireJson.decodeFromJsonElement(response)
    }
    suspend fun routineWebhook(botId: String, id: String, rotate: Boolean = false): RoutineWebhook {
        val response = hostQuery("routineWebhook", buildJsonObject { put("botId", botId); put("id", id); put("rotate", rotate) })
        return WireJson.decodeFromJsonElement(response)
    }

    fun respond(entry: Entry, option: String?) = action("permission:" + entry.id) {
        val active = ready()
        active.call("respondPermission", body("entryId" to entry.id, "optionId" to option))
        delay(2_000)
    }

    private suspend fun receiveEntries(response: JsonElement) {
        val entries = response.jsonObject.getValue("entries").jsonArray.map { Entry.decode(it.jsonObject) }
        withContext(Dispatchers.IO) { requireNotNull(database).receive(entries) }
        refresh()
    }

    fun syncPush() = action("push") {
        val active = channel?.takeIf { it.state.value is LinkState.Ready } ?: return@action
        val computer = state.value.computers.firstOrNull() ?: return@action
        try { PushDelivery.sync(getApplication(), local, computer, active) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { PushDelivery.schedule(getApplication()) }
    }
    fun notifications(enabled: Boolean) = action("notificationSettings") {
        PushPreferences.enabled(getApplication(), enabled)
        mutableState.update { it.copy(notifications = enabled) }
        if (!enabled) NotificationDisplay.cancel(getApplication(), 1)
        syncPush(); PushDelivery.schedule(getApplication())
    }
    fun taskNotifications(enabled: Boolean) = action("taskSettings") {
        PushPreferences.tasks(getApplication(), enabled)
        mutableState.update { it.copy(tasks = enabled) }
        if (!enabled) NotificationDisplay.cancel(getApplication(), 2)
        syncPush(); PushDelivery.schedule(getApplication())
    }
    fun openLink(uri: Uri) {
        linkJob?.cancel()
        linkJob = viewModelScope.launch {
            val completed = withTimeoutOrNull(30_000) {
                val activated = state.first { current -> !current.loading &&
                    (current.account.ready || !current.account.configured || current.account.error != null) &&
                    current.contextId == LocalStore.referenceId(current.account.userId ?: "local") }
                val usage = uri.host == "usage"
                val destination = try {
                    if (usage) { val target = UsageDestination.parse(uri.toString()); BotDestination(target.scope, target.computerId, "") }
                    else BotDestination.parse(uri.toString())
                }
                catch (_: Exception) { mutableState.update { it.copy(error = "This notification has an invalid destination.") }; return@withTimeoutOrNull true }
                if (destination.scope != activated.contextId) {
                    mutableState.update { it.copy(error = "Switch to the account used by this notification to open its bot.") }
                    return@withTimeoutOrNull true
                }
                if (destination.computerId != activated.computers.firstOrNull()?.id) {
                    mutableState.update { it.copy(error = "This notification's computer is no longer paired here.") }
                    return@withTimeoutOrNull true
                }
                val generation = activated.generation
                if (usage) {
                    if (state.value.generation == generation) {
                        openBot(null)
                        mutableState.update { it.copy(error = null, navigationRequest = it.navigationRequest + 1, navigationUsage = true) }
                    }
                    return@withTimeoutOrNull true
                }
                if (activated.bots.none { it.id == destination.botId }) {
                    // A cold cache or a newly created bot needs an authoritative current roster.
                    // The query belongs to the activated runtime; account retirement joins it.
                    val request = contextScope.async {
                        val active = ready()
                        val bots = HostClient.bots(active)
                        withContext(Dispatchers.IO) { requireNotNull(database).receive(bots) }
                        refresh()
                    }
                    try { request.await() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        if (state.value.generation == generation) mutableState.update { it.copy(error = "Reconnect to this computer and try opening the notification again.") }
                        return@withTimeoutOrNull true
                    } finally { request.cancel() }
                }
                if (state.value.generation != generation || state.value.contextId != destination.scope) return@withTimeoutOrNull true
                if (state.value.bots.any { it.id == destination.botId && !it.deleted }) {
                    mutableState.update { it.copy(error = null, navigationRequest = it.navigationRequest + 1, navigationUsage = false) }
                    openBot(destination.botId)
                }
                else mutableState.update { it.copy(error = "This notification's bot is no longer available on the computer.") }
                true
            }
            if (completed == null) mutableState.update { it.copy(error = "Couldn't open this notification yet. Reconnect and try again.") }
        }
    }

    private fun action(key: String, operation: suspend () -> Unit) {
        if (state.value.loading || key in state.value.busy) return
        mutableState.update { it.copy(busy = it.busy + key, error = null) }
        val job = contextScope.launch(start = CoroutineStart.LAZY) {
            try { operation() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { showError(error) }
            finally { actions.remove(key); mutableState.update { it.copy(busy = it.busy - key) } }
        }
        actions[key] = job
        job.start()
    }

    private fun showError(error: Exception) { mutableState.update { it.copy(loading = false, error = error.message ?: "Couldn't complete the action.") } }

    private data class WidgetSource(val computer: Computer?, val bots: List<Bot>, val usage: JsonObject, val fresh: Boolean)
    @OptIn(FlowPreview::class)
    private fun observeWidgets(owner: WidgetOwner) {
        contextScope.launch {
            state.filter { !it.loading && it.contextId == owner.scope }
                .map { WidgetSource(it.computers.firstOrNull(), it.bots, it.usage, it.widgetBotsCurrent) }
                .distinctUntilChanged().debounce(350).collect { source ->
                    try {
                        val usage = UsageReport.decode(source.usage)
                        val accepted = withContext(Dispatchers.IO) { WidgetStore.publish(getApplication(), owner, source.computer, source.bots, usage, source.fresh, false) }
                        if (!accepted) return@collect
                        WidgetRefreshWorker.schedule(getApplication(), source.computer != null)
                        CodyncWidgets.update(getApplication())
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { mutableState.update { it.copy(error = "Couldn't update widgets. Open State and try again.") } }
                }
        }
    }

    override fun onCleared() {
        state.value.voice?.id?.let { VoiceService.stop(getApplication(), it) }
        voiceBinding?.session?.end()
        channel?.close()
        val old = database
        val retiring = contextJob
        val book = draftBook
        val drafts = state.value.drafts.values.toList()
        CoroutineScope(Dispatchers.IO).launch {
            retiring.cancelAndJoin()
            try { book?.save(drafts) }
            catch (_: Exception) { /* An already closing activity cannot report a storage error. */ }
            finally { old?.close() }
        }
        super.onCleared()
    }

    private class Resync : IOException()
}

fun body(vararg values: Pair<String, String?>): JsonObject = buildJsonObject {
    for ((key, value) in values) if (value != null) put(key, value)
}
