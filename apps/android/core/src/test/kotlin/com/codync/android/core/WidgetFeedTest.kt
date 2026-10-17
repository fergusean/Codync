package com.codync.android.core

import org.junit.Assert.*
import org.junit.Test

class WidgetFeedTest {
    @Test fun attentionSortingExcludesDeletedAndHiddenBots() {
        val bots = listOf(Bot("idle", "Idle"), Bot("hidden", "Hidden", hidden = true),
            Bot("deleted", "Deleted", deleted = true), Bot("working", "Working", status = "working"),
            Bot("approval", "Approval", status = "needsInput"))
        assertEquals(listOf("approval", "working", "idle"), widgetBots(bots).map { it.id })
    }

    @Test fun `team spots keep picks, skip deleted ones and fill the rest in feed order`() {
        val bots = listOf(WidgetBot("a", "A", "needsInput"), WidgetBot("b", "B", "working"), WidgetBot("c", "C", "idle"))
        assertEquals(listOf("c", "a", "b", null), teamSlots(bots, listOf("c", "gone", null, null)).map { it?.id })
        assertEquals(listOf("a", "b", "c", null), teamSlots(bots, emptyList()).map { it?.id })
    }
}
