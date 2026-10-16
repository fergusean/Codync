package com.codync.android

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.codync.android.core.*
import com.codync.android.design.CodyncTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class AgentSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hostModelsRetainAnUnknownOverrideAndFoldTheRecommendedDefault() {
        var selected by mutableStateOf<String?>("old-model")
        compose.setContent { CodyncTheme { AgentModelPicker("host", "agent", selected, true, { selected = it }) {
            AgentModels(listOf(AgentModel("auto", "Default (recommended)"), AgentModel("host-model", "Host model", "From this agent")))
        } } }
        compose.waitUntil { compose.onAllNodesWithText("✓ old-model").fetchSemanticsNodes().size == 1 }
        assertEquals("old-model", selected)
        compose.onNodeWithText("Default (recommended)").performClick()
        assertNull(selected)
        compose.onNodeWithText("Host model").performClick()
        assertEquals("host-model", selected)
        compose.onNodeWithText("From this agent").assertIsDisplayed()
    }

    @Test fun aLateCancelledModelDiscoveryCannotReplaceAnotherAgentsCatalog() {
        var backend by mutableStateOf("old")
        val started = AtomicBoolean(false); val returned = AtomicBoolean(false)
        compose.setContent { CodyncTheme { Column {
            TextButton(onClick = { backend = "new" }) { Text("Switch agent") }
            AgentModelPicker("host", backend, null, true, {}) { id ->
                if (id == "old") withContext(NonCancellable) { started.set(true); delay(600); returned.set(true) }
                AgentModels(listOf(AgentModel(id, "$id advertised model")))
            }
        } } }
        compose.waitUntil { started.get() }
        compose.onNodeWithText("Switch agent").performClick()
        compose.waitUntil { compose.onAllNodesWithText("new advertised model").fetchSemanticsNodes().size == 1 }
        compose.waitUntil(3_000) { returned.get() }
        compose.onNodeWithText("old advertised model").assertDoesNotExist()
        compose.onNodeWithText("new advertised model").assertIsDisplayed()
    }

    @Test fun anOfflineModelOverrideRemainsEditableWithoutInventingAProviderList() {
        var selected by mutableStateOf<String?>("my-model")
        compose.setContent { CodyncTheme { AgentModelPicker("host", "agent", selected, false, { selected = it }) { fail("Offline must not query models"); AgentModels(emptyList()) } } }
        compose.onNodeWithText("Connect to this computer to load its models. You can still enter a model ID.").assertIsDisplayed()
        compose.onNodeWithText("Retry model list").assertIsNotEnabled()
        compose.onNodeWithText("my-model").performTextReplacement("saved-model")
        assertEquals("saved-model", selected)
    }

    @Test fun hostFolderBrowsingFiltersLocallyAndSelectsTheAuthoritativeDirectory() {
        var chosen: String? = null
        val requests = mutableListOf<String?>()
        compose.setContent { CodyncTheme { FolderPicker("host", "Fixture computer", "/home", true, { path ->
            requests.add(path)
            if (path == "/home/Repo") DirListing("/home/Repo", "/home", true, emptyList())
            else DirListing("/home", "/", false, listOf(DirItem("Repo", "/home/Repo", true), DirItem("Other", "/home/Other")))
        }, { chosen = it }, {}) } }
        compose.waitUntil { compose.onAllNodesWithText("Repo · Git repository").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Filter folders").performTextInput("repo")
        compose.onNodeWithText("Other").assertDoesNotExist()
        assertEquals(listOf("/home"), requests)
        compose.onNodeWithText("Repo · Git repository").performClick()
        compose.waitUntil { compose.onAllNodesWithText("No subfolders.").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Use this folder").performClick()
        assertEquals("/home/Repo", chosen)
        compose.onNodeWithText("Parent folder").performClick()
        compose.waitUntil { compose.onAllNodesWithText("Other").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Use personal workspace").performClick()
        assertEquals("", chosen)
    }

    @Test fun anUnavailableHostFolderShowsARecoverableErrorWithoutSelectingIt() {
        var count = 0; var chosen: String? = null
        compose.setContent { CodyncTheme { FolderPicker("host", "Fixture computer", "/missing", true, {
            count++
            if (count == 1) throw java.io.IOException("This folder no longer exists.")
            DirListing("/restored", null, false, emptyList())
        }, { chosen = it }, {}) } }
        compose.waitUntil { compose.onAllNodesWithText("Retry folder list").fetchSemanticsNodes().size == 1 }
        assertNull(chosen)
        compose.onNodeWithText("Use this folder").assertDoesNotExist()
        compose.onNodeWithText("Retry folder list").performClick()
        compose.waitUntil { compose.onAllNodesWithText("Use this folder").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Use this folder").performClick()
        assertEquals("/restored", chosen)
    }
}
