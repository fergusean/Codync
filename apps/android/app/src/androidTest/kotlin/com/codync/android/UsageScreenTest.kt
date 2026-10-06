package com.codync.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.codync.android.core.*
import com.codync.android.design.CodyncTheme
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UsageScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unavailableWindowsAndOldReportsNeverBecomeZeroUsage() {
        val raw = WireJson.parseToJsonElement("""{"providers":[{"id":"future","name":"Future provider","source":"future-source","updatedAt":1,"windows":[{"id":"month","label":"Monthly"}]}]}""")
        val state = mutableStateOf(AppState(loading = false, setupComplete = true,
            computers = listOf(Computer("fixture", "Acceptance computer", "fixture")), usage = raw.jsonObject,
            actualConnection = LinkState.ComputerOffline(null)))
        var refreshed = false
        var closed = false
        compose.setContent { CodyncTheme { UsageScreen(state.value, { refreshed = true }, { closed = true }) } }
        compose.onNodeWithText("Future provider").assertIsDisplayed()
        compose.onNodeWithText("Usage unavailable").assertIsDisplayed()
        compose.onNodeWithText("0%").assertDoesNotExist()
        compose.onNodeWithText("Source · future-source").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Last reported ·", substring = true).performScrollTo().assertIsDisplayed()
        compose.onControl("Refresh usage").assertIsNotEnabled()
        compose.onNodeWithText("Collapse").performScrollTo().performClick()
        compose.onNodeWithText("Usage unavailable").assertDoesNotExist()
        compose.onNodeWithText("Expand").performClick()
        compose.onNodeWithText("Usage unavailable").assertIsDisplayed()
        compose.runOnUiThread { state.value = state.value.copy(actualConnection = LinkState.Ready(HostRoute.Direct)) }
        compose.onControl("Refresh usage").performClick()
        assertTrue(refreshed)
        compose.onControl("Back").performClick()
        assertTrue(closed)
    }

    @Test fun anUnpairedUsageScreenExplainsHowReportsBecomeAvailable() {
        compose.setContent { CodyncTheme { UsageScreen(AppState(loading = false), {}, {}) } }
        compose.onNodeWithText("No usage yet").assertIsDisplayed()
        compose.onNodeWithText("Usage shows up once a computer is connected.").assertIsDisplayed()
        compose.onControl("Refresh usage").assertIsNotEnabled()
    }
}
