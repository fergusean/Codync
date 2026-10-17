package com.codync.android

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.codync.android.design.CodyncTheme
import com.codync.android.design.OverlayRoot

internal fun SemanticsNodeInteractionsProvider.onControl(label: String) = onNode(hasText(label) or hasContentDescription(label))

/** A screen as the app hosts it: the theme, and the overlay its sheets, menus and dialogs draw in. */
@Composable internal fun Hosted(content: @Composable () -> Unit) = CodyncTheme { OverlayRoot(content) }

/** The roster header's computers menu ("Computers, 1 connected. Filter conversations"). */
internal fun ComposeTestRule.computerAction(label: String) {
    onNodeWithContentDescription("Computers,", substring = true).performClick()
    onNodeWithText(label).performClick()
}

/** The open chat's title menu ("<bot> actions"). */
internal fun ComposeTestRule.chatAction(label: String) {
    val menu = hasContentDescription(" actions", substring = true)
    waitUntil(10_000) { onAllNodes(menu).fetchSemanticsNodes().size == 1 }
    onNode(menu).performClick()
    onNodeWithText(label).performClick()
}
