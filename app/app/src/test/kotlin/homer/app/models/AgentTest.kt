package homer.app.models

import homer.app.fixtures.AgentFixtures
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AgentTest {
    private val sampleParam = Param(paramName = "id")

    @Test
    fun `init throws when both inputs and queryParams are set`() {
        val ex =
            assertThrows<IllegalArgumentException> {
                AgentFixtures.simpleAgent().copy(
                    inputs = listOf(sampleParam),
                    queryParams = listOf(sampleParam),
                )
            }
        assertEquals(
            "Agent '${AgentFixtures.simpleAgent().name}': set either 'inputs' or legacy 'queryParams', not both",
            ex.message,
        )
    }

    @Test
    fun `effectiveInputs returns inputs when only inputs is set`() {
        val agent =
            AgentFixtures.simpleAgent().copy(
                inputs = listOf(sampleParam),
                queryParams = null,
            )
        assertSame(agent.inputs, agent.effectiveInputs)
    }

    @Test
    @Suppress("DEPRECATION") // asserts the legacy alias itself
    fun `effectiveInputs returns queryParams when only queryParams is set`() {
        val agent =
            AgentFixtures.simpleAgent().copy(
                inputs = null,
                queryParams = listOf(sampleParam),
            )
        assertSame(agent.queryParams, agent.effectiveInputs)
    }

    @Test
    fun `effectiveInputs is null when neither is set`() {
        val agent = AgentFixtures.simpleAgent().copy(inputs = null, queryParams = null)
        assertNull(agent.effectiveInputs)
    }

    @Test
    fun `Param defaults src to QUERY`() {
        assertEquals(ParamSource.QUERY, Param(paramName = "x").src)
    }

    @Test
    fun `runner defaults to null and the filter resolves to local`() {
        val agent = AgentFixtures.simpleAgent()
        assertNull(agent.runner)
        assertEquals(
            homer.app.models.RunnerFilter
                .Alias("local"),
            agent.runnerFilter,
        )
    }

    @Test
    fun `runner alias yields an alias filter`() {
        val agent = AgentFixtures.simpleAgent().copy(runner = "build-host")
        assertEquals(
            homer.app.models.RunnerFilter
                .Alias("build-host"),
            agent.runnerFilter,
        )
    }

    @Test
    fun `runnerTags yield a tags filter`() {
        val agent = AgentFixtures.simpleAgent().copy(runnerTags = listOf("linux", "gpu"))
        assertEquals(
            homer.app.models.RunnerFilter
                .Tags(setOf("linux", "gpu")),
            agent.runnerFilter,
        )
    }

    @Test
    fun `runner and runnerTags together are rejected`() {
        val ex =
            assertThrows<IllegalArgumentException> {
                AgentFixtures.simpleAgent().copy(runner = "local", runnerTags = listOf("linux"))
            }
        assertEquals("Agent '${AgentFixtures.simpleAgent().name}': runner and runnerTags are mutually exclusive", ex.message)
    }

    @Test
    fun `blank runner and malformed runnerTags are rejected`() {
        assertThrows<IllegalArgumentException> { AgentFixtures.simpleAgent().copy(runner = " ") }
        assertThrows<IllegalArgumentException> { AgentFixtures.simpleAgent().copy(runnerTags = listOf("bad tag")) }
    }

    @Test
    fun `runnerClaimTimeoutSec must be at least 1 when set`() {
        assertEquals(600, AgentFixtures.simpleAgent().copy(runnerClaimTimeoutSec = 600).runnerClaimTimeoutSec)
        assertThrows<IllegalArgumentException> { AgentFixtures.simpleAgent().copy(runnerClaimTimeoutSec = 0) }
    }

    @Test
    fun `group defaults to null and accepts a valid label`() {
        assertNull(AgentFixtures.simpleAgent().group)
        assertEquals("platform", AgentFixtures.simpleAgent().copy(group = "platform").group)
    }

    @Test
    fun `an over-long or control-character group is rejected at construction`() {
        val ex =
            assertThrows<IllegalArgumentException> {
                AgentFixtures.simpleAgent().copy(group = "g".repeat(65))
            }
        assertEquals("Agent '${AgentFixtures.simpleAgent().name}': group must be at most 64 characters, got 65", ex.message)
        assertThrows<IllegalArgumentException> { AgentFixtures.simpleAgent().copy(group = "a\nb") }
        assertThrows<IllegalArgumentException> { AgentFixtures.simpleAgent().copy(group = "  ") }
    }
}
