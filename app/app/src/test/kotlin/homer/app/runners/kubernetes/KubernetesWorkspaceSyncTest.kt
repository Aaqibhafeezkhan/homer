package homer.app.runners.kubernetes

import homer.app.runners.DownloadMode
import homer.app.runners.TarStreams
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KubernetesWorkspaceSyncTest {
    // "Remote" execs run locally: the pod's filesystem is the local one.
    // COPYFILE_DISABLE avoids macOS bsdtar emitting AppleDouble "._name" entries
    // for files carrying xattrs (e.g. com.apple.provenance) — a local test-host
    // artifact absent on the Linux pods this runs against in production.
    private val localExec =
        KubernetesExecClient { _, _, argv, _ ->
            ProcessBuilder(argv).apply { environment()["COPYFILE_DISABLE"] = "1" }.start()
        }

    private fun sync() = KubernetesWorkspaceSync(localExec, "ns", "pod-1")

    @Test
    fun `mkdirs creates nested directories`(
        @TempDir remote: Path,
    ) {
        sync().mkdirs("$remote/a/b/c")
        assertTrue(Files.isDirectory(remote.resolve("a/b/c")))
    }

    @Test
    fun `directory upload round-trips many small files with excludes and modes`(
        @TempDir local: Path,
        @TempDir remote: Path,
    ) {
        repeat(200) { local.resolve("f$it.txt").writeText("v$it") }
        local.resolve("sub/deep").createDirectories()
        local.resolve("sub/deep/nested.txt").writeText("nested")
        local.resolve("sub/.git").createDirectories()
        local.resolve("sub/.git/HEAD").writeText("ref")
        local.resolve(".git").createDirectories()
        local.resolve(".git/config").writeText("x")
        val script = local.resolve("run.sh")
        script.writeText("#!/bin/sh")
        Files.setPosixFilePermissions(
            script,
            PosixFilePermission.entries.toSet() - PosixFilePermission.OTHERS_WRITE - PosixFilePermission.GROUP_WRITE,
        )

        sync().upload(local, "$remote/ws", excludes = listOf(".git"))

        assertEquals("v0", remote.resolve("ws/f0.txt").readText())
        assertEquals("v199", remote.resolve("ws/f199.txt").readText())
        assertEquals("nested", remote.resolve("ws/sub/deep/nested.txt").readText())
        // excludes match by entry name at ANY depth
        assertFalse(Files.exists(remote.resolve("ws/.git")))
        assertFalse(Files.exists(remote.resolve("ws/sub/.git")))
        // executable bit survives (umask 022 keeps 755)
        assertTrue(PosixFilePermission.OWNER_EXECUTE in remote.resolve("ws/run.sh").getPosixFilePermissions())
    }

    @Test
    fun `anchored exclude scopes to the upload root, bare exclude still matches at any depth`(
        @TempDir local: Path,
        @TempDir remote: Path,
    ) {
        local.resolve("build").createDirectories()
        local.resolve("build/root.txt").writeText("root-build")
        local.resolve("sub/build").createDirectories()
        local.resolve("sub/build/nested.txt").writeText("nested-build")

        sync().upload(local, "$remote/anchored", excludes = listOf("/build"))
        // anchored exclude only removes the root-level entry
        assertFalse(Files.exists(remote.resolve("anchored/build")))
        assertTrue(Files.exists(remote.resolve("anchored/sub/build/nested.txt")))

        sync().upload(local, "$remote/bare", excludes = listOf("build"))
        // bare exclude keeps matching by entry name at any depth
        assertFalse(Files.exists(remote.resolve("bare/build")))
        assertFalse(Files.exists(remote.resolve("bare/sub/build")))
    }

    @Test
    fun `omitDirEntries omits the top-level directory entry but keeps its contents and a same-named nested dir`(
        @TempDir local: Path,
        @TempDir remote: Path,
    ) {
        local.resolve("workdir/nested").createDirectories()
        local.resolve("workdir/out.txt").writeText("workdir-file")
        local.resolve("workdir/nested/deep.txt").writeText("nested-file")
        local.resolve("repo/workdir").createDirectories()
        local.resolve("repo/workdir/inner.txt").writeText("repo-workdir-file")

        // Real tar extraction (to prove contents still arrive) tee'd to a captured raw
        // stream (to inspect which directory entries the producer actually wrote — an
        // extracted tree alone can't tell "no dir entry" apart from "dir entry present",
        // since tar auto-creates missing parents for a file entry either way).
        val capturedTar = remote.resolve("captured.tar.gz")
        val client =
            KubernetesExecClient { _, _, argv, _ ->
                if (argv.firstOrNull() == "tar" && argv.contains("-xzf")) {
                    val destDir = argv.last()
                    ProcessBuilder(listOf("sh", "-c", "tee '$capturedTar' | tar -xzf - -C '$destDir'"))
                        .apply { environment()["COPYFILE_DISABLE"] = "1" }
                        .start()
                } else {
                    ProcessBuilder(argv).apply { environment()["COPYFILE_DISABLE"] = "1" }.start()
                }
            }

        KubernetesWorkspaceSync(client, "ns", "pod-1")
            .upload(local, "$remote/ws", omitDirEntries = setOf("workdir"))

        // contents still upload and extract, including under the nested same-named dir
        assertEquals("workdir-file", remote.resolve("ws/workdir/out.txt").readText())
        assertEquals("nested-file", remote.resolve("ws/workdir/nested/deep.txt").readText())
        assertEquals("repo-workdir-file", remote.resolve("ws/repo/workdir/inner.txt").readText())

        val entryNames =
            TarArchiveInputStream(GzipCompressorInputStream(Files.newInputStream(capturedTar))).use { tar ->
                generateSequence { tar.nextEntry }.map { it.name }.toList()
            }
        // the top-level workdir's own directory entry is omitted...
        assertFalse("workdir/" in entryNames)
        assertTrue("workdir/out.txt" in entryNames)
        assertTrue("workdir/nested/" in entryNames)
        assertTrue("workdir/nested/deep.txt" in entryNames)
        // ...while a nested directory that happens to also be named "workdir" keeps its entry
        assertTrue("repo/workdir/" in entryNames)
    }

    @Test
    fun `an explicit exclude beats omitDirEntries — nothing under workdir uploads`(
        @TempDir local: Path,
        @TempDir remote: Path,
    ) {
        local.resolve("workdir/nested").createDirectories()
        local.resolve("workdir/out.txt").writeText("workdir-file")
        local.resolve("workdir/nested/deep.txt").writeText("nested-file")

        sync().upload(local, "$remote/ws", excludes = listOf("/workdir"), omitDirEntries = setOf("workdir"))

        // the exclude check runs (and returns) before the omit branch: if the omit branch
        // ran first, workdir's contents would still upload despite the exclude, and this
        // directory would exist locally (tar auto-creates parents for file entries).
        assertFalse(Files.exists(remote.resolve("ws/workdir")))
    }

    @Test
    fun `single file upload creates remote parent dirs`(
        @TempDir local: Path,
        @TempDir remote: Path,
    ) {
        val f = local.resolve("git.sh")
        f.writeText("#!/bin/sh")
        sync().upload(f, "$remote/ws/.homer/scripts/git.sh")
        assertEquals("#!/bin/sh", remote.resolve("ws/.homer/scripts/git.sh").readText())
    }

    @Test
    fun `upload skips a symlink escaping the workspace root`(
        @TempDir local: Path,
        @TempDir remote: Path,
        @TempDir outside: Path,
    ) {
        outside.resolve("secret.txt").writeText("host-secret")
        Files.createSymbolicLink(local.resolve("secret-link"), outside.resolve("secret.txt"))

        sync().upload(local, "$remote/ws")

        assertFalse(Files.exists(remote.resolve("ws/secret-link")))
    }

    @Test
    fun `upload dereferences a symlink that stays inside the workspace root`(
        @TempDir local: Path,
        @TempDir remote: Path,
    ) {
        local.resolve("real.txt").writeText("inner-content")
        Files.createSymbolicLink(local.resolve("inner-link"), Path.of("real.txt"))

        sync().upload(local, "$remote/ws")

        assertEquals("inner-content", remote.resolve("ws/inner-link").readText())
    }

    @Test
    fun `download honors OVERLAY and OVERWRITE and is a silent no-op on missing remote`(
        @TempDir local: Path,
        @TempDir remote: Path,
    ) {
        remote.resolve("artifacts").createDirectories()
        remote.resolve("artifacts/result.txt").writeText("remote")
        remote.resolve("artifacts/cmd_0.out").writeText("remote-out")
        local.resolve("artifacts").createDirectories()
        local.resolve("artifacts/cmd_0.out").writeText("local-streamed")

        sync().download("$remote/artifacts", local.resolve("artifacts"), mode = DownloadMode.OVERLAY)
        assertEquals("remote", local.resolve("artifacts/result.txt").readText())
        // OVERLAY keeps locally-streamed files
        assertEquals("local-streamed", local.resolve("artifacts/cmd_0.out").readText())

        sync().download("$remote/artifacts", local.resolve("artifacts"), mode = DownloadMode.OVERWRITE)
        assertEquals("remote-out", local.resolve("artifacts/cmd_0.out").readText())

        // missing remote path: silent no-op
        sync().download("$remote/nope", local.resolve("nope"), mode = DownloadMode.OVERLAY)
        assertFalse(Files.exists(local.resolve("nope")))
    }

    @Test
    fun `download of a single remote file works`(
        @TempDir local: Path,
        @TempDir remote: Path,
    ) {
        remote.resolve("workdir").createDirectories()
        remote.resolve("workdir/out.bin").writeText("payload")
        val target = local.resolve("in/out.bin")
        sync().download("$remote/workdir/out.bin", target, mode = DownloadMode.OVERWRITE)
        assertEquals("payload", target.readText())
    }

    @Test
    fun `download of a single dotfile works`(
        @TempDir local: Path,
        @TempDir remote: Path,
    ) {
        remote.resolve("workdir").createDirectories()
        remote.resolve("workdir/.env").writeText("SECRET=1")
        val target = local.resolve("in/.env")
        sync().download("$remote/workdir/.env", target, mode = DownloadMode.OVERWRITE)
        assertEquals("SECRET=1", target.readText())
    }

    @Test
    fun `single-file download treats MIRROR as OVERWRITE`(
        @TempDir local: Path,
        @TempDir remote: Path,
    ) {
        remote.resolve("workdir").createDirectories()
        remote.resolve("workdir/out.bin").writeText("remote-payload")
        val target = local.resolve("out.bin")
        target.writeText("stale-local")

        sync().download("$remote/workdir/out.bin", target, mode = DownloadMode.MIRROR)

        assertEquals("remote-payload", target.readText())
    }

    @Test
    fun `MIRROR overwrites a locally existing file with remote content`(
        @TempDir local: Path,
        @TempDir remote: Path,
    ) {
        remote.resolve("workdir").createDirectories()
        remote.resolve("workdir/out.txt").writeText("remote-content")
        val localWorkdir = local.resolve("workdir").createDirectories()
        localWorkdir.resolve("out.txt").writeText("local-content")

        sync().download("$remote/workdir", localWorkdir, mode = DownloadMode.MIRROR)

        assertEquals("remote-content", localWorkdir.resolve("out.txt").readText())
    }

    @Test
    fun `MIRROR deletes a local extra file and a local extra nested dir`(
        @TempDir local: Path,
        @TempDir remote: Path,
    ) {
        remote.resolve("workdir").createDirectories()
        remote.resolve("workdir/keep.txt").writeText("remote-keep")
        val localWorkdir = local.resolve("workdir").createDirectories()
        localWorkdir.resolve("keep.txt").writeText("local-keep")
        localWorkdir.resolve("extra.txt").writeText("stale")
        localWorkdir.resolve("extra-dir/nested").createDirectories()
        localWorkdir.resolve("extra-dir/nested/deep.txt").writeText("stale-deep")

        sync().download("$remote/workdir", localWorkdir, mode = DownloadMode.MIRROR)

        assertEquals("remote-keep", localWorkdir.resolve("keep.txt").readText())
        assertFalse(Files.exists(localWorkdir.resolve("extra.txt")))
        assertFalse(Files.exists(localWorkdir.resolve("extra-dir")))
    }

    @Test
    fun `MIRROR does not touch the local dir when the remote dir is missing`(
        @TempDir local: Path,
        @TempDir remote: Path,
    ) {
        val localWorkdir = local.resolve("workdir").createDirectories()
        localWorkdir.resolve("keep.txt").writeText("untouched")

        sync().download("$remote/nope", localWorkdir, mode = DownloadMode.MIRROR)

        assertEquals("untouched", localWorkdir.resolve("keep.txt").readText())
    }

    @Test
    fun `OVERLAY still keeps existing local files (extras untouched, no prune)`(
        @TempDir local: Path,
        @TempDir remote: Path,
    ) {
        remote.resolve("workdir").createDirectories()
        remote.resolve("workdir/keep.txt").writeText("remote-keep")
        val localWorkdir = local.resolve("workdir").createDirectories()
        localWorkdir.resolve("keep.txt").writeText("local-keep")
        localWorkdir.resolve("extra.txt").writeText("local-extra")

        sync().download("$remote/workdir", localWorkdir, mode = DownloadMode.OVERLAY)

        assertEquals("local-keep", localWorkdir.resolve("keep.txt").readText())
        assertEquals("local-extra", localWorkdir.resolve("extra.txt").readText())
    }

    @Test
    fun `MIRROR removes a local extra symlink as a link, leaving its outside target untouched`(
        @TempDir local: Path,
        @TempDir remote: Path,
        @TempDir outside: Path,
    ) {
        remote.resolve("workdir").createDirectories()
        remote.resolve("workdir/keep.txt").writeText("remote-keep")
        outside.resolve("target.txt").writeText("outside-content")
        val localWorkdir = local.resolve("workdir").createDirectories()
        localWorkdir.resolve("keep.txt").writeText("local-keep")
        Files.createSymbolicLink(localWorkdir.resolve("stale-link"), outside.resolve("target.txt"))

        sync().download("$remote/workdir", localWorkdir, mode = DownloadMode.MIRROR)

        assertFalse(Files.exists(localWorkdir.resolve("stale-link")))
        assertEquals("outside-content", outside.resolve("target.txt").readText())
    }

    @Test
    fun `MIRROR from an existing but empty remote dir prunes all local extras`(
        @TempDir local: Path,
        @TempDir remote: Path,
    ) {
        remote.resolve("workdir").createDirectories()
        val localWorkdir = local.resolve("workdir").createDirectories()
        localWorkdir.resolve("extra.txt").writeText("stale")
        localWorkdir.resolve("extra-dir/nested").createDirectories()
        localWorkdir.resolve("extra-dir/nested/deep.txt").writeText("stale-deep")

        sync().download("$remote/workdir", localWorkdir, mode = DownloadMode.MIRROR)

        assertFalse(Files.exists(localWorkdir.resolve("extra.txt")))
        assertFalse(Files.exists(localWorkdir.resolve("extra-dir")))
    }

    @Test
    fun `MIRROR does not prune when the tar process exits non-zero`(
        @TempDir local: Path,
        @TempDir remote: Path,
    ) {
        remote.resolve("workdir").createDirectories()
        val localWorkdir = local.resolve("workdir").createDirectories()
        localWorkdir.resolve("extra.txt").writeText("stale-extra")
        val tarBytes =
            ByteArrayOutputStream()
                .also { buf ->
                    GzipCompressorOutputStream(buf).use { gz ->
                        TarArchiveOutputStream(gz).use { tar ->
                            val content = "v".toByteArray()
                            val entry = TarArchiveEntry("keep.txt")
                            entry.size = content.size.toLong()
                            tar.putArchiveEntry(entry)
                            tar.write(content)
                            tar.closeArchiveEntry()
                        }
                    }
                }.toByteArray()
        val fakeTarProc =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns ByteArrayInputStream(tarBytes)
                every { errorStream } returns ByteArrayInputStream("boom".toByteArray())
                every { outputStream } returns ByteArrayOutputStream()
                every { waitFor(any<Long>(), any<TimeUnit>()) } returns true
                every { exitValue() } returns 1
            }
        val client =
            KubernetesExecClient { ns, pod, argv, stdin ->
                if (argv.firstOrNull() == "tar") fakeTarProc else localExec.start(ns, pod, argv, stdin)
            }

        assertFailsWith<IllegalStateException> {
            KubernetesWorkspaceSync(client, "ns", "pod-1").download(
                "$remote/workdir",
                localWorkdir,
                mode = DownloadMode.MIRROR,
            )
        }
        // extraction ran (keep.txt was written), but the non-zero exit must abort
        // before prune runs, so the pre-existing extra must survive.
        assertEquals("v", localWorkdir.resolve("keep.txt").readText())
        assertEquals("stale-extra", localWorkdir.resolve("extra.txt").readText())
    }

    @Test
    fun `MIRROR does not prune when the tar stream throws mid-extraction`(
        @TempDir local: Path,
        @TempDir remote: Path,
    ) {
        remote.resolve("workdir").createDirectories()
        val localWorkdir = local.resolve("workdir").createDirectories()
        localWorkdir.resolve("extra.txt").writeText("stale-extra")
        // A complete tar (one small entry fully extracted, one large entry that
        // won't be) truncated well before the end, simulating a channel that dies
        // mid-transfer (network blip, killed pod): the incomplete gzip/tar data
        // makes decompression itself throw once it runs out of bytes.
        val fullBytes =
            ByteArrayOutputStream()
                .also { buf ->
                    GzipCompressorOutputStream(buf).use { gz ->
                        TarArchiveOutputStream(gz).use { tar ->
                            val small = "v".toByteArray()
                            val smallEntry = TarArchiveEntry("keep.txt")
                            smallEntry.size = small.size.toLong()
                            tar.putArchiveEntry(smallEntry)
                            tar.write(small)
                            tar.closeArchiveEntry()
                            val large = ByteArray(50_000) { 'x'.code.toByte() }
                            val largeEntry = TarArchiveEntry("never-finishes.bin")
                            largeEntry.size = large.size.toLong()
                            tar.putArchiveEntry(largeEntry)
                            tar.write(large)
                            tar.closeArchiveEntry()
                        }
                    }
                }.toByteArray()
        val truncatedBytes = fullBytes.copyOf(fullBytes.size / 2)
        val fakeTarProc =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns ByteArrayInputStream(truncatedBytes)
                every { errorStream } returns ByteArrayInputStream(ByteArray(0))
                every { outputStream } returns ByteArrayOutputStream()
                every { waitFor(any<Long>(), any<TimeUnit>()) } returns true
                every { exitValue() } returns 0
            }
        val client =
            KubernetesExecClient { ns, pod, argv, stdin ->
                if (argv.firstOrNull() == "tar") fakeTarProc else localExec.start(ns, pod, argv, stdin)
            }

        assertFailsWith<java.io.IOException> {
            KubernetesWorkspaceSync(client, "ns", "pod-1").download(
                "$remote/workdir",
                localWorkdir,
                mode = DownloadMode.MIRROR,
            )
        }
        assertEquals("stale-extra", localWorkdir.resolve("extra.txt").readText())
    }

    @Test
    fun `MIRROR materializes a file's parent chain even without a directory entry for it`(
        @TempDir local: Path,
        @TempDir remote: Path,
    ) {
        remote.resolve("workdir").createDirectories()
        val localWorkdir = local.resolve("workdir").createDirectories()
        localWorkdir.resolve("elsewhere.txt").writeText("stale-elsewhere")
        // A tar stream carrying "sub/file.txt" with no preceding "sub/" directory
        // entry — a legitimate shape some tar producers emit, and (per ADR-0055's
        // trust boundary) one an attacker-replaced pod tar binary could also craft.
        val tarBytes =
            ByteArrayOutputStream()
                .also { buf ->
                    GzipCompressorOutputStream(buf).use { gz ->
                        TarArchiveOutputStream(gz).use { tar ->
                            val content = "nested".toByteArray()
                            val entry = TarArchiveEntry("sub/file.txt")
                            entry.size = content.size.toLong()
                            tar.putArchiveEntry(entry)
                            tar.write(content)
                            tar.closeArchiveEntry()
                        }
                    }
                }.toByteArray()
        val fakeTarProc =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns ByteArrayInputStream(tarBytes)
                every { errorStream } returns ByteArrayInputStream(ByteArray(0))
                every { outputStream } returns ByteArrayOutputStream()
                every { waitFor(any<Long>(), any<TimeUnit>()) } returns true
                every { exitValue() } returns 0
            }
        val client =
            KubernetesExecClient { ns, pod, argv, stdin ->
                if (argv.firstOrNull() == "tar") fakeTarProc else localExec.start(ns, pod, argv, stdin)
            }

        KubernetesWorkspaceSync(client, "ns", "pod-1").download(
            "$remote/workdir",
            localWorkdir,
            mode = DownloadMode.MIRROR,
        )

        assertEquals("nested", localWorkdir.resolve("sub/file.txt").readText())
        // the dir-entry-less parent ("sub") must not fail DirectoryNotEmptyException
        // during prune, and an extra elsewhere must still be pruned.
        assertFalse(Files.exists(localWorkdir.resolve("elsewhere.txt")))
    }

    @Test
    fun `MIRROR materializes a directory entry's own parent chain even without a directory entry for it`(
        @TempDir local: Path,
        @TempDir remote: Path,
    ) {
        remote.resolve("workdir").createDirectories()
        val localWorkdir = local.resolve("workdir").createDirectories()
        localWorkdir.resolve("elsewhere.txt").writeText("stale-elsewhere")
        // A tar stream carrying only "sub/inner/" — no preceding "sub/" directory
        // entry — the symmetric dir-entry-less shape for the directory branch of
        // extractEntry (the sibling of the file-entry case above).
        val tarBytes =
            ByteArrayOutputStream()
                .also { buf ->
                    GzipCompressorOutputStream(buf).use { gz ->
                        TarArchiveOutputStream(gz).use { tar ->
                            val entry = TarArchiveEntry("sub/inner/")
                            tar.putArchiveEntry(entry)
                            tar.closeArchiveEntry()
                        }
                    }
                }.toByteArray()
        val fakeTarProc =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns ByteArrayInputStream(tarBytes)
                every { errorStream } returns ByteArrayInputStream(ByteArray(0))
                every { outputStream } returns ByteArrayOutputStream()
                every { waitFor(any<Long>(), any<TimeUnit>()) } returns true
                every { exitValue() } returns 0
            }
        val client =
            KubernetesExecClient { ns, pod, argv, stdin ->
                if (argv.firstOrNull() == "tar") fakeTarProc else localExec.start(ns, pod, argv, stdin)
            }

        KubernetesWorkspaceSync(client, "ns", "pod-1").download(
            "$remote/workdir",
            localWorkdir,
            mode = DownloadMode.MIRROR,
        )

        assertTrue(Files.isDirectory(localWorkdir.resolve("sub/inner")))
        // the dir-entry-less parent ("sub") must not fail DirectoryNotEmptyException
        // during prune, and an extra elsewhere must still be pruned.
        assertFalse(Files.exists(localWorkdir.resolve("elsewhere.txt")))
    }

    @Test
    fun `download destroys the tar process when extraction fails containment`(
        @TempDir local: Path,
        @TempDir remote: Path,
    ) {
        remote.resolve("workdir").createDirectories()
        val tarBytes =
            ByteArrayOutputStream()
                .also { buf ->
                    GzipCompressorOutputStream(buf).use { gz ->
                        TarArchiveOutputStream(gz).use { tar ->
                            val content = "evil".toByteArray()
                            val entry = TarArchiveEntry("../evil")
                            entry.size = content.size.toLong()
                            tar.putArchiveEntry(entry)
                            tar.write(content)
                            tar.closeArchiveEntry()
                        }
                    }
                }.toByteArray()
        val fakeTarProc =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns ByteArrayInputStream(tarBytes)
                every { errorStream } returns ByteArrayInputStream(ByteArray(0))
                every { outputStream } returns ByteArrayOutputStream()
                every { waitFor(any<Long>(), any<TimeUnit>()) } returns true
                every { exitValue() } returns 0
            }
        val client =
            KubernetesExecClient { ns, pod, argv, stdin ->
                if (argv.firstOrNull() == "tar") fakeTarProc else localExec.start(ns, pod, argv, stdin)
            }

        assertFailsWith<SecurityException> {
            KubernetesWorkspaceSync(client, "ns", "pod-1").download(
                "$remote/workdir",
                local.resolve("out"),
                mode = DownloadMode.OVERWRITE,
            )
        }
        verify { fakeTarProc.destroyForcibly() }
    }

    @Test
    fun `download rejects extraction through a pre-existing local symlink`(
        @TempDir local: Path,
        @TempDir remote: Path,
        @TempDir outside: Path,
    ) {
        remote.resolve("workdir").createDirectories()
        Files.createSymbolicLink(local.resolve("evil"), outside)

        val tarBytes =
            ByteArrayOutputStream()
                .also { buf ->
                    GzipCompressorOutputStream(buf).use { gz ->
                        TarArchiveOutputStream(gz).use { tar ->
                            val content = "pwned".toByteArray()
                            val entry = TarArchiveEntry("evil/pwn.txt")
                            entry.size = content.size.toLong()
                            tar.putArchiveEntry(entry)
                            tar.write(content)
                            tar.closeArchiveEntry()
                        }
                    }
                }.toByteArray()
        val fakeTarProc =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns ByteArrayInputStream(tarBytes)
                every { errorStream } returns ByteArrayInputStream(ByteArray(0))
                every { outputStream } returns ByteArrayOutputStream()
                every { waitFor(any<Long>(), any<TimeUnit>()) } returns true
                every { exitValue() } returns 0
            }
        val client =
            KubernetesExecClient { ns, pod, argv, stdin ->
                if (argv.firstOrNull() == "tar") fakeTarProc else localExec.start(ns, pod, argv, stdin)
            }

        assertFailsWith<SecurityException> {
            KubernetesWorkspaceSync(client, "ns", "pod-1").download(
                "$remote/workdir",
                local,
                mode = DownloadMode.OVERWRITE,
            )
        }
        assertFalse(Files.exists(outside.resolve("pwn.txt")))
    }

    @Test
    fun `upload failure surfaces the tar exit and stderr`(
        @TempDir local: Path,
    ) {
        local.resolve("f.txt").writeText("x")
        // /proc/1/environ-style unwritable destination: use a path whose parent is a file
        val bogus = local.resolve("f.txt/sub")
        val ex = assertFailsWith<IllegalStateException> { sync().upload(local, bogus.toString()) }
        assertTrue(ex.message!!.contains("exit"))
    }

    @Test
    fun `upload surfaces the tar process's own exit code and stderr when it fails`(
        @TempDir local: Path,
        @TempDir remote: Path,
    ) {
        local.resolve("f.txt").writeText("x")
        // Real mkdir/test, but a fake "tar" that drains stdin then fails on its own.
        val failingTarExec =
            KubernetesExecClient { _, _, argv, _ ->
                if (argv.firstOrNull() == "tar") {
                    ProcessBuilder(listOf("sh", "-c", "cat >/dev/null; echo boom >&2; exit 2")).start()
                } else {
                    ProcessBuilder(argv).apply { environment()["COPYFILE_DISABLE"] = "1" }.start()
                }
            }
        val ex =
            assertFailsWith<IllegalStateException> {
                KubernetesWorkspaceSync(failingTarExec, "ns", "pod-1").upload(local, "$remote/ws")
            }
        assertTrue(ex.message!!.contains("2"))
        assertTrue(ex.message!!.contains("boom"))
    }

    @Test
    fun `upload names the exec-channel sentinel instead of reporting it as an exit code`(
        @TempDir local: Path,
        @TempDir remote: Path,
    ) {
        local.resolve("f.txt").writeText("x")
        val sentinelTarProc =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns ByteArrayInputStream(ByteArray(0))
                every { errorStream } returns ByteArrayInputStream(ByteArray(0))
                every { outputStream } returns ByteArrayOutputStream()
                every { waitFor(any<Long>(), any<TimeUnit>()) } returns true
                every { exitValue() } returns EXEC_NO_EXIT_STATUS_SENTINEL
            }
        val client =
            KubernetesExecClient { ns, pod, argv, stdin ->
                if (argv.firstOrNull() == "tar") sentinelTarProc else localExec.start(ns, pod, argv, stdin)
            }
        val ex =
            assertFailsWith<IllegalStateException> {
                KubernetesWorkspaceSync(client, "ns", "pod-1").upload(local, "$remote/ws")
            }
        assertTrue(ex.message!!.contains("no exit status"))
        assertFalse(ex.message!!.contains("-1975219"))
    }

    @Test
    fun `containedEntryPath rejects escaping and absolute entry names`(
        @TempDir base: Path,
    ) {
        assertFailsWith<SecurityException> { TarStreams.containedEntryPath(base, "../evil") }
        assertFailsWith<SecurityException> { TarStreams.containedEntryPath(base, "/etc/passwd") }
        assertFailsWith<SecurityException> { TarStreams.containedEntryPath(base, "a/../../evil") }
        assertEquals(base.toAbsolutePath().normalize().resolve("a/b"), TarStreams.containedEntryPath(base, "./a/b"))
    }
}
