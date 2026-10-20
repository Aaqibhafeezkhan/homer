package homer.app.executors

import homer.app.EnvConstants
import homer.app.config.ExecutionConfig
import homer.app.exceptions.CommandExecutionException
import homer.app.exceptions.MalformedLlmOutputException
import homer.app.exceptions.OutputCapExceededException
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.infra.ShellExecutor
import homer.app.infra.TelemetryService
import homer.app.infra.Workspace
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.CostSource
import homer.app.models.ExecutionInfo
import homer.app.models.ExtraUploadSpec
import homer.app.models.LlmAccounting
import homer.app.models.PodPhase
import homer.app.models.RunnerState
import homer.app.models.When
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.DownloadMode
import homer.app.runners.Runner
import homer.app.runners.RunnerSession
import homer.app.services.ProcessManager
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
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.relativeTo
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for AgentExecutor
 * Tests agent execution orchestration and error handling
 */
class AgentExecutorTest {
    @TempDir
    lateinit var tmp: Path

    private lateinit var mockWorkspace: Workspace
    private lateinit var mockExecutorFactory: CommandExecutorFactory
    private lateinit var mockTelemetryService: TelemetryService
    private lateinit var mockExecutor: CommandExecutor<Command>
    private lateinit var mockProcessManager: ProcessManager
    private lateinit var mockRunner: Runner
    private lateinit var mockSession: RunnerSession
    private lateinit var mockShell: ShellExecutor
    private lateinit var config: ExecutionConfig
    private val testProcessId = 123

    @BeforeEach
    fun setup() {
        mockWorkspace = TestFixtures.mockWorkspace()
        mockExecutorFactory = mockk(relaxed = true)
        mockTelemetryService = TestFixtures.mockTelemetryService()
        mockExecutor = mockk(relaxed = true)
        mockProcessManager = mockk(relaxed = true)
        // Relaxed mocks fabricate a non-null child mock for unstubbed calls returning a
        // nullable type, which breaks Path.relativeTo() in the finally block's fallback
        // (F1). Tests that care about a tracked path override this per-call.
        coEvery { mockProcessManager.getCommandArtifacts(any(), any()) } returns null
        mockShell = mockk(relaxed = true)
        mockSession =
            mockk(relaxed = true) {
                every { shell } returns mockShell
            }
        mockRunner =
            mockk(relaxed = true) {
                coEvery { openSession(any()) } returns mockSession
            }
        config = ExecutionConfig()
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    // ========== Execution Flow Tests ==========

    @Test
    fun `execute should run single command successfully`() =
        runTest {
            val agent = AgentFixtures.simpleAgent()
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)

            val executionInfos = mutableListOf<ExecutionInfo>()
            coEvery { mockProcessManager.completeStep(any(), capture(executionInfos), any(), any()) } just Runs

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            executor.execute()

            coVerify(exactly = 1) { mockExecutor.execute(any(), any(), any()) }
            verify { span.setStatus(StatusCode.OK) }
            verify { span.end() }
            assertEquals("cmd", executionInfos.single().kind)
        }

    @Test
    fun `command start registers its attempt-0 artifacts with the command's kind`() =
        runTest {
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)

            AgentExecutor(
                agent = AgentFixtures.simpleAgent(),
                workspace = mockWorkspace,
                executorFactory = mockExecutorFactory,
                telemetryService = mockTelemetryService,
                config = config,
                parentContext = Context.current(),
                stepRecords = mockProcessManager,
                processId = testProcessId,
                runner = mockRunner,
                activeSessions = ActiveSessionsRegistry(),
            ).execute()

