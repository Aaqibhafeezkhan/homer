package homer.app.runners

import homer.app.infra.PathSafety
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * One entry of [TarStreams.extract]: the containment, link-refusal and mode-normalization
 * rules that treat the far side of a transfer as a trust boundary (ADR-0043, ADR-0047,
 * ADR-0055). Logs under [TarStreams], the API it implements.
 */
internal object TarExtraction {
    private val logger = LoggerFactory.getLogger(TarStreams::class.java)

    private val NOFOLLOW_WRITE =
        arrayOf<OpenOption>(
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS,
        )

    /** Extracts [entry] of [tar] under [local]; the contract is [TarStreams.extract]'s. */
    fun extractEntry(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        remoteIsDir: Boolean,
        local: Path,
        fileName: String,
        mode: DownloadMode,
        materialized: MutableSet<Path>?,
        sourceLabel: String,
        posixViewLookup: ((Path, Boolean) -> PosixFileAttributeView?)?,
    ) {
        // The pod is a trust boundary (same rationale as WorkspaceSync.containedChild):
        // never let a remote-supplied entry name escape the target, and never
        // materialize link entries. TarArchiveEntry.isFile() also returns true for
        // symlink/hardlink/device/fifo flags, so it must be paired with the negative
        // isSymbolicLink/isLink checks to actually exclude them.
        if (!entry.isDirectoryOrRegularFile) {
            logger.warn("Skipping non-regular tar entry '{}' from {}", entry.name, sourceLabel)
            return
        }
        val root = local.toAbsolutePath().normalize()
        val target = entryTarget(entry, remoteIsDir, local, root, fileName, materialized)
        if (entry.isDirectory) {
            Files.createDirectories(target)
            recordAncestors(root, target.parent, materialized)
            return
        }
        if (mode == DownloadMode.OVERLAY && Files.exists(target)) return
        target.parent?.let { Files.createDirectories(it) }
        recordAncestors(root, target.parent, materialized)
        // NOFOLLOW_LINKS on the single-file branch only: a symlink appearing at that one
        // target between the guards above and this write is refused by the kernel. The
        // directory branch must keep following a CONTAINED link — assertContained is the
        // escape guard there, and a live workspace legitimately holds such links.
        val options = if (remoteIsDir) emptyArray() else NOFOLLOW_WRITE
        Files.newOutputStream(target, *options).use { tar.copyTo(it) }
        // The entry mode is attacker-controlled (§12 rule 2): apply the git-style
        // 0755/0644 set, never the raw bits — see ADR-0047. The OVERLAY return above
        // leaves a skipped file's own mode intact (ADR-0055).
        val view = posixViewFor(target, remoteIsDir, posixViewLookup)
        // A null view is a non-POSIX target: no mode exists to normalize, so the write
        // stays bytes-only. When a view exists, any failed chmod — not only an
        // IOException — must not leave the just-truncated target in place with its
        // pre-existing (possibly permissive) mode — fail closed by removing it and
        // refusing the entry. A failed removal is folded into the refusal, so the denial
        // text says whether content was left behind. CWE-732.
        if (view != null) normalizeModeOrRefuse(view, target, entry, sourceLabel)
    }

    private val TarArchiveEntry.isDirectoryOrRegularFile get() = isDirectory || (isFile && !isSymbolicLink && !isLink)

    /** Where [entry] lands: a contained path under [local] (recorded), or [local] itself for a file download. */
    private fun entryTarget(
        entry: TarArchiveEntry,
        remoteIsDir: Boolean,
        local: Path,
        root: Path,
        fileName: String,
        materialized: MutableSet<Path>?,
    ): Path =
        if (remoteIsDir) {
            val lexicalTarget = TarStreams.containedEntryPath(local, entry.name)
            // Record the pure lexical (pre-realpath) target: the post-download prune
            // walk (prune()) stays lexical too, so the two must agree on path form.
            // assertContained's realpath step below exists only to reject symlink
            // escapes; the write itself uses its (possibly realpath'd) return value.
            materialized?.add(root.relativize(lexicalTarget))
            // containedEntryPath is lexical only (absolute/".."/NUL rejection); it can't
            // see a symlink already present in the destination. PathSafety adds the
            // realpath check, so a pre-existing local symlink can't steer the write
            // outside the workspace even when the entry name itself is lexically safe.
            PathSafety.assertContained(local, lexicalTarget).also {
                // The write below follows links, so a dangling one must lead inside too.
                PathSafety.assertDanglingLinkContained(local, lexicalTarget)
            }
        } else {
            // single-file download: the only expected entry is fileName itself. Strip a
            // literal "./" prefix (tar's convention for a bare filename), not every leading
            // dot — otherwise a dotfile basename like ".env" is rejected as unexpected.
            if (entry.name.removePrefix("./") != fileName) {
                throw SecurityException("Unexpected tar entry '${entry.name}' for single-file download")
            }
            // `local` IS the file here, so the destination's own directory is the
            // containment root: without this, a symlink planted at that path steers the
            // write at an arbitrary host file (the directory branch's guard runs only
            // there). assertContained catches a live link, assertNoSymlinkComponentsBelow
            // also catches a dangling one (lstat, no follow).
            val parent =
                root.parent ?: throw SecurityException("Single-file download target '$local' has no parent directory")
            PathSafety.assertContained(parent, local)
            PathSafety.assertNoSymlinkComponentsBelow(parent, local)
            local
        }

