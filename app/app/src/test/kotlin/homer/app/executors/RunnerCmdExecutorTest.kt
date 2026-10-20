package homer.app.executors

import homer.app.fixtures.TestFixtures
import homer.app.infra.FileOperations
import homer.app.infra.OutputLineFilter
import homer.app.infra.SecretRedactor
import homer.app.infra.ShellExecutor
import homer.app.models.Command
import homer.app.models.CommandResult
import homer.app.models.ExtraUploadSpec
import homer.app.models.RunnerConfig
import homer.app.models.RunnerFilter
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.DownloadMode
import homer.app.runners.Runner
import homer.app.runners.RunnerRegistry
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import homer.app.runners.TarStreams
import homer.app.runners.external.ExternalRunnerDomain
import homer.app.runners.external.OpResult
import homer.app.runners.external.RegisterInstanceRequest
import homer.app.runners.external.RunnerOp
import homer.app.runners.queue.RunnerClaimQueue
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private class FakeShell(
    private val exitCodes: MutableList<Int>,
) : ShellExecutor {
    val invocations = mutableListOf<Pair<List<String>, String?>>()
    var lastEnv: Map<String, String> = emptyMap()

    override suspend fun cmd(
        command: List<String>,
        stdOut: Path,
        stdErr: Path,
        stdIn: String?,
        env: Map<String, String>,
        timeout: Duration,
        stdOutFilter: OutputLineFilter?,
    ): CommandResult {
        invocations += command to stdIn
        lastEnv = env
        val code = if (exitCodes.isEmpty()) 0 else exitCodes.removeFirst()
        return CommandResult(exitCode = code, stdOut = stdOut, stdErr = stdErr)
    }
}

private class FakeSession(
    override val shell: ShellExecutor,
    private val overrides: Map<String, String> = emptyMap(),
    private val materializeOnDownload: Boolean = true,
    override val extraUploads: List<ExtraUploadSpec> = emptyList(),
    private val packOnUpload: Boolean = false,
) : RunnerSession {
    val calls = mutableListOf<String>()
    override val runnerAlias = "fake-box"
    override val env = mapOf("SESSION_VAR" to "s1")

    override suspend fun createWorkspace(): Map<String, String> {
        calls += "createWorkspace"
        return overrides
    }

    override suspend fun upload(
        local: Path,
        remoteRelative: String,
        excludes: List<String>,
    ) {
        calls += "upload:$remoteRelative"
        // Stands in for the kubernetes transport: its sync packs the source, and
        // TarStreams.pack refuses a symlinked one.
        if (packOnUpload) TarStreams.pack(OutputStream.nullOutputStream(), local)
    }

    override suspend fun download(
        remoteRelative: String,
        local: Path,
        mode: DownloadMode,
    ) {
        calls += "download:$remoteRelative:mode=$mode"
        if (materializeOnDownload) {
            local.parent?.let { Files.createDirectories(it) }
            Files.writeString(local, "result")
        }
    }

    override suspend fun cleanWorkspace() {
        calls += "cleanWorkspace"
    }

    override fun close() {
        calls += "close"
    }
}

private class FakeRunner(
    private val session: RunnerSession,
    override val runnerEnv: Map<String, String> = emptyMap(),
    override val sysEnvBackedEnvNames: Set<String> = emptySet(),
    override val isRemote: Boolean = false,
) : Runner {
    override val alias = "fake-box"

    override suspend fun openSession(params: RunnerSessionParams): RunnerSession = session
}

class RunnerCmdExecutorTest {
    private fun executor(
        session: RunnerSession,
        scope: CoroutineScope,
    ) = RunnerCmdExecutor(
        runners = RunnerRegistry(listOf(FakeRunner(session))).also { it.start(scope) },
        activeSessions = ActiveSessionsRegistry(),
        fileOperations = TestFixtures.mockFileOperations(),
    )

    private fun command(
        check: List<String> = emptyList(),
        syncToRemote: List<String> = emptyList(),
        syncFromRemote: List<String> = emptyList(),
        finishExitCode: Int? = null,
    ) = Command.RunnerCmd(
        runner = "fake-box",
        execute = "echo hi",
        check = check,
        syncToRemote = syncToRemote,
        syncFromRemote = syncFromRemote,
        finishExitCode = finishExitCode,
    )

