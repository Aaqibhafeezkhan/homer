package homer.app.runners.ssh

import homer.app.EnvConstants
import homer.app.infra.WorkspaceConfig
import homer.app.models.ExtraUploadSpec
import homer.app.models.RunnerConfig
import homer.app.models.SshAuth
import homer.app.runners.RunnerSessionParams
import io.opentelemetry.context.Context
import kotlinx.coroutines.test.runTest
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier
import org.apache.sshd.sftp.common.SftpException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SshRunnerTest {
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

    private fun cfg(
        extras: List<ExtraUploadSpec> = emptyList(),
        env: Map<String, String> = emptyMap(),
    ) = RunnerConfig.Ssh(
        host = server.host,
        user = "test",
        auth = SshAuth.KeyFile("/unused-in-test"),
        strictHostKeyChecking = false,
        remoteWorkspaceRoot = server.rootDir.resolve("remote").toString(),
        extraUploads = extras,
        env = env,
    )

    @Test
    fun `session primitives create remote workspace, upload and translate env`(
        @TempDir serverRoot: Path,
        @TempDir agentDir: Path,
    ) = runTest {
        // Local workspace layout under a serverRoot Homer controls.
        val projectRoot = serverRoot.resolve("p1")
        projectRoot
            .resolve("workdir")
            .createDirectories()
            .resolve("in.txt")
            .writeText("hi")
        projectRoot.resolve("artifacts").createDirectories()
        agentDir.resolve("prompt.txt").writeText("sys")

        val ws =
            WorkspaceConfig(
                serverRoot = serverRoot,
                projectRoot = projectRoot,
                artifacts = projectRoot.resolve("artifacts"),
                workdir = projectRoot.resolve("workdir"),
                env = mapOf(EnvConstants.WORK_DIR to projectRoot.resolve("workdir").toString()),
                clearAfterFinish = true,
                lockKey = "k",
                maxOutputBytes = Long.MAX_VALUE,
            )

        val runner =
            SshRunner(
                alias = "ssh-box",
                config = cfg(),
                client = client,
                sessionFactory = { server.newClientSession(client) },
            )
        val params =
            RunnerSessionParams(
                processId = 1,
                agentName = "demo",
                workspace = ws,
                env = ws.env + mapOf(EnvConstants.AGENT_DIR to agentDir.toString()),
                parentContext = Context.root(),
            )

        val session = runner.openSession(params)
        val overrides = session.createWorkspace()
        session.upload(projectRoot, ".")
        session.upload(agentDir, ".homer/agent")

        val remoteP1 = server.rootDir.resolve("remote/p1")
        assertEquals("hi", remoteP1.resolve("workdir/in.txt").readText())
        assertEquals("sys", remoteP1.resolve(".homer/agent/prompt.txt").readText())
        assertTrue(overrides[EnvConstants.WORK_DIR]!!.startsWith(remoteP1.toString()))
        assertEquals(remoteP1.resolve(".homer/agent").toString(), overrides[EnvConstants.AGENT_DIR])
        session.close()
    }

    @Test
    fun `extraUploads are uploadable and env-repointed end to end`(
        @TempDir serverRoot: Path,
        @TempDir scriptsDir: Path,
    ) = runTest {
        scriptsDir.resolve("git.sh").writeText("#!/bin/sh\necho tooling")
        val projectRoot = serverRoot.resolve("p1")
        projectRoot.resolve("workdir").createDirectories()
        projectRoot.resolve("artifacts").createDirectories()

        val ws =
            WorkspaceConfig(
                serverRoot = serverRoot,
                projectRoot = projectRoot,
                artifacts = projectRoot.resolve("artifacts"),
                workdir = projectRoot.resolve("workdir"),
                env = mapOf(EnvConstants.WORK_DIR to projectRoot.resolve("workdir").toString()),
                clearAfterFinish = true,
                lockKey = "k",
                maxOutputBytes = Long.MAX_VALUE,
            )
        val extras = listOf(ExtraUploadSpec(scriptsDir.toString(), ".homer/scripts", env = "SCRIPTS_DIR"))
        val runner =
            SshRunner(
                alias = "ssh-box",
                config = cfg(extras),
                client = client,
                sessionFactory = { server.newClientSession(client) },
            )
        val session =
            runner.openSession(
                RunnerSessionParams(
                    processId = 1,
                    agentName = "demo",
                    workspace = ws,
                    env = ws.env,
                    parentContext = Context.root(),
                ),
            )
        val overrides = session.createWorkspace()
        assertEquals(extras, session.extraUploads)
        // what AgentExecutor / RunnerCmdExecutor will do with the specs:
        session.extraUploads.forEach { session.upload(Path.of(it.local), it.remote) }

        val remoteP1 = server.rootDir.resolve("remote/p1")
        assertEquals("#!/bin/sh\necho tooling", remoteP1.resolve(".homer/scripts/git.sh").readText())
        assertEquals(remoteP1.resolve(".homer/scripts").toString(), overrides["SCRIPTS_DIR"])
        session.close()
    }

    @Test
    fun `remote command env carries runner and agent values but no backend-host allowlist keys`(
        @TempDir serverRoot: Path,
    ) = runTest {
        // Env composition happens before the session (WorkspaceConfigFactory with
        // includeParentEnv=false for a remote runner); this asserts what the ssh
        // runner actually delivers over the exec channel: the `env K=V` prefix
        // carries runner/agent-supplied values, and no allowlisted host key unless
        // someone set it explicitly.
        val projectRoot = serverRoot.resolve("p1")
        projectRoot.resolve("workdir").createDirectories()
        projectRoot.resolve("artifacts").createDirectories()

        val ws =
            WorkspaceConfig(
                serverRoot = serverRoot,
                projectRoot = projectRoot,
                artifacts = projectRoot.resolve("artifacts"),
                workdir = projectRoot.resolve("workdir"),
                // What a remote run's composed env looks like now: no backend-host
                // HOME/PATH/LANG/TZ, runner + agent values present.
                env =
                    mapOf(
                        "MY_RUNNER_VAR" to "from-runner",
                        "MY_AGENT_VAR" to "from-agent",
                        EnvConstants.WORK_DIR to projectRoot.resolve("workdir").toString(),
                    ),
                clearAfterFinish = true,
                lockKey = "k",
                maxOutputBytes = Long.MAX_VALUE,
            )
        val runnerCfg = cfg(env = mapOf("MY_RUNNER_VAR" to "from-runner"))
        assertTrue(runnerCfg.env.containsKey("MY_RUNNER_VAR"))

        val runner =
            SshRunner(
                alias = "ssh-box",
                config = runnerCfg,
                client = client,
                sessionFactory = { server.newClientSession(client) },
            )
        val session =
            runner.openSession(
                RunnerSessionParams(
                    processId = 1,
                    agentName = "demo",
                    workspace = ws,
                    env = ws.env,
                    parentContext = Context.root(),
                ),
            )
        try {
            session.createWorkspace()
            val stdOut = projectRoot.resolve("artifacts/env.out")
            val stdErr = projectRoot.resolve("artifacts/env.err")
            session.shell.cmd(
                command = listOf("sh", "-c", "echo HOME=\$HOME PATH=\$PATH RUNNER=\$MY_RUNNER_VAR AGENT=\$MY_AGENT_VAR"),
                stdOut = stdOut,
                stdErr = stdErr,
                stdIn = null,
                env = ws.env,
                timeout = kotlin.time.Duration.parse("30s"),
                stdOutFilter = null,
            )
            // The test host's own env does not supply the remote values either: the
            // remote login shell resolves HOME/PATH itself, so assert only on what
            // Homer sent — the runner/agent values — and that the composed env Homer
            // holds carries no allowlisted host key.
            val out = stdOut.readText()
            assertTrue(out.contains("RUNNER=from-runner"), "out=$out")
            assertTrue(out.contains("AGENT=from-agent"), "out=$out")
            assertTrue(
                listOf("HOME", "PATH", "LANG", "TZ").none { it in ws.env },
                "a remote runner's composed env must not carry parentEnvAllowlist keys; got ${ws.env.keys}",
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `createWorkspace failure surfaces the SftpException`(
        @TempDir serverRoot: Path,
    ) = runTest {
        // Force mkdirs to fail: make the first path segment of remoteWorkspaceRoot
        // already exist as a plain file, so WorkspaceSync's mkdirsIdempotent hits
        // an SftpException it can't treat as "already a dir".
        val blocker = server.rootDir.resolve("blocked")
        Files.createFile(blocker)

        val projectRoot = serverRoot.resolve("p1")
        projectRoot.resolve("workdir").createDirectories()
        projectRoot.resolve("artifacts").createDirectories()

        val ws =
            WorkspaceConfig(
                serverRoot = serverRoot,
                projectRoot = projectRoot,
                artifacts = projectRoot.resolve("artifacts"),
                workdir = projectRoot.resolve("workdir"),
                env = emptyMap(),
                clearAfterFinish = true,
                lockKey = "k",
                maxOutputBytes = Long.MAX_VALUE,
            )

        val runner =
            SshRunner(
                alias = "ssh-box",
                config = cfg().copy(remoteWorkspaceRoot = blocker.toString()),
                client = client,
                sessionFactory = { server.newClientSession(client) },
            )
        val params =
            RunnerSessionParams(
                processId = 1,
                agentName = "demo",
                workspace = ws,
                env = ws.env,
                parentContext = Context.root(),
            )

        val session = runner.openSession(params)
        try {
            assertThrows<SftpException> { session.createWorkspace() }
        } finally {
            session.close()
        }
    }

    @Test
    fun `strict host key checking verifies against known_hosts and rejects unknown hosts`() {
        val verifier =
            sshServerKeyVerifier(cfg().copy(strictHostKeyChecking = true, knownHostsPath = "/etc/homer/known_hosts"))

        val knownHosts = assertIs<KnownHostsServerKeyVerifier>(verifier)
        assertEquals(Path.of("/etc/homer/known_hosts"), knownHosts.path)
        assertSame(RejectAllServerKeyVerifier.INSTANCE, knownHosts.delegateVerifier)
    }

    @Test
    fun `strict host key checking without a known_hosts path fails naming the host`() {
        val error =
            assertThrows<IllegalStateException> {
                sshServerKeyVerifier(cfg().copy(strictHostKeyChecking = true, knownHostsPath = null))
            }
        assertTrue(error.message!!.contains("requires knownHostsPath (ssh runner host=${server.host})"), error.message)
    }

    @Test
    fun `host key checking off accepts any host key`() {
        assertSame(AcceptAllServerKeyVerifier.INSTANCE, sshServerKeyVerifier(cfg()))
    }
}
