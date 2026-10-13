package dev.memoh.feature.sessions

import dev.memoh.core.model.Bot
import org.junit.Assert.*
import org.junit.Test

class BotSelectionTest {
    @Test fun deletingBotsAreExcludedAndReadyIsPreferredOverCreating() {
        val bots = availableBots(listOf(Bot("gone", status = "deleting"), Bot("creating", status = "creating"), Bot("ready", status = "ready")))
        assertEquals(listOf("creating", "ready"), bots.map { it.id })
        assertEquals("ready", preferredBot(bots, "gone")?.id)
        assertEquals("creating", preferredBot(bots, "creating")?.id)
        assertNull(preferredBot(availableBots(listOf(Bot("gone", status = "deleting"))), "gone"))
    }
}