    private fun context(workdir: Path): ExecutionContext {
        val config =
            TestFixtures.mockWorkspaceConfig(
                projectRoot = workdir.parent,
                workdir = workdir,
                artifacts = workdir.parent.resolve("artifacts"),
                env = mapOf("HMR_WORK_DIR" to workdir.toString()),
            )
        return TestFixtures.mockExecutionContext(
            workspace = TestFixtures.mockWorkspace(config),
            stdOut = workdir.parent.resolve("cmd_0.out"),
            stdErr = workdir.parent.resolve("cmd_0.err"),
        )
    }

    @Test
    fun `happy path runs phases in order and always cleans`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = runTest {
        val shell = FakeShell(mutableListOf(0, 0))
        val session = FakeSession(shell, overrides = mapOf("HMR_WORK_DIR" to "/remote/w"))
        val workdir = tmp.resolve("workdir").also { Files.createDirectories(it) }
        val result =
            executor(session, backgroundScope).execute(
                command(check = listOf("which cargo"), syncToRemote = listOf("src/"), syncFromRemote = listOf("out.bin")),
                context(workdir),
                TestFixtures.mockSpan(),
            )
        assertEquals(0, result.exitCode)
        assertEquals(
            listOf("createWorkspace", "upload:workdir/src/", "download:workdir/out.bin:mode=OVERWRITE", "cleanWorkspace", "close"),
            session.calls,
        )
        // check ran via `sh -c`, execute via `sh -s -` with the script on stdin
        assertEquals(listOf("sh", "-c", "which cargo") to null, shell.invocations[0])
        assertEquals(listOf("sh", "-s", "-") to "echo hi", shell.invocations[1])
        // target env = workspace env + session env + overrides, as a copy
        assertEquals("/remote/w", shell.lastEnv["HMR_WORK_DIR"])
        assertEquals("s1", shell.lastEnv["SESSION_VAR"])
    }

    @Test
    fun `a step targeting an external domain claims it by alias and runs there with the domain env and no host layer`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = runTest {
        val queue = RunnerClaimQueue(maxPending = 10)
        val domain =
            ExternalRunnerDomain("workers", RunnerConfig.External(token = "t", env = mapOf("DOMAIN_VAR" to "from-domain")), queue)
        val instance = domain.register(RegisterInstanceRequest(name = "i1"))
        val workdir = tmp.resolve("workdir").also { Files.createDirectories(it) }
        val config =
            TestFixtures.mockWorkspaceConfig(
                projectRoot = workdir.parent,
                workdir = workdir,
                artifacts = workdir.parent.resolve("artifacts"),
                env = mapOf("HMR_WORK_DIR" to workdir.toString(), "PATH" to "/homer/host/bin"),
                parentEnvLayerNames = setOf("PATH"),
            )
        val context =
            TestFixtures.mockExecutionContext(
                workspace = TestFixtures.mockWorkspace(config),
                stdOut = workdir.parent.resolve("cmd_0.out"),
                stdErr = workdir.parent.resolve("cmd_0.err"),
            )
        val claimedFilter = CompletableDeferred<RunnerFilter>()
        val cmdOp = CompletableDeferred<RunnerOp.Cmd>()
        // A scripted instance: claims through the real queue and answers each op the session sends.
        val instanceJob =
            launch {
                val request = queue.take(instance)
                claimedFilter.complete(request.filter)
                val session = domain.openSession(instance, request.params)
                check(request.complete(session))
                while (true) {
                    when (val op = session.mailbox.next(10.seconds) ?: continue) {
                        is RunnerOp.CreateWorkspace -> {
                            session.mailbox.complete(OpResult(op.opId, root = "/remote/root"))
                        }

                        is RunnerOp.Cmd -> {
                            cmdOp.complete(op)
                            session.sink(op.opId)!!.append("stdout", 0, "hi\n".toByteArray())
                            session.mailbox.complete(OpResult(op.opId, exitCode = 0))
                        }

                        is RunnerOp.CleanWorkspace -> {
                            session.mailbox.complete(OpResult(op.opId))
                        }

                        is RunnerOp.Close -> {
                            break
                        }

                        else -> {
                            error("unexpected op $op")
                        }
                    }
                }
            }

        val result =
            RunnerCmdExecutor(
                runners = RunnerRegistry(emptyList(), externalDomains = listOf(domain), queue = queue),
                activeSessions = ActiveSessionsRegistry(),
                fileOperations = TestFixtures.mockFileOperations(),
            ).execute(Command.RunnerCmd(runner = "workers", execute = "echo hi"), context, TestFixtures.mockSpan())
        instanceJob.join()

        assertEquals(0, result.exitCode)
        assertEquals(RunnerFilter.Alias("workers"), claimedFilter.await())
        val op = cmdOp.await()
        assertEquals(listOf("sh", "-s", "-"), op.argv)
        assertEquals("echo hi", op.stdin)
        assertEquals("from-domain", op.env["DOMAIN_VAR"], "the domain env reaches the step")
        assertFalse("PATH" in op.env, "the host allowlist layer never reaches a remote target")
        assertEquals("/remote/root/workdir", op.env["HMR_WORK_DIR"])
        assertEquals("hi\n", Files.readString(context.stdOut))
        assertEquals(0, instance.inUse, "the session is closed after the step")
    }

