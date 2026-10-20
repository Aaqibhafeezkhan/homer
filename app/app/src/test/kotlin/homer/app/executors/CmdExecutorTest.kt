package homer.app.executors

import homer.app.config.ExecutionConfig
import homer.app.fixtures.TestFixtures
import homer.app.infra.LocalShellExecutor
import homer.app.infra.Workspace
import homer.app.infra.WorkspaceConfig
import homer.app.models.Command
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for CmdExecutor
 * Tests command parsing and execution logic
 */
class CmdExecutorTest {
    private lateinit var cmdExecutor: CmdExecutor
    private lateinit var mockWorkspace: Workspace
    private lateinit var mockShellExecutor: LocalShellExecutor
    private lateinit var mockFileOperations: homer.app.infra.FileOperations
    private lateinit var mockSpan: Span
    private lateinit var mockArtifactsPath: Path
    private lateinit var mockStdOutPath: Path
    private lateinit var mockStdErrPath: Path
    private lateinit var executionContext: ExecutionContext

    @BeforeEach
    fun setup() {
        mockFileOperations = TestFixtures.mockFileOperations()
        cmdExecutor = CmdExecutor(mockFileOperations)
        mockWorkspace = mockk(relaxed = true)
        mockShellExecutor = mockk(relaxed = true)
        mockSpan = TestFixtures.mockSpan()
        mockArtifactsPath = mockk(relaxed = true)
        mockStdOutPath = mockk(relaxed = true)
        mockStdErrPath = mockk(relaxed = true)

        val mockConfig = mockk<WorkspaceConfig>(relaxed = true)
        every { mockConfig.artifacts } returns mockArtifactsPath
        every { mockConfig.env } returns mapOf("TEST_ENV" to "value")
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
    fun `execute should pass argv through to shell executor`() =
        runTest {
            val command = Command.Cmd(cmd = listOf("echo", "hello", "world"), timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 100
            every { mockFileOperations.readText(mockStdOutPath) } returns "hello world"

            coEvery {
                mockShellExecutor.cmd(
                    command = listOf("echo", "hello", "world"),
                    stdOut = mockStdOutPath,
                    stdErr = mockStdErrPath,
                    stdIn = null,
                    env = executionContext.workspace.config.env,
                    timeout = any(),
                )
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            val result = cmdExecutor.execute(command, executionContext, mockSpan)

            assertEquals(0, result.exitCode)
            coVerify {
                mockShellExecutor.cmd(
                    command = listOf("echo", "hello", "world"),
                    stdOut = any(),
                    stdErr = any(),
                    stdIn = null,
                    env = any(),
                    timeout = any(),
                )
            }
        }

    @Test
    fun `execute should preserve argv elements containing spaces`() =
        runTest {
            val command = Command.Cmd(cmd = listOf("echo", "hello world"), timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 0
            every { mockFileOperations.readText(mockStdOutPath) } returns ""

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            cmdExecutor.execute(command, executionContext, mockSpan)

            coVerify {
                mockShellExecutor.cmd(
                    command = listOf("echo", "hello world"),
                    stdOut = any(),
                    stdErr = any(),
                    stdIn = null,
                    env = any(),
                    timeout = any(),
                )
            }
        }

    @Test
    fun `execute should call shellExecutor with correct parameters`() =
        runTest {
            val command = Command.Cmd(cmd = listOf("ls", "-la"), timeout = 30)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 50
            every { mockFileOperations.readText(mockStdOutPath) } returns "output"

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            cmdExecutor.execute(command, executionContext, mockSpan)

            coVerify {
                mockShellExecutor.cmd(
                    command = listOf("ls", "-la"),
                    stdOut = mockStdOutPath,
                    stdErr = mockStdErrPath,
                    stdIn = null,
                    env = any(),
                    timeout = any(),
                )
            }
        }

    @Test
    fun `execute should set span attribute for input command`() =
        runTest {
            val command = Command.Cmd(cmd = listOf("echo", "hello"), timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 10
            every { mockFileOperations.readText(mockStdOutPath) } returns "hello"

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            cmdExecutor.execute(command, executionContext, mockSpan)

            verify { mockSpan.setAttribute("input.value", "echo hello") }
        }

    @Test
    fun `execute should include output in span if small`() =
        runTest {
            val command = Command.Cmd(cmd = listOf("echo", "test"), timeout = 60)
            val outputContent = "test output"

            every { mockFileOperations.fileSize(mockStdOutPath) } returns outputContent.length.toLong()
            every { mockFileOperations.readText(mockStdOutPath) } returns outputContent

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            val context =
                executionContext.copy(
                    config = ExecutionConfig(maxOutputSizeForTracing = 4096),
                )

            cmdExecutor.execute(command, context, mockSpan)

            verify { mockSpan.setAttribute("output.value", outputContent) }
        }

    @Test
    fun `execute should truncate output message if too large`() =
        runTest {
            val command = Command.Cmd(cmd = listOf("echo", "test"), timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 10000 // Larger than maxOutputSizeForTracing
            every { mockFileOperations.readText(mockStdOutPath) } returns "x".repeat(10000)
            every { mockStdOutPath.toString() } returns "/tmp/cmd_0.out"

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            val context =
                executionContext.copy(
                    config = ExecutionConfig(maxOutputSizeForTracing = 4096),
                )

            cmdExecutor.execute(command, context, mockSpan)

            verify {
                mockSpan.setAttribute(
                    "output.value",
                    match<String> { it.contains("Output too large") },
                )
            }
        }

    @Test
    fun `execute should use timeout from command`() =
        runTest {
            val command = Command.Cmd(cmd = listOf("sleep", "5"), timeout = 10)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 0
            every { mockFileOperations.readText(mockStdOutPath) } returns ""

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            cmdExecutor.execute(command, executionContext, mockSpan)

            coVerify {
                mockShellExecutor.cmd(
                    command = any(),
                    stdOut = any(),
                    stdErr = any(),
                    stdIn = any(),
                    env = any(),
                    timeout = 10.seconds,
                )
            }
        }

    @Test
    fun `execute should merge environment variables`() =
        runTest {
            val command = Command.Cmd(cmd = listOf("env"), timeout = 60)
            val env = mapOf("VAR1" to "value1", "VAR2" to "value2")

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 10
            every { mockFileOperations.readText(mockStdOutPath) } returns "output"

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            val customWorkspace = mockk<Workspace>(relaxed = true)
            val customConfig = mockk<WorkspaceConfig>(relaxed = true)
            every { customConfig.artifacts } returns mockArtifactsPath
            every { customConfig.env } returns env
            every { customWorkspace.config } returns customConfig

            val context = executionContext.copy(workspace = customWorkspace)

            cmdExecutor.execute(command, context, mockSpan)

            coVerify {
                mockShellExecutor.cmd(
                    command = any(),
                    stdOut = any(),
                    stdErr = any(),
                    stdIn = any(),
                    env = env,
                    timeout = any(),
                )
            }
        }

    @Test
    fun `execute redacts declared-secret values in input and output span attributes`() =
        runTest {
            val command = Command.Cmd(cmd = listOf("echo", "s3cr3t"), timeout = 60)
            every { mockFileOperations.fileSize(mockStdOutPath) } returns 100
            every { mockFileOperations.readText(mockStdOutPath) } returns "leaked s3cr3t value"
            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            val secretWorkspace = mockk<Workspace>(relaxed = true)
            val secretConfig = mockk<WorkspaceConfig>(relaxed = true)
            every { secretConfig.env } returns mapOf("TOKEN" to "s3cr3t")
            every { secretConfig.secretEnvNames } returns setOf("TOKEN")
            every { secretWorkspace.config } returns secretConfig

            cmdExecutor.execute(command, executionContext.copy(workspace = secretWorkspace), mockSpan)

            verify { mockSpan.setAttribute("input.value", "echo ***") }
            verify { mockSpan.setAttribute("output.value", "leaked *** value") }
        }

    @Test
    fun `execute should return CommandResult with correct exit code`() =
        runTest {
            val command = Command.Cmd(cmd = listOf("exit", "0"), timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 0
            every { mockFileOperations.readText(mockStdOutPath) } returns ""

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            val result = cmdExecutor.execute(command, executionContext, mockSpan)

            assertEquals(0, result.exitCode)
        }

    @Test
    fun `execute should return non-zero exit code on failure`() =
        runTest {
            val command = Command.Cmd(cmd = listOf("exit", "1"), timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 0
            every { mockFileOperations.readText(mockStdOutPath) } returns ""

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 1, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            val result = cmdExecutor.execute(command, executionContext, mockSpan)

            assertEquals(1, result.exitCode)
        }

    @Test
    fun `execute should use stdOut and stdErr from context`() =
        runTest {
            val command = Command.Cmd(cmd = listOf("echo", "test"), timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 10
            every { mockFileOperations.readText(mockStdOutPath) } returns "test"

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            cmdExecutor.execute(command, executionContext, mockSpan)

            coVerify {
                mockShellExecutor.cmd(
                    command = any(),
                    stdOut = mockStdOutPath,
                    stdErr = mockStdErrPath,
                    stdIn = any(),
                    env = any(),
                    timeout = any(),
                )
            }
        }
}
