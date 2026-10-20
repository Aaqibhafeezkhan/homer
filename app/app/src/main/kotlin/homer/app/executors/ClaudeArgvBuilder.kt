package homer.app.executors

import homer.app.infra.DefaultTextCompiler
import homer.app.infra.TextCompiler
import homer.app.models.ClaudeCmdOptions
import homer.app.models.ClaudeSubagent
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Builds the Claude CLI argv from a [ClaudeCmdOptions] block. Shared by
 * [homer.app.executors.llm.ClaudeHarness] and the agentdebug dry-run printer, so
 * neither ever drifts from what actually runs.
 *
 * Template resolution (`{{VAR}}`, see AGENT.md) is applied here against [env]:
 * subagent fields are resolved BEFORE JSON-encoding `--agents` (so the encoder
 * escapes substituted values rather than templating the encoded blob), and every
 * other argument is resolved after the list is assembled.
 */
class ClaudeArgvBuilder(
    private val textCompiler: TextCompiler = DefaultTextCompiler(),
) {
    private val agentsJson =
        Json {
            encodeDefaults = false
            explicitNulls = false
        }

    fun build(
        cmd: String,
        opts: ClaudeCmdOptions,
        env: Map<String, String>,
    ): List<String> {
        // Index of the JSON-encoded `--agents` value, excluded from the second
        // template pass below. -1 when no subagents are configured.
        val agentsJsonIndex: Int
        val argv =
            buildList {
                add(cmd)
                addModelAndPromptOpts(opts)
                addPermissionAndSessionOpts(opts)
                agentsJsonIndex = addAgentsOpt(opts, env)
                addToolAndSettingOpts(opts)
                addMiscOpts(opts)

                opts.options?.let { addAll(it) }
                add("--output-format")
                add("stream-json")
                add("--print")
                add("--verbose")
            }
        return argv.mapIndexed { index, arg ->
            // Resolve {{VAR}} placeholders in option values (see AGENT.md), except
            // the already-resolved-and-encoded `--agents` JSON (see above).
            if (index == agentsJsonIndex) arg else textCompiler.replaceArguments(arg, env)
        }
    }

    private fun MutableList<String>.addModelAndPromptOpts(opts: ClaudeCmdOptions) {
        // Agent / model
        addStringOpt("--agent", opts.agent)
        addStringOpt("--model", opts.model)
        addStringOpt("--fallback-model", opts.fallbackModel)

        // System prompt
        addStringOpt("--system-prompt", opts.systemPrompt)
        addStringOpt("--system-prompt-file", opts.systemPromptFile)
        addStringOpt("--append-system-prompt", opts.appendSystemPrompt)
        addStringOpt("--append-system-prompt-file", opts.appendSystemPromptFile)
        addBoolOpt("--exclude-dynamic-system-prompt-sections", opts.excludeDynamicSystemPromptSections)
    }

    private fun MutableList<String>.addPermissionAndSessionOpts(opts: ClaudeCmdOptions) {
        // Permissions
        if (opts.permissionMode != null) {
            add("--permission-mode")
            add(opts.permissionMode)
        } else {
            addBoolOpt("--dangerously-skip-permissions", opts.dangerouslySkipPermissions)
        }
        addStringOpt("--permission-prompt-tool", opts.permissionPromptTool)

        // Session
        addStringOpt("--session-id", opts.sessionId)
        addBoolOpt("--no-session-persistence", opts.noSessionPersistence)
    }

    /** Adds `--agents` when subagents are configured; returns the encoded value's index, or -1. */
    private fun MutableList<String>.addAgentsOpt(
        opts: ClaudeCmdOptions,
        env: Map<String, String>,
    ): Int {
        // Subagents. Resolve placeholders BEFORE encoding so the encoder
        // escapes substituted values, then EXCLUDE the encoded blob from the
        // second pass in build: a resolved value that itself contains `{{VAR}}`
        // would otherwise be re-substituted unescaped inside the JSON string,
        // letting a caller break out of a subagent field and inject sibling
        // keys (JSON injection).
        if (opts.agents == null) return -1
        add("--agents")
        val resolvedAgents = opts.agents.mapValues { (_, sub) -> sub.resolveTemplates(env) }
        add(agentsJson.encodeToString(MapSerializer(String.serializer(), ClaudeSubagent.serializer()), resolvedAgents))
        return size - 1
    }

    private fun MutableList<String>.addToolAndSettingOpts(opts: ClaudeCmdOptions) {
        // Tools
        addListOpt("--allowedTools", opts.allowedTools)
        addListOpt("--disallowedTools", opts.disallowedTools)
        addListOpt("--tools", opts.tools)
        addBoolOpt("--disable-slash-commands", opts.disableSlashCommands)

        // MCP
        addVariadicOpt("--mcp-config", opts.mcpConfig)
        addBoolOpt("--strict-mcp-config", opts.strictMcpConfig)

        // Settings
        addRepeatableOpt("--plugin-dir", opts.pluginDir)
        addRepeatableOpt("--plugin-url", opts.pluginUrl)
        addStringOpt("--settings", opts.settings)
        addListOpt("--setting-sources", opts.settingSources)
    }

    private fun MutableList<String>.addMiscOpts(opts: ClaudeCmdOptions) {
        addStringOpt("--max-turns", opts.maxTurns.toString())
        addVariadicOpt("--add-dir", opts.addDir)
        addVariadicOpt("--file", opts.file)
        addDoubleOpt("--max-budget-usd", opts.maxBudgetUsd)
        addStringOpt("--effort", opts.effort)
        addBoolOpt("--bare", opts.bare)
        addStringOpt("--json-schema", opts.jsonSchema)
        addStringOpt("--betas", opts.betas)
        addStringOpt("--debug-file", opts.debugFile)
        addBoolOpt("--include-hook-events", opts.includeHookEvents)
        addBoolOpt("--include-partial-messages", opts.includePartialMessages)
        addStringOpt("--input-format", opts.inputFormat)
    }

    private fun ClaudeSubagent.resolveTemplates(env: Map<String, String>): ClaudeSubagent =
        copy(
            prompt = textCompiler.replaceArguments(prompt, env),
            description = description?.let { textCompiler.replaceArguments(it, env) },
            tools = tools?.map { textCompiler.replaceArguments(it, env) },
            model = model?.let { textCompiler.replaceArguments(it, env) },
        )
}
