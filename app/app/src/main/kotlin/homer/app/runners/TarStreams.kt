package homer.app.runners

import homer.app.exceptions.RunnerTransferTooLargeException
import homer.app.infra.PathSafety
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Transport-neutral gzip'd-tar packing and extraction shared by the Kubernetes
 * sync (tar over the exec channel) and the external runner protocol (tar over
 * HTTP). Extraction treats the far side as a trust boundary: entry names are
 * contained lexically and through [PathSafety], link/device entries are skipped,
 * and MIRROR pruning is the caller's step after its transport confirmed success
 * (ADR-0043, ADR-0055, ADR-0066).
 */
object TarStreams {
    private val logger = LoggerFactory.getLogger(TarStreams::class.java)

    /**
     * Extraction bound for callers with no configured ceiling of their own (the
     * kubernetes sync and the runner instance). Matches the default of the one
     * configurable ceiling, `maxTransferBytes`, so a bomb is refused at the same
     * size wherever a tar is extracted.
     */
    const val DEFAULT_MAX_EXTRACTED_BYTES: Long = 1L * 1024 * 1024 * 1024

    /** Directory nesting an upload walk descends, here and in the ssh upload (§12 rule 4). */
    internal const val MAX_UPLOAD_DEPTH = 64

    /** Octal `0100`, the owner-execute mode bit. */
    internal const val OWNER_EXECUTE_BIT = 64

    /**
     * Realpath of an **operator-configured** pack source, so [pack]'s unconditional
     * symlink refusal does not break a legitimately symlinked agent dir or
     * `extraUploads` entry (ADR-0042). Lives next to the refusal it compensates for
     * so the two cannot drift apart. Never call it on an agent-written path: there
     * the refusal is the fix (root §12 rule 2). Falls back to [local] when the link
     * cannot be resolved, so the refusal still fires.
     */
    fun resolvedPackSource(local: Path): Path = runCatching { local.toRealPath() }.getOrDefault(local)

    /**
     * Writes [local] as a gzip'd tar to [out] and closes [out]. A directory
     * contributes its contents (no entry for itself); a regular file contributes
     * one entry named [singleFileEntryName] (default: its file name).
     */
    fun pack(
        out: OutputStream,
        local: Path,
        excludes: List<String> = emptyList(),
        omitDirEntries: Set<String> = emptySet(),
        singleFileEntryName: String? = null,
    ) {
        // A symlink at the source itself is checked nowhere else: addDirContents guards
        // only CHILDREN of an already-opened directory, and isDirectory()/isRegularFile()
        // below follow the link, so addFile would stream the target's bytes. Every caller
        // but one passes an agent-writable path (§12 rule 2); the operator-configured
        // sources are realpath'd by AgentExecutor before they get here.
        if (Files.isSymbolicLink(local)) {
            throw SecurityException("pack source '$local' is a symlink")
        }
        TarArchiveOutputStream(GzipCompressorOutputStream(out)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            when {
                local.isDirectory() -> {
                    addDirContents(tar, local, "", excludes, local, omitDirEntries, mutableSetOf(local.toRealPath()), 0, viaLink = false)
                }

                local.isRegularFile() -> {
                    // The directory walk's hard-link rule, refused rather than skipped: a single
                    // file was named explicitly, so an empty tar would hide the failure.
                    // CWE-200.
                    if (PathSafety.isHardLinked(local)) throw SecurityException("pack source '$local' is hard-linked")
                    addFile(tar, local, singleFileEntryName ?: local.name)
                }

                else -> {
                    error("pack source does not exist: $local")
                }
            }
        }
    }

