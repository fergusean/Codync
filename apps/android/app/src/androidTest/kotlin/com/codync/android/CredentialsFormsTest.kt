package com.codync.android

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.codync.android.core.*
import com.codync.android.design.CodyncTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CredentialsFormsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun connectorFieldsAreMaskedAndBlankSavedValuesArePreserved() {
        var fields: Map<String, String>? = null; var closed = false
        val connector = InstalledConnector("fixture", "Fixture connection", kind = "local", keys = listOf("KEY", "UNCHANGED"))
        compose.setContent { CodyncTheme { ConnectorCredentialsForm(connector, true, { closed = true }) { fields = it } } }
        compose.onNodeWithText("Save credentials").assertIsNotEnabled()
        compose.onNodeWithText("KEY").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).performTextInput("  fixture key  ")
        compose.onNodeWithText("Save credentials").performClick()
        compose.waitUntil { closed }
        assertEquals(mapOf("KEY" to "  fixture key  "), fields)
    }
    @Test fun loginCredentialsAreNotRestoredOrAutomaticallySubmittedAfterRecreation() {
        var calls = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CodyncTheme { Column {
            LoginCredentialsForm(true, { site, username, _ -> calls++; SavedLogin("id", site, username) }) {}
        } } }
        compose.onNodeWithText("Website or app").performTextInput("fixture.invalid")
        compose.onNodeWithText("Password or op:// reference").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).performTextInput("fixture-password")
        compose.onNodeWithText("Save sign-in").assertIsEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Save sign-in").assertIsNotEnabled()
        assertEquals(0, calls)
    }
    @Test fun loginValuesRetainTheirExactPasswordAndClearOnlyAfterConfirmation() {
        var password: String? = null; var saved = false
        compose.setContent { CodyncTheme { Column {
            LoginCredentialsForm(true, { site, username, value -> password = value; SavedLogin("id", site, username) }) { saved = true }
        } } }
        compose.onNodeWithText("Website or app").performTextInput("fixture.invalid")
        compose.onNodeWithText("Username or email").performTextInput("fixture-user")
        compose.onNodeWithText("Password or op:// reference").performTextInput("  fixture password  ")
        compose.onNodeWithText("Save sign-in").performClick()
        compose.waitUntil { saved }
        assertEquals("  fixture password  ", password)
        compose.onNodeWithText("Save sign-in").assertIsNotEnabled()
    }
    @Test fun appFieldsRequireTheirDeclaredKeyAndPreserveCredentialWhitespace() {
        var fields: Map<String, String>? = null
        val plan = ComposioConnect("needsFields", mode = "API_KEY", fields = listOf(
            ComposioField("key", "App key", secret = true, required = true), ComposioField("optional", "Label")))
        compose.setContent { CodyncTheme { Column { AppConnectionFields(plan, true, false) { fields = it } } } }
        compose.onNodeWithText("Connect app").assertIsNotEnabled()
        compose.onNodeWithText("App key (required)").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).performTextInput("  fixture key  ")
        compose.onNodeWithText("Connect app").performClick()
        assertEquals(mapOf("key" to "  fixture key  "), fields)
    }
    @Test fun cancellingAConnectionCardNeverSubmitsItsTypedCredential() {
        var submitted = false; var cancelled = false
        val request = ConnectionRequest("login", "pending", "Fixture login", "bot", site = "fixture.invalid")
        compose.setContent { CodyncTheme { Column(Modifier.fillMaxWidth()) {
            ConnectionRequestFields(request, "Fixture request", true, false, { _, _ -> submitted = true }, { cancelled = true })
        } } }
        compose.onNodeWithText("Save login").assertIsNotEnabled()
        compose.onNodeWithText("Password or op:// reference").performTextInput("fixture-password")
        compose.onNodeWithText("Cancel connection request").performClick()
        assertTrue(cancelled); assertFalse(submitted)
    }
    @Test fun anUnknownRequestStatusDoesNotLookConnected() {
        compose.setContent { CodyncTheme { Column {
            ConnectionRequestFields(ConnectionRequest("connection", "future-status", "Fixture", "bot"), "", true, false, { _, _ -> fail("Unknown status cannot submit") }, {})
        } } }
        compose.onNodeWithText("Request status: future-status").assertIsDisplayed()
        compose.onNodeWithText("Connected").assertDoesNotExist()
        compose.onNodeWithText("Connect").assertDoesNotExist()
    }
    @Test fun removingAComposioKeyRequiresItsOwnConfirmation() {
        var saved: String? = null; var closed = false
        compose.setContent { CodyncTheme { ComposioKeyForm(ComposioStatus(true, "https://example.com"), true, { closed = true }) { saved = it } } }
        compose.onNodeWithText("Save Composio key").assertIsNotEnabled()
        compose.onNodeWithText("Remove Composio key").performClick()
        compose.onNodeWithText("Keep Composio key").performClick()
        assertNull(saved)
        compose.onNodeWithText("Remove Composio key").performClick()
        compose.onNodeWithText("Confirm remove Composio key").performClick()
        compose.waitUntil { closed }
        assertEquals("", saved)
    }
}