    @Test
    fun `failing check returns its exit code and skips sync and execute`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = runTest {
        val shell = FakeShell(mutableListOf(3))
        val session = FakeSession(shell)
        val workdir = tmp.resolve("workdir").also { Files.createDirectories(it) }
        val result =
            executor(session, backgroundScope).execute(
                command(check = listOf("which cargo"), syncToRemote = listOf("src/"), syncFromRemote = listOf("out.bin")),
                context(workdir),
                TestFixtures.mockSpan(),
            )
        assertEquals(3, result.exitCode)
        assertEquals(1, shell.invocations.size) // execute never ran
        assertEquals(listOf("createWorkspace", "cleanWorkspace", "close"), session.calls)
    }

    @Test
    fun `extraUploads are uploaded before syncToRemote`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = runTest {
        val shell = FakeShell(mutableListOf(0))
        val session =
            FakeSession(
                shell,
                extraUploads = listOf(ExtraUploadSpec("/app/scripts", ".homer/scripts")),
            )
        val workdir = tmp.resolve("workdir").also { Files.createDirectories(it) }
        executor(session, backgroundScope).execute(
            command(syncToRemote = listOf("in.txt")),
            context(workdir),
            TestFixtures.mockSpan(),
        )
        val extraIdx = session.calls.indexOf("upload:.homer/scripts")
        val syncIdx = session.calls.indexOf("upload:workdir/in.txt")
        assertTrue(extraIdx in 0 until syncIdx)
    }

    @Test
    fun `a symlinked extraUploads source is resolved so the tar pack does not refuse it`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = runTest {
        val realScripts = tmp.resolve("real-scripts").also { Files.createDirectories(it) }
        Files.writeString(realScripts.resolve("tool.sh"), "echo tool")
        val scriptsLink = Files.createSymbolicLink(tmp.resolve("scripts-link"), realScripts)
        val shell = FakeShell(mutableListOf(0))
        val session =
            FakeSession(
                shell,
                extraUploads = listOf(ExtraUploadSpec(scriptsLink.toString(), ".homer/scripts")),
                packOnUpload = true,
            )
        val workdir = tmp.resolve("workdir").also { Files.createDirectories(it) }

        val result = executor(session, backgroundScope).execute(command(), context(workdir), TestFixtures.mockSpan())

        assertEquals(0, result.exitCode)
        assertTrue("upload:.homer/scripts" in session.calls)
    }

    @Test
    fun `extraUploads are uploaded even when a check fails`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = runTest {
        val shell = FakeShell(mutableListOf(3))
        val session =
            FakeSession(
                shell,
                extraUploads = listOf(ExtraUploadSpec("/app/scripts", ".homer/scripts")),
            )
        val workdir = tmp.resolve("workdir").also { Files.createDirectories(it) }
        val result =
            executor(session, backgroundScope).execute(
                command(check = listOf("which cargo")),
                context(workdir),
                TestFixtures.mockSpan(),
            )
        assertEquals(3, result.exitCode)
        assertTrue("upload:.homer/scripts" in session.calls)
    }

