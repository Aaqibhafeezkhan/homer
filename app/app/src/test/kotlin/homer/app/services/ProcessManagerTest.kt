package homer.app.services

import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.TestFixtures
import homer.app.infra.ProcessJobRegistry
import homer.app.models.CostSource
import homer.app.models.ExecutionInfo
import homer.app.models.FALLBACK_REASON_SESSION_FIGURE_PENDING
import homer.app.models.LlmAccounting
import homer.app.models.PodPhase
import homer.app.models.ProcessStatus
import homer.app.models.RunnerState
import homer.app.services.persistence.ProcessRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Unit tests for ProcessManager
 * Tests process state management, ID generation, and process lifecycle
 */
class ProcessManagerTest {
    private lateinit var processManager: ProcessManager
    private lateinit var mockLogger: org.slf4j.Logger

    /**
     * Builds a ProcessManager over a fresh, isolated SQLite database so each
     * test starts from an empty store. [mockLogger] is shared so the kill-path
     * logging assertions still see this test's logger.
     */
    private fun newManager(
        maxProcesses: Int = ProcessManager.DEFAULT_MAX_PROCESSES,
        onProcessEvicted: suspend (Int) -> Unit = {},
        workspaceRoot: Path = Path.of("."),
    ): ProcessManager {
        val registry = ProcessJobRegistry()
        val repository = ProcessRepository(PersistenceTestSupport.tempDatabase(), registry)
        return ProcessManager(
            repository = repository,
            jobRegistry = registry,
            logger = mockLogger,
            maxProcesses = maxProcesses,
            workspaceRoot = workspaceRoot,
            onProcessEvicted = onProcessEvicted,
        )
    }

    @BeforeEach
    fun setup() {
        mockLogger = TestFixtures.mockLogger()
        processManager = newManager()
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    // ========== Process ID Generation Tests ==========

    @Test
    fun `obtainProcessId should return unique IDs`() =
        runTest {
            val id1 = processManager.obtainProcessId("test-agent")
            val id2 = processManager.obtainProcessId("test-agent")
            val id3 = processManager.obtainProcessId("test-agent")

            // SQLite AUTOINCREMENT ids start at 1 and never reuse.
            assertEquals(1, id1)
            assertEquals(2, id2)
            assertEquals(3, id3)
        }

    @Test
    fun `obtainProcessId should start from 1`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")
            assertEquals(1, id)
        }

