package homer.agentdebug

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import homer.agentdebug.commands.HashPasswordCommand
import homer.agentdebug.commands.ListCommand
import homer.agentdebug.commands.RunCommand
import homer.agentdebug.commands.ShowCommandsCommand
import homer.agentdebug.commands.ValidateCommand

/**
 * Root Clikt command. Parses --config/--env and delegates to subcommands.
 *
 * Usage:
 *   agentdebug --config server/config.yaml [--env vars.env] list
 *   agentdebug --config server/config.yaml [--env vars.env] validate <agentname>
 *   agentdebug --config server/config.yaml [--env vars.env] show-commands <agentname>
 *   agentdebug --config server/config.yaml [--env vars.env] run <agentname> [--input input.env] [--dry-run] [--cmd N] [--keep-workspace]
 */
class AgentdebugCli : CliktCommand(name = "agentdebug") {
    override fun help(context: Context) =
        """
        Homer Agent Debugger — validate, inspect, and run agents locally.

        Usage:
          agentdebug --config <config> [--env <vars.env>] list
          agentdebug --config <config> [--env <vars.env>] validate <agentname>
          agentdebug --config <config> [--env <vars.env>] show-commands <agentname>
          agentdebug --config <config> [--env <vars.env>] run <agentname> [--input <input.env>] [--dry-run] [--cmd N] [--keep-workspace]
        """.trimIndent()

    val config by option("--config", help = "Path to Homer app config (app.yaml or app.json)")
    val envFile by option("--env", help = "Env file (key=value) loaded before config — used to resolve @SYSENV: references")

    override fun run() {}
}

private fun AgentdebugCli.loadConfig(): homer.app.models.AppConfig {
    val cfg = config ?: error("--config is required for this subcommand")
    val extraEnv = envFile?.let { ConfigLoader.loadEnvFile(it) } ?: emptyMap()
    return ConfigLoader.loadAppConfig(cfg, extraEnv)
}

class ListSubcommand : CliktCommand(name = "list") {
    override fun help(context: Context) = "List all available agents"

    override fun run() {
        val root = currentContext.findRoot().command as AgentdebugCli
        ListCommand(root.loadConfig()).run()
    }
}

class ValidateSubcommand : CliktCommand(name = "validate") {
    override fun help(context: Context) = "Validate an agent's structure"

    private val agentName by argument("agentname", help = "Agent name")

    override fun run() {
        val root = currentContext.findRoot().command as AgentdebugCli
        val cfg = root.config ?: error("--config is required for this subcommand")
        ValidateCommand(root.loadConfig(), agentName, cfg).run()
    }
}

class ShowCommandsSubcommand : CliktCommand(name = "show-commands") {
    override fun help(context: Context) = "Show commands and required inputs"

    private val agentName by argument("agentname", help = "Agent name")

    override fun run() {
        val root = currentContext.findRoot().command as AgentdebugCli
        ShowCommandsCommand(root.loadConfig(), agentName).run()
    }
}

class RunSubcommand : CliktCommand(name = "run") {
    override fun help(context: Context) = "Run agent commands"

    private val agentName by argument("agentname", help = "Agent name")
    private val inputFile by option("--input", help = "Env file (key=value) with query param values")
    private val dryRun by option("--dry-run", help = "Print commands without executing").flag()
    private val keepWorkspace by option("--keep-workspace", help = "Keep workspace after run").flag()
    private val cmdIndex by option("--cmd", help = "Run only command at index N (0-based)").convert { it.toInt() }

    override fun run() {
        val root = currentContext.findRoot().command as AgentdebugCli
        val appConfig = root.loadConfig()
        RunCommand(appConfig, agentName, root.envFile, inputFile, dryRun, keepWorkspace, cmdIndex).run()
    }
}

class HashPasswordSubcommand : CliktCommand(name = "hash-password") {
    override fun help(context: Context) = "Generate a bcrypt hash for AuthConfig.Password.users"

    private val cost by option("--cost", help = "bcrypt cost (4..15)").convert { it.toInt() }.default(12)

    override fun run() {
        HashPasswordCommand(cost).run()
    }
}

fun main(args: Array<String>) {
    AgentdebugCli()
        .subcommands(
            ListSubcommand(),
            ValidateSubcommand(),
            ShowCommandsSubcommand(),
            RunSubcommand(),
            HashPasswordSubcommand(),
        ).main(args)
}
