package com.codync.android

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.codync.android.core.*
import com.codync.android.design.CodyncTheme
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only UI fixtures. Production accounts and bot storage are never changed. */
class BotEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsShowsVisualAvatarControlsBeforeLabeledFieldsAndCompactOptions() {
        assumeTrue(Build.PRODUCT.startsWith("sdk"))
        val draft = mutableStateOf(BotDraft(id = "fixture", name = "Scout"))
        render(draft)
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Save").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        val avatar = compose.onNodeWithContentDescription("Bot avatar").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val name = field("Name").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val instructions = field("Standing instructions").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val bounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(avatar.center.x - bounds.center.x) < 2f)
        assertTrue(avatar.bottom < name.top && name.bottom < instructions.top)
        compose.onNodeWithContentDescription("blob shape").assertIsSelected()
        compose.onNodeWithText("Custom command").assertDoesNotExist()
        compose.onNodeWithContentDescription("Agent").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Agent").performClick()
        compose.onNodeWithText("Custom command").assertIsDisplayed()
        compose.onNodeWithText("Codex (not installed)").assertIsDisplayed()
        compose.onNodeWithText("Custom command").performClick()
        field("ACP command").performScrollTo().performTextReplacement("my-agent --acp")
        assertEquals("my-agent --acp", draft.value.command)
        compose.onNodeWithContentDescription("Agent").performClick()
        compose.onNodeWithText("Claude").performClick()
        field("ACP command").assertDoesNotExist()
        assertNull(draft.value.body()["command"]?.jsonPrimitive?.contentOrNull)
        compose.onNodeWithContentDescription("Bot avatar").performScrollTo()
        snapshot("bot-editor-settings-preview.png")
    }

    @Test fun newBotsStayUnnamedAndTheirVisualAvatarSelectionIsSavedInTheDraft() {
        assumeTrue(Build.PRODUCT.startsWith("sdk"))
        val draft = mutableStateOf(BotDraft())
        var saved: BotDraft? = null
        render(draft, save = { saved = draft.value })
        compose.onNodeWithText("New bot").assertIsDisplayed()
        field("Name").assertDoesNotExist()
        compose.onNodeWithContentDescription("Create").assertIsEnabled()
        compose.onNodeWithTag("Bot avatar shapes").performScrollToNode(hasContentDescription("cloud shape"))
        compose.onNodeWithContentDescription("cloud shape").performClick().assertIsSelected()
        compose.onNodeWithTag("Bot avatar colors").performScrollToNode(hasContentDescription("Green"))
        compose.onNodeWithContentDescription("Green").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Create").performClick()
        assertEquals("", saved?.name)
        assertEquals("cloud", saved?.avatarShape)
        assertEquals("green", saved?.avatarColor)
        snapshot("bot-editor-new-preview.png")
    }

    @Test fun workspaceAndPermissionsUseMenusWhileNotificationsAndPluginsUseRealSwitches() {
        assumeTrue(Build.PRODUCT.startsWith("sdk"))
        val draft = mutableStateOf(BotDraft(id = "fixture", name = "Scout", cwd = "/home/Project", notify = null))
        var folderRequested = false
        val plugins = PluginListing(listOf(InstalledConnector("git", "GitHub", kind = "local", command = "git-server")),
            listOf(InstalledSkill("review", "Review changes", "Review before pushing", "local", "/skills/review.md")))
        render(draft, state = fixture().copy(plugins = plugins), chooseFolder = { folderRequested = true })
        compose.onNodeWithContentDescription("Workspace").performScrollTo().performClick()
        compose.onNodeWithText("Personal workspace").performClick()
        assertEquals("", draft.value.cwd)
        compose.onNodeWithContentDescription("Workspace").performClick()
        compose.onNodeWithText("Choose project folder…").performClick()
        assertTrue(folderRequested)
        compose.onNodeWithContentDescription("Permissions").performScrollTo().performClick()
        compose.onNodeWithText("Approve automatically").performClick()
        assertEquals("auto", draft.value.permission)
        compose.onNodeWithContentDescription("Notifications").performScrollTo().assertIsOn().performClick().assertIsOff()
        assertEquals(false, draft.value.notify)
        compose.onNodeWithContentDescription("GitHub").performScrollTo().assertIsOn().performClick().assertIsOff()
        assertEquals(emptyList<String>(), draft.value.connectors)
        compose.onNodeWithContentDescription("Review changes").performScrollTo().assertIsOff().performClick().assertIsOn()
        assertEquals(listOf("review"), draft.value.skills)
        compose.onNodeWithText("Pinned").assertDoesNotExist()
    }

    @Test fun offlineSettingsDisableSavingButKeepManualModelAndExistingSettingsEditable() {
        assumeTrue(Build.PRODUCT.startsWith("sdk"))
        val draft = mutableStateOf(BotDraft(id = "fixture", name = "Scout", model = "old-model", permission = "auto", computer = true))
        render(draft, state = fixture().copy(actualConnection = LinkState.Connecting), load = { fail("Offline cannot discover models"); AgentModels(emptyList()) })
        compose.onNodeWithContentDescription("Save").assertIsNotEnabled()
        field("Name").performTextReplacement("")
        compose.onNodeWithContentDescription("Save").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Model ID").performScrollTo().performTextReplacement("my-model")
        assertEquals("my-model", draft.value.model)
        assertEquals("auto", draft.value.permission)
        assertTrue(draft.value.computer)
    }

    @Test fun advancedActionsRemainAvailableAndClosingDoesNotSaveTheDraft() {
        assumeTrue(Build.PRODUCT.startsWith("sdk"))
        val draft = mutableStateOf(BotDraft(id = "fixture", name = "Scout"))
        var copied = false; var refreshed = false; var deleted = false; var closed = false; var saved = false
        render(draft, close = { closed = true }, save = { saved = true }, copy = { copied = true },
            refresh = { refreshed = true }, delete = { deleted = true })
        compose.onNodeWithText("More settings").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Pinned").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithContentDescription("Show in roster").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText("Copy settings template").performScrollTo().performClick()
        compose.onNodeWithText("Refresh installed plugins").performScrollTo().performClick()
        compose.onNodeWithText("Delete bot").performScrollTo().performClick()
        assertTrue(copied && refreshed && deleted)
        assertTrue(draft.value.pinned && draft.value.hidden)
        compose.onNodeWithContentDescription("Close").performClick()
        assertTrue(closed)
        assertFalse(saved)
    }

    @Test fun compactModelMenuRetainsUnknownOverridesAndFoldsTheAgentDefault() {
        assumeTrue(Build.PRODUCT.startsWith("sdk"))
        var selected by mutableStateOf<String?>("old-model")
        compose.setContent { CodyncTheme { BotEditorCard {
            BotEditorModel("host", "agent", selected, true, { selected = it }) {
                AgentModels(listOf(AgentModel("auto", "Default (recommended)"), AgentModel("host-model", "Host model")))
            }
        } } }
        compose.waitUntil { compose.onAllNodesWithContentDescription("Refresh model list").fetchSemanticsNodes().size == 1 }
        assertEquals("old-model", selected)
        compose.onNodeWithContentDescription("Model").performClick()
        compose.onAllNodesWithText("Default (recommended)").assertCountEquals(1)
        compose.onNodeWithText("Default (recommended)").performClick()
        assertNull(selected)
        compose.onNodeWithContentDescription("Model").performClick()
        compose.onNodeWithText("Host model").performClick()
        assertEquals("host-model", selected)
    }

    @Test fun cancelledModelDiscoveryCannotReplaceTheNewAgentsMenu() {
        assumeTrue(Build.PRODUCT.startsWith("sdk"))
        var backend by mutableStateOf("old")
        val started = AtomicBoolean(false); val returned = AtomicBoolean(false)
        compose.setContent { CodyncTheme { Column {
            androidx.compose.material3.TextButton(onClick = { backend = "new" }) { androidx.compose.material3.Text("Switch agent") }
            BotEditorCard { BotEditorModel("host", backend, null, true, {}) { id ->
                if (id == "old") withContext(NonCancellable) { started.set(true); delay(600); returned.set(true) }
                AgentModels(listOf(AgentModel(id, "$id advertised model")))
            } }
        } } }
        compose.waitUntil { started.get() }
        compose.onNodeWithText("Switch agent").performClick()
        compose.waitUntil { compose.onAllNodesWithContentDescription("Refresh model list").fetchSemanticsNodes().size == 1 }
        compose.waitUntil(3_000) { returned.get() }
        compose.onNodeWithContentDescription("Model").performClick()
        compose.onNodeWithText("new advertised model").assertIsDisplayed()
        compose.onNodeWithText("old advertised model").assertDoesNotExist()
    }

    @Test fun remoteFolderPickerFiltersDrillsBackAndSelectsTheHostDirectory() {
        assumeTrue(Build.PRODUCT.startsWith("sdk"))
        val requested = mutableListOf<String?>()
        var chosen: String? = null; var closed = false
        compose.setContent { CodyncTheme { BotEditorFolders("host", "/home", true, { path ->
            requested.add(path)
            if (path == "/home/Repo") DirListing("/home/Repo", "/home", true, emptyList())
            else DirListing("/home", "/", false, listOf(DirItem("Repo", "/home/Repo", true), DirItem("Other", "/home/Other")))
        }, { chosen = it }, { closed = true }) } }
        compose.waitUntil { compose.onAllNodesWithText("Repo").fetchSemanticsNodes().size == 1 }
        compose.onNode(hasSetTextAction() and hasText("Search folders")).performTextInput("repo")
        compose.onNodeWithText("Other").assertDoesNotExist()
        compose.onNodeWithText("Repo").performClick()
        compose.waitUntil { compose.onAllNodesWithText("No subfolders.").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Use “Repo”").performClick()
        assertEquals("/home/Repo", chosen)
        compose.onNodeWithContentDescription("Back to parent folder").performClick()
        compose.waitUntil { compose.onAllNodesWithText("Other").fetchSemanticsNodes().size == 1 }
        assertEquals(listOf("/home", "/home/Repo", "/home"), requested)
        compose.onNodeWithContentDescription("Close folder picker").performClick()
        assertTrue(closed)
    }

    private fun render(draft: MutableState<BotDraft>, state: AppState = fixture(), close: () -> Unit = {}, save: () -> Unit = {},
        chooseFolder: () -> Unit = {}, copy: () -> Unit = {}, refresh: () -> Unit = {}, delete: () -> Unit = {},
        load: suspend (String) -> AgentModels = { AgentModels(listOf(AgentModel("sonnet", "Sonnet")), "sonnet") }) {
        compose.setContent { CodyncTheme { BotEditorForm(state, draft.value, { draft.value = it }, close, save,
            chooseFolder, load, refresh, copy, delete) } }
    }

    private fun fixture(): AppState = AppState(loading = false, setupComplete = true,
        actualConnection = LinkState.Ready(HostRoute.Direct), plugins = PluginListing(emptyList(), emptyList()),
        computers = listOf(Computer("fixture", "Sean's computer", "fixture-key")), hello = buildJsonObject {
            put("backends", buildJsonArray {
                add(buildJsonObject { put("id", "claude"); put("name", "Claude"); put("available", true) })
                add(buildJsonObject { put("id", "codex"); put("name", "Codex"); put("available", false); put("installHint", "Install Codex on your computer.") })
            })
            put("screen", buildJsonObject { put("enabled", true) })
        })

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasContentDescription(label))

    private fun snapshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("layoutSnapshots") != "true") return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
