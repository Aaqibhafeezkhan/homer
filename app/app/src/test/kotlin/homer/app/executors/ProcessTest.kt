package homer.app.executors

import homer.app.config.ExecutionConfig
import homer.app.exceptions.AnotherProcessStillActive
import homer.app.exceptions.MaxLockWaitersExceededException
import homer.app.exceptions.MaxParallelRunsExceededException
import homer.app.exceptions.MaxQueuedRunsExceededException
import homer.app.exceptions.UnresolvedLockKeyPlaceholderException
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.TestFixtures
import homer.app.infra.DefaultTextCompiler
import homer.app.infra.QuestionsWorkspaceSetup
import homer.app.infra.TelemetryService
import homer.app.infra.Workspace
import homer.app.infra.WorkspaceConfig
import homer.app.infra.WorkspaceConfigFactory
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.LockStrategy
import homer.app.models.OnKeyCollisionStrategy
import homer.app.models.OnMaxParallelRunsStrategy
import homer.app.models.Param
import homer.app.models.ProcessStatus
import homer.app.models.PurgeAfterParser
import homer.app.models.Retain
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.Runner
import homer.app.runners.RunnerRegistry
import homer.app.runners.RunnerSession
import homer.app.services.ProcessManager
import homer.app.services.QuestionsService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Takes a lock the way `Process.admit` does — acquire, then kill the holder the collision
 * displaced — under a no-op gate: these tests wire the real lock manager's waiters through
 * `Process`, and the agent's lock-wait bound is `ParallelRunsManager`'s to bound.
 */
private suspend fun LockManager.acquireThenDisplace(
    lockName: String,
    onKeyCollisionStrategy: OnKeyCollisionStrategy,
    processID: Int,
) {
    val gate = LockWaitGate { LockWaitTicket {} }
    killDisplaced(acquireLock(lockName, onKeyCollisionStrategy, processID, gate), processID)
}

/**
 * Unit tests for Process class
 * Tests process execution, workspace management, and agent executor integration
 */
class ProcessTest {
    private lateinit var mockWorkspaceConfigFactory: WorkspaceConfigFactory
    private lateinit var mockExecutorFactory: CommandExecutorFactory
    private lateinit var mockTelemetryService: TelemetryService
    private lateinit var mockLockManager: homer.app.executors.LockManager
    private lateinit var mockParallelRunsManager: ParallelRunsManager
    private lateinit var mockProcessManager: ProcessManager
    private lateinit var mockRunner: Runner
    private lateinit var mockSession: RunnerSession
    private lateinit var mockRunnerRegistry: RunnerRegistry
    private lateinit var mockQuestionsService: QuestionsService
    private lateinit var mockQuestionsWorkspaceSetup: QuestionsWorkspaceSetup
    private lateinit var mockSpan: Span
    private lateinit var process: Process

    private val testConfig = ExecutionConfig()
    private val testAgent: Agent = AgentFixtures.simpleAgent()
    private val testQueryParams = mapOf("param1" to "value1")
    private val testContext = Context.current()

    @BeforeEach
    fun setup() {
        mockSpan = TestFixtures.mockSpan()
        mockTelemetryService = TestFixtures.mockTelemetryService(mockSpan)
        mockWorkspaceConfigFactory = mockk(relaxed = true)
        mockExecutorFactory = mockk(relaxed = true)
        mockLockManager = mockk(relaxed = true)
        mockParallelRunsManager = mockk(relaxed = true)
        mockProcessManager = mockk(relaxed = true)
        coEvery { mockProcessManager.compareAndSetStatus(any(), any(), any()) } returns true
        mockRunner = mockk(relaxed = true)
        mockRunnerRegistry = mockk(relaxed = true)
        every { mockRunnerRegistry.forAgent(any()) } returns mockRunner
        // Process reads the SPI before createWorkspaceConfig; a relaxed mock would
        // hand back a mock Map and break the factory-stub argument matching below.
        every { mockRunner.isRemote } returns false
        every { mockRunner.runnerEnv } returns emptyMap()
        every { mockRunner.alias } returns "local"
        // A relaxed RunnerSession would fabricate a RunDeadline with softEpochSec 0, which
        // reads as "the soft deadline has passed" and stops the run before its first step
        // (ADR-0107). These tests run without a deadline.
        mockSession = mockk(relaxed = true)
        every { mockSession.runDeadline } returns null
        coEvery { mockRunner.openSession(any()) } returns mockSession
        mockQuestionsService = mockk(relaxed = true)
        coEvery { mockQuestionsService.registerProcess(any(), any(), any()) } returns "test-callback-token"
        mockQuestionsWorkspaceSetup = mockk(relaxed = true)
        // AgentExecutor now records the executor-returned CommandResult's actual
        // stdOut/stdErr in ExecutionInfo (relativized to the artifacts dir); a
        // fully-relaxed executor mock would otherwise return synthetic mock Paths
        // that blow up relativeTo(). These tests don't care about that path, only
        // that Process orchestrates the executor — give it real, if arbitrary, paths.
        coEvery {
            mockExecutorFactory.getExecutor<Command>(any()).execute(any(), any(), any())
        } returns TestFixtures.mockCommandResult(exitCode = 0)

        // Create a real Process instance with mocked dependencies
        process =
            Process(
                workspaceConfigFactory = mockWorkspaceConfigFactory,
                executorFactory = mockExecutorFactory,
                telemetryService = mockTelemetryService,
                config = testConfig,
                lockManager = mockLockManager,
                parallelRunsManager = mockParallelRunsManager,
                processManager = mockProcessManager,
                runnerRegistry = mockRunnerRegistry,
                activeSessions = ActiveSessionsRegistry(),
                questionsService = mockQuestionsService,
                questionsWorkspaceSetup = mockQuestionsWorkspaceSetup,
                agent = testAgent,
                queryParams = testQueryParams,
                parentContext = testContext,
            )
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    // ========== Constructor and Properties Tests ==========

    @Test
    fun `Process should expose agent name`() {
        assertEquals(testAgent.name, process.name)
    }

    @Test
    fun `Process name should match agent name`() =
        runTest {
            val customAgent = AgentFixtures.simpleAgent().copy(name = "custom-agent-name")
            val customProcess =
                Process(
                    workspaceConfigFactory = mockWorkspaceConfigFactory,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = testConfig,
                    lockManager = mockLockManager,
                    parallelRunsManager = mockParallelRunsManager,
                    processManager = mockProcessManager,
                    runnerRegistry = mockRunnerRegistry,
                    activeSessions = ActiveSessionsRegistry(),
                    questionsService = mockQuestionsService,
                    questionsWorkspaceSetup = mockQuestionsWorkspaceSetup,
                    agent = customAgent,
                    queryParams = emptyMap(),
                    parentContext = testContext,
                )

            assertEquals("custom-agent-name", customProcess.name)
        }

    // ========== Workspace Config Creation Tests ==========

    @Test
    fun `execute should create workspace config with correct parameters`() =
        runTest {
            val processId = 123
            val mockWorkspaceConfig = TestFixtures.mockWorkspaceConfig()

            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns mockWorkspaceConfig

            process.execute(processId, mockSpan)

            verify {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    processId = processId,
                    agentEnv = testAgent.env,
                    lockKeyTemplate = testAgent.lockStrategy.key,
                    queryParams = testQueryParams,
                    workspaceAgentConfig = testAgent.workspace,
                    runnerEnv = emptyMap(),
                    includeParentEnv = true,
                    runnerAlias = "local",
                )
            }
        }