    @Test
    fun `non-zero execute skips syncFromRemote but still cleans`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = runTest {
        val shell = FakeShell(mutableListOf(1))
        val session = FakeSession(shell)
        val workdir = tmp.resolve("workdir").also { Files.createDirectories(it) }
        val result =
            executor(session, backgroundScope).execute(
                command(syncFromRemote = listOf("out.bin")),
                context(workdir),
                TestFixtures.mockSpan(),
            )
        assertEquals(1, result.exitCode)
        assertEquals(listOf("createWorkspace", "cleanWorkspace", "close"), session.calls)
    }

    @Test
    fun `finishExitCode counts as success for syncFromRemote`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = runTest {
        val shell = FakeShell(mutableListOf(7))
        val session = FakeSession(shell)
        val workdir = tmp.resolve("workdir").also { Files.createDirectories(it) }
        executor(session, backgroundScope).execute(
            command(syncFromRemote = listOf("out.bin"), finishExitCode = 7),
            context(workdir),
            TestFixtures.mockSpan(),
        )
        assertTrue(session.calls.contains("download:workdir/out.bin:mode=OVERWRITE"))
    }

    @Test
    fun `missing syncFromRemote path after download fails the command but cleanup still runs`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = runTest {
        val shell = FakeShell(mutableListOf(0))
        val session = FakeSession(shell, materializeOnDownload = false)
        val workdir = tmp.resolve("workdir").also { Files.createDirectories(it) }
        assertThrows<homer.app.exceptions.CommandExecutionException> {
            executor(session, backgroundScope).execute(
                command(syncFromRemote = listOf("out.bin")),
                context(workdir),
                TestFixtures.mockSpan(),
            )
        }
        assertEquals(listOf("createWorkspace", "download:workdir/out.bin:mode=OVERWRITE", "cleanWorkspace", "close"), session.calls)
    }

    @Test
    fun `syncToRemote through a workspace symlink that escapes is rejected before upload`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = runTest {
        val shell = FakeShell(mutableListOf(0))
        val session = FakeSession(shell)
        val workdir = tmp.resolve("workdir").also { Files.createDirectories(it) }
        val outside = tmp.resolve("outside").also { Files.createDirectories(it) }
        // An earlier command left a symlink inside the workspace pointing at host files.
        Files.createSymbolicLink(workdir.resolve("escape"), outside)

        assertThrows<homer.app.exceptions.CommandExecutionException> {
            executor(session, backgroundScope).execute(
                command(syncToRemote = listOf("escape/secret")),
                context(workdir),
                TestFixtures.mockSpan(),
            )
        }
        // The escaping upload never happened; cleanup still ran.
        assertTrue(session.calls.none { it.startsWith("upload:") })
        assertEquals(listOf("createWorkspace", "cleanWorkspace", "close"), session.calls)
    }

    @Test
    fun `syncFromRemote through a workspace symlink that escapes is rejected before download`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = runTest {
        val shell = FakeShell(mutableListOf(0))
        val session = FakeSession(shell)
        val workdir = tmp.resolve("workdir").also { Files.createDirectories(it) }
        val outside = tmp.resolve("outside").also { Files.createDirectories(it) }
        Files.createSymbolicLink(workdir.resolve("escape"), outside)

        assertThrows<homer.app.exceptions.CommandExecutionException> {
            executor(session, backgroundScope).execute(
                command(syncFromRemote = listOf("escape/target")),
                context(workdir),
                TestFixtures.mockSpan(),
            )
        }
        // The escaping download never happened; execute ran, cleanup still ran.
        assertTrue(session.calls.none { it.startsWith("download:") })
    }

    @Test
    fun `target env overrides are never merged into the workspace env`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = runTest {
        val shell = FakeShell(mutableListOf(0))
        val session = FakeSession(shell, overrides = mapOf("HMR_WORK_DIR" to "/remote/w"))
        val workdir = tmp.resolve("workdir").also { Files.createDirectories(it) }
        val ctx = context(workdir)
        executor(session, backgroundScope).execute(command(), ctx, TestFixtures.mockSpan())
        verify(exactly = 0) { ctx.workspace.appendToEnv(any()) }
    }

