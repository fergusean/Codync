package com.codync.android

import android.graphics.Bitmap
import android.os.Build
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.platform.app.InstrumentationRegistry
import com.codync.android.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** A thread opens in a sheet over the chat, which stays put underneath. */
class RepliesPopoverTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val bot = Bot("thread-preview", "Scout")
    private val root = entry("root", 1, "user", "Please check the connection.")

    private fun entry(id: String, seq: Long, kind: String, text: String, thread: String? = null, replies: Int = 0) =
        Entry(id, seq, bot.id, threadId = thread, rev = seq, kind = kind, data = buildJsonObject {
            put("text", text); put("final", true)
            if (replies > 0) put("thread", buildJsonObject { put("count", replies) })
        })
    private fun fixture(entries: List<Entry>) = AppState(loading = false,
        selectedBot = bot.id, thread = root.id, bots = listOf(bot), entries = entries,
        historyComplete = setOf(bot.id), connection = LinkState.Ready(HostRoute.Direct), actualConnection = LinkState.Ready(HostRoute.Direct))
    private fun render(state: AppState) = compose.setContent { Hosted { ChatScreen(state, viewModel(), {}) } }

    @Before fun isolatedEmulatorOnly() { assumeTrue(Build.PRODUCT.startsWith("sdk")) }

    @Test fun emptyThreadShowsTheOriginalBeforeNoRepliesYet() {
        render(fixture(listOf(root, entry("other-thread", 3, "agent", "A reply in a different thread.", "different-root"))))
        compose.onNodeWithText("Thread").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close thread").assertIsDisplayed()
        compose.onNodeWithText("A reply in a different thread.").assertDoesNotExist()
        val original = compose.onAllNodesWithText(root.text).onLast().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithText("No replies yet").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(original.bottom <= label.top)
        compose.onNode(hasSetTextAction() and hasText("Reply…")).assertIsDisplayed()
        if (InstrumentationRegistry.getArguments().getString("layoutSnapshots") == "true") {
            val screen = instrumentation.uiAutomation.takeScreenshot()
            File(instrumentation.targetContext.cacheDir, "thread-empty-screen.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // In the thread the original offers no "Reply in thread" of its own.
        compose.onAllNodes(hasText(root.text) and hasClickAction()).onLast().performTouchInput { longClick() }
        compose.onNodeWithText("Copy").assertIsDisplayed()
        compose.onNodeWithText("Reply in thread").assertDoesNotExist()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("Copy").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close thread").assertIsDisplayed()
    }

    @Test fun repliesFollowTheOriginalInOrder() {
        val user = entry("reply-user", 2, "user", "Try the saved connection.", root.id)
        val agent = entry("reply-agent", 3, "agent", "The saved connection works.", root.id)
        render(fixture(listOf(entry(root.id, 1, "user", root.text, replies = 2), user, agent)))
        val original = compose.onAllNodesWithText(root.text).onLast().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithText("2 replies").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val reply = compose.onNodeWithText(user.text).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(original.bottom <= label.top)
        assertTrue(label.bottom <= reply.top)
        compose.onNodeWithText(agent.text).assertIsDisplayed()
        compose.onNodeWithText("No replies yet").assertDoesNotExist()
    }

    @Test fun longOriginalScrollsWhileTheTitleAndReplyComposerStayVisibleWithTheKeyboard() {
        val text = (1..40).joinToString("\n\n") { "Original paragraph $it: Keep the original reply context available." }
        render(fixture(listOf(entry(root.id, 1, "agent", text))))
        compose.onNodeWithText("No replies yet").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Thread").assertIsDisplayed()
        val composer = compose.onNode(hasSetTextAction() and hasText("Reply…"))
        val before = composer.fetchSemanticsNode().layoutInfo.coordinates.localToScreen(Offset.Zero).y
        composer.performTouchInput { click() }
        compose.waitUntil(10_000) { composer.fetchSemanticsNode().layoutInfo.coordinates.localToScreen(Offset.Zero).y < before - 100f }
        compose.onNodeWithText("Thread").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close thread").assertIsDisplayed()
    }
}
