package homer.app.models

import homer.app.EnvConstants
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class Command {
    abstract val label: String
    abstract val timeout: Long
    abstract val continueIfFail: Boolean
    abstract val finishExitCode: Int?
    abstract val resultEnvParam: List<ResultEnvParam>
    abstract val whenClause: When?

    /** What the LLM harness command types share, so one executor can drive them all. See ADR-0070. */
    sealed interface LlmCommand {
        val cmd: String
        val prompt: LlmPrompt
        val retry: LlmRetry?
        val label: String
        val timeout: Long

        /** The model requested in the command's options, if any (ADR-0070: no per-harness `when` outside the adapter). */
        val model: String?

        /** Models to rerun on when a tier's `when` judges the previous outcome bad; empty runs once, as before. See ADR-0079. */
        val escalate: List<LlmEscalation>
    }

    @Serializable
    @SerialName("cmd")
    data class Cmd(
        @Serializable(with = CmdArgsSerializer::class)
        val cmd: List<String>,
        override val label: String = "cmd",
        override val timeout: Long = 60,
        override val continueIfFail: Boolean = false,
        override val finishExitCode: Int? = null,
        override val resultEnvParam: List<ResultEnvParam> = emptyList(),
        @SerialName("when")
        override val whenClause: When? = null,
    ) : Command() {
        init {
            require(cmd.isNotEmpty()) { "cmd must not be empty" }
        }
    }

    // Each variant restates Command's shared fields: kotlinx.serialization reads the wire shape from each constructor.
    // jscpd:ignore-start
    @Serializable
    @SerialName("script")
    data class Script(
        val content: String,
        val shell: String = "sh",
        override val label: String = "script",
        override val timeout: Long = 60,
        override val continueIfFail: Boolean = false,
        override val finishExitCode: Int? = null,
        override val resultEnvParam: List<ResultEnvParam> = emptyList(),
        @SerialName("when")
        override val whenClause: When? = null,
    ) : Command()

    @Serializable
    @SerialName("claude")
    data class Claude(
        override val cmd: String = "claude",
        override val prompt: LlmPrompt,
        val options: ClaudeCmdOptions,
        override val retry: LlmRetry? = null,
        override val label: String = "claude",
        override val timeout: Long = 60,
        override val continueIfFail: Boolean = false,
        override val finishExitCode: Int? = null,
        override val resultEnvParam: List<ResultEnvParam> = emptyList(),
        @SerialName("when")
        override val whenClause: When? = null,
        override val escalate: List<LlmEscalation> = emptyList(),
    ) : Command(),
        LlmCommand {
        override val model: String? get() = options.model

        init {
            requireEscalationBound(escalate)
        }
    }

    @Serializable
    @SerialName("codex")
    data class Codex(
        override val cmd: String = "codex",
        override val prompt: LlmPrompt,
        val options: CodexCmdOptions = CodexCmdOptions(),
        override val retry: LlmRetry? = null,
        override val label: String = "codex",
        override val timeout: Long = 60,
        override val continueIfFail: Boolean = false,
        override val finishExitCode: Int? = null,
        override val resultEnvParam: List<ResultEnvParam> = emptyList(),
        @SerialName("when")
        override val whenClause: When? = null,
        override val escalate: List<LlmEscalation> = emptyList(),
    ) : Command(),
        LlmCommand {
        override val model: String? get() = options.model

        init {
            requireEscalationBound(escalate)
        }
    }

    @Serializable
    @SerialName("opencode")
    data class Opencode(
        override val cmd: String = "opencode",
        override val prompt: LlmPrompt,
        val options: OpencodeCmdOptions = OpencodeCmdOptions(),
        override val retry: LlmRetry? = null,
        override val label: String = "opencode",
        override val timeout: Long = 60,
        override val continueIfFail: Boolean = false,
        override val finishExitCode: Int? = null,
        override val resultEnvParam: List<ResultEnvParam> = emptyList(),
        @SerialName("when")
        override val whenClause: When? = null,
        override val escalate: List<LlmEscalation> = emptyList(),
    ) : Command(),
        LlmCommand {
        override val model: String? get() = options.model

        init {
            requireEscalationBound(escalate)
        }
    }

    @Serializable
    @SerialName("langgraph")
    data class LangGraph(
        val module: String,
        val python: String = "python3",
        val inputEnv: String = EnvConstants.LG_INPUT,
        override val label: String = "langgraph",
        override val timeout: Long = 60,
        override val continueIfFail: Boolean = false,
        override val finishExitCode: Int? = null,
        override val resultEnvParam: List<ResultEnvParam> = emptyList(),
        @SerialName("when")
        override val whenClause: When? = null,
    ) : Command()

    @Serializable
    @SerialName("runnerCmd")
    data class RunnerCmd(
        val runner: String,
        val execute: String,
        val shell: String = "sh",
        val check: List<String> = emptyList(),
        val syncToRemote: List<String> = emptyList(),
        val syncFromRemote: List<String> = emptyList(),
        override val label: String = "runnerCmd",
        override val timeout: Long = 60,
        override val continueIfFail: Boolean = false,
        override val finishExitCode: Int? = null,
        override val resultEnvParam: List<ResultEnvParam> = emptyList(),
        @SerialName("when")
        override val whenClause: When? = null,
    ) : Command() {
        init {
            require(runner.isNotBlank()) { "runnerCmd.runner must not be blank" }
            require(execute.isNotBlank()) { "runnerCmd.execute must not be blank" }
            require(check.all { it.isNotBlank() }) { "runnerCmd.check entries must be non-blank" }
        }
    }

    @Serializable
    @SerialName("agent")
    data class RunAgent(
        val agentName: String,
        val queryParams: List<AgentCommandParam> = emptyList(),
        override val label: String = "agent",
        override val timeout: Long = 60,
        override val continueIfFail: Boolean = false,
        override val finishExitCode: Int? = null,
        override val resultEnvParam: List<ResultEnvParam> = emptyList(),
        @SerialName("when")
        override val whenClause: When? = null,
    ) : Command()
    // jscpd:ignore-end
}

/** The kaml/JSON tag of a command — the same string the status API reports as an execution's `kind`. */
val Command.kind: String
    get() =
        when (this) {
            is Command.Cmd -> "cmd"
            is Command.Script -> "script"
            is Command.RunAgent -> "agent"
            is Command.LangGraph -> "langgraph"
            is Command.RunnerCmd -> "runnerCmd"
            is Command.LlmCommand -> harnessKind
        }

/** The tag of an LLM harness command, kept apart so the `Command` `when` stays one arm per harness. */
private val Command.LlmCommand.harnessKind: String
    get() =
        when (this) {
            is Command.Claude -> "claude"
            is Command.Codex -> "codex"
            is Command.Opencode -> "opencode"
        }

/** CWE-400: every tier multiplies a step's spend and runtime, so the list is bounded (ADR-0047 rule 4, ADR-0079). */
const val MAX_LLM_ESCALATION_TIERS = 3

private fun requireEscalationBound(escalate: List<LlmEscalation>) {
    require(escalate.size <= MAX_LLM_ESCALATION_TIERS) {
        "escalate has ${escalate.size} tiers; at most $MAX_LLM_ESCALATION_TIERS are allowed"
    }
}
