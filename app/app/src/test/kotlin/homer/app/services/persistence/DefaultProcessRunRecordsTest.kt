package homer.app.services.persistence

import homer.app.fixtures.PersistenceTestSupport
import homer.app.infra.ProcessJobRegistry
import homer.app.models.CostSource
import homer.app.models.PodPhase
import homer.app.models.RunnerState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultProcessRunRecordsTest {
    private val repository = ProcessRepository(PersistenceTestSupport.tempDatabase(), ProcessJobRegistry())
    private val records = DefaultProcessRunRecords(repository)

    @Test
    fun `annotations land on the run's row`() =
        runTest {
            val id = repository.obtainProcessId("a", emptyMap(), 100).processId

            records.setTraceId(id, "0af7651916cd43dd8448eb211c80319c")
            records.setArtifactsDir(id, Path.of("/ws/1/artifacts"))
            records.setRunnerState(id) { RunnerState("local", podPhase = PodPhase.READY) }
            records.addCost(id, 0.25, CostSource.SESSION)

            val info = repository.getInfo(id)
            assertEquals("0af7651916cd43dd8448eb211c80319c", info?.traceId)
            assertEquals(Path.of("/ws/1/artifacts"), info?.artifactsDir)
            assertEquals(PodPhase.READY, info?.runner?.podPhase)
            assertEquals(0.25, info?.costUsd)
            assertEquals(CostSource.SESSION.wire, info?.costSource)
        }

    @Test
    fun `a zero cost delta leaves the cost source unstamped`() =
        runTest {
            val id = repository.obtainProcessId("a", emptyMap(), 100).processId

            records.addCost(id, 0.0, CostSource.SESSION)

            assertEquals(CostSource.CLI.wire, repository.getInfo(id)?.costSource)
        }

    @Test
    fun `a runtime tag past the ceiling is refused`() =
        runTest {
            val id = repository.obtainProcessId("a", emptyMap(), 100).processId

            assertTrue(records.addRuntimeTag(id, "k:1", maxTags = 1, fromInputsKeys = emptySet()).written)
            assertFalse(records.addRuntimeTag(id, "k:2", maxTags = 1, fromInputsKeys = emptySet()).written)
            assertEquals(listOf("k:1"), repository.getTags(id))
        }
}
