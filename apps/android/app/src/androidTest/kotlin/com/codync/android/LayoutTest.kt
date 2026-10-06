package com.codync.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.codync.android.core.*
import com.codync.android.design.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LayoutTest {
    @get:Rule val compose = createComposeRule()
    private val state = AppState(loading = false, setupComplete = true, rosterLoaded = true,
        computers = listOf(Computer("fixture", "Acceptance computer", "fixture")),
        connection = LinkState.Ready(HostRoute.Direct), bots = (1..5).map {
            Bot("bot-$it", "Bot $it", lastMessage = "A recent reply", unread = if (it == 1) 12 else 0)
        })

    @Test fun theRosterShowsFiveRowsAndKeepsActionsOnDemand() {
        var creates = false
        var account = false
        compose.setContent { CodyncTheme { BotHomeScreen(state, null, { account = true }, {}, {}, {},
            { creates = true }, {}, {}, {}, {}) } }
        (1..5).forEach { compose.onNodeWithText("Bot $it").assertIsDisplayed() }
        snapshot("roster")
        compose.onNodeWithText("New bot").assertDoesNotExist()
        compose.onNodeWithText("Bot actions").assertDoesNotExist()
        compose.onNodeWithContentDescription("Accounts").performClick()
        assertTrue(account)
        compose.onNodeWithContentDescription("New").performClick()
        compose.onNodeWithText("New bot").performClick()
        assertTrue(creates)
        compose.onNodeWithText("State").performClick()
        compose.onNodeWithText("Widgets").assertIsSelected()
        compose.onNodeWithText("Provider usage").assertIsSelected()
        compose.onNodeWithContentDescription("Connect a computer").assertIsDisplayed()
        snapshot("widget-gallery")
        compose.onNodeWithText("Task status").performClick()
        compose.onNodeWithText("Bot activity").assertIsDisplayed()
        compose.onNodeWithText("Bots").performClick()
        compose.onNodeWithText("Bot 5").assertIsDisplayed()
    }

    @Test fun largerTextKeepsTheHeaderControlsAndUnreadBadgeAccessible() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                CodyncTheme { BotHomeScreen(state, null, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
            }
        }
        compose.onNodeWithContentDescription("Accounts").assertIsDisplayed()
        compose.onNodeWithContentDescription("New").assertIsDisplayed()
        compose.onNodeWithContentDescription("Computers").assertIsDisplayed()
        compose.onNodeWithContentDescription("12 unread messages", substring = true).assertIsDisplayed()
        val account = compose.onNodeWithContentDescription("Accounts").fetchSemanticsNode().boundsInRoot
        val computers = compose.onNodeWithContentDescription("Computers").fetchSemanticsNode().boundsInRoot
        val new = compose.onNodeWithContentDescription("New").fetchSemanticsNode().boundsInRoot
        assertTrue(account.right <= computers.left)
        assertTrue(computers.right <= new.left)
        snapshot("large-text")
        compose.onNodeWithText("State").performClick()
        compose.onNodeWithText("Choose your widget").performScrollTo()
        compose.onNodeWithText("Provider usage").assertIsSelected().assertIsDisplayed()
        snapshot("widget-gallery-large-text")
        compose.onAllNodesWithText("Bots").onLast().performClick()
        compose.computerAction("Search bots")
        compose.onNode(hasSetTextAction()).performTextReplacement("Bot 5")
        compose.onAllNodesWithText("Bot 5").onLast().assertIsDisplayed()
        compose.onNodeWithText("Bot 1").assertDoesNotExist()
    }
    private fun snapshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("layoutSnapshots") != "true") return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "layout-$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
