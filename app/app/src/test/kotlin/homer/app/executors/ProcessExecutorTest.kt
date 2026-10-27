package homer.app.executors

import homer.app.fixtures.TestFixtures
import homer.app.infra.TelemetryService
import homer.app.models.Cooldown
import homer.app.models.ProcessStatus
import homer.app.services.ProcessManager
import homer.app.services.QuestionsService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Unit tests for ProcessExecutor
 * Tests process execution lifecycle, telemetry integration, and error handling
 */
class ProcessExecutorTest {
    private lateinit var processExecutor: ProcessExecutor
    private lateinit var mockProcessManager: ProcessManager
    private lateinit var mockQuestionsService: QuestionsService
    private lateinit var mockRunMessages: RunMessageExpiry
    private lateinit var mockTelemetryService: TelemetryService
    private lateinit var mockLogger: org.slf4j.Logger
    private lateinit var mockSpan: Span
    private lateinit var mockProcess: Process
    private lateinit var testScope: CoroutineScope

    private val testContext = Context.current()

    @BeforeEach
    fun setup() {
        mockProcessManager = mockk(relaxed = true)
        mockQuestionsService = mockk(relaxed = true)
        mockRunMessages = mockk(relaxed = true)
        mockSpan = TestFixtures.mockSpan()
        mockTelemetryService = TestFixtures.mockTelemetryService(mockSpan)
        mockLogger = TestFixtures.mockLogger()
        mockProcess = mockk(relaxed = true)
        testScope = CoroutineScope(Dispatchers.Default + Job())

        // Setup default behavior
        every { mockProcess.name } returns "test-agent"
        // Relaxed mocks answer an Int? property with 0, never null; pin the real default so
        // positional assertions on the obtainProcessId call see a rootless run.
        every { mockProcess.rootProcessId } returns null
        coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { mockProcessManager.setProcessStatus(any(), any()) } just Runs
        coEvery { mockProcessManager.compareAndSetStatus(any(), any(), any()) } returns true
        coEvery { mockProcessManager.assignJob(any(), any()) } just Runs
        coEvery { mockProcessManager.removeJob(any()) } just Runs

        processExecutor =
            ProcessExecutor(
                telemetryService = mockTelemetryService,
                processManager = mockProcessManager,
                questionsService = mockQuestionsService,
                runMessages = mockRunMessages,
                coroutineScope = testScope,
                logger = mockLogger,
            )
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    // ========== Process Creation Tests ==========

    @Test
    fun `execute should obtain process ID from ProcessManager`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 42
            coEvery { mockProcess.execute(any(), any()) } just Runs

            val result = processExecutor.execute(mockProcess, testContext)

            assertEquals(42, result.processId)
            coVerify { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `execute should return ProcessCreationResult with process ID`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 123
            coEvery { mockProcess.execute(any(), any()) } just Runs

            val result = processExecutor.execute(mockProcess, testContext)

            assertEquals(123, result.processId)
        }

    @Test
    fun `execute should log process creation`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } just Runs
            every { mockProcess.name } returns "my-agent"

            processExecutor.execute(mockProcess, testContext)

