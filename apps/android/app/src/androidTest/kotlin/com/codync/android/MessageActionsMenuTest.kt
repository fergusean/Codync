package com.codync.android

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
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

class MessageActionsMenuTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val menu = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Message actions")

    @Test fun holdingTheMiddleOfALongMessageShowsOptionsWithoutMovingTheConversation() {
        assumeTrue(Build.PRODUCT.startsWith("sdk"))
        val store = ViewModelProvider(compose.activity)[AppStore::class.java]
        val bot = Bot("long-message-preview", "Scout")
        val paragraphs = (1..60).map { "Paragraph $it: A longer reply with enough detail to extend beyond the screen." }
        val text = paragraphs.joinToString("\n\n") + "\n\n```kotlin\nval result = checkConnection()\n```"
        val entry = Entry("long-reply", 1, bot.id, rev = 1, kind = "agent", data = buildJsonObject {
            put("text", text); put("final", true)
        })
        val initial = AppState(loading = false, selectedBot = bot.id, bots = listOf(bot),
            connection = LinkState.Ready(HostRoute.Direct), historyComplete = setOf(bot.id), entries = listOf(entry))
        val state = mutableStateOf(initial)
        compose.runOnUiThread { compose.activity.setContent { CodyncTheme { ChatScreen(state.value, store, {}) } } }
        val paragraph = compose.onNodeWithText(paragraphs[29], useUnmergedTree = true)
        paragraph.performScrollTo().assertIsDisplayed()
        val bubble = compose.onNode(hasText(paragraphs[29]) and hasClickAction())
        val size = bubble.fetchSemanticsNode().size
        val position = bubble.fetchSemanticsNode().positionInRoot
        val composer = compose.onNodeWithContentDescription("Attachments").fetchSemanticsNode().boundsInRoot
        paragraph.performTouchInput { longClick() }
        compose.onNode(menu).assertIsDisplayed()
        compose.onNodeWithContentDescription("React 👍").assertIsDisplayed()
        compose.onNodeWithText("Copy").assertIsDisplayed()
        compose.onNodeWithText("Reply in thread").assertIsDisplayed()
        compose.onNodeWithText("Show what it did").assertIsDisplayed()
        val viewport = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).fetchSemanticsNode()
        val viewportOrigin = viewport.layoutInfo.coordinates.localToScreen(Offset.Zero)
        val popup = compose.onNode(menu).fetchSemanticsNode()
        val popupOrigin = popup.layoutInfo.coordinates.localToScreen(Offset.Zero)
        assertTrue(popupOrigin.x >= viewportOrigin.x)
        assertTrue(popupOrigin.y >= viewportOrigin.y)
        assertTrue(popupOrigin.x + popup.size.width <= viewportOrigin.x + viewport.size.width)
        assertTrue(popupOrigin.y + popup.size.height <= viewportOrigin.y + viewport.size.height)
        assertEquals(size, bubble.fetchSemanticsNode().size)
        assertEquals(position, bubble.fetchSemanticsNode().positionInRoot)
        assertEquals(composer, compose.onNodeWithContentDescription("Attachments").fetchSemanticsNode().boundsInRoot)
        if (InstrumentationRegistry.getArguments().getString("layoutSnapshots") == "true") {
            val bitmap = compose.onNode(menu).captureToImage().asAndroidBitmap()
            File(compose.activity.cacheDir, "message-actions-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(compose.activity.cacheDir, "message-actions-screen.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithText("Copy").performClick()
        compose.onNode(menu).assertDoesNotExist()
        compose.runOnIdle { assertEquals(text, compose.activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()) }

        // Code blocks use the same message menu, rather than intercepting the hold for selection.
        val code = compose.onNodeWithText("val result = checkConnection()", useUnmergedTree = true)
        code.performScrollTo().performTouchInput { longClick() }
        compose.onNode(menu).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNode(menu).assertDoesNotExist()

        // Accessibility has no touch coordinates; use the visible part of the bubble.
        bubble.performSemanticsAction(SemanticsActions.OnLongClick) { assertTrue(it()) }
        compose.onNode(menu).assertIsDisplayed()
        // A retired account/session cannot keep the old message's actions alive.
        compose.runOnUiThread { state.value = initial.copy(generation = initial.generation + 1) }
        compose.onNode(menu).assertDoesNotExist()
        compose.runOnUiThread { compose.activity.getSystemService(ClipboardManager::class.java).clearPrimaryClip() }
    }

    @Test fun reactionsAndOptionsDismissTheMenuAndKeepTheirCallbacks() {
        assumeTrue(Build.PRODUCT.startsWith("sdk"))
        val invoked = mutableListOf<String>()
        val busy = mutableStateOf(false)
        var screenViewport = Rect.Zero
        compose.runOnUiThread { compose.activity.setContent { CodyncTheme {
            var viewport by remember { mutableStateOf(Rect.Zero) }
            Box(Modifier.fillMaxSize().safeDrawingPadding().onGloballyPositioned {
                viewport = it.boundsInWindow()
                val origin = it.localToScreen(Offset.Zero)
                screenViewport = Rect(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height)
            }) {
                MessageActionBubble(viewport, true, listOf("👍"), busy.value, { invoked.add(it) },
                    { invoked.add("thread") }, { invoked.add("trace") }, { invoked.add("copy") }, Modifier.padding(top = 80.dp)) {
                    Text("Action callback preview")
                }
            }
        } } }
        val bubble = compose.onNodeWithText("Action callback preview")
        bubble.performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Remove reaction 👍").assertIsSelected().performClick()
        compose.onNode(menu).assertDoesNotExist()
        assertEquals(listOf("👍"), invoked)
        for ((label, event) in listOf("Reply in thread" to "thread", "Show what it did" to "trace", "Copy" to "copy")) {
            bubble.performClick()
            compose.onNodeWithText(label).performClick()
            compose.onNode(menu).assertDoesNotExist()
            assertEquals(event, invoked.last())
        }
        compose.runOnUiThread { busy.value = true }
        bubble.performClick()
        compose.onNodeWithContentDescription("React ❤️").assertIsNotEnabled()
        val count = invoked.size
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNode(menu).assertDoesNotExist()
        assertEquals(count, invoked.size)

        bubble.performClick()
        compose.onNode(menu).assertIsDisplayed()
        // Tap the actual screen outside the popup; it dismisses without invoking any action.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, screenViewport.center.x, screenViewport.bottom - 16f, 0)
        val up = MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, screenViewport.center.x, screenViewport.bottom - 16f, 0)
        try { instrumentation.sendPointerSync(down); instrumentation.sendPointerSync(up) }
        finally { down.recycle(); up.recycle() }
        compose.waitUntil(5_000) { compose.onAllNodes(menu).fetchSemanticsNodes().isEmpty() }
        compose.onNode(menu).assertDoesNotExist()
        assertEquals(count, invoked.size)
    }

    @Test fun linksAndScrollingRetainTheirOwnGestures() {
        assumeTrue(Build.PRODUCT.startsWith("sdk"))
        val urls = mutableListOf<String>()
        compose.runOnUiThread { compose.activity.setContent { CodyncTheme {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { urls.add(uri) }
            }) {
                var viewport by remember { mutableStateOf(Rect.Zero) }
                Column(Modifier.fillMaxSize().safeDrawingPadding().onGloballyPositioned { viewport = it.boundsInWindow() }) {
                    MessageActionBubble(viewport, true, emptyList(), false, {}, null, null, {}) {
                        MarkdownText("[Visit Codync](https://codync.dev)", selectable = false)
                    }
                }
            }
        } } }
        val link = compose.onNodeWithText("Visit Codync", useUnmergedTree = true)
        link.performTouchInput { click() }
        compose.onNode(menu).assertDoesNotExist()
        assertEquals(listOf("https://codync.dev"), urls)
        link.performTouchInput { longClick() }
        compose.onNode(menu).assertIsDisplayed()
        assertEquals(1, urls.size)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        link.performTouchInput { swipe(start = center, end = center - Offset(0f, 200f)) }
        compose.onNode(menu).assertDoesNotExist()
        assertEquals(1, urls.size)
    }

    @Test fun largeTextInASmallViewportKeepsAllActionsReachable() {
        assumeTrue(Build.PRODUCT.startsWith("sdk"))
        var openedTrace = false
        compose.runOnUiThread { compose.activity.setContent { CodyncTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                var viewport by remember { mutableStateOf(Rect.Zero) }
                Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 96.dp)) {
                    Box(Modifier.width(260.dp).height(240.dp).onGloballyPositioned { viewport = it.boundsInWindow() }) {
                        MessageActionBubble(viewport, true, emptyList(), false, {}, {}, { openedTrace = true }, {}) {
                            Text("Small viewport preview")
                        }
                    }
                }
            }
        } } }
        compose.onNodeWithText("Small viewport preview").performTouchInput { longClick() }
        compose.onNode(menu).assertIsDisplayed()
        compose.onNodeWithContentDescription("React ✅").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Copy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reply in thread").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Show what it did").performScrollTo().assertIsDisplayed().performClick()
        compose.onNode(menu).assertDoesNotExist()
        assertTrue(openedTrace)
    }
}
