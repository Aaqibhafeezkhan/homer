package homer.agentdebug.execution

import homer.agentdebug.stubs.DebugFileOperations
import homer.agentdebug.stubs.DebugLangfuseClient
import homer.agentdebug.stubs.SkipLangGraphExecutor
import homer.agentdebug.stubs.SkipRunAgentExecutor
import homer.agentdebug.stubs.SkipRunnerCmdExecutor
import homer.app.EnvConstants
import homer.app.config.ExecutionConfig
import homer.app.executors.ArtifactNameResolver
import homer.app.executors.CommandExecutorFactory
import homer.app.executors.ExecutionContext
import homer.app.infra.DefaultTextCompiler
import homer.app.infra.LlmCostResolver
import homer.app.infra.OtelTelemetryService
import homer.app.infra.ProcessJobRegistry
import homer.app.infra.ResultToEnvMapper
import homer.app.infra.SecretRedactor
import homer.app.infra.WhenEvaluator
import homer.app.infra.Workspace
import homer.app.infra.WorkspaceConfigFactory
import homer.app.models.Agent
import homer.app.models.AppConfig
import homer.app.models.Command
import homer.app.models.CommandResult
import homer.app.models.LangfuseConfig
import homer.app.models.OpenTelemetryExport
import homer.app.models.kind
import homer.app.services.CostMeter
import homer.app.services.LlmTelemetryService
import homer.app.services.ProcessManager
import homer.app.services.persistence.CostRepository
import homer.app.services.persistence.DatabaseService
import homer.app.services.persistence.ProcessRepository
import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class DebugAgentRunner(
    private val appConfig: AppConfig,
    private val agent: Agent,
    private val envOverrides: Map<String, String>,
    private val inputMap: Map<String, String>,
    private val dryRun: Boolean,
    private val keepWorkspace: Boolean,
    private val cmdIndex: Int?,
    private val logger: Logger,
) {
    private val dummyLangfuseConfig =
        LangfuseConfig(
            serverUrl = "http://localhost",
            publicKey = "pk-debug",
            secretKey = "sk-debug",
        )

    /** Runs the agent's commands; `true` = a command failed or a `when` aborted the run. */
    suspend fun run(): Boolean {
        val textCompiler = DefaultTextCompiler()
        val executorFactory = debugExecutorFactory()
        val workspace = debugWorkspace(textCompiler)

        val commands = agent.commands
        val totalCmds = commands.size
        val globalEnvCount = appConfig.globalEnv.size
        val agentEnvCount = agent.env.size
        val inputCount = inputMap.size
        val totalVars = workspace.config.env.size

        println("[INFO] Agent: ${agent.name} ($totalCmds command${if (totalCmds != 1) "s" else ""})")
        println("[INFO] Workspace: ${workspace.config.projectRoot}")
        println("[INFO] Env: $globalEnvCount global + $agentEnvCount agent + $inputCount input = $totalVars vars")

        val executionConfig = ExecutionConfig()
        val tally = RunTally()

        val indicesToRun = if (cmdIndex != null) listOf(cmdIndex) else commands.indices.toList()

        for (i in indicesToRun) {
            val cmd = commands.getOrNull(i) ?: break
            if (tally.record(runCommand(i, cmd, totalCmds, workspace, executorFactory, textCompiler, executionConfig))) break
        }

        finish(tally, workspace)

        return tally.hadError
    }

    /**
     * The server's executor factory with the sub-agent, LangGraph and runnerCmd executors
     * stubbed out and no real Langfuse behind it.
     */
    private fun debugExecutorFactory(): CommandExecutorFactory {
        val telemetryService = OtelTelemetryService(dummyLangfuseConfig, OpenTelemetryExport.LOG)
        val llmTelemetryService = LlmTelemetryService(telemetryService, logger)
        val fileOperations = DebugFileOperations()
        val langfuseClient = DebugLangfuseClient()
        val skipRunAgentExecutor = SkipRunAgentExecutor()
        val skipLangGraphExecutor = SkipLangGraphExecutor()
        val skipRunnerCmdExecutor = SkipRunnerCmdExecutor()
        // ExecutionContext.processId stays -1 and agentName stays "" in debug runs, so
        // cost reporting and cap checks are no-ops; ProcessManager and CostMeter are only
        // here to satisfy the factory contract. Both are now SQLite-backed, so back them
        // with a throwaway temp database whose schema exists (the -1 writes are no-op
        // UPDATEs against it, and the empty agentName never accrues real cost entries).
        val dbFile = Files.createTempFile("agentdebug-", ".db")
        // WAL mode writes -wal/-shm sidecars next to the main file; delete all three on exit.
        listOf("", "-wal", "-shm").forEach { java.io.File("$dbFile$it").deleteOnExit() }
        val databaseService = DatabaseService(dbFile.toString(), logger).also { it.migrate() }
        val jobRegistry = ProcessJobRegistry()
        val processManager = ProcessManager(ProcessRepository(databaseService, jobRegistry), jobRegistry, logger)
        val costMeter = CostMeter(repository = CostRepository(databaseService))
        return CommandExecutorFactory(
            langfuseClient,
            llmTelemetryService,
            fileOperations,
            processManager,
            costMeter,
            // A debug run exits when its agent does; a session lookup still settling then is simply dropped.
            settlementScope = CoroutineScope(SupervisorJob()),
            llmCostResolver = LlmCostResolver(appConfig.llmPrice?.sessionSpend),
            runAgentExecutor = skipRunAgentExecutor,
            langGraphExecutor = skipLangGraphExecutor,
            runnerCmdExecutor = skipRunnerCmdExecutor,
            requireClaudeCodeVersion = appConfig.server.requireClaudeCodeVersion,
            claudeDropSystemSubtypes = appConfig.server.claudeDropSystemSubtypes.toSet(),
        )
    }

    /** The agent's workspace with the run's `HMR_*` debug values and env overrides applied, created on disk. */
    private fun debugWorkspace(textCompiler: DefaultTextCompiler): Workspace {
        val workspaceConfig =
            WorkspaceConfigFactory(
                workspaceRoot = appConfig.workspaceRootPath,
                globalEnv = appConfig.globalEnv,
                textCompiler = textCompiler,
                parentEnvAllowlist = appConfig.parentEnvAllowlist,
                hostEnvSupplier = { System.getenv() },
                // @SYSENV:-backed globalEnv names are secrets too; the factory folds
                // them into secretEnvNames for every redactor built from this config.
                sysEnvBackedGlobalEnvNames = appConfig.sysEnvBackedGlobalEnvNames,
            ).createWorkspaceConfig(
                processId = 0,
                agentEnv = agent.env,
                lockKeyTemplate = agent.lockStrategy.key,
                queryParams = inputMap,
                workspaceAgentConfig = agent.workspace.copy(cleanAfterFinish = !keepWorkspace),
                // Same union the run path seeds (`Process.execute`), so the redactor built
                // from this config screens the agent's declared `secret: true` inputs and
                // its `@SYSENV:`-sourced env values. CWE-532.
                secretEnvNames = agent.secretEnvNames + agent.sysEnvBackedEnvNames,
            )

        // Apply HMR_* vars and env overrides
        val debugEnv =
            workspaceConfig.env +
                mapOf(
                    EnvConstants.SERVER_ID to "agentdebug",
                    EnvConstants.AGENT_NAME to agent.name,
                ) + envOverrides

        val finalWorkspaceConfig = workspaceConfig.copy(env = debugEnv)
        val workspace = Workspace(finalWorkspaceConfig, Span.getInvalid())
        workspace.create()
        return workspace
    }

    /** A real run's summary line, then the workspace kept or cleaned up; a dry run prints nothing more. */
    private suspend fun finish(
        tally: RunTally,
        workspace: Workspace,
    ) {
        if (!dryRun) {
            println("─".repeat(53))
            println("[INFO] Done: ${tally.executed} executed, ${tally.skipped} skipped")
            if (keepWorkspace) {
                println("[INFO] Workspace kept at: ${workspace.config.projectRoot}")
            } else {
                workspace.cleanup()
            }
        }
    }

    /** The run's counters, fed one [StepOutcome] per command. */
    private class RunTally {
        var executed = 0
        var skipped = 0
        var hadError = false

        /** Counts [outcome]; true when it stops the run. */
        fun record(outcome: StepOutcome): Boolean =
            when (outcome) {
                StepOutcome.EXECUTED -> {
                    executed++
                    false
                }

                StepOutcome.SKIPPED -> {
                    skipped++
                    false
                }

                StepOutcome.PRINTED -> {
                    false
                }

                StepOutcome.FAILED -> {
                    executed++
                    hadError = true
                    true
                }

                StepOutcome.ABORTED -> {
                    hadError = true
                    true
                }
            }
    }

    /** How one command of the run ended, for the counters and the stop decision in [run]. */
    private enum class StepOutcome { EXECUTED, SKIPPED, PRINTED, FAILED, ABORTED }

    private suspend fun runCommand(
        i: Int,
        cmd: Command,
        totalCmds: Int,
        workspace: Workspace,
        executorFactory: CommandExecutorFactory,
        textCompiler: DefaultTextCompiler,
        executionConfig: ExecutionConfig,
    ): StepOutcome {
        val displayIndex = "$i/${totalCmds - 1}"

        println("─".repeat(53))
        println("[CMD $displayIndex] ${cmd.kind} \"${cmd.label}\"  timeout=${cmd.timeout}s")

        val skipReason = debugSkipReason(cmd)
        if (skipReason != null) {
            println(skipReason)
            return StepOutcome.SKIPPED
        }

        if (dryRun) {
            DryRunPrinter.print(
                cmd,
                workspace.config.env,
                SecretRedactor.from(workspace.config.env, workspace.config.secretEnvNames),
            )
            return StepOutcome.PRINTED
        }

        // Evaluate `when` predicate before running the command (real-run only).
        // Dry-run already printed the predicate via DryRunPrinter.
        evaluateWhen(cmd, i, workspace)?.let { return it }

        return executeCommand(i, cmd, workspace, executorFactory, textCompiler, executionConfig)
    }

    /** Why [cmd] is not executed in debug mode, as its `[SKIP]` line; `null` = it runs. */
    private fun debugSkipReason(cmd: Command): String? =
        when (cmd) {
            is Command.RunAgent -> {
                "[SKIP] Sub-agent commands are not executed in debug mode"
            }

            is Command.LangGraph -> {
                "[SKIP] LangGraph commands need server-side dispatch/continuation; not executed in debug mode"
            }

            is Command.RunnerCmd -> {
                "[SKIP] runnerCmd targets a configured RunnerRegistry, which agentdebug does not build; not executed in debug mode"
            }

            else -> {
                null
            }
        }

    /** The outcome a `when` predicate forces on [cmd]; `null` = run it. */
    private suspend fun evaluateWhen(
        cmd: Command,
        i: Int,
        workspace: Workspace,
    ): StepOutcome? {
        if (cmd.whenClause == null) return null
        val whenResult =
            WhenEvaluator(
                shell = workspace.shellExecutor,
                artifactsDir = workspace.config.artifacts,
            ).evaluate(cmd.whenClause, workspace.config.env, i)
        return when (
            val action =
                presentWhenResult(
                    whenResult,
                    SecretRedactor.from(workspace.config.env, workspace.config.secretEnvNames),
                )
        ) {
            is WhenAction.Run -> {
                null
            }

            is WhenAction.Skip -> {
                println(action.message)
                StepOutcome.SKIPPED
            }

            is WhenAction.Abort -> {
                println(action.message)
                StepOutcome.ABORTED
            }
        }
    }

    private suspend fun executeCommand(
        i: Int,
        cmd: Command,
        workspace: Workspace,
        executorFactory: CommandExecutorFactory,
        textCompiler: DefaultTextCompiler,
        executionConfig: ExecutionConfig,
    ): StepOutcome {
        val artifactNames = artifactNamesFor(i, workspace, textCompiler)
        val stdOut = artifactNames.stdOut(0)
        val stdErr = artifactNames.stdErr(0)

        val context =
            ExecutionContext(
                workspace = workspace,
                config = executionConfig,
                shell = workspace.shellExecutor,
                stdOut = stdOut,
                stdErr = stdErr,
                artifactNames = artifactNames,
            )

        val startMs = System.currentTimeMillis()
        val finalResult = executeTailing(cmd, context, executorFactory)

        val durationSec = (System.currentTimeMillis() - startMs) / 1000.0
        println("[CMD $i] exit=${finalResult.exitCode}  duration=${durationSec}s")

        // Apply resultEnvParam
        val envFromResult = ResultToEnvMapper(finalResult).mapToEnv(cmd.resultEnvParam)
        envFromResult.forEach { (key, value) ->
            println("[ENV ] $key=$value")
        }
        if (envFromResult.isNotEmpty()) {
            workspace.appendToEnv(envFromResult)
        }

        if (finalResult.exitCode != 0 && !cmd.continueIfFail) {
            println("[ERROR] Command failed with exit=${finalResult.exitCode}. Stopping.")
            return StepOutcome.FAILED
        }
        return StepOutcome.EXECUTED
    }

    /** Command [i]'s artifact names, from the agent's template, as the server resolves them. */
    private fun artifactNamesFor(
        i: Int,
        workspace: Workspace,
        textCompiler: DefaultTextCompiler,
    ): ArtifactNameResolver =
        ArtifactNameResolver(
            template = agent.artifactNameTemplate,
            textCompiler = textCompiler,
            artifactsDir = workspace.config.artifacts,
            cmdIndex = i,
            envProvider = { workspace.config.env },
            secretEnvNames = workspace.config.secretEnvNames,
            secretRedactor =
                SecretRedactor.from(
                    workspace.config.env,
                    workspace.config.secretEnvNames,
                    minNeedleLength = SecretRedactor.ARTIFACT_STEM_MIN_NEEDLE_LENGTH,
                ),
        )

    /** Runs [cmd] while echoing its stdout and stderr files as they grow, then flushes what is left. */
    private suspend fun executeTailing(
        cmd: Command,
        context: ExecutionContext,
        executorFactory: CommandExecutorFactory,
    ): CommandResult {
        // Each reader is single-owner: written only by its tail coroutine,
        // then read by the flush below after both coroutines are joined.
        val outReader = TailReader("[OUT]")
        val errReader = TailReader("[ERR]")

        val finalResult =
            coroutineScope {
                val tailOutJob: Job = launch(Dispatchers.IO) { tailFile(context.stdOut, outReader) }
                val tailErrJob: Job = launch(Dispatchers.IO) { tailFile(context.stdErr, errReader) }

                val executor = executorFactory.getExecutor(cmd)
                val result = executor.execute(cmd, context, Span.getInvalid())

                tailOutJob.cancelAndJoin()
                tailErrJob.cancelAndJoin()
                result
            }

        // Flush any remaining output (tail coroutines are joined, so the
        // readers' positions are final and accessed only from here).
        flushFile(context.stdOut, outReader)
        flushFile(context.stdErr, errReader)
        return finalResult
    }

    private suspend fun tailFile(
        path: Path,
        reader: TailReader,
    ) {
        while (true) {
            delay(100)
            if (!path.exists()) continue
            reader.consume(path.readText())
        }
    }

    private fun flushFile(
        path: Path,
        reader: TailReader,
    ) {
        if (!path.exists()) return
        reader.flushRemaining(path.readText())
    }
}
