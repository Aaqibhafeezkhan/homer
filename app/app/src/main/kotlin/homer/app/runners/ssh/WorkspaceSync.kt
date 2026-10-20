package homer.app.runners.ssh

import homer.app.infra.PathSafety
import homer.app.runners.DownloadMode
import homer.app.runners.TarStreams
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.common.SftpException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.time.Duration

/**
 * Recursive SFTP transfer over an authenticated [session]. Directory-oriented:
 * uploads the workspace + agent dir before a run, downloads artifacts (and
 * workdir when kept) after, and removes the remote projectRoot on cleanup.
 * A fresh SftpClient is opened per operation and closed with it.
 *
 * Every blocking SFTP round trip is bounded by [transferTimeout], including the
 * client's own close (CWE-400): a stalling peer answering
 * keepalives while withholding SFTP data would otherwise hold a `Dispatchers.IO`
 * worker, the workspace lock and a run slot indefinitely — MINA heartbeats only
 * disconnect a peer that stops answering.
 */
class WorkspaceSync(
    private val session: ClientSession,
    private val transferTimeout: Duration? = null,
    private val maxDirEntries: Int = DEFAULT_MAX_DIR_ENTRIES,
    // Test seam: runs after a file's link checks and before its open, so a test can
    // swap the file for a symlink inside that window deterministically.
    internal val beforeLocalOpen: (Path) -> Unit = {},
    // Test seam: MINA's own close only *enqueues* the close packet and
    // returns, so no SSH peer can make the release itself stall. Injecting the
    // client is the only way to assert the release is really under the deadline.
    internal val openSftp: (ClientSession) -> SftpClient = { SftpClientFactory.instance().createSftpClient(it) },
) {
    companion object {
        const val DEFAULT_MAX_DIR_ENTRIES = 100_000

        /**
         * Resolve a remote-supplied directory-entry name against [localDir].
         * SFTP listings cross the runner trust boundary — a compromised
         * runner (or a MITM when host-key checking is off) can return names
         * like `../../etc/cron.d/x` or absolute paths, which `Path.resolve`
         * would happily escape [localDir] with. Only a plain single-segment
         * child name is accepted.
         */
        internal fun containedChild(
            localDir: Path,
            filename: String,
        ): Path {
            if (!isPlainFileName(filename)) {
                throw SecurityException("Remote SFTP entry name '$filename' is not a plain file name")
            }
            val base = localDir.toAbsolutePath().normalize()
            val child = base.resolve(filename).normalize()
            if (child.parent != base) {
                throw SecurityException("Remote SFTP entry name '$filename' resolves outside '$localDir'")
            }
            return child
        }

        /** A single path segment: not empty, not `.` or `..`, and free of separators and NUL. */
        private fun isPlainFileName(filename: String): Boolean =
            filename.isNotEmpty() &&
                filename != "." &&
                filename != ".." &&
                '/' !in filename &&
                '\\' !in filename &&
                '\u0000' !in filename
    }

    private val logger = LoggerFactory.getLogger(WorkspaceSync::class.java)

    private val deadline = SftpTransferDeadline(session, transferTimeout, openSftp, logger)

    private suspend fun <T> withSftp(block: suspend (SftpClient) -> T): T = deadline.withSftp(block)

    private suspend fun <T> withTransferTimeout(block: suspend () -> T): T = deadline.withTransferTimeout(block)

    suspend fun mkdirs(remoteDir: String) = withSftp { sftp -> withTransferTimeout { sftp.mkdirsIdempotent(remoteDir) } }

    /** `lstat`: true when [remotePath] itself is a symbolic link; false when it does not exist. */
    suspend fun isSymlink(remotePath: String): Boolean =
        withSftp { sftp ->
            withTransferTimeout {
                try {
                    sftp.lstat(remotePath).isSymbolicLink
                } catch (e: SftpException) {
                    if (e.status == SftpConstants.SSH_FX_NO_SUCH_FILE || e.status == SftpConstants.SSH_FX_NO_SUCH_PATH) false else throw e
                }
            }
        }

    suspend fun uploadDir(
        localDir: Path,
        remoteDir: String,
        excludes: List<String> = emptyList(),
    ) = withSftp { sftp ->
        // Same refusal as upload(): a symlinked root would walk the link's target (CWE-59).
        if (Files.isSymbolicLink(localDir)) throw SecurityException("upload source '$localDir' is a symlink")
        uploadDirInto(sftp, localDir, remoteDir, excludes, localDir, "", mutableSetOf(localDir.toRealPath()), 0, viaLink = false)
    }

    /**
     * Upload a local file or directory to [remote]. Directories recurse and
     * honor [excludes]: a bare entry name matches at any depth, a leading
     * `/` anchors the exclude to the upload root; single files get remote
     * parent dirs created. The local path must exist.
     */
    suspend fun upload(
        local: Path,
        remote: String,
        excludes: List<String> = emptyList(),
    ) = withSftp { sftp ->
        // Refused before any branching or remote side effect, for a file or a directory
        // source alike: isDirectory()/isRegularFile() below follow a link, so a symlinked
        // directory would otherwise be walked and streamed as if it were the real upload
        // root. CWE-59: parity with TarStreams.pack.
        if (Files.isSymbolicLink(local)) throw SecurityException("upload source '$local' is a symlink")
        when {
            local.isDirectory() -> {
                uploadDirInto(sftp, local, remote, excludes, local, "", mutableSetOf(local.toRealPath()), 0, viaLink = false)
            }

            local.isRegularFile() -> {
                // The directory walk's hard-link rule (below), refused rather than skipped: a
                // single file was named explicitly, so silently skipping it would hide the
                // refusal. CWE-59: hard-link parity with TarStreams.pack's single-file branch.
                if (PathSafety.isHardLinked(local)) throw SecurityException("upload source '$local' is hard-linked")
                remote.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { withTransferTimeout { sftp.mkdirsIdempotent(it) } }
                uploadFile(sftp, local, remote)
            }

            else -> {
                error("upload source does not exist: $local")
            }
        }
    }

    /**
     * [visited] holds the realpath of every directory already descended into (seeded by
     * the caller with the upload root's realpath). Every directory is resolved with its own
     * `toRealPath()` at descent, not just a symlinked one (canonical-form rationale at the
     * descent site below; ADR-0042). A symlink that resolves
     * back INSIDE the tree (`loop -> .`, `a/self -> .`, `a/b -> a`, or a second name for one
     * directory) satisfies the containment check below and isDirectory() follows it, so
     * without a de-duplicated visited set and the [depth] cap the walk never terminates.
     * On the walk by name from the root a real directory (its realpath is its own entry
     * path under the directory's realpath) is always uploaded, so the result never depends
     * on which name the walk reaches first; only a symlink to a visited directory is
     * skipped. Under a symlink ([viaLink]) every visited directory is skipped, so each
     * directory is walked at most once by name and once through links. Entries are walked
     * real ones first, then symlinks, each group by name (parity with
     * `TarStreams.addDirContents`).
     *
     * [prefix] is built by concatenation, never `root.relativize(localDir)`: the walk
     * descends on realpaths, so relativize would corrupt anchored excludes (ADR-0042).
     */
    private suspend fun uploadDirInto(
        sftp: SftpClient,
        localDir: Path,
        remoteDir: String,
        excludes: List<String>,
        root: Path,
        prefix: String,
        visited: MutableSet<Path>,
        depth: Int,
        viaLink: Boolean,
    ) {
        // Below the root the walk only ever descends on a checked realpath.
        val realDir = if (depth == 0) root.toRealPath() else localDir
        withTransferTimeout { sftp.mkdirsIdempotent(remoteDir) }
        TarStreams
            .walkEntries(localDir, prefix, excludes)
            .forEach { (entry, entryName) ->
                // isDirectory()/isRegularFile() below follow symlinks, and uploadFile streams
                // the resolved target's bytes — fine for links that stay inside the upload
                // root (venv/tooling links are common there), but a link at a host file would
                // otherwise leak that file's content to the remote host. Warn+skip rather than
                // fail: failing the whole upload over a legitimate absolute tooling symlink
                // would break real runs.
                // A file is checked and streamed at the resolved target, not the link: the link's
                // own nlink is always 1 (CWE-59, parity with TarStreams.addDirContents).
                val source = TarStreams.containedSource(entry, root)
                if (source == null) {
                    logger.warn("Skipping symlink '{}' escaping the upload root", entry)
                    return@forEach
                }
                val remoteChild = "$remoteDir/${entry.name}"
                when {
                    source.isDirectory() -> {
                        uploadDirEntry(sftp, entry, source, remoteChild, excludes, root, entryName, visited, depth, viaLink, realDir)
                    }

                    source.isRegularFile() -> {
                        if (PathSafety.isHardLinked(source)) {
                            // CWE-59: hard-link parity with TarStreams — a hardlink is a second
                            // name for another file's inode, with no path back to the first, so
                            // containment cannot be decided; warn+skip like an escaping symlink.
                            logger.warn("Skipping '{}': hard-linked file, its other name may be outside the upload root", entry)
                            return@forEach
                        }
                        uploadFile(sftp, source, remoteChild)
                    }
                }
            }
    }

    /** A directory entry of [uploadDirInto]'s walk: created and descended into within the walk's bounds. */
    private suspend fun uploadDirEntry(
        sftp: SftpClient,
        entry: Path,
        source: Path,
        remoteChild: String,
        excludes: List<String>,
        root: Path,
        entryName: String,
        visited: MutableSet<Path>,
        depth: Int,
        viaLink: Boolean,
        realDir: Path,
    ) {
        // Resolve every directory here, symlink or not, to its canonical realpath —
        // an unresolved root (macOS /var -> /private/var) would otherwise let
        // `visited` hold two forms of the same directory. See ADR-0042 (2026-09-30
        // amendment).
        val real = runCatching { source.toRealPath() }.getOrNull()
        if (real == null || !real.startsWith(root.toRealPath())) {
            logger.warn("Skipping '{}': does not resolve inside the upload root", entry)
            return
        }
        // Descend on the checked realpath, not the entry's name: a name is
        // re-resolved when listed, so a link repointed after its check would lead
        // out of the root (CWE-59). Skip with a warning,
        // like TarStreams, rather than follow a link into a directory already
        // visited under another path.
        val throughLink = viaLink || real != realDir.resolve(entry.name)
        when {
            depth >= TarStreams.MAX_UPLOAD_DEPTH -> {
                logger.warn("Skipping '{}': upload nesting exceeds {} levels", entry, TarStreams.MAX_UPLOAD_DEPTH)
            }

            // Always add: a real directory's realpath must stop a later link to it.
            !visited.add(real) && throughLink -> {
                logger.warn("Skipping '{}': already uploaded as another path", entry)
            }

            else -> {
                uploadDirInto(sftp, real, remoteChild, excludes, root, entryName, visited, depth + 1, throughLink)
            }
        }
    }

    /**
     * Streams [local] from one handle opened before anything is written remotely. Callers
     * pass an already-resolved path for a contained symlink, so the link is never followed
     * here. The mode is read by path (java.nio has no permission read on a handle), also
     * `NOFOLLOW_LINKS`, as in `TarStreams.addFile`.
     */
    private suspend fun uploadFile(
        sftp: SftpClient,
        local: Path,
        remote: String,
    ) {
        beforeLocalOpen(local)
        // CWE-367: open once with NOFOLLOW_LINKS, so a symlink swapped in
        // after the caller's checks is refused, not streamed. The ancestor-swap residual is
        // accepted in ADR-0047's 2026-09-23 amendment.
        FileChannel.open(local, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            // CWE-400: bound the transfer so a stalling peer cannot hold resources indefinitely.
            withTransferTimeout { sftp.write(remote).use { out -> Channels.newInputStream(channel).copyTo(out) } }
        }
        try {
            val attrs = withTransferTimeout { sftp.stat(remote) }
            attrs.permissions = TarStreams.posixToMode(local.getPosixFilePermissions(LinkOption.NOFOLLOW_LINKS))
            withTransferTimeout { sftp.setStat(remote, attrs) }
        } catch (e: IOException) {
            logger.warn("Failed to set permissions on uploaded file {}", remote, e)
        }
    }

    suspend fun downloadDir(
        remoteDir: String,
        localDir: Path,
        mode: DownloadMode,
    ) = withSftp { sftp ->
        if (!withTransferTimeout { remoteExists(sftp, remoteDir) }) return@withSftp
        val materialized = if (mode == DownloadMode.MIRROR) mutableSetOf<Path>() else null
        downloadDirInto(sftp, remoteDir, localDir, mode, localDir, materialized)
        // Prune only after the SFTP walk completes without error — never on a partial/failed
        // transfer or a missing remote dir (the early return above skips this entirely). ADR-0055.
        if (materialized != null) TarStreams.prune(localDir, materialized)
    }

    /**
     * Download a remote file or directory to [local]. Missing remote paths
     * are a silent no-op (mirrors downloadDir). [mode] controls how remote
     * content reconciles with what's already at [local] — see [DownloadMode].
     * A single-file download has no extras scope, so MIRROR behaves like
     * OVERWRITE there.
     */
    suspend fun download(
        remote: String,
        local: Path,
        mode: DownloadMode,
    ) = withSftp { sftp ->
        if (!withTransferTimeout { remoteExists(sftp, remote) }) return@withSftp
        if (withTransferTimeout { sftp.stat(remote) }.isDirectory) {
            val materialized = if (mode == DownloadMode.MIRROR) mutableSetOf<Path>() else null
            downloadDirInto(sftp, remote, local, mode, local, materialized)
            if (materialized != null) TarStreams.prune(local, materialized)
        } else {
            if (mode == DownloadMode.OVERLAY && Files.exists(local)) return@withSftp
            local.parent?.let { Files.createDirectories(it) }
            // CWE-400: bound the transfer so a stalling peer cannot hold resources indefinitely.
            withTransferTimeout { sftp.read(remote).use { input -> Files.newOutputStream(local).use { input.copyTo(it) } } }
        }
    }

    private suspend fun downloadDirInto(
        sftp: SftpClient,
        remoteDir: String,
        localDir: Path,
        mode: DownloadMode,
        root: Path,
        materialized: MutableSet<Path>?,
    ) {
        if (!withTransferTimeout { remoteExists(sftp, remoteDir) }) return
        Files.createDirectories(localDir)
        // CWE-400: cap the listing to prevent heap exhaustion from a hostile peer.
        val entries = mutableListOf<SftpClient.DirEntry>()
        withTransferTimeout {
            for (entry in sftp.readDir(remoteDir)) {
                if (entries.size >= maxDirEntries) throw IOException("remote readDir cap of $maxDirEntries entries exceeded")
                entries.add(entry)
            }
        }
        for (entry in entries) {
            val fn = entry.filename
            if (fn == "." || fn == "..") continue
            val remoteChild = "$remoteDir/$fn"
            // containedChild is lexical only (absolute/".."/multi-segment rejection); it
            // can't see a symlink already present in the destination. PathSafety adds the
            // realpath check, so a pre-existing local symlink can't steer the write outside
            // the workspace even when the entry name itself is lexically safe (intra-root
            // symlinks stay allowed). Used as a pure check here, not for its return value:
            // assertContained resolves an existing candidate to its realpath, and binding
            // that into the next level's localDir would compare a realpath child against
            // the still-lexical root on recursion — spuriously rejecting deep downloads
            // into an existing local tree on symlinked-prefix hosts (e.g. macOS /var ->
            // /private/var). Recursion and writes stay on the lexical path throughout —
            // the same reason materialized records this lexical localChild, not a
            // realpath: prune()'s own walk stays lexical too, so the two must agree.
            val localChild = containedChild(localDir, fn)
            PathSafety.assertContained(root, localChild)
            materialized?.add(root.toAbsolutePath().normalize().relativize(localChild.toAbsolutePath().normalize()))
            if (entry.attributes.isDirectory) {
                downloadDirInto(sftp, remoteChild, localChild, mode, root, materialized)
            } else {
                if (mode == DownloadMode.OVERLAY && Files.exists(localChild)) {
                    // OVERLAY: keep locally-streamed files (e.g. cmd_N.out); only
                    // pull remote files that don't already exist locally.
                    continue
                }
                // CWE-400: bound the transfer so a stalling peer cannot hold resources indefinitely.
                withTransferTimeout {
                    sftp.read(remoteChild).use { input ->
                        Files.newOutputStream(localChild).use { input.copyTo(it) }
                    }
                }
            }
        }
    }

    suspend fun removeRecursively(remoteDir: String) = withSftp { sftp -> withTransferTimeout { removeRecursivelyIn(sftp, remoteDir) } }

    private fun removeRecursivelyIn(
        sftp: SftpClient,
        remoteDir: String,
    ) {
        if (!remoteExists(sftp, remoteDir)) return
        for (entry in sftp.readDir(remoteDir)) {
            val fn = entry.filename
            if (fn == "." || fn == "..") continue
            val child = "$remoteDir/$fn"
            if (entry.attributes.isDirectory) removeRecursivelyIn(sftp, child) else sftp.remove(child)
        }
        sftp.rmdir(remoteDir)
    }
}
