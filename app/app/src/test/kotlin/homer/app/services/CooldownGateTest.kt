package homer.app.services

import homer.app.exceptions.CooldownActiveException
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.Cooldown
import homer.app.services.persistence.ProcessRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CooldownGateTest {
    private val repo = mockk<ProcessRepository>()
    private val now = 10_000L

    private fun gate() = CooldownGate(repo) { now }

    private fun agent(cooldown: Cooldown?) =
        Agent(
            name = "watcher",
            commands = listOf(Command.Cmd(cmd = listOf("true"))),
            cooldown = cooldown,
        )

    @Test
    fun `no cooldown configured never touches the database`() =
        runTest {
            // Strict mock: any repository call here would throw, which is the assertion.
            gate().check(agent(null))
        }

    @Test
    fun `minInterval rejects a start inside the window`() =
        runTest {
            coEvery { repo.latestRunStartedAt("watcher") } returns now - 60

            val e =
                assertFailsWith<CooldownActiveException> {
                    gate().check(agent(Cooldown(minInterval = "5m")))
                }

            assertEquals("watcher", e.agentName)
            assertEquals(240, e.retryAfterSec)
        }

    @Test
    fun `minInterval allows a start once the window has elapsed`() =
        runTest {
            coEvery { repo.latestRunStartedAt("watcher") } returns now - 300

            gate().check(agent(Cooldown(minInterval = "5m")))
        }

    @Test
    fun `an agent that has never run is not in cooldown`() =
        runTest {
            coEvery { repo.latestRunStartedAt("watcher") } returns null

            gate().check(agent(Cooldown(minInterval = "5m")))
        }

    @Test
    fun `afterFailure backs off only when the last run failed`() =
        runTest {
            coEvery { repo.latestFailureEndedAt("watcher") } returns now - 60

            val e =
                assertFailsWith<CooldownActiveException> {
                    gate().check(agent(Cooldown(afterFailure = "15m")))
                }

            assertTrue(e.message!!.contains("last run failed"), e.message!!)
        }

    @Test
    fun `afterFailure does not slow down a healthy agent`() =
        runTest {
            // The shape a once-a-minute watcher needs: no failure on record, no delay,
            // even though a plain minInterval of the same size would have blocked it.
            coEvery { repo.latestFailureEndedAt("watcher") } returns null

            gate().check(agent(Cooldown(afterFailure = "15m")))
        }

    @Test
    fun `both windows apply and the failure window is checked first`() =
        runTest {
            coEvery { repo.latestFailureEndedAt("watcher") } returns now - 60
            coEvery { repo.latestRunStartedAt("watcher") } returns now - 10

            val e =
                assertFailsWith<CooldownActiveException> {
                    gate().check(agent(Cooldown(minInterval = "1m", afterFailure = "15m")))
                }

            assertTrue(e.message!!.contains("last run failed"), e.message!!)
        }

    @Test
    fun `an empty cooldown block is rejected at load rather than silently doing nothing`() {
        assertFailsWith<IllegalArgumentException> { Cooldown() }
    }

    @Test
    fun `an unparseable duration is rejected at load`() {
        assertFailsWith<NumberFormatException> { Cooldown(minInterval = "30") }
        assertFailsWith<IllegalArgumentException> { Cooldown(minInterval = "5x") }
    }

    // --- withoutFailureBackoff: the manual-recovery (retry/resume/debug) view ---

    @Test
    fun `withoutFailureBackoff clears afterFailure and keeps minInterval`() {
        val recovery = agent(Cooldown(minInterval = "1m", afterFailure = "15m")).withoutFailureBackoff()

        assertEquals(Cooldown(minInterval = "1m"), recovery.cooldown)
    }

    @Test
    fun `withoutFailureBackoff drops a cooldown that was only afterFailure`() {
        // `Cooldown`'s init rejects a value with neither window set, so clearing the
        // only field must collapse to null — a `copy()` without this would throw at
        // construction and take the start down with it.
        val recovery = agent(Cooldown(afterFailure = "15m")).withoutFailureBackoff()

        assertEquals(null, recovery.cooldown)
    }

    @Test
    fun `withoutFailureBackoff leaves an agent with no cooldown alone`() {
        assertEquals(null, agent(null).withoutFailureBackoff().cooldown)
    }
}
