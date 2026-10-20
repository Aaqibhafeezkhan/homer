package homer.app.infra

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class Workspace(
    config: WorkspaceConfig,
    private val processSpan: Span,
    // True when another live run still references this workspace directory; defaults
    // to no sibling, so a directly constructed workspace (e.g. agentdebug) cleans as before.
    private val isSharedWithLiveSibling: suspend () -> Boolean = { false },
    // Time budget for the end-of-run delete. A constructor seam so a test can inject a
    // spent budget; not a config key (the ADR-0067 CLEAN_TIMEOUT constant pattern).
    private val cleanupTimeout: Duration = CLEANUP_TIMEOUT,
) {
    var config: WorkspaceConfig
        private set

    init {
        this.config = config
    }

    val shellExecutor = LocalShellExecutor(config.workdir, config.maxOutputBytes)

    fun appendToEnv(env: Map<String, String>) {
        config = config.copy(env = config.env + env)
    }

    fun create() {
        processSpan.setAttribute("path.root", config.projectRoot.toString())
        processSpan.setAttribute("path.artifacts", config.artifacts.toString())
        processSpan.setAttribute("path.workdir", config.workdir.toString())
        config.artifacts.createDirectories()
        config.workdir.createDirectories()
        processSpan.addEvent("Workspace created")
    }

    suspend fun cleanup() {
        // The workspace may be gone (deleted externally, or the run failed before
        // create()) — cleanup must not throw; treat it as empty.
        val hasArtifacts = config.artifacts.exists() && config.artifacts.listDirectoryEntries().isNotEmpty()
        processSpan.setAttribute("path.hasArtifacts", hasArtifacts)
        if (!config.clearAfterFinish) {
            processSpan.addEvent("Workspace left as is")
            return
        }
        // Skip the deletes while a live sibling (CREATED/WORKING) shares this path —
        // CWE-362. See ADR-0048 (2026-10-06 amendment).
        if (isSharedWithLiveSibling()) {
            processSpan.addEvent("Workspace cleanup skipped (shared with a live run)")
            return
        }
        val started = TimeSource.Monotonic.markNow()
        val deadline = started + cleanupTimeout
        var workdirResult = BoundedDeleteResult(deleted = 0, failed = 0, complete = true)
        if (config.workdir.exists()) {
            workdirResult = deleteWithin(config.workdir, deadline)
        }
        // `.homer/` is Homer-managed run tooling, not output; the next run rewrites it.
        // On a shared path the guard above already returned.
        val homerDir = config.projectRoot.resolve(QuestionsWorkspaceSetup.HOMER_DIR)
        var homerResult = BoundedDeleteResult(deleted = 0, failed = 0, complete = true)
        if (homerDir.exists()) {
            homerResult = deleteWithin(homerDir, deadline)
        }
        if (workdirResult.complete && homerResult.complete) {
            processSpan.addEvent("Workspace cleaned up")
        } else {
            // Counts and elapsed only — never a path or file name from the tree.
            processSpan.addEvent(
                "Workspace cleanup incomplete",
                Attributes.of(
                    AttributeKey.longKey("deleted"),
                    (workdirResult.deleted + homerResult.deleted).toLong(),
                    AttributeKey.longKey("failed"),
                    (workdirResult.failed + homerResult.failed).toLong(),
                    AttributeKey.longKey("elapsed_ms"),
                    started.elapsedNow().inWholeMilliseconds,
                ),
            )
        }
    }

    /**
     * Deletes [dir]'s tree bottom-up without following symlinks, stopping once [deadline]
     * has passed. The tree is workload-sized and therefore attacker-sized, so the walk is
     * time-bounded and a per-entry failure is skipped rather than aborting it; whatever is
     * left stays on disk for `WorkspacePurgeService`'s `purgeAfter` sweep.
     * CWE-400. `ensureActive()` is no use here: teardown runs under `NonCancellable`, so
     * the deadline is the only bound. See ADR-0048 (2026-10-14 amendment).
     */
    private fun deleteWithin(
        dir: Path,
        deadline: TimeMark,
    ): BoundedDeleteResult {
        if (!dir.exists()) return BoundedDeleteResult(deleted = 0, failed = 0, complete = true)
        val visitor = BoundedDeleteVisitor(deadline)
        Files.walkFileTree(dir, visitor)
        return BoundedDeleteResult(
            deleted = visitor.deleted,
            failed = visitor.failed,
            complete = !visitor.stopped && !dir.exists(),
        )
    }

    companion object {
        /** Bounds the end-of-run delete; a remainder goes to the `purgeAfter` sweep. CWE-400. */
        private val CLEANUP_TIMEOUT = 2.minutes
    }
}

/** Entries removed and skipped by one [Workspace.deleteWithin] walk, and whether the tree is gone. */
private data class BoundedDeleteResult(
    val deleted: Int,
    val failed: Int,
    val complete: Boolean,
)

/**
 * Bottom-up delete walk for [Workspace.deleteWithin]. `walkFileTree` without `FOLLOW_LINKS`
 * removes a symlink as a link, never its target; a delete that fails is counted and the walk
 * carries on, and once the deadline passes the walk terminates leaving the rest in place.
 */
private class BoundedDeleteVisitor(
    private val deadline: TimeMark,
) : SimpleFileVisitor<Path>() {
    var deleted = 0
    var failed = 0
    var stopped = false

    override fun visitFile(
        file: Path,
        attrs: BasicFileAttributes,
    ): FileVisitResult {
        if (deadline.hasPassedNow()) {
            stopped = true
            return FileVisitResult.TERMINATE
        }
        deleteEntry(file)
        return FileVisitResult.CONTINUE
    }

    override fun postVisitDirectory(
        dir: Path,
        exc: IOException?,
    ): FileVisitResult {
        if (deadline.hasPassedNow()) {
            stopped = true
            return FileVisitResult.TERMINATE
        }
        deleteEntry(dir)
        return FileVisitResult.CONTINUE
    }

    override fun visitFileFailed(
        file: Path,
        exc: IOException,
    ): FileVisitResult {
        // The deadline is checked here too: an all-failing tree is routed entry by entry
        // through this method, so the bound must not depend on visitFile/postVisitDirectory.
        if (deadline.hasPassedNow()) {
            stopped = true
            return FileVisitResult.TERMINATE
        }
        // An unreadable entry (permission, race) is skipped, not fatal to the walk.
        failed++
        return FileVisitResult.CONTINUE
    }

    private fun deleteEntry(entry: Path) {
        try {
            Files.delete(entry)
            deleted++
        } catch (_: IOException) {
            // Deliberately swallowed: the entry stays on disk for the purge sweep, and the
            // message would carry a path from the attacker's tree into the log (§12 rule 7).
            failed++
        }
    }
}
