package homer.app.runners.ssh

import homer.app.exceptions.OutputCapExceededException
import homer.app.infra.OutputLineFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.apache.sshd.client.SshClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SshShellExecutorTest {
    private lateinit var server: EmbeddedSshServer
    private lateinit var client: SshClient
    private lateinit var workdir: Path

    @BeforeEach
    fun setup() {
        server = EmbeddedSshServer().also { it.start() }
        client = SshClient.setUpDefaultClient().also { it.start() }
        workdir = server.rootDir.resolve("wd").createDirectories()
    }

    @AfterEach
    fun teardown() {
        client.stop()
        server.close()
    }

    private fun executor() = SshShellExecutor(server.newClientSession(client), workdir.toString())

    private fun reapingExecutor() =
        SshShellExecutor(
            session = server.newClientSession(client),
            remoteWorkdir = workdir.toString(),
            reapProcessGroup = true,
            reapDir = workdir.resolve(".homer/reap").toString(),
        )

    /**
     * The embedded server runs commands on this same host, so local `setsid -w`
     * availability mirrors what the reaping path needs remotely. Absent (e.g.
     * macOS dev), the reaping tests are skipped — SshRunner's probe would have
     * disabled reaping there anyway.
     */
    private fun setsidAvailable(): Boolean =
        runCatching { ProcessBuilder("setsid", "-w", "true").start().waitFor() == 0 }.getOrDefault(false)

    @Test
    fun `runs a command remotely and captures stdout + exit code`(
        @TempDir local: Path,
    ) = runTest {
        val out = local.resolve("o.txt")
        val err = local.resolve("e.txt")
        val result =
            executor().cmd(
                command = listOf("echo", "hello world"),
                stdOut = out,
                stdErr = err,
                stdIn = null,
                env = emptyMap(),
                timeout = 10.seconds,
            )
        assertEquals(0, result.exitCode)
        assertEquals("hello world", out.readText().trim())
    }

    @Test
    fun `stdOutFilter drops remote stdout lines before they reach the file`(
        @TempDir local: Path,
    ) = runTest {
        val out = local.resolve("o.txt")
        val err = local.resolve("e.txt")
        val result =
            executor().cmd(
                command = listOf("printf", "keep 1\\ndrop me\\nkeep 2\\n"),
                stdOut = out,
                stdErr = err,
                stdIn = null,
                env = emptyMap(),
                timeout = 10.seconds,
                stdOutFilter = OutputLineFilter { !it.contains("drop") },
            )
        assertEquals(0, result.exitCode)
        assertEquals("keep 1\nkeep 2\n", out.readText())
    }

    @Test
    fun `pipes stdin to the remote command`(
        @TempDir local: Path,
    ) = runTest {
        val out = local.resolve("o.txt")
        executor().cmd(listOf("cat"), out, local.resolve("e.txt"), stdIn = "piped-input", env = emptyMap(), timeout = 10.seconds)
        assertEquals("piped-input", out.readText().trim())
    }

    @Test
    fun `propagates the remote non-zero exit code`(
        @TempDir local: Path,
    ) = runTest {
        val result =
            executor().cmd(listOf("sh", "-c", "exit 7"), local.resolve("o"), local.resolve("e"), null, emptyMap(), 10.seconds)
        assertEquals(7, result.exitCode)
    }

    @Test
    fun `applies env via the env prefix`(
        @TempDir local: Path,
    ) = runTest {
        val out = local.resolve("o.txt")
        executor().cmd(listOf("sh", "-c", "echo \$FOO"), out, local.resolve("e"), null, mapOf("FOO" to "bar"), 10.seconds)
        assertEquals("bar", out.readText().trim())
    }

    private fun cappedExecutor(maxOutputBytes: Long) = SshShellExecutor(server.newClientSession(client), workdir.toString(), maxOutputBytes)

    @Test
    fun `a command still writing past the output cap is killed while it runs`(
        @TempDir local: Path,
    ) = runTest {
        val err = local.resolve("e")
        assertThrows<OutputCapExceededException> {
            cappedExecutor(maxOutputBytes = 1_000).cmd(
                listOf("sh", "-c", "while true; do echo 0123456789; done"),
                local.resolve("o"),
                err,
                null,
                emptyMap(),
                20.seconds,
            )
        }
        assertContains(err.readText(), "Output cap exceeded (1000 bytes)")
    }

    @Test
    fun `a command that exits after overshooting the cap still fails on the post-close check`(
        @TempDir local: Path,
    ) = runTest {
        val err = local.resolve("e")
        assertThrows<OutputCapExceededException> {
            cappedExecutor(maxOutputBytes = 100).cmd(
                listOf("head", "-c", "100000", "/dev/zero"),
                local.resolve("o"),
                err,
                null,
                emptyMap(),
                20.seconds,
            )
        }
        assertContains(err.readText(), "Output cap exceeded (100 bytes)")
    }

    @Test
    fun `cancelling the caller stops waiting for the remote command`(
        @TempDir local: Path,
    ) = runBlocking {
        val job =
            launch(Dispatchers.IO) {
                executor().cmd(listOf("sleep", "30"), local.resolve("o"), local.resolve("e"), null, emptyMap(), 30.seconds)
            }
        delay(1_000)
        withTimeout(10_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    @Test
    fun `times out a long command and does not report success`(
        @TempDir local: Path,
    ) = runTest {
        val exception =
            assertThrows<CancellationException> {
                executor().cmd(listOf("sleep", "30"), local.resolve("o"), local.resolve("e"), null, emptyMap(), 1.seconds)
            }
        assertContains(exception.message.orEmpty(), "Timeout")
    }

    @Test
    fun `reaping wrapper preserves stdout and exit code`(
        @TempDir local: Path,
    ) = runTest {
        assumeTrue(setsidAvailable())
        val out = local.resolve("o.txt")
        val ok = reapingExecutor().cmd(listOf("echo", "hello world"), out, local.resolve("e"), null, emptyMap(), 10.seconds)
        assertEquals(0, ok.exitCode)
        assertEquals("hello world", out.readText().trim())

        val bad =
            reapingExecutor().cmd(
                listOf("sh", "-c", "exit 7"),
                local.resolve("o2"),
                local.resolve("e2"),
                null,
                emptyMap(),
                10.seconds,
            )
        assertEquals(7, bad.exitCode)
    }

    @Test
    fun `reaps a backgrounded remote child on timeout`(
        @TempDir local: Path,
    ) {
        assumeTrue(setsidAvailable())
        // The command backgrounds a child that writes `marker` after 3s, then blocks.
        // A 1s timeout kills the whole process group before the child can fire; if
        // reaping failed the detached child would survive close(true) and create it.
        val marker = workdir.resolve("orphan-marker")
        val script = "{ sleep 3; : > $marker; } & echo bg; sleep 30"
        runBlocking {
            assertThrows<CancellationException> {
                reapingExecutor().cmd(listOf("sh", "-c", script), local.resolve("o"), local.resolve("e"), null, emptyMap(), 1.seconds)
            }
        }
        Thread.sleep(4_000) // past the child's 3s timer
        assertFalse(marker.exists(), "backgrounded remote child was not reaped with its process group")
    }
}
