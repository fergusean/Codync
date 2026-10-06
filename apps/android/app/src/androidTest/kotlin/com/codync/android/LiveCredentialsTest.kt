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
import java.util.UUID

/** Opt-in, account-free, disposable host only. The caller seeds requests through its local token. */
class LiveCredentialsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun savedSignInsAndImportedConnectorCredentialsStayOnTheHost() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCredentials") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNull(PushPreferences.activeUser(context))
        val local = LocalStore(context)
        val computer = local.computers().single()
        assertEquals("9ESVKkTXlLyIExxnW7-PIg", computer.id)
        val route = computer.copy(urls = listOf("http://10.0.2.2:29322"), route = ConnectionRoute.DirectOnly)
        val channel = runBlocking { HostConnector.connect(route, local.identity()) }
        val original = runBlocking { HostClient.bots(channel) }
        val suffix = UUID.randomUUID().toString().take(8)
        val site = "android-$suffix.fixture.invalid"
        val name = "Android credentials $suffix"
        try {
            val store = ViewModelProvider(compose.activity)[AppStore::class.java]
            compose.waitUntil(30_000) { !store.state.value.loading && store.state.value.actualConnection is LinkState.Ready }
            compose.runOnUiThread { store.openBot(null) }
            compose.computerAction("Marketplace")
            compose.onNodeWithText("Credentials").performScrollTo().performClick()
            field("Website or app").performTextReplacement(site)
            field("Username or email").performTextReplacement("fixture-user")
            field("Password or op:// reference").performTextReplacement("  fixture-password  ")
            compose.onNodeWithText("Save sign-in").performScrollTo().performClick()
            compose.waitUntil(20_000) { listedLogins(channel).any { it.site == site } }
            val saved = listedLogins(channel).single { it.site == site }
            assertEquals("fixture-user", saved.username)
            assertFalse(WireJson.encodeToJsonElement(saved).toString().contains("fixture-password"))
            compose.onNodeWithText("Remove sign-in for $site").performScrollTo().performClick()
            compose.onNodeWithText("Keep sign-in").performScrollTo().performClick()
            assertTrue(listedLogins(channel).any { it.id == saved.id })
            compose.onNodeWithText("Remove sign-in for $site").performScrollTo().performClick()
            compose.onNodeWithText("Confirm remove sign-in").performScrollTo().performClick()
            compose.waitUntil(20_000) { listedLogins(channel).none { it.id == saved.id } }
            compose.onControl("Back").performScrollTo().performClick()
            compose.onNodeWithText("Installed plugins").performScrollTo().performClick()
            compose.onNodeWithText("Import connectors").performScrollTo().performClick()
            val fixturePath = requireNotNull(InstrumentationRegistry.getArguments().getString("credentialMcp"))
            assertTrue(fixturePath.endsWith("/Codync/host/tests/fixtures/credential_mcp.py"))
            val config = buildJsonObject { putJsonObject("mcpServers") { putJsonObject(name) {
                put("command", "/opt/homebrew/bin/python3.13"); put("args", JsonArray(listOf(JsonPrimitive(fixturePath))))
                putJsonObject("env") { put("API_KEY", "test-private-key"); put("PRESERVED", "fixture-preserved") }
            } } }.toString()
            field("MCP config JSON").performTextReplacement(config)
            compose.onNodeWithText("Import MCP config").performScrollTo().performClick()
            compose.waitUntil(30_000) { store.state.value.plugins?.connectors?.any { it.name == name } == true }
            compose.onNodeWithText("Verify $name").performScrollTo().performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("$name verified.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Credentials").performScrollTo().performClick()
            compose.onNodeWithText("Edit credentials for $name").performScrollTo().performClick()
            field("API_KEY").performTextReplacement("test-private-key")
            compose.onNodeWithText("Save credentials").performScrollTo().performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Edit credentials for $name").fetchSemanticsNodes().isNotEmpty() }
            val listing = runBlocking { channel.call("connectors") }
            val connector = WireJson.decodeFromJsonElement<List<InstalledConnector>>(listing.jsonObject.getValue("items")).single { it.name == name }
            assertEquals(setOf("API_KEY", "PRESERVED"), connector.keys.toSet())
            assertFalse(listing.toString().contains("test-private-key"))
            assertFalse(listing.toString().contains("fixture-preserved"))
            assertTrue(runCatching { runBlocking { channel.call("connectorRuntime", body("id" to connector.id)) } }.isFailure)
        } finally {
            runBlocking {
                val logins = channel.call("credentialLogins")
                for (login in WireJson.decodeFromJsonElement<List<SavedLogin>>(logins.jsonObject.getValue("items")).filter { it.site == site })
                    channel.call("credentialRemoveLogin", body("id" to login.id))
                val plugins = channel.call("connectors")
                for (connector in WireJson.decodeFromJsonElement<List<InstalledConnector>>(plugins.jsonObject.getValue("items")).filter { it.name == name })
                    channel.call("removeConnector", body("id" to connector.id))
                val remaining = HostClient.bots(channel)
                for (bot in original.filter { previous -> remaining.any { it.id == previous.id } }) channel.call("updateBot", buildJsonObject {
                    put("id", bot.id); put("connectors", WireJson.encodeToJsonElement(bot.connectors)); put("skills", WireJson.encodeToJsonElement(bot.skills))
                })
            }
            channel.close()
        }
    }

    @Test fun connectionCardsSaveAndCancelWithoutPuttingPasswordsInChat() {
        val args = InstrumentationRegistry.getArguments()
        val botId = args.getString("credentialBot")
        assumeTrue(botId != null && args.getString("liveCredentials") == "true")
        val savedRequest = requireNotNull(args.getString("loginRequest"))
        val cancelledRequest = requireNotNull(args.getString("cancelRequest"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNull(PushPreferences.activeUser(context))
        val local = LocalStore(context)
        val computer = local.computers().single()
        assertEquals("9ESVKkTXlLyIExxnW7-PIg", computer.id)
        val route = computer.copy(urls = listOf("http://10.0.2.2:29322"), route = ConnectionRoute.DirectOnly)
        val channel = runBlocking { HostConnector.connect(route, local.identity()) }
        try {
            val store = ViewModelProvider(compose.activity)[AppStore::class.java]
            compose.waitUntil(30_000) { store.state.value.bots.any { it.id == botId } && store.state.value.entries.any { it.id == savedRequest } }
            assertTrue(store.state.value.bots.single { it.id == botId }.name.startsWith("Android credentials acceptance"))
            compose.runOnUiThread { store.openBot(botId) }
            cardField(cancelledRequest, "Password or op:// reference").performTextReplacement("fixture-cancel-never-sent")
            cardAction(cancelledRequest, "Cancel connection request").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Cancellation wasn't confirmed. Reconnect and check this request.").assertDoesNotExist()
            compose.waitUntil(20_000) { store.state.value.entries.any { it.id == cancelledRequest && it.data["connectionRequest"]?.jsonObject?.get("status")?.jsonPrimitive?.content == "cancelled" } }
            cardField(savedRequest, "Username or email").performTextReplacement("fixture-card-user")
            cardField(savedRequest, "Password or op:// reference").performTextReplacement("fixture-card-password")
            cardAction(savedRequest, "Save login").performClick()
            compose.waitUntil(20_000) { store.state.value.entries.any { it.id == savedRequest && it.data["connectionRequest"]?.jsonObject?.get("status")?.jsonPrimitive?.content == "ready" } }
            runBlocking { store.finishConnectionRequest(savedRequest, value = "fixture-card-password", username = "fixture-card-user") }
            val history = runBlocking { channel.call("history", body("botId" to botId)) }
            val entries = history.jsonObject.getValue("entries").jsonArray
            assertEquals(1, entries.count { it.jsonObject["id"]?.jsonPrimitive?.content == "connection-ack-$savedRequest" })
            assertFalse(history.toString().contains("fixture-card-password"))
            assertFalse(history.toString().contains("fixture-cancel-never-sent"))
            val publicLogins = runBlocking { channel.call("credentialLogins") }
            assertFalse(publicLogins.toString().contains("fixture-card-password"))
            assertFalse(publicLogins.toString().contains("fixture-cancel-never-sent"))
        } finally { channel.close() }
    }

    private fun listedLogins(channel: EncryptedChannel): List<SavedLogin> = runBlocking {
        val response = channel.call("credentialLogins")
        WireJson.decodeFromJsonElement(response.jsonObject.getValue("items"))
    }
    private fun field(label: String): SemanticsNodeInteraction = compose.onNode(hasSetTextAction() and hasText(label)).performScrollTo()
    private fun cardField(id: String, label: String): SemanticsNodeInteraction = compose.onNode(
        hasSetTextAction() and hasText(label) and hasAnyAncestor(hasTestTag("connection:$id"))).performScrollTo()
    private fun cardAction(id: String, label: String): SemanticsNodeInteraction = compose.onNode(
        hasClickAction() and hasText(label) and hasAnyAncestor(hasTestTag("connection:$id"))).performScrollTo()
}
