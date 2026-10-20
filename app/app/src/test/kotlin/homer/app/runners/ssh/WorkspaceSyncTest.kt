package homer.app.runners.ssh

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import homer.app.runners.DownloadMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.sftp.client.SftpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import kotlin.io.path.createDirectories
import kotlin.io.path.isExecutable
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import ch.qos.logback.classic.Level as LogbackLevel
import ch.qos.logback.classic.Logger as LogbackLogger

class WorkspaceSyncTest {
    private lateinit var server: EmbeddedSshServer
    private lateinit var client: SshClient
    private lateinit var sync: WorkspaceSync

    @BeforeEach
    fun setup() {
        server = EmbeddedSshServer().also { it.start() }
        client = SshClient.setUpDefaultClient().also { it.start() }
        sync = WorkspaceSync(server.newClientSession(client))
    }

    @AfterEach
    fun teardown() {
        client.stop()
        server.close()
    }

    @Test
    fun `uploadDir mirrors a tree and preserves the executable bit`(
        @TempDir local: Path,
    ) = runBlocking {
        val bin = local.resolve(".homer/bin").createDirectories()
        val helper = bin.resolve("homer-ask")
        helper.writeText("#!/bin/sh\necho hi\n")
        Files.setPosixFilePermissions(helper, PosixFilePermissions.fromString("rwxr-xr-x"))
        local
            .resolve("workdir")
            .createDirectories()
            .resolve("f.txt")
            .writeText("data")

        val remote = server.rootDir.resolve("uploaded").toString()
        sync.uploadDir(local, remote)

        val remoteHelper = server.rootDir.resolve("uploaded/.homer/bin/homer-ask")
        assertEquals("#!/bin/sh\necho hi\n", remoteHelper.readText())
        assertTrue(remoteHelper.isExecutable(), "executable bit must survive upload")
        assertEquals("data", server.rootDir.resolve("uploaded/workdir/f.txt").readText())
    }

    @Test
    fun `downloadDir overlay does not delete existing local files`(
        @TempDir local: Path,
    ) = runBlocking {
        // Remote artifacts produced by the agent.
        val remoteArtifacts = server.rootDir.resolve("artifacts").createDirectories()
        remoteArtifacts.resolve("out.bin").writeText("agent-output")
        // Local artifacts already hold a streamed command log.
        val localArtifacts = local.resolve("artifacts").createDirectories()
        localArtifacts.resolve("cmd_0.out").writeText("streamed log")

        sync.downloadDir(server.rootDir.resolve("artifacts").toString(), localArtifacts, mode = DownloadMode.OVERLAY)

        assertEquals("agent-output", localArtifacts.resolve("out.bin").readText())
        assertEquals("streamed log", localArtifacts.resolve("cmd_0.out").readText(), "overlay must keep local logs")
    }

    @Test
    fun `removeRecursively deletes a remote tree`(
        @TempDir local: Path,
    ) = runBlocking {
        val remote = server.rootDir.resolve("gone")
        remote.createDirectories().resolve("x").writeText("x")
        sync.removeRecursively(remote.toString())
        assertTrue(Files.notExists(remote))
    }

    @Test
    fun `isSymlink tells a link from a file, a directory, a dangling link and a missing path`() =
        runBlocking {
            // [path_traversal / CWE-59] — the lstat the cleanup refusal rests on (ADR-0067).
            val root = server.rootDir.resolve("sym").createDirectories()
            root.resolve("file").writeText("x")
            root.resolve("dir").createDirectories()
            Files.createSymbolicLink(root.resolve("link"), root.resolve("dir"))
            Files.createSymbolicLink(root.resolve("dangling"), root.resolve("nowhere"))

            assertTrue(sync.isSymlink(root.resolve("link").toString()))
            assertTrue(sync.isSymlink(root.resolve("dangling").toString()))
            assertFalse(sync.isSymlink(root.resolve("file").toString()))
            assertFalse(sync.isSymlink(root.resolve("dir").toString()))
            assertFalse(sync.isSymlink(root.resolve("missing").toString()))
            assertFalse(sync.isSymlink(root.resolve("missing/deeper").toString()))
        }

    @Test
    fun `downloadDir OVERWRITE overwrites existing local files`(
        @TempDir local: Path,
    ) = runBlocking {
        val remoteArtifacts = server.rootDir.resolve("artifacts").createDirectories()
        remoteArtifacts.resolve("out.bin").writeText("new-content")
        val localArtifacts = local.resolve("artifacts").createDirectories()
        localArtifacts.resolve("out.bin").writeText("old-content")

        sync.downloadDir(server.rootDir.resolve("artifacts").toString(), localArtifacts, mode = DownloadMode.OVERWRITE)

        assertEquals("new-content", localArtifacts.resolve("out.bin").readText())
    }

    @Test
    fun `downloadDir OVERLAY pulls remote-only files from a nested subdir while keeping a pre-existing local file`(
        @TempDir local: Path,
    ) = runBlocking {
        val remoteRoot = server.rootDir.resolve("artifacts").createDirectories()
        remoteRoot.resolve("keep.txt").writeText("remote-keep")
        val remoteSub = remoteRoot.resolve("sub").createDirectories()
        remoteSub.resolve("new.txt").writeText("nested-remote-only")

        val localRoot = local.resolve("artifacts").createDirectories()
        localRoot.resolve("keep.txt").writeText("local-preexisting")

        sync.downloadDir(remoteRoot.toString(), localRoot, mode = DownloadMode.OVERLAY)

        assertEquals("local-preexisting", localRoot.resolve("keep.txt").readText(), "overlay must keep pre-existing local file")
        assertEquals("nested-remote-only", localRoot.resolve("sub/new.txt").readText(), "remote-only nested file must arrive")
    }

