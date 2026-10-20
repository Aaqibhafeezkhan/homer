package homer.app.infra

import homer.app.fixtures.TestFixtures
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Unit tests for Workspace
 * Tests workspace creation, cleanup, and directory management
 */
@OptIn(ExperimentalPathApi::class)
class WorkspaceTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var mockSpan: Span
    private val testServerId = "test-server-123"
    private val testProcessId = 1L
    private val testGlobalEnv = mapOf("GLOBAL_VAR" to "global_value")

    @BeforeEach
    fun setup() {
        mockSpan = TestFixtures.mockSpan()
    }

    private fun createTestWorkspace(
        serverId: String = testServerId,
        processId: Long = testProcessId,
        clearAfterFinish: Boolean = true,
        isSharedWithLiveSibling: suspend () -> Boolean = { false },
        cleanupTimeout: Duration = 2.minutes,
    ): Workspace {
        val config =
            WorkspaceConfig(
                serverRoot = tempDir.resolve(serverId),
                projectRoot = tempDir.resolve("$serverId/$processId"),
                artifacts = tempDir.resolve("$serverId/$processId/artifacts"),
                workdir = tempDir.resolve("$serverId/$processId/workdir"),
                env = testGlobalEnv,
                clearAfterFinish = clearAfterFinish,
                lockKey = "$serverId-$processId",
            )
        return Workspace(config, mockSpan, isSharedWithLiveSibling, cleanupTimeout)
    }

    @AfterEach
    fun teardown() {
        // Cleanup handled by @TempDir
    }

    // ========== Workspace Path Tests ==========

    @Test
    fun `workspace should have correct projectRoot path`() {
        val workspace = createTestWorkspace()

        val expectedPath = tempDir.resolve("$testServerId/$testProcessId")
        assertEquals(expectedPath, workspace.config.projectRoot)
    }

    @Test
    fun `workspace should have correct artifacts path`() {
        val workspace = createTestWorkspace()

        val expectedPath = tempDir.resolve("$testServerId/$testProcessId/artifacts")
        assertEquals(expectedPath, workspace.config.artifacts)
    }

    @Test
    fun `workspace should have correct workdir path`() {
        val workspace = createTestWorkspace()

        val expectedPath = tempDir.resolve("$testServerId/$testProcessId/workdir")
        assertEquals(expectedPath, workspace.config.workdir)
    }

    // ========== Workspace Creation Tests ==========

    @Test
    fun `create should create artifacts directory`() {
        val workspace = createTestWorkspace()

        workspace.create()

        assertTrue(workspace.config.artifacts.exists(), "Artifacts directory should exist")
        assertTrue(Files.isDirectory(workspace.config.artifacts), "Artifacts should be a directory")
    }

    @Test
    fun `create should create workdir directory`() {
        val workspace = createTestWorkspace()

        workspace.create()

        assertTrue(workspace.config.workdir.exists(), "Workdir directory should exist")
        assertTrue(Files.isDirectory(workspace.config.workdir), "Workdir should be a directory")
    }

    @Test
    fun `create should set span attributes for paths`() {
        val workspace = createTestWorkspace()

        workspace.create()

        verify { mockSpan.setAttribute("path.root", workspace.config.projectRoot.toString()) }
        verify { mockSpan.setAttribute("path.artifacts", workspace.config.artifacts.toString()) }
        verify { mockSpan.setAttribute("path.workdir", workspace.config.workdir.toString()) }
    }

    @Test
    fun `create should add workspace created event to span`() {
        val workspace = createTestWorkspace()

        workspace.create()

        verify { mockSpan.addEvent("Workspace created") }
    }

    // ========== Workspace Cleanup Tests ==========

    @Test
    fun `cleanup should remove workdir when artifacts is empty`() =
        runTest {
            val workspace = createTestWorkspace()

            workspace.create()
            workspace.config.workdir
                .resolve("temp.txt")
                .writeText("temp data")
            assertTrue(workspace.config.workdir.exists(), "Workdir should exist before cleanup")

            workspace.cleanup()

            assertFalse(workspace.config.workdir.exists(), "Workdir should be removed during cleanup")
            assertTrue(workspace.config.artifacts.exists(), "Empty artifacts directory should remain")
        }

    @Test
    fun `cleanup should set hasArtifacts to false when artifacts is empty`() =
        runTest {
            val workspace = createTestWorkspace()

            workspace.create()
            workspace.cleanup()

            verify { mockSpan.setAttribute("path.hasArtifacts", false) }
        }

    @Test
    fun `cleanup should set hasArtifacts to true when artifacts has files`() =
        runTest {
            val workspace = createTestWorkspace()

            workspace.create()

            // Create an artifact file
            workspace.config.artifacts
                .resolve("output.txt")
                .writeText("some output")

            workspace.cleanup()

            verify { mockSpan.setAttribute("path.hasArtifacts", true) }
        }

    @Test
    fun `cleanup should remove workdir even when artifacts has files`() =
        runTest {
            val workspace = createTestWorkspace()

            workspace.create()

            // Create an artifact file
            workspace.config.artifacts
                .resolve("output.txt")
                .writeText("some output")

            // Create a file in workdir
            workspace.config.workdir
                .resolve("temp.txt")
                .writeText("temp data")

            workspace.cleanup()

            assertFalse(workspace.config.workdir.exists(), "Workdir should be removed during cleanup")
            assertTrue(workspace.config.artifacts.exists(), "Artifacts directory should remain")
        }

    @Test
    fun `cleanup should add workspace cleaned up event to span`() =
        runTest {
            val workspace = createTestWorkspace()

            workspace.create()
            workspace.cleanup()

            verify { mockSpan.addEvent("Workspace cleaned up") }
        }

    @Test
    fun `cleanup should not throw when workspace was deleted externally`() =
        runTest {
            val workspace = createTestWorkspace()
            workspace.create()

            // Simulate WorkspacePurgeService deleting projectRoot while the run is live.
            workspace.config.projectRoot.deleteRecursively()

            workspace.cleanup()

            verify { mockSpan.setAttribute("path.hasArtifacts", false) }
        }

    @Test
    fun `cleanup should not throw when workspace was never created`() =
        runTest {
            val workspace = createTestWorkspace()

            workspace.cleanup()

            verify { mockSpan.setAttribute("path.hasArtifacts", false) }
        }

    @Test
    fun `cleanup should remove homer dir with workdir when clearAfterFinish is true`() =
        runTest {
            // Unshared path (probe false): both recursive deletes happen exactly as before.
            val workspace = createTestWorkspace(isSharedWithLiveSibling = { false })
            workspace.create()

            val homerDir = workspace.config.projectRoot.resolve(QuestionsWorkspaceSetup.HOMER_DIR)
            homerDir
                .resolve("bin")
                .resolve("homer-ask")
                .also { it.parent.createDirectories() }
                .writeText("#!/bin/sh\n")
            workspace.config.workdir
                .resolve("temp.txt")
                .writeText("temp data")
            workspace.config.artifacts
                .resolve("output.txt")
                .writeText("some output")

            workspace.cleanup()

            assertFalse(workspace.config.workdir.exists(), "Workdir should be removed during cleanup")
            assertFalse(homerDir.exists(), "Homer tooling dir should be removed during cleanup")
            assertTrue(workspace.config.artifacts.exists(), "Artifacts directory should remain")
        }

    @Test
    fun `cleanup should keep workdir and homer dir when clearAfterFinish is false`() =
        runTest {
            val workspace = createTestWorkspace(clearAfterFinish = false)
            workspace.create()

            val homerDir = workspace.config.projectRoot.resolve(QuestionsWorkspaceSetup.HOMER_DIR)
            homerDir
                .resolve("bin")
                .resolve("homer-ask")
                .also { it.parent.createDirectories() }
                .writeText("#!/bin/sh\n")
            workspace.config.workdir
                .resolve("temp.txt")
                .writeText("temp data")
            workspace.config.artifacts
                .resolve("output.txt")
                .writeText("some output")

            workspace.cleanup()

            assertTrue(workspace.config.workdir.exists(), "Workdir should remain when not clearing")
            assertTrue(homerDir.exists(), "Homer tooling dir should remain when not clearing")
            assertTrue(workspace.config.artifacts.exists(), "Artifacts directory should remain")
            verify { mockSpan.addEvent("Workspace left as is") }
        }

    @Test
    fun `cleanup should not throw when homer dir was never created`() =
        runTest {
            val workspace = createTestWorkspace()
            workspace.create()

            // materialize() never ran (run failed before it), so `.homer/` is absent.
            assertFalse(
                workspace.config.projectRoot
                    .resolve(QuestionsWorkspaceSetup.HOMER_DIR)
                    .exists(),
            )

            workspace.cleanup()

            assertFalse(workspace.config.workdir.exists(), "Workdir should be removed during cleanup")
            verify { mockSpan.addEvent("Workspace cleaned up") }
        }

    @Test
    fun `cleanup leaves workdir and homer dir for a live sibling on a shared fixed path (CWE-362)`() =
        runTest {
            // Two runs share one fixed workspace path and run 2 is still live when run 1
            // finishes. The finishing run must not delete the tooling the sibling executes.
            val workspace = createTestWorkspace(isSharedWithLiveSibling = { true })
            workspace.create()

            val homerDir = workspace.config.projectRoot.resolve(QuestionsWorkspaceSetup.HOMER_DIR)
            homerDir
                .resolve("bin")
                .resolve("homer-ask")
                .also { it.parent.createDirectories() }
                .writeText("#!/bin/sh\n")
            workspace.config.workdir
                .resolve("temp.txt")
                .writeText("temp data")

            workspace.cleanup()

            assertTrue(workspace.config.workdir.exists(), "Workdir must survive while a sibling run is live")
            assertTrue(
                homerDir.resolve("bin").resolve("homer-ask").exists(),
                "Sibling run's .homer/bin helper must survive a finishing run's cleanup",
            )
            verify { mockSpan.addEvent("Workspace cleanup skipped (shared with a live run)") }
        }

    @Test
    fun `cleanup stops at its budget and leaves the rest to the purge sweep`() =
        runTest {
            // The workload sizes its own tree, so the delete is bounded in time: a spent
            // budget must return with the remainder still on disk (the purgeAfter sweep
            // takes it) instead of holding the run's lock and slot for the whole walk.
            val workspace = createTestWorkspace(cleanupTimeout = Duration.ZERO)
            workspace.create()
            repeat(200) { i ->
                workspace.config.workdir
                    .resolve("f$i.txt")
                    .writeText("x")
            }
            val homerDir = workspace.config.projectRoot.resolve(QuestionsWorkspaceSetup.HOMER_DIR)
            homerDir
                .resolve("bin")
                .also { it.createDirectories() }
                .resolve("homer-ask")
                .writeText("#!/bin/sh\n")

            workspace.cleanup()

            assertTrue(workspace.config.workdir.exists(), "A spent budget must leave workdir for the purge sweep")
            assertTrue(homerDir.exists(), "A spent budget must leave .homer for the purge sweep")
            verify { mockSpan.addEvent("Workspace cleanup incomplete", any<Attributes>()) }
        }

    @Test
    fun `cleanup's budget is checked on the failed-entry path too`() =
        runTest {
            // An entry the walk cannot open is routed through visitFileFailed, not
            // visitFile; the deadline must be consulted there as well, so a spent budget
            // terminates the walk before the first failure is even counted.
            assumeTrue(
                TestFixtures.posixPermissionsEnforced(),
                "POSIX permissions are not enforced (root or non-POSIX FS); the unopenable entry cannot be induced",
            )
            val workspace = createTestWorkspace(cleanupTimeout = Duration.ZERO)
            workspace.create()
            val unreadable =
                workspace.config.workdir
                    .resolve("unreadable")
                    .createDirectories()
            Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("--x------"))
            val attributes = slot<Attributes>()
            try {
                workspace.cleanup()
            } finally {
                Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("rwx------"))
            }

            verify { mockSpan.addEvent("Workspace cleanup incomplete", capture(attributes)) }
            assertEquals(
                0L,
                attributes.captured.get(AttributeKey.longKey("failed")),
                "A spent budget must stop the walk on the failed-entry path, not count the failure",
            )
            assertTrue(unreadable.exists(), "The unreadable entry stays for the purge sweep")
        }

    @Test
    fun `cleanup removes a symlink inside workdir without following it`() =
        runTest {
            val outside = tempDir.resolve("outside.txt").also { it.writeText("keep me") }
            val workspace = createTestWorkspace()
            workspace.create()
            val link = workspace.config.workdir.resolve("link.txt")
            try {
                Files.createSymbolicLink(link, outside)
            } catch (e: UnsupportedOperationException) {
                assumeTrue(false, "symlinks not supported on this filesystem")
            } catch (e: IOException) {
                assumeTrue(false, "symlinks not supported on this filesystem")
            }

            workspace.cleanup()

            assertFalse(link.exists(LinkOption.NOFOLLOW_LINKS), "The link itself must be removed")
            assertTrue(outside.exists(), "The link target must be neither followed nor deleted")
        }

    @Test
    fun `cleanup keeps walking past an entry it cannot delete`() =
        runTest {
            assumeTrue(
                TestFixtures.posixPermissionsEnforced(),
                "POSIX permissions are not enforced (root or non-POSIX FS); the undeletable entry cannot be induced",
            )
            val workspace = createTestWorkspace()
            workspace.create()
            val locked =
                workspace.config.workdir
                    .resolve("locked")
                    .createDirectories()
            val lockedFile = locked.resolve("inner.txt").also { it.writeText("x") }
            val plain =
                workspace.config.workdir
                    .resolve("plain.txt")
                    .also { it.writeText("y") }
            // Read-only directory: the file inside cannot be unlinked, so its delete fails.
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"))
            try {
                workspace.cleanup()
            } finally {
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"))
            }

            assertFalse(plain.exists(), "The walk must continue past an entry it cannot delete")
            assertTrue(lockedFile.exists(), "The undeletable entry stays for the purge sweep")
            verify { mockSpan.addEvent("Workspace cleanup incomplete", any<Attributes>()) }
        }

    // ========== Shell Executor Tests ==========

    @Test
    fun `workspace should provide shellExecutor`() {
        val workspace = createTestWorkspace()

        assertNotNull(workspace.shellExecutor, "Shell executor should not be null")
    }

    // ========== Multiple Process Tests ==========

    @Test
    fun `workspaces with different process IDs should have different paths`() {
        val workspace1 = createTestWorkspace(processId = 1L)
        val workspace2 = createTestWorkspace(processId = 2L)

        assertTrue(
            workspace1.config.projectRoot != workspace2.config.projectRoot,
            "Process 1 and 2 should have different paths",
        )
    }

    @Test
    fun `workspaces with different server IDs should have different paths`() {
        val workspace1 = createTestWorkspace(serverId = "server-1")
        val workspace2 = createTestWorkspace(serverId = "server-2")

        assertTrue(
            workspace1.config.projectRoot != workspace2.config.projectRoot,
            "Different servers should have different paths",
        )
    }

    // ========== AppendToEnv Tests ==========

    @Test
    fun `appendToEnv should add new environment variables`() {
        val workspace = createTestWorkspace()

        val newEnv = mapOf("NEW_VAR" to "new_value", "ANOTHER_VAR" to "another_value")
        workspace.appendToEnv(newEnv)

        assertEquals("new_value", workspace.config.env["NEW_VAR"])
        assertEquals("another_value", workspace.config.env["ANOTHER_VAR"])
        assertEquals("global_value", workspace.config.env["GLOBAL_VAR"], "Original env should remain")
    }

    @Test
    fun `appendToEnv should merge with existing env variables`() {
        val workspace = createTestWorkspace()

        // Initial env should have GLOBAL_VAR
        assertEquals(testGlobalEnv, workspace.config.env)

        val additionalEnv = mapOf("ADDED_VAR" to "added_value")
        workspace.appendToEnv(additionalEnv)

        assertEquals(2, workspace.config.env.size, "Should have 2 environment variables")
        assertEquals("global_value", workspace.config.env["GLOBAL_VAR"])
        assertEquals("added_value", workspace.config.env["ADDED_VAR"])
    }

    @Test
    fun `appendToEnv should overwrite existing variables with same key`() {
        val workspace = createTestWorkspace()

        assertEquals("global_value", workspace.config.env["GLOBAL_VAR"])

        val overwritingEnv = mapOf("GLOBAL_VAR" to "overwritten_value")
        workspace.appendToEnv(overwritingEnv)

        assertEquals(
            "overwritten_value",
            workspace.config.env["GLOBAL_VAR"],
            "Variable should be overwritten with new value",
        )
    }

    @Test
    fun `appendToEnv should not modify original config`() {
        val workspace = createTestWorkspace()

        val originalEnv = workspace.config.env
        val originalEnvSize = originalEnv.size

        workspace.appendToEnv(mapOf("NEW_VAR" to "value"))

        // Original env map should not be modified (immutability)
        assertEquals(
            originalEnvSize,
            originalEnv.size,
            "Original env map should not be modified",
        )
        assertFalse(
            originalEnv.containsKey("NEW_VAR"),
            "Original env should not contain new variable",
        )

        // Workspace config should have the new env
        assertTrue(
            workspace.config.env.containsKey("NEW_VAR"),
            "Workspace config should contain new variable",
        )
    }
}
