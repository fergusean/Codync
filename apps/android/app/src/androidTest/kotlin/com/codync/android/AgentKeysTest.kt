package com.codync.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.codync.android.core.*
import com.codync.android.design.CodyncTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AgentKeysTest {
    @get:Rule val compose = createComposeRule()
    private val method = AgentAuthMethod("keys", "Provider keys", kind = "envVar", vars = listOf(
        AgentAuthVar("KEY", "API key"), AgentAuthVar("OPTIONAL", "Optional label", secret = false, optional = true)))
    @Test fun blankSavedKeysArePreservedAndOnlyTypedReplacementsAreSent() {
        var saved: Map<String, String>? = null; var closed = false
        compose.setContent { CodyncTheme { AgentKeysForm("Fixture agent", method, listOf("KEY"), true, { closed = true }) { saved = it } } }
        compose.onNodeWithText("Save keys").assertIsEnabled()
        compose.onNodeWithText("Optional label (optional)").performTextInput("fixture-label")
        compose.onNodeWithText("Save keys").performClick()
        compose.waitUntil { closed }
        assertEquals(mapOf("OPTIONAL" to "fixture-label"), saved)
    }
    @Test fun requiredKeysAndRemovalBothNeedAnExplicitUsableAction() {
        var saved: Map<String, String>? = null
        compose.setContent { CodyncTheme { AgentKeysForm("Fixture agent", method, listOf("OPTIONAL"), true, {}) { saved = it } } }
        compose.onNodeWithText("Save keys").assertIsNotEnabled()
        compose.onNodeWithText("Remove saved keys").performClick()
        compose.onNodeWithText("Keep keys").performClick()
        assertNull(saved)
        compose.onNodeWithText("Remove saved keys").performClick()
        compose.onNodeWithText("Confirm remove keys").performClick()
        compose.waitUntil { saved != null }
        assertEquals(mapOf("KEY" to "", "OPTIONAL" to ""), saved)
    }
}