    @Test
    fun `uploadDir honors excludes`(
        @TempDir local: Path,
    ) = runBlocking {
        local.resolve("keep.txt").writeText("keep-me")
        local.resolve("skip.txt").writeText("skip-me")
        local
            .resolve("skipdir")
            .createDirectories()
            .resolve("inner.txt")
            .writeText("inner")

        val remote = server.rootDir.resolve("uploaded-excludes").toString()
        sync.uploadDir(local, remote, excludes = listOf("skip.txt", "skipdir"))

        assertTrue(Files.exists(server.rootDir.resolve("uploaded-excludes/keep.txt")), "non-excluded sibling must upload")
        assertTrue(Files.notExists(server.rootDir.resolve("uploaded-excludes/skip.txt")), "excluded file must not upload")
        assertTrue(Files.notExists(server.rootDir.resolve("uploaded-excludes/skipdir")), "excluded dir must not upload")
    }

    @Test
    fun `anchored exclude scopes to the upload root, bare exclude still matches at any depth`(
        @TempDir local: Path,
    ) = runBlocking {
        local.resolve("build").createDirectories()
        local.resolve("build/root.txt").writeText("root-build")
        local.resolve("sub/build").createDirectories()
        local.resolve("sub/build/nested.txt").writeText("nested-build")

        val anchored = server.rootDir.resolve("anchored").toString()
        sync.uploadDir(local, anchored, excludes = listOf("/build"))
        // anchored exclude only removes the root-level entry
        assertTrue(Files.notExists(server.rootDir.resolve("anchored/build")))
        assertTrue(Files.exists(server.rootDir.resolve("anchored/sub/build/nested.txt")))

        val bare = server.rootDir.resolve("bare").toString()
        sync.uploadDir(local, bare, excludes = listOf("build"))
        // bare exclude keeps matching by entry name at any depth
        assertTrue(Files.notExists(server.rootDir.resolve("bare/build")))
        assertTrue(Files.notExists(server.rootDir.resolve("bare/sub/build")))
    }

    @Test
    fun `upload dispatches single file and creates remote parents`(
        @TempDir local: Path,
    ) = runBlocking {
        val file = local.resolve("bin.tar").also { it.writeText("payload") }
        sync.upload(file, server.rootDir.resolve("deep/nested/bin.tar").toString())
        assertEquals("payload", server.rootDir.resolve("deep/nested/bin.tar").readText())
    }

    @Test
    fun `upload dispatches directory recursively with excludes`(
        @TempDir local: Path,
    ) = runBlocking {
        local
            .resolve("keep")
            .createDirectories()
            .resolve("a.txt")
            .writeText("a")
        local
            .resolve("skip")
            .createDirectories()
            .resolve("b.txt")
            .writeText("b")
        sync.upload(local, server.rootDir.resolve("up").toString(), excludes = listOf("skip"))
        assertEquals("a", server.rootDir.resolve("up/keep/a.txt").readText())
        assertTrue(Files.notExists(server.rootDir.resolve("up/skip")))
    }

    @Test
    fun `upload of missing local path throws`(
        @TempDir local: Path,
    ) = runBlocking {
        assertThrows<IllegalStateException> {
            sync.upload(local.resolve("nope"), server.rootDir.resolve("x").toString())
        }
    }

    // ===== Hard-link parity with TarStreams (CWE-59) =====
    // The ssh runner streams over SFTP and never goes through TarStreams.pack, so
    // WorkspaceSync must apply the shared PathSafety.isHardLinked check itself
    // rather than inherit the guard for free.

    @Test
    fun `uploadDir skips a hard-linked file and still uploads the rest (CWE-59)`(
        @TempDir local: Path,
        @TempDir outside: Path,
    ) = runBlocking {
        // A hardlink is a second name for another file's inode: the symlink guard
        // above cannot see it, and uploading it would stream the host file's bytes.
        val secret = outside.resolve("host-secret.txt").also { it.writeText("SECRET") }
        local.resolve("keep.txt").writeText("keep-me")
        Files.createLink(local.resolve("stolen.txt"), secret)

        val remote = server.rootDir.resolve("hardlink-dir").toString()
        val warnings = captureWarnings { sync.uploadDir(local, remote) }

        assertEquals("keep-me", server.rootDir.resolve("hardlink-dir/keep.txt").readText())
        assertTrue(Files.notExists(server.rootDir.resolve("hardlink-dir/stolen.txt")), "hard-linked file must not upload")
        assertTrue(
            warnings.any { it.formattedMessage.contains("hard-linked") },
            "must warn about the skipped hard-linked file; got $warnings",
        )
    }

    @Test
    fun `upload refuses a hard-linked single file instead of streaming it (CWE-59)`(
        @TempDir local: Path,
        @TempDir outside: Path,
    ) = runBlocking {
        // A single file was named explicitly, so skipping it (as uploadDirInto does)
        // would hide the refusal — mirrors TarStreams.pack's single-file branch.
        val secret = outside.resolve("host-secret.txt").also { it.writeText("SECRET") }
        val linked = Files.createLink(local.resolve("innocent.txt"), secret)

        assertThrows<SecurityException> {
            sync.upload(linked, server.rootDir.resolve("refused.tar").toString())
        }
        assertTrue(Files.notExists(server.rootDir.resolve("refused.tar")))
    }

    @Test
    fun `upload of a normal (non-hard-linked) single file still succeeds (CWE-59)`(
        @TempDir local: Path,
    ) = runBlocking {
        val file = local.resolve("plain.txt").also { it.writeText("ordinary") }
        sync.upload(file, server.rootDir.resolve("plain.txt").toString())
        assertEquals("ordinary", server.rootDir.resolve("plain.txt").readText())
    }

