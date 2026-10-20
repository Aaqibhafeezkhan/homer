package homer.app.infra

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtifactRetentionTest {
    /**
     * Builds `root/proj/{workdir/scratch.txt, .homer/bin/homer-ask,
     * artifacts/{decision.json (10B), summary.md (10B), cmd_0.out (1KB),
     * sub/report.md (10B)}}` — the shape a finished run's workspace has
     * before the retention sweep runs.
     */
    private class Layout(
        val projectRoot: Path,
        val artifactsDir: Path,
        val workdir: Path,
        val homerDir: Path,
        val decisionJson: Path,
        val summaryMd: Path,
        val cmdOut: Path,
        val reportMd: Path,
    )

    private fun buildLayout(root: Path): Layout {
        val projectRoot = root.resolve("proj")
        val workdir = projectRoot.resolve("workdir")
        val homerDir = projectRoot.resolve(".homer/bin")
        val artifactsDir = projectRoot.resolve("artifacts")
        val subDir = artifactsDir.resolve("sub")
        Files.createDirectories(workdir)
        Files.createDirectories(homerDir)
        Files.createDirectories(subDir)
        Files.writeString(workdir.resolve("scratch.txt"), "scratch")
        Files.writeString(homerDir.resolve("homer-ask"), "#!/bin/sh")
        val decisionJson = artifactsDir.resolve("decision.json")
        Files.write(decisionJson, ByteArray(10))
        val summaryMd = artifactsDir.resolve("summary.md")
        Files.write(summaryMd, ByteArray(10))
        val cmdOut = artifactsDir.resolve("cmd_0.out")
        Files.write(cmdOut, ByteArray(1024))
        val reportMd = subDir.resolve("report.md")
        Files.write(reportMd, ByteArray(10))
        return Layout(projectRoot, artifactsDir, workdir, homerDir, decisionJson, summaryMd, cmdOut, reportMd)
    }

    @Test
    fun `keeps matched files in place and deletes everything else`(
        @TempDir root: Path,
    ) {
        val layout = buildLayout(root)

        val result =
            ArtifactRetention.prune(
                workspaceRoot = root,
                artifactsDir = layout.artifactsDir,
                globs = listOf("decision.json", "sub/*.md"),
                maxBytes = Long.MAX_VALUE,
            )

        assertEquals(ArtifactRetention.PruneResult(keptFiles = 2, truncated = false), result)
        assertTrue(Files.exists(layout.decisionJson), "decision.json should remain at its original path")
        assertTrue(Files.exists(layout.reportMd), "sub/report.md should remain at its original path")
        assertFalse(Files.exists(layout.workdir), "workdir should be gone")
        assertFalse(Files.exists(layout.homerDir), ".homer should be gone")
        assertFalse(Files.exists(layout.summaryMd), "summary.md should be gone (unmatched)")
        assertFalse(Files.exists(layout.cmdOut), "cmd_0.out should be gone (unmatched)")
        assertTrue(Files.exists(layout.artifactsDir), "artifacts dir should remain")
        assertTrue(Files.exists(layout.reportMd.parent), "sub/ should remain")
    }

    @Test
    fun `glob order wins the byte budget and overflow sets truncated`(
        @TempDir root: Path,
    ) {
        val layout = buildLayout(root)

        val result =
            ArtifactRetention.prune(
                workspaceRoot = root,
                artifactsDir = layout.artifactsDir,
                globs = listOf("decision.json", "*.md", "cmd_0.out"),
                // Exactly fits decision.json (10B) + summary.md (10B), not cmd_0.out (1KB).
                maxBytes = 20,
            )

        assertEquals(ArtifactRetention.PruneResult(keptFiles = 2, truncated = true), result)
        assertTrue(Files.exists(layout.decisionJson))
        assertTrue(Files.exists(layout.summaryMd))
        assertFalse(Files.exists(layout.cmdOut), "cmd_0.out overflowed the budget and should be deleted")
    }

    @Test
    fun `no match falls back to full workspace delete`(
        @TempDir root: Path,
    ) {
        val layout = buildLayout(root)

        val result =
            ArtifactRetention.prune(
                workspaceRoot = root,
                artifactsDir = layout.artifactsDir,
                globs = listOf("nothing-matches-*"),
                maxBytes = Long.MAX_VALUE,
            )

        assertEquals(ArtifactRetention.PruneResult(keptFiles = 0, truncated = false), result)
        assertFalse(Files.exists(layout.projectRoot), "whole project dir should be gone")
    }

    @Test
    fun `symlink in artifacts dir is never retained and never followed`(
        @TempDir root: Path,
        @TempDir outside: Path,
    ) {
        val layout = buildLayout(root)
        val outsideTarget = outside.resolve("host-secret.json")
        Files.write(outsideTarget, ByteArray(5))
        val link = layout.artifactsDir.resolve("link.json")
        Files.createSymbolicLink(link, outsideTarget)

        val result =
            ArtifactRetention.prune(
                workspaceRoot = root,
                artifactsDir = layout.artifactsDir,
                globs = listOf("*.json"),
                maxBytes = Long.MAX_VALUE,
            )

        assertEquals(ArtifactRetention.PruneResult(keptFiles = 1, truncated = false), result)
        assertTrue(Files.exists(layout.decisionJson), "the real decision.json should be kept")
        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS), "the symlink itself should be deleted")
        assertTrue(Files.exists(outsideTarget), "the symlink's target outside the workspace must be untouched")
    }

    @Test
    fun `oversized artifacts tree retains nothing and flags truncated`(
        @TempDir root: Path,
    ) {
        val layout = buildLayout(root)

        val result =
            ArtifactRetention.prune(
                workspaceRoot = root,
                artifactsDir = layout.artifactsDir,
                globs = listOf("*.json"),
                maxBytes = 1_000_000,
                maxWalkEntries = 3,
            )

        assertEquals(ArtifactRetention.PruneResult(keptFiles = 0, truncated = true), result)
        assertFalse(Files.exists(layout.projectRoot), "whole project dir should be gone")
    }

    @Test
    fun `a huge workdir does not count against the artifacts walk budget`(
        @TempDir root: Path,
    ) {
        val projectRoot = root.resolve("proj")
        val workdir = projectRoot.resolve("workdir")
        val artifactsDir = projectRoot.resolve("artifacts")
        Files.createDirectories(workdir)
        Files.createDirectories(artifactsDir)
        for (i in 0 until 10) {
            Files.writeString(workdir.resolve("scratch-$i.txt"), "scratch")
        }
        val decisionJson = artifactsDir.resolve("decision.json")
        Files.write(decisionJson, ByteArray(10))

        val result =
            ArtifactRetention.prune(
                workspaceRoot = root,
                artifactsDir = artifactsDir,
                globs = listOf("decision.json"),
                maxBytes = Long.MAX_VALUE,
                maxWalkEntries = 5,
            )

        assertEquals(ArtifactRetention.PruneResult(keptFiles = 1, truncated = false), result)
        assertTrue(Files.exists(decisionJson), "decision.json should remain at its original path")
        assertFalse(Files.exists(workdir), "workdir should be gone")
    }

    @Test
    fun `sibling delete survives a redundant path segment in artifactsDir`(
        @TempDir root: Path,
    ) {
        val layout = buildLayout(root)
        // Path.equals is structural — "proj/./artifacts" and "proj/artifacts" point
        // at the same directory but are not equal(). The sibling-delete pass must
        // still recognize this as artifactsDir (by file name), not as a sibling to
        // stream-delete along with workdir/.homer.
        val artifactsDirWithRedundantSegment = layout.projectRoot.resolve("./artifacts")

        val result =
            ArtifactRetention.prune(
                workspaceRoot = root,
                artifactsDir = artifactsDirWithRedundantSegment,
                globs = listOf("decision.json"),
                maxBytes = Long.MAX_VALUE,
            )

        assertEquals(ArtifactRetention.PruneResult(keptFiles = 1, truncated = false), result)
        assertTrue(Files.exists(layout.decisionJson), "decision.json should remain, not be swept up as a false sibling")
        assertFalse(Files.exists(layout.workdir), "workdir should still be gone")
    }

    @Test
    fun `refuses to prune a dir outside the workspace root`(
        @TempDir narrowRoot: Path,
        @TempDir outside: Path,
    ) {
        val artifactsDir = outside.resolve("artifacts")
        Files.createDirectories(artifactsDir)
        Files.write(artifactsDir.resolve("decision.json"), ByteArray(10))

        assertFailsWith<SecurityException> {
            ArtifactRetention.prune(
                workspaceRoot = narrowRoot,
                artifactsDir = artifactsDir,
                globs = listOf("*.json"),
                maxBytes = Long.MAX_VALUE,
            )
        }

        assertTrue(Files.exists(artifactsDir.resolve("decision.json")), "nothing outside the root should be deleted")
    }

    @Test
    fun `a missing artifacts dir deletes the whole project dir`(
        @TempDir root: Path,
    ) {
        val layout = buildLayout(root)
        layout.artifactsDir.toFile().deleteRecursively()

        val result =
            ArtifactRetention.prune(
                workspaceRoot = root,
                artifactsDir = layout.artifactsDir,
                globs = listOf("*.json"),
                maxBytes = Long.MAX_VALUE,
            )

        assertEquals(ArtifactRetention.PruneResult(keptFiles = 0, truncated = false), result)
        assertFalse(Files.exists(layout.projectRoot), "whole project dir should be gone")
    }

    @Test
    fun `an artifacts dir without a parent prunes nothing`(
        @TempDir root: Path,
    ) {
        val result =
            ArtifactRetention.prune(
                workspaceRoot = root,
                artifactsDir = Path.of("artifacts"),
                globs = listOf("*.json"),
                maxBytes = Long.MAX_VALUE,
            )

        assertEquals(ArtifactRetention.PruneResult(keptFiles = 0, truncated = false), result)
    }
}