    /**
     * Extracts a gzip'd tar from [input] (read to EOF and closed) into [local]
     * under [mode]. Returns the set of materialized paths relative to [local] when
     * `mode == MIRROR && remoteIsDir`, for the caller's [prune]; null otherwise.
     *
     * [maxDecompressedBytes] bounds what the stream may expand to, not what it
     * arrived as: a transport bound on the compressed bytes is no bound at all
     * here, since a few KiB of gzip decompress to gigabytes on disk (§12 rule 4).
     * Past the bound the extraction throws [RunnerTransferTooLargeException], so
     * a partially extracted tree can remain — never anything outside [local].
     *
     * An entry whose normalized mode cannot be applied is refused rather than written
     * unnormalized: its just-written target is deleted and a [SecurityException] is
     * thrown, again leaving the same partially extracted tree, never anything outside
     * [local]. A non-POSIX target has no mode to normalize and still extracts bytes-only.
     *
     * [posixViewLookup] overrides the per-target `PosixFileAttributeView` lookup —
     * a test seam only, kept out of object state so tests need no reset and stay
     * parallel-safe; null means the real lookup.
     */
    fun extract(
        input: InputStream,
        local: Path,
        mode: DownloadMode,
        remoteIsDir: Boolean,
        fileName: String,
        sourceLabel: String,
        maxDecompressedBytes: Long,
        posixViewLookup: ((Path, Boolean) -> PosixFileAttributeView?)? = null,
    ): Set<Path>? {
        val materialized = if (mode == DownloadMode.MIRROR && remoteIsDir) mutableSetOf<Path>() else null
        TarArchiveInputStream(BoundedInputStream(GzipCompressorInputStream(input), maxDecompressedBytes)).use { tar ->
            generateSequence { tar.nextEntry }.forEach { entry ->
                TarExtraction.extractEntry(tar, entry, remoteIsDir, local, fileName, mode, materialized, sourceLabel, posixViewLookup)
            }
        }
        return materialized
    }

    /**
     * Deletes local paths under [local] not present in [keep] (paths relative to
     * [local] recorded during extraction). Deepest paths first so a directory
     * empties before its own removal. `Files.walk` does not follow symlinks by
     * default, so a stale local symlink is deleted as a link — its target is
     * never touched. Only called after a fully successful download. See ADR-0055.
     */
    fun prune(
        local: Path,
        keep: Set<Path>,
    ) {
        val root = local.toAbsolutePath().normalize()
        if (!Files.isDirectory(root)) return
        val descendants = Files.walk(root).use { it.toList() }
        descendants
            .sortedByDescending { it.nameCount }
            .filter { it != root && root.relativize(it) !in keep }
            .forEach { Files.deleteIfExists(it) }
    }

    /**
     * [visited] holds the realpath of every directory already descended into and
     * [depth] bounds the descent: a symlink that resolves back INSIDE the upload root
     * (`loop -> .`, `a/b -> a`) satisfies the containment check below, and
     * `isDirectory()` follows it, so without these two the walk never terminates
     * (§12 rule 4). On the walk by name from the root a real directory (its realpath is
     * its own entry path under the directory's realpath) is always packed, so the result
     * never depends on which name the walk reaches first; only a symlink to a visited
     * directory is skipped. Under a symlink ([viaLink]) every visited directory is
     * skipped, so each directory is walked at most once by name and once through links.
     * Entries are walked real ones first, then symlinks, each group by name.
     */
    private fun addDirContents(
        out: TarArchiveOutputStream,
        dir: Path,
        prefix: String,
        excludes: List<String>,
        root: Path,
        omitDirEntries: Set<String>,
        visited: MutableSet<Path>,
        depth: Int,
        viaLink: Boolean,
    ) {
        // Below the root the walk only ever descends on a checked realpath.
        val realDir = if (depth == 0) root.toRealPath() else dir
        walkEntries(dir, prefix, excludes)
            .forEach { (entry, entryName) ->
                // Pack the resolved target, not the link: addFile must read the path that was
                // checked, so re-pointing the link afterwards changes nothing (see addFile).
                // isDirectory()/isRegularFile() below follow symlinks, and addFile streams
                // the resolved target's bytes — fine for links that stay inside the
                // workspace (venv/tooling links are common there), but a link at a host
                // file would otherwise leak that file's content into the pod. Warn+skip
                // rather than fail: failing the whole upload over a legitimate absolute
                // tooling symlink would break real runs.
                val source = containedSource(entry, root)
                if (source == null) {
                    logger.warn("Skipping symlink '{}' escaping the upload root", entry)
                    return@forEach
                }
                when {
                    source.isDirectory() -> {
                        addDirEntry(out, entry, source, entryName, excludes, root, omitDirEntries, visited, depth, viaLink, realDir)
                    }

                    source.isRegularFile() -> {
                        if (PathSafety.isHardLinked(source)) {
                            // A hardlink is a second name for another file's inode, with no path
                            // back to the first — containment cannot be decided, so the link count
                            // alone decides. Warn+skip like an escaping symlink: hardlink-deduped
                            // tool stores (pnpm, Nix) put legitimate ones in real workspaces.
                            logger.warn("Skipping '{}': hard-linked file, its other name may be outside the upload root", entry)
                            return@forEach
                        }
                        addFile(out, source, entryName)
                    }
                }
            }
    }