    @Test
    fun `non-empty runnerEnv is layered into the target command env`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = runTest {
        val shell = FakeShell(mutableListOf(0))
        val session = FakeSession(shell)
        val workdir = tmp.resolve("workdir").also { Files.createDirectories(it) }
        val runner = FakeRunner(session, runnerEnv = mapOf("MY_RUNNER_VAR" to "from-runner"))
        RunnerCmdExecutor(
            runners = RunnerRegistry(listOf(runner)).also { it.start(backgroundScope) },
            activeSessions = ActiveSessionsRegistry(),
            fileOperations = TestFixtures.mockFileOperations(),
        ).execute(command(), context(workdir), TestFixtures.mockSpan())
        assertEquals("from-runner", shell.lastEnv["MY_RUNNER_VAR"])
    }

    @Test
    fun `target runner @SYSENV-backed secrets are redacted from output telemetry span (CWE-200)`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = runTest {
        val shell = FakeShell(mutableListOf(0))
        val session = FakeSession(shell)
        val workdir = tmp.resolve("workdir").also { Files.createDirectories(it) }
        val runner =
            FakeRunner(
                session,
                runnerEnv = mapOf("RUNNER_TOKEN" to "secret-credential"),
                sysEnvBackedEnvNames = setOf("RUNNER_TOKEN"),
            )
        val fileOps =
            mockk<FileOperations>(relaxed = true) {
                every { fileSize(any()) } returns 100L
                every { readText(any()) } returns "output contains secret-credential here"
            }
        val span = TestFixtures.mockSpan()
        val capturedOutput = slot<String>()
        every { span.setAttribute("output.value", capture(capturedOutput)) } returns span
        RunnerCmdExecutor(
            runners = RunnerRegistry(listOf(runner)).also { it.start(backgroundScope) },
            activeSessions = ActiveSessionsRegistry(),
            fileOperations = fileOps,
        ).execute(command(), context(workdir), span)
        assertFalse(capturedOutput.captured.contains("secret-credential"), "target runner secret must not appear in telemetry span")
        assertTrue(capturedOutput.captured.contains(SecretRedactor.REDACTED))
    }

    @Test
    fun `workspace and runner credentials sharing a name are both redacted (CWE-200)`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = runTest {
        val shell = FakeShell(mutableListOf(0))
        val session = FakeSession(shell)
        val workdir = tmp.resolve("workdir").also { Files.createDirectories(it) }
        // Same name declared by both sides: the runner's value wins in the target env,
        // but the workspace credential is a distinct @SYSENV:-backed secret of its own
        // and must not lose its redaction needle in the merge.
        val runner =
            FakeRunner(
                session,
                runnerEnv = mapOf("REPO_TOKEN" to "runner-credential"),
                sysEnvBackedEnvNames = setOf("REPO_TOKEN"),
            )
        val config =
            TestFixtures.mockWorkspaceConfig(
                projectRoot = workdir.parent,
                workdir = workdir,
                artifacts = workdir.parent.resolve("artifacts"),
                env = mapOf("REPO_TOKEN" to "workspace-credential"),
                secretEnvNames = setOf("REPO_TOKEN"),
            )
        val fileOps =
            mockk<FileOperations>(relaxed = true) {
                every { fileSize(any()) } returns 100L
                every { readText(any()) } returns "workspace-credential runner-credential"
            }
        val span = TestFixtures.mockSpan()
        val capturedOutput = slot<String>()
        every { span.setAttribute("output.value", capture(capturedOutput)) } returns span
        RunnerCmdExecutor(
            runners = RunnerRegistry(listOf(runner)).also { it.start(backgroundScope) },
            activeSessions = ActiveSessionsRegistry(),
            fileOperations = fileOps,
        ).execute(
            command(),
            TestFixtures.mockExecutionContext(
                workspace = TestFixtures.mockWorkspace(config),
                stdOut = workdir.parent.resolve("cmd_0.out"),
                stdErr = workdir.parent.resolve("cmd_0.err"),
            ),
            span,
        )
        assertFalse(capturedOutput.captured.contains("workspace-credential"), "workspace credential must not appear in telemetry span")
        assertFalse(capturedOutput.captured.contains("runner-credential"), "runner credential must not appear in telemetry span")
        assertTrue(capturedOutput.captured.contains(SecretRedactor.REDACTED))
    }

