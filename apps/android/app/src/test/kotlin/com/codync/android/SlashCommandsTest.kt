package com.codync.android

import com.codync.android.core.Bot
import com.codync.android.core.InstalledSkill
import org.junit.Assert.*
import org.junit.Test

class SlashCommandsTest {
    @Test fun `only a leading slash word lists commands, and skills need a host that runs them`() {
        assertEquals("ne", SlashCommands.query("/ne"))
        assertNull(SlashCommands.query("/new session"))
        assertNull(SlashCommands.query("hi /new"))
        val bot = Bot("b", "Bot", skills = listOf("review"))
        val skills = listOf(InstalledSkill("review", "Review", "Reads diffs", "src", "/p"), InstalledSkill("other", "Other", "", "src", "/q"))
        assertEquals(listOf("new", "review"), SlashCommands.list(bot, "", skills, "2.14.1").map { it.name }.filter { it != "fast" })
        assertEquals(listOf("new"), SlashCommands.list(bot, "", skills, "2.9.3").map { it.name }.filter { it != "fast" })
        assertEquals("new", SlashCommands.builtIn(bot, " /new ")?.name)
        assertTrue(SlashCommands.list(Bot("g", "Team", kind = "group"), "", skills, null).isEmpty())
    }

    @Test fun `mentions start after a space and stay short`() {
        assertEquals("Re", mentionQuery("hi @Re"))
        assertNull(mentionQuery("mail@host"))
        assertNull(mentionQuery("@" + "x".repeat(25)))
    }
}
