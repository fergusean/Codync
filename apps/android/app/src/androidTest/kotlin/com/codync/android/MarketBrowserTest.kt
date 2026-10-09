package com.codync.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.codync.android.core.*
import com.codync.android.design.CodyncTheme
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MarketBrowserTest {
    @get:Rule val compose = createComposeRule()
    private fun item(id: Int) = MarketConnector("fixture-$id", "Connector $id", options = emptyList())
    @Test fun scrollingDoesNotFetchAnotherPageAndTheButtonRevealsFetchedRowsFirst() {
        val requests = mutableListOf<String?>()
        compose.setContent { CodyncTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            MarketConnectorBrowser("", true, emptySet(), { _, cursor ->
                requests.add(cursor)
                if (cursor == null) MarketConnectors((1..25).map(::item), "next")
                else MarketConnectors(listOf(item(25), item(26)))
            }, {})
        } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Load more connectors").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Load more connectors").performScrollTo()
        assertEquals(listOf<String?>(null), requests)
        compose.onNodeWithText("Connector 13").assertDoesNotExist()
        compose.onNodeWithText("Load more connectors").performClick()
        compose.onNodeWithText("Connector 13").assertExists()
        assertEquals(listOf<String?>(null), requests)
        compose.onNodeWithText("Load more connectors").performScrollTo().performClick()
        compose.onNodeWithText("Connector 25").assertExists()
        assertEquals(listOf<String?>(null), requests)
        compose.onNodeWithText("Load more connectors").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Connector 26").fetchSemanticsNodes().size == 1 }
        assertEquals(listOf(null, "next"), requests)
        compose.onAllNodesWithText("Connector 25").assertCountEquals(1)
        compose.onNodeWithText("Load more connectors").assertDoesNotExist()
    }
    @Test fun aLateCancelledSearchCannotOverwriteTheCurrentQuery() {
        var query by mutableStateOf("old")
        val started = AtomicBoolean(false); val returned = AtomicBoolean(false)
        compose.setContent { CodyncTheme { Column {
            TextButton(onClick = { query = "new" }) { Text("New search") }
            MarketConnectorBrowser(query, true, emptySet(), { search, _ ->
                if (search == "old") withContext(NonCancellable) { started.set(true); delay(600); returned.set(true) }
                MarketConnectors(listOf(MarketConnector(search, "$search result", options = emptyList())))
            }, {})
        } } }
        compose.waitUntil(10_000) { started.get() }
        compose.onNodeWithText("New search").performClick()
        compose.waitUntil(10_000) { returned.get() && compose.onAllNodesWithText("new result").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("old result").assertDoesNotExist()
    }
    @Test fun connectorSelectionRetainsUnknownSavedIdsAndNewBotsBeginWithHostDefaults() {
        var draft by mutableStateOf(BotDraft(connectors = null, skills = listOf("missing-skill")))
        val plugins = PluginListing(listOf(InstalledConnector("installed", "Fixture connector", kind = "remote", auth = "signedOut")),
            listOf(InstalledSkill("installed-skill", "Fixture skill", source = "fixture", path = "/fixture")))
        compose.setContent { CodyncTheme { PluginChoices(AppState(plugins = plugins), draft) { draft = it } } }
        compose.onNodeWithText("✓ Fixture connector").performClick()
        assertEquals(emptyList<String>(), draft.connectors)
        compose.onNodeWithText("Fixture skill").performClick()
        assertEquals(listOf("missing-skill", "installed-skill"), draft.skills)
        compose.onNodeWithText("Sign-in needed").assertIsDisplayed()
    }
}