            verify { mockLogger.info(match { it.contains("Process created") }, 1, "my-agent") }
        }

    @Test
    fun `execute should pass agent name to obtainProcessId`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } just Runs
            every { mockProcess.name } returns "my-custom-agent"

            processExecutor.execute(mockProcess, testContext)

            coVerify { mockProcessManager.obtainProcessId("my-custom-agent", any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `execute should transition through CREATED and FINISHED statuses`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } coAnswers {
                delay(10)
            }

            processExecutor.execute(mockProcess, testContext)
            delay(300)

            // Both statuses should have been set
            coVerify {
                mockProcessManager.compareAndSetStatus(
                    1,
                    setOf(ProcessStatus.CREATED),
                    ProcessStatus.CREATED,
                )
            }
            coVerify {
                mockProcessManager.compareAndSetStatus(
                    1,
                    any(),
                    ProcessStatus.FINISHED,
                )
            }
        }

    @Test
    fun `execute should log successful completion`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 5
            coEvery { mockProcess.execute(any(), any()) } just Runs

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            verify { mockLogger.info(match { it.contains("completed successfully") }, 5) }
        }

    // ========== Failure Handling Tests ==========

    @Test
    fun `execute should set status to FAILED when process execution throws exception`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } throws RuntimeException("Test error")

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            coVerify {
                mockProcessManager.compareAndSetStatus(1, any(), ProcessStatus.FAILED)
            }
        }

    @Test
    fun `execute should log error when process execution fails`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 2
            val exception = RuntimeException("Execution failed")
            coEvery { mockProcess.execute(any(), any()) } throws exception

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            verify {
                mockLogger.error(
                    match { it.contains("failed") },
                    2,
                    "Execution failed",
                    exception,
                )
            }
        }

    @Test
    fun `execute should not throw exception when process fails`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } throws RuntimeException("Test error")

            // Should not throw - exceptions are caught
            val result = processExecutor.execute(mockProcess, testContext)

            assertEquals(1, result.processId)
            delay(150)
        }

    @Test
    fun `execute should pass process owner to obtainProcessId`() =
        runBlocking {
            coEvery {
                mockProcessManager.obtainProcessId(
                    any(),
                    any(),
                    owner = "ci",
                    cooldown = any(),
                    secretInputKeys = any(),
                    parentProcessId = any(),
                    rootProcessId = any(),
                )
            } returns 1
            coEvery { mockProcess.execute(any(), any()) } just Runs
            every { mockProcess.owner } returns "ci"

            processExecutor.execute(mockProcess, testContext)

            coVerify {
                mockProcessManager.obtainProcessId(
                    "test-agent",
                    any(),
                    owner = "ci",
                    cooldown = any(),
                    secretInputKeys = any(),
                    parentProcessId = any(),
                    rootProcessId = any(),
                )
            }
        }

    @Test
    fun `execute hands the agent cooldown to obtainProcessId`() =
        runBlocking {
            // The bound is enforced inside obtainProcessId's transaction, atomic with the
            // INSERT — AgentRunStarter's pre-flight check cannot hold it on its own. If this
            // wiring is dropped the cooldown silently stops binding. See ADR-0051.
            val cooldown = Cooldown(minInterval = "1m")
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } just Runs
            every { mockProcess.cooldown } returns cooldown

            processExecutor.execute(mockProcess, testContext)

            coVerify {
                mockProcessManager.obtainProcessId(
                    any(),
                    any(),
                    any(),
                    cooldown = cooldown,
                    secretInputKeys = any(),
                    parentProcessId = any(),
                    rootProcessId = any(),
                )
            }
        }

    @Test
    fun `execute stores the process parent id with the row`() =
        runBlocking {
            coEvery { mockProcess.execute(any(), any()) } just Runs
            every { mockProcess.parentProcessId } returns 7

            processExecutor.execute(mockProcess, testContext)

            coVerify { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), parentProcessId = 7) }
        }

    // ========== Job Management Tests ==========

    @Test
    fun `execute should assign job to process`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } coAnswers {
                delay(50)
            }

            processExecutor.execute(mockProcess, testContext)

            // Job should be assigned immediately
            coVerify { mockProcessManager.assignJob(1, any()) }
        }

    @Test
    fun `execute should remove job after completion`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } just Runs

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            coVerify { mockProcessManager.removeJob(1) }
        }

    @Test
    fun `execute should remove job even when process fails`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } throws RuntimeException("Test error")

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            coVerify { mockProcessManager.removeJob(1) }
        }

    // ========== Telemetry Tests ==========

    @Test
    fun `execute should create span with agent_execution name`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } just Runs

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            verify(atLeast = 1) { mockTelemetryService.tracer }
        }

    @Test
    fun `execute should set process id attribute on span`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 42
            coEvery { mockProcess.execute(any(), any()) } just Runs

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            verify { mockSpan.setAttribute("process.id", 42L) }
        }

    @Test
    fun `execute should set WORKING status attribute on span`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } just Runs

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            verify { mockSpan.setAttribute("process.status", "CREATED") }
        }

    @Test
    fun `execute should set FINISHED status attribute on span when successful`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } just Runs

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            verify { mockSpan.setAttribute("process.status", "FINISHED") }
        }

    @Test
    fun `execute should set FAILED status attribute on span when process fails`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } throws RuntimeException("Test error")

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            verify { mockSpan.setAttribute("process.status", "FAILED") }
        }

    @Test
    fun `execute should set OK status on span when successful`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } just Runs

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            verify { mockSpan.setStatus(StatusCode.OK) }
        }

    @Test
    fun `execute should set ERROR status on span when process fails`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } throws RuntimeException("Test error")

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            verify {
                mockSpan.setStatus(
                    StatusCode.ERROR,
                    match { it.contains("Agent execution failed") },
                )
            }
        }

    @Test
    fun `execute should record exception on span when process fails`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            val exception = RuntimeException("Test error")
            coEvery { mockProcess.execute(any(), any()) } throws exception

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            verify { mockSpan.recordException(exception) }
        }

    @Test
    fun `execute should end span after successful completion`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } just Runs

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            verify { mockSpan.end() }
        }

    @Test
    fun `execute should end span even when process fails`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } throws RuntimeException("Test error")

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            verify { mockSpan.end() }
        }

    @Test
    fun `execute should call process execute with correct parameters`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 123
            coEvery { mockProcess.execute(any(), any()) } just Runs

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            coVerify { mockProcess.execute(123, mockSpan) }
        }

    // ========== Concurrent Execution Tests ==========

    @Test
    fun `should handle multiple concurrent process executions`() =
        runBlocking {
            var processIdCounter = 0
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } answers { processIdCounter++ }
            coEvery { mockProcess.execute(any(), any()) } coAnswers {
                delay(50)
            }

            // Execute 5 processes concurrently
            val results =
                (1..5).map {
                    processExecutor.execute(mockProcess, testContext)
                }

            // All should have unique IDs
            val ids = results.map { it.processId }
            assertEquals(5, ids.toSet().size)

            // Wait for all to complete
            delay(200)

            // All should have been executed
            coVerify(exactly = 5) { mockProcess.execute(any(), any()) }
        }

    @Test
    fun `should handle concurrent success and failure scenarios`() =
        runBlocking {
            var processIdCounter = 0
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } answers {
                processIdCounter++
            }

            // Create two different processes - one that succeeds, one that fails
            val successProcess = mockk<Process>(relaxed = true)
            val failProcess = mockk<Process>(relaxed = true)
            every { successProcess.name } returns "success-agent"
            every { failProcess.name } returns "fail-agent"

            coEvery { successProcess.execute(any(), any()) } coAnswers { delay(10) }
            coEvery { failProcess.execute(any(), any()) } throws RuntimeException("Test error")

            // Execute both types
            processExecutor.execute(successProcess, testContext)
            processExecutor.execute(failProcess, testContext)

            delay(300)

            // Should have both FINISHED and FAILED statuses
            coVerify(atLeast = 1) {
                mockProcessManager.compareAndSetStatus(
                    any(),
                    any(),
                    ProcessStatus.FINISHED,
                )
            }
            coVerify(atLeast = 1) {
                mockProcessManager.compareAndSetStatus(
                    any(),
                    any(),
                    ProcessStatus.FAILED,
                )
            }
        }

    // ========== Integration Tests ==========

    @Test
    fun `full successful execution lifecycle should work correctly`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } just Runs

            val result = processExecutor.execute(mockProcess, testContext)

            assertEquals(1, result.processId)

            testScope.coroutineContext[Job]!!
                .children
                .toList()
                .forEach { it.join() }

            // assignJob runs on the caller thread while the launched body starts on
            // Dispatchers.Default — the two race with no happens-before, so it cannot
            // be ordered against the guarded CREATED write. Verify it happened, but
            // keep it out of the ordered block (otherwise the test flakes under load).
            coVerify { mockProcessManager.assignJob(1, any()) }

            // Verify complete lifecycle (calls with a real happens-before chain only)
            coVerifyOrder {
                mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any())
                mockLogger.info(any<String>(), 1, any<String>())
                mockProcessManager.compareAndSetStatus(
                    1,
                    setOf(ProcessStatus.CREATED),
                    ProcessStatus.CREATED,
                )
                mockProcess.execute(1, any())
                mockProcessManager.compareAndSetStatus(
                    1,
                    any(),
                    ProcessStatus.FINISHED,
                )
                mockLogger.info(any<String>(), 1)
                mockSpan.end()
                mockProcessManager.removeJob(1)
            }
        }

    @Test
    fun `full failed execution lifecycle should work correctly`() =
        runBlocking {
            val exception = RuntimeException("Execution failed")
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 2
            coEvery { mockProcess.execute(any(), any()) } throws exception

            val result = processExecutor.execute(mockProcess, testContext)

            assertEquals(2, result.processId)

            testScope.coroutineContext[Job]!!
                .children
                .toList()
                .forEach { it.join() }

            // assignJob runs on the caller thread while the launched body starts on
            // Dispatchers.Default — the two race with no happens-before, so it cannot
            // be ordered against the guarded CREATED write. Verify it happened, but
            // keep it out of the ordered block (otherwise the test flakes under load).
            coVerify { mockProcessManager.assignJob(2, any()) }

            // Verify complete failure lifecycle (calls with a real happens-before chain only)
            coVerifyOrder {
                mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any())
                mockLogger.info(any<String>(), 2, any<String>())
                mockProcessManager.compareAndSetStatus(
                    2,
                    setOf(ProcessStatus.CREATED),
                    ProcessStatus.CREATED,
                )
                mockProcess.execute(2, any())
                mockProcessManager.compareAndSetStatus(2, any(), ProcessStatus.FAILED)
                mockSpan.recordException(exception)
                mockLogger.error(any<String>(), 2, any<String>(), exception)
                mockSpan.end()
                mockProcessManager.removeJob(2)
            }
        }

    // ========== Question Expiry Tests ==========

    @Test
    fun `execute should expire questions after successful completion`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } just Runs

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            coVerifyOrder {
                mockQuestionsService.expireForProcess(1)
                mockRunMessages.closeForProcess(1)
            }
            coVerify(exactly = 1) { mockRunMessages.closeForProcess(1) }
        }

    @Test
    fun `execute should expire questions when process fails`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 2
            coEvery { mockProcess.execute(any(), any()) } throws RuntimeException("boom")

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            coVerifyOrder {
                mockQuestionsService.expireForProcess(2)
                mockRunMessages.closeForProcess(2)
            }
            coVerify(exactly = 1) { mockRunMessages.closeForProcess(2) }
        }

    @Test
    fun `execute should expire questions when process is killed`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 3
            coEvery { mockProcess.execute(any(), any()) } coAnswers {
                delay(50)
                throw kotlinx.coroutines.CancellationException("Killed !")
            }
            coEvery { mockProcessManager.getProcessStatus(3) } returns ProcessStatus.KILLED

            processExecutor.execute(mockProcess, testContext)
            delay(200)

            coVerifyOrder {
                mockQuestionsService.expireForProcess(3)
                mockRunMessages.closeForProcess(3)
            }
            coVerify(exactly = 1) { mockRunMessages.closeForProcess(3) }
        }

    @Test
    fun `execute should still release the job when closing the inbox fails`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 4
            coEvery { mockProcess.execute(any(), any()) } just Runs
            coEvery { mockRunMessages.closeForProcess(4) } throws RuntimeException("db down")

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            coVerify { mockProcessManager.removeJob(4) }
        }

    // ========== purgeAt Tests ==========

    @Test
    fun `execute should set purgeAt on ProcessManager after successful completion`() =
        runBlocking {
            val fiveDaysInSeconds = 5 * 86400L
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } just Runs
            every { mockProcess.purgeAfterSeconds } returns fiveDaysInSeconds
            every { mockProcess.purgeRecordAfterSeconds } returns fiveDaysInSeconds
            // Pins the retainArtifacts passthrough: mockProcess is relaxed, so this field
            // defaults to emptyList() and a match{} on the 4th setPurgeAt arg (or omitting it)
            // would pass even if schedulePurge forwarded a typo'd constant or the wrong field.
            every { mockProcess.retainArtifacts } returns listOf("decision.json")

            val beforeCall = System.currentTimeMillis() / 1000

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            val afterCall = System.currentTimeMillis() / 1000

            coVerify {
                mockProcessManager.setPurgeAt(
                    1,
                    match { purgeAt ->
                        purgeAt >= beforeCall + fiveDaysInSeconds && purgeAt <= afterCall + fiveDaysInSeconds
                    },
                    match { recordPurgeAt ->
                        recordPurgeAt >= beforeCall + fiveDaysInSeconds && recordPurgeAt <= afterCall + fiveDaysInSeconds
                    },
                    listOf("decision.json"),
                )
            }
        }

    @Test
    fun `execute schedules the record purge on its own, longer window`() =
        runBlocking {
            val fiveDaysInSeconds = 5 * 86400L
            val thirtyDaysInSeconds = 30L * 24 * 60 * 60
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } just Runs
            every { mockProcess.purgeAfterSeconds } returns fiveDaysInSeconds
            every { mockProcess.purgeRecordAfterSeconds } returns thirtyDaysInSeconds

            val beforeCall = System.currentTimeMillis() / 1000

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            val afterCall = System.currentTimeMillis() / 1000

            // The directory goes at 5d; the record — the thing a link in a ticket
            // resolves against — stays for 30.
            coVerify {
                mockProcessManager.setPurgeAt(
                    1,
                    match { purgeAt ->
                        purgeAt >= beforeCall + fiveDaysInSeconds && purgeAt <= afterCall + fiveDaysInSeconds
                    },
                    match { recordPurgeAt ->
                        recordPurgeAt >= beforeCall + thirtyDaysInSeconds && recordPurgeAt <= afterCall + thirtyDaysInSeconds
                    },
                )
            }
        }

    // ========== KILLED Status Preservation Tests ==========

    @Test
    fun `execute should preserve KILLED status when process is killed via API`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1

            // Simulate process being killed while running
            coEvery { mockProcess.execute(any(), any()) } coAnswers {
                delay(50)
                throw kotlinx.coroutines.CancellationException("Killed !")
            }

            // When getProcessStatus is called, return KILLED (status was set by killProcess)
            coEvery { mockProcessManager.getProcessStatus(1) } returns ProcessStatus.KILLED

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            // Should NOT set status to FAILED
            coVerify(exactly = 0) {
                mockProcessManager.setProcessStatus(1, ProcessStatus.FAILED)
            }

            // Telemetry should show KILLED
            verify { mockSpan.setAttribute("process.status", "KILLED") }
        }

    @Test
    fun `execute should set FAILED status when process fails normally`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } throws RuntimeException("Normal error")

            // Status is not KILLED
            coEvery { mockProcessManager.getProcessStatus(1) } returns ProcessStatus.WORKING

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            // Should set status to FAILED
            coVerify {
                mockProcessManager.compareAndSetStatus(1, any(), ProcessStatus.FAILED)
            }

            // Telemetry should show FAILED
            verify { mockSpan.setAttribute("process.status", "FAILED") }
        }

    @Test
    fun `execute should preserve KILLED status from lock manager kill`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1

            // Simulate lock manager killing the process
            coEvery { mockProcess.execute(any(), any()) } coAnswers {
                delay(50)
                throw kotlinx.coroutines.CancellationException("The new process 2 takes precedence")
            }

            // When getProcessStatus is called, return KILLED (status was set by lock manager)
            coEvery { mockProcessManager.getProcessStatus(1) } returns ProcessStatus.KILLED

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            // Should NOT overwrite KILLED status
            coVerify(exactly = 0) {
                mockProcessManager.setProcessStatus(1, ProcessStatus.FAILED)
            }

            // Telemetry should show KILLED
            verify { mockSpan.setAttribute("process.status", "KILLED") }
        }

    @Test
    fun `execute should preserve KILLED when kill races with successful completion (audit #9)`() =
        runBlocking {
            // Happy path: process.execute() returns normally. Concurrently, killProcess set
            // KILLED. The FINISHED write must be guarded so KILLED is preserved.
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } just Runs
            // Simulate concurrent kill: by the time CAS runs, status is already KILLED.
            coEvery {
                mockProcessManager.compareAndSetStatus(
                    1,
                    any(),
                    ProcessStatus.FINISHED,
                )
            } returns false
            coEvery { mockProcessManager.getProcessStatus(1) } returns ProcessStatus.KILLED

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            // Must not bypass the guard with a plain setProcessStatus(FINISHED).
            coVerify(exactly = 0) {
                mockProcessManager.setProcessStatus(1, ProcessStatus.FINISHED)
            }
            // Telemetry reflects the actual final status.
            verify { mockSpan.setAttribute("process.status", "KILLED") }
        }

    @Test
    fun `execute should preserve KILLED when kill races with the failure path`() =
        runBlocking {
            // Failure path: process.execute() throws while a concurrent killProcess
            // moves the record to KILLED. The FAILED write must be guarded the same
            // way as the happy-path FINISHED write so KILLED is never overwritten.
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } throws RuntimeException("boom")

            var status = ProcessStatus.WORKING
            coEvery { mockProcessManager.getProcessStatus(1) } coAnswers { status }
            // The kill wins the mutex against the FAILED write attempt: the CAS
            // observes KILLED and refuses the transition.
            coEvery {
                mockProcessManager.compareAndSetStatus(1, any(), ProcessStatus.FAILED)
            } coAnswers {
                status = ProcessStatus.KILLED
                false
            }

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            // Must not bypass the guard with a plain setProcessStatus(FAILED).
            coVerify(exactly = 0) {
                mockProcessManager.setProcessStatus(1, ProcessStatus.FAILED)
            }
            // Telemetry reflects the actual final status.
            verify { mockSpan.setAttribute("process.status", "KILLED") }
        }

    @Test
    fun `execute should preserve a KILLED that landed before the job's first status write`() =
        runBlocking {
            // A caller can kill a run in the instant between obtainProcessId and the job's
            // first status write (a failed /callback/joins kills the sub it just started).
            // The job's CREATED write must not overwrite that KILLED: unguarded, it reset the
            // record to CREATED and the cancellation then ended the run FAILED.
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            var status = ProcessStatus.KILLED
            coEvery { mockProcessManager.getProcessStatus(1) } coAnswers { status }
            coEvery { mockProcessManager.setProcessStatus(1, any()) } coAnswers { status = secondArg() }
            coEvery { mockProcessManager.compareAndSetStatus(1, any(), any()) } coAnswers {
                if (status in secondArg<Set<ProcessStatus>>()) {
                    status = thirdArg()
                    true
                } else {
                    false
                }
            }
            coEvery { mockProcess.execute(any(), any()) } throws CancellationException("killed")

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            assertEquals(ProcessStatus.KILLED, status)
        }

    @Test
    fun `execute should set FAILED for lock collision with consistent telemetry`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            val exception = homer.app.exceptions.AnotherProcessStillActive(999)
            coEvery { mockProcess.execute(any(), any()) } throws exception

            // Status is not KILLED (process never ran)
            coEvery { mockProcessManager.getProcessStatus(1) } returns ProcessStatus.CREATED

            processExecutor.execute(mockProcess, testContext)
            delay(150)

            // Should set status to FAILED
            coVerify {
                mockProcessManager.compareAndSetStatus(1, any(), ProcessStatus.FAILED)
            }

            // Telemetry should ALSO be FAILED (fixes inconsistency)
            verify { mockSpan.setAttribute("process.status", "FAILED") }
        }

    // ========== onTerminal Callback Tests (ADR-0023) ==========

    private fun executorWithOnTerminal(onTerminal: suspend (Int, ProcessStatus) -> Unit) =
        ProcessExecutor(
            telemetryService = mockTelemetryService,
            processManager = mockProcessManager,
            questionsService = mockQuestionsService,
            runMessages = mockRunMessages,
            coroutineScope = testScope,
            logger = mockLogger,
            onTerminal = onTerminal,
        )

    @Test
    fun `execute should invoke onTerminal with the committed final status after a successful run`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } just Runs
            coEvery { mockProcessManager.getProcessStatus(1) } returns ProcessStatus.FINISHED

            val seen = slot<Pair<Int, ProcessStatus>>()
            executorWithOnTerminal { id, status -> seen.captured = id to status }
                .execute(mockProcess, testContext)
            delay(150)

            assertEquals(1 to ProcessStatus.FINISHED, seen.captured)
        }

    @Test
    fun `execute should still end span and remove job when onTerminal throws`() =
        runBlocking {
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } just Runs
            coEvery { mockProcessManager.getProcessStatus(1) } returns ProcessStatus.FINISHED

            executorWithOnTerminal { _, _ -> throw RuntimeException("hook boom") }
                .execute(mockProcess, testContext)
            delay(150)

            // Teardown must complete despite the hook failure.
            verify { mockSpan.end() }
            coVerify { mockProcessManager.removeJob(1) }
            // The committed terminal status is untouched: FINISHED was the only status write,
            // and no FAILED/KILLED override slipped in from the hook failure.
            coVerify {
                mockProcessManager.compareAndSetStatus(1, any(), ProcessStatus.FINISHED)
            }
            coVerify(exactly = 0) {
                mockProcessManager.setProcessStatus(1, ProcessStatus.FAILED)
            }
            verify { mockLogger.error(match { it.contains("Continuation hook") }, 1, "hook boom", any<Throwable>()) }
        }

    @Test
    fun `execute should pass KILLED to onTerminal when a kill wins the CAS`() =
        runBlocking {
            // Happy path returns, but a concurrent kill has already committed KILLED, so the
            // FINISHED CAS fails. The hook must observe the actual final status, KILLED.
            coEvery { mockProcessManager.obtainProcessId(any(), any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { mockProcess.execute(any(), any()) } just Runs
            coEvery {
                mockProcessManager.compareAndSetStatus(
                    1,
                    any(),
                    ProcessStatus.FINISHED,
                )
            } returns false
            coEvery { mockProcessManager.getProcessStatus(1) } returns ProcessStatus.KILLED

            val seen = slot<Pair<Int, ProcessStatus>>()
            executorWithOnTerminal { id, status -> seen.captured = id to status }
                .execute(mockProcess, testContext)
            delay(150)

            assertEquals(1 to ProcessStatus.KILLED, seen.captured)
        }
}
