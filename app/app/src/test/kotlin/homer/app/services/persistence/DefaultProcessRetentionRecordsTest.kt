package homer.app.services.persistence

import homer.app.fixtures.PersistenceTestSupport
import homer.app.infra.ProcessJobRegistry
import homer.app.models.ProcessStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultProcessRetentionRecordsTest {
    private val repository = ProcessRepository(PersistenceTestSupport.tempDatabase(), ProcessJobRegistry())
    private val retention = DefaultProcessRetentionRecords(repository)
    private val terminal = setOf(ProcessStatus.FINISHED)

    private suspend fun finishedRun(artifactsDir: Path): Int {
        val id = repository.obtainProcessId("a", emptyMap(), 100).processId
        repository.setArtifactsDir(id, artifactsDir.toString())
        repository.transitionStatus(id, ProcessStatus.FINISHED, 1, null)
        return id
    }

    @Test
    fun `a run stamped due is workspace-purgeable, then record-purgeable once its dir is cleared`() =
        runTest {
            val id = finishedRun(Path.of("/ws/1/artifacts"))
            retention.setPurgeAt(id, purgeAt = 10)

            assertEquals(listOf(id), retention.getWorkspacePurgeableProcesses(terminal, 10).map { it.id })
            assertEquals(emptyList(), retention.getRecordPurgeableProcesses(terminal, 10))

            retention.clearArtifactsDir(id)

            assertEquals(emptyList(), retention.getWorkspacePurgeableProcesses(terminal, 10))
            assertEquals(listOf(id), retention.getRecordPurgeableProcesses(terminal, 10).map { it.id })
        }

    @Test
    fun `a workspace marked purged keeps its dir and leaves the workspace sweep`() =
        runTest {
            val id = finishedRun(Path.of("/ws/1/artifacts"))
            retention.setPurgeAt(id, purgeAt = 10, recordPurgeAt = 20, retainArtifacts = listOf("*.txt"))

            retention.markWorkspacePurged(id, retainedTruncated = true)

            assertEquals(emptyList(), retention.getWorkspacePurgeableProcesses(terminal, 10))
            val info = repository.getInfo(id)
            assertEquals(Path.of("/ws/1/artifacts"), info?.artifactsDir)
            assertEquals(listOf("*.txt"), info?.retainArtifacts)
            assertEquals(true, info?.retainedTruncated)
        }

    @Test
    fun `clearing the dir with no truncation flag leaves the flag unset`() =
        runTest {
            val id = finishedRun(Path.of("/ws/1/artifacts"))

            retention.clearArtifactsDir(id)

            assertNull(repository.getInfo(id)?.artifactsDir)
            assertNull(repository.getInfo(id)?.retainedTruncated)
        }

    @Test
    fun `a shared dir counts every other row, the active-run check only live ones`() =
        runTest {
            val dir = Path.of("/ws/shared/artifacts")
            val finished = finishedRun(dir)
            val live = repository.obtainProcessId("a", emptyMap(), 100).processId
            repository.setArtifactsDir(live, dir.toString())

            assertTrue(retention.isArtifactsDirShared(dir, live))
            assertFalse(retention.isWorkspaceSharedWithActiveRun(dir, live))
            assertTrue(retention.isWorkspaceSharedWithActiveRun(dir, finished))
        }
}
