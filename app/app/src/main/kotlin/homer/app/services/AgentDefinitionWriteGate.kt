package homer.app.services

import com.charleskorn.kaml.Yaml
import homer.app.infra.AgentDirs
import homer.app.models.Agent
import kotlinx.serialization.json.Json
import org.slf4j.Logger

/**
 * The grant and schema gate of an [AgentFilesService.write] landing on a definition
 * file: a write that would create an agent needs the create-agent grant on the name
 * it declares, and every definition written must pass the load-time schema checks.
 */
internal class AgentDefinitionWriteGate(
    private val logger: Logger,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The denial of a write landing at [landingRel] (relative to the agent's real
     * directory), or null when the write may proceed. [relPath] is the name the
     * request used, the only one a denial may echo.
     */
    fun denial(
        landingRel: String,
        relPath: String,
        content: String,
        authorizeCreateAgent: (String) -> Boolean,
    ): AgentFilesService.FileOpResult<Unit>? {
        val isYaml = landingRel.endsWith(".yaml")
        // A definition write at ANY depth needs the create-agent grant on the name
        // it declares, denied as not-found (§12 rule 3). See ADR-0068. A body that
        // does not parse declares nothing and is denied the same way —
        // CWE-285: gating on `declared != null` alone let an
        // unparsable definition-named file through to create the boundary directory
        // on disk, which the file API then denies as not-found and nobody can
        // remove (fail open on malformed input, §12 rule 3). The agent's own root
        // definition is exempt: it cannot create an agent, and the schema
        // validation below already denies it (with the parse errors the caller
        // needs — a 404 there would only hide them).
        if (isDefFileName(landingRel)) {
            val declared = declaredAgentName(content, isYaml)
            if (declared == null) {
                if (!isAgentDef(landingRel)) return AgentFilesService.FileOpResult.NotFound("File not found: $relPath")
            } else if (!authorizeCreateAgent(declared)) {
                return AgentFilesService.FileOpResult.NotFound("File not found: $relPath")
            }
        }

        // At any depth: a written definition that fails validation would still mark
        // its directory as a nested-agent boundary no parent request can then reach.
        if (isDefFileName(landingRel)) {
            val errors = validateAgentDefinition(content, isYaml)
            if (errors.isNotEmpty()) return AgentFilesService.FileOpResult.SchemaInvalid(errors)
        }
        return null
    }

    private fun validateAgentDefinition(
        content: String,
        isYaml: Boolean,
    ): List<String> =
        try {
            val agent = decodeAgent(content, isYaml)
            buildList {
                if (!AgentDirs.AGENT_NAME_PATTERN.matches(agent.name)) {
                    add("Invalid agent name '${agent.name}': only letters, digits, '-', '_' allowed")
                }
                if (agent.commands.isEmpty()) add("Agent must have at least one command")
            }
        } catch (e: Exception) {
            logger.debug("Agent definition rejected: {}", e.message)
            listOf(e.message ?: "Failed to parse agent definition")
        }

    /** The `name:` a definition declares, when it parses; null when it does not (the schema validation reports that). */
    private fun declaredAgentName(
        content: String,
        isYaml: Boolean,
    ): String? =
        try {
            decodeAgent(content, isYaml).name
        } catch (_: Exception) {
            null
        }

    private fun decodeAgent(
        content: String,
        isYaml: Boolean,
    ): Agent =
        if (isYaml) {
            Yaml.default.decodeFromString(Agent.serializer(), content)
        } else {
            json.decodeFromString<Agent>(content)
        }
}
