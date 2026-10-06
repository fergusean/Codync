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
        assertEquals("9ESVKkTXlLyIExxnW7-PIg", local.computers().single().id)
        assertNull(PushPreferences.activeUser(context))
        val store = ViewModelProvider(compose.activity)[AppStore::class.java]
        compose.waitUntil(30_000) { !store.state.value.loading && store.state.value.rosterLoaded &&
            store.state.value.bots.any { it.name == "Android chat acceptance" } }
        val bot = store.state.value.bots.single { it.name == "Android chat acceptance" }
        compose.runOnUiThread { store.openBot(bot.id) }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Conversation actions").fetchSemanticsNodes().size == 1 }
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
        try {
            compose.onNode(hasSetTextAction()).performTextReplacement(mainDraft)
            compose.waitUntil(10_000) { store.state.value.drafts[ComposerDraft.lane(bot.id, null)]?.text == mainDraft }
            fun openThread() {
                compose.onNode(hasText(originalText) and hasClickAction()).performScrollTo().performTouchInput { longClick() }
                compose.onNodeWithText("Reply in thread").performClick()
                compose.waitUntil(30_000) { store.state.value.thread == root.id && "thread:${root.id}" !in store.state.value.busy }
                compose.onNode(isDialog()).assertExists()
                compose.onNodeWithText("Thread").assertIsDisplayed()
                compose.onNodeWithContentDescription("Close thread").assertIsDisplayed()
                compose.onNodeWithContentDescription("Back").assertDoesNotExist()
                compose.onAllNodesWithText(originalText).assertCountEquals(1)
                compose.onNodeWithText(originalText).assertIsDisplayed()
            }
            openThread()
            compose.onNodeWithText("No replies yet").assertIsDisplayed()
            compose.onNodeWithText("Reply…").assertIsDisplayed()
            compose.onNode(hasSetTextAction()).performTextReplacement(replyDraft)
            compose.waitUntil(10_000) { store.state.value.drafts[ComposerDraft.lane(bot.id, root.id)]?.text == replyDraft }
            compose.onNodeWithContentDescription("Close thread").performClick()
            compose.waitUntil(10_000) { store.state.value.thread == null && compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
            compose.onNode(hasSetTextAction()).assertTextEquals(mainDraft)
            openThread()
            compose.onNode(hasSetTextAction()).assertTextEquals(replyDraft)
            val replyText = "Thread reply acceptance " + UUID.randomUUID()
            compose.onNode(hasSetTextAction()).performTextReplacement(replyText)
            compose.onNodeWithContentDescription("Send").performClick()
            compose.waitUntil(30_000) { store.state.value.entries.any { it.botId == bot.id && it.threadId == root.id && it.kind == "permission" && it.status == "pending" } }
            compose.onNodeWithText("Allow once").performScrollTo().performClick()
            compose.waitUntil(30_000) { store.state.value.entries.any { it.botId == bot.id && it.threadId == root.id && it.kind == "agent" && it.isChat } }
            val sent = store.state.value.entries.single { it.botId == bot.id && it.kind == "user" && it.text == replyText }
            assertEquals(root.id, sent.threadId)
            compose.onNodeWithText(replyText).assertIsDisplayed()
            compose.onNodeWithText("No replies yet").assertDoesNotExist()
            compose.onNodeWithText("Reply…").assertIsDisplayed()
            val final = store.state.value.entries.last { it.botId == bot.id && it.threadId == root.id && it.kind == "agent" && it.isChat }
            compose.onNode(hasText(final.text) and hasClickAction()).performTouchInput { longClick() }
            compose.onNodeWithText("Show what it did").performClick()
            compose.onAllNodes(isDialog()).assertCountEquals(2)
            compose.onNodeWithText("Full conversation").assertIsDisplayed()
            compose.onNodeWithContentDescription("Close full conversation").performClick()
            compose.waitUntil(10_000) { !store.state.value.trace && compose.onAllNodes(isDialog()).fetchSemanticsNodes().size == 1 }
            assertEquals(root.id, store.state.value.thread)
            compose.onNodeWithText("Thread").assertIsDisplayed()
            compose.onNodeWithText("Reply…").assertIsDisplayed()
            if (InstrumentationRegistry.getArguments().getString("layoutSnapshots") == "true") {
                val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
                File(context.cacheDir, "thread-live-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            // Native Back dismisses the modal and restores the unchanged main-chat draft.
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(10_000) { store.state.value.thread == null && compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
            compose.onNode(hasSetTextAction()).assertTextEquals(mainDraft)
            compose.onNodeWithText(replyText).assertDoesNotExist()
            val count = store.state.value.entries.single { it.id == root.id }.data.getValue("thread").jsonObject.getValue("count").jsonPrimitive.int
            compose.onNode(hasText("$count replies") and hasAnyAncestor(hasText(originalText)))
                .performScrollTo().performClick()
            compose.waitUntil(10_000) { store.state.value.thread == root.id }
            compose.onNodeWithText(originalText).assertIsDisplayed()
            compose.onNodeWithContentDescription("Close thread").performClick()
            compose.waitUntil(10_000) { store.state.value.thread == null }
        } finally {
            compose.runOnUiThread { store.openBot(bot.id); store.editDraft(bot.id, null, ""); store.editDraft(bot.id, root.id, "") }
        }
    }
}
