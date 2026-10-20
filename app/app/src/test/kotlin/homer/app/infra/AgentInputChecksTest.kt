package homer.app.infra

import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.models.Command
import homer.app.models.Param
import homer.app.models.ParamSource
import homer.app.models.ResultEnvParam
import homer.app.models.SrcType
import homer.app.models.Tags
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentInputChecksTest {
    private val checks = AgentInputChecks(TestFixtures.mockLogger(), ParamValidator())
    private val errors = mutableListOf<String>()

    private fun agentWith(vararg inputs: Param) = AgentFixtures.simpleAgent(name = "a").copy(inputs = inputs.toList())

    @Test
    fun `reserved and malformed envNames are refused`() {
        assertFalse(checks.checkInputEnvNames(agentWith(Param(paramName = "p", envName = "PATH")), errors))
        assertFalse(checks.checkInputEnvNames(agentWith(Param(paramName = "p", envName = "1BAD")), errors))
        assertFalse(checks.checkInputEnvNames(agentWith(Param(paramName = "Authorization", src = ParamSource.HEADER)), errors))
        assertTrue(checks.checkInputEnvNames(agentWith(Param(paramName = "p", envName = "GOOD")), mutableListOf()))
        assertEquals(3, errors.size)
    }

    @Test
    fun `tags fromInputs must name a declared, non-secret input with a tag-key paramName`() {
        val base = agentWith(Param(paramName = "repo"), Param(paramName = "tok", secret = true))
        assertTrue(checks.checkTags(base.copy(tags = Tags(fromInputs = listOf("repo"))), errors))
        assertFalse(checks.checkTags(base.copy(tags = Tags(fromInputs = listOf("missing"))), errors))
        assertFalse(checks.checkTags(base.copy(tags = Tags(fromInputs = listOf("tok"))), errors))
        assertEquals(
            listOf(
                "Agent 'a': tags.fromInputs entry 'missing' names no declared input",
                "Agent 'a': tags.fromInputs entry 'tok' names the secret input 'tok'; a run tag is exported in the clear",
            ),
            errors,
        )
    }

    @Test
    fun `tags runtime keys need the key charset and must not overlap fromInputs`() {
        val base = agentWith(Param(paramName = "repo"))
        assertTrue(checks.checkTags(base.copy(tags = Tags(runtime = listOf("pr"))), errors))
        assertFalse(checks.checkTags(base.copy(tags = Tags(runtime = listOf("bad key"))), errors))
        assertFalse(checks.checkTags(base.copy(tags = Tags(fromInputs = listOf("repo"), runtime = listOf("repo"))), errors))
        assertEquals(2, errors.size)
    }

    @Test
    fun `duplicate envNames and paramNames are refused`() {
        assertFalse(
            checks.checkInputUniqueness(agentWith(Param(paramName = "a", envName = "X"), Param(paramName = "b", envName = "X")), errors),
        )
        assertFalse(
            checks.checkInputUniqueness(agentWith(Param(paramName = "a", envName = "X"), Param(paramName = "a", envName = "Y")), errors),
        )
        assertEquals(2, errors.size)
    }

    @Test
    fun `a result captured into a reserved envName is refused`() {
        val agent =
            AgentFixtures.simpleAgent(name = "a").copy(
                commands =
                    listOf(
                        Command.Cmd(cmd = listOf("echo"), resultEnvParam = listOf(ResultEnvParam(src = SrcType.STD_OUT, envName = "PATH"))),
                    ),
            )
        assertFalse(checks.checkCommandBindings(agent, errors))
        assertTrue(checks.checkCommandBindings(AgentFixtures.simpleAgent(name = "a"), mutableListOf()))
    }
}
