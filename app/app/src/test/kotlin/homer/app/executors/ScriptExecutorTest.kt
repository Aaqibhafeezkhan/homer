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
 * Unit tests for ScriptExecutor
 * Tests script piping and execution logic
 */
class ScriptExecutorTest {
    private lateinit var scriptExecutor: ScriptExecutor
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
        scriptExecutor = ScriptExecutor(mockFileOperations)
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
    fun `execute should pipe script content to shell via stdin`() =
        runTest {
            val scriptContent = "#!/bin/bash\necho 'Hello from script'"
            val command = Command.Script(content = scriptContent, shell = "bash", timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 100
            every { mockFileOperations.readText(mockStdOutPath) } returns "Hello from script"

            coEvery {
                mockShellExecutor.cmd(
                    command = listOf("bash", "-s", "-"),
                    stdOut = mockStdOutPath,
                    stdErr = mockStdErrPath,
                    stdIn = scriptContent,
                    env = executionContext.workspace.config.env,
                    timeout = any(),
                )
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            val result = scriptExecutor.execute(command, executionContext, mockSpan)

            assertEquals(0, result.exitCode)
            coVerify {
                mockShellExecutor.cmd(
                    command = listOf("bash", "-s", "-"),
                    stdOut = any(),
                    stdErr = any(),
                    stdIn = scriptContent,
                    env = any(),
                    timeout = any(),
                )
            }
        }

    @Test
    fun `execute should use default shell when not specified`() =
        runTest {
            val scriptContent = "echo 'test'"
            val command = Command.Script(content = scriptContent, timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 10
            every { mockFileOperations.readText(mockStdOutPath) } returns "test"

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            val context =
                executionContext.copy(
                    config = ExecutionConfig(),
                )

            scriptExecutor.execute(command, context, mockSpan)

            coVerify {
                mockShellExecutor.cmd(
                    command = listOf("sh", "-s", "-"),
                    stdOut = any(),
                    stdErr = any(),
                    stdIn = scriptContent,
                    env = any(),
                    timeout = any(),
                )
            }
        }

    @Test
    fun `execute should use specified shell type`() =
        runTest {
            val scriptContent = "echo 'test'"
            val command = Command.Script(content = scriptContent, shell = "zsh", timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 10
            every { mockFileOperations.readText(mockStdOutPath) } returns "test"

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            scriptExecutor.execute(command, executionContext, mockSpan)

            coVerify {
                mockShellExecutor.cmd(
                    command = listOf("zsh", "-s", "-"),
                    stdOut = any(),
                    stdErr = any(),
                    stdIn = any(),
                    env = any(),
                    timeout = any(),
                )
            }
        }

    @Test
    fun `execute should call shell with correct flags`() =
        runTest {
            val scriptContent = "ls -la"
            val command = Command.Script(content = scriptContent, shell = "bash", timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 10
            every { mockFileOperations.readText(mockStdOutPath) } returns "output"

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            scriptExecutor.execute(command, executionContext, mockSpan)

            coVerify {
                mockShellExecutor.cmd(
                    command = match { it.contains("-s") && it.contains("-") },
                    stdOut = any(),
                    stdErr = any(),
                    stdIn = any(),
                    env = any(),
                    timeout = any(),
                )
            }
        }

    @Test
    fun `execute should set span attribute for script content`() =
        runTest {
            val scriptContent = "echo 'hello world'"
            val command = Command.Script(content = scriptContent, shell = "bash", timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 10
            every { mockFileOperations.readText(mockStdOutPath) } returns "hello world"

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            scriptExecutor.execute(command, executionContext, mockSpan)

            verify { mockSpan.setAttribute("input.value", scriptContent) }
        }

    @Test
    fun `execute should include output in span if small`() =
        runTest {
            val command = Command.Script(content = "echo test", shell = "bash", timeout = 60)
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

            scriptExecutor.execute(command, context, mockSpan)

            verify { mockSpan.setAttribute("output.value", outputContent) }
        }

    @Test
    fun `execute should truncate output if too large`() =
        runTest {
            val command = Command.Script(content = "echo test", shell = "bash", timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 10000
            every { mockFileOperations.readText(mockStdOutPath) } returns "x".repeat(10000)
            every { mockStdOutPath.toString() } returns "/tmp/cmd_0.out"

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            val context =
                executionContext.copy(
                    config = ExecutionConfig(maxOutputSizeForTracing = 4096),
                )

            scriptExecutor.execute(command, context, mockSpan)

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
            val command = Command.Script(content = "sleep 5", shell = "bash", timeout = 20)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 0
            every { mockFileOperations.readText(mockStdOutPath) } returns ""

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            scriptExecutor.execute(command, executionContext, mockSpan)

            coVerify {
                mockShellExecutor.cmd(
                    command = any(),
                    stdOut = any(),
                    stdErr = any(),
                    stdIn = any(),
                    env = any(),
                    timeout = 20.seconds,
                )
            }
        }

    @Test
    fun `execute should return CommandResult with correct exit code`() =
        runTest {
            val command = Command.Script(content = "exit 0", shell = "bash", timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 0
            every { mockFileOperations.readText(mockStdOutPath) } returns ""

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            val result = scriptExecutor.execute(command, executionContext, mockSpan)

            assertEquals(0, result.exitCode)
        }

    @Test
    fun `execute should handle multi-line scripts`() =
        runTest {
            val scriptContent =
                """
                #!/bin/bash
                echo "Line 1"
                echo "Line 2"
                echo "Line 3"
                """.trimIndent()
            val command = Command.Script(content = scriptContent, shell = "bash", timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 30
            every { mockFileOperations.readText(mockStdOutPath) } returns "Line 1\nLine 2\nLine 3"

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            scriptExecutor.execute(command, executionContext, mockSpan)

            coVerify {
                mockShellExecutor.cmd(
                    command = any(),
                    stdOut = any(),
                    stdErr = any(),
                    stdIn = scriptContent,
                    env = any(),
                    timeout = any(),
                )
            }
        }

    @Test
    fun `execute should use stdOut and stdErr from context`() =
        runTest {
            val command = Command.Script(content = "echo test", shell = "bash", timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 10
            every { mockFileOperations.readText(mockStdOutPath) } returns "test"

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            scriptExecutor.execute(command, executionContext, mockSpan)

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

    @Test
    fun `execute should handle empty script content`() =
        runTest {
            val command = Command.Script(content = "", shell = "bash", timeout = 60)

            every { mockFileOperations.fileSize(mockStdOutPath) } returns 0
            every { mockFileOperations.readText(mockStdOutPath) } returns ""

            coEvery {
                mockShellExecutor.cmd(any(), any(), any(), any(), any(), any())
            } returns TestFixtures.mockCommandResult(exitCode = 0, stdOut = mockStdOutPath, stdErr = mockStdErrPath)

            val result = scriptExecutor.execute(command, executionContext, mockSpan)

            assertEquals(0, result.exitCode)
            coVerify {
                mockShellExecutor.cmd(
                    command = any(),
                    stdOut = any(),
                    stdErr = any(),
                    stdIn = "",
                    env = any(),
                    timeout = any(),
                )
            }
        }
}
