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
}