            coVerify {
                mockProcessManager.setCommandArtifacts(
                    testProcessId,
                    0,
                    match { it.kind == "cmd" && it.attemptIndex == 0 && it.stdOut.fileName.toString() == "cmd_0.out" },
                )
            }
        }

    @Test
    fun `execute stamps session metadata onto command spans`() =
        runTest {
            val agent = AgentFixtures.simpleAgent()
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)
            every { mockSession.metadata } returns mapOf("pod.name" to "homer-123-abc")

            AgentExecutor(
                agent = agent,
                workspace = mockWorkspace,
                executorFactory = mockExecutorFactory,
                telemetryService = mockTelemetryService,
                config = config,
                parentContext = Context.current(),
                stepRecords = mockProcessManager,
                processId = testProcessId,
                runner = mockRunner,
                activeSessions = ActiveSessionsRegistry(),
            ).execute()

            verify { span.setAttribute("pod.name", "homer-123-abc") }
        }

    @Test
    fun `execute should run multiple commands sequentially`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent()
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            executor.execute()

            coVerify(exactly = 3) { mockExecutor.execute(any(), any(), any()) }
        }

    @Test
    fun `execute should stop on first command failure`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent() // Has 3 commands
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor

            // First command succeeds, second fails
            coEvery { mockExecutor.execute(any(), any(), any()) } returnsMany
                listOf(
                    TestFixtures.mockCommandResult(exitCode = 0),
                    TestFixtures.mockCommandResult(exitCode = 1),
                )

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            assertThrows<CommandExecutionException> {
                executor.execute()
            }

            // Should execute only 2 commands (first success, second failure, third not executed)
            coVerify(exactly = 2) { mockExecutor.execute(any(), any(), any()) }
        }

    @Test
    fun `execute should propagate exception on failure`() =
        runTest {
            val agent = AgentFixtures.simpleAgent()
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 1)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            val exception =
                assertThrows<CommandExecutionException> {
                    executor.execute()
                }

            assertEquals(1, exception.exitCode)
            verify { span.setStatus(StatusCode.ERROR, any()) }
            verify { span.recordException(exception) }
        }

    // ========== Environment Management Tests ==========

    @Test
    fun `execute should use workspace config env`() =
        runTest {
            val agent = AgentFixtures.simpleAgent()
            val span = TestFixtures.mockSpan()
            val testEnv = mapOf("PARAM1" to "value1", "PARAM2" to "value2")
            val workspaceWithEnv =
                TestFixtures.mockWorkspace(
                    config = TestFixtures.mockWorkspaceConfig(env = testEnv),
                )

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = workspaceWithEnv,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            executor.execute()

            // Verify that executor was called (env is verified through workspace mock)
            coVerify(exactly = 1) {
                mockExecutor.execute(any(), any(), any())
            }
        }

    @Test
    fun `execute should pass workspace config env to each command`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent()
            val span = TestFixtures.mockSpan()
            val testEnv = mapOf("QUERY_PARAM" to "query_value")
            val workspaceWithEnv =
                TestFixtures.mockWorkspace(
                    config = TestFixtures.mockWorkspaceConfig(env = testEnv),
                )

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = workspaceWithEnv,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            executor.execute()

            // Verify that executor was called 3 times (env is verified through workspace mock)
            coVerify(exactly = 3) {
                mockExecutor.execute(any(), any(), any())
            }
        }

    // ========== Telemetry Tests ==========

    @Test
    fun `execute should create span for each command`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent() // 3 commands
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            executor.execute()

            verify(exactly = 3) { mockTelemetryService.tracer.spanBuilder(any()) }
            verify(exactly = 3) { span.end() }
            // The span is named after the command's INDEX, so the step's own name has to ride
            // along as an attribute — for a script step it is the only place it appears at all.
            verify { span.setAttribute("command.label", "cmd") }
            verify { span.setAttribute("command.label", "script") }
            verify { span.setAttribute("command.label", "claude") }
        }

    @Test
    fun `execute should set command type attribute`() =
        runTest {
            val agent = AgentFixtures.simpleAgent()
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            executor.execute()

            verify { span.setAttribute("cmd.type", "Cmd") }
        }

    @Test
    fun `execute should set exit code attribute`() =
        runTest {
            val agent = AgentFixtures.simpleAgent()
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            executor.execute()

            verify { span.setAttribute("output.exitCode", "0") }
        }

    @Test
    fun `execute should set span status to OK on success`() =
        runTest {
            val agent = AgentFixtures.simpleAgent()
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            executor.execute()

            verify { span.setStatus(StatusCode.OK) }
        }

    @Test
    fun `execute should set span status to ERROR on failure`() =
        runTest {
            val agent = AgentFixtures.simpleAgent()
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 1)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            assertThrows<CommandExecutionException> {
                executor.execute()
            }

            verify { span.setStatus(StatusCode.ERROR, any()) }
        }

    @Test
    fun `execute should record exception in span`() =
        runTest {
            val agent = AgentFixtures.simpleAgent()
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 1)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            assertThrows<CommandExecutionException> {
                executor.execute()
            }

            verify { span.recordException(any<CommandExecutionException>()) }
        }

    @Test
    fun `execute should end span after each command`() =
        runTest {
            val agent = AgentFixtures.simpleAgent()
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            executor.execute()

            verify { span.end() }
        }

    // ========== Execution Context Tests ==========

    @Test
    fun `execute should create ExecutionContext for each command with correct stdout stderr paths`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent() // 3 commands
            val span = TestFixtures.mockSpan()
            val artifactsPath = mockWorkspace.config.artifacts

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            executor.execute()

            // Verify ExecutionContext is created with correct paths for each command
            coVerifyOrder {
                mockExecutor.execute(
                    any(),
                    match {
                        it.workspace == mockWorkspace &&
                            it.stdOut == artifactsPath.resolve("cmd_0.out") &&
                            it.stdErr == artifactsPath.resolve("cmd_0.err")
                    },
                    any(),
                )
                mockExecutor.execute(
                    any(),
                    match {
                        it.workspace == mockWorkspace &&
                            it.stdOut == artifactsPath.resolve("cmd_1.out") &&
                            it.stdErr == artifactsPath.resolve("cmd_1.err")
                    },
                    any(),
                )
                mockExecutor.execute(
                    any(),
                    match {
                        it.workspace == mockWorkspace &&
                            it.stdOut == artifactsPath.resolve("cmd_2.out") &&
                            it.stdErr == artifactsPath.resolve("cmd_2.err")
                    },
                    any(),
                )
            }
        }

    @Test
    fun `execute forwards its owner into each command's ExecutionContext`() =
        runTest {
            val agent = AgentFixtures.simpleAgent()
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                    owner = "ci",
                )

            executor.execute()

            coVerify { mockExecutor.execute(any(), match { it.owner == "ci" }, any()) }
        }

    // ========== Error Handling Tests ==========

    @Test
    fun `execute should throw CommandExecutionException on non-zero exit code`() =
        runTest {
            val agent = AgentFixtures.simpleAgent()
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 42)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            val exception =
                assertThrows<CommandExecutionException> {
                    executor.execute()
                }

            assertTrue(exception.message!!.contains("Execution failed"))
        }

    @Test
    fun `execute should include exit code in exception`() =
        runTest {
            val agent = AgentFixtures.simpleAgent()
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 127)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            val exception =
                assertThrows<CommandExecutionException> {
                    executor.execute()
                }

            assertEquals(127, exception.exitCode)
        }

    @Test
    fun `execute should include command details in exception`() =
        runTest {
            val agent = AgentFixtures.simpleAgent()
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 1)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            val exception =
                assertThrows<CommandExecutionException> {
                    executor.execute()
                }

            assertTrue(exception.command.contains("Cmd"))
        }

    // ========== Continue If Fail Tests ==========

    @Test
    fun `execute should continue on failure when continueIfFail is true`() =
        runTest {
            val agent = AgentFixtures.agentWithContinueIfFail() // 3 commands with continueIfFail=true
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor

            // First command fails, but execution should continue
            coEvery { mockExecutor.execute(any(), any(), any()) } returnsMany
                listOf(
                    TestFixtures.mockCommandResult(exitCode = 1),
                    TestFixtures.mockCommandResult(exitCode = 0),
                    TestFixtures.mockCommandResult(exitCode = 0),
                )

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            // Should NOT throw exception
            executor.execute()

            // All 3 commands should be executed
            coVerify(exactly = 3) { mockExecutor.execute(any(), any(), any()) }
            verify(exactly = 3) { span.setStatus(StatusCode.OK) }
        }

    @Test
    fun `execute should stop on failure when continueIfFail is false`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent() // 3 commands with continueIfFail=false (default)
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor

            // Second command fails
            coEvery { mockExecutor.execute(any(), any(), any()) } returnsMany
                listOf(
                    TestFixtures.mockCommandResult(exitCode = 0),
                    TestFixtures.mockCommandResult(exitCode = 1),
                )

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            assertThrows<CommandExecutionException> {
                executor.execute()
            }

            // Only 2 commands should be executed (third not executed due to failure)
            coVerify(exactly = 2) { mockExecutor.execute(any(), any(), any()) }
        }

    // ========== finishExitCode Tests ==========

    @Test
    fun `execute should finish early when command exits with finishExitCode`() =
        runTest {
            val agent = AgentFixtures.agentWithFinishExitCode(finishExitCode = 128)
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 128)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            // Should NOT throw — job finishes cleanly
            executor.execute()

            // Only first command executed, second skipped
            coVerify(exactly = 1) { mockExecutor.execute(any(), any(), any()) }
            verify { span.setStatus(StatusCode.OK) }
        }

    @Test
    fun `execute should continue when finishExitCode matches but continueIfFail is true`() =
        runTest {
            val agent = AgentFixtures.agentWithFinishExitCodeAndContinueIfFail(finishExitCode = 128)
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returnsMany
                listOf(
                    TestFixtures.mockCommandResult(exitCode = 128),
                    TestFixtures.mockCommandResult(exitCode = 0),
                )

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            executor.execute()

            // Both commands executed — continueIfFail bypasses early finish
            coVerify(exactly = 2) { mockExecutor.execute(any(), any(), any()) }
        }

    @Test
    fun `execute should fail when non-zero exit code does not match finishExitCode`() =
        runTest {
            val agent = AgentFixtures.agentWithFinishExitCode(finishExitCode = 128)
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 1)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            val exception =
                assertThrows<CommandExecutionException> {
                    executor.execute()
                }

            assertEquals(1, exception.exitCode)
        }

    // ========== Result to Env Mapping Tests ==========

    @Test
    fun `execute should call workspace appendToEnv with mapped env vars`() =
        runTest {
            val agent = AgentFixtures.simpleAgent()
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            executor.execute()

            // Verify workspace.appendToEnv is called (even if with empty map for commands without resultEnvParam)
            verify(atLeast = 1) { mockWorkspace.appendToEnv(any()) }
        }

    @Test
    fun `execute should set output env span attribute`() =
        runTest {
            val agent = AgentFixtures.simpleAgent()
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            executor.execute()

            // Verify output.env attribute is set
            verify { span.setAttribute("output.env", any<String>()) }
        }

    // ========== Stdout/Stderr File Creation Tests ==========

    @Test
    fun `execute should pass stdout and stderr paths with correct pattern to ExecutionContext`() =
        runTest {
            val agent = AgentFixtures.simpleAgent()
            val span = TestFixtures.mockSpan()
            val artifactsPath = mockWorkspace.config.artifacts

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            executor.execute()

            // Verify ExecutionContext receives correct stdout/stderr paths
            coVerify {
                mockExecutor.execute(
                    any(),
                    match {
                        it.stdOut == artifactsPath.resolve("cmd_0.out") &&
                            it.stdErr == artifactsPath.resolve("cmd_0.err")
                    },
                    any(),
                )
            }
        }

    @Test
    fun `execute should create sequential stdout stderr paths for multiple commands`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent() // 3 commands
            val span = TestFixtures.mockSpan()
            val artifactsPath = mockWorkspace.config.artifacts

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            executor.execute()

            // Verify sequential path patterns for all commands
            coVerifyOrder {
                mockExecutor.execute(
                    any(),
                    match {
                        it.stdOut.fileName.toString() == "cmd_0.out" &&
                            it.stdErr.fileName.toString() == "cmd_0.err"
                    },
                    any(),
                )
                mockExecutor.execute(
                    any(),
                    match {
                        it.stdOut.fileName.toString() == "cmd_1.out" &&
                            it.stdErr.fileName.toString() == "cmd_1.err"
                    },
                    any(),
                )
                mockExecutor.execute(
                    any(),
                    match {
                        it.stdOut.fileName.toString() == "cmd_2.out" &&
                            it.stdErr.fileName.toString() == "cmd_2.err"
                    },
                    any(),
                )
            }
        }

    @Test
    fun `execute records the executor-returned result's actual paths in ExecutionInfo`() =
        runTest {
            // Simulates a Claude retry whose winning attempt wrote to a different file
            // than the attempt-0 path AgentExecutor built the ExecutionContext with
            // (per-attempt artifactNameTemplate) — ExecutionInfo must reflect where the
            // output actually landed, not the attempt-0 path.
            val agent = AgentFixtures.simpleAgent()
            val span = TestFixtures.mockSpan()
            val artifactsPath = mockWorkspace.config.artifacts
            val winningStdOut = artifactsPath.resolve("cmd_0_a1.out")
            val winningStdErr = artifactsPath.resolve("cmd_0_a1.err")

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns
                TestFixtures.mockCommandResult(exitCode = 0, stdOut = winningStdOut, stdErr = winningStdErr)

            val executionInfos = mutableListOf<ExecutionInfo>()
            coEvery { mockProcessManager.completeStep(any(), capture(executionInfos), any(), any()) } just Runs

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            executor.execute()

            assertEquals(1, executionInfos.size)
            assertEquals(winningStdOut.relativeTo(artifactsPath), executionInfos[0].stdOut)
            assertEquals(winningStdErr.relativeTo(artifactsPath), executionInfos[0].stdErr)
        }

    @Test
    fun `llmAccounting on the CommandResult lands on the ExecutionInfo`() =
        runTest {
            // Same arrangement as the "actual paths" test above, but the returned
            // CommandResult also carries llmAccounting, which must ride onto the
            // captured ExecutionInfo unchanged.
            val accounting = LlmAccounting(sessionId = "s1", costUsd = 0.2, source = CostSource.CLI, fallbackReason = "http 404")
            val agent = AgentFixtures.simpleAgent()
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns
                TestFixtures.mockCommandResult(exitCode = 0).copy(llmAccounting = accounting)

            val executionInfos = mutableListOf<ExecutionInfo>()
            coEvery { mockProcessManager.completeStep(any(), capture(executionInfos), any(), any()) } just Runs

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            executor.execute()

            assertEquals(1, executionInfos.size)
            assertEquals(accounting, executionInfos[0].llmAccounting)
        }

    @Test
    fun `execute records the attempt-0 paths in ExecutionInfo when no CommandResult exists`() =
        runTest {
            // An EvalError throws before executor.execute ever runs — no CommandResult
            // exists, so ExecutionInfo must fall back to the attempt-0 paths.
            val agent =
                Agent(
                    name = "eval_error_paths_test",
                    commands =
                        listOf(
                            Command.Cmd(
                                cmd = listOf("echo", "first"),
                                timeout = 60,
                                whenClause = When(When.Operator.ALL, listOf("syntax-error-expr")),
                            ),
                        ),
                    env = emptyMap(),
                )
            val span = TestFixtures.mockSpan()
            val artifactsPath = mockWorkspace.config.artifacts

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockShell.cmd(any(), any(), any(), any(), any(), any()) } answers {
                TestFixtures.mockCommandResult(exitCode = 2, stdOut = arg(1), stdErr = arg(2))
            }

            val executionInfos = mutableListOf<ExecutionInfo>()
            coEvery { mockProcessManager.completeStep(any(), capture(executionInfos), any(), any()) } just Runs

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            assertThrows<CommandExecutionException> { executor.execute() }

            assertEquals(1, executionInfos.size)
            assertEquals(artifactsPath.resolve("cmd_0.out").relativeTo(artifactsPath), executionInfos[0].stdOut)
            assertEquals(artifactsPath.resolve("cmd_0.err").relativeTo(artifactsPath), executionInfos[0].stdErr)
        }

    @Test
    fun `execute records the tracked per-attempt paths in ExecutionInfo when the executor throws without returning a result`() =
        runTest {
            // Simulates a MalformedLlmOutputException on the final retry attempt: the
            // command executor (e.g. LlmHarnessExecutor) throws instead of
            // returning a CommandResult, so executedResult stays null — but its retry
            // loop already updated the tracked per-attempt artifacts via
            // processManager.setCommandArtifacts before throwing. ExecutionInfo must
            // record that last-attempt path, not the attempt-0 path AgentExecutor built
            // the ExecutionContext with.
            val agent = AgentFixtures.simpleAgent()
            val span = TestFixtures.mockSpan()
            val artifactsPath = mockWorkspace.config.artifacts
            val lastAttemptOut = artifactsPath.resolve("cmd_0_a2.out")
            val lastAttemptErr = artifactsPath.resolve("cmd_0_a2.err")

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } throws MalformedLlmOutputException("still garbage")
            coEvery { mockProcessManager.getCommandArtifacts(testProcessId, 0) } returns
                CommandArtifactPaths(lastAttemptOut, lastAttemptErr)

            val executionInfos = mutableListOf<ExecutionInfo>()
            coEvery { mockProcessManager.completeStep(any(), capture(executionInfos), any(), any()) } just Runs

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            assertThrows<MalformedLlmOutputException> { executor.execute() }

            assertEquals(1, executionInfos.size)
            assertEquals(lastAttemptOut.relativeTo(artifactsPath), executionInfos[0].stdOut)
            assertEquals(lastAttemptErr.relativeTo(artifactsPath), executionInfos[0].stdErr)
        }

    @Test
    fun `execute records ExecutionInfo and ends the span when artifactNameTemplate resolution fails`() =
        runTest {
            // §12 denial path: a rendered-stem validation failure must flow through the
            // same per-command bookkeeping as any other command failure — an
            // ExecutionInfo row, the span ended, currentCommand cleared — not bypass it
            // via the outer session-lifecycle catch/finally.
            val agent = AgentFixtures.simpleAgent().copy(artifactNameTemplate = "{{MISSING}}")
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span

            val executionInfos = mutableListOf<ExecutionInfo>()
            coEvery { mockProcessManager.completeStep(any(), capture(executionInfos), any(), any()) } just Runs

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            val exception = assertThrows<CommandExecutionException> { executor.execute() }
            assertTrue(exception.message!!.contains("artifactNameTemplate"))

            // The command executor itself must never be reached.
            coVerify(exactly = 0) { mockExecutorFactory.getExecutor<Command>(any()) }
            coVerify(exactly = 0) { mockExecutor.execute(any(), any(), any()) }

            assertEquals(1, executionInfos.size)
            assertEquals(-1, executionInfos[0].resultCode)
            assertTrue(executionInfos[0].errMsg!!.contains("artifactNameTemplate"))
            assertEquals(false, executionInfos[0].skipped)
            verify { span.end() }
            verify { span.setStatus(StatusCode.ERROR, any()) }
            coVerify { mockProcessManager.completeStep(testProcessId, any(), null, null) }
        }

    @Test
    fun `execute rejects an artifactNameTemplate referencing a secret-classified env var without leaking its value`() =
        runTest {
            // The rendered stem becomes a server-local file name, so a
            // template referencing a secret-classified var must fail closed (§12) — and
            // neither the exception nor the recorded ExecutionInfo may carry the value.
            val agent = AgentFixtures.simpleAgent().copy(artifactNameTemplate = "{{SECRET_VAR}}")
            val span = TestFixtures.mockSpan()
            val workspaceWithSecret =
                TestFixtures.mockWorkspace(
                    config =
                        TestFixtures.mockWorkspaceConfig(
                            env = mapOf("SECRET_VAR" to "s3cr3t-value"),
                            secretEnvNames = setOf("SECRET_VAR"),
                        ),
                )

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span

            val executionInfos = mutableListOf<ExecutionInfo>()
            coEvery { mockProcessManager.completeStep(any(), capture(executionInfos), any(), any()) } just Runs

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = workspaceWithSecret,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            val exception = assertThrows<CommandExecutionException> { executor.execute() }
            assertTrue(exception.message!!.contains("artifactNameTemplate"))
            assertTrue(!exception.message!!.contains("s3cr3t-value"))

            coVerify(exactly = 0) { mockExecutorFactory.getExecutor<Command>(any()) }

            assertEquals(1, executionInfos.size)
            assertTrue(!executionInfos[0].errMsg!!.contains("s3cr3t-value"))
        }

    @Test
    fun `execute rejects an artifactNameTemplate whose stem embeds a derived secret value reaching a non-classified var`() =
        runTest {
            // Layer 1 (name exclusion) does not fire here: STEM_VAR is not itself
            // secret-classified, so its value renders through unfiltered. Only layer 2
            // — the SecretRedactor built from workspace.config.secretEnvNames and wired
            // into ArtifactNameResolver at the AgentExecutor call site — can catch this.
            // If that wiring is ever dropped, this is the test that fails, not the
            // sibling above (whose secret var is both in env AND secretEnvNames, so
            // layer 1 alone would still pass it).
            val secretValue = "s3cr3t-value-long-enough"
            val agent = AgentFixtures.simpleAgent().copy(artifactNameTemplate = "{{STEM_VAR}}")
            val span = TestFixtures.mockSpan()
            val workspaceWithDerivedSecret =
                TestFixtures.mockWorkspace(
                    config =
                        TestFixtures.mockWorkspaceConfig(
                            env = mapOf("REAL_SECRET" to secretValue, "STEM_VAR" to secretValue),
                            secretEnvNames = setOf("REAL_SECRET"),
                        ),
                )

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span

            val executionInfos = mutableListOf<ExecutionInfo>()
            coEvery { mockProcessManager.completeStep(any(), capture(executionInfos), any(), any()) } just Runs

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = workspaceWithDerivedSecret,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            val exception = assertThrows<CommandExecutionException> { executor.execute() }
            assertTrue(exception.message!!.contains("artifactNameTemplate"))
            assertTrue(!exception.message!!.contains(secretValue))

            coVerify(exactly = 0) { mockExecutorFactory.getExecutor<Command>(any()) }

            assertEquals(1, executionInfos.size)
            assertTrue(!executionInfos[0].errMsg!!.contains(secretValue))
        }

    @Test
    fun `execute runs only the selected command when commandIndices is set`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent() // 3 commands: Cmd, Script, Claude
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                    commandIndices = listOf(1),
                )

            executor.execute()

            coVerify(exactly = 1) { mockExecutor.execute(any(), any(), any()) }
            // The only executor invocation should be for the Script command at index 1
            coVerify(exactly = 1) {
                mockExecutor.execute(
                    match { it is Command.Script },
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun `execute runs nothing when commandIndices is empty`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent()
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                    commandIndices = emptyList(),
                )

            executor.execute()

            coVerify(exactly = 0) { mockExecutor.execute(any(), any(), any()) }
        }

    // ========== Step-boundary Tests (one write, one event per boundary) ==========

    @Test
    fun `each successful boundary makes exactly one completeStep call and one setCurrentCommand for the run`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent() // 3 commands
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)

            val executionInfos = mutableListOf<ExecutionInfo>()
            coEvery { mockProcessManager.completeStep(any(), capture(executionInfos), any(), any()) } just Runs

            AgentExecutor(
                agent = agent,
                workspace = mockWorkspace,
                executorFactory = mockExecutorFactory,
                telemetryService = mockTelemetryService,
                config = config,
                parentContext = Context.current(),
                stepRecords = mockProcessManager,
                processId = testProcessId,
                runner = mockRunner,
                activeSessions = ActiveSessionsRegistry(),
            ).execute()

            // One write per step boundary — not a split addExecution + clear pair.
            coVerify(exactly = 3) { mockProcessManager.completeStep(any(), any(), any(), any()) }
            // Only the run's first step is set up front; the rest ride a prior boundary.
            coVerify(exactly = 1) { mockProcessManager.setCurrentCommand(testProcessId, any(), any()) }
            assertEquals(listOf("cmd", "script", "claude"), executionInfos.map { it.label })
            // The last boundary clears the command; no step follows it.
            val last = slot<ExecutionInfo>()
            coVerify { mockProcessManager.completeStep(testProcessId, capture(last), null, null) }
            assertEquals("claude", last.captured.label)
        }

    @Test
    fun `a step boundary hands the next step's label and start to completeStep`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent()
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)

            AgentExecutor(
                agent = agent,
                workspace = mockWorkspace,
                executorFactory = mockExecutorFactory,
                telemetryService = mockTelemetryService,
                config = config,
                parentContext = Context.current(),
                stepRecords = mockProcessManager,
                processId = testProcessId,
                runner = mockRunner,
                activeSessions = ActiveSessionsRegistry(),
            ).execute()

            // The first boundary names the second step; its recorded start is the one
            // the second step's own ExecutionInfo carries (the same clock reading).
            val firstStart = slot<Long>()
            coVerify {
                mockProcessManager.completeStep(
                    testProcessId,
                    match { it.label == "cmd" },
                    nextLabel = "script",
                    nextStart = capture(firstStart),
                )
            }
            val secondStart = slot<ExecutionInfo>()
            coVerify { mockProcessManager.completeStep(testProcessId, capture(secondStart), "claude", any()) }
            assertEquals("script", secondStart.captured.label)
            assertEquals(firstStart.captured, secondStart.captured.start)
        }

    @Test
    fun `a step ending the run clears the current command at its boundary`() =
        runTest {
            // The default agent's single command ends the run: the boundary must clear,
            // never set a phantom next command.
            val span = TestFixtures.mockSpan()
            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)

            AgentExecutor(
                agent = AgentFixtures.simpleAgent(),
                workspace = mockWorkspace,
                executorFactory = mockExecutorFactory,
                telemetryService = mockTelemetryService,
                config = config,
                parentContext = Context.current(),
                stepRecords = mockProcessManager,
                processId = testProcessId,
                runner = mockRunner,
                activeSessions = ActiveSessionsRegistry(),
            ).execute()

            coVerify(exactly = 1) { mockProcessManager.completeStep(testProcessId, any(), null, null) }
        }

    @Test
    fun `a step whose command throws clears the current command at its boundary even with continueIfFail`() =
        runTest {
            // continueIfFail covers a non-zero exit code, which reaches the outcome as a
            // Success — not a command that throws. A CancellationException (a runner-enforced
            // timeout), an EvalError or an OutputCapExceededException is rethrown
            // unconditionally, so the run ends and the boundary must clear, never hand the
            // next step over as the current command.
            val agent = AgentFixtures.agentWithContinueIfFail() // 3 steps, all continueIfFail=true
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            // The thrown CancellationException is not our job being cancelled, so it
            // propagates as a failure (ProcessExecutor's runner-enforced-timeout case).
            coEvery { mockExecutor.execute(any(), any(), any()) } throws CancellationException("ttl exceeded")

            val executionInfos = mutableListOf<ExecutionInfo>()
            coEvery { mockProcessManager.completeStep(any(), capture(executionInfos), any(), any()) } just Runs

            assertThrows<CancellationException> {
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                ).execute()
            }

            // The failed step is recorded and its boundary clears; the second step
            // must not be announced as the current command.
            coVerify(exactly = 1) { mockProcessManager.completeStep(any(), any(), any(), any()) }
            coVerify { mockProcessManager.completeStep(testProcessId, capture(executionInfos), null, null) }
            assertTrue(!executionInfos[0].skipped, "the failed step is recorded, not skipped")
        }

    @Test
    fun `a finishExitCode boundary clears the current command instead of announcing the next step`() =
        runTest {
            // The first command exits with the agent's finishExitCode, so the run ends
            // there: the boundary must clear, never hand the second step over as the
            // current command of a run that is about to finish.
            val agent = AgentFixtures.agentWithFinishExitCode(finishExitCode = 128)
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 128)

            val executionInfos = mutableListOf<ExecutionInfo>()
            coEvery { mockProcessManager.completeStep(any(), capture(executionInfos), any(), any()) } just Runs

            AgentExecutor(
                agent = agent,
                workspace = mockWorkspace,
                executorFactory = mockExecutorFactory,
                telemetryService = mockTelemetryService,
                config = config,
                parentContext = Context.current(),
                stepRecords = mockProcessManager,
                processId = testProcessId,
                runner = mockRunner,
                activeSessions = ActiveSessionsRegistry(),
            ).execute()

            // The second step never ran; regressing the isFinishCode stop to false
            // would announce it here and call completeStep twice.
            coVerify(exactly = 1) { mockExecutor.execute(any(), any(), any()) }
            coVerify(exactly = 1) { mockProcessManager.completeStep(any(), any(), any(), any()) }
            coVerify { mockProcessManager.completeStep(testProcessId, capture(executionInfos), null, null) }
            assertEquals("cmd", executionInfos[0].label)
        }

    @Test
    fun `a skipped step's boundary hands over to the next step`() =
        runTest {
            val agent =
                Agent(
                    name = "skip_handover_test",
                    commands =
                        listOf(
                            Command.Cmd(
                                cmd = listOf("echo", "first"),
                                timeout = 60,
                                whenClause = When(When.Operator.ALL, listOf("false-expr")),
                            ),
                            Command.Cmd(cmd = listOf("echo", "second"), timeout = 60),
                        ),
                    env = emptyMap(),
                )
            val span = TestFixtures.mockSpan()
            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)
            coEvery { mockShell.cmd(any(), any(), any(), any(), any(), any()) } answers {
                TestFixtures.mockCommandResult(exitCode = 1, stdOut = arg(1), stdErr = arg(2))
            }

            AgentExecutor(
                agent = agent,
                workspace = mockWorkspace,
                executorFactory = mockExecutorFactory,
                telemetryService = mockTelemetryService,
                config = config,
                parentContext = Context.current(),
                stepRecords = mockProcessManager,
                processId = testProcessId,
                runner = mockRunner,
                activeSessions = ActiveSessionsRegistry(),
            ).execute()

            // The skipped step's boundary is a single completeStep that sets the second
            // command as current; the second (final) boundary clears.
            val skipStart = slot<Long>()
            coVerify {
                mockProcessManager.completeStep(
                    testProcessId,
                    match { it.skipped },
                    nextLabel = "cmd",
                    nextStart = capture(skipStart),
                )
            }
            coVerify(exactly = 2) { mockProcessManager.completeStep(any(), any(), any(), any()) }
            // The handover is the same one a success boundary computes: the start the
            // skipped boundary hands over is the one the second step's own ExecutionInfo
            // carries (the shared nextBoundary helper).
            val secondStart = slot<ExecutionInfo>()
            coVerify { mockProcessManager.completeStep(testProcessId, capture(secondStart), null, null) }
            assertEquals(skipStart.captured, secondStart.captured.start)
        }

    // ========== When-predicate Tests ==========

    @Test
    fun `execute skips command when 'when' predicate evaluates false`() =
        runTest {
            val agent =
                Agent(
                    name = "skip_test",
                    commands =
                        listOf(
                            Command.Cmd(
                                cmd = listOf("echo", "first"),
                                timeout = 60,
                                whenClause = When(When.Operator.ALL, listOf("false-expr")),
                            ),
                            Command.Cmd(cmd = listOf("echo", "second"), timeout = 60),
                        ),
                    env = emptyMap(),
                )
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns
                TestFixtures.mockCommandResult(exitCode = 0)
            // Predicate evaluates false (exit 1)
            coEvery { mockShell.cmd(any(), any(), any(), any(), any(), any()) } answers {
                TestFixtures.mockCommandResult(exitCode = 1, stdOut = arg(1), stdErr = arg(2))
            }

            val executionInfos = mutableListOf<ExecutionInfo>()
            coEvery { mockProcessManager.completeStep(any(), capture(executionInfos), any(), any()) } just Runs

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            executor.execute()

            // Only command 1 (the un-gated one) was executed
            coVerify(exactly = 1) { mockExecutor.execute(any(), any(), any()) }
            // Two execution infos recorded (skipped + ran)
            assertEquals(2, executionInfos.size)
            val skippedInfo = executionInfos[0]
            assertTrue(skippedInfo.skipped)
            assertEquals(-1, skippedInfo.resultCode)
            assertTrue(
                skippedInfo.errMsg!!.contains("when.all"),
                "expected skip reason to mention 'when.all', got: ${skippedInfo.errMsg}",
            )
            assertEquals("cmd", skippedInfo.kind)
            verify { span.setAttribute("cmd.skipped", true) }
        }

    @Test
    fun `execute redacts the skipped when predicate before it reaches errMsg`() =
        runTest {
            // CWE-532: the Skip reason interpolates the raw
            // expression, so a credential in a `when` expression must not survive into
            // the skipped step's ExecutionInfo.errMsg.
            val secretToken = "sup3r-secret-token-value"
            val agent =
                Agent(
                    name = "when_skip_redaction_test",
                    commands =
                        listOf(
                            Command.Cmd(
                                cmd = listOf("echo", "first"),
                                timeout = 60,
                                whenClause = When(When.Operator.ALL, listOf("curl -H 'Authorization: $secretToken'")),
                            ),
                        ),
                    env = emptyMap(),
                )
            val span = TestFixtures.mockSpan()
            val workspaceWithSecret =
                TestFixtures.mockWorkspace(
                    config =
                        TestFixtures.mockWorkspaceConfig(
                            env = mapOf("SECRET_VAR" to secretToken),
                            secretEnvNames = setOf("SECRET_VAR"),
                        ),
                )

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns
                TestFixtures.mockCommandResult(exitCode = 0)
            // Predicate evaluates false (exit 1) → Skip
            coEvery { mockShell.cmd(any(), any(), any(), any(), any(), any()) } answers {
                TestFixtures.mockCommandResult(exitCode = 1, stdOut = arg(1), stdErr = arg(2))
            }

            val executionInfos = mutableListOf<ExecutionInfo>()
            coEvery { mockProcessManager.completeStep(any(), capture(executionInfos), any(), any()) } just Runs

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = workspaceWithSecret,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            executor.execute()

            assertEquals(1, executionInfos.size)
            val skippedInfo = executionInfos[0]
            assertTrue(skippedInfo.skipped)
            assertTrue(
                skippedInfo.errMsg!!.contains("when.all"),
                "expected skip reason to mention 'when.all', got: ${skippedInfo.errMsg}",
            )
            assertTrue(
                !skippedInfo.errMsg.contains(secretToken),
                "the credential in the `when` expression must be redacted from the skip reason, got: ${skippedInfo.errMsg}",
            )
        }

    @Test
    fun `execute throws CommandExecutionException when 'when' predicate eval errors`() =
        runTest {
            val agent =
                Agent(
                    name = "eval_error_test",
                    commands =
                        listOf(
                            Command.Cmd(
                                cmd = listOf("echo", "first"),
                                timeout = 60,
                                whenClause = When(When.Operator.ALL, listOf("syntax-error-expr")),
                            ),
                            Command.Cmd(cmd = listOf("echo", "second"), timeout = 60),
                        ),
                    env = emptyMap(),
                )
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns
                TestFixtures.mockCommandResult(exitCode = 0)
            // Predicate eval error (exit 2)
            coEvery { mockShell.cmd(any(), any(), any(), any(), any(), any()) } answers {
                TestFixtures.mockCommandResult(exitCode = 2, stdOut = arg(1), stdErr = arg(2))
            }

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            val exception =
                assertThrows<CommandExecutionException> {
                    executor.execute()
                }

            assertEquals(2, exception.exitCode)
            assertTrue(
                exception.message!!.contains("when-predicate evaluation failed"),
                "expected eval-error message, got: ${exception.message}",
            )
            // Neither command's executor ran
            coVerify(exactly = 0) { mockExecutor.execute(any(), any(), any()) }
            verify { span.setStatus(StatusCode.ERROR, any()) }
            verify { span.recordException(exception) }
        }

    @Test
    fun `execute records an ExecutionInfo with the timeout when a 'when' predicate times out`() =
        runTest {
            val agent =
                Agent(
                    name = "when_timeout_test",
                    commands =
                        listOf(
                            Command.Cmd(
                                cmd = listOf("echo", "first"),
                                timeout = 60,
                                whenClause = When(When.Operator.ALL, listOf("sleep 6")),
                            ),
                            Command.Cmd(cmd = listOf("echo", "second"), timeout = 60),
                        ),
                    env = emptyMap(),
                )
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns
                TestFixtures.mockCommandResult(exitCode = 0)
            // The shell executors signal their own timeout with a CancellationException.
            coEvery { mockShell.cmd(any(), any(), any(), any(), any(), any()) } throws
                CancellationException("Timeout reached. 5s.")

            val executionInfos = mutableListOf<ExecutionInfo>()
            coEvery { mockProcessManager.completeStep(any(), capture(executionInfos), any(), any()) } just Runs

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            val exception =
                assertThrows<CommandExecutionException> {
                    executor.execute()
                }

            assertTrue(
                exception.message!!.contains("when-predicate evaluation failed"),
                "expected eval-error message, got: ${exception.message}",
            )
            assertTrue(
                exception.message!!.contains("timed out"),
                "expected the timeout summary, got: ${exception.message}",
            )
            // The timed-out step is no longer invisible: it has its own ExecutionInfo.
            assertEquals(1, executionInfos.size)
            assertTrue(
                executionInfos[0].errMsg!!.contains("when-predicate evaluation failed"),
                "expected the timeout message on the step's ExecutionInfo, got: ${executionInfos[0].errMsg}",
            )
            assertTrue(
                executionInfos[0].errMsg!!.contains("timed out"),
                "expected the timeout summary on the step's ExecutionInfo, got: ${executionInfos[0].errMsg}",
            )
            verify { span.setStatus(StatusCode.ERROR, any()) }
            coVerify { mockProcessManager.completeStep(testProcessId, any(), null, null) }
            // The next command never runs.
            coVerify(exactly = 0) { mockExecutor.execute(any(), any(), any()) }
        }

    @Test
    fun `execute redacts the timed-out when predicate before it reaches errMsg`() =
        runTest {
            // CWE-532: an operator puts a credential in a
            // `when` expression (`curl -H "Authorization: @SYSENV:..."`); the exception
            // message lands in ExecutionInfo.errMsg and is served raw by the status API,
            // so the expression must be redacted before it is persisted.
            val secretToken = "sup3r-secret-token-value"
            val agent =
                Agent(
                    name = "when_timeout_redaction_test",
                    commands =
                        listOf(
                            Command.Cmd(
                                cmd = listOf("echo", "first"),
                                timeout = 60,
                                whenClause = When(When.Operator.ALL, listOf("curl -H 'Authorization: $secretToken' && sleep 6")),
                            ),
                        ),
                    env = emptyMap(),
                )
            val span = TestFixtures.mockSpan()
            val workspaceWithSecret =
                TestFixtures.mockWorkspace(
                    config =
                        TestFixtures.mockWorkspaceConfig(
                            env = mapOf("SECRET_VAR" to secretToken),
                            secretEnvNames = setOf("SECRET_VAR"),
                        ),
                )

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns
                TestFixtures.mockCommandResult(exitCode = 0)
            coEvery { mockShell.cmd(any(), any(), any(), any(), any(), any()) } throws
                CancellationException("Timeout reached. 5s.")

            val executionInfos = mutableListOf<ExecutionInfo>()
            coEvery { mockProcessManager.completeStep(any(), capture(executionInfos), any(), any()) } just Runs

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = workspaceWithSecret,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            assertThrows<CommandExecutionException> {
                executor.execute()
            }

            assertEquals(1, executionInfos.size)
            val errMsg = executionInfos[0].errMsg ?: ""
            assertTrue(errMsg.contains("timed out"), "expected the timeout summary, got: $errMsg")
            assertTrue(
                !errMsg.contains(secretToken),
                "the credential in the `when` expression must be redacted from errMsg, got: $errMsg",
            )
        }

    @Test
    fun `execute propagates cancellation when the run is killed during a 'when' predicate`() =
        runTest {
            val agent =
                Agent(
                    name = "when_kill_test",
                    commands =
                        listOf(
                            Command.Cmd(
                                cmd = listOf("echo", "first"),
                                timeout = 60,
                                whenClause = When(When.Operator.ALL, listOf("sleep 6")),
                            ),
                        ),
                    env = emptyMap(),
                )
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            val started = CompletableDeferred<Unit>()
            // A runner-enforced timeout and a kill raise the same exception class; only the
            // caller's coroutine state tells them apart.
            coEvery { mockShell.cmd(any(), any(), any(), any(), any(), any()) } coAnswers {
                started.complete(Unit)
                delay(Long.MAX_VALUE)
                TestFixtures.mockCommandResult(exitCode = 0, stdOut = arg(1), stdErr = arg(2))
            }

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            var caught: Throwable? = null
            val job =
                launch {
                    try {
                        executor.execute()
                    } catch (e: Throwable) {
                        caught = e
                    }
                }
            started.await()
            job.cancelAndJoin()

            assertTrue(
                caught is CancellationException,
                "expected the kill's CancellationException to propagate, got: $caught",
            )
            assertTrue(
                caught !is CommandExecutionException,
                "a kill must not be turned into an eval error, got: $caught",
            )
            // No step-level eval-error bookkeeping for the killed predicate.
            coVerify(exactly = 0) { mockProcessManager.completeStep(any(), any(), any(), any()) }
        }

    @Test
    fun `execute passes workspace env to 'when' predicate`() =
        runTest {
            val workspaceWithEnv =
                TestFixtures.mockWorkspace(
                    config = TestFixtures.mockWorkspaceConfig(env = mapOf("FOO" to "bar")),
                )
            val agent =
                Agent(
                    name = "env_test",
                    commands =
                        listOf(
                            Command.Cmd(
                                cmd = listOf("echo", "first"),
                                timeout = 60,
                                whenClause = When(When.Operator.ALL, listOf("[ \"\$FOO\" = \"bar\" ]")),
                            ),
                        ),
                    env = emptyMap(),
                )
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } returns
                TestFixtures.mockCommandResult(exitCode = 0)

            val capturedEnv = slot<Map<String, String>>()
            coEvery {
                mockShell.cmd(any(), any(), any(), any(), env = capture(capturedEnv), timeout = any())
            } answers {
                TestFixtures.mockCommandResult(exitCode = 0, stdOut = arg(1), stdErr = arg(2))
            }

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = workspaceWithEnv,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            executor.execute()

            // Predicate evaluated true → command ran
            coVerify(exactly = 1) { mockExecutor.execute(any(), any(), any()) }
            assertEquals("bar", capturedEnv.captured["FOO"])
        }

    // ========== Output Cap Tests ==========

    @Test
    fun `execute flags outputTruncated on ExecutionInfo when OutputCapExceededException is thrown`() =
        runTest {
            val agent = AgentFixtures.simpleAgent()
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            coEvery { mockExecutor.execute(any(), any(), any()) } throws
                OutputCapExceededException("Output cap exceeded: 1024 bytes.")

            val executionInfos = mutableListOf<ExecutionInfo>()
            coEvery { mockProcessManager.completeStep(any(), capture(executionInfos), any(), any()) } just Runs

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            assertThrows<OutputCapExceededException> {
                executor.execute()
            }

            assertEquals(1, executionInfos.size)
            assertTrue(
                executionInfos[0].outputTruncated,
                "expected outputTruncated=true when OutputCapExceededException is thrown",
            )
            // OutputCapExceededException is not a CommandExecutionException, so the
            // recorded resultCode falls back to -1 and no skip flag is set.
            assertEquals(-1, executionInfos[0].resultCode)
            assertEquals(false, executionInfos[0].skipped)
        }

    @Test
    fun `execute records resultCode -1 when a non-Exception Throwable escapes the try`() =
        runTest {
            val agent = AgentFixtures.simpleAgent()
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            // An Error (not an Exception) skips the `catch (e: Exception)` block entirely.
            coEvery { mockExecutor.execute(any(), any(), any()) } throws NotImplementedError("boom")

            val executionInfos = mutableListOf<ExecutionInfo>()
            coEvery { mockProcessManager.completeStep(any(), capture(executionInfos), any(), any()) } just Runs

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            assertThrows<NotImplementedError> {
                executor.execute()
            }

            // The Error must not record a success-looking ExecutionInfo: the default
            // outcome should carry resultCode -1, not 0.
            assertEquals(1, executionInfos.size)
            assertEquals(-1, executionInfos[0].resultCode)
            assertEquals(null, executionInfos[0].errMsg)
        }

    @Test
    fun `execute records eval-error ExecutionInfo with predicate exit code and error message`() =
        runTest {
            val agent =
                Agent(
                    name = "eval_error_info_test",
                    commands =
                        listOf(
                            Command.Cmd(
                                cmd = listOf("echo", "first"),
                                timeout = 60,
                                whenClause = When(When.Operator.ALL, listOf("syntax-error-expr")),
                            ),
                        ),
                    env = emptyMap(),
                )
            val span = TestFixtures.mockSpan()

            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            // Predicate eval error (exit 2)
            coEvery { mockShell.cmd(any(), any(), any(), any(), any(), any()) } answers {
                TestFixtures.mockCommandResult(exitCode = 2, stdOut = arg(1), stdErr = arg(2))
            }

            val executionInfos = mutableListOf<ExecutionInfo>()
            coEvery { mockProcessManager.completeStep(any(), capture(executionInfos), any(), any()) } just Runs

            val executor =
                AgentExecutor(
                    agent = agent,
                    workspace = mockWorkspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )

            assertThrows<CommandExecutionException> {
                executor.execute()
            }

            // The eval-error path flows through the standard try/catch/finally, so an
            // ExecutionInfo is recorded with the predicate's exit code and error message.
            assertEquals(1, executionInfos.size)
            assertEquals(2, executionInfos[0].resultCode)
            assertEquals(false, executionInfos[0].outputTruncated)
            assertEquals(false, executionInfos[0].skipped)
            assertTrue(
                executionInfos[0].errMsg!!.contains("when-predicate evaluation failed"),
                "expected eval-error errMsg, got: ${executionInfos[0].errMsg}",
            )
        }

    // ========== Orchestration Tests ==========

    private fun runWith(
        session: RecordingSession,
        workspace: Workspace,
        agent: Agent = AgentFixtures.simpleAgent(),
    ) = runTest {
        val span = TestFixtures.mockSpan()
        every {
            mockTelemetryService.tracer
                .spanBuilder(any())
                .setParent(any())
                .startSpan()
        } returns span
        every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
        coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)
        coEvery { mockRunner.openSession(any()) } returns session

        AgentExecutor(
            agent = agent,
            workspace = workspace,
            executorFactory = mockExecutorFactory,
            telemetryService = mockTelemetryService,
            config = config,
            parentContext = Context.current(),
            stepRecords = mockProcessManager,
            processId = testProcessId,
            runner = mockRunner,
            activeSessions = ActiveSessionsRegistry(),
        ).execute()
    }

    @Test
    fun `execute orchestrates full lifecycle when workspace is cleared`() {
        val session = RecordingSession(mockShell, overrides = mapOf("HMR_WORK_DIR" to "/remote/w"))
        val workspace =
            TestFixtures.mockWorkspace(
                TestFixtures.mockWorkspaceConfig(
                    env = mapOf(EnvConstants.AGENT_DIR to "/tmp/test/agent-dir"),
                    clearAfterFinish = true,
                ),
            )
        runWith(session, workspace)
        assertEquals(
            listOf("createWorkspace", "upload:.", "upload:.homer/agent", "download:artifacts:OVERLAY", "cleanWorkspace", "close"),
            session.calls,
        )
        verify { workspace.appendToEnv(mapOf("HMR_WORK_DIR" to "/remote/w")) }
    }

    @Test
    fun `extraUploads are uploaded after the workspace and agent dir`() {
        val session =
            RecordingSession(
                mockShell,
                overrides = mapOf("HMR_WORK_DIR" to "/remote/w"),
                extraUploads = listOf(ExtraUploadSpec("/app/scripts", ".homer/scripts", env = "SCRIPTS_DIR")),
            )
        val workspace =
            TestFixtures.mockWorkspace(
                TestFixtures.mockWorkspaceConfig(
                    env = mapOf(EnvConstants.AGENT_DIR to "/tmp/test/agent-dir"),
                    clearAfterFinish = true,
                ),
            )
        runWith(session, workspace)
        assertEquals(
            listOf(
                "createWorkspace",
                "upload:.",
                "upload:.homer/agent",
                "upload:.homer/scripts",
                "download:artifacts:OVERLAY",
                "cleanWorkspace",
                "close",
            ),
            session.calls,
        )
    }

    @Test
    fun `operator-configured upload sources are resolved so a symlinked agent dir still uploads`() {
        val realAgentDir = tmp.resolve("real-agent").createDirectories()
        val agentDirLink = Files.createSymbolicLink(tmp.resolve("agent-link"), realAgentDir)
        val realScripts = tmp.resolve("real-scripts").createDirectories()
        val scriptsLink = Files.createSymbolicLink(tmp.resolve("scripts-link"), realScripts)
        val session =
            RecordingSession(
                mockShell,
                extraUploads = listOf(ExtraUploadSpec(scriptsLink.toString(), ".homer/scripts", env = "SCRIPTS_DIR")),
            )
        val workspace =
            TestFixtures.mockWorkspace(
                TestFixtures.mockWorkspaceConfig(env = mapOf(EnvConstants.AGENT_DIR to agentDirLink.toString())),
            )

        runWith(session, workspace)

        assertEquals(
            listOf(realAgentDir.toRealPath(), realScripts.toRealPath()),
            session.uploadLocals.drop(1),
        )
    }

    @Test
    fun `a parent agent's upload excludes its nested agent directories`() {
        val agentDir = tmp.resolve("parent-agent").createDirectories()
        Files.write(agentDir.resolve("agent.yaml"), "name: parent\ncommands: []\n".toByteArray())
        val nested = agentDir.resolve("nodes/analysis").createDirectories()
        Files.write(nested.resolve("agent.yaml"), "name: analysis\ncommands: []\n".toByteArray())
        val sibling = agentDir.resolve("scripts").createDirectories()
        Files.write(sibling.resolve("run.sh"), "echo hi\n".toByteArray())
        val session = RecordingSession(mockShell)
        val workspace =
            TestFixtures.mockWorkspace(
                TestFixtures.mockWorkspaceConfig(env = mapOf(EnvConstants.AGENT_DIR to agentDir.toString())),
            )

        runWith(session, workspace)

        assertEquals(
            listOf("/nodes/analysis"),
            session.uploadExcludes[".homer/agent"],
            "a parent's HMR_AGENT_DIR upload must carry none of its nested agents",
        )
        // The prefix anchors the exclude to the upload root: a bare name would match
        // a same-named directory at any depth.
        assertTrue(session.uploadExcludes[".homer/agent"]!!.all { it.startsWith("/") })
    }

    @Test
    fun `a childless agent uploads with no excludes`() {
        val agentDir = tmp.resolve("solo-agent").createDirectories()
        Files.write(agentDir.resolve("agent.yaml"), "name: solo\ncommands: []\n".toByteArray())
        Files.write(agentDir.resolve("README.md"), "x".toByteArray())
        val session = RecordingSession(mockShell)
        val workspace =
            TestFixtures.mockWorkspace(
                TestFixtures.mockWorkspaceConfig(env = mapOf(EnvConstants.AGENT_DIR to agentDir.toString())),
            )

        runWith(session, workspace)

        assertEquals(emptyList<String>(), session.uploadExcludes[".homer/agent"])
    }

    @Test
    fun `execute downloads workdir and skips clean when workspace is kept`() {
        val session = RecordingSession(mockShell)
        val workspace = TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig(clearAfterFinish = false))
        runWith(session, workspace)
        assertEquals(
            listOf("createWorkspace", "upload:.", "download:artifacts:OVERLAY", "download:workdir:MIRROR", "close"),
            session.calls,
        )
    }

    @Test
    fun `execute still downloads artifacts and closes when a command fails`() {
        val session = RecordingSession(mockShell)
        val workspace = TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig(clearAfterFinish = true))
        val span = TestFixtures.mockSpan()
        every {
            mockTelemetryService.tracer
                .spanBuilder(any())
                .setParent(any())
                .startSpan()
        } returns span
        every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
        coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 1)
        coEvery { mockRunner.openSession(any()) } returns session

        assertThrows<CommandExecutionException> {
            runTest {
                AgentExecutor(
                    agent = AgentFixtures.simpleAgent(),
                    workspace = workspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                ).execute()
            }
        }
        assertEquals(
            listOf("createWorkspace", "upload:.", "markFailed", "download:artifacts:OVERLAY", "cleanWorkspace", "close"),
            session.calls,
        )
    }

    @Test
    fun `execute marks the session failed before close when a command fails`() {
        val session = RecordingSession(mockShell)
        val workspace = TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig(clearAfterFinish = true))
        val span = TestFixtures.mockSpan()
        every {
            mockTelemetryService.tracer
                .spanBuilder(any())
                .setParent(any())
                .startSpan()
        } returns span
        every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
        coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 1)
        coEvery { mockRunner.openSession(any()) } returns session

        assertThrows<CommandExecutionException> {
            runTest {
                AgentExecutor(
                    agent = AgentFixtures.simpleAgent(),
                    workspace = workspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                ).execute()
            }
        }
        assertTrue("markFailed" in session.calls)
        assertTrue(session.calls.indexOf("markFailed") < session.calls.indexOf("close"))
    }

    @Test
    fun `execute marks the session failed when a command throws a plain CancellationException without our job being cancelled`() {
        val session = RecordingSession(mockShell)
        val workspace = TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig(clearAfterFinish = true))
        val span = TestFixtures.mockSpan()
        every {
            mockTelemetryService.tracer
                .spanBuilder(any())
                .setParent(any())
                .startSpan()
        } returns span
        every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
        // Simulates a runner-enforced timeout (e.g. KubernetesShellExecutor's TTL): a plain
        // CancellationException surfaces from a command without the executor's own coroutine
        // job ever being cancelled.
        coEvery { mockExecutor.execute(any(), any(), any()) } throws CancellationException("ttl exceeded")
        coEvery { mockRunner.openSession(any()) } returns session

        assertThrows<CancellationException> {
            runTest {
                AgentExecutor(
                    agent = AgentFixtures.simpleAgent(),
                    workspace = workspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                ).execute()
            }
        }
        assertTrue("markFailed" in session.calls)
    }

    @Test
    fun `execute does not mark the session failed when the run's own job is cancelled`() =
        runTest {
            val session = RecordingSession(mockShell)
            val workspace = TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig(clearAfterFinish = true))
            val span = TestFixtures.mockSpan()
            every {
                mockTelemetryService.tracer
                    .spanBuilder(any())
                    .setParent(any())
                    .startSpan()
            } returns span
            every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
            val started = CompletableDeferred<Unit>()
            coEvery { mockExecutor.execute(any(), any(), any()) } coAnswers {
                started.complete(Unit)
                delay(Long.MAX_VALUE)
                TestFixtures.mockCommandResult(exitCode = 0)
            }
            coEvery { mockRunner.openSession(any()) } returns session

            val executor =
                AgentExecutor(
                    agent = AgentFixtures.simpleAgent(),
                    workspace = workspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                )
            val job = launch { runCatching { executor.execute() } }
            started.await()
            job.cancelAndJoin()

            assertTrue("markFailed" !in session.calls)
        }

    @Test
    fun `execute closes session when createWorkspace fails`() {
        val session = RecordingSession(mockShell, failCreateWorkspace = true)
        val workspace = TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig(clearAfterFinish = true))
        coEvery { mockRunner.openSession(any()) } returns session

        assertThrows<IllegalStateException> {
            runTest {
                AgentExecutor(
                    agent = AgentFixtures.simpleAgent(),
                    workspace = workspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                ).execute()
            }
        }
        assertTrue("upload:." !in session.calls)
        assertTrue("close" in session.calls)
    }

    @Test
    fun `execute succeeds even when sync-down and cleanup fail`() {
        val session = RecordingSession(mockShell, failDownload = true, failCleanWorkspace = true)
        val workspace = TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig(clearAfterFinish = true))
        runWith(session, workspace) // must not throw — sync-down/cleanup are best-effort
        assertEquals(
            listOf("createWorkspace", "upload:.", "download:artifacts:OVERLAY", "cleanWorkspace", "close"),
            session.calls,
        )
    }

    // ========== Runner State Tests ==========

    @Test
    fun `execute records the serving backend alias as runner state`() {
        val session = RecordingSession(mockShell, runnerAlias = "local")
        val updates = mutableListOf<(RunnerState?) -> RunnerState>()
        coEvery { mockProcessManager.setRunnerState(any(), capture(updates)) } just Runs

        runWith(session, TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig()))

        assertEquals(
            RunnerState(alias = "local"),
            updates.single().invoke(null),
        )
    }

    @Test
    fun `execute records the serving runner's alias, not the queued tag filter alias`() {
        // A `runnerTags` run is queued under the filter alias "tags:..."; the
        // serving backend is only known once the session is open.
        every { mockRunner.alias } returns "tags:gpu,linux"
        val session = RecordingSession(mockShell, runnerAlias = "ssh-prod")
        val updates = mutableListOf<(RunnerState?) -> RunnerState>()
        coEvery { mockProcessManager.setRunnerState(any(), capture(updates)) } just Runs

        runWith(session, TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig()))

        assertEquals(
            RunnerState(alias = "ssh-prod"),
            updates.single().invoke(null),
        )
    }

    @Test
    fun `the runner-state alias write preserves the Kubernetes pod fields`() {
        val session = RecordingSession(mockShell, runnerAlias = "k8s-dev")
        val updates = mutableListOf<(RunnerState?) -> RunnerState>()
        coEvery { mockProcessManager.setRunnerState(any(), capture(updates)) } just Runs

        runWith(session, TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig()))

        // The k8s runner emits this during openSession, before the alias write.
        val podState =
            RunnerState(
                alias = "stale-alias",
                podName = "homer-123-abc",
                podPhase = PodPhase.READY,
                createdAt = 1_700_000_000,
                readyAt = 1_700_000_005,
            )
        assertEquals(
            podState.copy(alias = "k8s-dev"),
            updates.single().invoke(podState),
        )
    }

    @Test
    fun `the alias merge keeps the pod fields when a pod write lands during the merge`() {
        // The Kubernetes runner's pod transitions and this write both go through
        // the repository's single BEGIN IMMEDIATE transaction, so they serialize.
        // The merge's read sees the last committed pod state, and the merged value
        // must carry its pod fields — a replace-form lambda
        // (`{ prev -> RunnerState(alias = …) }`) drops them and fails here.
        val session = RecordingSession(mockShell, runnerAlias = "k8s-dev")
        var persisted: RunnerState? = null
        // The pod state as the merge's read sees it (the k8s runner wrote it during
        // openSession).
        val podState =
            RunnerState(
                alias = "stale-alias",
                podName = "homer-123-abc",
                podPhase = PodPhase.RUNNING,
                createdAt = 1_700_000_000,
            )
        coEvery {
            mockProcessManager.setRunnerState(
                any(),
                captureLambda<
                    (
                        RunnerState?,
                    ) -> RunnerState,
                >(),
            )
        } coAnswers
            {
                val merge = lambda<(RunnerState?) -> RunnerState>().captured
                // The merge reads the last pod state and the merged value is what
                // the alias write persists.
                persisted = merge(podState)
            }

        runWith(session, TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig()))

        assertEquals(
            podState.copy(alias = "k8s-dev"),
            persisted,
        )
    }

    @Test
    fun `execute records no runner state when the session never opens`() =
        runTest {
            val workspace = TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig())
            coEvery { mockRunner.openSession(any()) } throws IllegalStateException("claim timed out")

            assertThrows<IllegalStateException> {
                AgentExecutor(
                    agent = AgentFixtures.simpleAgent(),
                    workspace = workspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                ).execute()
            }

            coVerify(exactly = 0) { mockProcessManager.setRunnerState(any(), any()) }
        }

    @Test
    fun `execute records no runner state when workspace creation fails after the session opens`() =
        runTest {
            val session = RecordingSession(mockShell, failCreateWorkspace = true)
            val workspace = TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig())
            coEvery { mockRunner.openSession(any()) } returns session

            assertThrows<IllegalStateException> {
                AgentExecutor(
                    agent = AgentFixtures.simpleAgent(),
                    workspace = workspace,
                    executorFactory = mockExecutorFactory,
                    telemetryService = mockTelemetryService,
                    config = config,
                    parentContext = Context.current(),
                    stepRecords = mockProcessManager,
                    processId = testProcessId,
                    runner = mockRunner,
                    activeSessions = ActiveSessionsRegistry(),
                ).execute()
            }

            // The alias write sits after createWorkspace(), so a run whose session
            // opened but whose workspace was never created reports runner: null.
            coVerify(exactly = 0) { mockProcessManager.setRunnerState(any(), any()) }
        }
}

