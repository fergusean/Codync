package com.codync.android

import androidx.test.platform.app.InstrumentationRegistry
import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues
import com.codync.android.core.*
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class MirrorDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.noBackupFilesDir, "mirror-test-" + UUID.randomUUID()).apply { mkdirs() }
    private val computer = "AAAAAAAAAAAAAAAAAAAAAA"
    private var mirror = MirrorDatabase(context, directory, computer)
    private fun entry(rev: Long, text: String = "new", nonce: String? = null): Entry = Entry("message", 1, "bot", rev = rev, kind = "user",
        data = buildJsonObject { put("text", text); nonce?.let { put("clientNonce", it) } })

    @After fun cleanup() { mirror.close(); directory.deleteRecursively() }

    @Test fun rpcRepliesAndHelloCannotCheckpointPastUnseenEvents() {
        mirror.apply(MirrorEvent.Hello("database", 100, JsonObject(emptyMap()), null))
        mirror.receive(listOf(entry(90)))
        assertEquals(0, mirror.snapshot().rev)
        mirror.apply(MirrorEvent.EntryChanged(entry(2, "older")))
        assertEquals(2, mirror.snapshot().rev)
        assertEquals("new", mirror.snapshot().entries.single().text)
    }

    @Test fun anInitialRosterCannotSkipEarlierTranscriptEventsAndBatchesCommitTogether() {
        mirror.receive(listOf(Bot("bot", "Current roster", rev = 500)))
        assertEquals(0, mirror.snapshot().rev)
        mirror.save(PendingSend("nonce", "bot", text = "message", createdAt = 1))
        mirror.apply(listOf(MirrorEvent.BotChanged(Bot("bot", "Older roster", rev = 2)),
            MirrorEvent.EntryChanged(entry(3, nonce = "nonce"))))
        mirror.close()
        mirror = MirrorDatabase(context, directory, computer)
        val snapshot = mirror.snapshot()
        assertEquals(3, snapshot.rev)
        assertEquals("Current roster", snapshot.bots.single().name)
        assertEquals("nonce", snapshot.entries.single().clientNonce)
        assertTrue(snapshot.pending.isEmpty())
    }

    @Test fun cursorAndSendReconciliationSurviveReopeningTogether() {
        mirror.save(PendingSend("nonce", "bot", text = "message", createdAt = 1))
        mirror.apply(MirrorEvent.EntryChanged(entry(7, nonce = "nonce")))
        mirror.close()
        mirror = MirrorDatabase(context, directory, computer)
        assertEquals(7, mirror.snapshot().rev)
        assertTrue(mirror.snapshot().pending.isEmpty())
        assertEquals("nonce", mirror.snapshot().entries.single().clientNonce)
    }

    @Test fun tombstonesCannotBeResurrectedByLateRepliesOrHistory() {
        mirror.apply(MirrorEvent.BotChanged(Bot("bot", "Bot", rev = 2)))
        mirror.apply(MirrorEvent.EntryChanged(entry(3)))
        mirror.apply(MirrorEvent.BotChanged(Bot("bot", rev = 4, deleted = true)))
        mirror.receive(Bot("bot", "Old", rev = 2))
        mirror.receive(listOf(entry(3)))
        assertTrue(mirror.snapshot().bots.isEmpty())
        assertTrue(mirror.snapshot().entries.isEmpty())
        assertEquals(4, mirror.snapshot().rev)
    }

    @Test fun incompatibleCacheAndDatabaseReplacementRewindSafely() {
        mirror.setStamp("old", "2.4/2.4")
        mirror.apply(MirrorEvent.EntryChanged(entry(9)))
        mirror.setStamp("old", "2.5/2.4")
        assertEquals(0, mirror.snapshot().rev)
        assertEquals(1, mirror.snapshot().entries.size)
        mirror.setStamp("new", "2.5/2.4")
        assertEquals(0, mirror.snapshot().rev)
        assertTrue(mirror.snapshot().entries.isEmpty())
    }

    @Test fun interruptedSendRetainsItsNonceAndRequiresAnExplicitRetry() {
        mirror.save(PendingSend("stable", "bot", text = "message", createdAt = 1))
        mirror.close()
        mirror = MirrorDatabase(context, directory, computer)
        mirror.recoverInterrupted()
        assertEquals("stable", mirror.snapshot().pending.single().nonce)
        assertEquals(SendStatus.Failed, mirror.snapshot().pending.single().status)
    }

    @Test fun aLateSendFailureCannotResurrectAnAlreadyConfirmedMessage() {
        val send = PendingSend("stable", "bot", text = "message", createdAt = 1)
        mirror.save(send)
        mirror.apply(MirrorEvent.EntryChanged(entry(3, nonce = send.nonce)))
        mirror.save(send.copy(status = SendStatus.Failed))
        assertTrue(mirror.snapshot().pending.isEmpty())
        assertEquals(1, mirror.snapshot().entries.size)
    }

    @Test fun upgradingTheMirrorRetainsMessagesAndBackfillsNonceReconciliation() {
        mirror.close()
        val path = File(directory, "mirror-$computer.db")
        path.delete()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT NOT NULL)")
            db.execSQL("CREATE TABLE bots(id TEXT PRIMARY KEY, rev INTEGER NOT NULL, deleted INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE entries(id TEXT PRIMARY KEY, bot_id TEXT NOT NULL, thread_id TEXT, seq INTEGER NOT NULL, rev INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE pending(nonce TEXT PRIMARY KEY, created INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE history(lane TEXT PRIMARY KEY)")
            db.insertOrThrow("entries", null, ContentValues().apply {
                put("id", "message"); put("bot_id", "bot"); put("seq", 1); put("rev", 3)
                put("payload", WireJson.encodeToString(entry(3, nonce = "stable")))
            })
            db.version = 1
        }
        mirror = MirrorDatabase(context, directory, computer)
        assertEquals("stable", mirror.snapshot().entries.single().clientNonce)
        mirror.save(PendingSend("stable", "bot", text = "message", createdAt = 1))
        assertTrue(mirror.snapshot().pending.isEmpty())
    }
}
