package homer.app.executors

import homer.app.EnvConstants
import homer.app.config.ExecutionConfig
import homer.app.fixtures.TestFixtures
import homer.app.infra.LocalShellExecutor
import homer.app.infra.Workspace
import homer.app.infra.WorkspaceConfig
import homer.app.models.AppConfig
import homer.app.models.Command
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for LangGraphExecutor
 * Tests argv construction, HOMER_LG_STATE_DIR injection, env passthrough, and result return.
 */
class LangGraphExecutorTest {
    private lateinit var langGraphExecutor: LangGraphExecutor
    private lateinit var mockWorkspace: Workspace
    private lateinit var mockShellExecutor: LocalShellExecutor
    private lateinit var mockFileOperations: homer.app.infra.FileOperations
    private lateinit var mockAppConfig: AppConfig
    private lateinit var mockSpan: Span
    private lateinit var mockStdOutPath: Path
    private lateinit var mockStdErrPath: Path
    private lateinit var executionContext: ExecutionContext

    private val stateDir = "/var/lib/homer/langgraph"

    @BeforeEach
    fun setup() {
        mockFileOperations = TestFixtures.mockFileOperations()
        mockAppConfig = mockk(relaxed = true)
        every { mockAppConfig.resolveLangGraphStateDir(owner = any()) } returns stateDir
        langGraphExecutor = LangGraphExecutor(mockFileOperations, mockAppConfig)
        mockWorkspace = mockk(relaxed = true)
        mockShellExecutor = mockk(relaxed = true)
        mockSpan = TestFixtures.mockSpan()
        mockStdOutPath = mockk(relaxed = true)
        mockStdErrPath = mockk(relaxed = true)

        val mockConfig = mockk<WorkspaceConfig>(relaxed = true)
        every { mockConfig.env } returns mapOf("TEST_ENV" to "value", "HMR_LG_THREAD" to "thread-1")
        every { mockWorkspace.config } returns mockConfig

        executionContext =
            ExecutionContext(
                workspace = mockWorkspace,
                config = ExecutionConfig(),
                shell = mockShellExecutor,
                stdOut = mockStdOutPath,
                stdErr = mockStdErrPath,
            )
    }

    @Test
    fun `execute builds python module argv`() =
        runTest {
            val command = Command.LangGraph(module = "my_graphs.flow", python = "python3", timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 0
            every { mockFileOperations.readText(mockStdOutPath) } returns ""

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            langGraphExecutor.execute(command, executionContext, mockSpan)

            coVerify {
                mockShellExecutor.cmd(
                    command =
                        listOf(
                            "python3",
                            "-m",
                            "homer_langgraph",
                            "run",
                            "--module",
                            "my_graphs.flow",
                            "--input-env",
                            "HMR_LG_INPUT",
                        ),
                    stdOut = mockStdOutPath,
                    stdErr = mockStdErrPath,
                    stdIn = null,
                    env = any(),
                    timeout = 60.seconds,
                )
            }
        }

    @Test
    fun `execute redacts declared-secret values in the output span attribute`() =
        runTest {
            val command = Command.LangGraph(module = "my_graphs.flow", python = "python3", timeout = 60)
            every { mockFileOperations.fileSize(mockStdOutPath) } returns 100
            every { mockFileOperations.readText(mockStdOutPath) } returns "graph printed s3cr3t oops"
            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            val secretWorkspace = mockk<Workspace>(relaxed = true)
            every { secretWorkspace.config } returns
                TestFixtures.mockWorkspaceConfig(env = mapOf("TOKEN" to "s3cr3t"), secretEnvNames = setOf("TOKEN"))

            langGraphExecutor.execute(command, executionContext.copy(workspace = secretWorkspace), mockSpan)

            verify { mockSpan.setAttribute("output.value", "graph printed *** oops") }
        }

    @Test
    fun `execute injects the state dir scoped to the process owner`() =
        runTest {
            val command = Command.LangGraph(module = "g", timeout = 60)
            every { mockFileOperations.fileSize(mockStdOutPath) } returns 0
            every { mockFileOperations.readText(mockStdOutPath) } returns ""
            every { mockAppConfig.resolveLangGraphStateDir(owner = "alice") } returns "$stateDir/alice"

            val envSlot = slot<Map<String, String>>()
            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), capture(envSlot), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            langGraphExecutor.execute(command, executionContext.copy(owner = "alice"), mockSpan)

            assertEquals("$stateDir/alice", envSlot.captured[EnvConstants.LG_STATE_DIR])
        }

