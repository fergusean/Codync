package com.codync.android

import com.codync.android.core.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionPresentationTest {
    private val computer = Computer("fixture", "Computer", "fixture")
    private fun state(link: LinkState, loaded: Boolean = false) = AppState(
        account = AccountState(configured = false), loading = false, setupComplete = true,
        computers = listOf(computer), connection = link, actualConnection = link, rosterLoaded = loaded)

    @Test fun anUnreachableComputerIsNotAnEmptyRoster() {
        for (loaded in listOf(false, true)) {
            val empty = emptyRoster(state(LinkState.ComputerOffline(null), loaded), "", false)
            assertEquals("Computer offline", empty.title)
            assertFalse(empty.canCreate)
            assertNotNull(empty.detail)
        }
    }

    @Test fun accessAndCompatibilityFailuresKeepTheirActionableMessage() {
        for ((status, title) in listOf(403 to "Access denied", 426 to "Update required", 500 to "Disconnected")) {
            val link = LinkState.Failed("Actionable host error", status)
            assertEquals(title, connectionLabel(link))
            val empty = emptyRoster(state(link), "", false)
            assertEquals(title, empty.title)
            assertEquals(link.message, empty.detail)
            assertFalse(empty.canCreate)
        }
    }

    @Test fun creationWaitsForAnAuthoritativeOnlineRoster() {
        val online = state(LinkState.Ready(HostRoute.Direct))
        assertEquals("Syncing bots…", emptyRoster(online, "", false).title)
        val loaded = online.copy(rosterLoaded = true)
        assertTrue(emptyRoster(loaded, "", false).canCreate)
        assertFalse(emptyRoster(loaded.copy(actualConnection = LinkState.Connecting), "", false).canCreate)
        assertEquals("No matching bots", emptyRoster(loaded, "missing", false).title)
        assertEquals("No hidden bots", emptyRoster(loaded, "", true).title)
        assertFalse(emptyRoster(loaded, "", true).canCreate)
    }
}
