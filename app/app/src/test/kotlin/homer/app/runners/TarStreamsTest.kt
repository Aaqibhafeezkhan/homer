package homer.app.runners

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import homer.app.exceptions.RunnerTransferTooLargeException
import homer.app.fixtures.TestFixtures
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ch.qos.logback.classic.Logger as LogbackLogger

class TarStreamsTest {
    @TempDir
    lateinit var tmp: Path

    private fun entryNames(bytes: ByteArray): List<String> =
        org.apache.commons.compress.archivers.tar
            .TarArchiveInputStream(
                org.apache.commons.compress.compressors.gzip
                    .GzipCompressorInputStream(ByteArrayInputStream(bytes)),
            ).use { tar -> generateSequence { tar.nextEntry }.map { it.name }.toList() }

    private fun entryModes(bytes: ByteArray): Map<String, Int> =
        org.apache.commons.compress.archivers.tar
            .TarArchiveInputStream(
                org.apache.commons.compress.compressors.gzip
                    .GzipCompressorInputStream(ByteArrayInputStream(bytes)),
            ).use { tar -> generateSequence { tar.nextEntry }.associate { it.name to (it.mode and "777".toInt(8)) } }

    private fun packed(
        local: Path,
        excludes: List<String> = emptyList(),
        omit: Set<String> = emptySet(),
    ): ByteArray = ByteArrayOutputStream().also { TarStreams.pack(it, local, excludes, omit) }.toByteArray()

    /** [packed], plus the WARN lines TarStreams emitted — the skip is a denial, so assert it. */
    private fun packedWithWarnings(local: Path): Pair<List<String>, List<String>> {
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val logback = LoggerFactory.getLogger(TarStreams::class.java) as LogbackLogger
        logback.addAppender(appender)
        try {
            return entryNames(packed(local)) to appender.list.map { it.formattedMessage }
        } finally {
            logback.detachAppender(appender)
        }
    }

    @Test
    fun `directory round-trips with excludes and nested dirs`() {
        val src = tmp.resolve("src").createDirectories()
        src.resolve("a.txt").writeText("A")
        src
            .resolve("nested")
            .createDirectories()
            .resolve("b.txt")
            .writeText("B")
        src
            .resolve("build")
            .createDirectories()
            .resolve("junk")
            .writeText("x")
        val dst = tmp.resolve("dst").createDirectories()

        val kept =
            TarStreams.extract(
                ByteArrayInputStream(packed(src, excludes = listOf("build"))),
                dst,
                DownloadMode.OVERWRITE,
                remoteIsDir = true,
                fileName = ".",
                sourceLabel = "test",
                maxDecompressedBytes = TarStreams.DEFAULT_MAX_EXTRACTED_BYTES,
            )

        assertNull(kept, "OVERWRITE records nothing")
        assertEquals("A", dst.resolve("a.txt").readText())
        assertEquals("B", dst.resolve("nested/b.txt").readText())
        assertFalse(dst.resolve("build").exists())
    }

    @Test
    fun `MIRROR returns the materialized set and prune removes local extras`() {
        val src = tmp.resolve("src").createDirectories()
        src.resolve("keep.txt").writeText("K")
        val dst = tmp.resolve("dst").createDirectories()
        dst.resolve("extra.txt").writeText("gone")
        dst
            .resolve("extra-dir")
            .createDirectories()
            .resolve("deep")
            .writeText("gone")

        val kept =
            TarStreams.extract(
                ByteArrayInputStream(packed(src)),
                dst,
                DownloadMode.MIRROR,
                remoteIsDir = true,
                fileName = ".",
                sourceLabel = "test",
                maxDecompressedBytes = TarStreams.DEFAULT_MAX_EXTRACTED_BYTES,
            )!!
        assertTrue(Path.of("keep.txt") in kept)
        assertTrue(dst.resolve("extra.txt").exists(), "extract never prunes on its own")

        TarStreams.prune(dst, kept)
        assertTrue(dst.resolve("keep.txt").exists())
        assertFalse(dst.resolve("extra.txt").exists())
        assertFalse(dst.resolve("extra-dir").exists())
    }

    @Test
    fun `OVERLAY keeps an existing local file`() {
        val src = tmp.resolve("src").createDirectories()
        src.resolve("a.txt").writeText("remote")
        val dst = tmp.resolve("dst").createDirectories()
        dst.resolve("a.txt").writeText("local")

        TarStreams.extract(
            ByteArrayInputStream(packed(src)),
            dst,
            DownloadMode.OVERLAY,
            remoteIsDir = true,
            fileName = ".",
            sourceLabel = "test",
            maxDecompressedBytes = TarStreams.DEFAULT_MAX_EXTRACTED_BYTES,
        )

        assertEquals("local", dst.resolve("a.txt").readText())
    }

    @Test
    fun `single file packs under the given entry name and extracts to the target path`() {
        val file = tmp.resolve("one.txt").also { it.writeText("1") }
        val bytes = ByteArrayOutputStream().also { TarStreams.pack(it, file, singleFileEntryName = "renamed.txt") }.toByteArray()
        val target = tmp.resolve("out/renamed.txt")
        Files.createDirectories(target.parent)

        TarStreams.extract(
            ByteArrayInputStream(bytes),
            target,
            DownloadMode.OVERWRITE,
            remoteIsDir = false,
            fileName = "renamed.txt",
            sourceLabel = "test",
            maxDecompressedBytes = TarStreams.DEFAULT_MAX_EXTRACTED_BYTES,
        )

        assertEquals("1", target.readText())
    }

