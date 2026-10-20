package homer.app.executors.llm

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import homer.app.EnvConstants
import homer.app.config.ExecutionConfig
import homer.app.exceptions.ClaudeCliVersionMismatchException
import homer.app.exceptions.CommandExecutionException
import homer.app.exceptions.MalformedLlmOutputException
import homer.app.exceptions.OutputCapExceededException
import homer.app.executors.ArtifactNameResolver
import homer.app.executors.ExecutionContext
import homer.app.fixtures.TestFixtures
import homer.app.infra.DefaultTextCompiler
import homer.app.infra.LangfuseClient
import homer.app.infra.LlmCostResolver
import homer.app.infra.OutputLineFilter
import homer.app.models.ClaudeCmdOptions
import homer.app.models.Command
import homer.app.models.CommandResult
import homer.app.models.CostSource
import homer.app.models.FALLBACK_REASON_SESSION_FIGURE_PENDING
import homer.app.models.LlmAccounting
import homer.app.models.LlmEscalation
import homer.app.models.LlmEvent
import homer.app.models.LlmPrompt
import homer.app.models.LlmRetry
import homer.app.models.LlmUsage
import homer.app.models.ResolvedCost
import homer.app.models.When
import homer.app.services.CostMeter
import homer.app.services.LlmTelemetryService
import homer.app.services.ProcessManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * The generic LLM harness loop (retry, per-attempt accounting, fail-closed inspection,
 * telemetry hand-off), driven through [FakeHarness] so no real adapter's rules apply.
 */
class LlmHarnessExecutorTest {
    private lateinit var executor: LlmHarnessExecutor<Command.Claude>
    private lateinit var langfuse: LangfuseClient
    private lateinit var telemetry: LlmTelemetryService
    private lateinit var processManager: ProcessManager
    private lateinit var costMeter: CostMeter
    private lateinit var resolver: LlmCostResolver
    private lateinit var context: ExecutionContext
    private lateinit var span: Span
    private lateinit var stdOut: Path
    private lateinit var stdErr: Path
    private lateinit var tempDir: Path
    private lateinit var artifactNames: ArtifactNameResolver

    // Settlements run inline on the caller's thread here; a test that watches one settle builds its
    // executor on runTest's backgroundScope instead and drives it with the test scheduler.
    private val settlement = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val validOutput =
        """
        {"t":"start","sid":"s"}
        {"t":"msg","id":"m1"}
        {"t":"end","ok":true,"cost":0.0}
        """.trimIndent()

