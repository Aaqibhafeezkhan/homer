package homer.app.runners.ssh

import homer.app.executors.RunnerCmdExecutor
import homer.app.fixtures.TestFixtures
import homer.app.models.Command
import homer.app.models.RunnerConfig
import homer.app.models.SshAuth
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.RunnerRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.apache.sshd.client.SshClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunnerCmdSshIntegrationTest {
    private lateinit var server: EmbeddedSshServer
    private lateinit var client: SshClient

    @BeforeEach
    fun setup() {
        server = EmbeddedSshServer().also { it.start() }
        client = SshClient.setUpDefaultClient().also { it.start() }
    }

    @AfterEach
    fun teardown() {
        client.stop()
        server.close()
    }

    @Test
    fun `runnerCmd checks, syncs up, executes, syncs back and sweeps the remote root`(
        @TempDir serverRoot: Path,
    ) = runBlocking {
        val projectRoot = serverRoot.resolve("p1")
        val workdir = projectRoot.resolve("workdir").createDirectories()
        projectRoot.resolve("artifacts").createDirectories()
        workdir.resolve("in.txt").writeText("payload")

        val runner =
            SshRunner(
                alias = "ssh-box",
                config =
                    RunnerConfig.Ssh(
                        host = server.host,
                        user = "test",
                        auth = SshAuth.KeyFile("/unused-in-test"),
                        strictHostKeyChecking = false,
                        remoteWorkspaceRoot = server.rootDir.resolve("remote").toString(),
                    ),
                client = client,
                sessionFactory = { server.newClientSession(client) },
            )

        val config =
            TestFixtures.mockWorkspaceConfig(
                serverRoot = serverRoot,
                projectRoot = projectRoot,
                artifacts = projectRoot.resolve("artifacts"),
                workdir = workdir,
                env = mapOf("HMR_WORK_DIR" to workdir.toString()),
            )
        val context =
            TestFixtures.mockExecutionContext(
                workspace = TestFixtures.mockWorkspace(config),
                stdOut = projectRoot.resolve("artifacts/cmd_0.out"),
                stdErr = projectRoot.resolve("artifacts/cmd_0.err"),
            )

        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val executor =
                RunnerCmdExecutor(
                    runners = RunnerRegistry(listOf(runner)).also { it.start(scope) },
                    activeSessions = ActiveSessionsRegistry(),
                    fileOperations = TestFixtures.mockFileOperations(),
                )

            val result =
                executor.execute(
                    Command.RunnerCmd(
                        runner = "ssh-box",
                        check = listOf("true"),
                        syncToRemote = listOf("in.txt"),
                        execute = "tr a-z A-Z < in.txt > out.txt",
                        syncFromRemote = listOf("out.txt"),
                    ),
                    context,
                    TestFixtures.mockSpan(),
                )

            assertEquals(0, result.exitCode)
            assertEquals("PAYLOAD", workdir.resolve("out.txt").readText())
            // remote per-command workspace swept
            assertTrue(Files.notExists(server.rootDir.resolve("remote/p1")), "remote workspace should be swept")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `heartbeats keep a silent command alive despite a lowered idle timeout`(
        @TempDir serverRoot: Path,
    ) = runBlocking {
        // Idle timeout lowered far below the silent span; without the disabled
        // default / heartbeat vouching this configuration kills the session.
        // Own client, timing applied before start() — the order buildSshRunner uses.
        val timedClient =
            SshClient.setUpDefaultClient().also {
                applySshClientTiming(
                    it,
                    RunnerConfig.Ssh(
                        host = server.host,
                        user = "test",
                        auth = SshAuth.KeyFile("/unused-in-test"),
                        idleTimeoutSeconds = 2,
                        heartbeatIntervalSeconds = 1,
                        remoteWorkspaceRoot = "unused",
                    ),
                )
                it.start()
            }
        timedClient.use {
            val scope = CoroutineScope(Dispatchers.Default)
            try {
                val exitCode = runSilentCommand(serverRoot, runner(it), scope, sleepSeconds = 6)

                assertEquals(0, exitCode)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `duration ZERO disables the idle timeout for a live silent session`(
        @TempDir serverRoot: Path,
    ) = runBlocking {
        // Default config (both fields null): directly verifies that MINA treats
        // Duration.ZERO as "disabled", not as a kill-everything 0-second timeout.
        // Own client, timing applied before start() — the order buildSshRunner uses.
        val silentClient =
            SshClient.setUpDefaultClient().also {
                applySshClientTiming(
                    it,
                    RunnerConfig.Ssh(
                        host = server.host,
                        user = "test",
                        auth = SshAuth.KeyFile("/unused-in-test"),
                        idleTimeoutSeconds = null,
                        heartbeatIntervalSeconds = null,
                        remoteWorkspaceRoot = "unused",
                    ),
                )
                it.start()
            }
        silentClient.use {
            val scope = CoroutineScope(Dispatchers.Default)
            try {
                val exitCode = runSilentCommand(serverRoot, runner(it), scope)

                assertEquals(0, exitCode)
            } finally {
                scope.cancel()
            }
        }
    }

    private fun runner(timedClient: SshClient): SshRunner =
        SshRunner(
            alias = "ssh-box",
            config =
                RunnerConfig.Ssh(
                    host = server.host,
                    user = "test",
                    auth = SshAuth.KeyFile("/unused-in-test"),
                    strictHostKeyChecking = false,
                    remoteWorkspaceRoot = server.rootDir.resolve("remote").toString(),
                ),
            client = timedClient,
            sessionFactory = { server.newClientSession(timedClient) },
        )

    // Produces no output at all while running — longer than any lowered idle timeout.
    private suspend fun runSilentCommand(
        serverRoot: Path,
        runner: SshRunner,
        scope: CoroutineScope,
        sleepSeconds: Int = 3,
    ): Int {
        val projectRoot = serverRoot.resolve("p1")
        val workdir = projectRoot.resolve("workdir").createDirectories()
        projectRoot.resolve("artifacts").createDirectories()

        val config =
            TestFixtures.mockWorkspaceConfig(
                serverRoot = serverRoot,
                projectRoot = projectRoot,
                artifacts = projectRoot.resolve("artifacts"),
                workdir = workdir,
                env = mapOf("HMR_WORK_DIR" to workdir.toString()),
            )
        val context =
            TestFixtures.mockExecutionContext(
                workspace = TestFixtures.mockWorkspace(config),
                stdOut = projectRoot.resolve("artifacts/cmd_0.out"),
                stdErr = projectRoot.resolve("artifacts/cmd_0.err"),
            )

        val executor =
            RunnerCmdExecutor(
                runners = RunnerRegistry(listOf(runner)).also { it.start(scope) },
                activeSessions = ActiveSessionsRegistry(),
                fileOperations = TestFixtures.mockFileOperations(),
            )

        return executor
            .execute(
                Command.RunnerCmd(
                    runner = "ssh-box",
                    check = listOf("true"),
                    execute = "sleep $sleepSeconds",
                ),
                context,
                TestFixtures.mockSpan(),
            ).exitCode
    }
}