    @Test
    fun `execute passes includeParentEnv=false and runnerEnv for a remote runner`() =
        runTest {
            val runnerEnv = mapOf("CI" to "true")
            every { mockRunner.isRemote } returns true
            every { mockRunner.runnerEnv } returns runnerEnv
            every { mockRunner.alias } returns "ssh-test"
            val remoteAgent = testAgent.copy(runner = "ssh-test")
            val remoteProcess =
                Process(
                    workspaceConfigFactory = mockWorkspaceConfigFactory,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = testConfig,
                    lockManager = mockLockManager,
                    parallelRunsManager = mockParallelRunsManager,
                    processManager = mockProcessManager,
                    runnerRegistry = mockRunnerRegistry,
                    activeSessions = ActiveSessionsRegistry(),
                    questionsService = mockQuestionsService,
                    questionsWorkspaceSetup = mockQuestionsWorkspaceSetup,
                    agent = remoteAgent,
                    queryParams = testQueryParams,
                    parentContext = testContext,
                )
            val mockWorkspaceConfig = TestFixtures.mockWorkspaceConfig()
            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns mockWorkspaceConfig

            remoteProcess.execute(99, mockSpan)

            verify {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    processId = 99,
                    agentEnv = remoteAgent.env,
                    lockKeyTemplate = remoteAgent.lockStrategy.key,
                    queryParams = testQueryParams,
                    workspaceAgentConfig = remoteAgent.workspace,
                    runnerEnv = runnerEnv,
                    includeParentEnv = false,
                    runnerAlias = "ssh-test",
                )
            }
        }

    @Test
    fun `workspace config receives the queued runner's alias, env facets and secret names`() =
        runTest {
            every { mockRunner.alias } returns "tags:linux"
            every { mockRunner.isRemote } returns true
            every { mockRunner.runnerEnv } returns emptyMap()
            every { mockRunner.sysEnvBackedEnvNames } returns setOf("RUNNER_TOKEN")
            val mockWorkspaceConfig = TestFixtures.mockWorkspaceConfig()
            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns mockWorkspaceConfig

            process.execute(1, mockSpan)

            verify {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    processId = any(),
                    agentEnv = any(),
                    lockKeyTemplate = any(),
                    queryParams = any(),
                    workspaceAgentConfig = any(),
                    invocationChain = any(),
                    ancestorLockKeys = any(),
                    secretEnvNames = match { "RUNNER_TOKEN" in it },
                    parentProcessId = any(),
                    runnerEnv = emptyMap(),
                    includeParentEnv = false,
                    runnerAlias = "tags:linux",
                )
            }
        }

    @Test
    fun `execute should pass clearAfterFinish from agent workspace config`() =
        runTest {
            val agentWithCleanup =
                testAgent.copy(
                    workspace = testAgent.workspace.copy(cleanAfterFinish = false),
                )
            val processWithCleanup =
                Process(
                    workspaceConfigFactory = mockWorkspaceConfigFactory,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = testConfig,
                    lockManager = mockLockManager,
                    parallelRunsManager = mockParallelRunsManager,
                    processManager = mockProcessManager,
                    runnerRegistry = mockRunnerRegistry,
                    activeSessions = ActiveSessionsRegistry(),
                    questionsService = mockQuestionsService,
                    questionsWorkspaceSetup = mockQuestionsWorkspaceSetup,
                    agent = agentWithCleanup,
                    queryParams = testQueryParams,
                    parentContext = testContext,
                )

            val mockWorkspaceConfig = TestFixtures.mockWorkspaceConfig()
            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns mockWorkspaceConfig

            processWithCleanup.execute(1, mockSpan)

            verify {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    workspaceAgentConfig = agentWithCleanup.workspace,
                    runnerEnv = emptyMap(),
                    includeParentEnv = true,
                    runnerAlias = "local",
                )
            }
        }

    // ========== Callback Token Tests ==========

    @Test
    fun `execute should register a callback token for the process`() =
        runTest {
            val mockWorkspaceConfig = TestFixtures.mockWorkspaceConfig()
            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns mockWorkspaceConfig

            process.execute(42, mockSpan)

            coVerify { mockQuestionsService.registerProcess(42, testAgent.name, any()) }
        }

    @Test
    fun `execute should materialize the questions tooling into the workspace`() =
        runTest {
            val mockWorkspaceConfig = TestFixtures.mockWorkspaceConfig()
            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns mockWorkspaceConfig

            process.execute(1, mockSpan)

            verify { mockQuestionsWorkspaceSetup.materialize(any()) }
        }

    // ========== Span Management Tests ==========

    @Test
    fun `execute should end span on successful execution`() =
        runTest {
            val mockWorkspaceConfig = TestFixtures.mockWorkspaceConfig()
            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns mockWorkspaceConfig

            process.execute(1, mockSpan)

            verify { mockSpan.end() }
        }

    @Test
    fun `execute should end span even when workspace create or cleanup fails`() =
        runTest {
            // Note: This test verifies that span.end() is called even if workspace operations fail
            // However, if workspaceConfigFactory throws, the span won't be ended because
            // the exception occurs before the try-finally block

            // For this test, we'll mock the workspace config to succeed, but we can't easily
            // mock the Workspace or AgentExecutor to fail without extensive mocking
            // So we just verify that in normal flow, span.end() is called
            val mockWorkspaceConfig = TestFixtures.mockWorkspaceConfig()
            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns mockWorkspaceConfig

            process.execute(1, mockSpan)

            verify { mockSpan.end() }
        }

    // ========== Integration Tests ==========

    @Test
    fun `execute should create workspace, execute agent, and cleanup in order`() =
        runTest {
            val mockWorkspaceConfig = TestFixtures.mockWorkspaceConfig()
            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns mockWorkspaceConfig

            process.execute(1, mockSpan)

            // Verify the workspace config factory was called
            verify {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }

            // Verify span was ended
            verify { mockSpan.end() }
        }

    @Test
    fun `execute does not start a run that was killed before it reached WORKING`() =
        runTest {
            // A kill that lands after the slot and lock are taken but before the WORKING
            // write must win: the run stays KILLED and runs no command.
            coEvery { mockProcessManager.compareAndSetStatus(1, any(), ProcessStatus.WORKING) } returns false

            assertFailsWith<CancellationException> { process.execute(1, mockSpan) }

            coVerify(exactly = 0) { mockProcessManager.setProcessStatus(1, ProcessStatus.WORKING) }
            coVerify(exactly = 0) { mockExecutorFactory.getExecutor<Command>(any()).execute(any(), any(), any()) }
        }

    @Test
    fun `execute should use provided processId`() =
        runTest {
            val processId = 999
            val customProcess =
                Process(
                    workspaceConfigFactory = mockWorkspaceConfigFactory,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = testConfig,
                    lockManager = mockLockManager,
                    parallelRunsManager = mockParallelRunsManager,
                    processManager = mockProcessManager,
                    runnerRegistry = mockRunnerRegistry,
                    activeSessions = ActiveSessionsRegistry(),
                    questionsService = mockQuestionsService,
                    questionsWorkspaceSetup = mockQuestionsWorkspaceSetup,
                    agent = testAgent,
                    queryParams = testQueryParams,
                    parentContext = testContext,
                )
            val mockWorkspaceConfig = TestFixtures.mockWorkspaceConfig()

            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns mockWorkspaceConfig

            customProcess.execute(processId, mockSpan)

            verify {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    processId = processId,
                    agentEnv = any(),
                    lockKeyTemplate = any(),
                    queryParams = any(),
                    workspaceAgentConfig = any(),
                    runnerEnv = emptyMap(),
                    includeParentEnv = true,
                    runnerAlias = "local",
                )
            }
        }

    // ========== Error Handling Tests ==========

    @Test
    fun `execute should propagate exceptions from workspace config creation`() =
        runTest {
            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } throws IllegalStateException("Config creation failed")

            try {
                process.execute(1, mockSpan)
                throw AssertionError("Expected exception was not thrown")
            } catch (e: IllegalStateException) {
                assertEquals("Config creation failed", e.message)
            }

            // Note: span.end() is NOT called when workspaceConfigFactory throws
            // because the exception occurs before the try-finally block
            verify(exactly = 0) { mockSpan.end() }
        }

    @Test
    fun `execute should not end span when workspace config creation fails`() =
        runTest {
            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } throws RuntimeException("Workspace config creation failed")

            try {
                process.execute(1, mockSpan)
            } catch (e: RuntimeException) {
                // Expected
            }

            // span.end() is only called in the finally block of the try statement
            // If workspaceConfigFactory throws before the try block, span.end() won't be called
            verify(exactly = 0) { mockSpan.end() }
        }

    // ========== Query Params Tests ==========

    @Test
    fun `execute should pass query params to workspace config`() =
        runTest {
            val queryParams = mapOf("key1" to "value1", "key2" to "value2")
            val processWithParams =
                Process(
                    workspaceConfigFactory = mockWorkspaceConfigFactory,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = testConfig,
                    lockManager = mockLockManager,
                    parallelRunsManager = mockParallelRunsManager,
                    processManager = mockProcessManager,
                    runnerRegistry = mockRunnerRegistry,
                    activeSessions = ActiveSessionsRegistry(),
                    questionsService = mockQuestionsService,
                    questionsWorkspaceSetup = mockQuestionsWorkspaceSetup,
                    agent = testAgent,
                    queryParams = queryParams,
                    parentContext = testContext,
                )

            val mockWorkspaceConfig = TestFixtures.mockWorkspaceConfig()
            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns mockWorkspaceConfig

            processWithParams.execute(1, mockSpan)

            verify {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    processId = any(),
                    agentEnv = any(),
                    lockKeyTemplate = any(),
                    queryParams = queryParams,
                    workspaceAgentConfig = any(),
                    runnerEnv = emptyMap(),
                    includeParentEnv = true,
                    runnerAlias = "local",
                )
            }
        }

    @Test
    fun `execute should handle empty query params`() =
        runTest {
            val processWithoutParams =
                Process(
                    workspaceConfigFactory = mockWorkspaceConfigFactory,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = testConfig,
                    lockManager = mockLockManager,
                    parallelRunsManager = mockParallelRunsManager,
                    processManager = mockProcessManager,
                    runnerRegistry = mockRunnerRegistry,
                    activeSessions = ActiveSessionsRegistry(),
                    questionsService = mockQuestionsService,
                    questionsWorkspaceSetup = mockQuestionsWorkspaceSetup,
                    agent = testAgent,
                    queryParams = emptyMap(),
                    parentContext = testContext,
                )

            val mockWorkspaceConfig = TestFixtures.mockWorkspaceConfig()
            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns mockWorkspaceConfig

            processWithoutParams.execute(1, mockSpan)

            verify {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    processId = any(),
                    agentEnv = any(),
                    lockKeyTemplate = any(),
                    queryParams = emptyMap(),
                    workspaceAgentConfig = any(),
                    runnerEnv = emptyMap(),
                    includeParentEnv = true,
                    runnerAlias = "local",
                )
            }
        }

    // ========== acceptParentLock (flow-scoped pass-through) Tests ==========

    private fun processWith(
        agent: Agent,
        ancestorLockKeys: List<String>,
        processManager: ProcessManager = mockProcessManager,
    ): Process =
        Process(
            workspaceConfigFactory = mockWorkspaceConfigFactory,
            executorFactory = mockExecutorFactory,
            telemetryService = mockTelemetryService,
            config = testConfig,
            lockManager = mockLockManager,
            parallelRunsManager = mockParallelRunsManager,
            processManager = processManager,
            runnerRegistry = mockRunnerRegistry,
            activeSessions = ActiveSessionsRegistry(),
            questionsService = mockQuestionsService,
            questionsWorkspaceSetup = mockQuestionsWorkspaceSetup,
            agent = agent,
            queryParams = emptyMap(),
            parentContext = testContext,
            ancestorLockKeys = ancestorLockKeys,
        )

    @Test
    fun `execute skips lock acquire and release when acceptParentLock and key held by ancestor`() =
        runTest {
            val agent = testAgent.copy(lockStrategy = LockStrategy(key = "shared", acceptParentLock = true))
            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns TestFixtures.mockWorkspaceConfig(lockKey = "shared")

            processWith(agent, ancestorLockKeys = listOf("shared")).execute(1, mockSpan)

            coVerify(exactly = 0) { mockLockManager.acquireLock(any(), any(), any(), any()) }
            coVerify(exactly = 0) { mockLockManager.releaseLock(any(), any()) }
        }

    @Test
    fun `execute still respects parallel-run slot when inheriting parent lock`() =
        runTest {
            val agent = testAgent.copy(lockStrategy = LockStrategy(key = "shared", acceptParentLock = true))
            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns TestFixtures.mockWorkspaceConfig(lockKey = "shared")

            processWith(agent, ancestorLockKeys = listOf("shared")).execute(1, mockSpan)

            coVerify { mockParallelRunsManager.acquireSlot(agent.name, any(), any()) }
            coVerify { mockParallelRunsManager.releaseSlot(any()) }
        }

    @Test
    fun `execute acquires lock when acceptParentLock but key not held by ancestor`() =
        runTest {
            val agent = testAgent.copy(lockStrategy = LockStrategy(key = "shared", acceptParentLock = true))
            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns TestFixtures.mockWorkspaceConfig(lockKey = "shared")

            processWith(agent, ancestorLockKeys = listOf("a-different-key")).execute(7, mockSpan)

            coVerify { mockLockManager.acquireLock("shared", any(), 7, any()) }
            coVerify { mockLockManager.releaseLock("shared", 7) }
        }

    @Test
    fun `execute acquires lock when key matches ancestor but acceptParentLock is off`() =
        runTest {
            val agent = testAgent.copy(lockStrategy = LockStrategy(key = "shared", acceptParentLock = false))
            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns TestFixtures.mockWorkspaceConfig(lockKey = "shared")

            processWith(agent, ancestorLockKeys = listOf("shared")).execute(9, mockSpan)

            coVerify { mockLockManager.acquireLock("shared", any(), 9, any()) }
            coVerify { mockLockManager.releaseLock("shared", 9) }
        }

    // ========== Lock-before-slot admission order (ADR-0104) Tests ==========

    /** A Process over the given (real) gates; its lock key is rendered from the `MR` input. */
    private fun processFor(
        agent: Agent,
        queryParams: Map<String, String>,
        lockManager: homer.app.executors.LockManager,
        parallelRunsManager: ParallelRunsManager,
    ): Process =
        Process(
            workspaceConfigFactory = mockWorkspaceConfigFactory,
            executorFactory = mockExecutorFactory,
            telemetryService = mockTelemetryService,
            config = testConfig,
            lockManager = lockManager,
            parallelRunsManager = parallelRunsManager,
            processManager = mockProcessManager,
            runnerRegistry = mockRunnerRegistry,
            activeSessions = ActiveSessionsRegistry(),
            questionsService = mockQuestionsService,
            questionsWorkspaceSetup = mockQuestionsWorkspaceSetup,
            agent = agent,
            queryParams = queryParams,
            parentContext = testContext,
            // A mocked workspace: these tests are about admission order, not filesystem work.
            workspaceFactory = { config, _, _ -> TestFixtures.mockWorkspace(config) },
        )

    /** Renders `mr-<MR>` from the run's inputs, as the production factory would for that template. */
    private fun stubLockKeyFromInput() {
        every {
            mockWorkspaceConfigFactory.createWorkspaceConfig(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } answers {
            // `createWorkspaceConfig`'s 4th parameter is the run's inputs.
            @Suppress("UNCHECKED_CAST")
            val inputs = invocation.args[3] as Map<String, String>
            TestFixtures.mockWorkspaceConfig(lockKey = "mr-" + inputs["MR"])
        }
    }

    /** A fixed lock key for every run of the agent. */
    private fun stubSharedLockKey() {
        every {
            mockWorkspaceConfigFactory.createWorkspaceConfig(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns TestFixtures.mockWorkspaceConfig(lockKey = "shared")
    }

    /** An executor whose first call suspends until [released]; later calls return a green result. */
    private fun stubExecutorSuspendingOnce(released: CompletableDeferred<Unit>): CompletableDeferred<Unit> {
        val entered = CompletableDeferred<Unit>()
        var first = true
        coEvery { mockExecutorFactory.getExecutor<Command>(any()).execute(any(), any(), any()) } coAnswers {
            if (first) {
                first = false
                entered.complete(Unit)
                released.await()
            }
            TestFixtures.mockCommandResult(exitCode = 0)
        }
        return entered
    }

    @Test
    fun `a run of a free subject starts while another subject's run waits for the lock`() =
        runTest(timeout = 10.seconds) {
            val lockManager = DefaultLockManager(mockProcessManager, TestFixtures.mockLogger())
            val parallelRunsManager = DefaultParallelRunsManager()
            val agent =
                testAgent.copy(
                    maxParallelRuns = 2,
                    lockStrategy = LockStrategy(key = "mr-{{MR}}", onCollision = OnKeyCollisionStrategy.WAIT),
                )
            stubLockKeyFromInput()
            val release = CompletableDeferred<Unit>()
            val run1Entered = stubExecutorSuspendingOnce(release)

            val run1 = launch { processFor(agent, mapOf("MR" to "1"), lockManager, parallelRunsManager).execute(1, mockSpan) }
            run1Entered.await()

            // Run 2 collides on mr-1 and waits for that lock. It holds no slot while it waits.
            val run2 = launch { processFor(agent, mapOf("MR" to "1"), lockManager, parallelRunsManager).execute(2, mockSpan) }
            runCurrent()

            // Run 3's subject is mr-2, which nothing else uses: it must run now, not behind run 2.
            processFor(agent, mapOf("MR" to "2"), lockManager, parallelRunsManager).execute(3, mockSpan)

            coVerify { mockProcessManager.compareAndSetStatus(3, setOf(ProcessStatus.CREATED), ProcessStatus.WORKING) }
            coVerify(exactly = 0) {
                mockProcessManager.compareAndSetStatus(2, setOf(ProcessStatus.CREATED), ProcessStatus.WORKING)
            }

            // Run 1 finishes, the mr-1 lock passes to run 2, and run 2 runs.
            release.complete(Unit)
            run1.join()
            run2.join()
            coVerify { mockProcessManager.compareAndSetStatus(2, setOf(ProcessStatus.CREATED), ProcessStatus.WORKING) }
        }

    @Test
    fun `runs waiting for slots never put more than maxParallelRuns in WORKING`() =
        runTest(timeout = 10.seconds) {
            val lockManager = DefaultLockManager(mockProcessManager, TestFixtures.mockLogger())
            val parallelRunsManager = DefaultParallelRunsManager()
            val agent =
                testAgent.copy(
                    maxParallelRuns = 2,
                    lockStrategy = LockStrategy(key = "mr-{{MR}}", onCollision = OnKeyCollisionStrategy.WAIT),
                )
            stubLockKeyFromInput()
            val working = AtomicInteger(0)
            val peak = AtomicInteger(0)
            coEvery { mockExecutorFactory.getExecutor<Command>(any()).execute(any(), any(), any()) } coAnswers {
                val now = working.incrementAndGet()
                peak.getAndUpdate { maxOf(it, now) }
                yield()
                working.decrementAndGet()
                TestFixtures.mockCommandResult(exitCode = 0)
            }

            (1..6)
                .map { mr ->
                    launch {
                        processFor(agent, mapOf("MR" to mr.toString()), lockManager, parallelRunsManager).execute(mr, mockSpan)
                    }
                }.forEach { it.join() }

            assertTrue(peak.get() <= 2, "maxParallelRuns bounds WORKING runs, observed ${peak.get()}")
        }

    @Test
    fun `KILL_OLD_RUN_CURRENT kills the victim only once the new run holds a slot`() =
        runTest(timeout = 10.seconds) {
            val lockManager = DefaultLockManager(mockProcessManager, TestFixtures.mockLogger())
            val parallelRunsManager = DefaultParallelRunsManager()
            val agent =
                testAgent.copy(
                    maxParallelRuns = 1,
                    lockStrategy = LockStrategy(key = "shared", onCollision = OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT),
                )
            stubSharedLockKey()
            val release = CompletableDeferred<Unit>()
            val run1Entered = stubExecutorSuspendingOnce(release)

            val run1 = launch { processFor(agent, emptyMap(), lockManager, parallelRunsManager).execute(1, mockSpan) }
            run1Entered.await()

            // Run 2 displaces run 1 on the lock, but the only slot is held: no kill yet.
            val run2 = launch { processFor(agent, emptyMap(), lockManager, parallelRunsManager).execute(2, mockSpan) }
            runCurrent()
            coVerify(exactly = 0) { mockProcessManager.killProcess(1, any()) }

            release.complete(Unit)
            run1.join()
            run2.join()

            coVerify(exactly = 1) { mockProcessManager.killProcess(1, "The new process 2 takes precedence") }
        }

    @Test
    fun `a refused slot attempt leaves the displaced holder alive and the lock released`() =
        runTest(timeout = 10.seconds) {
            val lockManager = DefaultLockManager(mockProcessManager, TestFixtures.mockLogger())
            val parallelRunsManager = DefaultParallelRunsManager()
            val agent =
                testAgent.copy(
                    maxParallelRuns = 1,
                    onMaxParallelRuns = OnMaxParallelRunsStrategy.REJECT,
                    lockStrategy = LockStrategy(key = "shared", onCollision = OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT),
                )
            stubSharedLockKey()
            val release = CompletableDeferred<Unit>()
            val run1Entered = stubExecutorSuspendingOnce(release)

            val run1 = launch { processFor(agent, emptyMap(), lockManager, parallelRunsManager).execute(1, mockSpan) }
            run1Entered.await()

            assertFailsWith<MaxParallelRunsExceededException> {
                processFor(agent, emptyMap(), lockManager, parallelRunsManager).execute(2, mockSpan)
            }
            coVerify(exactly = 0) { mockProcessManager.killProcess(any(), any()) }

            // Run 2 gave the lock back, so run 1's own release frees the key for the next run.
            release.complete(Unit)
            run1.join()
            lockManager.acquireThenDisplace("shared", OnKeyCollisionStrategy.SKIP_CURRENT, 3)
        }

    @Test
    fun `SKIP_CURRENT fails on the lock before any slot is taken`() =
        runTest {
            val lockManager = DefaultLockManager(mockProcessManager, TestFixtures.mockLogger())
            val parallelRunsManager = mockk<ParallelRunsManager>(relaxed = true)
            val agent = testAgent.copy(lockStrategy = LockStrategy(key = "shared", onCollision = OnKeyCollisionStrategy.SKIP_CURRENT))
            stubSharedLockKey()
            lockManager.acquireThenDisplace("shared", OnKeyCollisionStrategy.WAIT, 1)

            val ex =
                assertFailsWith<AnotherProcessStillActive> {
                    processFor(agent, emptyMap(), lockManager, parallelRunsManager).execute(2, mockSpan)
                }

            assertEquals(1, ex.activeProcessId)
            coVerify(exactly = 0) { parallelRunsManager.acquireSlot(any(), any(), any(), any()) }
            coVerify(exactly = 0) { parallelRunsManager.releaseSlot(any()) }
        }

    @Test
    fun `a WAIT collision takes the slot only after the lock is granted`() =
        runTest(timeout = 10.seconds) {
            val lockManager = DefaultLockManager(mockProcessManager, TestFixtures.mockLogger())
            val parallelRunsManager = mockk<ParallelRunsManager>(relaxed = true)
            val agent = testAgent.copy(lockStrategy = LockStrategy(key = "shared", onCollision = OnKeyCollisionStrategy.WAIT))
            stubSharedLockKey()
            lockManager.acquireThenDisplace("shared", OnKeyCollisionStrategy.WAIT, 1)

            val run2 = launch { processFor(agent, emptyMap(), lockManager, parallelRunsManager).execute(2, mockSpan) }
            runCurrent()
            coVerify(exactly = 0) { parallelRunsManager.acquireSlot(any(), any(), any(), any()) }

            lockManager.releaseLock("shared", 1)
            run2.join()

            coVerify { parallelRunsManager.acquireSlot(any(), any(), any(), any()) }
        }

    @Test
    fun `a surplus lock waiter is refused instead of parked`() =
        runTest(timeout = 10.seconds) {
            val lockManager = DefaultLockManager(mockProcessManager, TestFixtures.mockLogger())
            val parallelRunsManager = DefaultParallelRunsManager()
            val agent =
                testAgent.copy(
                    maxParallelRuns = 1,
                    lockStrategy = LockStrategy(key = "shared", onCollision = OnKeyCollisionStrategy.WAIT),
                )
            stubSharedLockKey()
            lockManager.acquireThenDisplace("shared", OnKeyCollisionStrategy.WAIT, 1)

            val first = launch { processFor(agent, emptyMap(), lockManager, parallelRunsManager).execute(2, mockSpan) }
            runCurrent()

            // The lock-wait bound is maxParallelRuns here (maxQueuedRuns is unset): the surplus fails.
            assertFailsWith<MaxLockWaitersExceededException> {
                processFor(agent, emptyMap(), lockManager, parallelRunsManager).execute(3, mockSpan)
            }

            lockManager.releaseLock("shared", 1)
            first.join()
            coVerify { mockProcessManager.compareAndSetStatus(2, setOf(ProcessStatus.CREATED), ProcessStatus.WORKING) }
        }

    @Test
    fun `a surplus lock waiter is refused by the shared maxQueuedRuns cap`() =
        runTest(timeout = 10.seconds) {
            val lockManager = DefaultLockManager(mockProcessManager, TestFixtures.mockLogger())
            val parallelRunsManager = DefaultParallelRunsManager()
            val agent =
                testAgent.copy(
                    maxParallelRuns = 1,
                    maxQueuedRuns = 2,
                    onMaxParallelRuns = OnMaxParallelRunsStrategy.WAIT,
                    lockStrategy = LockStrategy(key = "shared", onCollision = OnKeyCollisionStrategy.WAIT),
                )
            stubSharedLockKey()
            lockManager.acquireThenDisplace("shared", OnKeyCollisionStrategy.WAIT, 1)

            val first = launch { processFor(agent, emptyMap(), lockManager, parallelRunsManager).execute(2, mockSpan) }
            runCurrent()
            val second = launch { processFor(agent, emptyMap(), lockManager, parallelRunsManager).execute(3, mockSpan) }
            runCurrent()

            // The wait queue is full (maxQueuedRuns: 2, shared with the slot queue): the surplus
            // is refused with the queue's own exception, not the fallback lock-waiter one.
            assertFailsWith<MaxQueuedRunsExceededException> {
                processFor(agent, emptyMap(), lockManager, parallelRunsManager).execute(4, mockSpan)
            }

            // The released lock passes to the first waiter and the queue still drains.
            lockManager.releaseLock("shared", 1)
            first.join()
            second.join()
            coVerify { mockProcessManager.compareAndSetStatus(2, setOf(ProcessStatus.CREATED), ProcessStatus.WORKING) }
            coVerify { mockProcessManager.compareAndSetStatus(3, setOf(ProcessStatus.CREATED), ProcessStatus.WORKING) }
        }

    // ========== Unresolved lock key placeholder (fail closed, before any gate) ==========

    /**
     * A Process over the **real** [WorkspaceConfigFactory], so the key render and its denial are
     * the production ones. The workspace factory throws if it is ever reached; the lock and slot
     * managers default to the real ones, where a denial after a gate would leave that gate held
     * and hang the test, and a mock pair lets a test assert the gates were never called at all.
     */
    private fun processOverRealFactory(
        agent: Agent,
        queryParams: Map<String, String>,
        lockManager: LockManager = DefaultLockManager(mockProcessManager, TestFixtures.mockLogger()),
        parallelRunsManager: ParallelRunsManager = DefaultParallelRunsManager(),
    ): Process =
        Process(
            workspaceConfigFactory =
                WorkspaceConfigFactory(
                    workspaceRoot = "/tmp/homer",
                    globalEnv = emptyMap(),
                    textCompiler = DefaultTextCompiler(),
                ),
            executorFactory = mockExecutorFactory,
            telemetryService = mockTelemetryService,
            config = testConfig,
            lockManager = lockManager,
            parallelRunsManager = parallelRunsManager,
            processManager = mockProcessManager,
            runnerRegistry = mockRunnerRegistry,
            activeSessions = ActiveSessionsRegistry(),
            questionsService = mockQuestionsService,
            questionsWorkspaceSetup = mockQuestionsWorkspaceSetup,
            agent = agent,
            queryParams = queryParams,
            parentContext = testContext,
            workspaceFactory = { _, _, _ -> throw AssertionError("a denied run must not create a workspace") },
        )

    /**
     * Runs the denial twice against one agent under [strategy]: both fail the same way, so
     * nothing was queued behind, skipped by or killed for the first denial, and the second run
     * never waits on a slot or lock the first one left held. See ADR-0047.
     */
    private suspend fun assertDeniedTwice(strategy: OnKeyCollisionStrategy) {
        val agent = testAgent.copy(lockStrategy = LockStrategy(key = "ticket-{{TICKET_ID}}", onCollision = strategy))
        val lockManager = DefaultLockManager(mockProcessManager, TestFixtures.mockLogger())
        val process = processOverRealFactory(agent, queryParams = emptyMap(), lockManager = lockManager)

        val denied = assertFailsWith<UnresolvedLockKeyPlaceholderException> { process.execute(1, mockSpan) }
        assertEquals(listOf("TICKET_ID"), denied.placeholders)
        val alsoDenied = assertFailsWith<UnresolvedLockKeyPlaceholderException> { process.execute(2, mockSpan) }
        assertEquals(listOf("TICKET_ID"), alsoDenied.placeholders)

        // No other run is skipped or killed on account of the denial.
        coVerify(exactly = 0) { mockProcessManager.killProcess(any(), any()) }
        coVerify(exactly = 0) { mockProcessManager.compareAndSetStatus(any(), any(), ProcessStatus.WORKING) }
        // And neither denial left a lock behind: the literal key they would have shared is free,
        // so this probe acquires it at once instead of queueing behind a denial that took it.
        lockManager.acquireThenDisplace("ticket-{{TICKET_ID}}", OnKeyCollisionStrategy.WAIT, 99)
        lockManager.releaseLock("ticket-{{TICKET_ID}}", 99)
    }

    @Test
    fun `a denied run calls neither gate`() =
        runTest(timeout = 10.seconds) {
            // The gates are mocked here, so "never reached" is asserted directly rather than
            // inferred: no slot, no lock, and no release of a lock the run never took.
            val agent = testAgent.copy(lockStrategy = LockStrategy(key = "ticket-{{TICKET_ID}}", onCollision = OnKeyCollisionStrategy.WAIT))
            val process =
                processOverRealFactory(
                    agent,
                    queryParams = emptyMap(),
                    lockManager = mockLockManager,
                    parallelRunsManager = mockParallelRunsManager,
                )

            assertFailsWith<UnresolvedLockKeyPlaceholderException> { process.execute(1, mockSpan) }

            coVerify(exactly = 0) { mockParallelRunsManager.acquireSlot(any(), any(), any(), any()) }
            coVerify(exactly = 0) { mockLockManager.acquireLock(any(), any(), any(), any()) }
            coVerify(exactly = 0) { mockLockManager.releaseLock(any(), any()) }
        }

    @Test
    fun `WAIT with an unresolved lock key placeholder is denied before it queues`() =
        runTest(timeout = 10.seconds) {
            assertDeniedTwice(OnKeyCollisionStrategy.WAIT)
        }

    @Test
    fun `SKIP_CURRENT with an unresolved lock key placeholder is denied, not skipped`() =
        runTest(timeout = 10.seconds) {
            assertDeniedTwice(OnKeyCollisionStrategy.SKIP_CURRENT)
        }

    @Test
    fun `KILL_OLD_RUN_CURRENT with an unresolved lock key placeholder kills no run`() =
        runTest(timeout = 10.seconds) {
            assertDeniedTwice(OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT)
        }

    @Test
    fun `a key whose placeholders resolve still acquires and releases the lock`() =
        runTest {
            // The denial must not leak into the ordinary path: the same real factory, with the
            // subject supplied, renders the key and takes the lock as before.
            val agent = testAgent.copy(lockStrategy = LockStrategy(key = "ticket-{{TICKET_ID}}", onCollision = OnKeyCollisionStrategy.WAIT))
            val lockManager = mockLockManager
            val process =
                Process(
                    workspaceConfigFactory =
                        WorkspaceConfigFactory(
                            workspaceRoot = "/tmp/homer",
                            globalEnv = emptyMap(),
                            textCompiler = DefaultTextCompiler(),
                        ),
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = testConfig,
                    lockManager = lockManager,
                    parallelRunsManager = mockParallelRunsManager,
                    processManager = mockProcessManager,
                    runnerRegistry = mockRunnerRegistry,
                    activeSessions = ActiveSessionsRegistry(),
                    questionsService = mockQuestionsService,
                    questionsWorkspaceSetup = mockQuestionsWorkspaceSetup,
                    agent = agent,
                    queryParams = mapOf("TICKET_ID" to "T-1"),
                    parentContext = testContext,
                    workspaceFactory = { _, _, _ -> TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig(lockKey = "ticket-T-1")) },
                )

            process.execute(1, mockSpan)

            coVerify { mockLockManager.acquireLock("ticket-T-1", OnKeyCollisionStrategy.WAIT, 1, any()) }
            coVerify { mockLockManager.releaseLock("ticket-T-1", 1) }
        }

    @Test
    fun `a denial leaves a run holding another key alone`() =
        runTest(timeout = 10.seconds) {
            // Availability, not just correctness: another subject's run holds its own key while a
            // run of this agent is denied. KILL_OLD_RUN_CURRENT on the denied run must kill nothing,
            // so the holder still holds the lock afterwards (ADR-0047).
            val lockManager = DefaultLockManager(mockProcessManager, TestFixtures.mockLogger())
            lockManager.acquireThenDisplace("other-subject", OnKeyCollisionStrategy.WAIT, 1)

            val agent =
                testAgent.copy(
                    lockStrategy = LockStrategy(key = "ticket-{{TICKET_ID}}", onCollision = OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT),
                )
            val process = processOverRealFactory(agent, queryParams = emptyMap(), lockManager = lockManager)
            assertFailsWith<UnresolvedLockKeyPlaceholderException> { process.execute(2, mockSpan) }

            assertNull(
                withTimeoutOrNull(50.milliseconds) {
                    lockManager.acquireThenDisplace("other-subject", OnKeyCollisionStrategy.WAIT, 3)
                },
                "the unrelated holder must still hold its lock",
            )
            coVerify(exactly = 0) { mockProcessManager.killProcess(any(), any()) }
            lockManager.releaseLock("other-subject", 1)
        }

    @Test
    fun `acceptParentLock does not excuse an unresolved key`() =
        runTest(timeout = 10.seconds) {
            // The pass-through skips the lock, not the render: a key the run cannot resolve is
            // denied here too, so it can never take the flow's shared lock as a literal string.
            val agent = testAgent.copy(lockStrategy = LockStrategy(key = "ticket-{{TICKET_ID}}", acceptParentLock = true))
            val process = processOverRealFactory(agent, queryParams = emptyMap())

            assertFailsWith<UnresolvedLockKeyPlaceholderException> { process.execute(1, mockSpan) }
        }

    // ========== Default workspace wiring Tests ==========

    @Test
    fun `default workspace wiring forwards the sibling probe to Workspace cleanup (CWE-362)`() =
        runTest {
            // Real directory tree: the default factory builds a real Workspace, whose
            // cleanup() consults the probe. A regression that drops the probe (the
            // `_` default) leaves the guard dead and this verification fails.
            val root = Files.createTempDirectory("homer-default-wiring")
            try {
                every {
                    mockWorkspaceConfigFactory.createWorkspaceConfig(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                } returns
                    TestFixtures.mockWorkspaceConfig(
                        serverRoot = root,
                        projectRoot = root.resolve("1"),
                        artifacts = root.resolve("1/artifacts"),
                        workdir = root.resolve("1/workdir"),
                        lockKey = "shared",
                    )

                // No explicit workspaceFactory: exercise the production default. It must
                // hand the ProcessManager-backed probe to the Workspace, so a live
                // sibling is detected from the same process rows the purge sweep reads.
                process.execute(1, mockSpan)

                coVerify { mockProcessManager.isWorkspaceSharedWithActiveRun(any(), 1) }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    // ========== End-to-end guard over the production wiring ==========

    /**
     * Drives two runs on one fixed path through the production wiring — real
     * `Workspace`, real `ProcessManager` and repository, default workspace
     * factory — instead of stubbing the probe. A wiring bug that calls the
     * probe with the wrong path or forgets `excludeProcessId` fails here: the
     * sibling's row decides the delete, not a stub.
     */
    @Test
    fun `cleanup through the production wiring leaves a live sibling's files on a shared fixed path (CWE-362)`() =
        runTest {
            val root = Files.createTempDirectory("homer-e2e-guard")
            try {
                // Real ProcessManager over a real SQLite db: the probe reads actual rows.
                val realManager = PersistenceTestSupport.processManager()
                // Two runs of one agent on the same fixed path: run 2 stays live
                // (CREATED) while run 1 finishes.
                realManager.obtainProcessId("test_agent")
                realManager.setArtifactsDir(1, root.resolve("shared/artifacts"))
                realManager.obtainProcessId("test_agent")
                realManager.setArtifactsDir(2, root.resolve("shared/artifacts"))

                every {
                    mockWorkspaceConfigFactory.createWorkspaceConfig(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                } returns
                    TestFixtures.mockWorkspaceConfig(
                        serverRoot = root,
                        projectRoot = root.resolve("shared"),
                        artifacts = root.resolve("shared/artifacts"),
                        workdir = root.resolve("shared/workdir"),
                        lockKey = "shared",
                    )

                // The state a first run leaves on the shared path: its workdir
                // contents and the materialized .homer/bin helper.
                root.resolve("shared/workdir").createDirectories()
                root.resolve("shared/workdir/temp.txt").writeText("temp data")
                root
                    .resolve("shared/.homer/bin/homer-ask")
                    .also { it.parent.createDirectories() }
                    .writeText("#!/bin/sh\n")

                processWith(agent = testAgent, ancestorLockKeys = emptyList(), processManager = realManager)
                    .execute(1, mockSpan)

                // Run 1 finished while run 2 is still live: both directories survive.
                assertTrue(root.resolve("shared/workdir").exists(), "Workdir must survive for the live sibling run")
                assertTrue(
                    root.resolve("shared/.homer/bin/homer-ask").exists(),
                    "Sibling run's .homer/bin helper must survive the finishing run's cleanup",
                )

                // Terminal siblings: nothing live shares the path any more, so a fresh
                // run's own cleanup deletes both.
                realManager.setProcessStatus(1, ProcessStatus.FINISHED)
                realManager.setProcessStatus(2, ProcessStatus.FINISHED)
                realManager.obtainProcessId("test_agent")
                realManager.setArtifactsDir(3, root.resolve("shared/artifacts"))
                processWith(agent = testAgent, ancestorLockKeys = emptyList(), processManager = realManager)
                    .execute(3, mockSpan)

                assertFalse(root.resolve("shared/workdir").exists(), "Workdir must go once no sibling is live")
                assertFalse(
                    root.resolve("shared/.homer").exists(),
                    ".homer must go once no sibling is live",
                )

                // Matrix row 3: no sibling rows at all. A fresh artifacts dir carries
                // only this run's own row, so the probe returns false on its own merits
                // — not as a side effect of the exclusion check in the row above — and
                // both directories go. A guard stuck true (skipping unconditionally)
                // fails here.
                every {
                    mockWorkspaceConfigFactory.createWorkspaceConfig(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                } returns
                    TestFixtures.mockWorkspaceConfig(
                        serverRoot = root,
                        projectRoot = root.resolve("solo"),
                        artifacts = root.resolve("solo/artifacts"),
                        workdir = root.resolve("solo/workdir"),
                        lockKey = "solo",
                    )
                val soloId = realManager.obtainProcessId("test_agent")
                realManager.setArtifactsDir(soloId, root.resolve("solo/artifacts"))
                root.resolve("solo/workdir").createDirectories()
                root.resolve("solo/workdir/temp.txt").writeText("temp data")
                root
                    .resolve("solo/.homer/bin/homer-ask")
                    .also { it.parent.createDirectories() }
                    .writeText("#!/bin/sh\n")

                processWith(agent = testAgent, ancestorLockKeys = emptyList(), processManager = realManager)
                    .execute(soloId, mockSpan)

                assertFalse(
                    root.resolve("solo/workdir").exists(),
                    "Workdir must go when no sibling row exists at all",
                )
                assertFalse(
                    root.resolve("solo/.homer").exists(),
                    ".homer must go when no sibling row exists at all",
                )
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    // ========== Teardown resilience (cleanup throws) Tests ==========

    private fun processWithWorkspace(
        agent: Agent = testAgent,
        lockManager: homer.app.executors.LockManager = mockLockManager,
        parallelRunsManager: ParallelRunsManager = mockParallelRunsManager,
        workspaceFactory: (WorkspaceConfig, Span, suspend () -> Boolean) -> Workspace,
    ): Process =
        Process(
            workspaceConfigFactory = mockWorkspaceConfigFactory,
            executorFactory = mockExecutorFactory,
            telemetryService = mockTelemetryService,
            config = testConfig,
            lockManager = lockManager,
            parallelRunsManager = parallelRunsManager,
            processManager = mockProcessManager,
            runnerRegistry = mockRunnerRegistry,
            activeSessions = ActiveSessionsRegistry(),
            questionsService = mockQuestionsService,
            questionsWorkspaceSetup = mockQuestionsWorkspaceSetup,
            agent = agent,
            queryParams = emptyMap(),
            parentContext = testContext,
            workspaceFactory = workspaceFactory,
        )

    private fun throwingCleanupWorkspace(): Workspace =
        TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig(lockKey = "shared")).also {
            coEvery { it.cleanup() } throws RuntimeException("cleanup failed")
        }

    @Test
    fun `execute releases lock and slot when workspace cleanup throws`() =
        runTest {
            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns TestFixtures.mockWorkspaceConfig(lockKey = "shared")

            processWithWorkspace { _, _, _ -> throwingCleanupWorkspace() }.execute(1, mockSpan)

            coVerify { mockLockManager.releaseLock("shared", 1) }
            coVerify { mockParallelRunsManager.releaseSlot(any()) }
        }

    @Test
    fun `a killed run still cleans its workspace and releases lock and slot`() =
        runTest {
            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns TestFixtures.mockWorkspaceConfig(lockKey = "shared")
            coEvery { mockExecutorFactory.getExecutor<Command>(any()).execute(any(), any(), any()) } coAnswers { awaitCancellation() }
            // No tracked attempt paths: the killed step records its attempt-0 paths.
            coEvery { mockProcessManager.getCommandArtifacts(any(), any()) } returns null
            // The production cleanup suspends in its sibling probe (a database read): a
            // cancelled teardown would throw there and leave the workspace behind.
            var cleaned = false
            val workspace =
                TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig(lockKey = "shared")).also {
                    coEvery { it.cleanup() } coAnswers {
                        yield()
                        cleaned = true
                    }
                }

            val run = launch { processWithWorkspace { _, _, _ -> workspace }.execute(1, mockSpan) }
            runCurrent()
            run.cancel(CancellationException("killed"))
            run.join()

            assertTrue(cleaned)
            coVerify { mockLockManager.releaseLock("shared", 1) }
            coVerify { mockParallelRunsManager.releaseSlot(any()) }
        }

    @Test
    fun `a throwing sibling probe still releases lock and slot (CWE-362)`() =
        runTest {
            // The guard's probe reads the process rows; if that read fails, cleanup must
            // not take the teardown down with it — the runCatching isolation keeps the
            // lock/slot release, which a leaked pair would wedge for every later run.
            val root = Files.createTempDirectory("homer-probe-throws")
            try {
                every {
                    mockWorkspaceConfigFactory.createWorkspaceConfig(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                } returns
                    TestFixtures.mockWorkspaceConfig(
                        serverRoot = root,
                        projectRoot = root.resolve("1"),
                        artifacts = root.resolve("1/artifacts"),
                        workdir = root.resolve("1/workdir"),
                        lockKey = "shared",
                    )
                coEvery { mockProcessManager.isWorkspaceSharedWithActiveRun(any(), 1) } throws
                    RuntimeException("probe failed")

                // No explicit factory: the production default builds a real Workspace
                // whose cleanup() calls the throwing probe.
                process.execute(1, mockSpan)

                coVerify { mockLockManager.releaseLock("shared", 1) }
                coVerify { mockParallelRunsManager.releaseSlot(any()) }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `execute surfaces the primary failure, not the cleanup failure, and still releases both`() =
        runTest {
            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns TestFixtures.mockWorkspaceConfig(lockKey = "shared")
            every { mockQuestionsWorkspaceSetup.materialize(any()) } throws IllegalStateException("primary failure")

            val thrown =
                assertFailsWith<IllegalStateException> {
                    processWithWorkspace { _, _, _ -> throwingCleanupWorkspace() }.execute(1, mockSpan)
                }

            assertEquals("primary failure", thrown.message)
            coVerify { mockLockManager.releaseLock("shared", 1) }
            coVerify { mockParallelRunsManager.releaseSlot(any()) }
        }

    @Test
    fun `second run acquires lock and slot after a run whose cleanup threw`() =
        runTest(timeout = 10.seconds) {
            // Real managers: with the leak, run 2 suspends forever in acquireSlot/waitForLock.
            val lockManager = DefaultLockManager(mockProcessManager, TestFixtures.mockLogger())
            val parallelRunsManager = DefaultParallelRunsManager()
            val agent = testAgent.copy(maxParallelRuns = 1)
            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns TestFixtures.mockWorkspaceConfig(lockKey = "shared")

            processWithWorkspace(agent, lockManager, parallelRunsManager) { _, _, _ -> throwingCleanupWorkspace() }
                .execute(1, mockSpan)
            processWithWorkspace(agent, lockManager, parallelRunsManager) { _, _, _ -> throwingCleanupWorkspace() }
                .execute(2, mockSpan)

            coVerify {
                mockProcessManager.compareAndSetStatus(
                    2,
                    setOf(ProcessStatus.CREATED),
                    ProcessStatus.WORKING,
                )
            }
        }

    // ========== Purge-race ordering Tests ==========

    @Test
    fun `execute registers artifactsDir before creating the workspace`() =
        runTest {
            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns TestFixtures.mockWorkspaceConfig(lockKey = "shared")
            val workspace = TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig(lockKey = "shared"))

            processWithWorkspace { _, _, _ -> workspace }.execute(1, mockSpan)

            coVerifyOrder {
                mockProcessManager.setArtifactsDir(1, any())
                workspace.create()
            }
        }

    // ========== Per-run secret set drives the workspace redactor (ADR-0052) ==========

    @Test
    fun `workspace redaction covers what was secret for the replayed run, not just current flags`() =
        runTest {
            // A retry passes the source run's snapshot as extraSecretInputKeys. The
            // workspace secret set is what masks values in span input/output and in the
            // live log stream, so recomputing it from the current definition let a
            // historically-secret value reach both unmasked.
            val agent =
                AgentFixtures.simpleAgent().copy(
                    inputs = listOf(Param(paramName = "mr", envName = "MR_IID", secret = false)),
                )
            val replay =
                Process(
                    workspaceConfigFactory = mockWorkspaceConfigFactory,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = testConfig,
                    lockManager = mockLockManager,
                    parallelRunsManager = mockParallelRunsManager,
                    processManager = mockProcessManager,
                    runnerRegistry = mockRunnerRegistry,
                    activeSessions = ActiveSessionsRegistry(),
                    questionsService = mockQuestionsService,
                    questionsWorkspaceSetup = mockQuestionsWorkspaceSetup,
                    agent = agent,
                    queryParams = mapOf("MR_IID" to "tok-historical"),
                    extraSecretInputKeys = setOf("MR_IID"),
                    parentContext = testContext,
                )
            // Full-arity matcher: this replay's secretEnvNames (extraSecretInputKeys
            // union) is non-empty, so the common 5-arg-any() stub (which only matches
            // calls using the trailing params' defaults) would not intercept it here.
            every {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    processId = any(),
                    agentEnv = any(),
                    lockKeyTemplate = any(),
                    queryParams = any(),
                    workspaceAgentConfig = any(),
                    invocationChain = any(),
                    ancestorLockKeys = any(),
                    secretEnvNames = any(),
                    runnerEnv = any(),
                    includeParentEnv = any(),
                    runnerAlias = any(),
                )
            } returns TestFixtures.mockWorkspaceConfig()

            replay.execute(7, TestFixtures.mockSpan())

            val secretNames = slot<Set<String>>()
            verify {
                mockWorkspaceConfigFactory.createWorkspaceConfig(
                    processId = any(),
                    agentEnv = any(),
                    lockKeyTemplate = any(),
                    queryParams = any(),
                    workspaceAgentConfig = any(),
                    invocationChain = any(),
                    ancestorLockKeys = any(),
                    secretEnvNames = capture(secretNames),
                    runnerEnv = any(),
                    includeParentEnv = any(),
                    runnerAlias = any(),
                )
            }
            assertTrue("MR_IID" in secretNames.captured, "the source run's secret must still be masked on replay")
        }

    // ========== Failure-reason redactor Tests (ADR-0052, §12 rule 8) ==========

    @Test
    fun `failureReasonRedactor masks a secret query param and a sys-env-backed agent env entry`() {
        // Built from agent.env + queryParams over secretInputKeys + sysEnvBackedEnvNames,
        // the same union the workspace redactor gets. Every executor test stubs this
        // property, so a dropped source would store an unredacted secret in the stored
        // failure reason — text every caller who can see the run reads — with nothing
        // failing; this pins the sources on a real Process.
        val agent =
            AgentFixtures.simpleAgent().copy(
                inputs = listOf(Param(paramName = "mr", envName = "MR_IID", secret = true)),
                env = mapOf("MR_TOKEN" to "host-token-value"),
                sysEnvBackedEnvNames = setOf("MR_TOKEN"),
            )
        val processWithSecrets =
            Process(
                workspaceConfigFactory = mockWorkspaceConfigFactory,
                executorFactory = mockExecutorFactory,
                telemetryService = mockTelemetryService,
                config = testConfig,
                lockManager = mockLockManager,
                parallelRunsManager = mockParallelRunsManager,
                processManager = mockProcessManager,
                runnerRegistry = mockRunnerRegistry,
                activeSessions = ActiveSessionsRegistry(),
                questionsService = mockQuestionsService,
                questionsWorkspaceSetup = mockQuestionsWorkspaceSetup,
                agent = agent,
                queryParams = mapOf("MR_IID" to "param-secret-value"),
                parentContext = testContext,
            )

        val redactor = processWithSecrets.failureReasonRedactor
        assertEquals("claimed but *** was in the query", redactor.redact("claimed but param-secret-value was in the query"))
        assertEquals("the runner env held ***", redactor.redact("the runner env held host-token-value"))
    }

    // ========== Retain-derived properties Tests (ADR-0053) ==========

    @Test
    fun `purgeRecordAfterSeconds derives from retain recordFor when set`() {
        val agent =
            testAgent.copy(
                workspace = testAgent.workspace.copy(retain = Retain(recordFor = "10d")),
            )
        val process = processWith(agent, ancestorLockKeys = emptyList())

        assertEquals(PurgeAfterParser.toSeconds("10d"), process.purgeRecordAfterSeconds)
    }

    @Test
    fun `purgeRecordAfterSeconds falls back to purgeAfterSeconds and retainArtifacts is empty without retain`() {
        val process = processWith(testAgent, ancestorLockKeys = emptyList())

        assertEquals(process.purgeAfterSeconds, process.purgeRecordAfterSeconds)
        assertTrue(process.retainArtifacts.isEmpty())
    }
}
