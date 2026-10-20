package homer.app.fixtures

import homer.app.config.ExecutionConfig
import homer.app.executors.ExecutionContext
import homer.app.infra.FileOperations
import homer.app.infra.ShellExecutor
import homer.app.infra.TelemetryService
import homer.app.infra.Workspace
import homer.app.infra.WorkspaceConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.Tracer
import org.junit.jupiter.api.Assumptions
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.Path

/**
 * Common test fixtures and mock objects for unit tests
 */
object TestFixtures {
    /**
     * Creates a FIFO at [fifo], or skips the test where `mkfifo` is unavailable (non-POSIX
     * hosts). Never reads it: a blocking open is exactly what the guards under test prevent.
     */
    fun mkfifoOrSkip(fifo: Path) {
        val made = runCatching { ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() == 0 }.getOrDefault(false)
        Assumptions.assumeTrue(made && Files.exists(fifo), "mkfifo unavailable; skipping FIFO case")
    }

    /**
     * Creates a relaxed mock Logger that allows all method calls
     */
    fun mockLogger(): Logger = mockk(relaxed = true)

    /**
     * Creates a mock Span with common method stubs
     */
    fun mockSpan(): Span =
        mockk(relaxed = true) {
            every { setAttribute(any<String>(), any<String>()) } returns this
            every { setAttribute(any<String>(), any<Long>()) } returns this
            every { setAttribute(any<String>(), any<Boolean>()) } returns this
            every { setStatus(any()) } returns this
            every { setStatus(any(), any<String>()) } returns this
        }

    /**
     * Creates a mock SpanBuilder that returns a mock Span
     */
    fun mockSpanBuilder(span: Span = mockSpan()): SpanBuilder =
        mockk {
            every { setParent(any()) } returns this
            every { setStartTimestamp(any()) } returns this
            every { startSpan() } returns span
        }

    /**
     * Creates a mock Tracer that returns a mock SpanBuilder
     */
    fun mockTracer(span: Span = mockSpan()): Tracer =
        mockk {
            every { spanBuilder(any()) } returns mockSpanBuilder(span)
        }

    /**
     * Creates a mock TelemetryService with a mock Tracer
     */
    fun mockTelemetryService(span: Span = mockSpan()): TelemetryService =
        mockk {
            every { tracer } returns mockTracer(span)
        }

    /**
     * Creates a mock WorkspaceConfig with default values
     */
    fun mockWorkspaceConfig(
        serverRoot: Path = Path("/tmp/test/server"),
        projectRoot: Path = Path("/tmp/test/server/1"),
        artifacts: Path = Path("/tmp/test/server/1/artifacts"),
        workdir: Path = Path("/tmp/test/server/1/workdir"),
        env: Map<String, String> = emptyMap(),
        clearAfterFinish: Boolean = true,
        lockKey: String = "test-lock-key",
        ancestorLockKeys: List<String> = emptyList(),
        maxOutputBytes: Long = Long.MAX_VALUE,
        secretEnvNames: Set<String> = emptySet(),
        parentEnvLayerNames: Set<String> = emptySet(),
    ): WorkspaceConfig =
        WorkspaceConfig(
            serverRoot = serverRoot,
            projectRoot = projectRoot,
            artifacts = artifacts,
            workdir = workdir,
            env = env,
            clearAfterFinish = clearAfterFinish,
            lockKey = lockKey,
            ancestorLockKeys = ancestorLockKeys,
            maxOutputBytes = maxOutputBytes,
            secretEnvNames = secretEnvNames,
            parentEnvLayerNames = parentEnvLayerNames,
        )

    /**
     * Creates a mock ExecutionContext with default values
     */
    fun mockExecutionContext(
        workspace: Workspace = mockWorkspace(),
        config: ExecutionConfig = ExecutionConfig(),
        shell: ShellExecutor = mockShellExecutor(),
        stdOut: Path = Path("/tmp/test/stdout.txt"),
        stdErr: Path = Path("/tmp/test/stderr.txt"),
    ): ExecutionContext =
        ExecutionContext(
            workspace = workspace,
            config = config,
            shell = shell,
            stdOut = stdOut,
            stdErr = stdErr,
        )

    /**
     * Creates a relaxed mock ShellExecutor
     */
    fun mockShellExecutor(): ShellExecutor = mockk(relaxed = true)

    /**
     * Creates a relaxed mock Workspace
     */
    fun mockWorkspace(config: WorkspaceConfig = mockWorkspaceConfig()): Workspace =
        mockk(relaxed = true) {
            every { this@mockk.config } returns config
            coEvery { this@mockk.cleanup() } returns Unit
        }

    /**
     * Creates a mock FileOperations with configurable responses
     */
    fun mockFileOperations(): FileOperations = mockk(relaxed = true)

    /**
     * Creates a mock CommandResult with default paths
     */
    fun mockCommandResult(
        exitCode: Int = 0,
        stdOut: Path = Path("/tmp/test/stdout.txt"),
        stdErr: Path = Path("/tmp/test/stderr.txt"),
    ): homer.app.models.CommandResult =
        homer.app.models.CommandResult(
            exitCode = exitCode,
            stdOut = stdOut,
            stdErr = stdErr,
        )

    /**
     * Returns true only when chmod-style POSIX read-only bits actually prevent writes
     * for the running process. False on non-POSIX filesystems and when running as root,
     * which bypasses mode bits (common in CI containers).
     */
    fun posixPermissionsEnforced(): Boolean {
        val tmp =
            try {
                Files.createTempDirectory("posix-perm-check")
            } catch (_: Exception) {
                return false
            }
        return try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("r-xr-xr-x"))
            !Files.isWritable(tmp)
        } catch (_: UnsupportedOperationException) {
            false
        } finally {
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwxr-xr-x"))
            } catch (_: Exception) {
                // best effort
            }
            try {
                Files.deleteIfExists(tmp)
            } catch (_: Exception) {
                // best effort
            }
        }
    }
}
