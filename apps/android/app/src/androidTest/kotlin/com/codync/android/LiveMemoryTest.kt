package com.codync.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.codync.android.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** The caller seeds and deletes a disposable bot on the isolated fake-agent host. */
class LiveMemoryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun forgettingFactsAndClearingMemoryPreserveTheConversation() {
        val botId = InstrumentationRegistry.getArguments().getString("memoryBot")
        assumeTrue(botId != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val local = LocalStore(context)
        val computer = local.computers().single().copy(urls = listOf("http://10.0.2.2:29322"), route = ConnectionRoute.DirectOnly)
        val channel = runBlocking { HostConnector.connect(computer, local.identity()) }
        try {
            val before = runBlocking { channel.call("history", body("botId" to botId)) }
            val store = ViewModelProvider(compose.activity)[AppStore::class.java]
            compose.waitUntil(30_000) { store.state.value.bots.any { it.id == botId } }
            assertTrue(store.state.value.bots.single { it.id == botId }.name.startsWith("Android memory acceptance"))
            compose.runOnUiThread { store.openBot(botId) }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Conversation actions").fetchSemanticsNodes().size == 1 }
            compose.chatAction("Edit")
            compose.waitUntil(30_000) { store.state.value.memories[botId]?.facts?.size == 2 }
            compose.onNodeWithText("Android memory profile acceptance").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("Forget Android memory profile acceptance").performScrollTo().performClick()
            compose.waitUntil(30_000) { store.state.value.memories[botId]?.facts?.size == 1 }
            assertEquals("Android memory history acceptance", store.state.value.memories[botId]?.facts?.single()?.content)
            compose.onNodeWithText("Forget everything").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Forget everything").fetchSemanticsNodes().size == 2 }
            compose.onAllNodesWithText("Forget everything").onLast().performScrollTo().performClick()
            compose.waitUntil(30_000) { store.state.value.memories[botId]?.facts?.isEmpty() == true }
            compose.onNodeWithText("Nothing yet. The bot remembers who you are and what you work on as you chat.").performScrollTo().assertIsDisplayed()
            val after = runBlocking { channel.call("history", body("botId" to botId)) }
            assertEquals(before.jsonObject.getValue("entries"), after.jsonObject.getValue("entries"))
            val memory = runBlocking { channel.call("memory", body("botId" to botId)) }
            assertTrue(MemoryListing.decode(memory).facts.isEmpty())
        } finally { channel.close() }
    }
}
