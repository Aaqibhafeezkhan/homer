package homer.app.models

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GrantTest {
    @Test
    fun `action serializes lowercase`() {
        assertEquals("\"run\"", Json.encodeToString(Action.serializer(), Action.RUN))
    }

    @Test
    fun `grant matches exact and wildcard agent names for the listed action`() {
        val g = Grant(agents = listOf("deploy-*", "release"), actions = listOf(Action.RUN, Action.READ))
        assertTrue(g.matches("deploy-web", Action.RUN))
        assertTrue(g.matches("release", Action.READ))
        assertFalse(g.matches("release", Action.EDIT)) // action not granted
        assertFalse(g.matches("other", Action.RUN)) // agent not matched
    }

    @Test
    fun `star agent pattern matches any agent`() {
        val g = Grant(agents = listOf("*"), actions = listOf(Action.READ))
        assertTrue(g.matches("anything", Action.READ))
    }
}
