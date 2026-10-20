package homer.app.services

import homer.app.fixtures.TestFixtures
import homer.app.infra.FileOperations
import homer.app.models.ArtifactEntry
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.Logger
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

class ArtifactServiceTest {
    private val processManager = mockk<ProcessManager>()
    private val fileOperations = mockk<FileOperations>()
    private val logger = mockk<Logger>(relaxed = true)
    private val artifactService = ArtifactService(processManager, fileOperations, logger)

    @Test
    fun `validateRelativePath - accepts valid relative paths`() {
        val validPaths = listOf("cmd_0.out", "subdir/file.txt", "a/b/c.log")

        validPaths.forEach { path ->
            val result = artifactService.validateRelativePath(path)
            assertTrue(result.isSuccess, "Path '$path' should be valid")
        }
    }

    @Test
    fun `validateRelativePath - rejects path traversal with double dots`() {
        val result = artifactService.validateRelativePath("../etc/passwd")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("traversal") == true)
    }

    @Test
    fun `validateRelativePath - rejects absolute paths starting with slash`() {
        val result = artifactService.validateRelativePath("/etc/passwd")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Absolute paths") == true)
    }

    @Test
    fun `validateRelativePath - rejects normalized escape paths`() {
        val result = artifactService.validateRelativePath("foo/../../etc/passwd")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("..") == true)
    }

    @Test
    fun `validateRelativePath - rejects empty path`() {
        val result = artifactService.validateRelativePath("")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("empty") == true)
    }

    @Test
    fun `validateRelativePath - rejects blank path`() {
        val result = artifactService.validateRelativePath("   ")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("empty") == true)
    }

    @Test
    fun `resolveArtifactPath - returns full path when process and artifacts dir exist`() =
        runTest {
            val processId = 123
            val relativePath = "cmd_0.out"
            val artifactsDir = Path("/tmp/homer/workspace-123/artifacts")
            val expectedPath = artifactsDir.resolve(relativePath)

            coEvery { processManager.getProcessInfo(processId) } returns
                ProcessInfo(
                    id = processId,
                    status = ProcessStatus.FINISHED,
                    agentName = "test-agent",
                    artifactsDir = artifactsDir,
                )

            val result = artifactService.resolveArtifactPath(processId, relativePath)

            assertTrue(result.isSuccess)
            assertEquals(expectedPath, result.getOrNull())
        }

    @Test
    fun `resolveArtifactPath - fails when process not found`() =
        runTest {
            val processId = 999
            coEvery { processManager.getProcessInfo(processId) } returns null

            val result = artifactService.resolveArtifactPath(processId, "cmd_0.out")

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("Process not found") == true)
        }

    @Test
    fun `resolveArtifactPath - fails when process has no artifacts directory`() =
        runTest {
            val processId = 123
            coEvery { processManager.getProcessInfo(processId) } returns
                ProcessInfo(
                    id = processId,
                    status = ProcessStatus.FINISHED,
                    agentName = "test-agent",
                    artifactsDir = null,
                )

            val result = artifactService.resolveArtifactPath(processId, "cmd_0.out")

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("no artifacts directory") == true)
        }

    @Test
    fun `resolveArtifactPath - rejects symlink escaping the artifacts dir`(
        @TempDir artifactsDir: Path,
        @TempDir outside: Path,
    ) = runTest {
        val processId = 555
        val outsideFile = outside.resolve("secret.txt").also { Files.createFile(it) }
        artifactsDir.resolve("link").also { Files.createSymbolicLink(it, outsideFile) }

        coEvery { processManager.getProcessInfo(processId) } returns
            ProcessInfo(
                id = processId,
                agentName = "x",
                status = ProcessStatus.FINISHED,
                artifactsDir = artifactsDir,
            )

        val result = artifactService.resolveArtifactPath(processId, "link")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not found") == true)
    }

    @Test
    fun `resolveArtifactPath - rejects a directory inside the artifacts dir`(
        @TempDir artifactsDir: Path,
    ) = runTest {
        val processId = 556
        Files.createDirectory(artifactsDir.resolve("sub"))

        coEvery { processManager.getProcessInfo(processId) } returns
            ProcessInfo(
                id = processId,
                agentName = "x",
                status = ProcessStatus.FINISHED,
                artifactsDir = artifactsDir,
            )

        val result = artifactService.resolveArtifactPath(processId, "sub")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not found") == true)
    }

    @Test
    fun `resolveArtifactPath - rejects a FIFO inside the artifacts dir`(
        @TempDir artifactsDir: Path,
    ) = runTest {
        val fifo = artifactsDir.resolve("pipe")
        TestFixtures.mkfifoOrSkip(fifo)

        val processId = 557
        coEvery { processManager.getProcessInfo(processId) } returns
            ProcessInfo(
                id = processId,
                agentName = "x",
                status = ProcessStatus.FINISHED,
                artifactsDir = artifactsDir,
            )

        val result = artifactService.resolveArtifactPath(processId, "pipe")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not found") == true)
    }

    @Test
    fun `resolveArtifactPath - rejects a hard-linked file inside the artifacts dir`(
        @TempDir artifactsDir: Path,
    ) = runTest {
        val processId = 558
        val original = artifactsDir.resolve("original.txt").also { Files.writeString(it, "x") }
        Files.createLink(artifactsDir.resolve("linked.txt"), original)

        coEvery { processManager.getProcessInfo(processId) } returns
            ProcessInfo(
                id = processId,
                agentName = "x",
                status = ProcessStatus.FINISHED,
                artifactsDir = artifactsDir,
            )

        val result = artifactService.resolveArtifactPath(processId, "linked.txt")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not found") == true)
    }

    @Test
    fun `resolveArtifactPath - accepts a plain regular file inside the artifacts dir`(
        @TempDir artifactsDir: Path,
    ) = runTest {
        val processId = 559
        Files.writeString(artifactsDir.resolve("plain.txt"), "x")

        coEvery { processManager.getProcessInfo(processId) } returns
            ProcessInfo(
                id = processId,
                agentName = "x",
                status = ProcessStatus.FINISHED,
                artifactsDir = artifactsDir,
            )

        val result = artifactService.resolveArtifactPath(processId, "plain.txt")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `streamCompleteFile - streams entire file in chunks`() =
        runTest {
            val artifactPath = Path("/tmp/homer/workspace-123/artifacts/cmd_0.out")
            val output = ByteArrayOutputStream()
            val fileContent = "Hello, World!".toByteArray()

            coEvery { fileOperations.streamFile(artifactPath, output, any()) } coAnswers {
                output.write(fileContent)
            }

            coEvery { processManager.getProcessInfo(123) } returns
                ProcessInfo(
                    id = 123,
                    status = ProcessStatus.FINISHED,
                    agentName = "test-agent",
                    artifactsDir = Path("/tmp/homer/workspace-123/artifacts"),
                )

            artifactService.streamArtifact(artifactPath, output)

            coVerify { fileOperations.streamFile(artifactPath, output, any()) }
            assertEquals("Hello, World!", output.toString())
        }

    @Test
    fun `listArtifacts - fails when process not found`() =
        runTest {
            coEvery { processManager.getProcessInfo(42) } returns null

            val result = artifactService.listArtifacts(42)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("Process not found") == true)
        }

    @Test
    fun `listArtifacts - fails when process has no artifacts directory`() =
        runTest {
            coEvery { processManager.getProcessInfo(42) } returns
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.CREATED,
                    agentName = "a",
                    artifactsDir = null,
                )

            val result = artifactService.listArtifacts(42)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("no artifacts directory") == true)
        }

    @Test
    fun `listArtifacts - fails when artifacts directory does not exist on disk`() =
        runTest {
            val dir = Path("/tmp/homer-nonexistent-${System.nanoTime()}")
            coEvery { processManager.getProcessInfo(42) } returns
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.CREATED,
                    agentName = "a",
                    artifactsDir = dir,
                )
            every { fileOperations.dirExists(dir) } returns false

            val result = artifactService.listArtifacts(42)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("does not exist") == true)
        }

    @Test
    fun `listArtifacts - returns empty list when dir exists but has no files`() =
        runTest {
            val dir = Path("/tmp/homer-empty")
            coEvery { processManager.getProcessInfo(42) } returns
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FINISHED,
                    agentName = "a",
                    artifactsDir = dir,
                )
            every { fileOperations.dirExists(dir) } returns true
            every { fileOperations.listRegularFiles(dir, any()) } returns emptyList()

            val result = artifactService.listArtifacts(42)

            assertTrue(result.isSuccess)
            val listing = result.getOrNull()!!
            assertEquals(dir.toString(), listing.artifactsDir)
            assertTrue(listing.files.isEmpty())
        }

    @Test
    fun `listArtifacts - returns relative paths with forward slashes`() =
        runTest {
            val dir = Path("/tmp/homer-list")
            val file1 = dir.resolve("cmd_0.out")
            val file2 = dir.resolve("subdir").resolve("foo.log")
            coEvery { processManager.getProcessInfo(42) } returns
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FINISHED,
                    agentName = "a",
                    artifactsDir = dir,
                )
            every { fileOperations.dirExists(dir) } returns true
            every { fileOperations.listRegularFiles(dir, any()) } returns listOf(file2, file1)
            every { fileOperations.fileSize(file1) } returns 12L
            every { fileOperations.fileSize(file2) } returns 34L
            every { fileOperations.lastModifiedEpochSecond(file1) } returns 1_700_000_100L
            every { fileOperations.lastModifiedEpochSecond(file2) } returns 1_700_000_200L

            val result = artifactService.listArtifacts(42)

            assertTrue(result.isSuccess)
            val files = result.getOrNull()!!.files
            assertEquals(
                listOf(
                    ArtifactEntry("cmd_0.out", 12L, 1_700_000_100L),
                    ArtifactEntry("subdir/foo.log", 34L, 1_700_000_200L),
                ),
                files,
            )
        }

    @Test
    fun `listArtifacts - sorts entries by path ascending`() =
        runTest {
            val dir = Path("/tmp/homer-sort")
            val a = dir.resolve("z.log")
            val b = dir.resolve("a.log")
            val c = dir.resolve("m.log")
            coEvery { processManager.getProcessInfo(42) } returns
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FINISHED,
                    agentName = "a",
                    artifactsDir = dir,
                )
            every { fileOperations.dirExists(dir) } returns true
            every { fileOperations.listRegularFiles(dir, any()) } returns listOf(a, b, c)
            every { fileOperations.fileSize(any()) } returns 1L
            every { fileOperations.lastModifiedEpochSecond(any()) } returns 0L

            val result = artifactService.listArtifacts(42)

            val paths = result.getOrNull()!!.files.map { it.path }
            assertEquals(listOf("a.log", "m.log", "z.log"), paths)
        }

    @Test
    fun `listArtifacts - caps entries and flags truncation when over the cap`() =
        runTest {
            val capped = ArtifactService(processManager, fileOperations, logger, maxListEntries = 3)
            val dir = Path("/tmp/homer-cap")
            // listRegularFiles is asked for cap+1; simulate the walk hitting that ceiling.
            val files = (1..4).map { dir.resolve("f$it.log") }
            coEvery { processManager.getProcessInfo(42) } returns
                ProcessInfo(id = 42, status = ProcessStatus.FINISHED, agentName = "a", artifactsDir = dir)
            every { fileOperations.dirExists(dir) } returns true
            every { fileOperations.listRegularFiles(dir, 4) } returns files
            every { fileOperations.fileSize(any()) } returns 1L
            every { fileOperations.lastModifiedEpochSecond(any()) } returns 0L

            val listing = capped.listArtifacts(42).getOrNull()!!

            assertEquals(3, listing.files.size)
            assertTrue(listing.truncated)
        }

    @Test
    fun `listArtifacts - not truncated when at or under the cap`() =
        runTest {
            val capped = ArtifactService(processManager, fileOperations, logger, maxListEntries = 3)
            val dir = Path("/tmp/homer-cap2")
            val files = (1..3).map { dir.resolve("f$it.log") }
            coEvery { processManager.getProcessInfo(42) } returns
                ProcessInfo(id = 42, status = ProcessStatus.FINISHED, agentName = "a", artifactsDir = dir)
            every { fileOperations.dirExists(dir) } returns true
            every { fileOperations.listRegularFiles(dir, 4) } returns files
            every { fileOperations.fileSize(any()) } returns 1L
            every { fileOperations.lastModifiedEpochSecond(any()) } returns 0L

            val listing = capped.listArtifacts(42).getOrNull()!!

            assertEquals(3, listing.files.size)
            assertTrue(!listing.truncated)
        }

    @Test
    fun `checkArtifactSize - succeeds when file size is below the cap (audit P1 #18)`() {
        val cappedService =
            ArtifactService(
                processManager = processManager,
                fileOperations = fileOperations,
                logger = logger,
                maxArtifactBytes = 1024L,
            )
        val path = Path("/tmp/homer-small.bin")
        every { fileOperations.fileSize(path) } returns 512L

        val result = cappedService.checkArtifactSize(path)

        assertTrue(result.isSuccess)
        assertEquals(512L, result.getOrNull())
    }

    @Test
    fun `checkArtifactSize - succeeds when file size equals the cap (audit P1 #18)`() {
        val cappedService =
            ArtifactService(
                processManager = processManager,
                fileOperations = fileOperations,
                logger = logger,
                maxArtifactBytes = 1024L,
            )
        val path = Path("/tmp/homer-exact.bin")
        every { fileOperations.fileSize(path) } returns 1024L

        val result = cappedService.checkArtifactSize(path)

        assertTrue(result.isSuccess)
        assertEquals(1024L, result.getOrNull())
    }

    @Test
    fun `checkArtifactSize - fails with ArtifactTooLargeException when over cap (audit P1 #18)`() {
        val cappedService =
            ArtifactService(
                processManager = processManager,
                fileOperations = fileOperations,
                logger = logger,
                maxArtifactBytes = 1024L,
            )
        val path = Path("/tmp/homer-big.bin")
        every { fileOperations.fileSize(path) } returns 4096L

        val result = cappedService.checkArtifactSize(path)

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as? ArtifactTooLargeException
        assertTrue(ex != null, "expected ArtifactTooLargeException, got ${result.exceptionOrNull()}")
        assertEquals(4096L, ex!!.size)
        assertEquals(1024L, ex.maxBytes)
    }
}
