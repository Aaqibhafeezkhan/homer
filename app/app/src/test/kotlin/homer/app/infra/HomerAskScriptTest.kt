package homer.app.infra

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioural tests for the `ask-and-dispatch-group` subcommand of the materialized `homer-ask`
 * helper (the shell script itself, run through `/bin/sh`). A stub `curl` on PATH captures the
 * request body, so the tests cover what the script builds and how it reads Homer's reply: the
 * questions in `--question` order with their options, the flag-derived fields, the printed id
 * order, and the refusals that must happen before any network call. See ADR-0105.
 */
class HomerAskScriptTest {
    private data class Run(
        val exit: Int,
        val stdout: String,
        val stderr: String,
    )

    private val scriptPath: Path =
        Paths.get(
            checkNotNull(javaClass.classLoader.getResource("scripts/homer-ask.sh")) {
                "scripts/homer-ask.sh missing from resources"
            }.toURI(),
        )

    /**
     * Writes a stub `curl` into [binDir] that writes the value of the `--data` argument to the
     * path in `$STUB_CAPTURE` and prints `$STUB_BODY`, exiting `$STUB_EXIT` — so a non-zero exit
     * exercises the "Homer unreachable" branch. The script's group POST passes no `-w`, so the
     * body is the whole of stdout.
     */
    private fun writeStubCurl(
        binDir: Path,
        body: String,
        curlExit: Int,
    ) {
        val stub = binDir.resolve("curl")
        stub.writeText(
            """
            #!/bin/sh
            prev=""
            for a in "${'$'}@"; do
                if [ "${'$'}prev" = "--data" ]; then
                    printf '%s' "${'$'}a" > "${'$'}STUB_CAPTURE"
                fi
                prev="${'$'}a"
            done
            printf '%s' "${'$'}STUB_BODY"
            exit "${'$'}STUB_EXIT"
            """.trimIndent() + "\n",
        )
        stub.setPosixFilePermissions(
            java.nio.file.attribute.PosixFilePermissions
                .fromString("rwxr-xr-x"),
        )
    }

    private fun runHomerAsk(
        args: List<String>,
        binDir: Path,
        stubBody: String = "",
        stubExit: Int = 0,
        env: Map<String, String> = emptyMap(),
    ): Run {
        val capture = binDir.resolve("capture.json")
        writeStubCurl(binDir, stubBody, stubExit)
        val pb = ProcessBuilder(listOf("/bin/sh", scriptPath.toString()) + args)
        pb.environment().clear()
        pb.environment()["PATH"] = "$binDir:/usr/bin:/bin"
        pb.environment()["STUB_BODY"] = stubBody
        pb.environment()["STUB_EXIT"] = stubExit.toString()
        pb.environment()["STUB_CAPTURE"] = capture.toString()
        pb.environment()["HMR_API_URL"] = "http://homer.test"
        pb.environment()["HMR_CALLBACK_TOKEN"] = "tok"
        pb.environment().putAll(env)
        val process = pb.start()
        val stdout = process.inputStream.readBytes().decodeToString()
        val stderr = process.errorStream.readBytes().decodeToString()
        process.waitFor()
        return Run(process.exitValue(), stdout, stderr)
    }

    private fun captured(binDir: Path): String = binDir.resolve("capture.json").readText()

    private val reply = """{"dispatchId":11,"questionIds":["qid-1","qid-2"]}"""

    @Test
    fun `ask-and-dispatch-group posts the questions in --question order with their options`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result =
            runHomerAsk(
                listOf(
                    "ask-and-dispatch-group",
                    "--question",
                    "Which release?",
                    "--option",
                    "1.0",
                    "--option",
                    "1.1",
                    "--question",
                    "Ship it?",
                    "--agent",
                    "handler",
                ),
                dir,
                stubBody = reply,
            )

        assertEquals(0, result.exit, "stderr=${result.stderr}")
        assertEquals(
            """{"questions":[""" +
                """{"text":"Which release?","options":["1.0","1.1"]},""" +
                """{"text":"Ship it?","options":[]}""" +
                """],"agent":"handler","answerParam":"answer","params":{}}""",
            captured(dir),
        )
    }

    @Test
    fun `ask-and-dispatch-group prints the dispatch id then each question id in order`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        // The reply is pretty-printed the way Homer's apiJson serializes it, so the array spans
        // lines — the reader has to walk them rather than match one.
        val result =
            runHomerAsk(
                listOf("ask-and-dispatch-group", "--question", "q1", "--question", "q2", "--agent", "handler"),
                dir,
                stubBody = "{\n    \"dispatchId\": 11,\n    \"questionIds\": [\n        \"qid-1\",\n        \"qid-2\"\n    ]\n}",
            )

        assertEquals(0, result.exit, "stderr=${result.stderr}")
        assertEquals("11\nqid-1\nqid-2\n", result.stdout)
    }

    @Test
    fun `ask-and-dispatch-group carries the answer param, params and ttl into the payload`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result =
            runHomerAsk(
                listOf(
                    "ask-and-dispatch-group",
                    "--question",
                    "q1",
                    "--agent",
                    "handler",
                    "--answer-param",
                    "reply",
                    "--param",
                    "k=v",
                    "--ttl",
                    "60",
                ),
                dir,
                stubBody = reply,
            )

        assertEquals(0, result.exit, "stderr=${result.stderr}")
        assertEquals(
            """{"questions":[{"text":"q1","options":[]}],"agent":"handler","answerParam":"reply","params":{"k":"v"},"ttlSec":60}""",
            captured(dir),
        )
    }

    @Test
    fun `ask-and-dispatch-group exits 2 without a request when --agent is missing`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result = runHomerAsk(listOf("ask-and-dispatch-group", "--question", "q1"), dir)

        assertEquals(2, result.exit)
        assertTrue("--agent is required" in result.stderr, "usage must be reported: ${result.stderr}")
        assertFalse(dir.resolve("capture.json").exists(), "a usage error must not reach the network")
    }

    @Test
    fun `ask-and-dispatch-group exits 2 without a request when no --question is given`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result = runHomerAsk(listOf("ask-and-dispatch-group", "--agent", "handler"), dir)

        assertEquals(2, result.exit)
        assertTrue("at least one --question" in result.stderr, "usage must be reported: ${result.stderr}")
        assertFalse(dir.resolve("capture.json").exists(), "a usage error must not reach the network")
    }

    @Test
    fun `ask-and-dispatch-group exits 2 without a request when --option precedes any --question`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result =
            runHomerAsk(listOf("ask-and-dispatch-group", "--option", "a", "--agent", "handler"), dir)

        assertEquals(2, result.exit)
        assertTrue("must follow a --question" in result.stderr, "usage must be reported: ${result.stderr}")
        assertFalse(dir.resolve("capture.json").exists(), "a usage error must not reach the network")
    }

    @Test
    fun `ask-and-dispatch-group exits 2 when Homer is unreachable`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result =
            runHomerAsk(
                listOf("ask-and-dispatch-group", "--question", "q1", "--agent", "handler"),
                dir,
                stubExit = 7,
            )

        assertEquals(2, result.exit)
        assertTrue(
            "registering the dispatch group failed" in result.stderr,
            "a transport failure must be reported: ${result.stderr}",
        )
    }

    private fun assumeShellAvailable() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        assumeTrue(Paths.get("/bin/sh").exists(), "POSIX sh required")
        assumeTrue(scriptPath.exists(), "homer-ask.sh must be on the test classpath")
    }
}
