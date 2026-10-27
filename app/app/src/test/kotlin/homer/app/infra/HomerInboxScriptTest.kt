package homer.app.infra

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavioural tests for the materialized `homer-inbox` helper (the shell script itself, run
 * through `/bin/sh`). A stub `curl` on PATH records its arguments and returns a canned body and
 * HTTP status, so the tests cover the script's own logic: the plain mode's exit 0 / exit 2, and
 * the hook mode's contract with Claude Code — the body on 200, nothing on 204, and exit 1 (a
 * non-blocking hook error, never 2) with a `homer-inbox:` reason on any failure. See ADR-0097.
 */
class HomerInboxScriptTest {
    private data class Run(
        val exit: Int,
        val stdout: String,
        val stderr: String,
        val curlArgs: String,
    )

    private val scriptPath: Path =
        Paths.get(
            checkNotNull(javaClass.classLoader.getResource("scripts/homer-inbox.sh")) {
                "scripts/homer-inbox.sh missing from resources"
            }.toURI(),
        )

    private val goodEnv = mapOf("HMR_API_URL" to "http://homer.test/", "HMR_CALLBACK_TOKEN" to "tok")

    /** A stub `curl` printing `<body>\n<code>` (the script's `-w '\n%{http_code}'`) and recording its args. */
    private fun writeStubCurl(binDir: Path) {
        val stub = binDir.resolve("curl")
        stub.writeText(
            """
            #!/bin/sh
            printf '%s\n' "${'$'}@" > "${'$'}STUB_ARGS"
            [ "${'$'}STUB_EXIT" = 0 ] || exit "${'$'}STUB_EXIT"
            printf '%s\n%s' "${'$'}STUB_BODY" "${'$'}STUB_CODE"
            """.trimIndent() + "\n",
        )
        stub.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    private fun runInbox(
        args: List<String>,
        dir: Path,
        env: Map<String, String> = goodEnv,
        stubBody: String = "",
        stubCode: String = "200",
        stubExit: Int = 0,
    ): Run {
        writeStubCurl(dir)
        val argsFile = dir.resolve("curl-args")
        val pb = ProcessBuilder(listOf("/bin/sh", scriptPath.toString()) + args)
        pb.environment().clear()
        pb.environment()["PATH"] = "$dir:/usr/bin:/bin"
        pb.environment()["STUB_BODY"] = stubBody
        pb.environment()["STUB_CODE"] = stubCode
        pb.environment()["STUB_EXIT"] = stubExit.toString()
        pb.environment()["STUB_ARGS"] = argsFile.toString()
        pb.environment().putAll(env)
        // stdin stays open and empty: a helper that read it would hang here, as it would under Claude.
        val process = pb.start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "homer-inbox must not wait on stdin")
        val stdout = process.inputStream.readBytes().decodeToString()
        val stderr = process.errorStream.readBytes().decodeToString()
        return Run(process.exitValue(), stdout, stderr, if (argsFile.exists()) argsFile.readText() else "")
    }

    @Test
    fun `plain mode prints the response body and exits 0`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val body = """{"messages":[{"id":"m1","text":"hi","createdAt":1}]}"""
        val result = runInbox(emptyList(), dir, stubBody = body)

