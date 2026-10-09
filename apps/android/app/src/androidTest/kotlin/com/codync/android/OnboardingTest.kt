package com.codync.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsNotEnabled
import com.codync.android.design.CodyncTheme
import org.junit.Rule
import org.junit.Test

class OnboardingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun skippingSetupLeavesAUsableEmptyRoster() {
        val state = mutableStateOf(AppState(loading = false))
        compose.setContent {
            CodyncTheme { CodyncApp(state.value, {}, { state.value = state.value.copy(setupComplete = true) }, {}, {}, {}) }
        }
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Skip for now").performClick()
        compose.onNodeWithText("No computer yet").assertIsDisplayed()
        compose.onNodeWithText("Bots").assertIsDisplayed()
    }

    @Test fun installationGuidanceRetainsTheComputerChoiceAndAllowsPairingLater() {
        compose.setContent { CodyncTheme { PairingSetup("", {}, false, false, {}, {}, {}) } }
        compose.onNodeWithText("Linux").performClick()
        compose.onNodeWithText("codync-host install").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("codync-host pair").assertIsDisplayed()
        compose.onNodeWithText("Pair").assertIsNotEnabled()
        compose.onNodeWithText("Scan pairing code").assertIsDisplayed()
        compose.onNodeWithText("Installation steps").performClick()
        compose.onNodeWithText("codync-host install").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Skip for now").assertIsDisplayed()
    }

    @Test fun returningFromTheScannerKeepsThePairingStepAndComputerChoice() {
        val state = AppState(loading = false)
        compose.setContent { CodyncTheme { CodyncApp(state, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Linux").performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Scan pairing code").performClick()
        compose.onNodeWithText("Use pairing link").performClick()
        compose.onNodeWithText("codync-host pair").assertIsDisplayed()
        compose.onNodeWithText("Pairing link").assertIsDisplayed()
    }
}