    @Test
    fun `an escaping entry is rejected and the target stays untouched`() {
        val dst = tmp.resolve("dst").createDirectories()
        val evil =
            ByteArrayOutputStream()
                .also { bos ->
                    org.apache.commons.compress.archivers.tar
                        .TarArchiveOutputStream(
                            org.apache.commons.compress.compressors.gzip
                                .GzipCompressorOutputStream(bos),
                        ).use { tar ->
                            val e =
                                org.apache.commons.compress.archivers.tar
                                    .TarArchiveEntry("../escape.txt")
                            e.size = 1
                            tar.putArchiveEntry(e)
                            tar.write("x".toByteArray())
                            tar.closeArchiveEntry()
                        }
                }.toByteArray()

        assertThrows<SecurityException> {
            TarStreams.extract(
                ByteArrayInputStream(evil),
                dst,
                DownloadMode.OVERWRITE,
                remoteIsDir = true,
                fileName = ".",
                sourceLabel = "test",
                maxDecompressedBytes = TarStreams.DEFAULT_MAX_EXTRACTED_BYTES,
            )
        }
        assertFalse(tmp.resolve("escape.txt").exists())
        assertEquals(0, Files.list(dst).use { it.count() })
    }

    @Test
    fun `a gzip bomb is refused at the decompressed bound and writes no more than it`() {
        // ~2 MiB of zeros compress to a couple of KiB: the compressed-body bound the
        // transport enforces would wave this through, so the bound has to sit on the
        // decompressed side.
        val src = tmp.resolve("bomb").createDirectories()
        src.resolve("blob").writeText("0".repeat(2 * 1024 * 1024))
        val bytes = packed(src)
        assertTrue(bytes.size < 64 * 1024, "the fixture must be small compressed, got ${bytes.size}")
        val dst = tmp.resolve("bomb-dst").createDirectories()
        val bound = 64L * 1024

        assertThrows<RunnerTransferTooLargeException> {
            TarStreams.extract(
                ByteArrayInputStream(bytes),
                dst,
                DownloadMode.OVERWRITE,
                remoteIsDir = true,
                fileName = ".",
                sourceLabel = "test",
                maxDecompressedBytes = bound,
            )
        }

        val written = Files.walk(dst).use { walk -> walk.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }
        assertTrue(written <= bound, "extraction stopped at the bound, wrote $written bytes")
    }

    @Test
    fun `a symlink cycle inside the upload root terminates and a contained symlink is still packed`() {
        val src = tmp.resolve("cyclic").createDirectories()
        src.resolve("real.txt").writeText("R")
        Files.createSymbolicLink(src.resolve("loop"), src)
        Files.createSymbolicLink(src.resolve("link.txt"), src.resolve("real.txt"))

        val names = entryNames(packed(src))

        // One loop only: the OS symlink-resolution limit caps a single chain, but two
        // sibling loops multiply into that limit and are what makes this unbounded.
        assertTrue(names.size <= 4, "the cycle must be packed once, not walked, got $names")
        assertTrue("link.txt" in names, "a contained symlink to a regular file is still packed, got $names")
        assertTrue("real.txt" in names)
    }

    @Test
    fun `a symlink cycling back to a non-root ancestor terminates and packs it once`() {
        // "self" resolves back to "a" itself — a non-root ancestor cycle, unlike the root-cycle
        // case above (parity with WorkspaceSyncTest's equivalent case).
        val src = tmp.resolve("non-root-cycle").createDirectories()
        src
            .resolve("a")
            .createDirectories()
            .resolve("f.txt")
            .writeText("a-content")
        Files.createSymbolicLink(src.resolve("a/self"), Path.of("."))

        val names = entryNames(packed(src))

        assertEquals(1, names.count { it == "a/f.txt" }, "a's files must be packed exactly once, got $names")
        assertFalse("a/self/f.txt" in names, "the cycle must be skipped, not descended into, got $names")
    }

    @Test
    fun `packing stops descending at the nesting cap`() {
        // 64 levels are packed, the 65th directory is not (parity with WorkspaceSyncTest).
        val levels = (1..70).map { "d$it" }
        val src = tmp.resolve("deep").createDirectories()
        val deepest = levels.fold(src) { dir, name -> dir.resolve(name) }.createDirectories()
        deepest.resolve("never.txt").writeText("too deep")
        val last = levels.take(64).fold(src) { dir, name -> dir.resolve(name) }
        last.resolve("kept.txt").writeText("at the cap")

        val (names, warnings) = packedWithWarnings(src)

        val keptPath = levels.take(64).joinToString("/") + "/kept.txt"
        assertTrue(keptPath in names, "the file at the cap must be packed, got $names")
        assertFalse(names.any { it.endsWith("never.txt") }, "the file past the cap must not be packed, got $names")
        assertFalse(names.any { it.contains("d66") }, "nothing past the skipped directory is packed, got $names")
        assertTrue(
            warnings.any { "nesting exceeds 64 levels" in it },
            "the skip must be logged, got $warnings",
        )
    }

