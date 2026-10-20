package homer.app.executors

import homer.app.exceptions.CommandExecutionException
import homer.app.infra.FileOperations
import homer.app.infra.PathSafety
import homer.app.infra.SecretRedactor
import homer.app.models.Command
import homer.app.models.CommandResult
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.DownloadMode
import homer.app.runners.Runner
import homer.app.runners.RunnerLookup
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import homer.app.runners.TarStreams
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * Executor for Command.RunnerCmd — runs one command on another runner
 * alias with check → syncToRemote → execute → syncFromRemote phases,
 * composed from the ADR-0033 session primitives. See ADR-0034.
 */
class RunnerCmdExecutor(
    private val runners: RunnerLookup,
    private val activeSessions: ActiveSessionsRegistry,
    private val fileOperations: FileOperations,
) : CommandExecutor<Command.RunnerCmd> {
    override suspend fun execute(
        command: Command.RunnerCmd,
        context: ExecutionContext,
        span: Span,
    ): CommandResult {
        val runner = runners.forAlias(command.runner)
        // Two redactors, never one merged env map: Kotlin `+` keeps only the
        // right-hand value for a shared key, so a runner `env` name colliding with a
        // workspace env name would silently drop one side's credential from the
        // redaction needles. The colliding entries are distinct operator-declared
        // credentials (each side sourced its own `@SYSENV:`), so both must screen.
        // CWE-200
        val redactor =
            SecretRedactor.from(context.workspace.config.env, context.workspace.config.secretEnvNames) +
                SecretRedactor.from(runner.runnerEnv, runner.sysEnvBackedEnvNames)
        span.setAttribute("runner.target", command.runner)
        span.setAttribute("input.value", redactor.redact(command.execute))

        val forwardedEnv = forwardedEnv(runner, context)

        logger.debug("Process {}: runnerCmd opening session on '{}'", context.processId, command.runner)
        val session =
            runner.openSession(
                RunnerSessionParams(
                    processId = context.processId,
                    agentName = context.agentName,
                    workspace = context.workspace.config,
                    env = forwardedEnv,
                    parentContext = Context.current(),
                ),
            )
        activeSessions.add(session)
        try {
            return runOnTarget(command, context, span, TargetRun(runner, session, forwardedEnv, redactor))
        } finally {
            withContext(NonCancellable) { releaseSession(command, context, session) }
        }
    }

    /** The open session on the target runner and what the run on it is composed from. */
    private class TargetRun(
        val runner: Runner,
        val session: RunnerSession,
        val forwardedEnv: Map<String, String>,
        val redactor: SecretRedactor,
    )

    /** check → syncToRemote → execute → syncFromRemote on [target]'s session (ADR-0034). */
    private suspend fun runOnTarget(
        command: Command.RunnerCmd,
        context: ExecutionContext,
        span: Span,
        target: TargetRun,
    ): CommandResult {
        val session = target.session
        val overrides = session.createWorkspace()
        // Local copy for the target's check/execute only — never merged
        // into the agent env (would repoint HMR_* for later local commands).
        // runner.runnerEnv carries the TARGET runner's config.env (e.g. PATH on
        // a macOS ssh box); without it an ssh target would only receive the
        // requesting process's workspace env, which has no runner-specific values.
        // session.env holds backend-derived facts (HMR_API_URL) that override it.
        val targetEnv = target.forwardedEnv + target.runner.runnerEnv + session.env + overrides

        // Extras before checks: checks may source the uploaded tooling, and
        // the env override from createWorkspace must be truthful whenever
        // the session env is used. Deliberate exception to ADR-0034's
        // no-transfer-before-checks — see ADR-0042.
        uploadExtras(command, context, session)

        val failedCheck = runChecks(command, context, session, targetEnv)
        if (failedCheck != null) return failedCheck

        syncToRemote(command, context, session)

        val result =
            session.shell.cmd(
                command = listOf(command.shell, "-s", "-"),
                stdOut = context.stdOut,
                stdErr = context.stdErr,
                stdIn = command.execute,
                env = targetEnv,
                timeout = command.timeout.seconds,
            )

        span.setOutputValue(context, fileOperations, target.redactor)

        if (succeeded(command, result)) syncFromRemote(command, context, session)
        return result
    }

    /**
     * The workspace env the target receives. Allowlisted host-env keys still carrying
     * the host value Homer layered them with must not reach a remote target — the same
     * CWE-200-adjacent rule that keeps them out of a remote run's composed env
     * (ADR-0065): they describe Homer's host, not the target's. Keys an operator re-set
     * via globalEnv/runnerEnv/agentEnv are absent from the set and survive. A local
     * target keeps them: it runs on Homer's own host, where they are correct.
     */
    private fun forwardedEnv(
        runner: Runner,
        context: ExecutionContext,
    ): Map<String, String> =
        // CWE-200
        if (runner.isRemote) {
            context.workspace.config.env - context.workspace.config.parentEnvLayerNames
        } else {
            context.workspace.config.env
        }

    /** Uploads the target runner's `extraUploads` (ADR-0042). */
    private suspend fun uploadExtras(
        command: Command.RunnerCmd,
        context: ExecutionContext,
        session: RunnerSession,
    ) {
        for (extra in session.extraUploads) {
            logger.debug("Process {}: runnerCmd extra upload '{}' to '{}'", context.processId, extra.remote, command.runner)
            session.upload(TarStreams.resolvedPackSource(Path.of(extra.local)), extra.remote)
        }
    }

    /** Uploads [command]'s `syncToRemote` paths into the target's workdir. */
    private suspend fun syncToRemote(
        command: Command.RunnerCmd,
        context: ExecutionContext,
        session: RunnerSession,
    ) {
        for (path in command.syncToRemote) {
            logger.debug("Process {}: runnerCmd upload '{}' to '{}'", context.processId, path, command.runner)
            session.upload(
                containedLocalPath(context.workspace.config.workdir, path, command),
                "workdir/$path",
            )
        }
    }

    /** Best-effort teardown: cleans the target workspace, unregisters and closes [session]. */
    @Suppress("SuspendFunSwallowedCancellation") // best-effort, and only ever runs under NonCancellable
    private suspend fun releaseSession(
        command: Command.RunnerCmd,
        context: ExecutionContext,
        session: RunnerSession,
    ) {
        runCatching { session.cleanWorkspace() }
            .onFailure {
                logger.warn(
                    "Process {}: runnerCmd workspace cleanup failed on '{}': {}",
                    context.processId,
                    command.runner,
                    it.message,
                )
            }
        activeSessions.remove(session)
        runCatching { session.close() }
    }

    /** Whether [result] exited 0 or with [command]'s `finishExitCode`: only then are the results synced back. */
    private fun succeeded(
        command: Command.RunnerCmd,
        result: CommandResult,
    ): Boolean = result.exitCode == 0 || (command.finishExitCode != null && result.exitCode == command.finishExitCode)

    /** Runs [command]'s checks on [session]; the first failing check's result, or null when all pass. */
    private suspend fun runChecks(
        command: Command.RunnerCmd,
        context: ExecutionContext,
        session: RunnerSession,
        targetEnv: Map<String, String>,
    ): CommandResult? {
        for (check in command.check) {
            val res =
                session.shell.cmd(
                    command = listOf(command.shell, "-c", check),
                    stdOut = context.stdOut,
                    stdErr = context.stdErr,
                    stdIn = null,
                    env = targetEnv,
                    timeout = CHECK_TIMEOUT,
                )
            if (res.exitCode != 0) {
                logger.error(
                    "Process {}: runnerCmd check failed on '{}': `{}` (exit {})",
                    context.processId,
                    command.runner,
                    check,
                    res.exitCode,
                )
                // Returned (not thrown) so continueIfFail/finishExitCode
                // apply uniformly — AgentExecutor rethrows executor
                // exceptions regardless of continueIfFail.
                return res
            }
        }
        return null
    }

    /** Downloads [command]'s `syncFromRemote` paths; a path missing on the target fails the step. */
    private suspend fun syncFromRemote(
        command: Command.RunnerCmd,
        context: ExecutionContext,
        session: RunnerSession,
    ) {
        for (path in command.syncFromRemote) {
            logger.debug("Process {}: runnerCmd download '{}' from '{}'", context.processId, path, command.runner)
            val local = containedLocalPath(context.workspace.config.workdir, path, command)
            session.download("workdir/$path", local, DownloadMode.OVERWRITE)
            // download() is a silent no-op on missing remote paths —
            // a requested result that never appeared is an error.
            if (!Files.exists(local)) {
                throw CommandExecutionException(
                    message = "runnerCmd syncFromRemote path not found on target: $path",
                    exitCode = -1,
                    command = command.toString(),
                )
            }
        }
    }

    /**
     * Resolves a `sync*` path under the workspace and asserts it stays inside it.
     * The load-time check on these paths is only lexical (no `..`, not absolute),
     * so a symlink written into the workspace by an earlier command could redirect
     * the resolved path at host files; `PathSafety` catches that via realpath.
     */
    private fun containedLocalPath(
        workdir: Path,
        path: String,
        command: Command.RunnerCmd,
    ): Path =
        try {
            PathSafety.assertContained(workdir, workdir.resolve(path))
        } catch (_: SecurityException) {
            throw CommandExecutionException(
                message = "runnerCmd sync path '$path' resolves outside the workspace",
                exitCode = -1,
                command = command.toString(),
            )
        }

    companion object {
        private val CHECK_TIMEOUT = 60.seconds
        private val logger = LoggerFactory.getLogger(RunnerCmdExecutor::class.java)
    }
}
