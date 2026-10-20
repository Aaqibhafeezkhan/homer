package homer.agentdebug.commands

import com.github.ajalt.clikt.core.ProgramResult
import homer.agentdebug.ConfigLoader
import homer.agentdebug.execution.DebugAgentRunner
import homer.app.models.AppConfig
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

class RunCommand(
    private val appConfig: AppConfig,
    private val agentName: String,
    private val envFile: String?,
    private val inputFile: String?,
    private val dryRun: Boolean,
    private val keepWorkspace: Boolean,
    private val cmdIndex: Int?,
) {
    private val logger = LoggerFactory.getLogger("agentdebug")

    fun run() {
        val agentsService = appConfig.loadAgents(logger)
        val agent =
            agentsService.agents[agentName]
                ?: error("Agent '$agentName' not found. Available: ${agentsService.agents.keys.sorted().joinToString(", ")}")

        val envOverrides = envFile?.let { ConfigLoader.loadEnvFile(it) } ?: emptyMap()
        val inputMap = inputFile?.let { ConfigLoader.loadEnvFile(it) } ?: emptyMap()

        val failed =
            runBlocking {
                DebugAgentRunner(
                    appConfig = appConfig,
                    agent = agent,
                    envOverrides = envOverrides,
                    inputMap = inputMap,
                    dryRun = dryRun,
                    keepWorkspace = keepWorkspace,
                    cmdIndex = cmdIndex,
                    logger = logger,
                ).run()
            }
        if (failed) throw ProgramResult(1)
    }
}