    @Test
    fun `a directory and a symlink alias to it beside it pack the real name, the same way on every run`() {
        val src = tmp.resolve("alias").createDirectories()
        src
            .resolve("lib")
            .createDirectories()
            .resolve("f.txt")
            .writeText("L")
        Files.createSymbolicLink(src.resolve("lib64"), Path.of("lib"))

        val runs = List(3) { packedWithWarnings(src) }

        runs.forEach { (names, warnings) ->
            assertTrue("lib/f.txt" in names, "the real directory is always packed, got $names")
            assertFalse("lib64/f.txt" in names, "the alias is skipped, got $names")
            assertTrue(warnings.any { "already packed as another path" in it }, "the skip must be logged, got $warnings")
        }
        assertEquals(1, runs.map { it.first }.distinct().size, "the walk order must be deterministic, got ${runs.map { it.first }}")
    }

    @Test
    fun `a real directory reached after a symlink alias to it from another subtree is still packed`() {
        // node_modules lists (and sorts) before packages, so the alias is walked first.
        val src = tmp.resolve("workspace").createDirectories()
        src
            .resolve("packages/pkg")
            .createDirectories()
            .resolve("index.js")
            .writeText("module.exports = 1")
        Files.createSymbolicLink(src.resolve("node_modules").createDirectories().resolve("pkg"), Path.of("../packages/pkg"))

        val names = entryNames(packed(src))

        assertTrue("packages/pkg/index.js" in names, "the real directory must never be skipped, got $names")
        assertTrue("node_modules/pkg/index.js" in names, "the link is still packed under its own name, got $names")
    }

    @Test
    fun `links to nested ancestors walk each directory at most once through links`() {
        // "L" sorts before "a", and L1 before L2: the alias walks run before a's own-name walk.
        val src = tmp.resolve("nested-aliases").createDirectories()
        src
            .resolve("a/b")
            .createDirectories()
            .resolve("f.txt")
            .writeText("F")
        val links = src.resolve("L").createDirectories()
        Files.createSymbolicLink(links.resolve("L1"), Path.of("../a/b"))
        Files.createSymbolicLink(links.resolve("L2"), Path.of("../a"))

        val names = entryNames(packed(src))

        // Once through L1, once by name; L2's walk reaches a/b already visited and skips it.
        assertEquals(listOf("L/L1/f.txt", "a/b/f.txt"), names.filter { it.endsWith("/f.txt") }.sorted(), "got $names")
    }

    @Test
    fun `a symlinked directory repointed during the walk never leaks its new target (CWE-59)`() {
        val src = tmp.resolve("racy").createDirectories()
        src
            .resolve("inside")
            .createDirectories()
            .resolve("ok.txt")
            .writeText("ok")
        val outside = tmp.resolve("outside").createDirectories().also { it.resolve("secret.txt").writeText("SECRET") }
        val link = src.resolve("link")
        Files.createSymbolicLink(link, src.resolve("inside"))
        val stop = AtomicBoolean(false)
        // Repoints the link atomically, so every check sees a link, and half of them a contained one.
        val flipper =
            thread {
                val staged = tmp.resolve("link.staged")
                var toOutside = true
                while (!stop.get()) {
                    Files.deleteIfExists(staged)
                    Files.createSymbolicLink(staged, if (toOutside) outside else src.resolve("inside"))
                    Files.move(staged, link, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    toOutside = !toOutside
                }
            }
        try {
            val deadline = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < deadline) {
                val names = entryNames(packed(src))
                assertTrue(names.none { it.endsWith("secret.txt") }, "the walk followed a repointed link out of the root: $names")
            }
        } finally {
            stop.set(true)
            flipper.join()
        }
    }

    @Test
    fun `an ancestor directory symlinked out of the upload root is skipped with a warning (CWE-59, CWE-367)`() {
        // Covers the walk's static check only, not the accepted swap landing between
        // addDirContents' containment check and addFile's open — see addFile's KDoc
        // and ADR-0047's 2026-09-23 amendment.
        val outside = tmp.resolve("outside-anc").createDirectories().also { it.resolve("secret.txt").writeText("SECRET") }
        val src = tmp.resolve("src-anc").createDirectories()
        // A link named as the walk's ancestor: every entry below it resolves outside.
        Files.createSymbolicLink(src.resolve("anc"), outside)

        val (names, warnings) = packedWithWarnings(src)

        assertTrue(names.none { it.endsWith("secret.txt") }, "a symlinked ancestor must not leak its target, got $names")
        assertFalse(names.any { it.startsWith("anc/") }, "the escaping subtree must be skipped, got $names")
        assertTrue(
            warnings.any { "escaping the upload root" in it },
            "the skip must be logged, got $warnings",
        )
    }

    @Test
    fun `a real ancestor directory's contents are packed (the residual's pre-swap baseline)`() {
        // The accepted residual assumes a real ancestor whose contents ARE packed
        // after the containment check — pin that baseline so a regression in the
        // directory branch shows up as a failure here (ADR-0047).
        val src = tmp.resolve("src-real").createDirectories()
        src
            .resolve("anc")
            .createDirectories()
            .resolve("kept.txt")
            .writeText("K")

        val names = entryNames(packed(src))

        assertTrue("anc/" in names, "a real ancestor directory is packed, got $names")
        assertTrue("anc/kept.txt" in names, "its contents are packed, got $names")
    }

