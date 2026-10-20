package homer.app.infra

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.streams.toList

/**
 * Prunes a run's workspace down to its declared retained artifacts: deletes
 * everything under the project root except artifact files matching the run's
 * `workspace.retain.artifacts` globs, which stay in place so the artifact
 * routes keep resolving them. See ADR-0053.
 */
internal object ArtifactRetention {
    const val MAX_PRUNE_WALK_ENTRIES = 10_000

    data class PruneResult(
        val keptFiles: Int,
        val truncated: Boolean,
    )

    fun prune(
        workspaceRoot: Path,
        artifactsDir: Path,
        globs: List<String>,
        maxBytes: Long,
        maxWalkEntries: Int = MAX_PRUNE_WALK_ENTRIES,
    ): PruneResult {
        // Normalize first: the dir comes from a DB round-trip, and a '.'-suffixed
        // form would defeat the fileName sibling comparison in deleteSiblings.
        val dir = artifactsDir.normalize()
        val projectRoot = dir.parent ?: return PruneResult(0, false)
        PathSafety.assertContained(workspaceRoot, projectRoot)
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            PathSafety.deleteContainedRecursively(workspaceRoot, projectRoot)
            return PruneResult(0, false)
        }
        // Bound the walk over agent-written structure (§12.4):
        // count raw entries (dirs included) so deep nesting can't dodge the cap.
        val entries =
            Files.walk(dir).use { stream ->
                stream.limit(maxWalkEntries + 1L).toList()
            }
        if (entries.size > maxWalkEntries) {
            // Fail closed: a tree this large is not a curated retained set.
            // Retain nothing and flag the truncation — never silent.
            PathSafety.deleteContainedRecursively(workspaceRoot, projectRoot)
            return PruneResult(0, true)
        }
        // NOFOLLOW + regular-files-only: everything here is agent-written, and a
        // symlink must not smuggle a host file into a long-lived, downloadable set.
        val candidates = entries.filter { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) }
        val (kept, truncated) = fitWithin(matchedFiles(dir, candidates, globs), maxBytes)
        if (kept.isEmpty()) {
            PathSafety.deleteContainedRecursively(workspaceRoot, projectRoot)
            return PruneResult(0, truncated)
        }
        deleteSiblings(workspaceRoot, projectRoot, dir)
        deleteUnkept(dir, kept)
        return PruneResult(kept.size, truncated)
    }

    /** The [candidates] matching [globs] relative to [dir], in declared-glob order, each glob's matches sorted. */
    private fun matchedFiles(
        dir: Path,
        candidates: List<Path>,
        globs: List<String>,
    ): Set<Path> {
        val matched = LinkedHashSet<Path>()
        for (glob in globs) {
            val matcher = FileSystems.getDefault().getPathMatcher("glob:$glob")
            candidates
                .filter { matcher.matches(dir.relativize(it)) }
                .sorted()
                .forEach { matched.add(it) }
        }
        return matched
    }

    /** The prefix-in-order subset of [matched] that fits [maxBytes], and whether any file did not fit. */
    private fun fitWithin(
        matched: Set<Path>,
        maxBytes: Long,
    ): Pair<Set<Path>, Boolean> {
        // Declared-glob order decides who fits under the cap; overflow is flagged,
        // never silent.
        var budget = maxBytes
        val kept = LinkedHashSet<Path>()
        var truncated = false
        for (file in matched) {
            val size = Files.size(file)
            if (size <= budget) {
                kept.add(file)
                budget -= size
            } else {
                truncated = true
            }
        }
        return kept to truncated
    }

    // Siblings of the artifacts dir (workdir, .homer, ...) can be arbitrarily
    // large and hold no retained files — stream-delete them instead of
    // materializing them into the reverse-order walk.
    private fun deleteSiblings(
        workspaceRoot: Path,
        projectRoot: Path,
        dir: Path,
    ) {
        Files.newDirectoryStream(projectRoot).use { children ->
            children.forEach { child ->
                // Compare file names, not full paths: Path.equals is structural, and
                // artifactsDir (a DB round-trip in production) can carry a redundant
                // segment (e.g. a trailing slash or "./") that DirectoryStream's freshly
                // constructed `child` never would — a mismatch here would stream-delete
                // the retained set itself. child's parent is projectRoot by construction,
                // so the file name alone is enough to identify it.
                if (child.fileName != dir.fileName) {
                    PathSafety.deleteContainedRecursively(workspaceRoot, child)
                }
            }
        }
    }

    /** Deletes everything under [dir] but the [kept] files and the directories holding them. */
    private fun deleteUnkept(
        dir: Path,
        kept: Set<Path>,
    ) {
        val keepDirs =
            kept
                .flatMap { file ->
                    generateSequence(file.parent) { it.parent }.takeWhile { it.startsWith(dir) }.toList()
                }.toSet() + dir
        // Reverse-order walk deletes children before parents; a dir holding a kept
        // file is in keepDirs and survives, an emptied dir is deleted. This walk is
        // unbounded (no maxWalkEntries re-check): prune only runs on terminal,
        // non-shared workspaces, so there is no live writer left to grow the tree
        // between the capped candidate walk and here. A future caller invoking prune
        // on a non-terminal workspace must not assume that still holds.
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path ->
                if (path in keepDirs) return@forEach
                // Re-check regular-file-under-NOFOLLOW at delete time, not just membership
                // in `kept`: the artifacts dir is agent-writable, so a matched regular file
                // could have been swapped for a symlink between the candidate walk and here.
                val stillKept = path in kept && path.isRegularFile(LinkOption.NOFOLLOW_LINKS)
                if (!stillKept) {
                    Files.deleteIfExists(path)
                }
            }
        }
    }
}
