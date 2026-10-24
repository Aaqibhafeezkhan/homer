package homer.app.infra

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import homer.app.fixtures.TestFixtures
import homer.app.models.ResultEnvParam
import homer.app.models.ResultTransformer
import homer.app.models.SrcType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * Unit tests for ResultToEnvMapper
 * Tests result-to-environment variable mapping with various transformers
 */
class ResultToEnvMapperTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var stdOutFile: Path
    private lateinit var stdErrFile: Path

    @BeforeEach
    fun setup() {
        stdOutFile = tempDir.resolve("stdout.txt")
        stdErrFile = tempDir.resolve("stderr.txt")
    }

    @AfterEach
    fun teardown() {
        // Cleanup handled by @TempDir
    }

    // ========== Empty/No Params Tests ==========

    @Test
    fun `mapToEnv should return empty map when no params`() {
        Files.writeString(stdOutFile, "some output")
        Files.writeString(stdErrFile, "some error")
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOutFile, stdErr = stdErrFile)

        val mapper = ResultToEnvMapper(result)
        val env = mapper.mapToEnv(emptyList())

        assertTrue(env.isEmpty(), "Should return empty map when no parameters")
    }

    // ========== Pass Transformer Tests ==========

    @Test
    fun `mapToEnv should extract value with Pass transformer from stdout`() {
        Files.writeString(stdOutFile, "Hello from stdout")
        Files.writeString(stdErrFile, "")
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "OUTPUT",
                    transformer = ResultTransformer.Pass,
                ),
            )

        val mapper = ResultToEnvMapper(result)
        val env = mapper.mapToEnv(params)

        assertEquals(1, env.size)
        assertEquals("Hello from stdout", env["OUTPUT"])
    }

    @Test
    fun `mapToEnv should extract value with Pass transformer from stderr`() {
        Files.writeString(stdOutFile, "")
        Files.writeString(stdErrFile, "Error message")
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(
                    src = SrcType.STD_ERR,
                    envName = "ERROR",
                    transformer = ResultTransformer.Pass,
                ),
            )

        val mapper = ResultToEnvMapper(result)
        val env = mapper.mapToEnv(params)

        assertEquals(1, env.size)
        assertEquals("Error message", env["ERROR"])
    }

    // The `quickstart` example gates its closing steps on `[[ "$SHIP_ANSWER" == ship* ]]`,
    // where SHIP_ANSWER is a captured stdout: a trim here would stop both predicates
    // matching and the run would end FINISHED printing nothing. Pins the raw capture.
    @Test
    fun `mapToEnv should preserve the trailing newline with Pass transformer`() {
        Files.writeString(stdOutFile, "ship\n")
        Files.writeString(stdErrFile, "")
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "SHIP_ANSWER",
                    transformer = ResultTransformer.Pass,
                ),
            )

        val env = ResultToEnvMapper(result).mapToEnv(params)

        assertEquals("ship\n", env["SHIP_ANSWER"], "a Pass transformer must not trim captured stdout")
    }

    @Test
    fun `mapToEnv should read stderr and stdout from separate sources`() {
        Files.writeString(stdOutFile, "stdout content")
        Files.writeString(stdErrFile, "stderr content")
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(src = SrcType.STD_OUT, envName = "OUT", transformer = ResultTransformer.Pass),
                ResultEnvParam(src = SrcType.STD_ERR, envName = "ERR", transformer = ResultTransformer.Pass),
            )

        val mapper = ResultToEnvMapper(result)
        val env = mapper.mapToEnv(params)

        assertEquals("stdout content", env["OUT"])
        assertEquals("stderr content", env["ERR"])
    }

    @Test
    fun `mapToEnv should extract value with JsonPath transformer from stderr`() {
        Files.writeString(stdOutFile, "{}")
        Files.writeString(stdErrFile, """{"errorCode": "E404", "message": "not found"}""")
        val result = TestFixtures.mockCommandResult(exitCode = 1, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(
                    src = SrcType.STD_ERR,
                    envName = "ERROR_CODE",
                    transformer = ResultTransformer.JsonPath(path = "$.errorCode"),
                ),
            )

        val mapper = ResultToEnvMapper(result)
        val env = mapper.mapToEnv(params)

        assertEquals(1, env.size)
        assertEquals("E404", env["ERROR_CODE"])
    }

    @Test
    fun `mapToEnv should extract capture group with Regex transformer from stderr`() {
        Files.writeString(stdOutFile, "")
        Files.writeString(stdErrFile, "Error: exit status 127")
        val result = TestFixtures.mockCommandResult(exitCode = 1, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(
                    src = SrcType.STD_ERR,
                    envName = "EXIT_STATUS",
                    transformer = ResultTransformer.Regex(regex = "exit status (\\d+)", group = 1),
                ),
            )

        val mapper = ResultToEnvMapper(result)
        val env = mapper.mapToEnv(params)

        assertEquals(1, env.size)
        assertEquals("127", env["EXIT_STATUS"])
    }

    @Test
    fun `mapToEnv should extract value with Pass transformer from result code`() {
        Files.writeString(stdOutFile, "")
        Files.writeString(stdErrFile, "")
        val result = TestFixtures.mockCommandResult(exitCode = 42, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(
                    src = SrcType.RESULT_CODE,
                    envName = "EXIT_CODE",
                    transformer = ResultTransformer.Pass,
                ),
            )

        val mapper = ResultToEnvMapper(result)
        val env = mapper.mapToEnv(params)

        assertEquals(1, env.size)
        assertEquals("42", env["EXIT_CODE"])
    }

    // ========== JsonPath Transformer Tests ==========

    @Test
    fun `mapToEnv should extract value with JsonPath transformer from stdout`() {
        val jsonOutput = """{"status": "success", "value": 123}"""
        Files.writeString(stdOutFile, jsonOutput)
        Files.writeString(stdErrFile, "")
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "STATUS",
                    transformer = ResultTransformer.JsonPath(path = "$.status"),
                ),
            )

        val mapper = ResultToEnvMapper(result)
        val env = mapper.mapToEnv(params)

        assertEquals(1, env.size)
        assertEquals("success", env["STATUS"])
    }

    @Test
    fun `mapToEnv should extract nested value with JsonPath transformer`() {
        val jsonOutput = """{"data": {"user": {"name": "John"}}}"""
        Files.writeString(stdOutFile, jsonOutput)
        Files.writeString(stdErrFile, "")
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "USER_NAME",
                    transformer = ResultTransformer.JsonPath(path = "$.data.user.name"),
                ),
            )

        val mapper = ResultToEnvMapper(result)
        val env = mapper.mapToEnv(params)

        assertEquals(1, env.size)
        assertEquals("John", env["USER_NAME"])
    }

    @Test
    fun `mapToEnv should handle JsonPath extraction failure gracefully`() {
        val jsonOutput = """{"status": "success"}"""
        Files.writeString(stdOutFile, jsonOutput)
        Files.writeString(stdErrFile, "")
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "MISSING",
                    transformer = ResultTransformer.JsonPath(path = "$.nonexistent"),
                ),
            )

        val mapper = ResultToEnvMapper(result)
        val env = mapper.mapToEnv(params)

        // Should not include the variable if path doesn't exist
        assertFalse(env.containsKey("MISSING"))
    }

    @Test
    fun `mapToEnv should handle invalid JSON gracefully`() {
        Files.writeString(stdOutFile, "not valid json")
        Files.writeString(stdErrFile, "")
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "VALUE",
                    transformer = ResultTransformer.JsonPath(path = "$.value"),
                ),
            )

        val mapper = ResultToEnvMapper(result)

        // Should handle parsing errors gracefully (might throw or return empty)
        val env =
            try {
                mapper.mapToEnv(params)
            } catch (e: Exception) {
                emptyMap()
            }

        // Either way, we shouldn't crash
        assertTrue(env.isEmpty() || !env.containsKey("VALUE"))
    }

    // ========== Claude NDJSON (stream-json) JsonPath Tests ==========

    private val claudeNdjsonStdout =
        listOf(
            """{"type":"system","subtype":"init","claude_code_version":"1.2.3"}""",
            """{"type":"assistant","message":{"content":"hi"}}""",
            """{"type":"result","subtype":"success","total_cost_usd":0.42,"result":"done"}""",
        ).joinToString("\n")

    @Test
    fun `mapToEnv should evaluate JsonPath against Claude NDJSON stdout as an event array`() {
        // Claude's --output-format stream-json writes NDJSON (one event per line),
        // which is not a single JSON document. The mapper re-assembles the lines
        // into a JSON array so array-filter JsonPath works as it did against the
        // pre-streaming --output-format json output. A filter is an "indefinite"
        // JsonPath, so the match is returned as a JSON array string.
        Files.writeString(stdOutFile, claudeNdjsonStdout)
        Files.writeString(stdErrFile, "")
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "CLAUDE_COST",
                    transformer = ResultTransformer.JsonPath(path = "$[?(@.type=='result')].total_cost_usd"),
                ),
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "CLAUDE_RESULT",
                    transformer = ResultTransformer.JsonPath(path = "$[?(@.type=='result')].result"),
                ),
            )

        val env = ResultToEnvMapper(result).mapToEnv(params)

        assertEquals("[0.42]", env["CLAUDE_COST"])
        assertEquals("""["done"]""", env["CLAUDE_RESULT"])
    }

    @Test
    fun `mapToEnv should extract a scalar from Claude NDJSON stdout with a definite JsonPath`() {
        Files.writeString(stdOutFile, claudeNdjsonStdout)
        Files.writeString(stdErrFile, "")
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "CLAUDE_RESULT",
                    transformer = ResultTransformer.JsonPath(path = "$[2].result"),
                ),
            )

        val env = ResultToEnvMapper(result).mapToEnv(params)

        assertEquals(1, env.size)
        assertEquals("done", env["CLAUDE_RESULT"])
    }

    @Test
    fun `mapToEnv should skip JsonPath with no match against Claude NDJSON stdout`() {
        Files.writeString(stdOutFile, claudeNdjsonStdout)
        Files.writeString(stdErrFile, "")
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "MISSING",
                    transformer = ResultTransformer.JsonPath(path = "$[?(@.type=='nope')].value"),
                ),
            )

        val env = ResultToEnvMapper(result).mapToEnv(params)

        // An empty filter match yields "[]" rather than being absent — the path
        // itself is valid, it just matched nothing.
        assertEquals("[]", env["MISSING"])
    }

    // ========== Regex Transformer Tests ==========

    @Test
    fun `mapToEnv should extract value with Regex transformer full match`() {
        Files.writeString(stdOutFile, "Version: 1.2.3")
        Files.writeString(stdErrFile, "")
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "VERSION",
                    transformer = ResultTransformer.Regex(regex = "\\d+\\.\\d+\\.\\d+"),
                ),
            )

        val mapper = ResultToEnvMapper(result)
        val env = mapper.mapToEnv(params)

        assertEquals(1, env.size)
        assertEquals("1.2.3", env["VERSION"])
    }

    @Test
    fun `mapToEnv should extract specific group with Regex transformer`() {
        Files.writeString(stdOutFile, "Error code: 404")
        Files.writeString(stdErrFile, "")
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "ERROR_CODE",
                    transformer = ResultTransformer.Regex(regex = "Error code: (\\d+)", group = 1),
                ),
            )

        val mapper = ResultToEnvMapper(result)
        val env = mapper.mapToEnv(params)

        assertEquals(1, env.size)
        assertEquals("404", env["ERROR_CODE"])
    }

    @Test
    fun `mapToEnv should handle Regex no match gracefully`() {
        Files.writeString(stdOutFile, "No version here")
        Files.writeString(stdErrFile, "")
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "VERSION",
                    transformer = ResultTransformer.Regex(regex = "\\d+\\.\\d+\\.\\d+"),
                ),
            )

        val mapper = ResultToEnvMapper(result)
        val env = mapper.mapToEnv(params)

        // Should not include the variable if regex doesn't match
        assertFalse(env.containsKey("VERSION"))
    }

    // The shared bound with the `!<agent>` queryParams transformer: a shed match and an
    // uncompilable pattern both omit the value instead of pinning a CPU or throwing.
    @Test
    fun `mapToEnv should omit a value whose regex exceeds the time bound`() {
        Files.writeString(stdOutFile, "a".repeat(30))
        Files.writeString(stdErrFile, "")
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "SLOW",
                    transformer = ResultTransformer.Regex(regex = "(a+)+\\1b"),
                ),
            )

        val env = ResultToEnvMapper(result).mapToEnv(params)

        assertFalse(env.containsKey("SLOW"), "a shed regex match must omit the value")
    }

    @Test
    fun `mapToEnv should omit a value whose regex does not compile`() {
        Files.writeString(stdOutFile, "anything")
        Files.writeString(stdErrFile, "")
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "BAD_PATTERN",
                    transformer = ResultTransformer.Regex(regex = "("),
                ),
            )

        val env = ResultToEnvMapper(result).mapToEnv(params)

        assertFalse(env.containsKey("BAD_PATTERN"))
    }

    // An uncompilable pattern omits only its own value: the shed-then-skip cap fires
    // only on a shed match, so a later valid regex param still resolves. §12 rule 4.
    @Test
    fun `mapToEnv still evaluates a later regex param after an uncompilable one`() {
        Files.writeString(stdOutFile, "Version: 1.2.3")
        Files.writeString(stdErrFile, "")
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "BAD_PATTERN",
                    transformer = ResultTransformer.Regex(regex = "("),
                ),
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "VERSION",
                    transformer = ResultTransformer.Regex(regex = "\\d+\\.\\d+\\.\\d+"),
                ),
            )

        val env = ResultToEnvMapper(result).mapToEnv(params)

        assertFalse(env.containsKey("BAD_PATTERN"))
        assertEquals("1.2.3", env["VERSION"], "an uncompilable pattern must not skip a later regex param")
    }

    // The shed-then-skip contract the `!<agent>` queryParams path already has: after one
    // regex param is shed, the remaining regex params are omitted without a second bounded
    // match, while a non-regex transformer still runs. §12 rule 4.
    @Test
    fun `mapToEnv omits the remaining regex params after one is shed without evaluating them`() {
        val mapperLogger = LoggerFactory.getLogger(ResultToEnvMapper::class.java) as LogbackLogger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        mapperLogger.addAppender(appender)
        try {
            Files.writeString(stdOutFile, "a".repeat(30))
            Files.writeString(stdErrFile, "")
            val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOutFile, stdErr = stdErrFile)

            val params =
                listOf(
                    ResultEnvParam(
                        src = SrcType.STD_OUT,
                        envName = "SLOW_A",
                        transformer = ResultTransformer.Regex(regex = "(a+)+\\1b"),
                    ),
                    ResultEnvParam(
                        src = SrcType.STD_OUT,
                        envName = "SLOW_B",
                        transformer = ResultTransformer.Regex(regex = "(a+)+\\1b"),
                    ),
                    ResultEnvParam(
                        src = SrcType.STD_OUT,
                        envName = "PASSED",
                        transformer = ResultTransformer.Pass,
                    ),
                )

            val env = ResultToEnvMapper(result).mapToEnv(params)

            assertFalse(env.containsKey("SLOW_A"), "the shed regex param is omitted")
            assertFalse(env.containsKey("SLOW_B"), "the later regex param is skipped, not evaluated")
            assertEquals("a".repeat(30), env["PASSED"], "a non-regex param after a shed one still resolves")
            val logged = appender.list.filter { it.level.levelStr == "WARN" }.map { it.formattedMessage }
            assertTrue(logged.any { it.contains("SLOW_A") }, "expected a WARN for the shed param, got $logged")
            assertTrue(logged.none { it.contains("SLOW_B") }, "the second regex param should not be matched: $logged")
        } finally {
            mapperLogger.detachAppender(appender)
        }
    }

    // ========== Multiple Parameters Tests ==========

    @Test
    fun `mapToEnv should process multiple parameters`() {
        val jsonOutput = """{"status": "ok", "count": 5}"""
        Files.writeString(stdOutFile, jsonOutput)
        Files.writeString(stdErrFile, "")
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "STATUS",
                    transformer = ResultTransformer.JsonPath(path = "$.status"),
                ),
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "COUNT",
                    transformer = ResultTransformer.JsonPath(path = "$.count"),
                ),
                ResultEnvParam(
                    src = SrcType.RESULT_CODE,
                    envName = "EXIT",
                    transformer = ResultTransformer.Pass,
                ),
            )

        val mapper = ResultToEnvMapper(result)
        val env = mapper.mapToEnv(params)

        assertEquals(3, env.size)
        assertEquals("ok", env["STATUS"])
        assertEquals("5", env["COUNT"])
        assertEquals("0", env["EXIT"])
    }

    @Test
    fun `mapToEnv should handle mixed transformers`() {
        Files.writeString(stdOutFile, """{"version": "2.0"}""")
        Files.writeString(stdErrFile, "")
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "RAW_OUTPUT",
                    transformer = ResultTransformer.Pass,
                ),
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "VERSION",
                    transformer = ResultTransformer.JsonPath(path = "$.version"),
                ),
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "VERSION_NUMBER",
                    transformer = ResultTransformer.Regex(regex = "\\d+\\.\\d+"),
                ),
            )

        val mapper = ResultToEnvMapper(result)
        val env = mapper.mapToEnv(params)

        assertEquals(3, env.size)
        assertEquals("""{"version": "2.0"}""", env["RAW_OUTPUT"])
        assertEquals("2.0", env["VERSION"])
        assertEquals("2.0", env["VERSION_NUMBER"])
    }

    @Test
    fun `mapToEnv should skip parameters that fail extraction`() {
        Files.writeString(stdOutFile, "Simple text output")
        Files.writeString(stdErrFile, "")
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOutFile, stdErr = stdErrFile)

        val params =
            listOf(
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "GOOD",
                    transformer = ResultTransformer.Pass,
                ),
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "BAD_JSON",
                    transformer = ResultTransformer.JsonPath(path = "$.missing"),
                ),
                ResultEnvParam(
                    src = SrcType.STD_OUT,
                    envName = "BAD_REGEX",
                    transformer = ResultTransformer.Regex(regex = "\\d+\\.\\d+\\.\\d+"),
                ),
            )

        val mapper = ResultToEnvMapper(result)
        val env =
            try {
                mapper.mapToEnv(params)
            } catch (e: Exception) {
                // If JSON parsing fails completely, we get an empty map
                emptyMap()
            }

        // Only the Pass transformer should succeed
        assertTrue(env.containsKey("GOOD") || env.isEmpty())
        assertFalse(env.containsKey("BAD_REGEX"))
    }
}