    private fun extractDir(
        tar: ByteArray,
        dst: Path,
        mode: DownloadMode = DownloadMode.OVERWRITE,
        posixViewLookup: ((Path, Boolean) -> PosixFileAttributeView?)? = null,
    ) = TarStreams.extract(
        ByteArrayInputStream(tar),
        dst,
        mode,
        remoteIsDir = true,
        fileName = ".",
        sourceLabel = "test",
        maxDecompressedBytes = TarStreams.DEFAULT_MAX_EXTRACTED_BYTES,
        posixViewLookup = posixViewLookup,
    )

    /** A one-entry gzip'd tar whose entry carries [mode], for the extract-side mode tests. */
    private fun tarWithMode(
        name: String,
        mode: Int,
        content: String = "x",
    ): ByteArray =
        ByteArrayOutputStream()
            .also { bos ->
                org.apache.commons.compress.archivers.tar
                    .TarArchiveOutputStream(
                        org.apache.commons.compress.compressors.gzip
                            .GzipCompressorOutputStream(bos),
                    ).use { tar ->
                        val e =
                            org.apache.commons.compress.archivers.tar
                                .TarArchiveEntry(name)
                        e.mode = mode
                        e.size = content.length.toLong()
                        tar.putArchiveEntry(e)
                        tar.write(content.toByteArray())
                        tar.closeArchiveEntry()
                    }
            }.toByteArray()

    /**
     * A multi-entry gzip'd tar — one archive, so [extract] sees every entry (two concatenated
     * gzip members would read as a single member, the default `decompressConcatenated = false`).
     */
    private fun tarWithModes(vararg entries: Pair<String, Int>): ByteArray =
        ByteArrayOutputStream()
            .also { bos ->
                org.apache.commons.compress.archivers.tar
                    .TarArchiveOutputStream(
                        org.apache.commons.compress.compressors.gzip
                            .GzipCompressorOutputStream(bos),
                    ).use { tar ->
                        entries.forEach { (name, mode) ->
                            val e =
                                org.apache.commons.compress.archivers.tar
                                    .TarArchiveEntry(name)
                            e.mode = mode
                            e.size = "x".length.toLong()
                            tar.putArchiveEntry(e)
                            tar.write("x".toByteArray())
                            tar.closeArchiveEntry()
                        }
                    }
            }.toByteArray()