    /** A directory entry of [addDirContents]' walk: its tar entry, then its contents within the walk's bounds. */
    private fun addDirEntry(
        out: TarArchiveOutputStream,
        entry: Path,
        source: Path,
        entryName: String,
        excludes: List<String>,
        root: Path,
        omitDirEntries: Set<String>,
        visited: MutableSet<Path>,
        depth: Int,
        viaLink: Boolean,
        realDir: Path,
    ) {
        // Descend on the realpath checked here, not the entry's name: a name is re-resolved
        // when listed, so a link repointed after its check would lead out of the root
        // (CWE-59). An ancestor swapped later stays addFile's
        // residual (accepted in ADR-0047's 2026-09-23 amendment).
        val real = realPathInside(source, root)
        if (real == null) {
            logger.warn("Skipping '{}': does not resolve inside the upload root", entry)
            return
        }
        if (entryName !in omitDirEntries) {
            out.putArchiveEntry(TarArchiveEntry("$entryName/"))
            out.closeArchiveEntry()
        }
        val throughLink = viaLink || real != realDir.resolve(entry.name)
        when {
            depth >= MAX_UPLOAD_DEPTH -> {
                logger.warn("Skipping '{}': upload nesting exceeds {} levels", entry, MAX_UPLOAD_DEPTH)
            }

            // Always add: a real directory's realpath must stop a later link to it.
            !visited.add(real) && throughLink -> {
                logger.warn("Skipping '{}': already packed as another path", entry)
            }

            else -> {
                addDirContents(out, real, entryName, excludes, root, omitDirEntries, visited, depth + 1, throughLink)
            }
        }
    }

    /** [path]'s realpath when it resolves inside [root]; null when it resolves outside or not at all. */
    private fun realPathInside(
        path: Path,
        root: Path,
    ): Path? = runCatching { path.toRealPath() }.getOrNull()?.takeIf { it.startsWith(root.toRealPath()) }