    @BeforeEach
    fun setup(
        @TempDir tempDir: Path,
    ) {
        this.tempDir = tempDir
        artifactNames = fixedArtifactNames(tempDir)
        stdOut = artifactNames.stdOut(0)
        stdErr = artifactNames.stdErr(0)
        stdOut.writeText(validOutput)
        stdErr.writeText("")

        langfuse = mockk(relaxed = true)
        telemetry = mockk(relaxed = true)
        processManager = mockk(relaxed = true)
        costMeter = mockk(relaxed = true)
        resolver = mockk()
        coEvery { resolver.resolve(any(), any(), any(), any(), any()) } answers { ResolvedCost(secondArg(), CostSource.CLI) }
        every { resolver.configured } returns false
        span = TestFixtures.mockSpan()
        coEvery { langfuse.compilePrompt(any(), any()) } answers { firstArg() }
        coEvery { telemetry.process(any(), any(), any(), any(), any(), any()) } just Runs

        context =
            ExecutionContext(
                workspace = TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig(env = emptyMap())),
                config = ExecutionConfig(),
                shell = TestFixtures.mockShellExecutor(),
                stdOut = stdOut,
                stdErr = stdErr,
                artifactNames = artifactNames,
            )
        executor = newExecutor(FakeHarness())
    }

    private fun newExecutor(
        harness: LlmHarness<Command.Claude>,
        random: Random = Random.Default,
        settlementScope: CoroutineScope = settlement,
    ) = LlmHarnessExecutor(
        harness,
        langfuse,
        telemetry,
        processManager,
        costMeter,
        settlementScope,
        llmCostResolver = resolver,
        random = random,
    )

    /** A resolver whose stem never varies by attempt — `context.stdOut`/`stdErr` stay valid for `.stdOut(0)`/`.stdErr(0)`. */
    private fun fixedArtifactNames(dir: Path): ArtifactNameResolver =
        ArtifactNameResolver(
            template = "cmd_0",
            textCompiler = DefaultTextCompiler(),
            artifactsDir = dir,
            cmdIndex = 0,
            envProvider = { emptyMap() },
        )

    private fun claude(
        options: ClaudeCmdOptions = ClaudeCmdOptions(),
        retry: LlmRetry? = null,
    ) = Command.Claude(prompt = LlmPrompt.Prompt("hi"), options = options, retry = retry)

    private fun stubShellExit(exitCode: Int = 0) {
        coEvery {
            context.shell.cmd(any(), any(), any(), any(), any(), any(), any())
        } returns TestFixtures.mockCommandResult(exitCode = exitCode, stdOut = stdOut, stdErr = stdErr)
    }

    /** One zero-exit attempt over whatever is in [stdOut]. */
    private suspend fun run(
        processId: Int = -1,
        agentName: String = "",
        exec: LlmHarnessExecutor<Command.Claude> = executor,
    ): CommandResult {
        stubShellExit(0)
        return exec.execute(claude(), context.copy(processId = processId, agentName = agentName), span)
    }

    // ========== retry ==========

    @Test
    fun `retry delay includes jitter drawn from the injected Random`() =
        runTest {
            val seed = 12345L
            val jitterExecutor = newExecutor(FakeHarness(), random = Random(seed))

            // First attempt fails (exit 1) to force a retry; second succeeds.
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } returnsMany
                listOf(
                    TestFixtures.mockCommandResult(exitCode = 1, stdOut = stdOut, stdErr = stdErr),
                    TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOut, stdErr = stdErr),
                )

            val baseDelayMs = 100L
            val start = testScheduler.currentTime
            jitterExecutor.execute(claude(retry = LlmRetry(attempts = 2, baseDelayMs = baseDelayMs, maxDelayMs = 10_000)), context, span)
            val elapsed = testScheduler.currentTime - start

            // Single retry: shift = 0 → exponential part = baseDelayMs. The jitter is
            // the first draw from an identically-seeded Random in [0, baseDelayMs).
            val expectedJitter = Random(seed).nextLong(baseDelayMs)
            assertEquals(baseDelayMs + expectedJitter, elapsed)
            verify { span.addEvent("llm.retry.attempt_1") }
        }

    @Test
    fun `retry policy retries non-zero exit until success`() =
        runTest {
            var attempt = 0
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } answers {
                attempt += 1
                if (attempt < 3) {
                    stdOut.writeText("")
                    TestFixtures.mockCommandResult(exitCode = 1, stdOut = stdOut, stdErr = stdErr)
                } else {
                    stdOut.writeText(validOutput)
                    TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOut, stdErr = stdErr)
                }
            }

            val result = executor.execute(claude(retry = LlmRetry(attempts = 3, baseDelayMs = 0, maxDelayMs = 0)), context, span)
            assertEquals(0, result.exitCode)
            assertEquals(3, attempt)
        }

    @Test
    fun `retry policy retries malformed output until valid`() =
        runTest {
            var attempt = 0
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } answers {
                attempt += 1
                stdOut.writeText(if (attempt < 2) "garbage not json" else validOutput)
                TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOut, stdErr = stdErr)
            }

            val result = executor.execute(claude(retry = LlmRetry(attempts = 3, baseDelayMs = 0, maxDelayMs = 0)), context, span)
            assertEquals(0, result.exitCode)
            assertEquals(2, attempt)
        }

    @Test
    fun `retry policy exhausted on persistent malformed output rethrows`() =
        runTest {
            var attempt = 0
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } answers {
                attempt += 1
                stdOut.writeText("still garbage")
                TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOut, stdErr = stdErr)
            }

            assertThrows<MalformedLlmOutputException> {
                executor.execute(claude(retry = LlmRetry(attempts = 3, baseDelayMs = 0, maxDelayMs = 0)), context, span)
            }
            assertEquals(3, attempt)
        }

    @Test
    fun `retry policy exhausted on persistent non-zero exit returns last failed result`() =
        runTest {
            // No exception is raised — downstream continueIfFail / finishExitCode handling
            // depends on receiving the actual non-zero CommandResult, not a thrown error.
            var attempt = 0
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } answers {
                attempt += 1
                stdOut.writeText("")
                TestFixtures.mockCommandResult(exitCode = 7, stdOut = stdOut, stdErr = stdErr)
            }

            val result: CommandResult =
                executor.execute(claude(retry = LlmRetry(attempts = 2, baseDelayMs = 0, maxDelayMs = 0)), context, span)
            assertEquals(7, result.exitCode)
            assertEquals(2, attempt)
        }

    @Test
    fun `null retry config invokes shell exactly once`() =
        runTest {
            var attempt = 0
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } answers {
                attempt += 1
                stdOut.writeText(validOutput)
                TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOut, stdErr = stdErr)
            }

            executor.execute(claude(), context, span)
            assertEquals(1, attempt)
        }

    @Test
    fun `a turn-cap stop is not retried even when retry attempts remain`() =
        runTest {
            // Another attempt from scratch would burn the same budget again for the same
            // reason; the cap stop is terminal for the retry loop.
            stdOut.writeText("""{"t":"stop"}""")
            stubShellExit(0)

            val result =
                newExecutor(FakeHarness(terminalExit = 7))
                    .execute(claude(retry = LlmRetry(attempts = 3, baseDelayMs = 0, maxDelayMs = 0)), context, span)
            assertEquals(7, result.exitCode)
            coVerify(exactly = 1) { context.shell.cmd(any(), any(), any(), any(), any(), any(), any()) }
            verify { span.setAttribute("claude.max_turns_stop", true) }
        }

    @Test
    fun `a turn-cap stop still reaches the telemetry sink`() =
        runTest {
            stdOut.writeText("""{"t":"stop"}""")
            stubShellExit(0)

            newExecutor(FakeHarness(terminalExit = 7)).execute(claude(), context, span)

            coVerify(exactly = 1) { telemetry.process(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a non-zero exit whose stdout has valid lines reaches the telemetry sink`() =
        runTest {
            stubShellExit(1)

            executor.execute(claude(), context, span)

            coVerify(exactly = 1) { telemetry.process(match { it.events.isNotEmpty() }, any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a cancellation during the telemetry hand-off propagates instead of returning the result`() =
        runTest {
            stubShellExit(1)
            coEvery { telemetry.process(any(), any(), any(), any(), any(), any()) } throws CancellationException("killed")

            assertThrows<CancellationException> { executor.execute(claude(), context, span) }

            verify(exactly = 0) { span.setAttribute("telemetry.error", any<String>()) }
        }

    @Test
    fun `a telemetry failure during the hand-off is recorded on the span and the result still returns`() =
        runTest {
            stubShellExit(0)
            coEvery { telemetry.process(any(), any(), any(), any(), any(), any()) } throws IllegalStateException("sink down")

            assertEquals(0, executor.execute(claude(), context, span).exitCode)

            verify(exactly = 1) { span.setAttribute("telemetry.error", "sink down") }
        }

    @Test
    fun `a non-zero exit with a truncated last line is parsed leniently and reaches the sink`() =
        runTest {
            // A killed CLI's stdout ends mid-line; the events before it (and their spans) must survive.
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"s\"}\n{\"t\":\"msg\",\"i")
            stubShellExit(137)

            assertEquals(137, executor.execute(claude(), context, span).exitCode)

            coVerify(exactly = 1) {
                telemetry.process(match { stream -> stream.events.any { it is LlmEvent.SessionStart } }, any(), any(), any(), any(), any())
            }
            verify(exactly = 0) { span.setAttribute("telemetry.error", any<String>()) }
        }

    @Test
    fun `a garbled mid-stream line on a non-zero exit is counted into the malformed counter`() =
        runTest {
            // Leniency must not be silent: a skipped line would otherwise drop its spans with no signal.
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"s\"}\nthis-is-not-json\n{\"t\":\"msg\",\"i")
            stubShellExit(137)

            assertEquals(137, executor.execute(claude(), context, span).exitCode)

            coVerify(exactly = 1) {
                telemetry.process(match { stream -> stream.malformed == 2L }, any(), any(), any(), any(), any())
            }
        }

    /** Stands in for an adapter's operator-misconfiguration abort (Claude's version pin). */
    private class PinMismatch : IllegalStateException("pin mismatch")

    @Test
    fun `an exception thrown by inspect escapes without a retry`() =
        runTest {
            stubShellExit(0)
            val throwing =
                object : FakeHarness() {
                    override fun inspect(
                        events: List<LlmEvent>,
                        raw: List<JsonObject>,
                        command: Command.Claude,
                    ): Inspection = throw PinMismatch()
                }

            assertThrows<PinMismatch> {
                newExecutor(throwing).execute(claude(retry = LlmRetry(attempts = 3, baseDelayMs = 0, maxDelayMs = 0)), context, span)
            }
            coVerify(exactly = 1) { context.shell.cmd(any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `empty stdout on exit 0 throws MalformedLlmOutputException`() =
        runTest {
            stdOut.writeText("")
            stubShellExit(0)

            assertThrows<MalformedLlmOutputException> { executor.execute(claude(), context, span) }
        }

    @Test
    fun `a zero exit with no turn end is rejected by inspection`() =
        runTest {
            stdOut.writeText("""{"t":"start","sid":"s"}""")
            stubShellExit(0)

            val e = assertThrows<MalformedLlmOutputException> { executor.execute(claude(), context, span) }
            assertEquals("no end", e.reason)
        }

    // ========== accounting ==========

    @Test
    fun `successful run reports total_cost_usd to ProcessManager`() =
        runTest {
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"s\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.42}")
            run(processId = 7)
            coVerify(exactly = 1) { processManager.addCost(7, 0.42, CostSource.CLI) }
        }

    @Test
    fun `processId of -1 skips cost reporting`() =
        runTest {
            // Default ExecutionContext has processId = -1 (e.g. agentdebug runs).
            stdOut.writeText("""{"t":"end","ok":true,"cost":1.0}""")
            run()
            coVerify(exactly = 0) { processManager.addCost(any(), any(), any()) }
        }

    @Test
    fun `zero total_cost_usd does not call addCost`() =
        runTest {
            run(processId = 9)
            coVerify(exactly = 0) { processManager.addCost(any(), any(), any()) }
        }

    @Test
    fun `result without total_cost_usd field does not call addCost`() =
        runTest {
            stdOut.writeText("""{"t":"end","ok":true}""")
            run(processId = 11)
            coVerify(exactly = 0) { processManager.addCost(any(), any(), any()) }
        }

    @Test
    fun `missing total_cost_usd on success result sets llm_cost_parse_failed span attribute (audit P1 #15)`() =
        runTest {
            stdOut.writeText("""{"t":"end","ok":true}""")
            run(processId = 12)
            verify { span.setAttribute("llm.cost.parse_failed", true) }
        }

    @Test
    fun `unparseable total_cost_usd on success result sets llm_cost_parse_failed span attribute (audit P1 #15)`() =
        runTest {
            stdOut.writeText("""{"t":"end","ok":true,"cost":"not-a-number"}""")
            run(processId = 13)
            verify { span.setAttribute("llm.cost.parse_failed", true) }
            coVerify(exactly = 0) { processManager.addCost(any(), any(), any()) }
        }

    @Test
    fun `valid total_cost_usd does not set llm_cost_parse_failed span attribute`() =
        runTest {
            run(processId = 14)
            verify(exactly = 0) { span.setAttribute("llm.cost.parse_failed", any<Boolean>()) }
        }

    @Test
    fun `executor never calls checkCaps - cap enforcement lives at the request boundary`() =
        runTest {
            run(processId = 1, agentName = "demo")
            coVerify(exactly = 0) { costMeter.checkCaps(any()) }
        }

    @Test
    fun `recordCost called with agentName and cost on successful run`() =
        runTest {
            stdOut.writeText("""{"t":"end","ok":true,"cost":0.75}""")
            run(processId = 1, agentName = "demo")
            coVerify(exactly = 1) { costMeter.recordCost("demo", 0.75) }
        }

    @Test
    fun `empty agentName skips recordCost`() =
        runTest {
            // Default ExecutionContext has agentName = "" (e.g. agentdebug).
            run()
            coVerify(exactly = 0) { costMeter.recordCost(any(), any()) }
        }

    @Test
    fun `rejected error_max_turns attempt still charges its billed cost to both sinks`() =
        runTest {
            // A rejected exit-0 attempt with real spend: the output is refused (throws), but
            // the cost MUST be charged — otherwise forcing failures bypasses the cost cap.
            stdOut.writeText("""{"t":"end","ok":false,"cost":1.5}""")
            stubShellExit(0)

            assertThrows<MalformedLlmOutputException> {
                executor.execute(claude(), context.copy(agentName = "demo", processId = 7), span)
            }
            coVerify(exactly = 1) { processManager.addCost(7, 1.5, CostSource.CLI) }
            coVerify(exactly = 1) { costMeter.recordCost("demo", 1.5) }
        }

    @Test
    fun `failure subtype without a cost figure charges nothing`() =
        runTest {
            stdOut.writeText("""{"t":"end","ok":false}""")
            stubShellExit(0)

            assertThrows<MalformedLlmOutputException> {
                executor.execute(claude(), context.copy(agentName = "demo", processId = 8), span)
            }
            coVerify(exactly = 0) { processManager.addCost(any(), any(), any()) }
            coVerify(exactly = 0) { costMeter.recordCost(any(), any()) }
        }

    @Test
    fun `a failed billable attempt before a later success charges both attempts`() =
        runTest {
            var attempt = 0
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } answers {
                attempt += 1
                stdOut.writeText(
                    if (attempt == 1) """{"t":"end","ok":false,"cost":2.0}""" else """{"t":"end","ok":true,"cost":0.5}""",
                )
                TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOut, stdErr = stdErr)
            }

            executor.execute(
                claude(retry = LlmRetry(attempts = 2, baseDelayMs = 0, maxDelayMs = 0)),
                context.copy(agentName = "demo", processId = 9),
                span,
            )
            assertEquals(2, attempt)
            coVerify(exactly = 1) { processManager.addCost(9, 2.0, CostSource.CLI) }
            coVerify(exactly = 1) { processManager.addCost(9, 0.5, CostSource.CLI) }
            coVerify(exactly = 1) { costMeter.recordCost("demo", 2.0) }
            coVerify(exactly = 1) { costMeter.recordCost("demo", 0.5) }
        }

    @Test
    fun `success attempt is priced through the resolver with its session id and cli cost`() =
        runTest {
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"sess-9\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.42}")
            coEvery { resolver.resolve("sess-9", 0.42, any(), any(), any()) } returns ResolvedCost(0.18, CostSource.SESSION)

            val result = run(processId = 7, agentName = "demo")

            coVerify(exactly = 1) { processManager.addCost(7, 0.18, CostSource.SESSION) }
            coVerify(exactly = 1) { costMeter.recordCost("demo", 0.18) }
            verify { span.setAttribute("llm.cost_usd", 0.18) }
            verify { span.setAttribute("llm.cost.source", "session") }
            verify { span.setAttribute("llm.session_id", "sess-9") }
            assertEquals(LlmAccounting("sess-9", 0.18, CostSource.SESSION, null, usage = LlmUsage()), result.llmAccounting)
            coVerify { telemetry.process(any(), any(), any(), any(), any(), 0.18) }
        }

    @Test
    fun `with a configured resolver the attempt returns on the cli figure before the session lookup answers`() =
        runTest {
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"sess-9\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.42}")
            every { resolver.configured } returns true
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } coAnswers { CompletableDeferred<ResolvedCost>().await() }

            val result = run(processId = 7, agentName = "demo", exec = newExecutor(FakeHarness(), settlementScope = backgroundScope))

            assertEquals(
                LlmAccounting("sess-9", 0.42, CostSource.CLI, FALLBACK_REASON_SESSION_FIGURE_PENDING, usage = LlmUsage()),
                result.llmAccounting,
            )
            verify { span.setAttribute("llm.cost.fallback_reason", FALLBACK_REASON_SESSION_FIGURE_PENDING) }
            coVerify(exactly = 0) { processManager.addCost(any(), any(), any()) }
            coVerify(exactly = 0) { costMeter.recordCost(any(), any()) }
        }

    @Test
    fun `the attempt's model call count is passed to the resolver`() =
        runTest {
            // Two distinct message ids across three message events — the resolver must be told 2 calls, not 3.
            stdOut.writeText(
                """
                {"t":"start","sid":"sess-c"}
                {"t":"msg","id":"msg_1"}
                {"t":"msg","id":"msg_1"}
                {"t":"msg","id":"msg_2"}
                {"t":"end","ok":true,"cost":0.5}
                """.trimIndent(),
            )
            coEvery { resolver.resolve("sess-c", 0.5, 2, any(), any()) } returns ResolvedCost(0.24, CostSource.SESSION)

            val result = run(processId = 7)

            coVerify(exactly = 1) { resolver.resolve("sess-c", 0.5, 2, any(), any()) }
            assertEquals(LlmAccounting("sess-c", 0.24, CostSource.SESSION, null, usage = LlmUsage()), result.llmAccounting)
        }

    @Test
    fun `a harness that cannot count model calls passes no call count and gets no cap mark`() =
        runTest {
            writeStreamWithCalls("sess-nc", 1_000 + 500)
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } returns ResolvedCost(0.24, CostSource.SESSION)

            val result = run(processId = 7, exec = newExecutor(FakeHarness(countsModelCalls = false)))

            coVerify(exactly = 1) { resolver.resolve("sess-nc", 0.5, null, any(), any()) }
            assertEquals(null, result.llmAccounting?.fallbackReason)
        }

    @Test
    fun `the attempt's wall-clock window is captured and handed to the resolver`() =
        runTest {
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"sess-w\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.5}")
            var start: Long? = null
            var end: Long? = null
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } coAnswers {
                start = arg(3)
                end = arg(4)
                ResolvedCost(0.2, CostSource.SESSION)
            }

            run(processId = 7)

            assertTrue(start != null && end != null, "both window boundaries must be passed")
            assertTrue(end!! >= start!!, "the window end must not precede its start")
        }

    @Test
    fun `the per-attempt window figure is what reaches both cost sinks`() =
        runTest {
            // The window-priced figure (0.2), not the session total, must be charged.
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"sess-w\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.9}")
            coEvery { resolver.resolve("sess-w", 0.9, any(), any(), any()) } returns ResolvedCost(0.2, CostSource.SESSION)

            val result = run(processId = 7, agentName = "demo")

            coVerify(exactly = 1) { processManager.addCost(7, 0.2, CostSource.SESSION) }
            coVerify(exactly = 1) { costMeter.recordCost("demo", 0.2) }
            verify { span.setAttribute("llm.cost_usd", 0.2) }
            assertEquals(0.2, result.llmAccounting?.costUsd)
        }

    private fun writeStreamWithCalls(
        sessionId: String,
        calls: Int,
    ) {
        val lines =
            buildList {
                add("""{"t":"start","sid":"$sessionId"}""")
                repeat(calls) { i -> add("""{"t":"msg","id":"msg_$i"}""") }
                add("""{"t":"end","ok":true,"cost":0.5}""")
            }
        stdOut.writeText(lines.joinToString("\n"))
    }

    @Test
    fun `107AaI5G inflated modelCalls cannot drive polling past MAX_EXPECTED_CALLS_FOR_POLLING`() =
        runTest {
            writeStreamWithCalls("sess-107", 1_000 + 500)
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } returns ResolvedCost(0.24, CostSource.SESSION)

            run(processId = 7)

            coVerify(exactly = 1) { resolver.resolve("sess-107", 0.5, 1_000, any(), any()) }
        }

    @Test
    fun `modelCalls at exactly MAX_EXPECTED_CALLS_FOR_POLLING pass through uncapped`() =
        runTest {
            writeStreamWithCalls("sess-cap", 1_000)
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } returns ResolvedCost(0.24, CostSource.SESSION)

            run(processId = 7)

            coVerify(exactly = 1) { resolver.resolve("sess-cap", 0.5, 1_000, any(), any()) }
        }

    @Test
    fun `session figure is marked incomplete when modelCalls exceeds cap`() =
        runTest {
            writeStreamWithCalls("sess-big", 1_000 + 500)
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } returns ResolvedCost(0.24, CostSource.SESSION)

            val result = run(processId = 7)

            assertEquals("incomplete: 1500 session calls, poll count capped at 1000", result.llmAccounting?.fallbackReason)
        }

    @Test
    fun `fallback is charged at the cli figure and marked`() =
        runTest {
            stdOut.writeText(validOutput.replace("\"cost\":0.0", "\"cost\":0.5"))
            coEvery { resolver.resolve("s", 0.5, any(), any(), any()) } returns ResolvedCost(0.5, CostSource.CLI, "http 404")

            val result = run(processId = 7)

            coVerify(exactly = 1) { processManager.addCost(7, 0.5, CostSource.CLI) }
            verify { span.setAttribute("llm.cost.fallback_reason", "http 404") }
            assertEquals("http 404", result.llmAccounting?.fallbackReason)
        }

    @Test
    fun `non-zero exit with init event on disk is still priced and charged`() =
        runTest {
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"sess-k\"}\n{\"t\":\"msg\",\"i")
            stubShellExit(137)
            coEvery { resolver.resolve("sess-k", 0.0, any(), any(), any()) } returns ResolvedCost(0.07, CostSource.SESSION)

            val result = executor.execute(claude(), context.copy(processId = 7, agentName = "demo"), span)

            assertEquals(137, result.exitCode)
            coVerify(exactly = 1) { processManager.addCost(7, 0.07, CostSource.SESSION) }
            coVerify(exactly = 1) { costMeter.recordCost("demo", 0.07) }
            assertEquals("sess-k", result.llmAccounting?.sessionId)
        }

    @Test
    fun `non-zero exit whose stdout still has a result event charges the cli figure when unresolved`() =
        runTest {
            stdOut.writeText(validOutput.replace("\"cost\":0.0", "\"cost\":0.9"))
            stubShellExit(1)

            executor.execute(claude(), context.copy(processId = 7), span)

            coVerify(exactly = 1) { processManager.addCost(7, 0.9, CostSource.CLI) }
        }

    @Test
    fun `empty stdout on failure charges nothing and records the reason`() =
        runTest {
            stdOut.writeText("")
            stubShellExit(1)
            coEvery { resolver.resolve(null, 0.0, any(), any(), any()) } returns
                ResolvedCost(0.0, CostSource.CLI, "no session id in output")

            val result = executor.execute(claude(), context.copy(processId = 7), span)

            coVerify(exactly = 0) { processManager.addCost(any(), any(), any()) }
            assertEquals(LlmAccounting(null, 0.0, CostSource.CLI, "no session id in output"), result.llmAccounting)
        }

    @Test
    fun `every retry attempt is priced and charged`() =
        runTest {
            coEvery { context.shell.cmd(any(), any(), any(), any(), any(), any(), any()) } returnsMany
                listOf(TestFixtures.mockCommandResult(1, stdOut, stdErr), TestFixtures.mockCommandResult(0, stdOut, stdErr))
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } answers { ResolvedCost(0.11, CostSource.SESSION) }

            executor.execute(
                claude(retry = LlmRetry(attempts = 2, baseDelayMs = 0, maxDelayMs = 0)),
                context.copy(processId = 7, agentName = "demo"),
                span,
            )

            coVerify(exactly = 2) { processManager.addCost(7, 0.11, CostSource.SESSION) }
        }

    @Test
    fun `no result event yields the no-result-event reason without charging`() =
        runTest {
            stdOut.writeText("""{"t":"start","sid":"s-only"}""")
            stubShellExit(1)
            coEvery { resolver.resolve("s-only", 0.0, any(), any(), any()) } returns ResolvedCost(0.0, CostSource.CLI)

            val result = executor.execute(claude(), context.copy(processId = 7, agentName = "demo"), span)

            assertEquals(LlmAccounting("s-only", 0.0, CostSource.CLI, "no result event"), result.llmAccounting)
            coVerify(exactly = 0) { processManager.addCost(any(), any(), any()) }
            coVerify(exactly = 0) { costMeter.recordCost(any(), any()) }
        }

    @Test
    fun `shell timeout still accounts and charges the attempt before the exception propagates`() =
        runTest {
            stdOut.writeText("""{"t":"start","sid":"sess-to"}""")
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } throws CancellationException("Timeout reached")
            coEvery { resolver.resolve("sess-to", 0.0, any(), any(), any()) } returns ResolvedCost(0.09, CostSource.SESSION)

            assertThrows<CancellationException> {
                executor.execute(claude(), context.copy(processId = 7, agentName = "demo"), span)
            }

            coVerify(exactly = 1) { processManager.addCost(7, 0.09, CostSource.SESSION) }
        }

    @Test
    fun `output cap trip still accounts and charges the attempt before the exception propagates`() =
        runTest {
            stdOut.writeText("""{"t":"start","sid":"sess-cap"}""")
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } throws OutputCapExceededException("Output cap exceeded: 100 bytes.")
            coEvery { resolver.resolve("sess-cap", 0.0, any(), any(), any()) } returns ResolvedCost(0.05, CostSource.SESSION)

            assertThrows<OutputCapExceededException> {
                executor.execute(claude(), context.copy(processId = 7, agentName = "demo"), span)
            }

            coVerify(exactly = 1) { processManager.addCost(7, 0.05, CostSource.SESSION) }
        }

    @Test
    fun `genuine cancellation before the shell throws skips the remote lookup but still accounts the attempt`() =
        runTest {
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"sess-killed\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.3}")
            every { resolver.configured } returns true
            // A child Job we hold and cancel ourselves, standing in for the run's own Job
            // being cancelled by a parent (process kill, server shutdown) mid-attempt.
            lateinit var runJob: Job
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                runJob.cancel()
                throw CancellationException("killed")
            }

            val caught = CompletableDeferred<Throwable>()
            // Plain (non-UNDISPATCHED) launch: runJob is assigned before the body runs.
            runJob =
                launch {
                    try {
                        executor.execute(claude(), context.copy(processId = 7, agentName = "demo"), span)
                    } catch (e: Throwable) {
                        caught.complete(e)
                    }
                }
            runJob.join()
            assertTrue(caught.await() is CancellationException)

            coVerify(exactly = 0) { resolver.resolve(any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { processManager.addCost(7, 0.3, CostSource.CLI) }
        }

    @Test
    fun `a DB failure while accounting a killed attempt does not mask the shell's exception`() =
        runTest {
            stdOut.writeText("""{"t":"start","sid":"sess-cap"}""")
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } throws OutputCapExceededException("Output cap exceeded: 100 bytes.")
            coEvery { resolver.resolve("sess-cap", 0.0, any(), any(), any()) } returns ResolvedCost(0.05, CostSource.SESSION)
            coEvery { processManager.addCost(any(), any(), any()) } throws IllegalStateException("db down")

            assertThrows<OutputCapExceededException> {
                executor.execute(claude(), context.copy(processId = 7, agentName = "demo"), span)
            }
        }

    @Test
    fun `a settlement cancelled while the resolver polls still charges the attempt once, from the CLI figure`() =
        runTest {
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"sess-poll\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.3}")
            every { resolver.configured } returns true
            // The scope settling the attempt goes away (shutdown) while the resolver waits out a
            // poll slot: the cancellation escapes resolve(), but the spend is real (ADR-0061, ADR-0089).
            val settling = CoroutineScope(coroutineContext + SupervisorJob())
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } coAnswers {
                settling.cancel()
                throw CancellationException("cancelled while polling session spend")
            }

            val result = run(processId = 7, agentName = "demo", exec = newExecutor(FakeHarness(), settlementScope = settling))
            advanceUntilIdle()

            assertEquals(FALLBACK_REASON_SESSION_FIGURE_PENDING, result.llmAccounting?.fallbackReason)
            coVerify(exactly = 1) { resolver.resolve("sess-poll", 0.3, any(), any(), any()) }
            coVerify(exactly = 1) { processManager.addCost(7, 0.3, CostSource.CLI) }
            coVerify(exactly = 1) { costMeter.recordCost("demo", 0.3) }
        }

    @Test
    fun `a shell abort still hands the accounted attempt to telemetry before rethrowing`() =
        runTest {
            // The normal hand-off never runs when the shell throws; without this the killed
            // step would get no generation at all while the ledger charged it.
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"sess-cap\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.05}")
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } throws OutputCapExceededException("Output cap exceeded: 100 bytes.")
            coEvery { resolver.resolve("sess-cap", 0.05, any(), any(), any()) } returns ResolvedCost(0.05, CostSource.CLI)

            assertThrows<OutputCapExceededException> {
                executor.execute(claude(), context.copy(processId = 7, agentName = "demo"), span)
            }

            coVerify(exactly = 1) { telemetry.process(any(), any(), any(), any(), any(), 0.05) }
        }

    @Test
    fun `an abort-path stdout flood is bounded and does not reach the telemetry sink as events`() =
        runTest {
            // CWE-400 denial path: a CLI that floods stdout must not inflate heap while a
            // genuinely cancelled attempt is being torn down — the abort hand-off's parse
            // stops at the event cap instead of materializing the whole artifact.
            stdOut.writeText((1..55_000).joinToString("") { "not-json\n" })
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } throws OutputCapExceededException("Output cap exceeded: 100 bytes.")
            coEvery { resolver.resolve(null, 0.0, any(), any(), any()) } returns ResolvedCost(0.0, CostSource.NONE)

            assertThrows<OutputCapExceededException> {
                executor.execute(claude(), context.copy(processId = 7, agentName = "demo"), span)
            }

            // The flood is not parsed into events: the stream the sink receives carries none,
            // and every flooded line is counted as skipped — a lenient primitive (the shape a
            // truncated object lexes as) is dropped by the shared parser, never silently.
            coVerify(exactly = 1) {
                telemetry.process(
                    match { stream -> stream.events.isEmpty() && stream.malformed == 55_000L },
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun `an abort-path parseable flood stops at the event cap`() =
        runTest {
            // Denial path on the kept side: more parseable events than the cap arrive, and
            // the parse must stop there — the sink never sees the excess.
            stdOut.writeText((1..55_000).joinToString("") { """{"t":"msg","id":"m"}""" + "\n" })
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } throws OutputCapExceededException("Output cap exceeded: 100 bytes.")
            coEvery { resolver.resolve(null, 0.0, any(), any(), any()) } returns ResolvedCost(0.0, CostSource.NONE)

            assertThrows<OutputCapExceededException> {
                executor.execute(claude(), context.copy(processId = 7, agentName = "demo"), span)
            }

            coVerify(exactly = 1) {
                telemetry.process(
                    match { stream -> stream.events.size == 50_000 && stream.malformed == 0L },
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun `an abort-path flood past the byte cap stops reading`() =
        runTest {
            // Denial path on the read side: the event cap counts what is kept, so a flood of
            // long unparseable lines must be stopped by the byte bound instead of being read
            // line by line past it. The cut is visible as skipped lines only up to the cap.
            val longLine = "x".repeat(1_000) + "\n"
            stdOut.writeText(longLine.repeat(40_000)) // ~40 MB, far past ABORT_PARSE_MAX_BYTES
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } throws OutputCapExceededException("Output cap exceeded: 100 bytes.")
            coEvery { resolver.resolve(null, 0.0, any(), any(), any()) } returns ResolvedCost(0.0, CostSource.NONE)

            assertThrows<OutputCapExceededException> {
                executor.execute(claude(), context.copy(processId = 7, agentName = "demo"), span)
            }

            coVerify(exactly = 1) {
                telemetry.process(
                    match { stream -> stream.events.isEmpty() && stream.malformed in 1..32_000_000L },
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun `an abort-path flood past the byte cap reports the cut on the span`() =
        runTest {
            // §12 rules 4-5: a bound's overflow is reportable, not silent. The byte bound
            // sheds long lines; the hand-off that looked complete must be marked as truncated
            // (denial_of_service / CWE-400 — the bound itself is covered above).
            val longLine = "x".repeat(1_000) + "\n"
            stdOut.writeText(longLine.repeat(40_000)) // ~40 MB, far past ABORT_PARSE_MAX_BYTES
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } throws OutputCapExceededException("Output cap exceeded: 100 bytes.")
            coEvery { resolver.resolve(null, 0.0, any(), any(), any()) } returns ResolvedCost(0.0, CostSource.NONE)

            assertThrows<OutputCapExceededException> {
                executor.execute(claude(), context.copy(processId = 7, agentName = "demo"), span)
            }

            verify { span.setAttribute("telemetry.llm.handoff_truncated", true) }
        }

    @Test
    fun `an abort-path flood past the event cap reports the truncation on the span`() =
        runTest {
            // Same reporting duty for the event cap: a parseable flood past ABORT_PARSE_MAX_EVENTS
            // sheds the events after it, and the span must not present the hand-off as complete.
            stdOut.writeText((1..55_000).joinToString("") { """{"t":"msg","id":"m"}""" + "\n" })
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } throws OutputCapExceededException("Output cap exceeded: 100 bytes.")
            coEvery { resolver.resolve(null, 0.0, any(), any(), any()) } returns ResolvedCost(0.0, CostSource.NONE)

            assertThrows<OutputCapExceededException> {
                executor.execute(claude(), context.copy(processId = 7, agentName = "demo"), span)
            }

            verify { span.setAttribute("telemetry.llm.handoff_truncated", true) }
        }

    @Test
    fun `an abort-path stream under both caps sets no truncation marker`() =
        runTest {
            // The marker means "spans were shed" — a small stream must never raise it.
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"sess-cap\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.05}")
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } throws OutputCapExceededException("Output cap exceeded: 100 bytes.")
            coEvery { resolver.resolve("sess-cap", 0.05, any(), any(), any()) } returns ResolvedCost(0.05, CostSource.CLI)

            assertThrows<OutputCapExceededException> {
                executor.execute(claude(), context.copy(processId = 7, agentName = "demo"), span)
            }

            verify(exactly = 0) { span.setAttribute("telemetry.llm.handoff_truncated", any<Boolean>()) }
        }

    @Test
    fun `a non-zero-exit parseable flood is bounded and reports the truncation like the abort path`() =
        runTest {
            // Denial path (denial_of_service / CWE-400): the lenient hand-off for a non-zero
            // exit holds one JsonObject per line in heap; a flood of tiny objects must stop at
            // the same event cap as the abort path, and the shed lines must be visible.
            stdOut.writeText((1..55_000).joinToString("") { """{"t":"msg","id":"m"}""" + "\n" })
            stubShellExit(1)

            assertEquals(1, executor.execute(claude(), context, span).exitCode)

            coVerify(exactly = 1) {
                telemetry.process(
                    match { stream -> stream.events.size == 50_000 },
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
            verify { span.setAttribute("telemetry.llm.handoff_truncated", true) }
        }

    @Test
    fun `a non-zero-exit long-line flood stops at the byte cap and reports the cut like the abort path`() =
        runTest {
            // Denial path on the read side of the non-zero-exit hand-off: the event cap counts
            // what is kept, so a flood of long unparseable lines must be stopped by the byte
            // bound instead of being read line by line past it, and the cut must be visible
            // (§12 rules 4-5) — the same rows the abort path covers above.
            val longLine = "x".repeat(1_000) + "\n"
            stdOut.writeText(longLine.repeat(40_000)) // ~40 MB, far past ABORT_PARSE_MAX_BYTES
            stubShellExit(1)

            assertEquals(1, executor.execute(claude(), context, span).exitCode)

            coVerify(exactly = 1) {
                telemetry.process(
                    match { stream -> stream.events.isEmpty() && stream.malformed in 1..32_000_000L },
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
            verify { span.setAttribute("telemetry.llm.handoff_truncated", true) }
        }

    @Test
    fun `a non-zero-exit stream under both caps sets no truncation marker`() =
        runTest {
            // The marker means "spans were shed" — a small stream on a failing CLI must never
            // raise it, or the failure-path spans would read as truncated every time.
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"s\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.05}")
            stubShellExit(1)

            assertEquals(1, executor.execute(claude(), context, span).exitCode)

            verify(exactly = 0) { span.setAttribute("telemetry.llm.handoff_truncated", any<Boolean>()) }
        }

    @Test
    fun `the telemetry hand-off waits for the settlement and carries the settled figure`() =
        runTest {
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"sess-9\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.42}")
            every { resolver.configured } returns true
            val lookup = CompletableDeferred<ResolvedCost>()
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } coAnswers { lookup.await() }

            // runCurrent, not advanceUntilIdle: only the former drives backgroundScope's coroutines.
            run(processId = 7, agentName = "demo", exec = newExecutor(FakeHarness(), settlementScope = backgroundScope))
            runCurrent()
            coVerify(exactly = 0) { telemetry.process(any(), any(), any(), any(), any(), any()) }

            lookup.complete(ResolvedCost(0.18, CostSource.SESSION))
            runCurrent()

            coVerify(exactly = 1) { telemetry.process(any(), any(), any(), any(), any(), 0.18) }
        }

    @Test
    fun `a settled session figure is charged once and replaces the preview on the process record`() =
        runTest {
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"sess-9\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.42}")
            every { resolver.configured } returns true
            val lookup = CompletableDeferred<ResolvedCost>()
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } coAnswers { lookup.await() }

            val result = run(processId = 7, agentName = "demo", exec = newExecutor(FakeHarness(), settlementScope = backgroundScope))
            lookup.complete(ResolvedCost(0.18, CostSource.SESSION))
            runCurrent()

            coVerify(exactly = 1) { processManager.addCost(7, 0.18, CostSource.SESSION) }
            coVerify(exactly = 1) { costMeter.recordCost("demo", 0.18) }
            coVerify(exactly = 1) {
                processManager.settleAccounting(
                    7,
                    result.llmAccounting!!,
                    LlmAccounting("sess-9", 0.18, CostSource.SESSION, null, usage = LlmUsage()),
                )
            }
        }

    @Test
    fun `a settled figure lands on the llm_cost_settlement child span, not the ended command span`() =
        runTest {
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"sess-9\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.42}")
            every { resolver.configured } returns true
            val lookup = CompletableDeferred<ResolvedCost>()
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } coAnswers { lookup.await() }
            val settlementSpan = TestFixtures.mockSpan()
            every { telemetry.tracer } returns TestFixtures.mockTracer(settlementSpan)

            run(processId = 7, agentName = "demo", exec = newExecutor(FakeHarness(), settlementScope = backgroundScope))
            lookup.complete(ResolvedCost(0.18, CostSource.SESSION))
            runCurrent()

            verify(exactly = 1) { settlementSpan.setAttribute("llm.cost.source", "session") }
            verify(exactly = 1) { settlementSpan.setAttribute("llm.cost_usd", 0.18) }
            // A clean settlement carries no fallback reason on the settlement span, and the ended
            // command span is never written again.
            verify(exactly = 0) { settlementSpan.setAttribute("llm.cost.fallback_reason", any<String>()) }
            verify(exactly = 0) { span.setAttribute("llm.cost.fallback_reason", "") }
        }

    @Test
    fun `a telemetry failure in the background hand-off is recorded on the settlement span`() =
        runTest {
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"sess-9\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.42}")
            every { resolver.configured } returns true
            coEvery { telemetry.process(any(), any(), any(), any(), any(), any()) } throws IllegalStateException("sink down")
            val settlementSpan = TestFixtures.mockSpan()
            every { telemetry.tracer } returns TestFixtures.mockTracer(settlementSpan)

            run(processId = 7, agentName = "demo", exec = newExecutor(FakeHarness(), settlementScope = backgroundScope))
            runCurrent()

            verify(exactly = 1) { settlementSpan.setAttribute("telemetry.error", "sink down") }
            verify(exactly = 1) { settlementSpan.recordException(any()) }
            verify(exactly = 0) { span.setAttribute("telemetry.error", any<String>()) }
        }

    @Test
    fun `a settlement whose record swap throws keeps the attempt on its pending figure`() =
        runTest {
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"sess-9\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.42}")
            every { resolver.configured } returns true
            val lookup = CompletableDeferred<ResolvedCost>()
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } coAnswers { lookup.await() }
            coEvery { processManager.settleAccounting(any(), any(), any()) } throws IllegalStateException("db down")
            val settlementSpan = TestFixtures.mockSpan()
            every { telemetry.tracer } returns TestFixtures.mockTracer(settlementSpan)

            val result = run(processId = 7, agentName = "demo", exec = newExecutor(FakeHarness(), settlementScope = backgroundScope))
            lookup.complete(ResolvedCost(0.18, CostSource.SESSION))
            runCurrent()

            assertEquals(FALLBACK_REASON_SESSION_FIGURE_PENDING, result.llmAccounting?.fallbackReason)
            // The settled figure is charged exactly once even though the swap failed; the attempt
            // and the telemetry hand-off keep the pending figure.
            coVerify(exactly = 1) { processManager.addCost(7, 0.18, CostSource.SESSION) }
            coVerify(exactly = 1) { costMeter.recordCost("demo", 0.18) }
            coVerify(exactly = 1) { telemetry.process(any(), any(), any(), any(), any(), 0.42) }
        }

    @Test
    fun `a settlement hand-off past the concurrency bound sheds to the inline pending-figure hand-off (CWE-770)`() =
        runTest {
            // Denial path (ADR-0047 §12 rule 8): more concurrent hand-offs than the local permit
            // bound must shed to the inline harness figure — never queue a job that keeps the
            // attempt's parsed stream alive on the shared heap (CWE-770/CWE-400).
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"sess-shed\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.42}")
            every { resolver.configured } returns true
            val held = CompletableDeferred<ResolvedCost>()
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } coAnswers { held.await() }

            val exec = newExecutor(FakeHarness(), settlementScope = backgroundScope)
            repeat(SETTLEMENT_HANDOFFS + 4) { run(processId = 7, agentName = "demo", exec = exec) }
            runCurrent()

            // The surplus reported the pending figure inline, on the still-open command span, and
            // is marked shed; the permitted attempts are still waiting on the settlement.
            coVerify(exactly = 4) { telemetry.process(any(), any(), any(), any(), any(), 0.42) }
            verify(exactly = 4) { span.setAttribute("telemetry.llm.handoff_shed", true) }

            held.complete(ResolvedCost(0.18, CostSource.SESSION))
            runCurrent()
            coVerify(exactly = SETTLEMENT_HANDOFFS) { telemetry.process(any(), any(), any(), any(), any(), 0.18) }
        }

    @Test
    fun `a settlement that never answers releases its hand-off at the wait bound (CWE-400)`() =
        runTest {
            // Denial path: a stalled resolver must not pin the attempt's stream for longer than
            // the hand-off's own wait bound, whatever the resolver's poll schedule does.
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"sess-stall\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.42}")
            every { resolver.configured } returns true
            val held = CompletableDeferred<ResolvedCost>()
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } coAnswers { held.await() }

            // A foreground scope: only the test scheduler's own work is advanced, so the hand-off's
            // wait bound is what ends it, not the test's teardown.
            val settling = CoroutineScope(coroutineContext + SupervisorJob())
            val settlementSpan = TestFixtures.mockSpan()
            every { telemetry.tracer } returns TestFixtures.mockTracer(settlementSpan)
            val exec = newExecutor(FakeHarness(), settlementScope = settling)
            run(processId = 7, agentName = "demo", exec = exec)
            runCurrent()
            coVerify(exactly = 0) { telemetry.process(any(), any(), any(), any(), any(), any()) }

            advanceUntilIdle()

            // The hand-off gave up on the settlement, reported the pending figure instead, and marked
            // the shed on the child span — the command span has ended, so writing it there would drop
            // the mark (OpenTelemetry drops attribute writes after `end()`).
            coVerify(exactly = 1) { telemetry.process(any(), any(), any(), any(), any(), 0.42) }
            verify(exactly = 1) { settlementSpan.setAttribute("telemetry.llm.handoff_shed", true) }
            verify(exactly = 0) { span.setAttribute("telemetry.llm.handoff_shed", true) }
            settling.cancel()
        }

    @Test
    fun `a failed or cancelled settlement frees its permit, so the next hand-off is not shed (CWE-770)`() =
        runTest {
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"sess-free\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.42}")
            every { resolver.configured } returns true
            // A foreground scope, so the test scheduler runs each settlement and its hand-off.
            val settling = CoroutineScope(coroutineContext + SupervisorJob())
            val settlementSpan = TestFixtures.mockSpan()
            every { telemetry.tracer } returns TestFixtures.mockTracer(settlementSpan)
            val exec = newExecutor(FakeHarness(), settlementScope = settling)

            // A lookup that blows up, then one cancelled mid-poll: each hand-off must end and give
            // its permit back — a permit leaked here would shed later attempts for good.
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } throws IllegalStateException("endpoint down")
            run(processId = 7, agentName = "demo", exec = exec)
            advanceUntilIdle()
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } coAnswers {
                throw CancellationException("cancelled while polling session spend")
            }
            run(processId = 7, agentName = "demo", exec = exec)
            advanceUntilIdle()

            // Both permits are free again: a full batch of held settlements still hands off in the
            // background, so nothing shed.
            val held = CompletableDeferred<ResolvedCost>()
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } coAnswers { held.await() }
            repeat(SETTLEMENT_HANDOFFS) { run(processId = 7, agentName = "demo", exec = exec) }
            runCurrent()

            // A settlement that failed or was cancelled is not a shed: neither the ended command span
            // nor the settlement span is marked.
            verify(exactly = 0) { span.setAttribute("telemetry.llm.handoff_shed", true) }
            verify(exactly = 0) { settlementSpan.setAttribute("telemetry.llm.handoff_shed", true) }
            settling.cancel()
        }

    @Test
    fun `a background hand-off whose telemetry fails frees its permit, so the next hand-off is not shed (CWE-770)`() =
        runTest {
            // Last completion variant of the release invariant: the post-wait emission itself blows
            // up inside the background job. The permit is released on the job's completion, however
            // it ends — a leak here would shed every later hand-off for good.
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"sess-sink\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.42}")
            every { resolver.configured } returns true
            // A foreground scope, so the test scheduler runs the settlement and its hand-off.
            val settling = CoroutineScope(coroutineContext + SupervisorJob())
            val settlementSpan = TestFixtures.mockSpan()
            every { telemetry.tracer } returns TestFixtures.mockTracer(settlementSpan)
            val exec = newExecutor(FakeHarness(), settlementScope = settling)

            coEvery { telemetry.process(any(), any(), any(), any(), any(), any()) } throws IllegalStateException("sink down")
            run(processId = 7, agentName = "demo", exec = exec)
            advanceUntilIdle()
            coEvery { telemetry.process(any(), any(), any(), any(), any(), any()) } just Runs

            // The permit is free again: a full batch of held settlements still hands off in the
            // background, so nothing shed.
            val held = CompletableDeferred<ResolvedCost>()
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } coAnswers { held.await() }
            repeat(SETTLEMENT_HANDOFFS) { run(processId = 7, agentName = "demo", exec = exec) }
            runCurrent()

            // A failed emission is not a shed: neither the ended command span nor the settlement
            // span is marked (the failure is recorded as `telemetry.error` on the settlement span).
            verify(exactly = 0) { span.setAttribute("telemetry.llm.handoff_shed", true) }
            verify(exactly = 0) { settlementSpan.setAttribute("telemetry.llm.handoff_shed", true) }
            settling.cancel()
        }

    @Test
    fun `a hand-off onto a cancelled settlement scope still frees its permit (CWE-770)`() =
        runTest {
            // Denial path: the settlement scope is already cancelled (the shutdown drain), so the
            // job's body never runs. A permit released in a `finally` inside that body would leak
            // here, and eight leaks would shed every later hand-off for good.
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"sess-dead\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.42}")
            every { resolver.configured } returns true
            val dead = CoroutineScope(SupervisorJob())
            dead.cancel()

            val exec = newExecutor(FakeHarness(), settlementScope = dead)
            repeat(SETTLEMENT_HANDOFFS + 1) { run(processId = 7, agentName = "demo", exec = exec) }
            runCurrent()

            // Every attempt took a permit of its own; none had to shed, so none was retained.
            verify(exactly = 0) { span.setAttribute("telemetry.llm.handoff_shed", true) }
        }

    @Test
    fun `a shed hand-off keeps the step on its pending figure and still charges the run (CWE-770)`() =
        runTest {
            // Shedding costs the trace's settled figure, nothing else: the step record keeps its
            // pending figure, the trace reaches the sink inline, and the settlement still charges
            // the run exactly once (ADR-0007, ADR-0035).
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"sess-charge\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.42}")
            every { resolver.configured } returns true
            val held = CompletableDeferred<ResolvedCost>()
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } coAnswers { held.await() }

            val exec = newExecutor(FakeHarness(), settlementScope = backgroundScope)
            val results = (1..SETTLEMENT_HANDOFFS + 1).map { run(processId = 7, agentName = "demo", exec = exec) }
            runCurrent()

            assertEquals(FALLBACK_REASON_SESSION_FIGURE_PENDING, results.last().llmAccounting?.fallbackReason)
            coVerify(exactly = 1) { telemetry.process(any(), any(), any(), any(), any(), 0.42) }
            verify(exactly = 1) { span.setAttribute("telemetry.llm.handoff_shed", true) }

            held.complete(ResolvedCost(0.18, CostSource.SESSION))
            runCurrent()
            coVerify(exactly = SETTLEMENT_HANDOFFS + 1) { processManager.addCost(7, 0.18, CostSource.SESSION) }
        }

    @Test
    fun `a shed hand-off marks the span with a boolean and logs no prompt or output content (CWE-770)`() =
        runTest {
            // Denial path (§12 rule 7): the shed logs counts and marks the span with a boolean, so
            // neither the compiled prompt nor the attempt's parsed output reaches a log line, a span
            // attribute or error text.
            val promptMarker = "PROMPT-SECRET-MARKER"
            val outputMarker = "OUTPUT-SECRET-MARKER"
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"$outputMarker\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.42}")
            every { resolver.configured } returns true
            val held = CompletableDeferred<ResolvedCost>()
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } coAnswers { held.await() }
            val settlementSpan = TestFixtures.mockSpan()
            every { telemetry.tracer } returns TestFixtures.mockTracer(settlementSpan)

            val log = LoggerFactory.getLogger("homer.app.executors.llm.claude") as LogbackLogger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            log.addAppender(appender)

            stubShellExit(0)
            val exec = newExecutor(FakeHarness(), settlementScope = backgroundScope)
            repeat(SETTLEMENT_HANDOFFS + 1) {
                exec.execute(
                    Command.Claude(prompt = LlmPrompt.Prompt(promptMarker), options = ClaudeCmdOptions()),
                    context.copy(processId = 7, agentName = "demo"),
                    span,
                )
            }
            runCurrent()

            // The shed is marked with the boolean `true`; a string "true" would not match this verify.
            verify(exactly = 1) { span.setAttribute("telemetry.llm.handoff_shed", true) }
            assertTrue(appender.list.any { it.formattedMessage.contains("shed at the concurrency bound") })
            assertTrue(appender.list.none { it.formattedMessage.contains(promptMarker) })
            assertTrue(appender.list.none { it.formattedMessage.contains(outputMarker) })

            held.complete(ResolvedCost(0.18, CostSource.SESSION))
            runCurrent()
            log.detachAppender(appender)

            // The prompt reaches only the pre-existing redacted `input.value` on the command span, and
            // never the settlement span the hand-off writes after the command span ended.
            verify(exactly = 0) {
                span.setAttribute(match<String> { it != "input.value" }, match<String> { it.contains(promptMarker) })
            }
            verify(exactly = 0) { settlementSpan.setAttribute(any<String>(), match<String> { it.contains(promptMarker) }) }
        }

    @Test
    fun `a cancellation delivered mid-charge does not let the catch path charge the attempt twice`() =
        runTest {
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"sess-mid\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.4}")
            stubShellExit(0)
            // An unconfigured resolver prices inline; a configured one settles in the background (ADR-0089).
            every { resolver.configured } returns false
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } returns ResolvedCost(0.4, CostSource.CLI)
            // Cancelled between the two charges, while suspended in recordCost: under
            // NonCancellable recordCost still returns, so the cancellation surfaces only at a
            // later suspension point and the executor's catch cannot re-charge the attempt.
            lateinit var runJob: Job
            coEvery { costMeter.recordCost(any(), any()) } coAnswers {
                runJob.cancel()
                delay(20)
            }

            val outcome = CompletableDeferred<Result<CommandResult>>()
            runJob =
                launch {
                    outcome.complete(
                        runCatching { executor.execute(claude(), context.copy(processId = 7, agentName = "demo"), span) },
                    )
                }
            runJob.join()

            assertTrue(runJob.isCancelled)
            coVerify(exactly = 1) { resolver.resolve("sess-mid", 0.4, any(), any(), any()) }
            coVerify(exactly = 1) { processManager.addCost(7, 0.4, CostSource.CLI) }
            coVerify(exactly = 1) { costMeter.recordCost("demo", 0.4) }
            assertEquals(
                0.4,
                outcome
                    .await()
                    .getOrThrow()
                    .llmAccounting
                    ?.costUsd,
            )
        }

    @Test
    fun `a harness with no cost and no resolver hit records source none and charges nothing`() =
        runTest {
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"s\"}\n{\"t\":\"end\",\"ok\":true}\n")
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } answers { ResolvedCost(secondArg(), CostSource.CLI) }
            val result = run(processId = 5, agentName = "demo", exec = newExecutor(FakeHarness(reportsCost = false)))
            assertEquals(CostSource.NONE, result.llmAccounting?.source)
            assertEquals(0.0, result.llmAccounting?.costUsd)
            assertNull(result.llmAccounting?.fallbackReason)
            coVerify(exactly = 0) { processManager.addCost(any(), any(), any()) }
            coVerify(exactly = 0) { costMeter.recordCost(any(), any()) }
            verify { span.setAttribute("llm.cost.source", "none") }
            verify(exactly = 0) { span.setAttribute("llm.cost.parse_failed", any<Boolean>()) }
            // Nothing priced it, so telemetry gets no cost rather than a zero it would show as a free run.
            coVerify { telemetry.process(any(), any(), any(), any(), any(), null) }
        }

    @Test
    fun `a session-priced zero reaches telemetry as a zero`() =
        runTest {
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"sess-0\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.42}")
            coEvery { resolver.resolve("sess-0", 0.42, any(), any(), any()) } returns ResolvedCost(0.0, CostSource.SESSION)

            run(processId = 7, agentName = "demo")

            coVerify { telemetry.process(any(), any(), any(), any(), any(), 0.0) }
        }

    @Test
    fun `a harness with no cost keeps the resolver's own fallback reason under source none`() =
        runTest {
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"s\"}\n{\"t\":\"end\",\"ok\":true}\n")
            coEvery { resolver.resolve(any(), any(), any(), any(), any()) } returns ResolvedCost(0.0, CostSource.CLI, "lookup failed")
            val result = run(processId = 5, exec = newExecutor(FakeHarness(reportsCost = false)))
            assertEquals(CostSource.NONE, result.llmAccounting?.source)
            assertEquals("lookup failed", result.llmAccounting?.fallbackReason)
        }

    @Test
    fun `a harness with no cost and no turn end records source none without the no-result reason`() =
        runTest {
            stdOut.writeText("""{"t":"start","sid":"s"}""")
            stubShellExit(1)
            val result = newExecutor(FakeHarness(reportsCost = false)).execute(claude(), context.copy(processId = 5), span)
            assertEquals(LlmAccounting("s", 0.0, CostSource.NONE, null), result.llmAccounting)
        }

    @Test
    fun `a harness with no cost answered by the session endpoint is charged the session figure`() =
        runTest {
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"s\"}\n{\"t\":\"end\",\"ok\":true}\n")
            coEvery { resolver.resolve("s", 0.0, any(), any(), any()) } returns ResolvedCost(0.2, CostSource.SESSION)
            val result = run(processId = 5, exec = newExecutor(FakeHarness(reportsCost = false)))
            assertEquals(CostSource.SESSION, result.llmAccounting?.source)
            coVerify(exactly = 1) { processManager.addCost(5, 0.2, CostSource.SESSION) }
        }

    @Test
    fun `a harness with no cost cancelled before the lookup records source none with the cancellation reason`() =
        runTest {
            stdOut.writeText("""{"t":"start","sid":"s"}""")
            every { resolver.configured } returns true
            lateinit var runJob: Job
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                runJob.cancel()
                throw CancellationException("killed")
            }
            val noCost = newExecutor(FakeHarness(reportsCost = false))

            val caught = CompletableDeferred<Throwable>()
            runJob =
                launch {
                    try {
                        noCost.execute(claude(), context.copy(processId = 7), span)
                    } catch (e: Throwable) {
                        caught.complete(e)
                    }
                }
            runJob.join()

            assertTrue(caught.await() is CancellationException)
            coVerify(exactly = 0) { resolver.resolve(any(), any(), any(), any(), any()) }
            verify { span.setAttribute("llm.cost.source", "none") }
            verify { span.setAttribute("llm.cost.fallback_reason", "run cancelled before lookup") }
            verify(exactly = 0) { span.setAttribute("llm.cost.source", "cli") }
        }

    @Test
    fun `usage from the winning attempt lands on the accounting record`() =
        runTest {
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"s\"}\n{\"t\":\"end\",\"ok\":true,\"in\":7}\n")
            val result = run(processId = 5)
            assertEquals(7L, result.llmAccounting?.usage?.input)
            verify { span.setAttribute("llm.usage.input", 7L) }
        }

    @Test
    fun `a session id the adapter rejected is passed to the resolver as null`() =
        runTest {
            stdOut.writeText("{\"t\":\"start\",\"sid\":\"../x\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.1}\n")
            coEvery { resolver.resolve(null, 0.1, any(), any(), any()) } returns
                ResolvedCost(0.1, CostSource.CLI, "no session id in output")
            val result = run(processId = 5)
            coVerify { resolver.resolve(null, 0.1, any(), any(), any()) }
            assertEquals("no session id in output", result.llmAccounting?.fallbackReason)
        }

    @Test
    fun `a turn end seen twice charges the attempt once`() =
        runTest {
            stdOut.writeText("{\"t\":\"end\",\"ok\":true,\"cost\":0.4}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.4}\n")
            run(processId = 5)
            coVerify(exactly = 1) { processManager.addCost(5, 0.4, any()) }
        }

    @Test
    fun `malformed lines never fail a zero-exit attempt on their own`() =
        runTest {
            stdOut.writeText("{\"t\":\"junk\"}\n{\"t\":\"end\",\"ok\":true}\n")
            assertEquals(0, run().exitCode)
            coVerify { telemetry.process(match { it.malformed == 1L }, any(), any(), any(), any(), any()) }
        }

    // ========== artifacts ==========

    @Test
    fun `default artifactNameTemplate keeps the same stdout stderr paths across retry attempts`() =
        runTest {
            val capturedPaths = mutableListOf<Pair<Path, Path>>()
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } answers {
                val out = arg<Path>(1)
                val err = arg<Path>(2)
                capturedPaths.add(out to err)
                out.writeText(if (capturedPaths.size < 2) "not-json" else validOutput)
                TestFixtures.mockCommandResult(exitCode = 0, stdOut = out, stdErr = err)
            }

            executor.execute(claude(retry = LlmRetry(attempts = 2, baseDelayMs = 0, maxDelayMs = 0)), context, span)

            assertEquals(2, capturedPaths.size)
            assertEquals(capturedPaths[0], capturedPaths[1], "the default template must render the same path on every attempt")
            assertEquals(stdOut, capturedPaths[0].first)
            assertEquals(stdErr, capturedPaths[0].second)
        }

    private fun perAttemptNames() =
        ArtifactNameResolver(
            template = "cmd_0_a{{${EnvConstants.CMD_ATTEMPT}}}",
            textCompiler = DefaultTextCompiler(),
            artifactsDir = tempDir,
            cmdIndex = 0,
            envProvider = { emptyMap() },
        )

    @Test
    fun `attempt-varying artifactNameTemplate preserves a failed attempt's output and the result points at the winning attempt's file`() =
        runTest {
            val names = perAttemptNames()
            val perAttemptContext = context.copy(artifactNames = names)

            var attempt = 0
            coEvery {
                perAttemptContext.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } answers {
                attempt += 1
                val out = arg<Path>(1)
                val err = arg<Path>(2)
                out.writeText(if (attempt == 1) "garbage not json" else validOutput)
                TestFixtures.mockCommandResult(exitCode = 0, stdOut = out, stdErr = err)
            }

            val result =
                executor.execute(claude(retry = LlmRetry(attempts = 2, baseDelayMs = 0, maxDelayMs = 0)), perAttemptContext, span)

            val attempt0Out = names.stdOut(0)
            val attempt1Out = names.stdOut(1)
            assertTrue(attempt0Out.exists(), "attempt 0's output file must still exist")
            assertEquals("garbage not json", attempt0Out.readText(), "attempt 1 must not have truncated attempt 0's file")
            assertEquals(attempt1Out, result.stdOut, "the returned result must point at the winning attempt's file")
        }

    @Test
    fun `each attempt's artifacts are tracked on the ProcessManager for the console live tail`() =
        runTest {
            val names = perAttemptNames()
            val perAttemptContext = context.copy(processId = 5, cmdIndex = 0, artifactNames = names)

            var attempt = 0
            coEvery {
                perAttemptContext.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } answers {
                attempt += 1
                val out = arg<Path>(1)
                val err = arg<Path>(2)
                if (attempt == 1) {
                    out.writeText("")
                    TestFixtures.mockCommandResult(exitCode = 1, stdOut = out, stdErr = err)
                } else {
                    out.writeText(validOutput)
                    TestFixtures.mockCommandResult(exitCode = 0, stdOut = out, stdErr = err)
                }
            }

            executor.execute(claude(retry = LlmRetry(attempts = 2, baseDelayMs = 0, maxDelayMs = 0)), perAttemptContext, span)

            for (n in 0..1) {
                coVerify {
                    processManager.setCommandArtifacts(
                        5,
                        0,
                        match {
                            it.stdOut == names.stdOut(n) && it.stdErr == names.stdErr(n) && it.kind == "claude" && it.attemptIndex == n
                        },
                    )
                }
            }
        }

    // ========== real ClaudeHarness integration ==========
    // Version-pin / maxMcpOutputTokens / max_turns_stop coverage, exercised through
    // the generic executor with a real (not fake) harness.

    @Test
    fun `a version mismatch through the real ClaudeHarness is not retried`() =
        runTest {
            stdOut.writeText(
                """
                {"type":"system","subtype":"init","session_id":"s","claude_code_version":"9.9.9"}
                {"type":"result","subtype":"success","session_id":"s","total_cost_usd":0.0,"usage":{"input_tokens":1,"output_tokens":1}}
                """.trimIndent(),
            )
            stubShellExit(0)
            val pinned = newExecutor(ClaudeHarness(requireClaudeCodeVersion = "1.2.3"))

            assertThrows<ClaudeCliVersionMismatchException> {
                pinned.execute(claude(retry = LlmRetry(attempts = 5, baseDelayMs = 0, maxDelayMs = 0)), context, span)
            }
            // The mismatch bypasses retry — the shell is invoked exactly once.
            coVerify(exactly = 1) { context.shell.cmd(any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `maxMcpOutputTokens reaches the process env through the real ClaudeHarness`() =
        runTest {
            stdOut.writeText("""{"type":"result","subtype":"success","total_cost_usd":0.0,"usage":{"input_tokens":1,"output_tokens":1}}""")
            val capturedEnv = slot<Map<String, String>>()
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), env = capture(capturedEnv), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOut, stdErr = stdErr)

            newExecutor(ClaudeHarness()).execute(
                Command.Claude(prompt = LlmPrompt.Prompt("hi"), options = ClaudeCmdOptions(maxMcpOutputTokens = 4096)),
                context,
                span,
            )

            assertEquals("4096", capturedEnv.captured["MAX_MCP_OUTPUT_TOKENS"])
        }

    @Test
    fun `a maxTurns stop through the real ClaudeHarness sets claude_max_turns_stop and returns the configured exit code`() =
        runTest {
            stdOut.writeText("""{"type":"result","subtype":"error_max_turns","session_id":"s","total_cost_usd":1.5}""")
            stubShellExit(0)

            val result =
                newExecutor(ClaudeHarness()).execute(
                    Command.Claude(prompt = LlmPrompt.Prompt("hi"), options = ClaudeCmdOptions(maxTurnsExitCode = 75)),
                    context.copy(processId = 7, agentName = "demo"),
                    span,
                )

            assertEquals(75, result.exitCode)
            verify { span.setAttribute("claude.max_turns_stop", true) }
            // The spend is real and is still charged, same as the fake-harness cap-stop coverage above.
            coVerify(exactly = 1) { processManager.addCost(7, 1.5, CostSource.CLI) }
        }

    // ========== env / span ==========

    @Test
    fun `valid span context is propagated as OTEL_TRACE_PARENT env var`() =
        runTest {
            val capturedEnv = slot<Map<String, String>>()
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), env = capture(capturedEnv), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOut, stdErr = stdErr)

            val traceId = "0af7651916cd43dd8448eb211c80319c"
            val spanId = "b7ad6b7169203331"
            val realSpan = Span.wrap(SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault()))

            executor.execute(claude(), context, realSpan)

            assertEquals("00-$traceId-$spanId-01", capturedEnv.captured["OTEL_TRACE_PARENT"])
        }

    @Test
    fun `invalid span context does not emit OTEL_TRACE_PARENT`() =
        runTest {
            val capturedEnv = slot<Map<String, String>>()
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), env = capture(capturedEnv), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOut, stdErr = stdErr)

            executor.execute(claude(), context, Span.getInvalid())

            assertFalse(capturedEnv.captured.containsKey("OTEL_TRACE_PARENT"))
        }

    @Test
    fun `compiled prompt is passed as stdin`() =
        runTest {
            val capturedStdIn = slot<String>()
            coEvery {
                context.shell.cmd(any(), any(), any(), stdIn = capture(capturedStdIn), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOut, stdErr = stdErr)
            coEvery { langfuse.compilePrompt("hello", any()) } returns "hello compiled"

            executor.execute(Command.Claude(prompt = LlmPrompt.Prompt("hello"), options = ClaudeCmdOptions()), context, span)

            assertEquals("hello compiled", capturedStdIn.captured)
            coVerify { telemetry.process(any(), "hello compiled", any(), any(), any(), any()) }
        }

    @Test
    fun `a Langfuse-managed prompt is fetched and passed as stdin`() =
        runTest {
            val capturedStdIn = slot<String>()
            coEvery {
                context.shell.cmd(any(), any(), any(), stdIn = capture(capturedStdIn), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOut, stdErr = stdErr)
            coEvery { langfuse.getPrompt("my-prompt", any(), 3, "prod") } returns "from langfuse"

            executor.execute(
                Command.Claude(
                    prompt = LlmPrompt.Langfuse(promptName = "my-prompt", version = 3, label = "prod"),
                    options = ClaudeCmdOptions(),
                ),
                context,
                span,
            )

            assertEquals("from langfuse", capturedStdIn.captured)
        }

    @Test
    fun `secret env values are redacted from input value and input argv span attributes`() =
        runTest {
            val secretContext =
                context.copy(
                    workspace =
                        TestFixtures.mockWorkspace(
                            TestFixtures.mockWorkspaceConfig(env = mapOf("TOKEN" to "hunter2"), secretEnvNames = setOf("TOKEN")),
                        ),
                )
            coEvery {
                secretContext.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOut, stdErr = stdErr)

            executor.execute(
                Command.Claude(cmd = "run-hunter2", prompt = LlmPrompt.Prompt("say hunter2 to alice"), options = ClaudeCmdOptions()),
                secretContext,
                span,
            )

            verify { span.setAttribute("input.value", "say *** to alice") }
            verify { span.setAttribute("input.argv", match<String> { !it.contains("hunter2") && it.contains("***") }) }
        }

    @Test
    fun `non-secret env values are left untouched in span attributes`() =
        runTest {
            run()
            verify { span.setAttribute("input.value", "hi") }
            verify { span.setAttribute("input.argv", "[claude, --fake]") }
        }

    @Test
    fun `the step's label and configured model reach the telemetry sink`() =
        runTest {
            stubShellExit(0)

            executor.execute(
                Command.Claude(prompt = LlmPrompt.Prompt("hello"), options = ClaudeCmdOptions(model = "opus"), label = "implement"),
                context,
                span,
            )

            coVerify { telemetry.process(any(), any(), any(), any(), LlmStepInfo(label = "implement", configuredModel = "opus"), any()) }
        }

    // ========== stdout filter (ADR-0063) ==========

    private val thinkingLine =
        """{"type":"system","subtype":"thinking_tokens","estimated_tokens":2,"estimated_tokens_delta":2,"session_id":"s","uuid":"u"}"""

    private val filteringHarness =
        object : FakeHarness() {
            override fun stdOutFilter(command: Command.Claude): LlmStdOutFilter = ClaudeSystemEventFilter(setOf("thinking_tokens"))
        }

    /** Stubs the shell to run the given lines through the filter it receives, as a real runner would. */
    private fun stubShellToWriteThroughFilter(lines: List<String>) {
        val filterSlot = slot<OutputLineFilter?>()
        coEvery {
            context.shell.cmd(any(), any(), any(), any(), any(), any(), stdOutFilter = captureNullable(filterSlot))
        } answers {
            val filter = filterSlot.captured
            stdOut.writeText(lines.filter { filter == null || filter.keep(it) }.joinToString("\n", postfix = "\n"))
            TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOut, stdErr = stdErr)
        }
    }

    @Test
    fun `configured system subtypes are dropped from stdout and summarised on stderr`() =
        runTest {
            val (start, msg, end) = validOutput.lines()
            stubShellToWriteThroughFilter(listOf(start, thinkingLine, msg, thinkingLine, thinkingLine, end))

            val outcome = newExecutor(filteringHarness).execute(claude(), context, span)

            assertEquals(0, outcome.exitCode)
            assertEquals(listOf(start, msg, end).joinToString("\n", postfix = "\n"), stdOut.readText())
            val expectedBytes = 3L * (thinkingLine.toByteArray(Charsets.UTF_8).size + 1)
            assertContains(stdErr.readText(), "[HOMER] Dropped 3 system/thinking_tokens events ($expectedBytes bytes) from stdout")
            verify { span.setAttribute("claude.dropped_system_events", 3L) }
        }

    @Test
    fun `no summary line and no span attribute when nothing was dropped`() =
        runTest {
            stubShellToWriteThroughFilter(validOutput.lines())

            newExecutor(filteringHarness).execute(claude(), context, span)

            assertFalse(stdErr.readText().contains("[HOMER] Dropped"))
            verify(exactly = 0) { span.setAttribute("claude.dropped_system_events", any<Long>()) }
        }

    @Test
    fun `an empty subtype set passes no filter to the shell`() =
        runTest {
            val filterSlot = slot<OutputLineFilter?>()
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), any(), any(), stdOutFilter = captureNullable(filterSlot))
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = stdOut, stdErr = stdErr)

            executor.execute(claude(), context, span)

            assertEquals(null, filterSlot.captured)
        }

    @Test
    fun `the summary is still written when the shell throws`() =
        runTest {
            val filterSlot = slot<OutputLineFilter?>()
            coEvery {
                context.shell.cmd(any(), any(), any(), any(), any(), any(), stdOutFilter = captureNullable(filterSlot))
            } answers {
                filterSlot.captured!!.keep(thinkingLine)
                stdOut.writeText("")
                throw OutputCapExceededException("Output cap exceeded: 10 bytes.")
            }

            assertThrows<OutputCapExceededException> { newExecutor(filteringHarness).execute(claude(), context, span) }
            assertContains(stdErr.readText(), "[HOMER] Dropped 1 system/thinking_tokens events")
        }

    // ========== escalate (ADR-0079) ==========

    private data class Call(
        val argv: List<String>,
        val env: Map<String, String>,
    )

    private val okEnd = """{"t":"end","ok":true,"cost":0.0}"""

    private fun tier(
        model: String,
        vararg expressions: String,
        options: List<String>? = null,
    ) = LlmEscalation(model = model, options = options, whenClause = When(When.Operator.ANY, expressions.toList()))

    private fun claudeWithTiers(
        vararg tiers: LlmEscalation,
        retry: LlmRetry? = null,
    ) = Command.Claude(
        prompt = LlmPrompt.Prompt("hi"),
        options = ClaudeCmdOptions(model = "cheap"),
        retry = retry,
        escalate = tiers.toList(),
    )

    /**
     * LLM calls take the next (stdout, exit) from [llm]; escalate predicates (`bash -c`) exit with
     * the next of [predicateExits]. Both are recorded with the env they saw.
     */
    private fun scriptShell(
        llm: List<Pair<String, Int>>,
        predicateExits: List<Int> = emptyList(),
        llmCalls: MutableList<Call> = mutableListOf(),
        predicateCalls: MutableList<Call> = mutableListOf(),
    ) {
        var nextLlm = 0
        var nextPredicate = 0
        coEvery {
            context.shell.cmd(any(), any(), any(), any(), any(), any(), any())
        } answers {
            val argv = firstArg<List<String>>()
            val out = arg<Path>(1)
            val err = arg<Path>(2)
            val env = arg<Map<String, String>>(4)
            if (argv.firstOrNull() == "bash") {
                predicateCalls += Call(argv, env)
                TestFixtures.mockCommandResult(exitCode = predicateExits[nextPredicate++], stdOut = out, stdErr = err)
            } else {
                llmCalls += Call(argv, env)
                val (text, exit) = llm[nextLlm++]
                out.writeText(text)
                TestFixtures.mockCommandResult(exitCode = exit, stdOut = out, stdErr = err)
            }
        }
    }

    @Test
    fun `a step without escalate runs once and evaluates no predicate`() =
        runTest {
            val llmCalls = mutableListOf<Call>()
            val predicateCalls = mutableListOf<Call>()
            scriptShell(llm = listOf(okEnd to 1), llmCalls = llmCalls, predicateCalls = predicateCalls)

            val result = executor.execute(claude(), context, span)

            assertEquals(1, result.exitCode)
            assertEquals(1, llmCalls.size)
            assertTrue(predicateCalls.isEmpty())
            verify(exactly = 0) { span.setAttribute("llm.escalation.tier", any<Long>()) }
        }

    @Test
    fun `a failed tier escalates to the next model when its predicate is true`() =
        runTest {
            val llmCalls = mutableListOf<Call>()
            val predicateCalls = mutableListOf<Call>()
            scriptShell(
                llm = listOf(okEnd to 1, validOutput to 0),
                predicateExits = listOf(0),
                llmCalls = llmCalls,
                predicateCalls = predicateCalls,
            )

            val result = executor.execute(claudeWithTiers(tier("strong", "[ \"\$HMR_LLM_OUTCOME\" != success ]")), context, span)

            assertEquals(0, result.exitCode)
            assertEquals(listOf("claude", "--fake", "cheap"), llmCalls[0].argv)
            assertEquals(listOf("claude", "--fake", "strong"), llmCalls[1].argv)
            assertEquals("failed", predicateCalls.single().env[EnvConstants.LLM_OUTCOME])
            assertEquals("1", predicateCalls.single().env[EnvConstants.LLM_EXIT_CODE])
            assertNull(llmCalls[1].env[EnvConstants.LLM_OUTCOME], "the outcome is predicate-only, never process env")
            verify { span.setAttribute("llm.escalation.tier", 1L) }
        }

    @Test
    fun `a false predicate returns the tier's own result`() =
        runTest {
            val llmCalls = mutableListOf<Call>()
            scriptShell(llm = listOf(okEnd to 3), predicateExits = listOf(1), llmCalls = llmCalls)

            val result = executor.execute(claudeWithTiers(tier("strong", "false")), context, span)

            assertEquals(3, result.exitCode)
            assertEquals(1, llmCalls.size)
        }

    @Test
    fun `a successful tier escalates when the predicate judges its work bad`() =
        runTest {
            val llmCalls = mutableListOf<Call>()
            val predicateCalls = mutableListOf<Call>()
            scriptShell(
                llm = listOf(validOutput to 0, validOutput to 0),
                predicateExits = listOf(0),
                llmCalls = llmCalls,
                predicateCalls = predicateCalls,
            )

            executor.execute(claudeWithTiers(tier("strong", "[ -f hold ]")), context, span)

            assertEquals(2, llmCalls.size)
            assertEquals("success", predicateCalls.single().env[EnvConstants.LLM_OUTCOME])
            assertEquals("0", predicateCalls.single().env[EnvConstants.LLM_EXIT_CODE])
        }

    @Test
    fun `each tier is judged only after the previous one and the last tier stops escalating`() =
        runTest {
            val llmCalls = mutableListOf<Call>()
            val predicateCalls = mutableListOf<Call>()
            scriptShell(
                llm = listOf(okEnd to 1, okEnd to 1, okEnd to 1),
                predicateExits = listOf(0, 0),
                llmCalls = llmCalls,
                predicateCalls = predicateCalls,
            )

            val result = executor.execute(claudeWithTiers(tier("mid", "true"), tier("strong", "true")), context, span)

            assertEquals(1, result.exitCode)
            assertEquals(listOf("cheap", "mid", "strong"), llmCalls.map { it.argv.last() })
            assertEquals(2, predicateCalls.size, "no predicate after the last tier")
        }

    @Test
    fun `a tier stopped at its turn cap escalates with outcome capped and its real exit code`() =
        runTest {
            val llmCalls = mutableListOf<Call>()
            val predicateCalls = mutableListOf<Call>()
            scriptShell(
                llm = listOf("""{"t":"stop"}""" to 0, validOutput to 0),
                predicateExits = listOf(0),
                llmCalls = llmCalls,
                predicateCalls = predicateCalls,
            )

            val result =
                newExecutor(FakeHarness(terminalExit = 75))
                    .execute(claudeWithTiers(tier("strong", "true")), context, span)

            assertEquals(0, result.exitCode)
            assertEquals(2, llmCalls.size)
            assertEquals("capped", predicateCalls.single().env[EnvConstants.LLM_OUTCOME])
            assertEquals("75", predicateCalls.single().env[EnvConstants.LLM_EXIT_CODE])
        }

    @Test
    fun `malformed output escalates when the predicate takes it`() =
        runTest {
            val predicateCalls = mutableListOf<Call>()
            scriptShell(llm = listOf("garbage" to 0, validOutput to 0), predicateExits = listOf(0), predicateCalls = predicateCalls)

            val result = executor.execute(claudeWithTiers(tier("strong", "true")), context, span)

            assertEquals(0, result.exitCode)
            assertEquals("malformed", predicateCalls.single().env[EnvConstants.LLM_OUTCOME])
            assertEquals("-1", predicateCalls.single().env[EnvConstants.LLM_EXIT_CODE])
        }

    @Test
    fun `malformed output no tier takes over is rethrown as before`() =
        runTest {
            scriptShell(llm = listOf("garbage" to 0), predicateExits = listOf(1))

            assertThrows<MalformedLlmOutputException> {
                executor.execute(claudeWithTiers(tier("strong", "false")), context, span)
            }
        }

    @Test
    fun `a predicate eval error fails the step`() =
        runTest {
            scriptShell(llm = listOf(okEnd to 1), predicateExits = listOf(2))

            val e =
                assertThrows<CommandExecutionException> {
                    executor.execute(claudeWithTiers(tier("strong", "broken")), context, span)
                }
            assertContains(e.message ?: "", "escalate[0].when evaluation failed")
        }

    @Test
    fun `every tier is cost-accounted`() =
        runTest {
            scriptShell(
                llm = listOf("""{"t":"end","ok":false,"cost":0.02}""" to 1, """{"t":"end","ok":true,"cost":1.5}""" to 0),
                predicateExits = listOf(0),
            )

            executor.execute(claudeWithTiers(tier("strong", "true")), context.copy(agentName = "demo", processId = 9), span)

            coVerify(exactly = 1) { costMeter.recordCost("demo", 0.02) }
            coVerify(exactly = 1) { costMeter.recordCost("demo", 1.5) }
        }

    @Test
    fun `a tier continues the attempt numbering so its artifacts are its own`() =
        runTest {
            val perAttemptContext = context.copy(artifactNames = perAttemptNames(), processId = 4)
            val llmOut = mutableListOf<Path>()
            coEvery {
                perAttemptContext.shell.cmd(any(), any(), any(), any(), any(), any(), any())
            } answers {
                val argv = firstArg<List<String>>()
                val out = arg<Path>(1)
                if (argv.first() == "bash") {
                    TestFixtures.mockCommandResult(exitCode = 0, stdOut = out, stdErr = arg(2))
                } else {
                    llmOut.add(out)
                    out.writeText(if (llmOut.size <= 2) okEnd else validOutput)
                    TestFixtures.mockCommandResult(exitCode = if (llmOut.size <= 2) 1 else 0, stdOut = out, stdErr = arg(2))
                }
            }

            val result =
                executor.execute(
                    claudeWithTiers(tier("strong", "true"), retry = LlmRetry(attempts = 2, baseDelayMs = 0, maxDelayMs = 0)),
                    perAttemptContext,
                    span,
                )

            assertEquals(3, llmOut.size)
            assertEquals(3, llmOut.toSet().size, "tier 1 must not overwrite tier 0's attempt files")
            assertEquals(llmOut[2], result.stdOut)
        }

    @Test
    fun `escalate is bounded`() {
        val tiers = List(MAX_TIERS + 1) { tier("m$it", "true") }
        assertThrows<IllegalArgumentException> { claudeWithTiers(*tiers.toTypedArray()) }
        assertThrows<IllegalArgumentException> { tier(" ", "true") }
    }

    private companion object {
        const val MAX_TIERS = homer.app.models.MAX_LLM_ESCALATION_TIERS

        /** Mirrors the executor's private `MAX_CONCURRENT_SETTLEMENT_HANDOFFS`, which the shed tests pin. */
        const val SETTLEMENT_HANDOFFS = 8
    }
}