    private fun permissions(path: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    @Test
    fun `a dangling symlink at a directory-branch target that leads outside is refused (CWE-59)`() {
        val outside = tmp.resolve("outside").createDirectories()
        val src = tmp.resolve("src").createDirectories().also { it.resolve("evil").writeText("pwned") }
        val dst = tmp.resolve("dst").createDirectories()
        Files.createSymbolicLink(dst.resolve("evil"), outside.resolve("created.txt"))

        assertThrows<SecurityException> { extractDir(packed(src), dst) }
        assertFalse(outside.resolve("created.txt").exists(), "the write must not create the link's outside target")
    }

    @Test
    fun `a chain of dangling symlinks that ends outside is refused too`() {
        val outside = tmp.resolve("outside2").createDirectories()
        val src = tmp.resolve("src2").createDirectories().also { it.resolve("hop").writeText("pwned") }
        val dst = tmp.resolve("dst2").createDirectories()
        Files.createSymbolicLink(dst.resolve("next"), outside.resolve("created.txt"))
        Files.createSymbolicLink(dst.resolve("hop"), Path.of("next"))

        assertThrows<SecurityException> { extractDir(packed(src), dst) }
        assertFalse(outside.resolve("created.txt").exists())
    }

    @Test
    fun `a dangling link whose dot-dot the kernel resolves outside is refused although it looks contained`() {
        val outside = tmp.resolve("outside4/a/b").createDirectories()
        val src =
            tmp.resolve("src4").createDirectories().also {
                it
                    .resolve("bin")
                    .createDirectories()
                    .resolve("tool")
                    .writeText("pwned")
            }
        val dst = tmp.resolve("dst4").createDirectories()
        dst.resolve("bin").createDirectories()
        // esc is a live link out; lexically bin/../esc/../x is dst/x, but the kernel follows esc first.
        Files.createSymbolicLink(dst.resolve("esc"), outside)
        Files.createSymbolicLink(dst.resolve("bin/tool"), Path.of("../esc/../x"))

        assertThrows<SecurityException> { extractDir(packed(src), dst) }
        assertFalse(tmp.resolve("outside4/a/x").exists(), "the write must not land where the kernel resolves the link")
    }

    @Test
    fun `an absolute dangling link that names the root through another alias is still written through`() {
        val real = tmp.resolve("realdst").createDirectories()
        real.resolve("bin").createDirectories()
        real.resolve("pkg").createDirectories()
        val alias = tmp.resolve("aliasdst")
        Files.createSymbolicLink(alias, real)
        // The link spells the destination by the real path while the extract is given the alias.
        Files.createSymbolicLink(real.resolve("bin/tool"), real.toRealPath().resolve("pkg/tool"))
        val src =
            tmp
                .resolve(
                    "src5",
                ).createDirectories()
                .also {
                    it
                        .resolve("bin")
                        .createDirectories()
                        .resolve("tool")
                        .writeText("#!/bin/sh")
                }

        extractDir(packed(src), alias)

        assertEquals("#!/bin/sh", real.resolve("pkg/tool").readText())
    }

    @Test
    fun `under an aliased root a dangling link to a sibling of the real root is refused`() {
        val real = tmp.resolve("realdst6").createDirectories()
        real.resolve("bin").createDirectories()
        val sibling = tmp.resolve("sibling6").createDirectories()
        val alias = tmp.resolve("aliasdst6")
        Files.createSymbolicLink(alias, real)
        Files.createSymbolicLink(real.resolve("bin/tool"), sibling.toRealPath().resolve("x"))
        val src =
            tmp.resolve("src6").createDirectories().also {
                it
                    .resolve("bin")
                    .createDirectories()
                    .resolve("tool")
                    .writeText("pwned")
            }

        assertThrows<SecurityException> { extractDir(packed(src), alias) }
        assertFalse(sibling.resolve("x").exists())
    }

    @Test
    fun `a dangling dot-dot link at a single-file target is refused before its target is followed`() {
        val outside = tmp.resolve("outside7/a/b").createDirectories()
        val dst = tmp.resolve("dst7").createDirectories()
        Files.createSymbolicLink(dst.resolve("esc"), outside)
        Files.createSymbolicLink(dst.resolve("out.txt"), Path.of("esc/../x"))
        val src = tmp.resolve("src7.txt").also { it.writeText("pwned") }
        val tar = ByteArrayOutputStream().also { TarStreams.pack(it, src, singleFileEntryName = "out.txt") }.toByteArray()

        assertThrows<SecurityException> {
            TarStreams.extract(
                ByteArrayInputStream(tar),
                dst.resolve("out.txt"),
                DownloadMode.OVERWRITE,
                remoteIsDir = false,
                fileName = "out.txt",
                sourceLabel = "test",
                maxDecompressedBytes = TarStreams.DEFAULT_MAX_EXTRACTED_BYTES,
            )
        }
        assertFalse(tmp.resolve("outside7/a/x").exists())
    }

    @Test
    fun `a dangling symlink that stays inside the target is still written through`() {
        val src = tmp.resolve("src3").createDirectories()
        src
            .resolve("bin")
            .createDirectories()
            .resolve("tool")
            .writeText("#!/bin/sh")
        val dst = tmp.resolve("dst3").createDirectories()
        dst.resolve("bin").createDirectories()
        dst.resolve("pkg").createDirectories()
        // The node_modules/.bin shape: a relative link whose target does not exist yet.
        Files.createSymbolicLink(dst.resolve("bin/tool"), Path.of("../pkg/tool"))

        extractDir(packed(src), dst)

        assertEquals("#!/bin/sh", dst.resolve("pkg/tool").readText())
    }

    @Test
    fun `a symlink at the single-file destination is refused and its target is untouched`() {
        val outside = tmp.resolve("outside.txt").also { it.writeText("original") }
        val dst = tmp.resolve("dst").createDirectories()
        val target = Files.createSymbolicLink(dst.resolve("one.txt"), outside)
        val file = tmp.resolve("src-one.txt").also { it.writeText("attacker") }
        val bytes = ByteArrayOutputStream().also { TarStreams.pack(it, file, singleFileEntryName = "one.txt") }.toByteArray()

        assertThrows<SecurityException> {
            TarStreams.extract(
                ByteArrayInputStream(bytes),
                target,
                DownloadMode.OVERWRITE,
                remoteIsDir = false,
                fileName = "one.txt",
                sourceLabel = "test",
                maxDecompressedBytes = TarStreams.DEFAULT_MAX_EXTRACTED_BYTES,
            )
        }

        assertEquals("original", outside.readText())
    }

    @Test
    fun `the directory branch still writes through a contained symlink at a destination path`() {
        // The k8s sync-down (ADR-0055) extracts into a live workspace where contained
        // links are normal; assertContained is what blocks escapes there, so a dangling
        // one (its target arrives later in the same tar) must not fail the whole sync.
        val src = tmp.resolve("src").createDirectories()
        src.resolve("a.txt").writeText("remote")
        val dst = tmp.resolve("dst").createDirectories()
        dst.resolve("real").createDirectories()
        Files.createSymbolicLink(dst.resolve("a.txt"), dst.resolve("real/later.txt"))

        TarStreams.extract(
            ByteArrayInputStream(packed(src)),
            dst,
            DownloadMode.OVERWRITE,
            remoteIsDir = true,
            fileName = ".",
            sourceLabel = "test",
            maxDecompressedBytes = TarStreams.DEFAULT_MAX_EXTRACTED_BYTES,
        )

        assertEquals("remote", dst.resolve("real/later.txt").readText())
    }

    @Test
    fun `a hardlink inside the upload root is not packed`() {
        // A hardlink to a host file is a second name for that file's inode: the symlink
        // guard cannot see it, and packing it would stream the host file into the pod.
        val outside = tmp.resolve("host-secret.txt").also { it.writeText("SECRET") }
        val src = tmp.resolve("src").createDirectories()
        src.resolve("ok.txt").writeText("O")
        Files.createLink(src.resolve("stolen.txt"), outside)

        val bytes = packed(src)
        val names = entryNames(bytes)

        assertFalse("stolen.txt" in names, "a hard-linked entry must not be packed, got $names")
        assertTrue("ok.txt" in names)
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("SECRET"), "the host file's bytes must not ride along")
    }