    @Test
    fun `uploadDir skips a contained symlink to a hard-linked file (CWE-59)`(
        @TempDir local: Path,
        @TempDir outside: Path,
    ) = runBlocking {
        // The link's own nlink is 1; uploadFile streams the target, so the target's count decides.
        val secret = outside.resolve("host-secret.txt").also { it.writeText("SECRET") }
        Files.createLink(local.resolve("h.txt"), secret)
        Files.createSymbolicLink(local.resolve("s.txt"), Path.of("h.txt"))

        val remote = server.rootDir.resolve("symlink-hardlink-dir").toString()
        val warnings = captureWarnings { sync.uploadDir(local, remote) }

        assertTrue(Files.notExists(server.rootDir.resolve("symlink-hardlink-dir/h.txt")), "hard-linked file must not upload")
        assertTrue(Files.notExists(server.rootDir.resolve("symlink-hardlink-dir/s.txt")), "symlink to it must not upload")
        assertTrue(
            warnings.count { it.formattedMessage.contains("hard-linked") } == 2,
            "must warn about both skipped names; got $warnings",
        )
    }

    @Test
    fun `upload refuses a symlink single-file source (CWE-59)`(
        @TempDir local: Path,
    ) = runBlocking {
        // Mirrors TarStreams.pack: a symlink source is checked nowhere else.
        val target = local.resolve("target.txt").also { it.writeText("ordinary") }
        val link = Files.createSymbolicLink(local.resolve("link.txt"), target)

        assertThrows<SecurityException> {
            sync.upload(link, server.rootDir.resolve("symlink-refused.txt").toString())
        }
        assertTrue(Files.notExists(server.rootDir.resolve("symlink-refused.txt")))
    }

    // ===== Directory-symlink cycles and a refused symlinked directory source =====

    @Test
    fun `upload refuses a symlinked directory source and creates nothing remotely (CWE-59)`(
        @TempDir local: Path,
    ) = runBlocking {
        // Mirrors TarStreams.pack: a symlink source is checked nowhere else, and this must
        // fire before any remote side effect (no remote directory left behind by a partial walk).
        val real = local.resolve("real").createDirectories()
        real.resolve("f.txt").writeText("content")
        val link = Files.createSymbolicLink(local.resolve("link"), real)
        val remote = server.rootDir.resolve("dir-symlink-refused").toString()

        assertThrows<SecurityException> {
            sync.upload(link, remote)
        }
        assertTrue(Files.notExists(server.rootDir.resolve("dir-symlink-refused")), "nothing must be created remotely")
    }

    @Test
    fun `uploadDir refuses a symlinked directory source and creates nothing remotely (CWE-59)`(
        @TempDir local: Path,
    ) = runBlocking {
        val real = local.resolve("real").createDirectories()
        real.resolve("f.txt").writeText("content")
        val link = Files.createSymbolicLink(local.resolve("link"), real)
        val remote = server.rootDir.resolve("uploaddir-symlink-refused").toString()

        assertThrows<SecurityException> {
            sync.uploadDir(link, remote)
        }
        assertTrue(Files.notExists(server.rootDir.resolve("uploaddir-symlink-refused")), "nothing must be created remotely")
    }

    @Test
    fun `uploadDir stops at a directory-symlink cycle back to the upload root and uploads each real directory once`(
        @TempDir local: Path,
    ) = runBlocking {
        // "loop" resolves back to the upload root itself (an ancestor cycle): isDirectory()
        // follows the link, and without a visited set each recursion re-lists the same
        // content under a path that looks unvisited, so the walk never terminates.
        local
            .resolve("a")
            .createDirectories()
            .resolve("f.txt")
            .writeText("a-content")
        local
            .resolve("b")
            .createDirectories()
            .resolve("g.txt")
            .writeText("b-content")
        Files.createSymbolicLink(local.resolve("a/loop"), local.toAbsolutePath())

        val remote = server.rootDir.resolve("cycle").toString()
        withTimeout(20.seconds) { sync.uploadDir(local, remote) }

        assertEquals("a-content", server.rootDir.resolve("cycle/a/f.txt").readText())
        assertEquals("b-content", server.rootDir.resolve("cycle/b/g.txt").readText())
        assertTrue(Files.notExists(server.rootDir.resolve("cycle/a/loop")), "the cycle must be skipped, not descended into")
    }

    @Test
    fun `uploadDir stops at a directory symlink that cycles back to a non-root ancestor and uploads it once`(
        @TempDir local: Path,
    ) = runBlocking {
        // "self" resolves back to "a" itself — a non-root ancestor, unlike the test above.
        // A plain directory's own (unresolved) path and a link that resolves to it are the
        // same physical directory but different Path values whenever the upload root's own
        // path isn't already canonical (e.g. macOS's @TempDir sitting under /var ->
        // /private/var): visited must compare one canonical form for both, or the alias goes
        // undetected and "a"'s content uploads a second time under "a/self".
        val a = local.resolve("a").createDirectories()
        a.resolve("f.txt").writeText("a-content")
        Files.createSymbolicLink(a.resolve("self"), Path.of("."))

        val remote = server.rootDir.resolve("non-root-cycle").toString()
        withTimeout(20.seconds) { sync.uploadDir(local, remote) }

        assertEquals("a-content", server.rootDir.resolve("non-root-cycle/a/f.txt").readText())
        assertTrue(
            Files.notExists(server.rootDir.resolve("non-root-cycle/a/self")),
            "the cycle must be skipped, not descended into",
        )
    }

    @Test
    fun `uploadDir still uploads a contained directory symlink to a sibling directory under the link's name`(
        @TempDir local: Path,
    ) = runBlocking {
        // "hidden" is excluded by name so this proves the symlink path alone uploads the
        // sibling's content, deterministically — not a coincidence of which of two aliases
        // to the same directory the (unordered) directory listing happens to visit first.
        local
            .resolve("hidden")
            .createDirectories()
            .resolve("f.txt")
            .writeText("sibling-content")
        val visible = local.resolve("visible").createDirectories()
        Files.createSymbolicLink(visible.resolve("link"), Path.of("../hidden"))

        val remote = server.rootDir.resolve("sibling").toString()
        sync.uploadDir(local, remote, excludes = listOf("hidden"))

        assertEquals("sibling-content", server.rootDir.resolve("sibling/visible/link/f.txt").readText())
        assertTrue(Files.notExists(server.rootDir.resolve("sibling/hidden")), "the excluded real name must not itself upload")
    }

