package com.codync.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.codync.android.core.*
import com.codync.android.design.CodyncTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ConnectedAppsTest {
    @get:Rule val compose = createComposeRule()
    private val configured = ComposioStatus(true, "https://example.com/key")
    private fun apps(first: Int, last: Int) = (first..last).map { ComposioApp("app-$it", "Fixture app $it") }
    @Test fun appsRevealAndFetchAnotherPageOnlyAfterExplicitRequests() {
        val requests = CopyOnWriteArrayList<String?>()
        compose.setContent { CodyncTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ConnectedAppsBrowser("", true, emptySet(), { configured }, { _, cursor ->
                requests.add(cursor)
                if (cursor == null) ComposioApps(apps(1, 13), "next") else ComposioApps(apps(13, 14))
            }, {}, {})
        } } }
        compose.waitUntil { compose.onAllNodesWithText("Fixture app 12").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf<String?>(null), requests)
        compose.onNodeWithText("Fixture app 13").assertDoesNotExist()
        compose.onNodeWithText("Load more apps").performScrollTo().performClick()
        compose.onNodeWithText("Fixture app 13").assertExists()
        assertEquals(1, requests.size)
        compose.onNodeWithText("Load more apps").performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithText("Fixture app 14").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(null, "next"), requests)
        assertEquals(1, compose.onAllNodesWithText("Fixture app 13").fetchSemanticsNodes().size)
    }
    @Test fun aLateCancelledPageCannotReplaceANewSearch() {
        var query by mutableStateOf("old")
        val late = CompletableDeferred<ComposioApps>()
        var oldPageStarted = false
        compose.setContent { CodyncTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ConnectedAppsBrowser(query, true, emptySet(), { configured }, { search, cursor ->
                if (cursor != null) { oldPageStarted = true; withContext(NonCancellable) { late.await() } }
                else if (search == "old") ComposioApps(apps(1, 1), "old-page")
                else ComposioApps(listOf(ComposioApp("new", "New search app")))
            }, {}, {})
        } } }
        compose.waitUntil { compose.onAllNodesWithText("Load more apps").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Load more apps").performScrollTo().performClick()
        compose.waitUntil { oldPageStarted }
        compose.runOnIdle { query = "new" }
        compose.waitUntil { compose.onAllNodesWithText("New search app").fetchSemanticsNodes().isNotEmpty() }
        late.complete(ComposioApps(listOf(ComposioApp("stale", "Stale page app"))))
        compose.waitForIdle()
        compose.onNodeWithText("New search app").assertExists()
        compose.onNodeWithText("Stale page app").assertDoesNotExist()
    }
    @Test fun anUnconfiguredComputerDoesNotFetchTheAppCatalog() {
        var catalogs = 0; var selected: ComposioStatus? = null
        compose.setContent { CodyncTheme { Column {
            ConnectedAppsBrowser("", true, emptySet(), { configured.copy(configured = false) }, { _, _ -> catalogs++; error("No catalog without a key") }, { selected = it }, {})
        } } }
        compose.waitUntil { compose.onAllNodesWithText("Set up Composio").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, catalogs)
        compose.onNodeWithText("Set up Composio").performClick()
        assertEquals(false, selected?.configured)
    }
    @Test fun unknownConnectionStatusesRemainVisibleAndExistingConnectionsCannotBeAddedAgain() {
        compose.setContent { CodyncTheme { Column {
            ConnectedAppsBrowser("", true, setOf("composio-existing"), { configured }, { _, _ -> ComposioApps(listOf(
                ComposioApp("future", "Future app", connection = ComposioConnectionState("id", "future-state")),
                ComposioApp("existing", "Existing app"))) }, {}, {})
        } } }
        compose.waitUntil { compose.onAllNodesWithText("Connection: future-state").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Connect Future app").assertIsEnabled()
        compose.onNodeWithText("Connected to Existing app").assertIsNotEnabled()
    }
}
