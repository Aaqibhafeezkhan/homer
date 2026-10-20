package homer.app.infra

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PathSafetyTest {
    @Test
    fun `contained path is returned normalized`(
        @TempDir root: Path,
    ) {
        val candidate = root.resolve("subdir/file.txt")
        Files.createDirectories(candidate.parent)
        Files.createFile(candidate)

        val result = PathSafety.assertContained(root, candidate)
        assertEquals(candidate.toRealPath(), result)
    }

    @Test
    fun `path containing dot-dot that escapes root is rejected`(
        @TempDir root: Path,
    ) {
        val escape = root.resolve("../../etc/passwd")

        assertFailsWith<SecurityException> {
            PathSafety.assertContained(root, escape)
        }
    }

    @Test
    fun `path containing dot-dot that stays inside root is allowed`(
        @TempDir root: Path,
    ) {
        val sub = root.resolve("a")
        Files.createDirectories(sub)
        val candidate = sub.resolve("../a/file.txt") // normalizes back to root/a/file.txt
        Files.createFile(sub.resolve("file.txt"))

        val resolved = PathSafety.assertContained(root, candidate)
        assertEquals(sub.resolve("file.txt").toRealPath(), resolved)
    }

    @Test
    fun `symlink that points outside root is rejected`(
        @TempDir root: Path,
        @TempDir outside: Path,
    ) {
        val outsideFile = outside.resolve("secret.txt")
        Files.createFile(outsideFile)

        val link = root.resolve("escape")
        Files.createSymbolicLink(link, outsideFile)

        assertFailsWith<SecurityException> {
            PathSafety.assertContained(root, link)
        }
    }

    @Test
    fun `deleteContainedRecursively refuses to delete the root itself`(
        @TempDir root: Path,
    ) {
        Files.createFile(root.resolve("keep.txt"))

        assertFailsWith<SecurityException> {
            PathSafety.deleteContainedRecursively(root, root)
        }
        assertTrue(Files.exists(root.resolve("keep.txt")), "root contents must be untouched")
    }

    @Test
    fun `non-existent candidate inside root is allowed via normalized comparison`(
        @TempDir root: Path,
    ) {
        // Streaming code may resolve a path before the file exists yet.
        // We still want the containment check to succeed.
        val candidate = root.resolve("not-yet/file.txt")
        val resolved = PathSafety.assertContained(root, candidate)
        // For non-existent candidates we fall back to lexical containment,
        // so the helper returns the normalized absolute path.
        assertEquals(candidate.toAbsolutePath().normalize(), resolved)
    }

    @Test
    fun `assertNoSymlinkComponentsBelow rejects a symlinked intermediate component`(
        @TempDir root: Path,
    ) {
        Files.createDirectories(root.resolve("real/shared"))
        // plant root/alias -> root/real ; candidate root/alias/shared traverses the symlink
        val alias = Files.createSymbolicLink(root.resolve("alias"), root.resolve("real"))
        val candidate = alias.resolve("shared")
        assertFailsWith<SecurityException> { PathSafety.assertNoSymlinkComponentsBelow(root, candidate) }
    }

    @Test
    fun `assertNoSymlinkComponentsBelow rejects a symlinked leaf component`(
        @TempDir root: Path,
    ) {
        val target = Files.createDirectories(root.resolve("target"))
        val leaf = Files.createSymbolicLink(root.resolve("run1"), target)
        assertFailsWith<SecurityException> { PathSafety.assertNoSymlinkComponentsBelow(root, leaf) }
    }

    @Test
    fun `assertNoSymlinkComponentsBelow passes for a plain nested real directory`(
        @TempDir root: Path,
    ) {
        val candidate = Files.createDirectories(root.resolve("a/b/run1"))
        PathSafety.assertNoSymlinkComponentsBelow(root, candidate) // does not throw
    }

    @Test
    fun `assertNoSymlinkComponentsBelow passes when the leaf does not exist yet`(
        @TempDir root: Path,
    ) {
        val candidate = root.resolve("run-not-created-yet")
        PathSafety.assertNoSymlinkComponentsBelow(root, candidate) // first run of an id: leaf absent, no symlink
    }

    @Test
    fun `assertNoSymlinkComponentsBelow ignores a symlinked root ancestry`(
        @TempDir base: Path,
    ) {
        // The root itself reached via a symlinked parent (mirrors /tmp -> /private/tmp):
        // components BELOW root are real, so it must not throw.
        val realParent = Files.createDirectories(base.resolve("realparent"))
        Files.createDirectories(realParent.resolve("wsroot/run1"))
        val linkParent = Files.createSymbolicLink(base.resolve("plink"), realParent)
        val rootViaLink = linkParent.resolve("wsroot")
        val candidate = rootViaLink.resolve("run1")
        PathSafety.assertNoSymlinkComponentsBelow(rootViaLink, candidate) // real component below root → no throw
    }

    @Test
    fun `assertNoSymlinkComponentsBelow is a no-op when candidate equals root`(
        @TempDir root: Path,
    ) {
        PathSafety.assertNoSymlinkComponentsBelow(root, root) // does not throw
    }

    @Test
    fun `a dangling symlink cycle is refused instead of followed forever`(
        @TempDir root: Path,
    ) {
        Files.createSymbolicLink(root.resolve("a"), Path.of("b"))
        Files.createSymbolicLink(root.resolve("b"), Path.of("a"))

        assertFailsWith<SecurityException> { PathSafety.assertDanglingLinkContained(root, root.resolve("a")) }
    }

    @Test
    fun `isLinkedOrSpecial refuses symlinks, dangling links, hard links and directories, not a plain file`(
        @TempDir root: Path,
    ) {
        val plain = Files.writeString(root.resolve("plain"), "x")
        assertFalse(PathSafety.isLinkedOrSpecial(plain))
        assertFalse(PathSafety.isLinkedOrSpecial(root.resolve("missing")))
        assertTrue(PathSafety.isLinkedOrSpecial(Files.createSymbolicLink(root.resolve("link"), plain)))
        assertTrue(PathSafety.isLinkedOrSpecial(Files.createSymbolicLink(root.resolve("dangling"), root.resolve("nowhere"))))
        assertTrue(PathSafety.isLinkedOrSpecial(Files.createLink(root.resolve("hard"), plain)))
        assertTrue(PathSafety.isLinkedOrSpecial(Files.createDirectory(root.resolve("dir"))))
    }

    @Test
    fun `isHardLinked reads the link count of the name itself, not of a symlink's target (CWE-59)`(
        @TempDir root: Path,
    ) {
        val plain = Files.writeString(root.resolve("plain"), "x")
        assertFalse(PathSafety.isHardLinked(plain))
        val hard = Files.createLink(root.resolve("hard"), plain)
        assertTrue(PathSafety.isHardLinked(plain))
        assertTrue(PathSafety.isHardLinked(hard))
        // The link's own nlink is 1 even though its target is hard-linked: callers check the resolved target.
        assertFalse(PathSafety.isHardLinked(Files.createSymbolicLink(root.resolve("link"), plain)))
        assertFalse(PathSafety.isHardLinked(root.resolve("missing")))
    }
}
