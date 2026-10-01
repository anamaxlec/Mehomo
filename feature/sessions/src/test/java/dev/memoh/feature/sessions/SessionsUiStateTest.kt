package dev.memoh.feature.sessions

import dev.memoh.core.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The session list must never be left spinning.
 *
 * The first version of this screen showed a spinner forever. The cause was a
 * single default: `SessionsUiState.loading` started as `true`, and the load was
 * triggered by an external `bind()` call that nothing ever made. The early
 * return left the flag set, so the screen rendered a spinner over an empty list
 * — a state indistinguishable from a slow network, which is why it shipped.
 *
 * A spinner must therefore mean "a request is in flight", never "nobody has
 * started one yet". That is a property of the state type, and it is pinned here.
 *
 * The full login → bot list → session list path is verified on device against a
 * local mock server (`tools/mock-server.py`), because `SessionRepository` is
 * built on `EncryptedSharedPreferences` and cannot be constructed in a plain JVM
 * test without adding Robolectric.
 */
class SessionsUiStateTest {

    @Test
    fun `a fresh state is not loading`() {
        // The exact regression: a spinner shown before any request exists is a
        // spinner that an early return can leave running forever.
        assertFalse(
            "loading must default to false; a spinner means a request is in flight",
            SessionsUiState().loading,
        )
    }

    @Test
    fun `a fresh state has no bot, no sessions and no error`() {
        val state = SessionsUiState()
        assertEquals(null, state.bot)
        assertTrue(state.sessions.isEmpty())
        assertEquals(null, state.error)
        assertFalse(state.creating)
        assertEquals(null, state.renaming)
        assertEquals(null, state.deleting)
    }

    @Test
    fun `sessions split into today and earlier without overlap`() {
        val today = session("a", LocalDate.now().toString() + "T10:00:00+08:00")
        val older = session("b", LocalDate.now().minusDays(3).toString() + "T10:00:00+08:00")
        val state = SessionsUiState(sessions = listOf(today, older))

        assertEquals(listOf("a"), state.today.map { it.id })
        assertEquals(listOf("b"), state.earlier.map { it.id })
        // Every session appears exactly once across the two groups: the list is
        // rendered by iterating both, so an overlap would duplicate rows.
        assertEquals(state.sessions.size, state.today.size + state.earlier.size)
    }

    @Test
    fun `an unparseable timestamp falls into earlier rather than being dropped`() {
        val broken = session("x", "not-a-timestamp")
        val state = SessionsUiState(sessions = listOf(broken))

        assertTrue("a bad timestamp must not hide the row", state.earlier.contains(broken))
        assertTrue(state.today.isEmpty())
    }

    @Test
    fun `a session with no timestamp at all still renders`() {
        val bare = Session(id = "y", title = "no dates")
        val state = SessionsUiState(sessions = listOf(bare))

        assertTrue(state.earlier.contains(bare))
        assertEquals(1, state.sessions.size)
    }

    private fun session(id: String, updatedAt: String) =
        Session(id = id, title = id, type = "chat", updatedAt = updatedAt)
}