    @Test
    fun `uploadDir uploads a directory under its real name and skips a symlink alias beside it, the same way on every run`(
        @TempDir local: Path,
    ) = runBlocking {
        local
            .resolve("lib")
            .createDirectories()
            .resolve("f.txt")
            .writeText("lib-content")
        Files.createSymbolicLink(local.resolve("lib64"), Path.of("lib"))

        repeat(2) { run ->
            val remote = server.rootDir.resolve("alias-$run")
            val warnings = captureWarnings { withTimeout(20.seconds) { sync.uploadDir(local, remote.toString()) } }

            assertEquals("lib-content", remote.resolve("lib/f.txt").readText(), "run $run: the real directory is always uploaded")
            assertTrue(Files.notExists(remote.resolve("lib64/f.txt")), "run $run: the alias is skipped")
            assertTrue(
                warnings.any { "already uploaded as another path" in it.formattedMessage },
                "run $run: the skip must be logged, got ${warnings.map { it.formattedMessage }}",
            )
        }
    }

    @Test
    fun `uploadDir still uploads a real directory reached after a symlink alias to it from another subtree`(
        @TempDir local: Path,
    ) = runBlocking {
        // node_modules lists (and sorts) before packages, so the alias is walked first.
        local
            .resolve("packages/pkg")
            .createDirectories()
            .resolve("index.js")
            .writeText("module.exports = 1")
        Files.createSymbolicLink(local.resolve("node_modules").createDirectories().resolve("pkg"), Path.of("../packages/pkg"))

        val remote = server.rootDir.resolve("cross-tree")
        withTimeout(20.seconds) { sync.uploadDir(local, remote.toString()) }

        assertEquals("module.exports = 1", remote.resolve("packages/pkg/index.js").readText(), "the real directory must never be skipped")
        assertEquals("module.exports = 1", remote.resolve("node_modules/pkg/index.js").readText(), "the link uploads under its own name")
    }

    @Test
    fun `uploadDir walks each directory at most once through links when links target nested ancestors`(
        @TempDir local: Path,
    ) = runBlocking {
        // "L" sorts before "a", and L1 before L2: the alias walks run before a's own-name walk.
        local
            .resolve("a/b")
            .createDirectories()
            .resolve("f.txt")
            .writeText("F")
        val links = local.resolve("L").createDirectories()
        Files.createSymbolicLink(links.resolve("L1"), Path.of("../a/b"))
        Files.createSymbolicLink(links.resolve("L2"), Path.of("../a"))

        val remote = server.rootDir.resolve("nested-aliases")
        withTimeout(20.seconds) { sync.uploadDir(local, remote.toString()) }

        val uploaded =
            Files.walk(remote).use { walk ->
                walk
                    .filter { it.fileName.toString() == "f.txt" }
                    .map { remote.relativize(it).toString() }
                    .sorted()
                    .toList()
            }
        // Once through L1, once by name; L2's walk reaches a/b already visited and skips it.
        assertEquals(listOf("L/L1/f.txt", "a/b/f.txt"), uploaded)
    }

    @Test
    fun `uploadDir stops descending at the nesting cap`(
        @TempDir local: Path,
    ) = runBlocking {
        // 64 levels are descended, the 65th directory is not (parity with TarStreams).
        val levels = (1..70).map { "d$it" }
        val deepest = levels.fold(local) { dir, name -> dir.resolve(name) }.createDirectories()
        deepest.resolve("never.txt").writeText("too deep")
        val last = levels.take(64).fold(local) { dir, name -> dir.resolve(name) }
        last.resolve("kept.txt").writeText("at the cap")

        val remote = server.rootDir.resolve("deep")
        val warnings = captureWarnings { withTimeout(20.seconds) { sync.uploadDir(local, remote.toString()) } }

        val remoteLast = levels.take(64).fold(remote) { dir, name -> dir.resolve(name) }
        assertEquals("at the cap", remoteLast.resolve("kept.txt").readText())
        assertTrue(Files.notExists(remoteLast.resolve("d65")), "the directory past the cap must not be uploaded")
        assertTrue(
            warnings.any { "nesting exceeds 64 levels" in it.formattedMessage },
            "the skip must be logged, got ${warnings.map { it.formattedMessage }}",
        )
    }

    // ===== A file swapped for a symlink after its checks (CWE-367) =====

    /** A sync whose seam replaces [victim] with a symlink to [target] after the checks, before the open. */
    private fun syncSwapping(
        victim: Path,
        target: Path,
    ) = WorkspaceSync(
        server.newClientSession(client),
        beforeLocalOpen = { opened ->
            if (opened.toAbsolutePath().normalize() == victim.toAbsolutePath().normalize()) {
                Files.delete(victim)
                Files.createSymbolicLink(victim, target)
            }
        },
    )

    @Test
    fun `upload never streams an outside file swapped in for the single file after its checks (CWE-367)`(
        @TempDir local: Path,
        @TempDir outside: Path,
    ) = runBlocking {
        val secret = outside.resolve("host-secret.txt").also { it.writeText("SECRET") }
        val file = local.resolve("plain.txt").also { it.writeText("ordinary") }
        val remote = server.rootDir.resolve("swapped-single.txt")

        val result = runCatching { syncSwapping(file, secret).upload(file, remote.toString()) }

        assertTrue(result.isFailure, "the swapped-in symlink must be refused at the open")
        assertFalse(Files.exists(remote) && remote.readText().contains("SECRET"), "outside content must never be streamed")
    }

