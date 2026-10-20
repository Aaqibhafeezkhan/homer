package homer.app.infra

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/**
 * Containment check for filesystem paths.
 *
 * String-level `..` rejection (already done by `ArtifactService.validateRelativePath`)
 * stops the obvious traversal but does nothing against:
 *  - Symlinks created inside the workspace that point at host files.
 *  - Multi-stage paths that resolve outside the root via OS-level
 *    canonicalization differences (case-insensitive FS, junctions, etc).
 *
 * This helper compares `realpath` of both root and candidate when each
 * exists, and falls back to normalized lexical containment otherwise
 * (so callers that resolve a path before the file is written — common
 * for streamed artifacts — still get a check). The candidate is
 * considered contained if it sits inside the root by both the
 * lexical-normalized view and the realpath-canonical view; mismatches
 * between the two (e.g. the symlinked-out case) are still rejected.
 *
 * Throws `SecurityException` if the candidate is not inside the root.
 * Closes audit P0 #4.
 */
internal object PathSafety {
    fun assertContained(
        root: Path,
        candidate: Path,
    ): Path {
        val rootExists = Files.exists(root)
        val candidateExists = Files.exists(candidate)

        val lexicalRoot = root.toAbsolutePath().normalize()
        val lexicalCandidate = candidate.toAbsolutePath().normalize()

        // When neither root nor candidate exists, we can't call
        // toRealPath() directly. Walk up to the longest existing ancestor,
        // canonicalize that, and append the remainder so we still detect
        // symlink-escapes for prefix segments — and the two sides stay
        // consistent (e.g. on macOS `/tmp` -> `/private/tmp`).
        val realRoot = if (rootExists) root.toRealPath() else resolveLongestExistingPrefix(lexicalRoot)
        val realCandidate =
            if (candidateExists) {
                candidate.toRealPath()
            } else {
                resolveLongestExistingPrefix(lexicalCandidate)
            }

        // Both views must agree: the candidate must be contained both
        // lexically and under realpath canonicalization.
        val lexicalContained = lexicalCandidate.startsWith(lexicalRoot)
        val realContained = realCandidate.startsWith(realRoot)

        if (!lexicalContained || !realContained) {
            throw SecurityException(
                "Path '$candidate' resolves outside root '$root' (resolved to '$realCandidate')",
            )
        }
        // For existing candidates return the realpath; for non-existing
        // ones return the lexically-normalized absolute path so callers
        // that go on to create the file write to the path they expect.
        return if (candidateExists) realCandidate else lexicalCandidate
    }

    /**
     * Recursively deletes [dir], but only after asserting it is contained within
     * [root]. Workspace-cleanup paths (capacity eviction, the purge sweep) read the
     * directory back from persisted state (`processes.artifacts_dir`) rather than the
     * freshly-validated workspace config, so without this re-check a poisoned or
     * crafted stored path could steer `deleteRecursively` at an arbitrary host
     * directory. Throws [SecurityException] (deleting nothing) when [dir] resolves
     * outside [root]. Closes "Unsafe Recursive Workspace Deletion" (CWE-22/73).
     */
    @OptIn(ExperimentalPathApi::class)
    fun deleteContainedRecursively(
        root: Path,
        dir: Path,
    ) {
        val resolved = assertContained(root, dir)
        val normalizedRoot = root.toAbsolutePath().normalize()
        // Strict descent: assertContained accepts dir == root (a set contains itself),
        // but the delete callers (purge sweep, capacity eviction) pass a per-run
        // projectRoot read back from persisted state. A poisoned/degenerate value
        // equal to the root must not turn a workspace cleanup into a wipe of the
        // whole workspace root. Root §12.
        if (resolved == normalizedRoot || dir.toAbsolutePath().normalize() == normalizedRoot) {
            throw SecurityException("Refusing to recursively delete the workspace root itself: $dir")
        }
        dir.deleteRecursively()
    }

