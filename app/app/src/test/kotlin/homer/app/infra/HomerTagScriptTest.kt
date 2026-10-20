package homer.app.infra

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavioural tests for the materialized `homer-tag` helper (the shell script
 * itself, run through `/bin/sh`). A stub `curl` on PATH captures the request and
 * returns a canned body + HTTP status, so the tests cover the script's own logic:
 * env/argument validation before any network call, success mapping to exit 0, and
 * a server rejection surfacing its `msg` on stderr with exit 2. See ADR-0060.
 */
class HomerTagScriptTest {
    private data class Run(
        val exit: Int,
        val stdout: String,
        val stderr: String,
    )

    private val scriptPath: Path =
        Paths.get(
            checkNotNull(javaClass.classLoader.getResource("scripts/homer-tag.sh")) {
                "scripts/homer-tag.sh missing from resources"
            }.toURI(),
        )

    /**
     * Writes a stub `curl` into [binDir] that ignores its args, prints
     * `<body>\n<code>` (matching the script's `-w '\n%{http_code}'`), and exits
     * [curlExit] — so a non-zero exit exercises the "Homer unreachable" branch.
     */
    private fun writeStubCurl(
        binDir: Path,
        body: String,
        code: String,
        curlExit: Int,
    ) {
        val stub = binDir.resolve("curl")
        stub.writeText(
            """
            #!/bin/sh
            printf '%s\n%s' "${'$'}STUB_BODY" "${'$'}STUB_CODE"
            exit ${'$'}STUB_EXIT
            """.trimIndent() + "\n",
        )
        stub.setPosixFilePermissions(
            java.nio.file.attribute.PosixFilePermissions
                .fromString("rwxr-xr-x"),
        )
    }

    private fun runHomerTag(
        arg: String?,
        env: Map<String, String>,
        binDir: Path,
        stubBody: String = "",
        stubCode: String = "200",
        stubExit: Int = 0,
    ): Run {
        writeStubCurl(binDir, stubBody, stubCode, stubExit)
        val command = mutableListOf("/bin/sh", scriptPath.toString())
        if (arg != null) command.add(arg)
        val pb = ProcessBuilder(command)
        pb.environment().clear()
        pb.environment()["PATH"] = "$binDir:/usr/bin:/bin"
        pb.environment()["STUB_BODY"] = stubBody
        pb.environment()["STUB_CODE"] = stubCode
        pb.environment()["STUB_EXIT"] = stubExit.toString()
        pb.environment().putAll(env)
        val process = pb.start()
        val stdout = process.inputStream.readBytes().decodeToString()
        val stderr = process.errorStream.readBytes().decodeToString()
        process.waitFor()
        return Run(process.exitValue(), stdout, stderr)
    }

    private val goodEnv = mapOf("HMR_API_URL" to "http://homer.test", "HMR_CALLBACK_TOKEN" to "tok")

    @Test
    fun `posts and exits 0 on a 200 response`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result =
            runHomerTag(
                "decision:approved",
                goodEnv,
                dir,
                stubBody = """{"tag":"decision:approved","count":1}""",
                stubCode = "200",
            )

        assertEquals(0, result.exit, "a 200 must map to exit 0; stderr=${result.stderr}")
    }

    @Test
    fun `surfaces the server msg and exits 2 on a 400 response`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result =
            runHomerTag(
                "bogus:value",
                goodEnv,
                dir,
                stubBody = """{"msg":"key 'bogus' is not a declared tags.runtime key"}""",
                stubCode = "400",
            )

        assertEquals(2, result.exit)
        assertTrue(
            "is not a declared tags.runtime key" in result.stderr,
            "the server's rejection reason must reach stderr: ${result.stderr}",
        )
    }

    @Test
    fun `exits 2 when Homer is unreachable`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result = runHomerTag("k:v", goodEnv, dir, stubExit = 7)

        assertEquals(2, result.exit)
        assertTrue("unreachable" in result.stderr, "a transport failure must be reported: ${result.stderr}")
    }

    @Test
    fun `exits 2 when the callback token is missing`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result = runHomerTag("k:v", mapOf("HMR_API_URL" to "http://homer.test"), dir)

        assertEquals(2, result.exit)
        assertTrue("HMR_CALLBACK_TOKEN" in result.stderr, "missing token must be named: ${result.stderr}")
    }

    @Test
    fun `exits 2 for an argument with no colon`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result = runHomerTag("noseparator", goodEnv, dir)

        assertEquals(2, result.exit)
        assertTrue("KEY:VALUE" in result.stderr, "usage must be shown: ${result.stderr}")
    }

    @Test
    fun `exits 2 for an empty value`(
        @TempDir dir: Path,
    ) {
        assumeShellAvailable()
        val result = runHomerTag("key:", goodEnv, dir)

        assertEquals(2, result.exit)
        assertTrue("VALUE must not be empty" in result.stderr, "empty value must be rejected: ${result.stderr}")
    }

    private fun assumeShellAvailable() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        assumeTrue(Paths.get("/bin/sh").exists(), "POSIX sh required")
        assumeTrue(scriptPath.exists(), "homer-tag.sh must be on the test classpath")
    }
}
