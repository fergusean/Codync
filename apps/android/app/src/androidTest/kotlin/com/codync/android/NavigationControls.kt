package com.codync.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

internal fun SemanticsNodeInteractionsProvider.onControl(label: String) = onNode(hasText(label) or hasContentDescription(label))

internal fun ComposeTestRule.computerAction(label: String) {
    onNodeWithContentDescription("Computers").performClick()
    onNodeWithText(label).performClick()
}

internal fun ComposeTestRule.chatAction(label: String) {
    waitUntil(10_000) { onAllNodesWithContentDescription("Conversation actions").fetchSemanticsNodes().size == 1 }
    onNodeWithContentDescription("Conversation actions").performClick()
    onNodeWithText(label).performClick()
}