    @Test
    fun `packed files carry their own permission bits, a contained symlink its target's`() {
        val src = tmp.resolve("modes").createDirectories()
        val run = src.resolve("run.sh").also { it.writeText("#!/bin/sh") }
        Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwxr-x--x"))
        val data = src.resolve("data.txt").also { it.writeText("d") }
        Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("rw-r-----"))
        Files.createSymbolicLink(src.resolve("run-link"), run)

        val modes = entryModes(packed(src))

        assertEquals("751".toInt(8), modes["run.sh"])
        assertEquals("640".toInt(8), modes["data.txt"])
        assertEquals("751".toInt(8), modes["run-link"], "a contained symlink is packed as its resolved target")
        val single = ByteArrayOutputStream().also { TarStreams.pack(it, data, singleFileEntryName = "renamed.txt") }.toByteArray()
        assertEquals(mapOf("renamed.txt" to "640".toInt(8)), entryModes(single))
    }

    @Test
    fun `a pack round trip keeps a 0755 file executable and a 0644 file non-executable`() {
        val src = tmp.resolve("modes-src").createDirectories()
        val run = src.resolve("run.sh").also { it.writeText("#!/bin/sh") }
        Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwxr-xr-x"))
        val data = src.resolve("data.txt").also { it.writeText("d") }
        Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("rw-r--r--"))
        val dst = tmp.resolve("modes-dst").createDirectories()

        extractDir(packed(src), dst)

        assertEquals("rwxr-xr-x", permissions(dst.resolve("run.sh")), "the exec bit must survive the round trip")
        assertEquals("rw-r--r--", permissions(dst.resolve("data.txt")))
    }

    @Test
    fun `an entry carrying setuid, setgid or sticky bits extracts with those stripped`() {
        val dst = tmp.resolve("special-dst").createDirectories()

        extractDir(tarWithMode("setuid.sh", "4755".toInt(8)), dst)
        extractDir(tarWithMode("setgid.sh", "2755".toInt(8)), dst)
        extractDir(tarWithMode("sticky.sh", "1755".toInt(8)), dst)
        extractDir(tarWithMode("special-no-exec.sh", "4644".toInt(8)), dst)

        assertEquals("rwxr-xr-x", permissions(dst.resolve("setuid.sh")), "a remote tar must not plant a setuid file")
        assertEquals("rwxr-xr-x", permissions(dst.resolve("setgid.sh")), "a remote tar must not plant a setgid file")
        assertEquals("rwxr-xr-x", permissions(dst.resolve("sticky.sh")), "a remote tar must not plant a sticky file")
        assertEquals("rw-r--r--", permissions(dst.resolve("special-no-exec.sh")))
    }

    @Test
    fun `a hostile mode extracts as a safe 0755 or 0644`() {
        val dst = tmp.resolve("hostile-dst").createDirectories()

        extractDir(tarWithMode("world-writable", "777".toInt(8)), dst)
        extractDir(tarWithMode("world-writable-no-exec", "666".toInt(8)), dst)
        extractDir(tarWithMode("no-access", "000".toInt(8)), dst)
        extractDir(tarWithMode("read-only", "444".toInt(8)), dst)

        assertEquals("rwxr-xr-x", permissions(dst.resolve("world-writable")))
        assertEquals("rw-r--r--", permissions(dst.resolve("world-writable-no-exec")))
        assertEquals("rw-r--r--", permissions(dst.resolve("no-access")), "Homer must be able to read back what it extracted")
        assertEquals("rw-r--r--", permissions(dst.resolve("read-only")), "Homer must be able to overwrite what it extracted")
    }

    @Test
    fun `an OVERWRITE onto a file left by an earlier entry still succeeds`() {
        val dst = tmp.resolve("overwrite-dst").createDirectories()

        extractDir(tarWithMode("a.txt", "444".toInt(8), content = "first"), dst)
        extractDir(tarWithMode("a.txt", "644".toInt(8), content = "second"), dst)

        assertEquals("second", dst.resolve("a.txt").readText())
    }

    @Test
    fun `the single-file download branch preserves the entry's mode`() {
        val run = tmp.resolve("one-run.sh").also { it.writeText("#!/bin/sh") }
        Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwxr-xr-x"))
        val data = tmp.resolve("one-data.txt").also { it.writeText("d") }
        Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("rw-r--r--"))
        val runTarget = tmp.resolve("out/one-run.sh")
        val dataTarget = tmp.resolve("out/one-data.txt")
        Files.createDirectories(runTarget.parent)

        fun download(
            source: Path,
            target: Path,
        ) {
            val bytes = ByteArrayOutputStream().also { TarStreams.pack(it, source) }.toByteArray()
            TarStreams.extract(
                ByteArrayInputStream(bytes),
                target,
                DownloadMode.OVERWRITE,
                remoteIsDir = false,
                fileName = target.fileName.toString(),
                sourceLabel = "test",
                maxDecompressedBytes = TarStreams.DEFAULT_MAX_EXTRACTED_BYTES,
            )
        }

        download(run, runTarget)
        download(data, dataTarget)

        assertEquals("rwxr-xr-x", permissions(runTarget), "the single-file branch must keep the exec bit")
        assertEquals("rw-r--r--", permissions(dataTarget))
    }

    @Test
    fun `the single-file download branch normalizes a hostile entry mode`() {
        val target = tmp.resolve("out/one.sh")
        Files.createDirectories(target.parent)

        TarStreams.extract(
            ByteArrayInputStream(tarWithMode("one.sh", "4777".toInt(8))),
            target,
            DownloadMode.OVERWRITE,
            remoteIsDir = false,
            fileName = "one.sh",
            sourceLabel = "test",
            maxDecompressedBytes = TarStreams.DEFAULT_MAX_EXTRACTED_BYTES,
        )

        assertEquals("rwxr-xr-x", permissions(target), "the single-file branch must normalize a raw attacker mode")
    }

    @Test
    fun `OVERLAY leaves a kept file's own mode untouched`() {
        val src = tmp.resolve("overlay-src").createDirectories()
        val remote = src.resolve("a.txt").also { it.writeText("remote") }
        Files.setPosixFilePermissions(remote, PosixFilePermissions.fromString("rwxr-xr-x"))
        val dst = tmp.resolve("overlay-dst").createDirectories()
        val local = dst.resolve("a.txt").also { it.writeText("local") }
        Files.setPosixFilePermissions(local, PosixFilePermissions.fromString("rw-------"))

        extractDir(packed(src), dst, mode = DownloadMode.OVERLAY)

        assertEquals("local", local.readText())
        assertEquals("rw-------", permissions(local), "a skipped file keeps its own mode")
    }

    @Test
    fun `a directory entry still extracts`() {
        val dst = tmp.resolve("dir-dst").createDirectories()

        extractDir(tarWithMode("nested/", "755".toInt(8), content = ""), dst)

        assertTrue(Files.isDirectory(dst.resolve("nested")), "a directory entry is created as a directory")
    }

    @Test
    fun `pack refuses a hard-linked single-file source instead of streaming its bytes (CWE-200)`() {
        val secret = tmp.resolve("host-secret").also { it.writeText("SECRET") }
        val workdir = tmp.resolve("wd").createDirectories()
        val linked = Files.createLink(workdir.resolve("innocent.txt"), secret)

        assertThrows<SecurityException> { ByteArrayOutputStream().also { TarStreams.pack(it, linked) } }
    }

    @Test
    fun `pack refuses a symlinked source`() {
        val real = tmp.resolve("real").createDirectories()
        real.resolve("secret.txt").writeText("S")
        val dirLink = Files.createSymbolicLink(tmp.resolve("dir-link"), real)
        val fileLink = Files.createSymbolicLink(tmp.resolve("file-link"), real.resolve("secret.txt"))

        assertThrows<SecurityException> { packed(dirLink) }
        assertThrows<SecurityException> { packed(fileLink) }
    }

    /** A POSIX view whose [setPermissions] always fails, for the fail-closed denial path. */
    private class FailingPosixView(
        private val failure: Exception = IOException("chmod refused"),
    ) : PosixFileAttributeView {
        override fun name(): String = "posix"

        override fun readAttributes(): PosixFileAttributes = throw UnsupportedOperationException()

        override fun setPermissions(perms: MutableSet<PosixFilePermission>): Unit = throw failure

        override fun setGroup(group: GroupPrincipal) = throw UnsupportedOperationException()

        override fun getOwner(): UserPrincipal = throw UnsupportedOperationException()

        override fun setOwner(owner: UserPrincipal) = throw UnsupportedOperationException()

        override fun setTimes(
            lastModifiedTime: FileTime?,
            lastAccessTime: FileTime?,
            createTime: FileTime?,
        ) = throw UnsupportedOperationException()
    }

    @Test
    fun `a failed mode normalization refuses the entry and removes the target (CWE-732)`() {
        val dst = tmp.resolve("failclosed-dst").createDirectories()
        // The pre-existing target is permissive; the extract must not leave it in place
        // with that mode when the chmod cannot be applied.
        val target = dst.resolve("evil.sh").also { it.writeText("pre-existing") }
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxrwxrwx"))

        assertThrows<SecurityException> {
            extractDir(tarWithMode("evil.sh", "4755".toInt(8)), dst, posixViewLookup = { _, _ -> FailingPosixView() })
        }

        assertFalse(target.exists(), "the target must be deleted when its mode cannot be normalized")
    }

    /** A POSIX view whose [setPermissions] fails with a non-`IOException`, for the any-failure refusal (CWE-732). */
    @Test
    fun `a non-IOException mode normalization failure still refuses the entry and removes the target (CWE-732)`() {
        val dst = tmp.resolve("failclosed-runtime-dst").createDirectories()
        // The pre-existing target is permissive; the extract must not leave it in place
        // with that mode when the chmod fails with anything other than an IOException.
        val target = dst.resolve("evil.sh").also { it.writeText("pre-existing") }
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxrwxrwx"))

        assertThrows<SecurityException> {
            extractDir(
                tarWithMode("evil.sh", "4755".toInt(8)),
                dst,
                posixViewLookup = { _, _ -> FailingPosixView(IllegalStateException("chmod refused")) },
            )
        }

        assertFalse(target.exists(), "the target must be deleted when its mode cannot be normalized")
    }

    @Test
    fun `a failed mode normalization also removes a dangling link's destination (CWE-59)`() {
        val dst = tmp.resolve("failclosed-link-dst").createDirectories()
        dst.resolve("bin").createDirectories()
        dst.resolve("pkg").createDirectories()
        // The write-through shape from the pin above: the bytes land at dst/pkg/tool, not
        // at the link's own name, so a failed chmod must delete the link's destination too.
        Files.createSymbolicLink(dst.resolve("bin/tool"), Path.of("../pkg/tool"))

        assertThrows<SecurityException> {
            extractDir(tarWithMode("bin/tool", "755".toInt(8)), dst, posixViewLookup = { _, _ -> FailingPosixView() })
        }

        assertFalse(
            Files.isSymbolicLink(dst.resolve("bin/tool")),
            "the link must be deleted when its mode cannot be normalized",
        )
        assertFalse(dst.resolve("pkg/tool").exists(), "the link's destination must be deleted too")
    }

    @Test
    fun `a failed landing delete is reported as content left behind (CWE-59)`() {
        assumeTrue(
            TestFixtures.posixPermissionsEnforced(),
            "POSIX write bits not enforced (root or non-POSIX FS); the denied unlink cannot be exercised",
        )
        val dst = tmp.resolve("failclosed-landing-dst").createDirectories()
        dst.resolve("bin").createDirectories()
        dst.resolve("pkg").createDirectories()
        Files.createSymbolicLink(dst.resolve("bin/tool"), Path.of("../pkg/tool"))
        // Make the landing delete fail while the link delete (a different parent) succeeds:
        // once the (already-failing) chmod is reached, stripping the destination's parent of
        // its write permission denies unlinking pkg/tool, so the denial must say content
        // survived rather than blame the target.
        val real = Files.getFileAttributeView(dst.resolve("bin/tool"), PosixFileAttributeView::class.java)
        val chmodAndLock =
            object : PosixFileAttributeView by real {
                override fun setPermissions(perms: MutableSet<PosixFilePermission>) {
                    Files.setPosixFilePermissions(dst.resolve("pkg"), PosixFilePermissions.fromString("r-xr-xr-x"))
                    throw IOException("chmod refused")
                }
            }

        try {
            val e =
                assertThrows<SecurityException> {
                    extractDir(tarWithMode("bin/tool", "755".toInt(8)), dst, posixViewLookup = { _, _ -> chmodAndLock })
                }

            assertTrue(Files.exists(dst.resolve("pkg/tool")), "the landing delete failed, so the content is still there")
            assertTrue(
                e.message!!.contains("content was left behind at the link's destination"),
                "the denial must name the surviving landing content, not just the target",
            )
        } finally {
            Files.setPosixFilePermissions(dst.resolve("pkg"), PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `a non-POSIX target still extracts bytes-only without failing`() {
        val dst = tmp.resolve("nonposix-dst").createDirectories()

        extractDir(tarWithMode("plain.txt", "777".toInt(8), content = "bytes"), dst, posixViewLookup = { _, _ -> null })

        assertEquals("bytes", dst.resolve("plain.txt").readText(), "a non-POSIX target degrades to a bytes-only write")
    }

    @Test
    fun `MIRROR leaves earlier entries in place when a later entry fails normalization`() {
        // ADR-0055: prune runs only after a fully successful extract, so a refusal
        // mid-extract must bypass it — the pre-existing workspace files survive.
        val dst = tmp.resolve("mirror-failclosed-dst").createDirectories()
        dst.resolve("kept.txt").writeText("keep")
        val tar = tarWithModes("a.txt" to "644".toInt(8), "evil.sh" to "4755".toInt(8))
        // Only the later entry's chmod fails — an earlier entry must survive to prove the
        // ordering the test name claims (a view failing every entry would deny on the first).
        val failingView = FailingPosixView()

        val evil = dst.resolve("evil.sh")
        assertThrows<SecurityException> {
            extractDir(tar, dst, mode = DownloadMode.MIRROR, posixViewLookup = { target, _ ->
                if (target == evil) failingView else null
            })
        }

        assertTrue(dst.resolve("kept.txt").exists(), "a refused entry must not trigger the prune")
        assertFalse(dst.resolve("evil.sh").exists(), "the refused entry's target is removed")
    }

    @Test
    fun `containedEntryPath rejects absolute, dot-dot and NUL names`() {
        val base = tmp.resolve("base")
        assertThrows<SecurityException> { TarStreams.containedEntryPath(base, "/etc/passwd") }
        assertThrows<SecurityException> { TarStreams.containedEntryPath(base, "a/../../b") }
        assertThrows<SecurityException> { TarStreams.containedEntryPath(base, "a\u0000b") }
        assertEquals(base.toAbsolutePath().normalize().resolve("a/b"), TarStreams.containedEntryPath(base, "a/b"))
    }

    @Test
    fun `walkEntries lists real entries before symlinks, each by name, prefixed and minus excludes`() {
        val dir = tmp.resolve("walk").createDirectories()
        dir.resolve("b.txt").writeText("b")
        dir.resolve("a").createDirectories()
        dir.resolve("skip").writeText("s")
        Files.createSymbolicLink(dir.resolve("0-link"), dir.resolve("b.txt"))

        val walked = TarStreams.walkEntries(dir, "pre", listOf("skip")).map { (entry, name) -> entry.fileName.toString() to name }

        assertEquals(listOf("a" to "pre/a", "b.txt" to "pre/b.txt", "0-link" to "pre/0-link"), walked)
        assertEquals(listOf("b.txt", "skip"), TarStreams.walkEntries(dir, "", listOf("/a", "/0-link")).map { it.second })
    }
}
