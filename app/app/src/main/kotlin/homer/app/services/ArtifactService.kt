package homer.app.services

import homer.app.infra.FileOperations
import homer.app.infra.PathSafety
import homer.app.models.ArtifactEntry
import homer.app.models.ArtifactListingResponse
import org.slf4j.Logger
import java.io.OutputStream
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

class ArtifactService(
    private val processManager: ProcessManager,
    private val fileOperations: FileOperations,
    private val logger: Logger,
    private val maxArtifactBytes: Long = DEFAULT_MAX_ARTIFACT_BYTES,
    private val maxListEntries: Int = MAX_ARTIFACT_LIST_ENTRIES,
) {
    /**
     * Pre-flight size check for [path]. Returns success with the file size
     * when it is within [maxArtifactBytes]; failure with an
     * [ArtifactTooLargeException] otherwise. Used by the artifact route to
     * answer with `413 Payload Too Large` before opening the stream, so a
     * caller-induced multi-GB download cannot turn into a sustained
     * bandwidth/time DoS. Audit P1 #18.
     */
    fun checkArtifactSize(path: Path): Result<Long> {
        val size = fileOperations.fileSize(path)
        if (size > maxArtifactBytes) {
            return Result.failure(ArtifactTooLargeException(size, maxArtifactBytes))
        }
        return Result.success(size)
    }

    /**
     * Validates a relative path to prevent directory traversal attacks
     * Returns the normalized path if valid, or an error result
     */
    fun validateRelativePath(path: String): Result<String> {
        if (path.isBlank()) {
            return Result.failure(IllegalArgumentException("Path cannot be empty"))
        }

        // Reject absolute paths
        if (path.startsWith("/") || path.startsWith("\\")) {
            return Result.failure(IllegalArgumentException("Absolute paths are not allowed"))
        }

        // Reject paths containing ..
        if (path.contains("..")) {
            return Result.failure(IllegalArgumentException("Path traversal (..) is not allowed"))
        }

        // Normalize the path
        val normalized = Path.of(path).normalize().invariantSeparatorsPathString

        // After normalization, verify it still doesn't start with .. or /
        if (normalized.startsWith("..") || normalized.startsWith("/")) {
            return Result.failure(IllegalArgumentException("Path escapes the artifacts directory"))
        }

        return Result.success(normalized)
    }

    /**
     * Resolves an artifact path for a given process and relative path
     * Returns the full path if valid, or an error result
     */
    suspend fun resolveArtifactPath(
        processId: Int,
        relativePath: String,
    ): Result<Path> {
        val processInfo =
            processManager.getProcessInfo(processId)
                ?: return Result.failure(IllegalArgumentException("Process not found"))

        val artifactsDir =
            processInfo.artifactsDir
                ?: return Result.failure(IllegalArgumentException("Process has no artifacts directory"))

        val fullPath = artifactsDir.resolve(relativePath)
        val contained =
            try {
                PathSafety.assertContained(artifactsDir, fullPath)
            } catch (_: SecurityException) {
                // Surface as "not found" instead of "forbidden" — leaking
                // the distinction lets an attacker probe filesystem layout.
                return Result.failure(IllegalArgumentException("Artifact not found"))
            }
        // Reject an existing non-regular file (FIFO/device/directory/symlink) and a
        // hard-linked regular file (its other name may be another run's artifact —
        // the same cross-principal read LogTailService/LlmEventStreamService refuse
        // for stdout, CWE-59). Files.size reports 0 for a FIFO so the size pre-flight
        // passes, then the blocking read would pin the request thread forever. A
        // not-yet-existing path still succeeds — streamed/tailed artifacts resolve
        // before they are written.
        if (PathSafety.isLinkedOrSpecial(contained)) {
            return Result.failure(IllegalArgumentException("Artifact not found"))
        }
        return Result.success(contained)
    }

    /**
     * Lists regular files under the process's artifacts directory (recursive).
     * Paths are relative to the artifacts directory, use forward slashes, and are sorted
     * ascending. The listing is capped at [maxListEntries] entries — a directory with
     * more files yields exactly that many with `truncated = true`, so an agent that
     * writes an enormous number of artifacts cannot exhaust memory here. Returns
     * failure if the process is unknown, has no artifacts dir, or the directory does
     * not exist on disk.
     */
    suspend fun listArtifacts(processId: Int): Result<ArtifactListingResponse> {
        val processInfo =
            processManager.getProcessInfo(processId)
                ?: return Result.failure(IllegalArgumentException("Process not found"))

        val artifactsDir =
            processInfo.artifactsDir
                ?: return Result.failure(IllegalArgumentException("Process has no artifacts directory"))

        if (!fileOperations.dirExists(artifactsDir)) {
            return Result.failure(IllegalArgumentException("Artifacts directory does not exist"))
        }

        // Request one past the cap so we can tell "exactly at the cap" from "more".
        val walked = fileOperations.listRegularFiles(artifactsDir, maxListEntries + 1)
        val truncated = walked.size > maxListEntries
        val entries =
            walked
                .take(maxListEntries)
                .map { file ->
                    ArtifactEntry(
                        path = artifactsDir.relativize(file).invariantSeparatorsPathString,
                        size = fileOperations.fileSize(file),
                        modified = fileOperations.lastModifiedEpochSecond(file),
                    )
                }.sortedBy { it.path }

        return Result.success(
            ArtifactListingResponse(
                processId = processId,
                artifactsDir = artifactsDir.toString(),
                files = entries,
                truncated = truncated,
            ),
        )
    }

    /**
     * Streams an artifact file to the output stream
     * For active files (still being written), uses tail-like incremental streaming
     * For complete files, streams the entire file at once
     */
    suspend fun streamArtifact(
        artifactPath: Path,
        output: OutputStream,
    ) {
        streamCompleteFile(artifactPath, output)
    }

    /**
     * Streams a complete file in chunks
     */
    private suspend fun streamCompleteFile(
        artifactPath: Path,
        output: OutputStream,
    ) {
        logger.debug("Streaming complete file: {}", artifactPath)
        fileOperations.streamFile(artifactPath, output)
    }

    companion object {
        const val DEFAULT_MAX_ARTIFACT_BYTES: Long = 100L * 1024 * 1024

        /** Hard cap on entries returned by [listArtifacts]; excess is dropped and flagged. */
        const val MAX_ARTIFACT_LIST_ENTRIES: Int = 10_000
    }
}

class ArtifactTooLargeException(
    val size: Long,
    val maxBytes: Long,
) : RuntimeException("Artifact size $size exceeds maximum $maxBytes")