        assertEquals(0, result.exit, "stderr=${result.stderr}")
        assertEquals(body, result.stdout.trimEnd())
        assertTrue("http://homer.test/api/v1/callback/messages/take\n" in result.curlArgs, result.curlArgs)
        assertTrue("Authorization: Bearer tok" in result.curlArgs, result.curlArgs)
    }

    @Test
    fun `plain mode surfaces the server msg and exits 2 on a 409`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result =
            runInbox(emptyList(), dir, stubBody = "{\n    \"msg\": \"Process 7 is no longer accepting messages\"\n}", stubCode = "409")

        assertEquals(2, result.exit)
        assertEquals("homer-inbox: Process 7 is no longer accepting messages\n", result.stderr)
    }

    @Test
    fun `hook mode prints the hook JSON on 200 and asks for the event`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val body = """{"decision":"block","reason":"The operator sent you a message"}"""
        val result = runInbox(listOf("--hook", "stop"), dir, stubBody = body)

        assertEquals(0, result.exit, "stderr=${result.stderr}")
        assertEquals(body, result.stdout.trimEnd())
        assertTrue("http://homer.test/api/v1/callback/messages/take?hook=stop\n" in result.curlArgs, result.curlArgs)
    }

    @Test
    fun `hook mode prints nothing on 204`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result = runInbox(listOf("--hook", "post-tool-use"), dir, stubCode = "204")

        assertEquals(0, result.exit, "stderr=${result.stderr}")
        assertEquals("", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    fun `hook mode exits 1 with the reason on a 500`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result = runInbox(listOf("--hook", "stop"), dir, stubBody = """{"msg":"Internal server error"}""", stubCode = "500")

        assertEquals(1, result.exit, "a hook failure must never block Claude (exit 2)")
        assertEquals("", result.stdout)
        assertEquals("homer-inbox: Internal server error\n", result.stderr)
    }

    @Test
    fun `hook mode exits 1 when Homer is unreachable`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result = runInbox(listOf("--hook", "post-tool-use"), dir, stubExit = 7)

        assertEquals(1, result.exit)
        assertEquals("", result.stdout)
        assertTrue(result.stderr.startsWith("homer-inbox: ") && "unreachable" in result.stderr, result.stderr)
    }

    @Test
    fun `hook mode exits 1 when the callback token is missing`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result = runInbox(listOf("--hook", "stop"), dir, env = mapOf("HMR_API_URL" to "http://homer.test"))

        assertEquals(1, result.exit)
        assertTrue("HMR_CALLBACK_TOKEN" in result.stderr, result.stderr)
        assertEquals("", result.curlArgs, "no request without a token")
    }

    @Test
    fun `an unknown hook event is a usage error, exit 1 in hook mode`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result = runInbox(listOf("--hook", "nope"), dir)

        assertEquals(1, result.exit)
        assertTrue(result.stderr.startsWith("homer-inbox: usage"), result.stderr)
        assertEquals("", result.curlArgs, "no request for an unknown event")
    }

    @Test
    fun `an unknown argument is a usage error, exit 2`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result = runInbox(listOf("--bogus"), dir)

        assertEquals(2, result.exit)
        assertTrue(result.stderr.startsWith("homer-inbox: usage"), result.stderr)
    }

    @Test
    fun `plain mode exits 2 when Homer is unreachable`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result = runInbox(emptyList(), dir, stubExit = 7)

        assertEquals(2, result.exit)
        assertEquals("", result.stdout)
        assertTrue(result.stderr.startsWith("homer-inbox: request failed") && "unreachable" in result.stderr, result.stderr)
    }

    @Test
    fun `plain mode exits 2 without a request when the API URL is missing`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result = runInbox(emptyList(), dir, env = mapOf("HMR_CALLBACK_TOKEN" to "tok"))

        assertEquals(2, result.exit)
        assertTrue("HMR_API_URL" in result.stderr, result.stderr)
        assertEquals("", result.curlArgs, "no request without an API URL")
    }

    @Test
    fun `plain mode exits 2 without a request when the callback token is missing`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result = runInbox(emptyList(), dir, env = mapOf("HMR_API_URL" to "http://homer.test"))

        assertEquals(2, result.exit)
        assertTrue("HMR_CALLBACK_TOKEN" in result.stderr, result.stderr)
        assertEquals("", result.curlArgs, "no request without a token")
    }

    @Test
    fun `a rejection without a msg falls back to the HTTP status, exit 2 in plain mode`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result = runInbox(emptyList(), dir, stubBody = "{}", stubCode = "409")

        assertEquals(2, result.exit)
        assertEquals("homer-inbox: request rejected with HTTP 409\n", result.stderr)
    }

    @Test
    fun `a rejection without a msg falls back to the HTTP status, exit 1 in hook mode`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result = runInbox(listOf("--hook", "stop"), dir, stubBody = "Conflict", stubCode = "409")

        assertEquals(1, result.exit)
        assertEquals("", result.stdout)
        assertEquals("homer-inbox: request rejected with HTTP 409\n", result.stderr)
    }

    @Test
    fun `hook mode without an event is a usage error, exit 1`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result = runInbox(listOf("--hook"), dir)

        assertEquals(1, result.exit)
        assertTrue(result.stderr.startsWith("homer-inbox: usage"), result.stderr)
        assertEquals("", result.curlArgs, "no request without an event")
    }

    private fun assumeShellAvailable() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        assumeTrue(Paths.get("/bin/sh").exists(), "POSIX sh required")
        assumeTrue(scriptPath.exists(), "homer-inbox.sh must be on the test classpath")
    }
}