    /** Normalizes [target]'s mode; on any failure removes what was written and refuses [entry]. See [TarStreams.extract]. */
    private fun normalizeModeOrRefuse(
        view: PosixFileAttributeView,
        target: Path,
        entry: TarArchiveEntry,
        sourceLabel: String,
    ) {
        try {
            view.setPermissions(normalizedPermissions(entry.mode))
        } catch (e: Exception) {
            // On the directory branch the write above followed a contained dangling
            // symlink, so `target` names the link while the bytes landed at its
            // destination; deleting only the link would leave the attacker-supplied,
            // unnormalized file behind. Delete the landing path first, then the link.
            // CWE-59.
            val landing = PathSafety.resolveDanglingLinkTarget(target)
            // Track the two deletes separately so a failed one is logged with the path
            // that actually failed, and the denial still says content was left behind
            // when either fails (ADR-0047, 2026-10-06 amendment).
            val landingRemoved = runCatching { if (landing != target) Files.deleteIfExists(landing) }
            val linkRemoved = runCatching { Files.deleteIfExists(target) }
            landingRemoved.onFailure {
                logger.warn("Could not remove the landing path '{}' of '{}' after a failed mode normalization", landing, target, it)
            }
            linkRemoved.onFailure {
                logger.warn("Could not remove '{}' after a failed mode normalization", target, it)
            }
            val removed = if (landingRemoved.isFailure) landingRemoved else linkRemoved
            val leftBehind =
                if (removed.isSuccess) {
                    ""
                } else if (landingRemoved.isFailure) {
                    "; content was left behind at the link's destination"
                } else {
                    "; the target could not be removed"
                }
            throw SecurityException(
                "Could not normalize the mode of tar entry '${entry.name}' from $sourceLabel$leftBehind",
                e,
            )
        }
    }

    /**
     * The POSIX attribute view for an extracted [target]: the directory branch follows a
     * contained link (the write above did), the single-file branch uses `NOFOLLOW_LINKS`
     * like its write. Returns null on a non-POSIX target, where there is no mode to set.
     * [override] is the test seam passed down from [TarStreams.extract]; null means the real lookup.
     */
    private fun posixViewFor(
        target: Path,
        remoteIsDir: Boolean,
        override: ((Path, Boolean) -> PosixFileAttributeView?)?,
    ): PosixFileAttributeView? {
        if (override != null) return override(target, remoteIsDir)
        return if (remoteIsDir) {
            Files.getFileAttributeView(target, PosixFileAttributeView::class.java)
        } else {
            Files.getFileAttributeView(target, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        }
    }

    /**
     * Records every ancestor of [from] up to (excluding) [root] into
     * [materialized]. A directory or file entry with no preceding directory
     * entry for its own parent chain (an untrusted stream shape — the pod's
     * tar binary is attacker-replaceable, see the class doc) would otherwise
     * leave those ancestors out of the keep set: prune() then fails with
     * DirectoryNotEmptyException trying to remove a non-empty directory it
     * thinks is an extra.
     */
    private fun recordAncestors(
        root: Path,
        from: Path?,
        materialized: MutableSet<Path>?,
    ) {
        if (materialized == null) return
        var ancestor = from
        while (ancestor != null && ancestor != root && ancestor.startsWith(root)) {
            materialized.add(root.relativize(ancestor))
            ancestor = ancestor.parent
        }
    }

    private val EXECUTABLE_PERMISSIONS: Set<PosixFilePermission> =
        PosixFilePermissions.fromString("rwxr-xr-x")

    private val NON_EXECUTABLE_PERMISSIONS: Set<PosixFilePermission> =
        PosixFilePermissions.fromString("rw-r--r--")

    /**
     * The permission set to apply to an extracted file: 0755 when the entry carries the
     * owner-exec bit, 0644 otherwise. Never the entry's own bits — the tar is
     * attacker-controlled (§12, ADR-0047).
     */
    private fun normalizedPermissions(mode: Int): Set<PosixFilePermission> =
        if (mode and TarStreams.OWNER_EXECUTE_BIT != 0) EXECUTABLE_PERMISSIONS else NON_EXECUTABLE_PERMISSIONS
}
