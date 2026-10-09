package com.codync.android

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

class ChatLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun botExchangesShowACompactRowThatOpensTheConversationSheet() {
        assumeTrue(Build.PRODUCT.startsWith("sdk"))
        val store = ViewModelProvider(compose.activity)[AppStore::class.java]
        val bot = Bot("egan", "Egan")
        val peer = Bot("owen", "Owen", avatarColor = "green")
        val body = "Urgent. Investigate the connection error."
        val answer = "The caller cancelled while audio was connecting."
        val entry = Entry("bot-message", 1, bot.id, rev = 1, kind = "notice", data = buildJsonObject {
            val heading = "Asked Owen: $body"
            put("text", "$heading\nReply from Owen:\n$answer"); put("heading", heading); put("status", "completed")
            put("delegationId", "request"); put("sourceBotId", bot.id); put("targetBotId", peer.id)
            put("botMessage", buildJsonObject { put("sourceBotId", bot.id); put("targetBotId", peer.id); put("text", body); put("reply", answer) })
        })
        val state = AppState(loading = false, selectedBot = bot.id, bots = listOf(bot, peer),
            connection = LinkState.Ready(HostRoute.Direct), actualConnection = LinkState.Ready(HostRoute.Direct),
            historyComplete = setOf(bot.id), entries = listOf(entry))
        compose.runOnUiThread { compose.activity.setContent { CodyncTheme { ChatScreen(state, store, {}) } } }
        compose.onNodeWithText("Messaged").assertIsDisplayed()
        compose.onNodeWithText("Owen").assertIsDisplayed()
        compose.onNodeWithText(body).assertDoesNotExist()
        compose.onNode(hasText("Messaged") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(body).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(answer).assertIsDisplayed()
        compose.onNodeWithContentDescription("Close conversation").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(body).fetchSemanticsNodes().isEmpty() }
        if (InstrumentationRegistry.getArguments().getString("layoutSnapshots") == "true") {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(compose.activity.cacheDir, "bot-exchange-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun aShortConversationStaysNearItsComposerAndRetainsContextActions() {
        assumeTrue(Build.PRODUCT.startsWith("sdk"))
        val store = ViewModelProvider(compose.activity)[AppStore::class.java]
        val bot = Bot("layout-preview", "Scout", avatarColor = "green", avatarShape = "cloud")
        val sent = "Add a weekly totals helper and check its tests."
        val reply = "I added the helper and a test. All four tests pass."
        val now = System.currentTimeMillis()
        val state = AppState(loading = false, selectedBot = bot.id, bots = listOf(bot),
            connection = LinkState.Ready(HostRoute.Direct), actualConnection = LinkState.Ready(HostRoute.Direct),
            historyComplete = setOf(bot.id), entries = listOf(
                Entry("user-layout", 1, bot.id, rev = 1, kind = "user", data = buildJsonObject { put("text", sent) }, createdAt = now),
                Entry("agent-layout", 2, bot.id, rev = 1, kind = "agent", data = buildJsonObject { put("text", reply); put("author", bot.id); put("final", true) }, createdAt = now)))
        compose.runOnUiThread { compose.activity.setContent { CodyncTheme { ChatScreen(state, store, {}) } } }
        compose.onNodeWithText("You").assertDoesNotExist()
        compose.onNodeWithText("Message actions").assertDoesNotExist()
        compose.onNodeWithText("Reply in thread").assertDoesNotExist()
        compose.onAllNodesWithText(bot.name).assertCountEquals(1)
        val user = compose.onNode(hasText(sent) and hasClickAction())
        val agent = compose.onNode(hasText(reply) and hasClickAction())
        val userBounds = user.fetchSemanticsNode().boundsInRoot
        val agentBounds = agent.fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(userBounds.left > agentBounds.left)
        assertTrue(userBounds.right > agentBounds.right)
        assertTrue(userBounds.top > root.height / 2)
        compose.onNodeWithContentDescription("Attachments").assertIsDisplayed()
        compose.onNodeWithContentDescription("Voice chat").assertIsDisplayed()
        if (InstrumentationRegistry.getArguments().getString("layoutSnapshots") == "true") {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(compose.activity.cacheDir, "layout-chat-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        user.performTouchInput { longClick(Offset(4f, 4f)) }
        compose.onNodeWithText("Reply in thread").assertIsDisplayed()
        compose.onNodeWithContentDescription("React 👍").assertIsDisplayed()
        compose.onNodeWithText("Copy").assertIsDisplayed()
        compose.onNodeWithText("Show what it did").assertDoesNotExist()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("Copy").assertDoesNotExist()
        compose.onNodeWithContentDescription("Conversation actions").performClick()
        compose.onNodeWithText("Edit").assertIsDisplayed()
        compose.onNodeWithText("Routines").assertIsDisplayed()
        compose.onNodeWithText("Full conversation").assertIsDisplayed()
    }
}
