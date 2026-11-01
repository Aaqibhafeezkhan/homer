package homer.app.executors

import homer.app.executors.llm.ClaudeHarness
import homer.app.executors.llm.CodexHarness
import homer.app.executors.llm.CostRecorder
import homer.app.executors.llm.LlmHarnessExecutor
import homer.app.executors.llm.LlmStepTelemetry
import homer.app.executors.llm.OpencodeHarness
import homer.app.infra.FileOperations
import homer.app.infra.LangfuseClient
import homer.app.infra.LlmCostResolver
import homer.app.models.Command
import kotlinx.coroutines.CoroutineScope

/**
 * Factory for creating appropriate command executors
 */
class CommandExecutorFactory(
    langfuseClient: LangfuseClient,
    llmTelemetryService: LlmStepTelemetry,
    fileOperations: FileOperations,
    stepRecords: StepRecords,
    costRecorder: CostRecorder,
    /** Where LLM session lookups settle after their step returned; must outlive the runs (ADR-0089). */
    settlementScope: CoroutineScope,
    llmCostResolver: LlmCostResolver = LlmCostResolver.DISABLED,
    private val runAgentExecutor: CommandExecutor<Command.RunAgent>,
    private val langGraphExecutor: CommandExecutor<Command.LangGraph>,
    private val runnerCmdExecutor: CommandExecutor<Command.RunnerCmd>,
    requireClaudeCodeVersion: String? = null,
    claudeDropSystemSubtypes: Set<String> = emptySet(),
) {
    private val cmdExecutor = CmdExecutor(fileOperations)
    private val scriptExecutor = ScriptExecutor(fileOperations)
    private val claudeExecutor =
        LlmHarnessExecutor(
            ClaudeHarness(requireClaudeCodeVersion = requireClaudeCodeVersion, dropSystemSubtypes = claudeDropSystemSubtypes),
            langfuseClient,
            llmTelemetryService,
            stepRecords,
            costRecorder,
            settlementScope,
            llmCostResolver = llmCostResolver,
        )
    private val codexExecutor =
        LlmHarnessExecutor(
            CodexHarness(),
            langfuseClient,
            llmTelemetryService,
            stepRecords,
            costRecorder,
            settlementScope,
            llmCostResolver = llmCostResolver,
        )
    private val opencodeExecutor =
        LlmHarnessExecutor(
            OpencodeHarness(),
            langfuseClient,
            llmTelemetryService,
            stepRecords,
            costRecorder,
            settlementScope,
            llmCostResolver = llmCostResolver,
        )

    /**
     * Get executor for a specific command type
     */
    fun <T : Command> getExecutor(command: T): CommandExecutor<T> {
        val executor =
            when (command) {
                is Command.Cmd -> cmdExecutor
                is Command.Script -> scriptExecutor
                is Command.RunAgent -> runAgentExecutor
                is Command.LangGraph -> langGraphExecutor
                is Command.RunnerCmd -> runnerCmdExecutor
                is Command.LlmCommand -> llmExecutor(command)
            }
        @Suppress("UNCHECKED_CAST")
        return executor as CommandExecutor<T>
    }

    /** One executor per harness, all of them [LlmHarnessExecutor] over a different adapter (ADR-0070). */
    private fun llmExecutor(command: Command.LlmCommand): CommandExecutor<*> =
        when (command) {
            is Command.Claude -> claudeExecutor
            is Command.Codex -> codexExecutor
            is Command.Opencode -> opencodeExecutor
        }
}