    @Test
    fun `execute appends default input-env to argv`() =
        runTest {
            val command = Command.LangGraph(module = "g", timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 0
            every { mockFileOperations.readText(mockStdOutPath) } returns ""

            val argvSlot = slot<List<String>>()
            coEvery {
                mockShellExecutor.cmd(capture(argvSlot), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            langGraphExecutor.execute(command, executionContext, mockSpan)

            val argv = argvSlot.captured
            assertEquals("HMR_LG_INPUT", argv[argv.indexOf("--input-env") + 1], "default inputEnv must be passed")
        }

    @Test
    fun `execute appends custom input-env to argv`() =
        runTest {
            val command = Command.LangGraph(module = "g", inputEnv = "GRAPH_INPUT", timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 0
            every { mockFileOperations.readText(mockStdOutPath) } returns ""

            val argvSlot = slot<List<String>>()
            coEvery {
                mockShellExecutor.cmd(capture(argvSlot), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            langGraphExecutor.execute(command, executionContext, mockSpan)

            val argv = argvSlot.captured
            assertEquals("GRAPH_INPUT", argv[argv.indexOf("--input-env") + 1], "custom inputEnv must be passed")
        }

    @Test
    fun `execute uses configured python interpreter`() =
        runTest {
            val command = Command.LangGraph(module = "g", python = "/usr/bin/python3.12", timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 0
            every { mockFileOperations.readText(mockStdOutPath) } returns ""

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            langGraphExecutor.execute(command, executionContext, mockSpan)

            coVerify {
                mockShellExecutor.cmd(
                    command =
                        listOf(
                            "/usr/bin/python3.12",
                            "-m",
                            "homer_langgraph",
                            "run",
                            "--module",
                            "g",
                            "--input-env",
                            "HMR_LG_INPUT",
                        ),
                    stdOut = any(),
                    stdErr = any(),
                    stdIn = null,
                    env = any(),
                    timeout = any(),
                )
            }
        }

    @Test
    fun `execute injects HOMER_LG_STATE_DIR and passes workspace env through`() =
        runTest {
            val command = Command.LangGraph(module = "g", timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 0
            every { mockFileOperations.readText(mockStdOutPath) } returns ""

            val envSlot = slot<Map<String, String>>()
            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), capture(envSlot), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            langGraphExecutor.execute(command, executionContext, mockSpan)

            assertEquals(stateDir, envSlot.captured["HOMER_LG_STATE_DIR"], "state dir must be injected")
            assertEquals("value", envSlot.captured["TEST_ENV"], "workspace env must pass through")
            assertEquals("thread-1", envSlot.captured["HMR_LG_THREAD"], "resume params in env must pass through")
        }

    @Test
    fun `execute sets span input attribute`() =
        runTest {
            val command = Command.LangGraph(module = "my_graphs.flow", python = "python3", timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 0
            every { mockFileOperations.readText(mockStdOutPath) } returns ""

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            langGraphExecutor.execute(command, executionContext, mockSpan)

            verify {
                mockSpan.setAttribute(
                    "input.value",
                    "python3 -m homer_langgraph run --module my_graphs.flow --input-env HMR_LG_INPUT",
                )
            }
        }

    @Test
    fun `execute returns CommandResult with exit code from shell`() =
        runTest {
            val command = Command.LangGraph(module = "g", timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 0
            every { mockFileOperations.readText(mockStdOutPath) } returns ""

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 3, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            val result = langGraphExecutor.execute(command, executionContext, mockSpan)

            assertEquals(3, result.exitCode)
        }
}
