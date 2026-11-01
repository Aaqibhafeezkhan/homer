package homer.agentdebug.commands

import homer.app.models.AppConfig
import homer.app.models.Command
import homer.app.models.kind
import org.slf4j.LoggerFactory

class ShowCommandsCommand(
    private val appConfig: AppConfig,
    private val agentName: String,
) {
    private val logger = LoggerFactory.getLogger("agentdebug")

    fun run() {
        val agentsService = appConfig.loadAgents(logger)
        val agent =
            agentsService.agents[agentName]
                ?: error("Agent '$agentName' not found")

        println("Agent: ${agent.name}")
        agent.description?.let { println("Description: $it") }
        println("Commands (${agent.commands.size}):")

        agent.commands.forEachIndexed { i, cmd ->
            println("  [$i] ${cmd.kind.padEnd(7)} \"${cmd.label}\"    timeout=${cmd.timeout}s continueIfFail=${cmd.continueIfFail}")
            printWhenLine(cmd)
            detailLines(cmd).forEach { println("      $it") }
        }

        val params = agent.effectiveInputs
        if (!params.isNullOrEmpty()) {
            println()
            println("Inputs:")
            params.forEach { p ->
                val req = if (p.required) "required" else "optional"
                println("  ${p.paramName.padEnd(14)} ${p.type.wireName.padEnd(8)} $req  -> env: ${p.envName}")
            }
        }
    }

    /** The lines printed under [cmd]'s header: what it runs, or that debug mode skips it. */
    private fun detailLines(cmd: Command): List<String> =
        when (cmd) {
            is Command.Cmd -> {
                listOf("cmd: ${cmd.cmd.joinToString(" ")}") +
                    cmd.resultEnvParam.map { r -> "outputs: ${r.envName} <- ${r.src} (${r.transformer})" }
            }

            is Command.Script -> {
                val lines =
                    cmd.content
                        .trimEnd()
                        .lines()
                        .size
                listOf("shell: ${cmd.shell}  stdin: $lines lines")
            }

            is Command.LlmCommand -> {
                listOf("cmd: ${llmArgvLine(cmd)}")
            }

            is Command.RunAgent -> {
                listOf("agentName: ${cmd.agentName}  [SKIPPED in debug mode]")
            }

            is Command.LangGraph -> {
                listOf("module: ${cmd.module}  (python: ${cmd.python})  [SKIPPED in debug mode]")
            }

            is Command.RunnerCmd -> {
                listOf("runner: ${cmd.runner}  [SKIPPED in debug mode]")
            }
        }

    /** The argv an LLM harness command runs, as its own adapter builds it. */
    private fun llmArgvLine(cmd: Command.LlmCommand): String =
        when (cmd) {
            is Command.Claude -> "${cmd.cmd} --max-turns ${cmd.options.maxTurns} --output-format json --print --verbose"
            is Command.Codex -> "${cmd.cmd} exec --json --color never -"
            is Command.Opencode -> "${cmd.cmd} run --format json"
        }

    private fun printWhenLine(cmd: Command) {
        cmd.whenClause?.let { clause ->
            val op = clause.operator.name.lowercase()
            println("      when ($op): ${clause.expressions.joinToString(" ; ")}")
        }
    }
}