private class RecordingSession(
    override val shell: ShellExecutor,
    private val overrides: Map<String, String> = emptyMap(),
    private val failCreateWorkspace: Boolean = false,
    private val failDownload: Boolean = false,
    private val failCleanWorkspace: Boolean = false,
    override val extraUploads: List<ExtraUploadSpec> = emptyList(),
    override val runnerAlias: String = "recording",
) : RunnerSession {
    val calls = mutableListOf<String>()
    val uploadLocals = mutableListOf<java.nio.file.Path>()
    val uploadExcludes = mutableMapOf<String, List<String>>()

    override suspend fun createWorkspace(): Map<String, String> {
        calls += "createWorkspace"
        if (failCreateWorkspace) error("mkdir failed")
        return overrides
    }

    override suspend fun upload(
        local: java.nio.file.Path,
        remoteRelative: String,
        excludes: List<String>,
    ) {
        calls += "upload:$remoteRelative"
        uploadLocals.add(local)
        uploadExcludes[remoteRelative] = excludes
    }

    override suspend fun download(
        remoteRelative: String,
        local: java.nio.file.Path,
        mode: DownloadMode,
    ) {
        calls += "download:$remoteRelative:$mode"
        if (failDownload) throw RuntimeException("network gone")
    }

    override suspend fun cleanWorkspace() {
        calls += "cleanWorkspace"
        if (failCleanWorkspace) throw RuntimeException("sweep failed")
    }

    override fun close() {
        calls += "close"
    }

    override fun markFailed() {
        calls.add("markFailed")
    }
}
