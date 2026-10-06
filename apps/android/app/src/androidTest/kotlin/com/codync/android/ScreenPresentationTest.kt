package com.codync.android

import android.graphics.Bitmap
import android.content.res.Configuration
import android.os.Build
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.codync.android.core.*
import com.codync.android.design.CodyncTheme
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercise the real presenters and Android window Back dispatch using read-only fixtures. */
class ScreenPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val bot = Bot("presentation-fixture", "Scout")
    private fun fixture() = AppState(contextId = "presentation-fixture", loading = false,
        setupComplete = true, rosterLoaded = true, bots = listOf(bot),
        computers = listOf(Computer("fixture-computer", "Fixture computer", "fixture-key")),
        account = AccountState(ready = true, configured = true, multiple = true, userId = "fixture-account",
            accounts = listOf(SavedAccount("fixture-account", "fixture-session", "alex@example.test", null))))

    @Before fun isolatedEmulatorOnly() { assumeTrue(Build.PRODUCT.startsWith("sdk")) }

    @Test fun accountNavigationAndNestedPairingReturnToTheRetainedRoster() {
        val store = ViewModelProvider(compose.activity)[AppStore::class.java]
        val state = fixture()
        compose.runOnUiThread { compose.activity.setContent { CodyncTheme { CodyncApp(state, {}, {}, {}, {}, {}, store) } } }
        compose.onNodeWithContentDescription("Accounts").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText("Account").assertIsDisplayed()
        compose.onNodeWithText(bot.name).assertDoesNotExist()
        snapshot("account-sheet")
        compose.onNodeWithText("Computers").performClick()
        compose.onNodeWithText("Computers & settings").assertIsDisplayed()
        back()
        compose.onNodeWithText("Account").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close account").assertIsDisplayed()
        compose.onNodeWithText("Computers").performClick()
        compose.onNodeWithText("Pair a computer").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Close pairing").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close account").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("Pairing link")).performTextInput("draft-link")
        compose.onNodeWithText("Scan pairing code").performClick()
        compose.onNodeWithText("Use pairing link").assertIsDisplayed()
        back()
        compose.onNode(hasSetTextAction() and hasText("Pairing link")).assert(hasText("draft-link"))
        compose.onNodeWithContentDescription("Close pairing").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close account").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Computers & settings").assertIsDisplayed()
        back()
        compose.onNodeWithText("Account").assertIsDisplayed()
        back()
        compose.waitUntil(5_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(bot.name).assertIsDisplayed()
        compose.onNodeWithContentDescription("Accounts").assertIsDisplayed()
    }

    @Test fun settingsCloseRetainsTheConversationAndGenerationRetiresAnOldDraft() {
        val store = ViewModelProvider(compose.activity)[AppStore::class.java]
        val state = mutableStateOf(fixture().copy(selectedBot = bot.id, historyComplete = setOf(bot.id)))
        compose.runOnUiThread { compose.activity.setContent { CodyncTheme { CodyncApp(state.value, {}, {}, {}, {}, {}, store) } } }
        compose.chatAction("Edit")
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Save").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Conversation actions").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasContentDescription("Name")).performTextReplacement("Discard this draft")
        snapshot("bot-settings-sheet")
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Conversation actions").assertIsDisplayed()
        compose.chatAction("Edit")
        compose.onNode(hasSetTextAction() and hasContentDescription("Name")).assert(hasText(bot.name))
        compose.runOnUiThread { state.value = state.value.copy(generation = state.value.generation + 1) }
        compose.waitUntil(5_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Conversation actions").assertIsDisplayed()
    }

    @Test fun largeTextKeepsSettingsActionsAndCompactOptionsWithinTheSheet() {
        val draft = mutableStateOf(BotDraft(id = bot.id, name = bot.name, permission = "auto", cwd = "/projects/A-long-project-name"))
        compose.runOnUiThread { compose.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                CodyncTheme { CodyncSheet("Settings", {}) { close ->
                    BotEditorForm(fixture(), draft.value, { draft.value = it }, close, {}, {},
                        { AgentModels(emptyList()) }, {}, {}, {}, modal = true)
                } }
            }
        } }
        val title = compose.onNodeWithText("Settings").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithContentDescription("Save").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithContentDescription("Close").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(title.right < save.left && save.right <= close.left)
        listOf("Agent", "Workspace", "Permissions").forEach { label ->
            val row = compose.onNodeWithContentDescription(label).performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val panel = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
            assertTrue(row.left >= panel.left && row.right <= panel.right)
        }
        compose.onNodeWithContentDescription("Notifications").performScrollTo().assertIsOn().performClick().assertIsOff()
        snapshot("bot-settings-large-text")
    }

    @Test fun darkThemeKeepsTheProfileAndSettingsReadableInTheSheet() {
        val settings = mutableStateOf(false)
        val draft = mutableStateOf(BotDraft(id = bot.id, name = bot.name))
        val state = fixture().copy(actualConnection = LinkState.Ready(HostRoute.Direct),
            plugins = PluginListing(emptyList(), emptyList()), hello = buildJsonObject {
                put("backends", buildJsonArray { add(buildJsonObject {
                    put("id", "claude"); put("name", "Claude"); put("available", true)
                }) })
            })
        compose.runOnUiThread { compose.activity.setContent {
            // SDK images lock system night mode; override only this read-only preview.
            val deviceConfiguration = LocalConfiguration.current
            val darkConfiguration = remember(deviceConfiguration) { Configuration(deviceConfiguration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            } }
            CompositionLocalProvider(LocalConfiguration provides darkConfiguration) { CodyncTheme {
                if (!settings.value) CodyncSheet("Account", { settings.value = true }) { close ->
                    AccountScreenContent(state, AccountScreenActions(), close, inPopover = true)
                } else CodyncSheet("Settings", {}) { close ->
                    BotEditorForm(state, draft.value, { draft.value = it }, close, {}, {},
                        { AgentModels(listOf(AgentModel("sonnet", "Sonnet"))) }, {}, {}, {}, modal = true)
                }
            } }
        } }
        compose.onNodeWithText("Account").assertIsDisplayed()
        compose.onNodeWithText("alex@example.test").assertIsDisplayed()
        compose.onNodeWithText("Computers").assertIsDisplayed()
        snapshot("account-sheet-dark")
        compose.onNodeWithContentDescription("Close account").performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Bot avatar").assertIsDisplayed()
        compose.onNodeWithContentDescription("Save").assertIsEnabled()
        snapshot("bot-settings-sheet-dark")
        compose.onNodeWithContentDescription("Permissions").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Notifications").performScrollTo().assertIsOn()
        snapshot("bot-settings-options-dark")
    }

    private fun back() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) }
    private fun snapshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("layoutSnapshots") != "true") return
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
