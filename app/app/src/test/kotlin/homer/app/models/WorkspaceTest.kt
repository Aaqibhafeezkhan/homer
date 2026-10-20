package homer.app.models

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue

class WorkspaceTest {
    @Test
    fun `retain recordFor must be at least purgeAfter`() {
        val e =
            assertThrows<IllegalArgumentException> {
                Workspace(purgeAfter = "5d", retain = Retain(recordFor = "1d"))
            }
        assertTrue(e.message!!.contains("workspace.retain.recordFor"))
    }

    @Test
    fun `retain accepts recordFor equal to purgeAfter and valid globs`() {
        Workspace(purgeAfter = "5d", retain = Retain(recordFor = "5d", artifacts = listOf("decision.json", "reports/**/*.md")))
    }

    @Test
    fun `purgeRecordAfter is rejected with a migration hint`() {
        val e =
            assertThrows<IllegalArgumentException> {
                Workspace(purgeRecordAfter = "30d")
            }
        assertTrue(e.message!!.contains("retain"))
        assertTrue(e.message!!.contains("recordFor"))
    }

    @Test
    fun `retain rejects absolute traversal backslash and malformed globs`() {
        for (bad in listOf("/etc/passwd", "../escape", "a\\b", "", " ", "[")) {
            assertThrows<IllegalArgumentException>("glob '$bad' should be rejected") {
                Workspace(retain = Retain(recordFor = "5d", artifacts = listOf(bad)))
            }
        }
    }

    @Test
    fun `retain artifacts require a per-run workspace path`() {
        val e =
            assertThrows<IllegalArgumentException> {
                Workspace(path = "shared-dir", retain = Retain(recordFor = "5d", artifacts = listOf("a.json")))
            }
        assertTrue(e.message!!.contains("HMR_PROCESS_ID"))
    }

    @Test
    fun `retain artifacts reject a traversal workspace path`() {
        val e =
            assertThrows<IllegalArgumentException> {
                Workspace(
                    path = "runs/{{HMR_PROCESS_ID}}/../shared",
                    retain = Retain(recordFor = "5d", artifacts = listOf("a.json")),
                )
            }
        assertTrue(e.message!!.contains(".."))
    }

    @Test
    fun `record-only retain allows a fixed workspace path`() {
        Workspace(path = "shared-dir", retain = Retain(recordFor = "5d"))
    }

    @Test
    fun `default workspace path satisfies the per-run requirement`() {
        Workspace(retain = Retain(recordFor = "5d", artifacts = listOf("a.json")))
    }

    @Test
    fun `a workspace path containing a traversal segment is rejected at load`() {
        val e = assertThrows<IllegalArgumentException> { Workspace(path = "a/../b") }
        assertTrue(e.message!!.contains(".."))
    }
}
