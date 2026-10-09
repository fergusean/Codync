package com.codync.android.core

import org.junit.Assert.*
import org.junit.Test

class WidgetFeedTest {
    private val feed = WidgetFeed(computerId = "fixture", computerName = "Computer", botsAt = 1_000,
        bots = listOf(WidgetBot("approval", "Scout", "needsInput"), WidgetBot("running", "Dex", "working")),
        usageAt = 1_000, usage = UsageReport(listOf(UsageProvider("claude", "Claude", listOf(
            UsageWindow("session", "Session", 97.0), UsageWindow("week", "Weekly", 35.0))),
            UsageProvider("codex", "Codex", listOf(UsageWindow("daily", "Daily", 120.0))))))

    @Test fun staleBotsKeepTheirScopedIdentityWithoutClaimingCurrentActivity() {
        val content = feed.present(WidgetKind.Bots, now = 1_801_001)
        assertTrue(content.stale)
        assertEquals(listOf("Update delayed", "Update delayed"), content.lines.map { it.value })
        assertEquals("approval", content.lines.first().botId)
        assertFalse(content.lines.first().warning)
        val oldProvider = feed.copy(usageAt = 1_900_000, usage = UsageReport(listOf(feed.usage.providers.first().copy(updatedAt = 1_000))))
        assertTrue(oldProvider.present(WidgetKind.Provider, "claude", 1_900_000).stale)
        assertEquals(1_000L, oldProvider.present(WidgetKind.Provider, "claude", 1_900_000).updatedAt)
    }
    @Test fun providerSelectionShowsItsWindowsAndRetainsReportedValues() {
        val content = feed.present(WidgetKind.Provider, "claude", 1_000)
        assertEquals(listOf("Session", "Weekly"), content.lines.map { it.title })
        assertTrue(content.lines.first().warning)
        val combined = feed.present(WidgetKind.Usage, now = 1_000)
        assertEquals("120%", combined.lines.last().value)
        assertEquals(1f, combined.lines.last().fraction)
    }
    @Test fun anUnpairedWidgetUsesAnHonestEmptyState() {
        assertTrue(WidgetFeed().present(WidgetKind.Bots).empty!!.contains("Connect a computer"))
        assertEquals("No saved usage report. Open ZC Codync to refresh.", feed.present(WidgetKind.Provider, "absent", 1_000).empty)
    }
    @Test fun attentionSortingExcludesDeletedAndHiddenBots() {
        val bots = listOf(Bot("idle", "Idle"), Bot("hidden", "Hidden", hidden = true),
            Bot("deleted", "Deleted", deleted = true), Bot("working", "Working", status = "working"),
            Bot("approval", "Approval", status = "needsInput"))
        assertEquals(listOf("approval", "working", "idle"), widgetBots(bots).map { it.id })
    }
}
