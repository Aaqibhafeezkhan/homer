package homer.agentdebug.commands

import com.github.ajalt.clikt.core.ProgramResult
import homer.app.config.AgentSchemaValidator
import homer.app.models.Agent
import homer.app.models.AppConfig
import homer.app.models.Command
import homer.app.models.LlmPrompt
import homer.app.models.kind
import org.slf4j.LoggerFactory

class ValidateCommand(
    private val appConfig: AppConfig,
    private val agentName: String,
    private val configPath: String,
) {
    private val logger = LoggerFactory.getLogger("agentdebug")

    fun run() {
        println("Loading config: $configPath")
        println("Agents path: ${appConfig.agentsSearchPath}")

        val agentsService = appConfig.loadAgents(logger)
        val agent = agentsService.agents[agentName]

        println("Agent: $agentName")
        println()

        if (agent == null) {
            println("✗ Agent '$agentName' not found in ${appConfig.agentsSearchPath}")
            println("  Available: ${agentsService.agents.keys.sorted().joinToString(", ")}")
            throw ProgramResult(1)
        }

        println("✓ Loaded successfully")
        println("✓ Name: ${agent.name}")
        println("✓ Commands: ${agent.commands.size} (non-empty)")

        agent.commands.forEachIndexed { i, cmd -> println("✓ Command[$i] ${commandStatus(cmd)}") }

        printInputs(agent)
        printSchemaValidation(agent)
    }

    /** The validation line for one command, after its `Command[i]` prefix. */
    private fun commandStatus(cmd: Command): String =
        when (cmd) {
            is Command.Cmd -> "cmd: valid"
            is Command.Script -> "script: valid"
            is Command.LlmCommand -> "${cmd.kind}: valid${langfuseNote(cmd.prompt)}"
            is Command.RunAgent -> "agent: valid (note: will be SKIPPED in debug run)"
            is Command.LangGraph -> "langgraph: valid (note: will be SKIPPED in debug run)"
            is Command.RunnerCmd -> "runnerCmd: valid (note: will be SKIPPED in debug run)"
        }

    private fun printInputs(agent: Agent) {
        val params = agent.effectiveInputs
        if (params.isNullOrEmpty()) {
            println("✓ Inputs: none")
        } else {
            println("✓ Inputs: ${params.size} params")
            params.forEach { p ->
                val req = if (p.required) "required" else "optional"
                println("  ${p.paramName} (${p.type.wireName}, $req) -> env: ${p.envName}")
            }
        }
    }

    private fun printSchemaValidation(agent: Agent) {
        // Run the same JSON-schema validation the server's bundled schema and
        // SchemaValidationTest enforce, so local-debug doesn't diverge from the
        // shipped contract (audit P2 #28).
        val schemaErrors = AgentSchemaValidator().validate(agent)
        if (schemaErrors.isEmpty()) {
            println("✓ Schema: valid")
            println("Validation passed.")
        } else {
            println("✗ Schema validation failed:")
            schemaErrors.forEach { println("  - $it") }
            println("Validation FAILED.")
            throw ProgramResult(1)
        }
    }

    /** The note an LLM step's Langfuse prompt gets, since debug mode cannot fetch it; empty for an inline prompt. */
    private fun langfuseNote(prompt: LlmPrompt): String =
        if (prompt is LlmPrompt.Langfuse) {
            " (note: Langfuse prompt '${prompt.promptName}' not available in debug mode)"
        } else {
            ""
        }
}