    @Test
    fun `obtainProcessId should create process with CREATED status`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")
            val status = processManager.getProcessStatus(id)
            assertEquals(ProcessStatus.CREATED, status)
        }

    @Test
    fun `obtainProcessId should update lastProcessId`() =
        runTest {
            assertEquals(-1, processManager.lastProcessId())

            processManager.obtainProcessId("test-agent")
            assertEquals(1, processManager.lastProcessId())

            processManager.obtainProcessId("test-agent")
            assertEquals(2, processManager.lastProcessId())
        }

    @Test
    fun `obtainProcessId should evict oldest terminal process when at capacity`() =
        runTest {
            val capped = newManager(maxProcesses = 3)
            val id0 = capped.obtainProcessId("a")
            val id1 = capped.obtainProcessId("a")
            val id2 = capped.obtainProcessId("a")
            capped.setProcessStatus(id0, ProcessStatus.FINISHED)
            capped.setProcessStatus(id1, ProcessStatus.FAILED)

            capped.obtainProcessId("a")

            assertEquals(ProcessStatus.UNKNOWN, capped.getProcessStatus(id0))
            assertEquals(ProcessStatus.FAILED, capped.getProcessStatus(id1))
            assertEquals(ProcessStatus.CREATED, capped.getProcessStatus(id2))
        }

    @Test
    fun `obtainProcessId notifies onProcessEvicted for the evicted process`() =
        runTest {
            val evicted = mutableListOf<Int>()
            val capped = newManager(maxProcesses = 2, onProcessEvicted = { evicted += it })
            val id0 = capped.obtainProcessId("a")
            capped.obtainProcessId("a")
            capped.setProcessStatus(id0, ProcessStatus.FINISHED)

            capped.obtainProcessId("a")

            assertEquals(listOf(id0), evicted)
        }

    @Test
    fun `obtainProcessId deletes the evicted process workspace directory`(
        @TempDir tmp: Path,
    ) = runTest {
        val capped = newManager(maxProcesses = 1, workspaceRoot = tmp)
        val id0 = capped.obtainProcessId("a")
        val artifacts = tmp.resolve("run0/artifacts")
        Files.createDirectories(artifacts)
        Files.writeString(artifacts.resolve("out.txt"), "x")
        capped.setArtifactsDir(id0, artifacts)
        capped.setProcessStatus(id0, ProcessStatus.FINISHED)

        // At capacity → obtaining a new id evicts id0; its workspace must be deleted.
        capped.obtainProcessId("a")

        assertFalse(
            Files.exists(tmp.resolve("run0")),
            "evicted process workspace directory must be deleted, not orphaned",
        )
    }

    @Test
    fun `obtainProcessId keeps an evicted workspace still referenced by another process`(
        @TempDir tmp: Path,
    ) = runTest {
        val capped = newManager(maxProcesses = 2, workspaceRoot = tmp)
        val id0 = capped.obtainProcessId("a")
        val id1 = capped.obtainProcessId("a")
        val shared = tmp.resolve("shared/artifacts")
        Files.createDirectories(shared)
        capped.setArtifactsDir(id0, shared)
        capped.setArtifactsDir(id1, shared) // id1 (still active) references the same dir
        capped.setProcessStatus(id0, ProcessStatus.FINISHED)

        // Evicts id0 (oldest terminal); the shared dir must survive for id1.
        capped.obtainProcessId("a")

        assertTrue(
            Files.exists(tmp.resolve("shared")),
            "a workspace still referenced by another process must not be deleted on eviction",
        )
    }

    @Test
    fun `obtainProcessId refuses to delete an evicted workspace outside the workspace root`(
        @TempDir tmp: Path,
    ) = runTest {
        val root = tmp.resolve("root")
        Files.createDirectories(root)
        // A poisoned/crafted artifactsDir pointing outside the configured root.
        val outside = tmp.resolve("outside/artifacts")
        Files.createDirectories(outside)
        Files.writeString(outside.resolve("keep.txt"), "x")

        val capped = newManager(maxProcesses = 1, workspaceRoot = root)
        val id0 = capped.obtainProcessId("a")
        capped.setArtifactsDir(id0, outside)
        capped.setProcessStatus(id0, ProcessStatus.FINISHED)

        capped.obtainProcessId("a") // evicts id0

        // The containment guard refuses the delete; the out-of-root dir survives.
        assertTrue(
            Files.exists(tmp.resolve("outside")),
            "eviction must not delete a workspace that resolves outside the workspace root",
        )
    }

    @Test
    fun `obtainProcessId should throw ProcessManagerAtCapacityException when no terminal processes exist`() =
        runTest {
            val capped = newManager(maxProcesses = 2)
            val id0 = capped.obtainProcessId("a")
            val id1 = capped.obtainProcessId("a")

            val ex =
                kotlin.runCatching { capped.obtainProcessId("a") }.exceptionOrNull()

            assertTrue(ex is homer.app.exceptions.ProcessManagerAtCapacityException)
            assertEquals(2, ex.maxProcesses)
            // Existing processes remain untouched; refused id was never allocated.
            assertEquals(ProcessStatus.CREATED, capped.getProcessStatus(id0))
            assertEquals(ProcessStatus.CREATED, capped.getProcessStatus(id1))
            assertEquals(id1, capped.lastProcessId())
        }

    // ========== Process Status Tests ==========

    @Test
    fun `getProcessStatus should return CREATED for new process`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")
            val status = processManager.getProcessStatus(id)
            assertEquals(ProcessStatus.CREATED, status)
        }

    @Test
    fun `getProcessStatus should return UNKNOWN for non-existent process`() =
        runTest {
            val status = processManager.getProcessStatus(999)
            assertEquals(ProcessStatus.UNKNOWN, status)
        }

    @Test
    fun `setProcessStatus should update process status`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")

            processManager.setProcessStatus(id, ProcessStatus.WORKING)
            assertEquals(ProcessStatus.WORKING, processManager.getProcessStatus(id))

            processManager.setProcessStatus(id, ProcessStatus.FINISHED)
            assertEquals(ProcessStatus.FINISHED, processManager.getProcessStatus(id))
        }

    @Test
    fun `setProcessStatus should handle all status transitions`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")

            processManager.setProcessStatus(id, ProcessStatus.WORKING)
            assertEquals(ProcessStatus.WORKING, processManager.getProcessStatus(id))

            processManager.setProcessStatus(id, ProcessStatus.FINISHED)
            assertEquals(ProcessStatus.FINISHED, processManager.getProcessStatus(id))

            val id2 = processManager.obtainProcessId("test-agent")
            processManager.setProcessStatus(id2, ProcessStatus.WORKING)
            processManager.setProcessStatus(id2, ProcessStatus.FAILED)
            assertEquals(ProcessStatus.FAILED, processManager.getProcessStatus(id2))
        }

    // ========== Compare-And-Set Status Tests (audit #9) ==========

    @Test
    fun `compareAndSetStatus should update when current status is in expected set`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")
            processManager.setProcessStatus(id, ProcessStatus.WORKING)

            val updated =
                processManager.compareAndSetStatus(
                    id,
                    setOf(ProcessStatus.WORKING),
                    ProcessStatus.FINISHED,
                )

            assertTrue(updated)
            assertEquals(ProcessStatus.FINISHED, processManager.getProcessStatus(id))
        }

    @Test
    fun `compareAndSetStatus should refuse to overwrite KILLED with FINISHED`() =
        runTest {
            // Simulates the audit #9 race: DefaultLockManager set KILLED while ProcessExecutor
            // is about to write FINISHED. The terminal KILLED status must win.
            val id = processManager.obtainProcessId("test-agent")
            processManager.setProcessStatus(id, ProcessStatus.WORKING)
            processManager.setProcessStatus(id, ProcessStatus.KILLED)

            val updated =
                processManager.compareAndSetStatus(
                    id,
                    setOf(ProcessStatus.WORKING, ProcessStatus.CREATED),
                    ProcessStatus.FINISHED,
                )

            assertEquals(false, updated)
            assertEquals(ProcessStatus.KILLED, processManager.getProcessStatus(id))
        }

    @Test
    fun `compareAndSetStatus should return false for unknown processId without throwing`() =
        runTest {
            val updated =
                processManager.compareAndSetStatus(
                    999,
                    setOf(ProcessStatus.WORKING),
                    ProcessStatus.FINISHED,
                )

            assertEquals(false, updated)
        }

    @Test
    fun `compareAndSetStatus should append to history only when transition succeeds`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")
            processManager.setProcessStatus(id, ProcessStatus.WORKING)
            val historyBefore = processManager.getProcessHistory(id)

            // Fails: current is WORKING, not FINISHED
            processManager.compareAndSetStatus(
                id,
                setOf(ProcessStatus.FINISHED),
                ProcessStatus.FAILED,
            )
            assertEquals(historyBefore, processManager.getProcessHistory(id))

            // Succeeds: WORKING → FINISHED
            processManager.compareAndSetStatus(
                id,
                setOf(ProcessStatus.WORKING),
                ProcessStatus.FINISHED,
            )
            val historyAfter = processManager.getProcessHistory(id)
            assertEquals(historyBefore.size + 1, historyAfter.size)
            assertEquals(ProcessStatus.FINISHED, historyAfter.last().second)
        }

    // ========== Job Management Tests ==========

    @Test
    fun `assignJob should assign job to process`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")
            val job: Job =
                mockk(relaxed = true) {
                    every { isActive } returns true
                }

            processManager.assignJob(id, job)

            // Job should be assigned - no exception thrown
            assertTrue(true)
        }

    @Test
    fun `removeJob should remove job from process`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")
            val job: Job = mockk(relaxed = true)

            processManager.assignJob(id, job)
            processManager.removeJob(id)

            // Should complete normally - no exception thrown
            assertTrue(true)
        }

    @Test
    fun `hasLiveJob is true only while an active job is registered`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")
            assertFalse(processManager.hasLiveJob(id))

            val job = Job()
            processManager.assignJob(id, job)
            assertTrue(processManager.hasLiveJob(id))

            job.cancel()
            assertFalse(processManager.hasLiveJob(id))

            processManager.assignJob(id, Job())
            processManager.removeJob(id)
            assertFalse(processManager.hasLiveJob(id))
        }

    // ========== Process Killing Tests ==========

    @Test
    fun `killProcess should cancel job and change status to KILLED`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")
            val job: Job =
                mockk(relaxed = true) {
                    every { cancel(any()) } just Runs
                }

            processManager.assignJob(id, job)
            processManager.setProcessStatus(id, ProcessStatus.WORKING)

            processManager.killProcess(id)

            assertEquals(ProcessStatus.KILLED, processManager.getProcessStatus(id))
            verify { job.cancel(any()) }
        }

    @Test
    fun `killProcess should kill process in CREATED status`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")
            val job: Job =
                mockk(relaxed = true) {
                    every { cancel(any()) } just Runs
                }

            processManager.assignJob(id, job)
            // Process is in CREATED status (default from obtainProcessId)

            processManager.killProcess(id)

            assertEquals(ProcessStatus.KILLED, processManager.getProcessStatus(id))
            verify { job.cancel(any()) }
        }

    @Test
    fun `killProcess should not affect non-WORKING process`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")
            processManager.setProcessStatus(id, ProcessStatus.FINISHED)

            processManager.killProcess(id)

            // Status should remain FINISHED
            assertEquals(ProcessStatus.FINISHED, processManager.getProcessStatus(id))
        }

    @Test
    fun `killProcess should log when killing a process`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")
            val job: Job = mockk(relaxed = true)

            processManager.assignJob(id, job)
            processManager.setProcessStatus(id, ProcessStatus.WORKING)

            processManager.killProcess(id)

            verify { mockLogger.info(match<String> { it.contains("Killing") }, id, any<String>()) }
        }

    @Test
    fun `killProcess should have no effect on non-existent process`() =
        runTest {
            // Should not throw
            processManager.killProcess(999)
            assertEquals(ProcessStatus.UNKNOWN, processManager.getProcessStatus(999))
        }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `killProcess should append a KILLED history entry with a fresh timestamp`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")
            val job: Job = mockk(relaxed = true)
            processManager.assignJob(id, job)
            processManager.setProcessStatus(id, ProcessStatus.WORKING)

            val before = Clock.System.now().epochSeconds
            processManager.killProcess(id)

            val history = processManager.getProcessHistory(id)
            assertEquals(ProcessStatus.KILLED, history.last().second)
            assertTrue(history.last().first >= before)
        }

    @Test
    fun `killProcess should not append to history when process is already terminal`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")
            processManager.setProcessStatus(id, ProcessStatus.FINISHED)
            val historyBefore = processManager.getProcessHistory(id)

            processManager.killProcess(id)

            assertEquals(historyBefore, processManager.getProcessHistory(id))
        }

    // ========== Start Time Tests ==========

    @Test
    fun `startTime should be set on initialization`() {
        assertNotNull(processManager.startTime)
        assertTrue(processManager.startTime.toLong() > 0)
    }

    @Test
    fun `startTime should remain constant`() {
        val time1 = processManager.startTime
        val time2 = processManager.startTime
        assertEquals(time1, time2)
    }

    // ========== Concurrency Tests ==========

    @Test
    fun `should handle concurrent obtainProcessId calls`() =
        runTest {
            val ids = mutableListOf<Int>()

            // Create 10 processes concurrently
            coroutineScope {
                repeat(10) {
                    launch {
                        val id = processManager.obtainProcessId("test-agent")
                        synchronized(ids) {
                            ids.add(id)
                        }
                    }
                }
            }

            // All IDs should be unique
            assertEquals(10, ids.size)
            assertEquals(10, ids.toSet().size)

            // IDs should be the sequential AUTOINCREMENT range 1..10
            assertEquals((1..10).toSet(), ids.toSet())
        }

    @Test
    fun `should handle concurrent status updates`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")

            coroutineScope {
                // Multiple concurrent status updates
                launch { processManager.setProcessStatus(id, ProcessStatus.WORKING) }
                launch { processManager.setProcessStatus(id, ProcessStatus.WORKING) }
                launch { processManager.setProcessStatus(id, ProcessStatus.WORKING) }
            }

            // Should not crash and should have some final status
            val finalStatus = processManager.getProcessStatus(id)
            assertTrue(finalStatus == ProcessStatus.WORKING)
        }

    @Test
    fun `should handle concurrent killProcess calls`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")
            val job: Job = mockk(relaxed = true)

            processManager.assignJob(id, job)
            processManager.setProcessStatus(id, ProcessStatus.WORKING)

            // Multiple concurrent kill calls
            coroutineScope {
                launch { processManager.killProcess(id) }
                launch { processManager.killProcess(id) }
                launch { processManager.killProcess(id) }
            }

            // Should end up KILLED
            assertEquals(ProcessStatus.KILLED, processManager.getProcessStatus(id))
        }

    // ========== Integration Tests ==========

    @Test
    fun `full process lifecycle should work correctly`() =
        runTest {
            // Obtain ID
            val id = processManager.obtainProcessId("test-agent")
            assertEquals(ProcessStatus.CREATED, processManager.getProcessStatus(id))

            // Assign job
            val job: Job = mockk(relaxed = true)
            processManager.assignJob(id, job)

            // Start working
            processManager.setProcessStatus(id, ProcessStatus.WORKING)
            assertEquals(ProcessStatus.WORKING, processManager.getProcessStatus(id))

            // Mark as finished
            processManager.setProcessStatus(id, ProcessStatus.FINISHED)
            assertEquals(ProcessStatus.FINISHED, processManager.getProcessStatus(id))

            // Remove job
            processManager.removeJob(id)
        }

    @Test
    fun `multiple concurrent processes should work correctly`() =
        runTest {
            val processCount = 5
            val processIds = mutableListOf<Int>()
            val jobs = mutableListOf<Job>()

            // Create multiple processes
            repeat(processCount) {
                val id = processManager.obtainProcessId("test-agent")
                processIds.add(id)

                val job: Job = mockk()
                jobs.add(job)

                processManager.assignJob(id, job)
                processManager.setProcessStatus(id, ProcessStatus.WORKING)
            }

            // All should be working
            processIds.forEach { id ->
                assertEquals(ProcessStatus.WORKING, processManager.getProcessStatus(id))
            }

            // Mark all as finished
            processIds.forEach { id ->
                processManager.setProcessStatus(id, ProcessStatus.FINISHED)
                processManager.removeJob(id)
            }

            // All should be finished
            processIds.forEach { id ->
                assertEquals(ProcessStatus.FINISHED, processManager.getProcessStatus(id))
            }
        }

    // ========== History Tracking Tests ==========

    @Test
    fun `history should be empty for new process`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")
            val history = processManager.getProcessHistory(id)
            assertEquals(0, history.size)
        }

    @Test
    fun `setProcessStatus should append to history with timestamp`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")

            processManager.setProcessStatus(id, ProcessStatus.WORKING)
            processManager.setProcessStatus(id, ProcessStatus.FINISHED)

            val history = processManager.getProcessHistory(id)
            assertEquals(2, history.size)
            assertEquals(ProcessStatus.WORKING, history[0].second)
            assertEquals(ProcessStatus.FINISHED, history[1].second)
            assertTrue(history[0].first > 0) // Valid timestamp
            assertTrue(history[1].first >= history[0].first) // Monotonic
        }

    @Test
    fun `history should contain all status transitions in order`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")

            processManager.setProcessStatus(id, ProcessStatus.WORKING)
            processManager.setProcessStatus(id, ProcessStatus.FINISHED)

            val history = processManager.getProcessHistory(id)

            assertEquals(2, history.size)
            assertEquals(ProcessStatus.WORKING, history[0].second)
            assertEquals(ProcessStatus.FINISHED, history[1].second)
        }

    @Test
    fun `history timestamps should be monotonically increasing`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")

            processManager.setProcessStatus(id, ProcessStatus.WORKING)
            processManager.setProcessStatus(id, ProcessStatus.FINISHED)

            val history = processManager.getProcessHistory(id)

            assertTrue(history[1].first >= history[0].first)
        }

    @Test
    fun `concurrent status updates should maintain complete history`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")
            val statusCount = 10

            coroutineScope {
                repeat(statusCount) {
                    launch {
                        processManager.setProcessStatus(id, ProcessStatus.WORKING)
                    }
                }
            }

            val history = processManager.getProcessHistory(id)
            assertEquals(statusCount, history.size)
            // All should be WORKING status
            assertTrue(history.all { it.second == ProcessStatus.WORKING })
        }

    @Test
    fun `getProcessHistory should return empty list for non-existent process`() =
        runTest {
            val history = processManager.getProcessHistory(999)
            assertEquals(0, history.size)
        }

    // ========== Execution Tracking Tests ==========

    @Test
    fun `executions should be empty for new process`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")
            val executions = processManager.getProcessExecutions(id)
            assertEquals(0, executions.size)
        }

    @Test
    fun `completeStep should append execution info`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")

            val executionInfo =
                ExecutionInfo(
                    label = "cmd",
                    start = 1000L,
                    end = 2000L,
                    resultCode = 0,
                    stdOut = Paths.get("/tmp/out.txt"),
                    stdErr = Paths.get("/tmp/err.txt"),
                    errMsg = null,
                )

            processManager.completeStep(id, executionInfo, null, null)

            val executions = processManager.getProcessExecutions(id)
            assertEquals(1, executions.size)
            assertEquals(executionInfo, executions[0])
        }

    @Test
    fun `settleAccounting replaces a recorded step's pending accounting`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")
            val pending = LlmAccounting("sess-1", 0.42, CostSource.CLI, FALLBACK_REASON_SESSION_FIGURE_PENDING)
            val settled = LlmAccounting("sess-1", 0.18, CostSource.SESSION)
            processManager.completeStep(id, step("llm", pending), null, null)
            processManager.completeStep(id, step("other", null), null, null)

            processManager.settleAccounting(id, pending, settled)

            assertEquals(listOf(settled, null), processManager.getProcessExecutions(id).map { it.llmAccounting })
        }

    @Test
    fun `settleAccounting that lands before the step is recorded is merged when it is`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")
            val pending = LlmAccounting("sess-1", 0.42, CostSource.CLI, FALLBACK_REASON_SESSION_FIGURE_PENDING)
            val settled = LlmAccounting("sess-1", 0.18, CostSource.SESSION)

            processManager.settleAccounting(id, pending, settled)
            processManager.completeStep(id, step("llm", pending), null, null)

            assertEquals(listOf(settled), processManager.getProcessExecutions(id).map { it.llmAccounting })
        }

    @Test
    fun `a settlement for a removed process is dropped, not buffered`() =
        runTest {
            // ADR-0089: removeProcess already ran the only unappliedSettlements removal that
            // would ever clear it, so buffering here would leak the entry for the server's uptime.
            val id = processManager.obtainProcessId("test-agent")
            val pending = LlmAccounting("sess-1", 0.42, CostSource.CLI, FALLBACK_REASON_SESSION_FIGURE_PENDING)
            val settled = LlmAccounting("sess-1", 0.18, CostSource.SESSION)
            processManager.removeProcess(id)

            processManager.settleAccounting(id, pending, settled)

            assertEquals(emptyList<LlmAccounting?>(), processManager.getProcessExecutions(id).map { it.llmAccounting })
        }

    private fun step(
        label: String,
        llmAccounting: LlmAccounting?,
    ) = ExecutionInfo(
        label = label,
        start = 1000L,
        end = 2000L,
        resultCode = 0,
        stdOut = Paths.get("/tmp/out.txt"),
        stdErr = Paths.get("/tmp/err.txt"),
        errMsg = null,
        llmAccounting = llmAccounting,
    )

    @Test
    fun `completeStep should handle multiple commands`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")

            val exec1 =
                ExecutionInfo(
                    label = "cmd",
                    start = 1000L,
                    end = 2000L,
                    resultCode = 0,
                    stdOut = Paths.get("/tmp/out1.txt"),
                    stdErr = Paths.get("/tmp/err1.txt"),
                    errMsg = null,
                )
            val exec2 =
                ExecutionInfo(
                    label = "cmd",
                    start = 2000L,
                    end = 3000L,
                    resultCode = 0,
                    stdOut = Paths.get("/tmp/out2.txt"),
                    stdErr = Paths.get("/tmp/err2.txt"),
                    errMsg = null,
                )

            processManager.completeStep(id, exec1, null, null)
            processManager.completeStep(id, exec2, null, null)

            val executions = processManager.getProcessExecutions(id)
            assertEquals(2, executions.size)
            assertEquals(exec1, executions[0])
            assertEquals(exec2, executions[1])
        }

    @Test
    fun `concurrent completeStep calls should maintain all executions`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")
            val executionCount = 10

            coroutineScope {
                repeat(executionCount) { index ->
                    launch {
                        val executionInfo =
                            ExecutionInfo(
                                label = "cmd",
                                start = 1000L + index,
                                end = 2000L + index,
                                resultCode = 0,
                                stdOut = Paths.get("/tmp/out_$index.txt"),
                                stdErr = Paths.get("/tmp/err_$index.txt"),
                                errMsg = null,
                            )
                        processManager.completeStep(id, executionInfo, null, null)
                    }
                }
            }

            val executions = processManager.getProcessExecutions(id)
            assertEquals(executionCount, executions.size)
        }

    @Test
    fun `completeStep should store error message when provided`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")

            val executionInfo =
                ExecutionInfo(
                    label = "cmd",
                    start = 1000L,
                    end = 2000L,
                    resultCode = 1,
                    stdOut = Paths.get("/tmp/out.txt"),
                    stdErr = Paths.get("/tmp/err.txt"),
                    errMsg = "Command failed with exit code 1",
                )

            processManager.completeStep(id, executionInfo, null, null)

            val executions = processManager.getProcessExecutions(id)
            assertEquals(1, executions.size)
            assertEquals("Command failed with exit code 1", executions[0].errMsg)
            assertEquals(1, executions[0].resultCode)
        }

    @Test
    fun `getProcessExecutions should return empty list for non-existent process`() =
        runTest {
            val executions = processManager.getProcessExecutions(999)
            assertEquals(0, executions.size)
        }

    @Test
    fun `completeStep should handle non-zero exit codes`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")

            val executionInfo =
                ExecutionInfo(
                    label = "cmd",
                    start = 1000L,
                    end = 2000L,
                    resultCode = 127,
                    stdOut = Paths.get("/tmp/out.txt"),
                    stdErr = Paths.get("/tmp/err.txt"),
                    errMsg = "Command not found",
                )

            processManager.completeStep(id, executionInfo, null, null)

            val executions = processManager.getProcessExecutions(id)
            assertEquals(1, executions.size)
            assertEquals(127, executions[0].resultCode)
        }

    // ========== Integration Tests for History and Executions ==========

    @Test
    fun `full process lifecycle should populate both history and executions`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")

            // Status transitions
            processManager.setProcessStatus(id, ProcessStatus.WORKING)
            processManager.setProcessStatus(id, ProcessStatus.FINISHED)

            // Add some executions
            processManager.completeStep(
                id,
                ExecutionInfo(
                    label = "cmd",
                    start = 1000L,
                    end = 2000L,
                    resultCode = 0,
                    stdOut = Paths.get("/tmp/out1.txt"),
                    stdErr = Paths.get("/tmp/err1.txt"),
                    errMsg = null,
                ),
                null,
                null,
            )
            processManager.completeStep(
                id,
                ExecutionInfo(
                    label = "cmd",
                    start = 2000L,
                    end = 3000L,
                    resultCode = 0,
                    stdOut = Paths.get("/tmp/out2.txt"),
                    stdErr = Paths.get("/tmp/err2.txt"),
                    errMsg = null,
                ),
                null,
                null,
            )

            // Verify history
            val history = processManager.getProcessHistory(id)
            assertEquals(2, history.size)
            assertEquals(ProcessStatus.WORKING, history[0].second)
            assertEquals(ProcessStatus.FINISHED, history[1].second)

            // Verify executions
            val executions = processManager.getProcessExecutions(id)
            assertEquals(2, executions.size)
            assertEquals(0, executions[0].resultCode)
            assertEquals(0, executions[1].resultCode)
        }

    // ========== getProcessInfo Tests ==========

    @Test
    fun `getProcessInfo should return full process information`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")

            // Set status and add execution
            processManager.setProcessStatus(id, ProcessStatus.WORKING)
            processManager.completeStep(
                id,
                ExecutionInfo(
                    label = "cmd",
                    start = 1000L,
                    end = 2000L,
                    resultCode = 0,
                    stdOut = Paths.get("/tmp/out.txt"),
                    stdErr = Paths.get("/tmp/err.txt"),
                    errMsg = null,
                ),
                null,
                null,
            )

            val processInfo = processManager.getProcessInfo(id)
            assertNotNull(processInfo)
            assertEquals(id, processInfo.id)
            assertEquals(ProcessStatus.WORKING, processInfo.status)
            assertEquals(1, processInfo.history.size)
            assertEquals(1, processInfo.executions.size)
        }

    @Test
    fun `getProcessInfo should return null for non-existent process`() =
        runTest {
            val processInfo = processManager.getProcessInfo(999)
            assertEquals(null, processInfo)
        }

    @Test
    fun `getProcessInfo should include job when assigned`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")
            val job: Job = mockk(relaxed = true)
            processManager.assignJob(id, job)

            val processInfo = processManager.getProcessInfo(id)
            assertNotNull(processInfo)
            assertNotNull(processInfo.job)
        }

    @Test
    fun `getProcessInfo should return complete state snapshot`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")

            // Build up process state
            processManager.setProcessStatus(id, ProcessStatus.WORKING)
            processManager.setProcessStatus(id, ProcessStatus.FINISHED)
            processManager.completeStep(
                id,
                ExecutionInfo(
                    label = "cmd",
                    start = 1000L,
                    end = 2000L,
                    resultCode = 0,
                    stdOut = Paths.get("/tmp/out1.txt"),
                    stdErr = Paths.get("/tmp/err1.txt"),
                    errMsg = null,
                ),
                null,
                null,
            )
            processManager.completeStep(
                id,
                ExecutionInfo(
                    label = "cmd",
                    start = 2000L,
                    end = 3000L,
                    resultCode = 1,
                    stdOut = Paths.get("/tmp/out2.txt"),
                    stdErr = Paths.get("/tmp/err2.txt"),
                    errMsg = "Command failed",
                ),
                null,
                null,
            )

            val processInfo = processManager.getProcessInfo(id)
            assertNotNull(processInfo)
            assertEquals(id, processInfo.id)
            assertEquals(ProcessStatus.FINISHED, processInfo.status)
            assertEquals(2, processInfo.history.size)
            assertEquals(2, processInfo.executions.size)
            assertEquals("Command failed", processInfo.executions[1].errMsg)
        }

    // ========== getAllProcesses Tests ==========

    @Test
    fun `getAllProcesses should return all process infos`() =
        runTest {
            val processId1 = processManager.obtainProcessId("test-agent")
            val processId2 = processManager.obtainProcessId("test-agent")
            val processId3 = processManager.obtainProcessId("test-agent")

            processManager.setProcessStatus(processId1, ProcessStatus.WORKING)
            processManager.setProcessStatus(processId2, ProcessStatus.FINISHED)
            processManager.setProcessStatus(processId3, ProcessStatus.FAILED)

            val result = processManager.getAllProcesses()

            assertEquals(3, result.processes.size)
            assertEquals(3, result.total)
            assertTrue(result.processes.any { it.id == processId1 && it.status == ProcessStatus.WORKING })
            assertTrue(result.processes.any { it.id == processId2 && it.status == ProcessStatus.FINISHED })
            assertTrue(result.processes.any { it.id == processId3 && it.status == ProcessStatus.FAILED })
        }

    @Test
    fun `getAllProcesses should return empty list when no processes exist`() =
        runTest {
            val result = processManager.getAllProcesses()
            assertTrue(result.processes.isEmpty())
            assertEquals(0, result.total)
        }

    @Test
    fun `getAllProcesses should filter by status`() =
        runTest {
            val processId1 = processManager.obtainProcessId("test-agent")
            val processId2 = processManager.obtainProcessId("test-agent")
            val processId3 = processManager.obtainProcessId("test-agent")

            processManager.setProcessStatus(processId1, ProcessStatus.WORKING)
            processManager.setProcessStatus(processId2, ProcessStatus.FINISHED)
            processManager.setProcessStatus(processId3, ProcessStatus.WORKING)

            val result =
                processManager.getAllProcesses(
                    filterStatuses = setOf(ProcessStatus.WORKING),
                )

            assertEquals(2, result.processes.size)
            assertEquals(2, result.total)
            assertTrue(result.processes.all { it.status == ProcessStatus.WORKING })
        }

    @Test
    fun `getAllProcesses should order ascending by ID`() =
        runTest {
            val processId1 = processManager.obtainProcessId("test-agent")
            val processId2 = processManager.obtainProcessId("test-agent")
            val processId3 = processManager.obtainProcessId("test-agent")

            val result = processManager.getAllProcesses(orderAsc = true)

            assertEquals(3, result.processes.size)
            assertEquals(3, result.total)
            assertEquals(processId1, result.processes[0].id)
            assertEquals(processId2, result.processes[1].id)
            assertEquals(processId3, result.processes[2].id)
        }

    @Test
    fun `getAllProcesses should order descending by ID by default`() =
        runTest {
            val processId1 = processManager.obtainProcessId("test-agent")
            val processId2 = processManager.obtainProcessId("test-agent")
            val processId3 = processManager.obtainProcessId("test-agent")

            val result = processManager.getAllProcesses()

            assertEquals(3, result.processes.size)
            assertEquals(3, result.total)
            assertEquals(processId3, result.processes[0].id)
            assertEquals(processId2, result.processes[1].id)
            assertEquals(processId1, result.processes[2].id)
        }

    @Test
    fun `getAllProcesses should combine filtering and ordering`() =
        runTest {
            val processId1 = processManager.obtainProcessId("test-agent")
            val processId2 = processManager.obtainProcessId("test-agent")
            val processId3 = processManager.obtainProcessId("test-agent")
            val processId4 = processManager.obtainProcessId("test-agent")

            processManager.setProcessStatus(processId1, ProcessStatus.WORKING)
            processManager.setProcessStatus(processId2, ProcessStatus.FINISHED)
            processManager.setProcessStatus(processId3, ProcessStatus.WORKING)
            processManager.setProcessStatus(processId4, ProcessStatus.FINISHED)

            val result =
                processManager.getAllProcesses(
                    filterStatuses = setOf(ProcessStatus.FINISHED),
                    orderAsc = true,
                )

            assertEquals(2, result.processes.size)
            assertEquals(2, result.total)
            assertEquals(processId2, result.processes[0].id)
            assertEquals(processId4, result.processes[1].id)
            assertTrue(result.processes.all { it.status == ProcessStatus.FINISHED })
        }

    @Test
    fun `getAllProcesses should apply pagination with offset`() =
        runTest {
            repeat(10) { processManager.obtainProcessId("test-agent") }

            val result =
                processManager.getAllProcesses(
                    offset = 3,
                    limit = 3,
                    orderAsc = true,
                )

            assertEquals(3, result.processes.size)
            assertEquals(10, result.total)
            // AUTOINCREMENT ids are 1..10, so offset 3 yields the 4th..6th ids.
            assertEquals(4, result.processes[0].id)
            assertEquals(5, result.processes[1].id)
            assertEquals(6, result.processes[2].id)
        }

    @Test
    fun `getAllProcesses should apply pagination with limit`() =
        runTest {
            repeat(10) { processManager.obtainProcessId("test-agent") }

            val result =
                processManager.getAllProcesses(
                    offset = 0,
                    limit = 5,
                    orderAsc = true,
                )

            assertEquals(5, result.processes.size)
            assertEquals(10, result.total)
        }

    @Test
    fun `getAllProcesses should handle offset beyond total`() =
        runTest {
            repeat(5) { processManager.obtainProcessId("test-agent") }

            val result =
                processManager.getAllProcesses(
                    offset = 10,
                    limit = 5,
                )

            assertEquals(0, result.processes.size)
            assertEquals(5, result.total)
        }

    @Test
    fun `getAllProcesses should combine filtering ordering and pagination`() =
        runTest {
            // Create 10 processes with alternating statuses
            repeat(10) { idx ->
                val processId = processManager.obtainProcessId("test-agent")
                val status =
                    if (idx % 2 == 0) {
                        ProcessStatus.WORKING
                    } else {
                        ProcessStatus.FINISHED
                    }
                processManager.setProcessStatus(processId, status)
            }

            val result =
                processManager.getAllProcesses(
                    filterStatuses = setOf(ProcessStatus.WORKING),
                    orderAsc = true,
                    offset = 1,
                    limit = 2,
                )

            assertEquals(2, result.processes.size)
            assertEquals(5, result.total) // 5 WORKING processes total
            assertTrue(result.processes.all { it.status == ProcessStatus.WORKING })
        }

    // ========== Agent Name Tracking Tests ==========

    @Test
    fun `obtainProcessId should store agent name in ProcessInfo`() =
        runTest {
            val id = processManager.obtainProcessId("my-agent")
            val processInfo = processManager.getProcessInfo(id)

            assertNotNull(processInfo)
            assertEquals("my-agent", processInfo.agentName)
        }

    @Test
    fun `getProcessInfo should include agent name`() =
        runTest {
            val id1 = processManager.obtainProcessId("agent-one")
            val id2 = processManager.obtainProcessId("agent-two")

            val processInfo1 = processManager.getProcessInfo(id1)
            val processInfo2 = processManager.getProcessInfo(id2)

            assertNotNull(processInfo1)
            assertNotNull(processInfo2)
            assertEquals("agent-one", processInfo1.agentName)
            assertEquals("agent-two", processInfo2.agentName)
        }

    @Test
    fun `agent name should persist through status changes`() =
        runTest {
            val id = processManager.obtainProcessId("persistent-agent")

            processManager.setProcessStatus(id, ProcessStatus.WORKING)
            val processInfo1 = processManager.getProcessInfo(id)
            assertEquals("persistent-agent", processInfo1?.agentName)

            processManager.setProcessStatus(id, ProcessStatus.FINISHED)
            val processInfo2 = processManager.getProcessInfo(id)
            assertEquals("persistent-agent", processInfo2?.agentName)
        }

    @Test
    fun `agent name should persist through job assignment`() =
        runTest {
            val id = processManager.obtainProcessId("job-agent")
            val job: Job = mockk(relaxed = true)

            processManager.assignJob(id, job)
            val processInfo = processManager.getProcessInfo(id)

            assertEquals("job-agent", processInfo?.agentName)
        }

    @Test
    fun `agent name should persist when process is killed`() =
        runTest {
            val id = processManager.obtainProcessId("killable-agent")
            val job: Job = mockk(relaxed = true)

            processManager.assignJob(id, job)
            processManager.setProcessStatus(id, ProcessStatus.WORKING)
            processManager.killProcess(id)

            val processInfo = processManager.getProcessInfo(id)
            assertEquals("killable-agent", processInfo?.agentName)
        }

    // ========== purgeAt and removeProcess Tests ==========

    @Test
    fun `setPurgeAt stores purgeAt on process`() =
        runTest {
            val id = processManager.obtainProcessId("agent")
            processManager.setPurgeAt(id, 9999L)
            val info = processManager.getProcessInfo(id)
            assertEquals(9999L, info?.purgeAt)
        }

    @Test
    fun `removeProcess removes process from manager`() =
        runTest {
            val id = processManager.obtainProcessId("agent")
            processManager.removeProcess(id)
            val info = processManager.getProcessInfo(id)
            assertEquals(null, info)
        }

    @Test
    fun `removeProcess on unknown id is a no-op`() =
        runTest {
            // Should not throw
            processManager.removeProcess(9999)
        }

    // ========== Label Tracking Tests ==========

    @Test
    fun `label should be preserved through completeStep and getProcessExecutions`() =
        runTest {
            val id = processManager.obtainProcessId("test-agent")

            val executionInfo =
                ExecutionInfo(
                    label = "Fetch user data",
                    start = 1000L,
                    end = 2000L,
                    resultCode = 0,
                    stdOut = Paths.get("/tmp/out.txt"),
                    stdErr = Paths.get("/tmp/err.txt"),
                    errMsg = null,
                )

            processManager.completeStep(id, executionInfo, null, null)

            val executions = processManager.getProcessExecutions(id)
            assertEquals(1, executions.size)
            assertEquals("Fetch user data", executions[0].label)
        }

    // ========== inputParams Tests ==========

    @Test
    fun `obtainProcessId stores inputParams on ProcessInfo`() =
        runTest {
            val params = mapOf("TARGET" to "prod", "USER" to "alice")

            val processId = processManager.obtainProcessId("deploy-agent", params)

            val info = processManager.getProcessInfo(processId)
            assertNotNull(info)
            assertEquals("deploy-agent", info.agentName)
            assertEquals(params, info.inputParams)
        }

    @Test
    fun `obtainProcessId defaults inputParams to empty map when not provided`() =
        runTest {
            val processId = processManager.obtainProcessId("simple-agent")

            val info = processManager.getProcessInfo(processId)
            assertNotNull(info)
            assertEquals(emptyMap<String, String>(), info.inputParams)
        }

    // ========== RunnerState Tests ==========

    @Test
    fun `setRunnerState seeds state when previous is null`() =
        runTest {
            val pid = processManager.obtainProcessId("demo")

            processManager.setRunnerState(pid) {
                RunnerState(alias = "k8s", podPhase = PodPhase.CREATING)
            }

            val state = processManager.getRunnerState(pid)
            assertNotNull(state)
            assertEquals("k8s", state.alias)
            assertEquals(PodPhase.CREATING, state.podPhase)
        }

    @Test
    fun `setRunnerState updates prior state via copy`() =
        runTest {
            val pid = processManager.obtainProcessId("demo")
            processManager.setRunnerState(pid) {
                RunnerState(
                    alias = "k8s",
                    podName = "homer-$pid-abc",
                    podPhase = PodPhase.CREATING,
                    createdAt = 100L,
                )
            }

            processManager.setRunnerState(pid) { prev ->
                prev!!.copy(podPhase = PodPhase.READY, readyAt = 150L)
            }

            val state = processManager.getRunnerState(pid)!!
            assertEquals("homer-$pid-abc", state.podName)
            assertEquals(PodPhase.READY, state.podPhase)
            assertEquals(100L, state.createdAt)
            assertEquals(150L, state.readyAt)
        }

    @Test
    fun `setRunnerState is a no-op for unknown processId`() =
        runTest {
            processManager.setRunnerState(999) {
                RunnerState(alias = "k8s", podPhase = PodPhase.CREATING)
            }

            assertEquals(null, processManager.getRunnerState(999))
        }

    @Test
    fun `addCost accumulates across calls`() =
        runTest {
            val pid = processManager.obtainProcessId("demo")
            processManager.addCost(pid, 0.10, CostSource.CLI)
            processManager.addCost(pid, 0.25, CostSource.CLI)
            processManager.addCost(pid, 0.05, CostSource.CLI)

            assertEquals(0.40, processManager.getProcessInfo(pid)!!.costUsd, 1e-9)
        }

    @Test
    fun `addCost with zero delta is a no-op`() =
        runTest {
            val pid = processManager.obtainProcessId("demo")
            processManager.addCost(pid, 1.0, CostSource.CLI)
            processManager.addCost(pid, 0.0, CostSource.CLI)

            assertEquals(1.0, processManager.getProcessInfo(pid)!!.costUsd, 1e-9)
        }

    @Test
    fun `addCost on unknown processId is a no-op`() =
        runTest {
            processManager.addCost(424242, 5.0, CostSource.CLI)
            // Just shouldn't throw; nothing to assert beyond that.
        }
}
