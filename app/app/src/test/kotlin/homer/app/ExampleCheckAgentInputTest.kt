package homer.app

import com.charleskorn.kaml.Yaml
import homer.app.fixtures.repoRoot
import homer.app.infra.ParamValidation
import homer.app.infra.ParamValidator
import homer.app.models.Agent
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertIs

/**
 * The bundled `langgraph_demo_check` agent embeds its `check` input in JSON
 * unescaped, so its declared pattern must refuse anything but a bare name
 * (CWE-116).
 */
class ExampleCheckAgentInputTest {
    private val check =
        Yaml.default
            .decodeFromString(Agent.serializer(), File(repoRoot, "example/agents/langgraph_demo_check/agent.yaml").readText())
            .effectiveInputs!!
            .single { it.paramName == "check" }
    private val validator = ParamValidator()

    @Test
    fun `accepts a bare check name`() {
        assertIs<ParamValidation.Valid>(validator.validate("lint", check))
    }

    @Test
    fun `rejects a value that would break out of the JSON string`() {
        assertIs<ParamValidation.Invalid>(validator.validate("""x", "ok": false, "y": "z""", check))
    }

    @Test
    fun `rejects a command substitution`() {
        assertIs<ParamValidation.Invalid>(validator.validate("\$(id)", check))
    }
}
