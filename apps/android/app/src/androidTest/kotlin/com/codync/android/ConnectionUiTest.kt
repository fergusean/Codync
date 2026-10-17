package com.codync.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.codync.android.core.*
import com.codync.android.design.CodyncTheme
import org.junit.Rule
import org.junit.Test

class ConnectionUiTest {
    @get:Rule val compose = createComposeRule()
    private fun fixture(link: LinkState) = AppState(loading = false, setupComplete = true,
        computers = listOf(Computer("fixture", "Computer", "fixture")), connection = link, actualConnection = link)

    @Test fun aFailedInitialConnectionShowsTheHostErrorAndNeverOffersAnEmptyBotList() {
        val state = mutableStateOf(fixture(LinkState.Failed("Your access was revoked.", 403)))
        compose.setContent { CodyncTheme { BotHomeScreen(state.value, null, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Access denied").assertIsDisplayed()
        compose.onNodeWithText("Your access was revoked.").assertIsDisplayed()
        compose.onNodeWithText("No bots yet").assertDoesNotExist()
        compose.onNodeWithText("Create your first bot").assertDoesNotExist()
        compose.onNodeWithContentDescription("New").assertIsNotEnabled()
        state.value = fixture(LinkState.Ready(HostRoute.Direct)).copy(rosterLoaded = true)
        compose.onNodeWithText("No bots yet").assertIsDisplayed()
        compose.onNodeWithText("Create your first bot").assertIsDisplayed()
        compose.onNodeWithContentDescription("New").assertIsEnabled()
    }

    @Test fun cachedBotsRemainVisibleAndAreLabeledAsSavedWhileOffline() {
        val state = fixture(LinkState.ComputerOffline(null)).copy(bots = listOf(Bot("bot", "Saved bot")))
        compose.setContent { CodyncTheme { BotHomeScreen(state, null, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Saved bot").assertIsDisplayed()
        compose.onNodeWithText("Computer offline · Showing saved bots").assertIsDisplayed()
        compose.onNodeWithContentDescription("New").assertIsNotEnabled()
    }
}
