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

/** The button labeled [label] level with [text]: for list rows that merge no semantics of their own. */
internal fun ComposeTestRule.onRowButton(label: String, text: String): SemanticsNodeInteraction {
    val row = onNodeWithText(text).performScrollTo().fetchSemanticsNode().boundsInRoot
    val buttons = onAllNodesWithContentDescription(label)
    val index = buttons.fetchSemanticsNodes().indexOfFirst { it.boundsInRoot.top < row.bottom && it.boundsInRoot.bottom > row.top }
    check(index >= 0) { "No $label button beside $text" }
    return buttons[index]
}

/** Extensions opens from the paired computer's row in Computers & settings. */
internal fun ComposeTestRule.openExtensions() {
    computerAction("Manage computers")
    waitUntil(10_000) { onAllNodesWithContentDescription("Extensions").fetchSemanticsNodes().size == 1 }
    onNodeWithContentDescription("Extensions").performClick()
}

/** The open chat's title menu ("<bot> actions"). */
internal val chatMenu = hasContentDescription(" actions", substring = true)

internal fun ComposeTestRule.chatAction(label: String) {
    waitUntil(10_000) { onAllNodes(chatMenu).fetchSemanticsNodes().size == 1 }
    onNode(chatMenu).performClick()
    onNodeWithText(label).performClick()
}
