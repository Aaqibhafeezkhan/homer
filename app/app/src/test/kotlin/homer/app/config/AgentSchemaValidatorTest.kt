package homer.app.config

import homer.app.fixtures.AgentFixtures
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class AgentSchemaValidatorTest {
    private val validator = AgentSchemaValidator()

    @Test
    fun `valid agent passes schema validation`() {
        val errors = validator.validate(AgentFixtures.simpleAgent())
        assertTrue(errors.isEmpty(), "expected no validation errors, got: $errors")
    }

    @Test
    fun `agent JSON with empty commands array fails schema validation`() {
        // commands has minItems: 1 in homer-agent.schema.json
        val errors = validator.validate("""{"name":"x","commands":[]}""")
        assertTrue(errors.isNotEmpty(), "expected a schema violation for empty commands")
    }

    @Test
    fun `agent name with disallowed characters fails schema validation`() {
        // name has pattern ^[a-zA-Z0-9_-]+$ in homer-agent.schema.json
        val errors = validator.validate("""{"name":"bad name","commands":[{"type":"cmd","cmd":["echo"]}]}""")
        assertTrue(errors.isNotEmpty(), "expected a schema violation for name with a space")
    }

    @Test
    fun `an agent pinned to a runner alias passes`() {
        val errors = validator.validate(AgentFixtures.simpleAgent().copy(runner = "build-host"))
        assertTrue(errors.isEmpty(), "expected no validation errors, got: $errors")
    }

    @Test
    fun `an agent with runnerTags passes`() {
        val errors = validator.validate(AgentFixtures.simpleAgent().copy(runnerTags = listOf("linux")))
        assertTrue(errors.isEmpty(), "expected no validation errors, got: $errors")
    }

    @Test
    fun `raw JSON with both runner and runnerTags fails`() {
        val errors =
            validator.validate(
                """{"name":"x","runner":"x","runnerTags":["linux"],"commands":[{"type":"cmd","cmd":["echo"]}]}""",
            )
        assertTrue(errors.isNotEmpty(), "expected a schema violation for runner + non-empty runnerTags")
    }
}
