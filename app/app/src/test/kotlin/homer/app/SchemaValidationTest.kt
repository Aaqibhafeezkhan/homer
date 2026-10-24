package homer.app

import com.charleskorn.kaml.Yaml
import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import homer.app.config.AgentSchemaValidator
import homer.app.fixtures.repoRoot
import homer.app.models.Agent
import homer.app.models.Command
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Validates the shipped JSON Schemas against the bundled example fixtures.
 * Keeps schemas in schemas/ honest as the Kotlin models evolve.
 */
class SchemaValidationTest {
    private val schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
    private val json =
        Json {
            prettyPrint = false
            encodeDefaults = true
            explicitNulls = false
        }

    private fun loadSchema(fileName: String): Schema {
        val schemaFile = File(repoRoot, "app/schemas/$fileName")
        return schemaRegistry.getSchema(schemaFile.readText())
    }

    private fun validate(
        schema: Schema,
        jsonContent: String,
        fixturePath: String,
    ) {
        val errors = schema.validate(jsonContent, InputFormat.JSON)
        assertTrue(
            errors.isEmpty(),
            "Schema validation failed for $fixturePath:\n" +
                errors.joinToString("\n") { "  - ${it.message}" },
        )
    }

    @Test
    fun `homer-config schema validates all example configs`() {
        val schema = loadSchema("homer-config.schema.json")
        val configDir = File(repoRoot, "example/config")
        val fixtures = configDir.listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue(fixtures.isNotEmpty(), "Expected at least one example config fixture")

        for (fixture in fixtures) {
            validate(schema, fixture.readText(), fixture.relativeTo(repoRoot).path)
        }
    }

    @Test
    fun `homer-config schema rejects an unsafe remoteWorkspaceRoot`() {
        // Pins the `pattern` that mirrors RunnerConfig.assertSafeRemoteRoot (ADR-0067):
        // the example fixtures are all valid, so without this the line could vanish
        // unnoticed. Built from a valid fixture so the only violation is the root.
        val schema = loadSchema("homer-config.schema.json")
        val fixture = File(repoRoot, "example/config/config-custom.json").readText()
        val base = Json.parseToJsonElement(fixture).jsonObject
        val buildBox =
            base
                .getValue("runners")
                .jsonObject
                .getValue("build-box")
                .jsonObject

        fun withRoot(root: String): String {
            val runner = JsonObject(buildBox + ("remoteWorkspaceRoot" to JsonPrimitive(root)))
            val runners = JsonObject(mapOf("build-box" to runner))
            return JsonObject(base + ("runners" to runners)).toString()
        }
        for (root in listOf("", "/", "relative/ws", "/ws/../..", "/ws/./x", "/ws//x", "/ws/")) {
            val errors = schema.validate(withRoot(root), InputFormat.JSON)
            assertTrue(
                errors.any { it.message.contains("remoteWorkspaceRoot") || it.message.contains("pattern") },
                "pattern accepted '$root' (errors: ${errors.joinToString { it.message }})",
            )
        }
        validate(schema, withRoot("/home/homer/ws"), "config-custom.json with remoteWorkspaceRoot=/home/homer/ws")
    }

    @Test
    fun `no example agent opts into dangerouslySkipPermissions`() {
        // Acceptance criterion (GH-7): a bundled example must not skip Claude's
        // permission gate without a comment saying why. JSON has no comments at
        // all, so no example sets the flag; an example that legitimately needs it
        // relaxes this case to "YAML with an adjacent justification".
        val agentsDir = File(repoRoot, "example/agents")
        val offenders =
            agentsDir
                .walkTopDown()
                .filter { it.isFile && (it.name == "agent.json" || it.name == "agent.yaml") }
                .flatMap { fixture ->
                    val agent =
                        if (fixture.extension == "json") {
                            json.decodeFromString(Agent.serializer(), fixture.readText())
                        } else {
                            Yaml.default.decodeFromString(Agent.serializer(), fixture.readText())
                        }
                    agent.commands
                        .filterIsInstance<Command.Claude>()
                        .filter { it.options.dangerouslySkipPermissions }
                        .map { fixture.relativeTo(repoRoot).path }
                }.toList()

        assertTrue(
            offenders.isEmpty(),
            "These example agents skip the permission gate without a justification: $offenders",
        )
    }

    @Test
    fun `homer-agent schema validates all example agents (JSON and YAML)`() {
        // Use the shared production validator so local-debug (agentdebug validate)
        // and this backstop check the exact same schema + code path (audit P2 #28).
        val validator = AgentSchemaValidator()
        val agentsDir = File(repoRoot, "example/agents")
        val fixtures =
            agentsDir
                .walkTopDown()
                .filter { it.isFile && (it.name == "agent.json" || it.name == "agent.yaml") }
                .toList()
        assertTrue(fixtures.isNotEmpty(), "Expected at least one example agent fixture")

        for (fixture in fixtures) {
            val relPath = fixture.relativeTo(repoRoot).path
            val jsonContent =
                if (fixture.extension == "json") {
                    fixture.readText()
                } else {
                    val agent = Yaml.default.decodeFromString(Agent.serializer(), fixture.readText())
                    json.encodeToString(agent)
                }
            val errors = validator.validate(jsonContent)
            assertTrue(
                errors.isEmpty(),
                "Schema validation failed for $relPath:\n" + errors.joinToString("\n") { "  - $it" },
            )
        }
    }
}
