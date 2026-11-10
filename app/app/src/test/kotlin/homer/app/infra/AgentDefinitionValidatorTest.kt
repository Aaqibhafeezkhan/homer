package homer.app.infra

import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.models.ClaudeCmdOptions
import homer.app.models.CronConfig
import homer.app.models.Param
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
