package com.codync.android

import android.graphics.Bitmap
import android.os.Build
import android.view.KeyEvent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.codync.android.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Isolated emulator host only: exercise the real reply entry point, drafts and wire routing. */
class LiveThreadTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun replyPopoverKeepsItsOriginalAndDraftsAndSendsToTheThread() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveHost") == "true")
        assumeTrue(Build.PRODUCT.startsWith("sdk"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val local = LocalStore(context)
        requireFixtureHost(local.computers().single().id)
        assertNull(PushPreferences.activeUser(context))
        val store = ViewModelProvider(compose.activity)[AppStore::class.java]
        compose.waitUntil(30_000) { !store.state.value.loading && store.state.value.rosterLoaded &&
            store.state.value.bots.any { it.name == "Android chat acceptance" } }
        val bot = store.state.value.bots.single { it.name == "Android chat acceptance" }
        compose.runOnUiThread { store.openBot(bot.id) }
        compose.waitUntil(10_000) { compose.onAllNodes(chatMenu).fetchSemanticsNodes().size == 1 }
        val originalText = "Thread original acceptance " + UUID.randomUUID()
        compose.onNode(hasSetTextAction()).performTextReplacement(originalText)
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Allow once").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Allow once").performScrollTo().performClick()
        var original: Entry? = null
        compose.waitUntil(30_000) {
            original = store.state.value.entries.firstOrNull { it.botId == bot.id && it.kind == "user" && it.text == originalText }
            val root = original
            root != null && store.state.value.entries.any { it.botId == bot.id && it.turn == root.turn && it.threadId == null && it.kind == "agent" && it.isChat }
        }
        val root = requireNotNull(original)
        val mainDraft = "Unsent main-chat draft " + UUID.randomUUID()
        val replyDraft = "Unsent thread draft " + UUID.randomUUID()
        // The thread is a sheet over the chat, which stays underneath: its own copies come last.
        val threadOpen = hasContentDescription("Close thread")
        fun threadComposer() = compose.onAllNodes(hasSetTextAction()).onLast()
        try {
            compose.onNode(hasSetTextAction()).performTextReplacement(mainDraft)
            compose.waitUntil(10_000) { store.state.value.drafts[ComposerDraft.lane(bot.id, null)]?.text == mainDraft }
            fun openThread() {
                compose.onNode(hasText(originalText) and hasClickAction()).performScrollTo().performTouchInput { longClick() }
                compose.onNodeWithText("Reply in thread").performClick()
                compose.waitUntil(30_000) { store.state.value.thread == root.id && compose.onAllNodes(threadOpen).fetchSemanticsNodes().size == 1 }
                compose.onNodeWithText("Thread").assertIsDisplayed()
                compose.onAllNodesWithText(originalText).onLast().assertIsDisplayed()
            }
            openThread()
            compose.onNodeWithText("No replies yet").assertIsDisplayed()
            compose.onNode(hasSetTextAction() and hasText("Reply…")).performTextReplacement(replyDraft)
            compose.waitUntil(10_000) { store.state.value.drafts[ComposerDraft.lane(bot.id, root.id)]?.text == replyDraft }
            compose.onNodeWithContentDescription("Close thread").performClick()
            compose.waitUntil(10_000) { store.state.value.thread == null && compose.onAllNodes(threadOpen).fetchSemanticsNodes().isEmpty() }
            compose.onNode(hasSetTextAction() and hasText(mainDraft)).assertIsDisplayed()
            openThread()
            compose.onNode(hasSetTextAction() and hasText(replyDraft)).assertIsDisplayed()
            val replyText = "Thread reply acceptance " + UUID.randomUUID()
            threadComposer().performTextReplacement(replyText)
            compose.onAllNodesWithContentDescription("Send").onLast().performClick()
            compose.waitUntil(30_000) { store.state.value.entries.any { it.botId == bot.id && it.threadId == root.id && it.kind == "permission" && it.status == "pending" } }
            compose.onAllNodesWithText("Allow once").onLast().performScrollTo().performClick()
            compose.waitUntil(30_000) { store.state.value.entries.any { it.botId == bot.id && it.threadId == root.id && it.kind == "agent" && it.isChat } }
            val sent = store.state.value.entries.single { it.botId == bot.id && it.kind == "user" && it.text == replyText }
            assertEquals(root.id, sent.threadId)
            compose.onNodeWithText(replyText).assertIsDisplayed()
            compose.onNodeWithText("No replies yet").assertDoesNotExist()
            compose.onNode(hasSetTextAction() and hasText("Reply…")).assertIsDisplayed()
            val final = store.state.value.entries.last { it.botId == bot.id && it.threadId == root.id && it.kind == "agent" && it.isChat }
            compose.onAllNodes(hasText(final.text) and hasClickAction()).onLast().performTouchInput { longClick() }
            compose.onNodeWithText("Show what it did").performClick()
            compose.onNodeWithText("Full conversation").assertIsDisplayed()
            compose.onNodeWithContentDescription("Close").performClick()
            compose.waitUntil(10_000) { !store.state.value.trace && compose.onAllNodesWithText("Full conversation").fetchSemanticsNodes().isEmpty() }
            assertEquals(root.id, store.state.value.thread)
            compose.onNodeWithText("Thread").assertIsDisplayed()
            if (InstrumentationRegistry.getArguments().getString("layoutSnapshots") == "true") {
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                File(context.cacheDir, "thread-live-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            // Native Back dismisses the sheet and restores the unchanged main-chat draft.
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(10_000) { store.state.value.thread == null && compose.onAllNodes(threadOpen).fetchSemanticsNodes().isEmpty() }
            compose.onNode(hasSetTextAction() and hasText(mainDraft)).assertIsDisplayed()
            compose.onNodeWithText(replyText).assertDoesNotExist()
            val count = store.state.value.entries.single { it.id == root.id }.data.getValue("thread").jsonObject.getValue("count").jsonPrimitive.int
            compose.onNodeWithContentDescription("View thread, $count ${if (count == 1) "reply" else "replies"}", substring = true)
                .performScrollTo().performClick()
            compose.waitUntil(10_000) { store.state.value.thread == root.id }
            compose.onAllNodesWithText(originalText).onLast().assertIsDisplayed()
            compose.onNodeWithContentDescription("Close thread").performClick()
            compose.waitUntil(10_000) { store.state.value.thread == null }
        } finally {
            compose.runOnUiThread { store.openBot(bot.id); store.editDraft(bot.id, null, ""); store.editDraft(bot.id, root.id, "") }
        }
    }
}
