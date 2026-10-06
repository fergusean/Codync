package com.codync.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.codync.android.core.*
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

/** App-owned preferences are excluded from backup with the rest of the application. */
object PushPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("codync-push", Context.MODE_PRIVATE)
    fun activeUser(context: Context): String? = prefs(context).getString("user", null)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", true)
    fun tasks(context: Context) = prefs(context).getBoolean("tasks", true)
    fun asked(context: Context) = prefs(context).getBoolean("asked", false)
    fun markAsked(context: Context) { prefs(context).edit().putBoolean("asked", true).apply() }
    fun fid(context: Context) = prefs(context).getString("fid", null)
    fun fid(context: Context, token: String) { prefs(context).edit().putString("fid", token).apply() }
    fun active(context: Context, user: String?) = synchronized(this) { prefs(context).edit().putString("user", user).putBoolean("retired", false).commit() }
    fun retired(context: Context) = prefs(context).getBoolean("retired", false)
    fun retire(context: Context) = synchronized(this) { prefs(context).edit().putBoolean("retired", true).commit() }
    fun enabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("enabled", enabled).commit() }
    fun tasks(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("tasks", enabled).commit() }
    fun reset(context: Context) {
        val token = fid(context)
        prefs(context).edit().clear().putString("fid", token).commit()
    }
}

object PushDelivery {
    private val gate = Mutex()
    @Volatile var foreground = false

    suspend fun retire(context: Context) {
        PushPreferences.retire(context)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        gate.withLock { context.getSystemService(NotificationManager::class.java).cancelAll() }
    }
    suspend fun activate(context: Context, user: String?) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        gate.withLock { PushPreferences.active(context, user) }
    }

    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun sync(context: Context, local: LocalStore, computer: Computer, active: EncryptedChannel) = gate.withLock {
        syncLocked(context, local, computer, active)
    }

    private suspend fun syncLocked(context: Context, local: LocalStore, computer: Computer, active: EncryptedChannel) {
        if (PushPreferences.retired(context) || local.contextId != (PushPreferences.activeUser(context) ?: "local")) return
        val permitted = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val feed = TaskFeed(local.directory)
        if (!PushPreferences.tasks(context) || !permitted) {
            feed.all().forEach { active.call("unregisterActivity", body("botId" to it.botId)) }
            feed.clear()
        }
        if (!PushPreferences.enabled(context) || !permitted) {
            active.call("unregisterDevice")
            if (!permitted || !PushPreferences.tasks(context) || feed.all().none { !it.finished }) return
        }
        val token = PushPreferences.fid(context) ?: suspendCancellableCoroutine<String> { continuation ->
            FirebaseMessaging.getInstance().register().addOnSuccessListener {
                FirebaseInstallations.getInstance().id.addOnSuccessListener { value ->
                    PushPreferences.fid(context, value)
                    continuation.resumeWith(Result.success(value))
                }.addOnFailureListener { error -> continuation.resumeWith(Result.failure(error)) }
            }.addOnFailureListener { error -> continuation.resumeWith(Result.failure(error)) }
        }
        val identity = withContext(Dispatchers.IO) { local.identity() }
        if (PushPreferences.enabled(context)) {
            val ticket = PushRelay.ticket(BuildConfig.PUSH_RELAY_URL, token, local.referenceId, computer.id)
            currentCoroutineContext().ensureActive()
            active.call("registerDevice", buildJsonObject {
                put("ticket", ticket); put("relay", BuildConfig.PUSH_RELAY_URL); put("name", Build.MODEL.take(100))
                put("pushKey", identity.pushKey); put("ctx", local.referenceId)
            })
        }
        if (PushPreferences.tasks(context)) for (watch in feed.all().filter { !it.finished }) {
            val activity = PushRelay.ticket(BuildConfig.PUSH_RELAY_URL, token, local.referenceId, computer.id, watch.botId, watch.taskId)
            currentCoroutineContext().ensureActive()
            active.call("registerActivity", body("botId" to watch.botId, "ticket" to activity))
        }
    }

    suspend fun background(context: Context) = gate.withLock {
        if (PushPreferences.retired(context)) return@withLock
        val local = LocalStore(context, PushPreferences.activeUser(context) ?: "local")
        val computers = withContext(Dispatchers.IO) { local.computers() }
        val computer = computers.firstOrNull() ?: return@withLock
        if (!local.hasIdentity()) return@withLock
        var active: EncryptedChannel? = null
        try {
            withTimeout(45_000) {
                val identity = withContext(Dispatchers.IO) { local.identity() }
                val connected = HostConnector.connect(computer, identity)
                active = connected
                connected.state.firstReady()
                syncLocked(context, local, computer, connected)
            }
        } finally { active?.close() }
    }

    const val WORK_NAME = "codync-push-registration"
}

class PushRegistrationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (PushPreferences.retired(applicationContext)) return Result.success()
        try {
            PushDelivery.background(applicationContext)
            return Result.success()
        } catch (error: CancellationException) {
            if (error !is TimeoutCancellationException) throw error
            return if (runAttemptCount < 8) Result.retry() else Result.failure()
        } catch (error: HostError) {
            return if (error.status in listOf(403, 426)) Result.failure() else Result.retry()
        } catch (_: Exception) { return if (runAttemptCount < 8) Result.retry() else Result.failure() }
    }
}

private suspend fun kotlinx.coroutines.flow.StateFlow<LinkState>.firstReady() {
    val result = first { it is LinkState.Ready || it is LinkState.ComputerOffline || it is LinkState.Failed }
    if (result !is LinkState.Ready) throw java.io.IOException("Computer unavailable")
}

class PushService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        val changed = PushPreferences.fid(this) != installationId
        PushPreferences.fid(this, installationId)
        if (changed) PushDelivery.schedule(this)
    }
    override fun onMessageReceived(message: RemoteMessage) { receive(this, message.data) }

    companion object {
        /** Local, bounded work only: notification text is never fetched from the network. */
        fun receive(context: Context, data: Map<String, String>) {
            synchronized(PushPreferences) {
            try {
                if (PushPreferences.retired(context)) return
                val user = PushPreferences.activeUser(context)
                val scope = LocalStore.referenceId(user ?: "local")
                if (data["ctx"] != scope || data["kind"] !in setOf("alert", "task")) return
                val local = LocalStore(context, user ?: "local")
                val computerId = data["computerId"] ?: return
                if (local.computers().none { it.id == computerId }) return
                val botId = data["botId"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) } ?: return
                if (!context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) return
                NotificationDisplay.channels(context)
                if (data["kind"] == "task") {
                    if (!PushPreferences.tasks(context)) return
                    val watch = TaskFeed(local.directory).remote(data, botId) ?: return
                    NotificationDisplay.task(context, scope, computerId, watch)
                    return
                }
                if (!PushPreferences.enabled(context) || PushDelivery.foreground) return
                var title = "ZC Codync"
                var text = "A bot has an update."
                try {
                    if (local.hasIdentity()) {
                        val sealed = requireNotNull(data["sealed"]).takeIf { it.length <= 8192 } ?: return
                        val plain = local.identity().openPush(sealed, computerId)
                        val alert = Json.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject
                        title = alert.getValue("title").jsonPrimitive.content.take(120)
                        text = alert.getValue("body").jsonPrimitive.content.take(400)
                    }
                } catch (_: Exception) { /* Locked/unavailable key: show only a generic fallback. */ }
                NotificationDisplay.alert(context, scope, computerId, botId, title, text)
            } catch (_: Exception) { /* A malformed push cannot crash the Firebase service. */ }
            }
        }
    }
}

internal object NotificationDisplay {
    const val ALERTS = "codync-alerts"
    const val TASKS = "codync-tasks"
    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(ALERTS, "Bot updates", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(TASKS, "Delegated tasks", NotificationManager.IMPORTANCE_LOW))
    }
    private fun builder(context: Context, scope: String, computer: String, bot: String, channel: String): NotificationCompat.Builder {
        val url = Uri.Builder().scheme("codync").authority("bot").appendPath(bot).appendQueryParameter("scope", scope).appendQueryParameter("computer", computer).build()
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(url)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val generic = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ZC Codync").setContentText("A bot has an update.").build()
        return NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification).setContentIntent(pending)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(generic).setGroup("$scope/$computer")
    }
    fun alert(context: Context, scope: String, computer: String, bot: String, title: String, text: String) {
        channels(context)
        val notification = builder(context, scope, computer, bot, ALERTS).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_MESSAGE).build()
        try { context.getSystemService(NotificationManager::class.java).notify("$scope/$computer/$bot", 1, notification) } catch (_: SecurityException) { }
    }
    fun task(context: Context, scope: String, computer: String, watch: TaskWatch) {
        channels(context)
        val text = when (watch.status) { "working" -> "Working…"; "needsInput" -> "Needs your approval"; "error" -> "Could not finish"; else -> "Done" }
        val notification = builder(context, scope, computer, watch.botId, TASKS).setContentTitle(watch.name).setContentText(text)
            .setOngoing(!watch.finished).setOnlyAlertOnce(true).setAutoCancel(watch.finished)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS).setTimeoutAfter(if (watch.finished) 60_000 else maxOf(1, watch.deadline - System.currentTimeMillis())).build()
        try { context.getSystemService(NotificationManager::class.java).notify("$scope/$computer/${watch.botId}", 2, notification) } catch (_: SecurityException) { }
    }
    fun cancel(context: Context, id: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.id == id }.forEach { manager.cancel(it.tag, it.id) }
    }
}
