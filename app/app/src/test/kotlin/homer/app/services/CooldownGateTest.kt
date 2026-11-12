package homer.app.services

import homer.app.exceptions.CooldownActiveException
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.Cooldown
import homer.app.models.Param
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

    // --- cooldown.key: the same windows, counted per subject ---

    @Test
    fun `a keyed afterFailure refuses the same subject and leaves another alone`() =
        runTest {
            // mr-1's failure is on record; mr-2 has none. The two lookups are scoped to
            // the start's own key, so one subject's failure cannot bar another's start.
            coEvery { repo.latestFailureEndedAt("watcher", "mr-1") } returns now - 60
            coEvery { repo.latestFailureEndedAt("watcher", "mr-2") } returns null

            val e =
                assertFailsWith<CooldownActiveException> {
                    gate().check(agent(Cooldown(afterFailure = "15m", key = "mr-{{MR}}")), "mr-1")
                }

            assertTrue(e.message!!.contains("last run failed"), e.message!!)
            gate().check(agent(Cooldown(afterFailure = "15m", key = "mr-{{MR}}")), "mr-2")
        }

    @Test
    fun `a keyed minInterval spaces one subject's starts only`() =
        runTest {
            coEvery { repo.latestRunStartedAt("watcher", "mr-1") } returns now - 60
            coEvery { repo.latestRunStartedAt("watcher", "mr-2") } returns null

            assertFailsWith<CooldownActiveException> {
                gate().check(agent(Cooldown(minInterval = "5m", key = "mr-{{MR}}")), "mr-1")
            }
            gate().check(agent(Cooldown(minInterval = "5m", key = "mr-{{MR}}")), "mr-2")
        }

    @Test
    fun `a key is never echoed in the refusal`() =
        runTest {
            // §12 rule 7: the key is derived from the run's inputs, which may be secret
            // under another agent's definition — the refusal names the agent, not the key.
            coEvery { repo.latestRunStartedAt("watcher", "mr-s3cr3t") } returns now - 60

            val e =
                assertFailsWith<CooldownActiveException> {
                    gate().check(agent(Cooldown(minInterval = "5m", key = "mr-{{MR}}")), "mr-s3cr3t")
                }

            assertTrue(!e.message!!.contains("mr-s3cr3t"), e.message!!)
        }

    @Test
    fun `without a key both lookups stay unscoped`() =
        runTest {
            // The strict mock is the assertion: a null key must not reach the repository
            // as anything but null, or a keyless agent would stop seeing its own rows.
            coEvery { repo.latestRunStartedAt("watcher", null) } returns now - 60

            assertFailsWith<CooldownActiveException> {
                gate().check(agent(Cooldown(minInterval = "5m")))
            }
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

    @Test
    fun `a blank or malformed cooldown key is rejected at load`() {
        assertFailsWith<IllegalArgumentException> { Cooldown(minInterval = "1m", key = "  ") }
        // A `{{` that never closes would otherwise be stored as the literal every subject
        // shares — the one outcome `key` exists to prevent.
        assertFailsWith<IllegalArgumentException> { Cooldown(minInterval = "1m", key = "mr-{{MR") }
        assertFailsWith<IllegalArgumentException> { Cooldown(minInterval = "1m", key = "mr-{{M R}}") }
        assertFailsWith<IllegalArgumentException> { Cooldown(minInterval = "1m", key = "x".repeat(257)) }
        // At the cap, and a well-formed placeholder, both load.
        Cooldown(minInterval = "1m", key = "x".repeat(256))
        Cooldown(minInterval = "1m", key = "mr-{{MR}}")
    }

    @Test
    fun `a cooldown key naming a secret input is rejected at load`() {
        // A secret input's value would otherwise land in `processes.cooldown_key`, where it
        // outlives the run and is readable by anyone who can list the agent's runs.
        val e =
            assertFailsWith<IllegalArgumentException> {
                Agent(
                    name = "watcher",
                    commands = listOf(Command.Cmd(cmd = listOf("true"))),
                    inputs = listOf(Param(paramName = "MR", secret = true)),
                    cooldown = Cooldown(minInterval = "1m", key = "mr-{{MR}}"),
                )
            }

        assertTrue(e.message!!.contains("MR"), e.message!!)
        // A non-secret input of the same name is exactly what the key is for.
        Agent(
            name = "watcher",
            commands = listOf(Command.Cmd(cmd = listOf("true"))),
            inputs = listOf(Param(paramName = "MR", secret = false)),
            cooldown = Cooldown(minInterval = "1m", key = "mr-{{MR}}"),
        )
    }

    @Test
    fun `a cooldown key naming a secret legacy queryParams input is rejected at load`() {
        // The load-time guard reads the legacy alias on purpose (an agent may set only one
        // of the two forms), so this arm must stay covered: a regression to `inputs.orEmpty()`
        // would let a legacy-format agent store a secret input's value in the indexed
        // `processes.cooldown_key` column while the suite stays green.
        @Suppress("DEPRECATION")
        val e =
            assertFailsWith<IllegalArgumentException> {
                Agent(
                    name = "watcher",
                    commands = listOf(Command.Cmd(cmd = listOf("true"))),
                    queryParams = listOf(Param(paramName = "MR", secret = true)),
                    cooldown = Cooldown(minInterval = "1m", key = "mr-{{MR}}"),
                )
            }

        assertTrue(e.message!!.contains("MR"), e.message!!)
    }

    @Test
    fun `a cooldown key naming a SYSENV-backed env value is rejected at load`() {
        // An env entry whose raw value referenced `@SYSENV:` is a secret by construction —
        // the host variable rides the run's env, and the key is stored in plain text in the
        // indexed `processes.cooldown_key` column, outside the secret store.
        val e =
            assertFailsWith<IllegalArgumentException> {
                Agent(
                    name = "watcher",
                    env = mapOf("TOKEN" to "@SYSENV:REPO_TOKEN"),
                    sysEnvBackedEnvNames = setOf("TOKEN"),
                    commands = listOf(Command.Cmd(cmd = listOf("true"))),
                    cooldown = Cooldown(minInterval = "1m", key = "build-{{TOKEN}}"),
                )
            }

        assertTrue(e.message!!.contains("TOKEN"), e.message!!)
        // The key naming a different, non-backed env value loads.
        Agent(
            name = "watcher",
            env = mapOf("TOKEN" to "@SYSENV:REPO_TOKEN", "TEAM" to "core"),
            sysEnvBackedEnvNames = setOf("TOKEN"),
            commands = listOf(Command.Cmd(cmd = listOf("true"))),
            cooldown = Cooldown(minInterval = "1m", key = "build-{{TEAM}}"),
        )
    }

    // --- withoutFailureBackoff: the manual-recovery (retry/resume/debug) view ---

    @Test
    fun `withoutFailureBackoff clears afterFailure and keeps minInterval`() {
        val recovery = agent(Cooldown(minInterval = "1m", afterFailure = "15m")).withoutFailureBackoff()

        assertEquals(Cooldown(minInterval = "1m"), recovery.cooldown)
    }

    @Test
    fun `withoutFailureBackoff keeps a key alongside minInterval`() {
        // A keyed `minInterval` is still pacing, so a manual recovery keeps the key and
        // its subject — dropping it would let a retry start inside the window it is
        // pacing. `afterFailure` goes; the key is not its property.
        val recovery =
            agent(Cooldown(minInterval = "1m", afterFailure = "15m", key = "mr-{{MR}}")).withoutFailureBackoff()

        assertEquals(Cooldown(minInterval = "1m", key = "mr-{{MR}}"), recovery.cooldown)
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