    /**
     * Opens [file] before reading anything about it: the size and the bytes then come
     * from one pinned handle, and `NOFOLLOW_LINKS` refuses a symlink swapped in at this
     * path after its caller's check. Callers pass an already-resolved path for a
     * contained symlink, so the link is never followed here (§12 rule 2).
     * The mode is the one read by path (java.nio has no permission read on a handle),
     * also `NOFOLLOW_LINKS`, so it is not bound to the pinned inode.
     *
     * Residual, unclosable with java.nio: an ancestor *directory* of [file] can still be
     * replaced by a symlink between the caller's check and this open — there is no
     * openat/dirfd walk and no fstat on an open handle to bind the check to the inode.
     * Accepted as a same-UID residual (CWE-59 / CWE-367); see
     * ADR-0047's 2026-09-23 amendment, which records the decision and why the
     * prerequisite (same-UID write access) is not a trust boundary.
     */
    private fun addFile(
        out: TarArchiveOutputStream,
        file: Path,
        entryName: String,
    ) {
        FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val entry = TarArchiveEntry(entryName)
            entry.size = channel.size()
            runCatching { entry.mode = posixToMode(file.getPosixFilePermissions(LinkOption.NOFOLLOW_LINKS)) }
            out.putArchiveEntry(entry)
            Channels.newInputStream(channel).copyTo(out)
            out.closeArchiveEntry()
        }
    }

    private val POSIX_MODE_BITS =
        linkedMapOf(
            PosixFilePermission.OWNER_READ to 256,
            PosixFilePermission.OWNER_WRITE to 128,
            PosixFilePermission.OWNER_EXECUTE to OWNER_EXECUTE_BIT,
            PosixFilePermission.GROUP_READ to 32,
            PosixFilePermission.GROUP_WRITE to 16,
            PosixFilePermission.GROUP_EXECUTE to 8,
            PosixFilePermission.OTHERS_READ to 4,
            PosixFilePermission.OTHERS_WRITE to 2,
            PosixFilePermission.OTHERS_EXECUTE to 1,
        )

    /** The numeric mode (e.g. `0755`) of [perms]; shared with the ssh `WorkspaceSync` upload. */
    internal fun posixToMode(perms: Set<PosixFilePermission>): Int {
        var mode = 0
        POSIX_MODE_BITS.forEach { (perm, bit) -> if (perm in perms) mode = mode or bit }
        return mode
    }

    /**
     * The entries of [dir] an upload walk visits, each with its entry name ([prefix]-joined path
     * from the upload root): real ones first, then symlinks, each group by name, minus the
     * [isExcluded] ones. Shared with the ssh `WorkspaceSync` upload walk.
     */
    internal fun walkEntries(
        dir: Path,
        prefix: String,
        excludes: List<String>,
    ): List<Pair<Path, String>> =
        dir
            .listDirectoryEntries()
            // The flag is read once so a flip mid-sort cannot break the comparator; it pins only the
            // order. Handling re-stats the entry, and is safe either way: a directory is descended
            // on its checked realpath, a file is opened NOFOLLOW_LINKS.
            .map { it to Files.isSymbolicLink(it) }
            .sortedWith(compareBy({ it.second }, { it.first.name }))
            .map { (entry, _) -> entry to if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}" }
            .filterNot { (entry, entryName) -> isExcluded(excludes, entryName, entry.name) }

    /**
     * Whether an upload walk skips the entry [name] at [entryName] (its path from the upload
     * root): a `/`-prefixed exclude matches that root-anchored path, any other the bare name.
     * Shared with the ssh `WorkspaceSync` upload walk.
     */
    internal fun isExcluded(
        excludes: List<String>,
        entryName: String,
        name: String,
    ): Boolean =
        excludes.any {
            if (it.startsWith("/")) {
                it.length == entryName.length + 1 && it.regionMatches(1, entryName, 0, entryName.length)
            } else {
                it == name
            }
        }

    /**
     * What an upload walk reads for [entry]: the entry itself, or a symlink's resolved target
     * when it stays inside [root]; null for a symlink that escapes [root] or cannot be resolved.
     * Shared with the ssh `WorkspaceSync` upload walk.
     */
    internal fun containedSource(
        entry: Path,
        root: Path,
    ): Path? {
        if (!Files.isSymbolicLink(entry)) return entry
        val real = runCatching { entry.toRealPath() }.getOrNull()
        val contained = real != null && runCatching { real.startsWith(root.toRealPath()) }.getOrDefault(false)
        return if (contained) real else null
    }

    /**
     * Resolve a remote-supplied tar entry name against [base], rejecting
     * anything that is not strictly inside it (absolute names, `..`).
     */
    internal fun containedEntryPath(
        base: Path,
        entryName: String,
    ): Path {
        // Char.MIN_VALUE is the NUL codepoint; using the named constant here
        // (rather than a char literal) keeps this source file plain text.
        if (entryName.isEmpty() || entryName.startsWith("/") || Char.MIN_VALUE in entryName) {
            throw SecurityException("Tar entry name '$entryName' is not a safe relative path")
        }
        val root = base.toAbsolutePath().normalize()
        val resolved = root.resolve(entryName).normalize()
        if (resolved != root && !resolved.startsWith(root)) {
            throw SecurityException("Tar entry name '$entryName' resolves outside '$base'")
        }
        if (entryName.split('/').any { it == ".." }) {
            throw SecurityException("Tar entry name '$entryName' contains '..'")
        }
        return resolved
    }
}

/**
 * Fails with [RunnerTransferTooLargeException] once more than [limit] bytes were
 * read. One copy for both sides of a transfer — the compressed request body on the
 * tar route and the bytes it expands to inside [TarStreams.extract] (§12 rule 4).
 */
internal class BoundedInputStream(
    private val inner: InputStream,
    private val limit: Long,
) : InputStream() {
    private var count = 0L

    override fun read(): Int = inner.read().also { if (it >= 0) account(1) }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int = inner.read(b, off, len).also { if (it > 0) account(it) }

    private fun account(n: Int) {
        count += n
        if (count > limit) throw RunnerTransferTooLargeException(limit)
    }

    override fun close() = inner.close()
}