    /**
     * Asserts no path component strictly below [root] — up to and including
     * [candidate] — is a symbolic link. Call AFTER [assertContained], which
     * guarantees [candidate] is lexically inside [root] but *follows* symlinks
     * and only checks the resolved target stays in root. That is not enough for
     * per-run workspace isolation: an earlier same-UID run can plant a symlink at
     * a predictable future per-run path (process ids are monotonic), so two
     * string-distinct per-run paths resolve to one directory — cross-run, and
     * across owner boundaries. This walk rejects that (CWE-59).
     *
     * lstat-based ([Files.isSymbolicLink] never follows), and it only inspects
     * components strictly below [root], so a symlink in the root's own ancestry
     * (e.g. /tmp -> /private/tmp on Darwin/CI) is not flagged.
     *
     * Residual: a component created as a symlink after this check but before the
     * workspace directory is materialized is a same-UID race — not a trust
     * boundary (§12), same class as LocalFileOperations.openBounded's documented residual.
     *
     * Throws [SecurityException] on a symlink component.
     */
    fun assertNoSymlinkComponentsBelow(
        root: Path,
        candidate: Path,
    ) {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedCandidate = candidate.toAbsolutePath().normalize()
        if (normalizedCandidate == normalizedRoot) return
        // relativize is safe: assertContained already proved lexical containment.
        var current = normalizedRoot
        for (segment in normalizedRoot.relativize(normalizedCandidate)) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) {
                throw SecurityException(
                    "workspace path component '$current' is a symlink; a per-run workspace path must not traverse symlinks",
                )
            }
        }
    }

    /**
     * A write that follows a *dangling* symlink at [candidate] creates the link's
     * destination, which [assertContained] cannot see: the link does not resolve, so
     * it falls back to the link's own lexical path. Follows the chain hop by hop and
     * requires every destination to resolve inside [root]; a dangling link that stays
     * inside is allowed. CWE-59.
     *
     * Destinations are compared by realpath only and never normalized first: the kernel
     * applies `..` after following links, so a lexical view can disagree with where the
     * write lands, and an absolute target may spell the root through another alias.
     */
    fun assertDanglingLinkContained(
        root: Path,
        candidate: Path,
    ) {
        val realRoot = resolveLongestExistingPrefix(root.toAbsolutePath())
        var current = candidate.toAbsolutePath()
        var hops = 0
        while (Files.isSymbolicLink(current) && !Files.exists(current)) {
            if (++hops > MAX_LINK_HOPS) throw SecurityException("symlink chain at '$candidate' is too long")
            current = current.resolveSibling(Files.readSymbolicLink(current))
            if (!resolveLongestExistingPrefix(current).startsWith(realRoot)) {
                throw SecurityException("dangling symlink at '$candidate' leads outside '$root'")
            }
        }
    }

    /**
     * True when [path] exists but is not a single-name regular file: a symlink (dangling
     * or not), FIFO, device, directory, or a hard link, whose other name may be anywhere.
     * A read that serves a run's own file must refuse it; a missing path answers false.
     * Without the `unix` attribute view nlink reads as 1. CWE-59.
     */
    fun isLinkedOrSpecial(path: Path): Boolean {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return true
        return isHardLinked(path)
    }

    /**
     * True when [path] itself (not a link's target) has more than one link, i.e. the same
     * bytes are also reachable under a name a walk never sees, so containment cannot be
     * decided. Without the `unix` attribute view nlink reads as 1 and the answer is false.
     * The one hard-link check for every upload walk and linked-file refusal. CWE-59.
     */
    fun isHardLinked(path: Path): Boolean {
        // Some attribute view implementations report nlink as Long rather than Int;
        // a bare `as? Int` would silently drop those and read as unlinked (CWE-59).
        val nlink =
            runCatching {
                Files.readAttributes(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)["nlink"] as? Number
            }.getOrNull()
        return nlink != null && nlink.toLong() > 1
    }

    /** The kernel's own ELOOP bound on Linux. */
    private const val MAX_LINK_HOPS = 40

    /**
     * Where a write to [candidate] would land (absolute, canonical): the candidate
     * with its own dangling symlink chain followed AND its intermediate symlink
     * components resolved — `link/new.txt` is not itself a link, but the write
     * follows `link`. Companion to [assertDanglingLinkContained], which throws on a
     * chain that leaves the root — a caller that first asserted containment gets
     * the landing path from here to run its own boundary checks on (a lexical gate
     * alone reads the link's name, never the link's destination).
     * CWE-59.
     */
    fun resolveDanglingLinkTarget(candidate: Path): Path {
        var current = candidate.toAbsolutePath()
        var hops = 0
        while (Files.isSymbolicLink(current) && !Files.exists(current)) {
            if (++hops > MAX_LINK_HOPS) break
            current = current.resolveSibling(Files.readSymbolicLink(current))
        }
        return resolveLongestExistingPrefix(current.toAbsolutePath().normalize())
    }

    /**
     * Walk up `path`'s ancestry until we find a path that exists on
     * disk. Canonicalize that ancestor with `toRealPath()` and
     * re-append the remainder lexically. If no ancestor exists,
     * return the lexically-normalized absolute path unchanged.
     */
    private fun resolveLongestExistingPrefix(path: Path): Path {
        var existing: Path? = path
        while (existing != null && !Files.exists(existing)) {
            existing = existing.parent
        }
        if (existing == null) return path
        val real = existing.toRealPath()
        val remainder = existing.relativize(path)
        return if (remainder.toString().isEmpty()) real else real.resolve(remainder).normalize()
    }
}
