package com.codync.android.core

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class MirrorWindowTest {
    private val old = Entry("o", 5, "egan", rev = 50, kind = "notice")

    @Test fun `rewritten old entry below the floor is dropped`() =
        assertTrue(outsideLoadedWindow(old, known = false, floor = 10))

    @Test fun `update to a held entry applies`() =
        assertFalse(outsideLoadedWindow(old, known = true, floor = 10))

    @Test fun `new entry at or above the floor applies`() {
        assertFalse(outsideLoadedWindow(old.copy(seq = 10), known = false, floor = 10))
        assertFalse(outsideLoadedWindow(old.copy(seq = 12), known = false, floor = 10))
    }

    @Test fun `no floor accepts everything, including lower seqs than earlier inserts`() {
        // since=0 catch-up arrives in rev order, so a lower seq may follow a higher one.
        assertFalse(outsideLoadedWindow(old.copy(seq = 20), known = false, floor = null))
        assertFalse(outsideLoadedWindow(old.copy(seq = 3), known = false, floor = null))
    }

    @Test fun `threads are never dropped`() =
        assertFalse(outsideLoadedWindow(old.copy(threadId = "t"), known = false, floor = 10))
}
