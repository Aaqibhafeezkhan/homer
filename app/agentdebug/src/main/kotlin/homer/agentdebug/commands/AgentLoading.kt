package homer.agentdebug.commands

import homer.app.models.AppConfig
import homer.app.services.AgentsService
import org.slf4j.Logger

/** The agents under [AppConfig.agentsSearchPath], loaded knowing `local` and every configured runner alias. */
internal fun AppConfig.loadAgents(logger: Logger): AgentsService =
    AgentsService(agentsSearchPath, logger, runnerAliases = setOf("local") + runners.keys)