    @Test
    fun `uploadDir never streams an outside file swapped in for a walked file after its checks (CWE-367)`(
        @TempDir local: Path,
        @TempDir outside: Path,
    ) = runBlocking {
        val secret = outside.resolve("host-secret.txt").also { it.writeText("SECRET") }
        val file = local.resolve("walked.txt").also { it.writeText("ordinary") }
        val remote = server.rootDir.resolve("swapped-walk")

        val result = runCatching { syncSwapping(file, secret).uploadDir(local, remote.toString()) }

        assertTrue(result.isFailure, "the swapped-in symlink must be refused at the open")
        val uploaded = remote.resolve("walked.txt")
        assertFalse(Files.exists(uploaded) && uploaded.readText().contains("SECRET"), "outside content must never be streamed")
    }

    @Test
    fun `download dispatches single file and honors OVERLAY`(
        @TempDir local: Path,
    ) = runBlocking {
        val remote = server.rootDir.resolve("r.txt").also { it.writeText("remote") }
        val target = local.resolve("r.txt")
        sync.download(remote.toString(), target, mode = DownloadMode.OVERLAY)
        assertEquals("remote", target.readText())
        // OVERLAY: existing local file is kept
        target.writeText("local-edit")
        sync.download(remote.toString(), target, mode = DownloadMode.OVERLAY)
        assertEquals("local-edit", target.readText())
    }

    @Test
    fun `download of missing remote path is a no-op`(
        @TempDir local: Path,
    ) = runBlocking {
        sync.download(server.rootDir.resolve("absent").toString(), local.resolve("out"), mode = DownloadMode.OVERLAY)
        assertTrue(Files.notExists(local.resolve("out")))
    }

    @Test
    fun `single-file download treats MIRROR as OVERWRITE`(
        @TempDir local: Path,
    ) = runBlocking {
        val remote = server.rootDir.resolve("single-mirror.bin").also { it.writeText("remote-payload") }
        val target = local.resolve("single-mirror.bin").also { it.writeText("stale-local") }
        sync.download(remote.toString(), target, mode = DownloadMode.MIRROR)
        assertEquals("remote-payload", target.readText())
    }

    @Test
    fun `download of a remote directory MIRRORs it, pruning local extras`(
        @TempDir local: Path,
    ) = runBlocking {
        val remoteDir = server.rootDir.resolve("dl-mirror").createDirectories()
        remoteDir.resolve("keep.txt").writeText("remote-keep")
        remoteDir
            .resolve("sub")
            .createDirectories()
            .resolve("n.txt")
            .writeText("remote-nested")
        val localDir = local.resolve("dl-mirror").createDirectories()
        localDir.resolve("keep.txt").writeText("local-keep")
        localDir.resolve("extra.txt").writeText("stale")

        sync.download(remoteDir.toString(), localDir, mode = DownloadMode.MIRROR)

        assertEquals("remote-keep", localDir.resolve("keep.txt").readText())
        assertEquals("remote-nested", localDir.resolve("sub/n.txt").readText())
        assertTrue(Files.notExists(localDir.resolve("extra.txt")), "MIRROR must prune a local-only file")
    }

    @Test
    fun `download of a remote directory in OVERLAY keeps local files and extras`(
        @TempDir local: Path,
    ) = runBlocking {
        val remoteDir = server.rootDir.resolve("dl-overlay").createDirectories()
        remoteDir.resolve("streamed.out").writeText("remote")
        remoteDir.resolve("new.txt").writeText("remote-new")
        val localDir = local.resolve("dl-overlay")
        localDir.createDirectories().resolve("streamed.out").writeText("local")
        localDir.resolve("extra.txt").writeText("local-only")

        sync.download(remoteDir.toString(), localDir, mode = DownloadMode.OVERLAY)

        assertEquals("local", localDir.resolve("streamed.out").readText())
        assertEquals("remote-new", localDir.resolve("new.txt").readText())
        assertEquals("local-only", localDir.resolve("extra.txt").readText())
    }

    // ===== MIRROR mode =====

    @Test
    fun `downloadDir MIRROR overwrites a locally existing file with remote content`(
        @TempDir local: Path,
    ) = runBlocking {
        val remoteDir = server.rootDir.resolve("mirror").createDirectories()
        remoteDir.resolve("out.txt").writeText("remote-content")
        val localDir = local.resolve("mirror").createDirectories()
        localDir.resolve("out.txt").writeText("local-content")

        sync.downloadDir(remoteDir.toString(), localDir, mode = DownloadMode.MIRROR)

        assertEquals("remote-content", localDir.resolve("out.txt").readText())
    }

    @Test
    fun `downloadDir MIRROR deletes a local extra file and a local extra nested dir`(
        @TempDir local: Path,
    ) = runBlocking {
        val remoteDir = server.rootDir.resolve("mirror2").createDirectories()
        remoteDir.resolve("keep.txt").writeText("remote-keep")
        val localDir = local.resolve("mirror2").createDirectories()
        localDir.resolve("keep.txt").writeText("local-keep")
        localDir.resolve("extra.txt").writeText("stale")
        localDir.resolve("extra-dir/nested").createDirectories()
        localDir.resolve("extra-dir/nested/deep.txt").writeText("stale-deep")

        sync.downloadDir(remoteDir.toString(), localDir, mode = DownloadMode.MIRROR)

        assertEquals("remote-keep", localDir.resolve("keep.txt").readText())
        assertTrue(Files.notExists(localDir.resolve("extra.txt")))
        assertTrue(Files.notExists(localDir.resolve("extra-dir")))
    }

    @Test
    fun `downloadDir MIRROR does not touch the local dir when the remote dir is missing`(
        @TempDir local: Path,
    ) = runBlocking {
        val localDir = local.resolve("mirror3").createDirectories()
        localDir.resolve("keep.txt").writeText("untouched")

        sync.downloadDir(server.rootDir.resolve("absent-mirror").toString(), localDir, mode = DownloadMode.MIRROR)

        assertEquals("untouched", localDir.resolve("keep.txt").readText())
    }

