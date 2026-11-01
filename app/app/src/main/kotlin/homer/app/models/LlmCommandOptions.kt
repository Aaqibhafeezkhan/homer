package homer.app.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface LlmPrompt {
    @Serializable
    @SerialName("prompt")
    data class Prompt(
        val prompt: String,
    ) : LlmPrompt

    @Serializable
    @SerialName("langfuse")
    data class Langfuse(
        val promptName: String,
        val version: Int? = null,
        val label: String? = null,
    ) : LlmPrompt
}

/**
 * Retry configuration for the `claude` command.
 *
 * Wraps the entire CLI invocation (including stdin, env, and stdout
 * validation). Retries fire when the run finishes with a non-zero exit
 * code OR when its stdout fails the JSON-event-stream validation
 * (`MalformedLlmOutputException`). Other exceptions (timeouts,
 * cancellation, validation of `Command.Claude` itself) propagate
 * immediately.
 *
 * Backoff is exponential with full jitter:
 * `min(maxDelayMs, baseDelayMs * 2^attemptIndex + random(0, baseDelayMs))`
 * applied between attempts (no delay before the first attempt). The jitter
 * spreads concurrent retriers so they don't reconverge on the same instant.
 * With the default `attempts = 1` no retry happens and behavior is unchanged.
 */
@Serializable
data class LlmRetry(
    val attempts: Int = 1,
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 30_000,
) {
    init {
        require(attempts >= 1) { "LlmRetry.attempts must be >= 1" }
        require(baseDelayMs >= 0) { "LlmRetry.baseDelayMs must be >= 0" }
        require(maxDelayMs >= baseDelayMs) {
            "LlmRetry.maxDelayMs ($maxDelayMs) must be >= baseDelayMs ($baseDelayMs)"
        }
    }
}

/**
 * One escalation tier of an LLM step (ADR-0079): rerun the same prompt on [model], with the raw CLI
 * [options] list replaced when set, if [whenClause] — evaluated after the previous tier, with
 * `HMR_LLM_OUTCOME` and `HMR_LLM_EXIT_CODE` describing it — is true.
 */
@Serializable
data class LlmEscalation(
    val model: String,
    val options: List<String>? = null,
    @SerialName("when")
    val whenClause: When,
) {
    init {
        require(model.isNotBlank()) { "escalate[].model must be non-blank" }
    }
}

@Serializable
data class ClaudeSubagent(
    val prompt: String,
    val description: String? = null,
    val tools: List<String>? = null,
    val model: String? = null,
)

@Serializable
data class ClaudeCmdOptions(
    val agent: String? = null,
    val agents: Map<String, ClaudeSubagent>? = null,
    val maxTurns: Int = 40,
    // Opt-in: when Claude stops with result subtype `error_max_turns`, return this exit
    // code instead of failing the run with a validation error, so a later step (e.g. a
    // deterministic ship step) can decide what to do with the partial work. Unset keeps
    // the fail-closed behaviour.
    val maxTurnsExitCode: Int? = null,
    val maxMcpOutputTokens: Int? = null,
    val model: String? = null,
    val fallbackModel: String? = null,
    val systemPrompt: String? = null,
    val systemPromptFile: String? = null,
    val appendSystemPrompt: String? = null,
    val appendSystemPromptFile: String? = null,
    val permissionMode: String? = null,
    val permissionPromptTool: String? = null,
    val maxBudgetUsd: Double? = null,
    val sessionId: String? = null,
    @Serializable(with = StringOrStringListSerializer::class) val addDir: List<String>? = null,
    val allowedTools: List<String>? = null,
    val disallowedTools: List<String>? = null,
    @Serializable(with = StringOrStringListSerializer::class) val mcpConfig: List<String>? = null,
    val strictMcpConfig: Boolean = false,
    val includeHookEvents: Boolean = false,
    val includePartialMessages: Boolean = false,
    val inputFormat: String? = null,
    @Serializable(with = StringOrStringListSerializer::class) val pluginDir: List<String>? = null,
    @Serializable(with = StringOrStringListSerializer::class) val pluginUrl: List<String>? = null,
    @Serializable(with = StringOrStringListSerializer::class) val file: List<String>? = null,
    val settings: String? = null,
    val settingSources: List<String>? = null,
    val tools: List<String>? = null,
    val disableSlashCommands: Boolean = false,
    val effort: String? = null,
    val excludeDynamicSystemPromptSections: Boolean = false,
    val noSessionPersistence: Boolean = false,
    val bare: Boolean = false,
    val jsonSchema: String? = null,
    val betas: String? = null,
    val debugFile: String? = null,
    // Security: opt-in — passes --dangerously-skip-permissions to Claude CLI; only
    // safe in isolated/sandboxed environments. See ADR-0017.
    // When permissionMode is set, it takes precedence and the flag is omitted.
    val dangerouslySkipPermissions: Boolean = false,
    val options: List<String>? = null,
) {
    init {
        // CWE-394: maxTurnsExitCode must be in 1..255; a value of 0
        // would signal success on a max-turns failure.
        maxTurnsExitCode?.let {
            require(it in 1..255) {
                "maxTurnsExitCode must be in 1..255 (got $it); 0 would signal success on a max-turns failure"
            }
        }
    }
}

/**
 * Options for the `codex` command, mapped 1:1 to `codex exec` CLI flags.
 * The executor always appends `--json --color never` and reads the prompt
 * from stdin (`-` positional), mirroring how [ClaudeCmdOptions] is driven.
 */
@Serializable
data class CodexCmdOptions(
    val model: String? = null,
    val profile: String? = null,
    // read-only | workspace-write | danger-full-access
    val sandbox: String? = null,
    val cd: String? = null,
    val addDir: String? = null,
    val skipGitRepoCheck: Boolean = false,
    val ephemeral: Boolean = false,
    val ignoreUserConfig: Boolean = false,
    val ignoreRules: Boolean = false,
    val outputSchema: String? = null,
    val outputLastMessage: String? = null,
    // Security: opt-in — passes --dangerously-bypass-approvals-and-sandbox to the
    // Codex CLI; only safe in isolated/sandboxed environments (same reasoning as
    // ClaudeCmdOptions.dangerouslySkipPermissions, ADR-0017).
    // When sandbox is set, it takes precedence and the flag is omitted.
    val dangerouslyBypassApprovalsAndSandbox: Boolean = false,
    // Repeated `-c key=value` config overrides (TOML values).
    val config: List<String>? = null,
    val options: List<String>? = null,
)

/**
 * Options for the `opencode` command, mapped 1:1 to `opencode run` CLI flags.
 * The executor always appends `--format json` and reads the prompt from stdin,
 * mirroring how [CodexCmdOptions] is driven.
 */
@Serializable
data class OpencodeCmdOptions(
    val model: String? = null,
    val agent: String? = null,
    val dir: String? = null,
    // Repeated `-f path` attachments.
    val file: List<String>? = null,
    // Security: opt-in — passes --auto to the opencode CLI, which auto-approves
    // every permission the configuration does not explicitly deny; only safe in
    // isolated/sandboxed environments (same reasoning as
    // ClaudeCmdOptions.dangerouslySkipPermissions, ADR-0017).
    val auto: Boolean = false,
    val options: List<String>? = null,
)
