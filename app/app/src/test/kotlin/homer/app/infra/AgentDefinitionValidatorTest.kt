package homer.app.infra

import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.models.ClaudeCmdOptions
import homer.app.models.Command
import homer.app.models.CronConfig
import homer.app.models.DEFAULT_INTERRUPT_GRACE_SECONDS
import homer.app.models.MAX_INTERRUPT_GRACE_SECONDS
import homer.app.models.MAX_ON_STOP_STEPS
import homer.app.models.OnTimeout
import homer.app.models.Param
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentDefinitionValidatorTest {
    private val logger = TestFixtures.mockLogger()
    private val validator =
        AgentDefinitionValidator(
            logger,
            runnerAliases = setOf("local"),
            runnerTagSets = { error("registry unavailable") },
            inputResolver = AgentInputResolver(),
            inputSchemaValidator = InputSchemaValidator(),
            inputChecks = AgentInputChecks(logger, ParamValidator()),
        )
    private val errors = mutableListOf<String>()

    @Test
    fun `an invalid agent name is reported and refused`() {
        assertNull(validator.checkAgentName(AgentFixtures.simpleAgent(name = "bad name"), "dir", errors))
        assertEquals(listOf("Invalid agent name 'bad name' in dir"), errors)
        assertEquals("ok", validator.checkAgentName(AgentFixtures.simpleAgent(name = "ok"), "dir", mutableListOf()))
    }

    @Test
    fun `a valid definition loads with its schedule enabled`() {
        assertEquals(false, validator.checkDefinition(AgentFixtures.simpleAgent(name = "a"), "dir", errors))
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `no commands, an unknown runner or an invalid cron skip the agent`() {
        val agent = AgentFixtures.simpleAgent(name = "a")
        assertNull(validator.checkDefinition(agent.copy(commands = emptyList()), "dir", errors))
        assertNull(validator.checkDefinition(agent.copy(runner = "nowhere"), "dir", errors))
        assertNull(validator.checkDefinition(agent.copy(cron = CronConfig("not a cron")), "dir", errors))
        assertEquals(3, errors.size)
    }

    @Test
    fun `a schedule no required input can satisfy is disabled, not skipped`() {
        val agent = AgentFixtures.simpleAgent(name = "a").copy(cron = CronConfig("0 * * * *"), inputs = listOf(Param(paramName = "q")))
        assertEquals(true, validator.checkDefinition(agent, "dir", errors))
        assertEquals(1, errors.size)
    }

    @Test
    fun `a claude step that skips permissions with only allowedTools warns and still loads`() {
        val agent =
            AgentFixtures.claudeAgent(
                name = "a",
                options = ClaudeCmdOptions(dangerouslySkipPermissions = true, allowedTools = listOf("Read", "Grep")),
            )

        assertEquals(false, validator.checkDefinition(agent, "dir", errors))
        assertEquals(emptyList(), errors)
        verify { logger.warn(match<String> { it.contains("allowedTools") }, "a", "claude") }
    }

    // ========== Graceful-stop policy (ADR-0108) ==========

    /** A one-step agent whose step is `onTimeout: stop`, plus the given `onStop:` steps. */
    private fun stoppableAgent(
        onStop: List<Command> = emptyList(),
        onTimeout: OnTimeout = OnTimeout.STOP,
    ) = AgentFixtures.simpleAgent(name = "a").copy(
        commands = listOf(Command.Cmd(cmd = listOf("run"), timeout = 60, onTimeout = onTimeout)),
        onStop = onStop,
    )

    private fun stopStep(grace: Long = DEFAULT_INTERRUPT_GRACE_SECONDS) =
        Command.Cmd(cmd = listOf("save"), timeout = 30, interruptGraceSeconds = grace)

    @Test
    fun `onTimeout stop with no onStop steps is refused`() {
        assertNull(validator.checkDefinition(stoppableAgent(), "dir", errors))

        assertTrue(errors.single().contains("declares no onStop steps"), errors.single())
    }

    @Test
    fun `onTimeout stop with an onStop step loads`() {
        assertEquals(false, validator.checkDefinition(stoppableAgent(onStop = listOf(stopStep())), "dir", errors))
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `a bare onStop loads, so a run may be stopped by its deadline alone`() {
        // No step opted in: the run can still be stopped by the runner's soft run deadline.
        val agent = stoppableAgent(onStop = listOf(stopStep()), onTimeout = OnTimeout.FAIL)

        assertEquals(false, validator.checkDefinition(agent, "dir", errors))
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `an onStop step that itself declares onTimeout stop is refused`() {
        val agent =
            stoppableAgent(
                onStop = listOf(Command.Cmd(cmd = listOf("save"), timeout = 30, onTimeout = OnTimeout.STOP)),
            )

        assertNull(validator.checkDefinition(agent, "dir", errors))
        assertTrue(errors.single().contains("on an onStop step"), errors.single())
    }

    @Test
    fun `an interrupt grace outside the bound is refused on a regular and on a stop step`() {
        for (grace in listOf(-1L, MAX_INTERRUPT_GRACE_SECONDS + 1)) {
            val onRegular = stoppableAgent(onStop = listOf(stopStep())).copy(commands = listOf(stopStep(grace)))
            assertNull(validator.checkDefinition(onRegular, "dir", errors), "grace $grace on a regular step")

            val onStop = stoppableAgent(onStop = listOf(stopStep(grace)))
            assertNull(validator.checkDefinition(onStop, "dir", errors), "grace $grace on a stop step")
        }

        // Both ends of the bound are legal, and a grace is checked even when no step opted in.
        errors.clear()
        assertEquals(false, validator.checkDefinition(stoppableAgent(onStop = listOf(stopStep(0))), "dir", errors))
        assertEquals(
            false,
            validator.checkDefinition(
                stoppableAgent(onStop = listOf(stopStep(MAX_INTERRUPT_GRACE_SECONDS))),
                "dir",
                errors,
            ),
        )
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `more stop steps than the bound allows are refused`() {
        assertNull(
            validator.checkDefinition(stoppableAgent(onStop = List(MAX_ON_STOP_STEPS + 1) { stopStep() }), "dir", errors),
        )
        assertTrue(errors.single().contains("more than $MAX_ON_STOP_STEPS"), errors.single())

        errors.clear()
        assertEquals(
            false,
            validator.checkDefinition(stoppableAgent(onStop = List(MAX_ON_STOP_STEPS) { stopStep() }), "dir", errors),
        )
    }
}