    @Test
    fun `downloadDir MIRROR does not prune when the SFTP walk fails mid-transfer`(
        @TempDir local: Path,
    ) = runBlocking {
        val remoteDir = server.rootDir.resolve("mirror-fail").createDirectories()
        remoteDir.resolve("keep.txt").writeText("remote-keep")
        val unreadable = remoteDir.resolve("unreadable.txt").also { it.writeText("blocked") }
        Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"))
        // chmod 000 only blocks reads for a non-root process (root bypasses POSIX
        // permission checks) — skip rather than force a false failure/pass under root.
        assumeTrue(!Files.isReadable(unreadable), "must run as non-root to induce a permission failure")

        val localDir = local.resolve("mirror-fail").createDirectories()
        localDir.resolve("extra.txt").writeText("stale-extra")
        try {
            assertThrows<IOException> {
                sync.downloadDir(remoteDir.toString(), localDir, mode = DownloadMode.MIRROR)
            }
            // the walk aborted on the unreadable file; the pre-existing extra must
            // survive since prune only runs after a fully successful walk.
            assertEquals("stale-extra", localDir.resolve("extra.txt").readText())
        } finally {
            Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("rw-------"))
        }
    }

    @Test
    fun `downloadDir OVERLAY still keeps existing local files (extras untouched, no prune)`(
        @TempDir local: Path,
    ) = runBlocking {
        val remoteDir = server.rootDir.resolve("mirror5").createDirectories()
        remoteDir.resolve("keep.txt").writeText("remote-keep")
        val localDir = local.resolve("mirror5").createDirectories()
        localDir.resolve("keep.txt").writeText("local-keep")
        localDir.resolve("extra.txt").writeText("local-extra")

        sync.downloadDir(remoteDir.toString(), localDir, mode = DownloadMode.OVERLAY)

        assertEquals("local-keep", localDir.resolve("keep.txt").readText())
        assertEquals("local-extra", localDir.resolve("extra.txt").readText())
    }

    @Test
    fun `downloadDir MIRROR removes a local extra symlink as a link, leaving its outside target untouched`(
        @TempDir local: Path,
        @TempDir outside: Path,
    ) = runBlocking {
        val remoteDir = server.rootDir.resolve("mirror4").createDirectories()
        remoteDir.resolve("keep.txt").writeText("remote-keep")
        outside.resolve("target.txt").writeText("outside-content")
        val localDir = local.resolve("mirror4").createDirectories()
        localDir.resolve("keep.txt").writeText("local-keep")
        Files.createSymbolicLink(localDir.resolve("stale-link"), outside.resolve("target.txt"))

        sync.downloadDir(remoteDir.toString(), localDir, mode = DownloadMode.MIRROR)

        assertTrue(Files.notExists(localDir.resolve("stale-link")))
        assertEquals("outside-content", outside.resolve("target.txt").readText())
    }

    // ===== Remote-supplied entry names must stay inside localDir =====
    // The embedded server can't emit malicious listings (a real filesystem
    // can't hold "/" in a name), so the containment seam is tested directly.

    @Test
    fun `containedChild resolves a plain filename inside localDir`(
        @TempDir local: Path,
    ) = runBlocking {
        val resolved = WorkspaceSync.containedChild(local, "out.bin")
        assertEquals(local.toAbsolutePath().normalize().resolve("out.bin"), resolved)
    }

    @Test
    fun `containedChild rejects traversal, absolute, and multi-segment names`(
        @TempDir local: Path,
    ) = runBlocking {
        assertThrows<SecurityException> { WorkspaceSync.containedChild(local, "../evil.sh") }
        assertThrows<SecurityException> { WorkspaceSync.containedChild(local, "/etc/cron.d/x") }
        assertThrows<SecurityException> { WorkspaceSync.containedChild(local, "sub/child.txt") }
        assertThrows<SecurityException> { WorkspaceSync.containedChild(local, "evil\\..\\x") }
        assertThrows<SecurityException> { WorkspaceSync.containedChild(local, "..") }
        assertThrows<SecurityException> { WorkspaceSync.containedChild(local, ".") }
        assertThrows<SecurityException> { WorkspaceSync.containedChild(local, "") }
        assertThrows<SecurityException> { WorkspaceSync.containedChild(local, "nul\u0000l") }
    }

    // ===== Pre-existing local symlinks must not redirect writes =====

    @Test
    fun `downloadDir rejects writes through a pre-existing local symlink`(
        @TempDir local: Path,
        @TempDir outside: Path,
    ) = runBlocking {
        val remoteEvil = server.rootDir.resolve("art/evil").createDirectories()
        remoteEvil.resolve("pwn.txt").writeText("pwned")
        Files.createSymbolicLink(local.resolve("evil"), outside)

        assertThrows<SecurityException> {
            sync.downloadDir(server.rootDir.resolve("art").toString(), local, mode = DownloadMode.OVERWRITE)
        }
        assertTrue(Files.notExists(outside.resolve("pwn.txt")))
    }

    @Test
    fun `downloadDir still follows a symlink that stays inside the destination`(
        @TempDir local: Path,
    ) = runBlocking {
        val remoteRoot = server.rootDir.resolve("art2").createDirectories()
        remoteRoot.resolve("ok").writeText("inner")
        val realTarget = local.resolve("real.txt").also { it.writeText("stale") }
        Files.createSymbolicLink(local.resolve("ok"), realTarget)

        sync.downloadDir(remoteRoot.toString(), local, mode = DownloadMode.OVERWRITE)

        assertEquals("inner", realTarget.readText())
    }

    @Test
    fun `downloadDir follows a contained symlink through nested subdirectories`(
        @TempDir local: Path,
    ) = runBlocking {
        val remoteRoot = server.rootDir.resolve("art3").createDirectories()
        remoteRoot
            .resolve("link/sub")
            .createDirectories()
            .resolve("file.txt")
            .writeText("deep-content")

        val realDir = local.resolve("realdir").createDirectories()
        Files.createSymbolicLink(local.resolve("link"), realDir)

        sync.downloadDir(remoteRoot.toString(), local, mode = DownloadMode.OVERWRITE)

        assertEquals("deep-content", realDir.resolve("sub/file.txt").readText())
    }

    @Test
    fun `downloadDir overlay does not spuriously reject a pre-existing local subdirectory two levels deep`(
        @TempDir local: Path,
    ) = runBlocking {
        val remoteRoot = server.rootDir.resolve("art4").createDirectories()
        remoteRoot
            .resolve("sub")
            .createDirectories()
            .resolve("new.txt")
            .writeText("remote-new")

        // Local destination already has "sub" from a prior run (the production overlay shape).
        val localSub = local.resolve("sub").createDirectories()
        localSub.resolve("keep.txt").writeText("local-keep")

        sync.downloadDir(remoteRoot.toString(), local, mode = DownloadMode.OVERLAY)

        assertEquals("local-keep", localSub.resolve("keep.txt").readText(), "overlay must keep pre-existing local file")
        assertEquals("remote-new", localSub.resolve("new.txt").readText(), "remote-only nested file must still download")
    }

    // ===== Upload symlinks must not leak host content outside the upload root =====

    @Test
    fun `uploadDir skips a symlink escaping the upload root`(
        @TempDir local: Path,
        @TempDir outside: Path,
    ) = runBlocking {
        outside.resolve("secret.txt").writeText("host-secret")
        Files.createSymbolicLink(local.resolve("secret-link"), outside.resolve("secret.txt"))

        val remote = server.rootDir.resolve("upload-escape").toString()
        sync.uploadDir(local, remote)

        assertTrue(Files.notExists(server.rootDir.resolve("upload-escape/secret-link")))
    }

    @Test
    fun `uploadDir dereferences a symlink that stays inside the upload root`(
        @TempDir local: Path,
    ) = runBlocking {
        local.resolve("real.txt").writeText("inner-content")
        Files.createSymbolicLink(local.resolve("inner-link"), Path.of("real.txt"))

        val remote = server.rootDir.resolve("upload-inner").toString()
        sync.uploadDir(local, remote)

        assertEquals("inner-content", server.rootDir.resolve("upload-inner/inner-link").readText())
    }

    // ===== Transfer deadline on a stalling peer (CWE-400) =====
    // Keepalives only disconnect a peer that stops answering; these tests pin
    // the coroutine-level bound that also fires for one that keeps answering
    // while withholding SFTP data. EmbeddedSshServer serves any `stall*` file
    // as an eternally-hanging read for this.

    @Test
    fun `download_times_out_when_sftp_stalls`(
        @TempDir local: Path,
    ) = runBlocking {
        // Larger than one SFTP read chunk: the client's first read is served,
        // the next one never answers — the stall the deadline must catch.
        server.rootDir.resolve("stall.bin").writeText("x".repeat(128 * 1024))
        val stalling = WorkspaceSync(server.newClientSession(client), transferTimeout = 2.seconds)

        assertThrows<IOException> {
            stalling.download(server.rootDir.resolve("stall.bin").toString(), local.resolve("stall.bin"), mode = DownloadMode.OVERWRITE)
        }
        Unit
    }

    @Test
    fun `downloadDir_times_out_when_sftp_stalls`(
        @TempDir local: Path,
    ) = runBlocking {
        val remoteDir = server.rootDir.resolve("stall-dir").createDirectories()
        remoteDir.resolve("stall.bin").writeText("x".repeat(128 * 1024))
        val stalling = WorkspaceSync(server.newClientSession(client), transferTimeout = 2.seconds)

        assertThrows<IOException> {
            stalling.downloadDir(remoteDir.toString(), local.resolve("out"), DownloadMode.OVERWRITE)
        }
        Unit
    }

    @Test
    fun `upload_times_out_when_sftp_stalls`(
        @TempDir local: Path,
    ) = runBlocking {
        val stalling = WorkspaceSync(server.newClientSession(client), transferTimeout = 2.seconds)
        val payload = local.resolve("payload.bin").also { it.writeText("upload-payload") }

        assertThrows<IOException> {
            stalling.upload(payload, server.rootDir.resolve("stall-upload.bin").toString())
        }
        Unit
    }

    @Test
    fun `removeRecursively_times_out_when_sftp_stalls`(
        @TempDir local: Path,
    ) = runBlocking {
        val remoteDir = server.rootDir.resolve("to-remove").createDirectories()
        // Place a stall-named file so the server's removeFile override blocks on removal.
        remoteDir.resolve("stall.bin").writeText("data")
        val stalling = WorkspaceSync(server.newClientSession(client), transferTimeout = 2.seconds)

        assertThrows<IOException> {
            stalling.removeRecursively(remoteDir.toString())
        }
        Unit
    }

    // ===== The release itself is bounded (CWE-400) =====
    // MINA's own SftpClient.close() only *enqueues* SSH_MSG_CHANNEL_CLOSE
    // on the non-blocking session write and returns, so no SSH peer can make it
    // stall (verified against a healthy, a torn-down, a mid-rekey and a
    // mid-stalled-read session). The client is therefore injected here: the
    // bound under test is ours, and a fake is the only way to exercise it.

    @Test
    fun `close_times_out_when_the_release_stalls`() =
        runBlocking {
            val session = server.newClientSession(client)
            val stalling = mockk<SftpClient>(relaxed = true)
            // The round trip succeeds instantly; only the release stalls. It polls
            // the session the way a real blocked SFTP call does — the watchdog's
            // session.close(true) is what sheds it, so a mock that ignored the
            // teardown would just hang and prove nothing. The cap is a safety valve:
            // without the bound the test fails on the assertion below, not hangs.
            every { stalling.close() } answers { releaseWhenSessionCloses(session, 30.seconds) }
            val sync = WorkspaceSync(session, transferTimeout = 2.seconds) { stalling }

            val start = System.nanoTime()
            sync.mkdirs("/hdh9")
            val elapsedMs = (System.nanoTime() - start) / 1_000_000

            assertTrue(elapsedMs < 60_000, "the release must be shed at the deadline; took ${elapsedMs}ms")
            assertTrue(session.isClosed(), "the watchdog must tear the session down to unblock the stalled release")
        }

    @Test
    fun `failed_close_does_not_mask_the_operations_outcome`() =
        runBlocking {
            val session = server.newClientSession(client)
            val failing = mockk<SftpClient>(relaxed = true)
            every { failing.close() } throws IOException("peer refused the release")
            val sync = WorkspaceSync(session, transferTimeout = 2.seconds) { failing }

            val warnings =
                captureWarnings {
                    // A release failure is swallowed: the operation's own outcome stands
                    // and no exception escapes to the caller.
                    sync.mkdirs("/hdh9-ok")
                }

            verify { failing.mkdir(any()) }
            verify { failing.close() }
            assertTrue(
                warnings.any { it.formattedMessage.contains("Failed to close the SFTP client") },
                "a swallowed release failure must be WARN-logged; got $warnings",
            )
        }

    @Test
    fun `failed_close_sheds_the_session`() =
        runBlocking {
            // A close that fails outright (not a deadline expiry — the watchdog
            // already sheds those) must close the session too: the session's state
            // is unknown, and reusing it would let each failed release strand
            // another SFTP channel while the run still holds the lock and slot.
            val session = server.newClientSession(client)
            val failing = mockk<SftpClient>(relaxed = true)
            every { failing.close() } throws IOException("peer refused the release")
            val sync = WorkspaceSync(session, transferTimeout = 2.seconds) { failing }

            captureWarnings { sync.mkdirs("/hdh9-shed") }

            assertTrue(session.isClosed(), "a failed release must shed the session so nothing reuses it")
        }

    @Test
    fun `null_timeout_leaves_the_release_unbounded`() =
        runBlocking {
            val release = CountDownLatch(1)
            val session = server.newClientSession(client)
            val stalling = mockk<SftpClient>(relaxed = true)
            every { stalling.close() } answers { release.await() }
            val unbounded = WorkspaceSync(session, transferTimeout = null) { stalling }

            // No watchdog: the stalled release is not shed and the session stays open.
            val stalled = launch(Dispatchers.IO) { unbounded.mkdirs("/hdh9-null") }
            delay(1.seconds)
            assertTrue(stalled.isActive, "a null transferTimeout must keep meaning no watchdog, close included")
            assertFalse(session.isClosed())
            release.countDown()
            stalled.join()
        }

    @Test
    fun `cancelled_caller_still_sheds_a_stalled_release`() =
        runBlocking {
            val session = server.newClientSession(client)
            val stalling = mockk<SftpClient>(relaxed = true)
            // The watchdog is a child of the caller's job: once the caller is
            // cancelled its delay() throws before the deadline ever fires, so only
            // a non-cancellable release keeps the bound alive (a run killed during
            // sync-up lands exactly here).
            every { stalling.close() } answers { releaseWhenSessionCloses(session, 30.seconds) }
            val sync = WorkspaceSync(session, transferTimeout = 2.seconds) { stalling }

            val start = System.nanoTime()
            val stalled = launch(Dispatchers.IO) { sync.mkdirs("/hdh9-cancelled") }
            delay(1.seconds) // the round trip is done; the release is now stalled on close()
            stalled.cancel()

            withTimeout(60.seconds) { stalled.join() }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000

            assertTrue(
                elapsedMs < 60_000,
                "the stalled release must be shed at the deadline even for a cancelled caller; took ${elapsedMs}ms",
            )
            assertTrue(session.isClosed(), "the watchdog must tear the session down to unblock the stalled release")
        }

    /**
     * Block like a real stalled SFTP call: until the session the watchdog tears
     * down is observed closed, or [cap] lapses.
     */
    private fun releaseWhenSessionCloses(
        session: ClientSession,
        cap: kotlin.time.Duration,
    ) {
        val deadline = System.nanoTime() + cap.inWholeNanoseconds
        while (!session.isClosed && System.nanoTime() < deadline) Thread.sleep(20)
    }

    /** Run [body] with WorkspaceSync's WARN output captured. */
    private suspend fun captureWarnings(body: suspend () -> Unit): List<ILoggingEvent> {
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val logback = LoggerFactory.getLogger(WorkspaceSync::class.java) as LogbackLogger
        val level = logback.level
        logback.level = LogbackLevel.WARN
        logback.addAppender(appender)
        try {
            body()
        } finally {
            logback.detachAppender(appender)
            logback.level = level
        }
        return appender.list
    }

    @Test
    fun `readDir cap rejects oversized remote listing`(
        @TempDir local: Path,
    ) = runBlocking {
        val remoteDir = server.rootDir.resolve("big-dir").createDirectories()
        repeat(4) { i -> remoteDir.resolve("f$i.txt").writeText("data") }
        // cap of 3 is below the 4 actual files (plus the . and .. entries the server returns)
        val capped = WorkspaceSync(server.newClientSession(client), maxDirEntries = 3)

        assertThrows<IOException> {
            capped.downloadDir(remoteDir.toString(), local.resolve("out"), DownloadMode.OVERWRITE)
        }
        Unit
    }

    @Test
    fun `transfer deadline fires within the configured bound and closes the session`(
        @TempDir local: Path,
    ) = runBlocking {
        server.rootDir.resolve("stall2.bin").writeText("x".repeat(128 * 1024))
        val session = server.newClientSession(client)
        val stalling = WorkspaceSync(session, transferTimeout = 2.seconds)

        val start = System.nanoTime()
        val e =
            assertThrows<IOException> {
                stalling.download(
                    server.rootDir.resolve("stall2.bin").toString(),
                    local.resolve("stall2.bin"),
                    mode = DownloadMode.OVERWRITE,
                )
            }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs < 60_000, "deadline must fire well before an unbounded wait; took ${elapsedMs}ms")
        // session.close(true) in the cancellation path unblocks the blocked
        // IO thread; the closed session must be observable afterwards.
        assertTrue(session.isClosed())
        assertTrue(e.message!!.contains("timed out"))
    }
}
