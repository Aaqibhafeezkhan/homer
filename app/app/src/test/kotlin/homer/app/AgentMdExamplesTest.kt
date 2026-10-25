package homer.app

import com.charleskorn.kaml.Yaml
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import homer.app.fixtures.repoRoot
import homer.app.models.Agent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Structural backstop for [`app/AGENT.md`](../../../../../AGENT.md).
 *
 * Every complete-agent YAML/JSON code block in AGENT.md must:
 *  1. Deserialize via [Agent.serializer] (matches how `AgentsService`
 *     loads agents from disk).
 *  2. Re-validate against `homer-agent.schema.json`.
 *
 * Catches structural drift like:
 *  - `agents: [code-reviewer]` list form when the model expects a map.
 *  - `resultEnvParam: { ... }` object form when the model expects an array.
 *  - Renamed/removed kaml tags after a `Command` sealed-class refactor.
 *
 * A second test scans every yaml/json block (partial snippets included) and
 * fails on the deprecated param types `STRING`/`NUMBER`/`ANY` and on the
 * agent-level `queryParams` alias. A block that deliberately shows the legacy
 * syntax opts out with `<!-- legacy-syntax-example -->` on the line before its
 * fence. A `queryParams` nested under a `!<agent>` command is a different field
 * and is not flagged.
 *
 * Does NOT catch prose drift (wrong default values in field tables,
 * misdescribed validation semantics, missing rows). Per root §10 those
 * rely on reviewer discipline when changing the model.
 */
class AgentMdExamplesTest {
    private val agentMd = File(repoRoot, "app/AGENT.md")
    private val schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
    private val schema =
        schemaRegistry.getSchema(File(repoRoot, "app/schemas/homer-agent.schema.json").readText())
    private val json =
        Json {
            prettyPrint = false
            encodeDefaults = true
            explicitNulls = false
        }

    @Test
    fun `every complete-agent code block in AGENT md deserializes and validates against schema`() {
        val blocks = extractCompleteAgentBlocks(agentMd.readText())
        assertTrue(
            blocks.isNotEmpty(),
            "Expected at least one complete-agent block in AGENT.md — extractor regression?",
        )

        val failures = mutableListOf<String>()
        for (block in blocks) {
            try {
                val agent =
                    when (block.lang) {
                        "yaml" -> Yaml.default.decodeFromString(Agent.serializer(), block.content)
                        "json" -> json.decodeFromString(Agent.serializer(), block.content)
                        else -> error("unreachable: ${block.lang}")
                    }
                val asJson = json.encodeToString(Agent.serializer(), agent)
                val errors = schema.validate(asJson, InputFormat.JSON)
                if (errors.isNotEmpty()) {
                    failures += renderFailure(block, "Schema validation failed", errors.joinToString("\n") { "    - ${it.message}" })
                }
            } catch (e: Exception) {
                failures += renderFailure(block, "Deserialization failed", "    ${e.message}")
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                "AGENT.md drift detected (${failures.size} of ${blocks.size} blocks failed):\n\n" +
                    failures.joinToString("\n\n"),
            )
        }
    }

    @Test
    fun `no AGENT md example uses the deprecated param syntax unless marked`() {
        val legacyType = Regex("""^\s*(?:-\s*)?"?type"?\s*:\s*"?(STRING|NUMBER|ANY)"?\s*,?\s*(#.*)?$""")
        val agentLevelQueryParams = Regex("""^(?:queryParams:| {2}"queryParams"\s*:)""")
        val lines = agentMd.readText().lines()
        val offenders = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val lang = lines[i].trimEnd().removePrefix("```").takeIf { lines[i].startsWith("```") }
            if (lang != "yaml" && lang != "json") {
                i++
                continue
            }
            val marked = i > 0 && lines[i - 1].trim() == "<!-- legacy-syntax-example -->"
            val fenceLine = i + 1
            i++
            while (i < lines.size && lines[i].trimEnd() != "```") {
                if (!marked && (legacyType.matches(lines[i]) || agentLevelQueryParams.containsMatchIn(lines[i]))) {
                    offenders += "AGENT.md:$fenceLine (block) line ${i + 1}: ${lines[i].trim()}"
                }
                i++
            }
            i++
        }
        if (offenders.isNotEmpty()) {
            fail(
                "Deprecated param syntax in AGENT.md examples (use typed inputs, or mark a deliberate " +
                    "legacy example with <!-- legacy-syntax-example -->):\n" + offenders.joinToString("\n"),
            )
        }
    }

    private fun renderFailure(
        block: Block,
        reason: String,
        detail: String,
    ): String {
        val preview =
            block.content
                .lines()
                .take(4)
                .joinToString("\n") { "    $it" }
        return "$reason at AGENT.md:${block.startLine} (${block.lang}):\n$detail\n  Block preview:\n$preview\n    ..."
    }

    private data class Block(
        val lang: String,
        val content: String,
        val startLine: Int,
    )

    /**
     * Walks the file line by line. A fenced ```yaml or ```json block is
     * a "complete agent" if its top-level lines include both a name
     * declaration and a commands declaration. Partial snippets
     * (command-only, workspace-only, API-response examples, etc.) are
     * skipped.
     */
    private fun extractCompleteAgentBlocks(text: String): List<Block> {
        val lines = text.lines()
        val out = mutableListOf<Block>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val lang =
                when (line.trimEnd()) {
                    "```yaml" -> "yaml"
                    "```json" -> "json"
                    else -> null
                }
            if (lang == null) {
                i++
                continue
            }
            val fenceStart = i
            i++
            val contentStart = i
            while (i < lines.size && lines[i].trimEnd() != "```") i++
            val content = lines.subList(contentStart, i).joinToString("\n")
            i++ // step past closing fence
            if (isCompleteAgent(lang, content)) {
                out.add(Block(lang, content, fenceStart + 1))
            }
        }
        return out
    }

    private fun isCompleteAgent(
        lang: String,
        content: String,
    ): Boolean =
        when (lang) {
            "yaml" -> {
                content.lineSequence().any { it.startsWith("name:") } &&
                    content.lineSequence().any { it.startsWith("commands:") }
            }

            "json" -> {
                // Top-level keys are formatted at indent 2 in AGENT.md JSON
                // blocks (`  "name":`, `  "commands":`).
                val topLevelName = Regex("""^ {2}"name"\s*:""", RegexOption.MULTILINE)
                val topLevelCommands = Regex("""^ {2}"commands"\s*:""", RegexOption.MULTILINE)
                topLevelName.containsMatchIn(content) && topLevelCommands.containsMatchIn(content)
            }

            else -> {
                false
            }
        }
}
