package homer.app.infra

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProcessGraphStateRunnerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `an unlaunchable interpreter is reported as an error`() =
        runBlocking {
            val result =
                ProcessGraphStateRunner()
                    .run("/nonexistent/homer-python", "pkg.mod", "thread-1", emptyMap(), 10.seconds)

            assertTrue(result is GraphStateResult.Err, "an unlaunchable interpreter must not crash the call; got $result")
            assertTrue(result.message.isNotBlank(), "the launch failure must carry a message")
        }

    @Test
    fun `a normal state JSON is returned trimmed`() =
        runBlocking {
            val python = executable("""printf '%s\n' '{"topology":{"nodes":[],"edges":[]}}'""")

            val result = ProcessGraphStateRunner().run(python.toString(), "pkg.mod", "thread-1", emptyMap(), 10.seconds)

            assertEquals(GraphStateResult.Ok("""{"topology":{"nodes":[],"edges":[]}}"""), result)
        }

    @Test
    fun `a non-zero exit surfaces the stderr head`() =
        runBlocking {
            val python = executable("printf '%s\n' 'checkpoint store unreadable' >&2\nexit 3")

            val result = ProcessGraphStateRunner().run(python.toString(), "pkg.mod", "thread-1", emptyMap(), 10.seconds)

            assertEquals(GraphStateResult.Err("checkpoint store unreadable"), result)
        }

    @Test
    fun `a non-zero exit with blank stderr falls back to the stdout head`() =
        runBlocking {
            val python = executable("printf '%s\n' 'runtime said no'\nexit 4")

            val result = ProcessGraphStateRunner().run(python.toString(), "pkg.mod", "thread-1", emptyMap(), 10.seconds)

            assertEquals(GraphStateResult.Err("runtime said no"), result)
        }

    @Test
    fun `a silent module that outlives the timeout is killed and reported`() =
        runBlocking {
            val python = executable("sleep 60")

            val result = ProcessGraphStateRunner().run(python.toString(), "pkg.mod", "thread-1", emptyMap(), 1.seconds)

            assertEquals(GraphStateResult.Err("state timed out after 1s"), result)
        }

    @Test
    fun `state stdout flood kills the subprocess and returns the output-exceeds error`() =
        runBlocking {
            val before = stateTempFiles()
            // `yes` floods stdout far past the 2 MiB cap and never exits on its own.
            val python = executable("exec yes xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")

            val started = System.currentTimeMillis()
            val result = ProcessGraphStateRunner().run(python.toString(), "pkg.mod", "thread-1", emptyMap(), 15.seconds)
            val elapsed = System.currentTimeMillis() - started

            assertEquals(GraphStateResult.Err("state output exceeds $MAX_GRAPH_STDOUT_BYTES bytes"), result)
            assertTrue(elapsed < 10_000, "the overflow must kill the subprocess well before the timeout; took ${elapsed}ms")
            assertEquals(before, stateTempFiles(), "no homer-lg-state* temp file may remain")
        }

    /** Writes an executable `python` stand-in into the per-test temp dir. */
    private fun executable(body: String): Path {
        val script = tempDir.resolve("python")
        script.writeText("#!/bin/sh\n$body\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
        return script
    }

    /** Names of the legacy temp files the runner used to leave in `java.io.tmpdir`. */
    private fun stateTempFiles(): Set<String> =
        Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { stream ->
            stream
                .map { it.fileName.toString() }
                .filter { it.startsWith("homer-lg-state") }
                .toList()
                .toSet()
        }
}
