package homer.agentdebug.commands

import homer.app.models.AppConfig
import org.slf4j.LoggerFactory

class ListCommand(
    private val appConfig: AppConfig,
) {
    private val logger = LoggerFactory.getLogger("agentdebug")

    fun run() {
        val agentsService = appConfig.loadAgents(logger)
        val agents = agentsService.agents
        println("Available agents (${agents.size}):")
        agents.values.sortedBy { it.name }.forEach { agent ->
            val cmdCount = agent.commands.size
            val cronPart = agent.cron?.let { ", cron: ${it.expression}" } ?: ""
            val namePart = agent.name.padEnd(20)
            val descPart = agent.description?.let { "- $it " } ?: ""
            println("  $namePart $descPart($cmdCount command${if (cmdCount != 1) "s" else ""}$cronPart)")
        }
    }
}
