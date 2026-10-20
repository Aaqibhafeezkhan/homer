package homer.app.infra

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Default implementation of FileOperations
 * Wraps Kotlin Path extension functions
 */
class LocalFileOperations : FileOperations {
    override fun fileSize(path: Path): Long =
        // NOFOLLOW: a stat over an agent-writable path must report the link's own
        // size, never the target's — else it is a size/existence oracle for host
        // files a swapped symlink points at. Security follow-up; root §12.
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size()

    override fun readText(path: Path): String = path.readText()

    override fun fileExists(path: Path): Boolean = path.exists()

    override suspend fun streamFile(
        path: Path,
        outputStream: OutputStream,
        bufferSize: Int,
    ) {
        withContext(Dispatchers.IO) {
            // Closes the resolve-vs-open window on a hard link: ArtifactService (and
            // callers like it) check isLinkedOrSpecial once at resolve, but nlink isn't
            // covered by O_NOFOLLOW, so re-check it here too, immediately before the
            // open. Residual: a link planted between this check and the open(2) below
            // still wins that race — same-UID, ADR-0047. CWE-59.
            if (PathSafety.isLinkedOrSpecial(path)) {
                throw IOException("Refusing to read non-regular file: $path")
            }
            // The open itself is O_NOFOLLOW — a symlink swapped in after the
            // pre-check fails the open instead of being followed.
            openBounded(path) { path.inputStream(LinkOption.NOFOLLOW_LINKS) }.use { input ->
                val buffer = ByteArray(bufferSize)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    outputStream.flush()
                }
            }
        }
    }

    override fun dirExists(path: Path): Boolean = path.exists() && path.isDirectory()

    override fun listRegularFiles(
        dir: Path,
        limit: Int,
    ): List<Path> =
        Files.walk(dir).use { stream ->
            // NOFOLLOW_LINKS: a symlink is not a regular file, so a symlink an agent
            // drops in its artifacts dir is excluded rather than listed with its
            // target's size/mtime. Files.walk already does not descend dir symlinks.
            // limit short-circuits the lazy walk so an oversized tree is never
            // fully materialized in memory (see ArtifactService's cap).
            stream
                .filter { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) }
                .limit(limit.toLong())
                .toList()
        }

    override fun lastModifiedEpochSecond(path: Path): Long =
        Files
            .readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            .lastModifiedTime()
            .toInstant()
            .epochSecond

    /**
     * Opens [path] for reading with two TOCTOU defences (see the artifact-race
     * hardening): re-assert the target is a regular file (NOFOLLOW) immediately
     * before opening, and run the blocking `open(2)` on a **bounded** daemon pool
     * capped at [OPEN_TIMEOUT_MS] per open. The [open] lambda itself now opens
     * with `LinkOption.NOFOLLOW_LINKS`, making the symlink check atomic with the
     * open — a symlink swapped in after this pre-check fails the open instead of
     * being followed. The pre-check and the bounded pool remain: they are what
     * catches a same-UID swap to a FIFO/device, which `O_NOFOLLOW` does not
     * refuse. A swap that still wins that residual race blocks a pool thread
     * instead of the caller. `open(2)` is not interruptible, so a genuinely
     * blocked open leaks its thread until a writer appears — but the pool is
     * capped at [MAX_OPEN_THREADS] with a synchronous handoff, so leaked threads
     * cannot grow without bound: once saturated, further opens fail fast
     * ([RejectedExecutionException] → [IOException]) rather than exhausting
     * threads. Normal opens complete in microseconds and return their thread
     * immediately, so the small cap does not throttle legitimate reads. A
     * bounded, documented residual acceptable under the single-UID model.
     */
    private fun <T : AutoCloseable> openBounded(
        path: Path,
        open: () -> T,
    ): T {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Refusing to read non-regular file: $path")
        }
        val future =
            try {
                OPEN_EXECUTOR.submit(Callable { open() })
            } catch (_: RejectedExecutionException) {
                throw IOException("Artifact reader pool saturated; refusing to open $path")
            }
        return awaitOpen(future, path)
    }

    /** Waits at most [OPEN_TIMEOUT_MS] for [future]'s open; every failure surfaces as an [IOException]. */
    @Suppress("SwallowedException") // unwraps the ExecutionException to the open's own failure
    private fun <T> awaitOpen(
        future: Future<T>,
        path: Path,
    ): T =
        try {
            future.get(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw IOException("Timed out opening artifact (possible non-regular file): $path")
        } catch (e: ExecutionException) {
            throw (e.cause as? IOException ?: IOException("Failed to open $path", e.cause))
        }

    private companion object {
        private const val OPEN_TIMEOUT_MS = 10_000L
        private const val MAX_OPEN_THREADS = 8

        // Bounded: a SynchronousQueue with max MAX_OPEN_THREADS means a submit is
        // accepted only if a thread is immediately free or one can still be spawned
        // under the cap, else it is rejected. Idle threads are reaped after 30s, so
        // normal load holds none; only opens blocked on a FIFO retain a slot, and
        // they can never exceed the cap.
        private val OPEN_EXECUTOR: ExecutorService =
            ThreadPoolExecutor(
                0,
                MAX_OPEN_THREADS,
                30L,
                TimeUnit.SECONDS,
                SynchronousQueue(),
            ) { r -> Thread(r, "artifact-open").apply { isDaemon = true } }
    }
}