    @Test
    fun `remote runnerCmd target receives no host-layer env keys a remote run would not get (CWE-200)`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = runTest {
        // A runnerCmd target inherits the requesting run's composed env — which, on
        // a local agent, carries the parentEnvAllowlist host layer. A remote target
        // must be stripped of exactly the host values (never an operator re-set one):
        // they describe Homer's host, not the target's — the same rule that keeps
        // them out of a remote run's own env (ADR-0065).
        val shell = FakeShell(mutableListOf(0))
        val session = FakeSession(shell)
        val workdir = tmp.resolve("workdir").also { Files.createDirectories(it) }
        val runner =
            FakeRunner(
                session,
                runnerEnv = mapOf("MY_RUNNER_VAR" to "from-runner"),
                isRemote = true,
            )
        val config =
            TestFixtures.mockWorkspaceConfig(
                projectRoot = workdir.parent,
                workdir = workdir,
                artifacts = workdir.parent.resolve("artifacts"),
                env =
                    mapOf(
                        "HOME" to "/home/homer",
                        "PATH" to "/usr/bin",
                        "MY_AGENT_VAR" to "from-agent",
                        "HMR_WORK_DIR" to workdir.toString(),
                    ),
                parentEnvLayerNames = setOf("HOME", "PATH"),
            )
        RunnerCmdExecutor(
            runners = RunnerRegistry(listOf(runner)).also { it.start(backgroundScope) },
            activeSessions = ActiveSessionsRegistry(),
            fileOperations = TestFixtures.mockFileOperations(),
        ).execute(
            command(),
            TestFixtures.mockExecutionContext(
                workspace = TestFixtures.mockWorkspace(config),
                stdOut = workdir.parent.resolve("cmd_0.out"),
                stdErr = workdir.parent.resolve("cmd_0.err"),
            ),
            TestFixtures.mockSpan(),
        )
        assertFalse("HOME" in shell.lastEnv, "host HOME must not reach a remote runnerCmd target")
        assertFalse("PATH" in shell.lastEnv, "host PATH must not reach a remote runnerCmd target")
        // Agent/global values and the target runner's own env survive.
        assertEquals("from-agent", shell.lastEnv["MY_AGENT_VAR"])
        assertEquals("from-runner", shell.lastEnv["MY_RUNNER_VAR"])
    }

    @Test
    fun `local runnerCmd target keeps the host-layer env keys`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = runTest {
        // Parity: a local target runs on Homer's own host, where the allowlisted
        // values are correct — stripping them would break local-runnerCmd agents
        // exactly the way dropping the layer from the local runner would.
        val shell = FakeShell(mutableListOf(0))
        val session = FakeSession(shell)
        val workdir = tmp.resolve("workdir").also { Files.createDirectories(it) }
        val config =
            TestFixtures.mockWorkspaceConfig(
                projectRoot = workdir.parent,
                workdir = workdir,
                artifacts = workdir.parent.resolve("artifacts"),
                env = mapOf("HOME" to "/home/homer", "HMR_WORK_DIR" to workdir.toString()),
                parentEnvLayerNames = setOf("HOME"),
            )
        RunnerCmdExecutor(
            runners = RunnerRegistry(listOf(FakeRunner(session))).also { it.start(backgroundScope) },
            activeSessions = ActiveSessionsRegistry(),
            fileOperations = TestFixtures.mockFileOperations(),
        ).execute(
            command(),
            TestFixtures.mockExecutionContext(
                workspace = TestFixtures.mockWorkspace(config),
                stdOut = workdir.parent.resolve("cmd_0.out"),
                stdErr = workdir.parent.resolve("cmd_0.err"),
            ),
            TestFixtures.mockSpan(),
        )
        assertEquals("/home/homer", shell.lastEnv["HOME"])
    }
}
