package com.codync.android

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.codync.android.core.*
import com.codync.android.design.CodyncTheme
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class RepliesPopoverTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val bot = Bot("thread-preview", "Scout")
    private val root = entry("root", 1, "user", "Please check the connection.")

    private fun entry(id: String, seq: Long, kind: String, text: String, thread: String? = null) =
        Entry(id, seq, bot.id, threadId = thread, rev = seq, kind = kind, data = buildJsonObject { put("text", text); put("final", true) })
    private fun fixture(entries: List<Entry>, busy: Set<String> = emptySet()) = AppState(loading = false,
        selectedBot = bot.id, thread = root.id, bots = listOf(bot), entries = entries, busy = busy,
        historyComplete = setOf(bot.id), connection = LinkState.Ready(HostRoute.Direct), actualConnection = LinkState.Ready(HostRoute.Direct))

    @Test fun emptyThreadShowsTheOriginalBeforeNoRepliesYetInAModal() {
        assumeTrue(Build.PRODUCT.startsWith("sdk"))
        val store = ViewModelProvider(compose.activity)[AppStore::class.java]
        val state = fixture(listOf(root, entry("main-later", 2, "agent", "A later main-chat message."),
            entry("other-thread", 3, "agent", "A reply in a different thread.", "different-root")))
        compose.runOnUiThread { compose.activity.setContent { CodyncTheme { ChatScreen(state, store, {}) } } }
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText("Thread").assertIsDisplayed()
        compose.onNodeWithText(bot.name).assertIsDisplayed()
        compose.onNodeWithContentDescription("Close thread").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNodeWithContentDescription("Conversation actions").assertDoesNotExist()
        compose.onNodeWithText("A later main-chat message.").assertDoesNotExist()
        compose.onNodeWithText("A reply in a different thread.").assertDoesNotExist()
        compose.onNodeWithText("Start a conversation").assertDoesNotExist()
        compose.onAllNodesWithText(root.text).assertCountEquals(1)
        val original = compose.onNodeWithText(root.text).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithText("No replies yet").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(original.bottom <= label.top)
        compose.onNodeWithText("Reply…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Voice chat").assertDoesNotExist()
        if (InstrumentationRegistry.getArguments().getString("layoutSnapshots") == "true") {
            val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
            File(compose.activity.cacheDir, "thread-empty-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(compose.activity.cacheDir, "thread-empty-screen.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNode(hasText(root.text) and hasClickAction()).performTouchInput { longClick() }
        compose.onNodeWithText("Copy").assertIsDisplayed()
        compose.onNodeWithText("Reply in thread").assertDoesNotExist()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("Copy").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close thread").assertIsDisplayed()
    }

    @Test fun repliesFollowTheOriginalAndKeepASpecificComposer() {
        assumeTrue(Build.PRODUCT.startsWith("sdk"))
        val store = ViewModelProvider(compose.activity)[AppStore::class.java]
        val user = entry("reply-user", 2, "user", "Try the saved connection.", root.id)
        val agent = entry("reply-agent", 3, "agent", "The saved connection works.", root.id)
        compose.runOnUiThread { compose.activity.setContent { CodyncTheme { ChatScreen(fixture(listOf(root, user, agent)), store, {}) } } }
        val original = compose.onNodeWithText(root.text).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithText("2 replies").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val reply = compose.onNodeWithText(user.text).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(original.bottom <= label.top)
        assertTrue(label.bottom <= reply.top)
        compose.onNodeWithText(agent.text).assertIsDisplayed()
        compose.onNodeWithText("No replies yet").assertDoesNotExist()
        compose.onNodeWithText("Reply…").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
    }

    @Test fun loadingRepliesKeepsTheOriginalAndDoesNotClaimAnEmptyThread() {
        assumeTrue(Build.PRODUCT.startsWith("sdk"))
        val store = ViewModelProvider(compose.activity)[AppStore::class.java]
        val state = mutableStateOf(fixture(listOf(root), setOf("thread:${root.id}")))
        compose.runOnUiThread { compose.activity.setContent { CodyncTheme { ChatScreen(state.value, store, {}) } } }
        compose.onNodeWithText(root.text).assertIsDisplayed()
        compose.onNodeWithText("Loading replies…").assertIsDisplayed()
        compose.onNodeWithText("No replies yet").assertDoesNotExist()
        compose.runOnUiThread { state.value = state.value.copy(busy = emptySet()) }
        compose.onNodeWithText("No replies yet").assertIsDisplayed()
        compose.runOnUiThread { state.value = state.value.copy(thread = null, generation = state.value.generation + 1) }
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun longOriginalScrollsWhileTheTitleAndReplyComposerStayVisibleWithTheKeyboard() {
        assumeTrue(Build.PRODUCT.startsWith("sdk"))
        val store = ViewModelProvider(compose.activity)[AppStore::class.java]
        val text = (1..40).joinToString("\n\n") { "Original paragraph $it: Keep the original reply context available." }
        val state = mutableStateOf(fixture(listOf(root.copy(data = buildJsonObject { put("text", text) }))))
        val closed = mutableStateOf(false)
        compose.runOnUiThread { compose.activity.setContent { CodyncTheme {
            if (!closed.value) RepliesPopover(close = { closed.value = true; state.value = state.value.copy(thread = null) }, back = { it() }) {
                close -> ConversationScreen(state.value, store, {}, close)
            }
        } } }
        compose.onNodeWithText("Original paragraph 1: Keep the original reply context available.", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("No replies yet").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Thread").assertIsDisplayed()
        val composer = compose.onNode(hasSetTextAction())
        val before = composer.fetchSemanticsNode().layoutInfo.coordinates.localToScreen(Offset.Zero).y
        composer.performTouchInput { click() }
        compose.waitUntil(10_000) { composer.fetchSemanticsNode().layoutInfo.coordinates.localToScreen(Offset.Zero).y < before - 100f }
        compose.onNodeWithText("Reply…").assertIsDisplayed()
        compose.onNodeWithText("Thread").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close thread").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        assertTrue(closed.value)
    }

    @Test fun outsideTapAndGrabberSwipeDismissTheOverlay() {
        assumeTrue(Build.PRODUCT.startsWith("sdk"))
        val showing = mutableStateOf(true)
        compose.runOnUiThread { compose.activity.setContent { CodyncTheme {
            if (showing.value) RepliesPopover(close = { showing.value = false }, back = { it() }) { androidx.compose.material3.Text("Dismissal preview") }
        } } }
        val panel = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Thread"))
        panel.assertIsDisplayed()
        val node = panel.fetchSemanticsNode()
        val origin = node.layoutInfo.coordinates.localToScreen(Offset.Zero)
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, origin.x + node.size.width / 2, origin.y - 8f, 0)
        val up = MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, origin.x + node.size.width / 2, origin.y - 8f, 0)
        try {
            InstrumentationRegistry.getInstrumentation().sendPointerSync(down)
            InstrumentationRegistry.getInstrumentation().sendPointerSync(up)
        } finally { down.recycle(); up.recycle() }
        compose.waitUntil(5_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        assertFalse(showing.value)
        compose.runOnUiThread { showing.value = true }
        panel.assertIsDisplayed()
        val density = compose.activity.resources.displayMetrics.density
        panel.performTouchInput { swipe(Offset(center.x, 11f * density), Offset(center.x, 131f * density), durationMillis = 250) }
        compose.waitUntil(5_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        assertFalse(showing.value)
    }
}
