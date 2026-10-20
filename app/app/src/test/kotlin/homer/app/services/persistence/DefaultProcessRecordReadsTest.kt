package homer.app.services.persistence

import homer.app.fixtures.PersistenceTestSupport
import homer.app.infra.ProcessJobRegistry
import homer.app.models.CostSource
import homer.app.models.ExecutionInfo
import homer.app.models.PodPhase
import homer.app.models.ProcessStatus
import homer.app.models.RunnerState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefaultProcessRecordReadsTest {
    private val repository = ProcessRepository(PersistenceTestSupport.tempDatabase(), ProcessJobRegistry())
    private val reads = DefaultProcessRecordReads(repository)

    @Test
    fun `reads an absent process as unknown and empty`() =
        runTest {
            assertEquals(-1, reads.lastProcessId())
            assertEquals(ProcessStatus.UNKNOWN, reads.getProcessStatus(42))
            assertNull(reads.getProcessInfo(42))
            assertNull(reads.getRunnerState(42))
            assertEquals(emptyList(), reads.getProcessHistory(42))
            assertEquals(emptyList(), reads.getProcessExecutions(42))
            assertEquals(emptyMap(), reads.getSubRunProgress(listOf(42)))
        }

    @Test
    fun `reads a stored run's record, history, executions and runner state`() =
        runTest {
            val id = repository.obtainProcessId("a", mapOf("K" to "v"), 100, tags = listOf("team:x")).processId
            repository.transitionStatus(id, ProcessStatus.WORKING, 5, null)
            val execution = ExecutionInfo("step", 1, 2, 0, null, null, null)
            repository.addExecution(id, execution)
            repository.setRunnerState(id) { RunnerState("local", podPhase = PodPhase.RUNNING) }

            assertEquals(id, reads.lastProcessId())
            assertEquals(ProcessStatus.WORKING, reads.getProcessStatus(id))
            assertEquals(mapOf("K" to "v"), reads.getProcessInfo(id)?.inputParams)
            assertEquals(listOf(5L to ProcessStatus.WORKING), reads.getProcessHistory(id))
            assertEquals(listOf(execution), reads.getProcessExecutions(id))
            assertEquals(PodPhase.RUNNING, reads.getRunnerState(id)?.podPhase)
            assertEquals(setOf(id), reads.activeProcessIds())
            assertEquals(ProcessStatus.WORKING, reads.getSubRunProgress(listOf(id))[id]?.status)
        }

    @Test
    fun `lists with the defaults and finds CREATED runs of an agent`() =
        runTest {
            val first = repository.obtainProcessId("a", emptyMap(), 100).processId
            val second = repository.obtainProcessId("b", emptyMap(), 100).processId

            val page = reads.getAllProcesses()

            assertEquals(listOf(second, first), page.processes.map { it.id })
            assertEquals(2, page.total)
            assertEquals(listOf(first to "a"), reads.createdRunsOf(listOf("a")))
        }

    @Test
    fun `sums cost by tag value through the screen`() =
        runTest {
            val id = repository.obtainProcessId("a", emptyMap(), 100, tags = listOf("team:x")).processId
            repository.addCost(id, 1.5, CostSource.CLI)

            val result = reads.costByTag("team") { it.tags }

            assertEquals(mapOf("x" to 1.5), result.valuesUsd)
        }
}
