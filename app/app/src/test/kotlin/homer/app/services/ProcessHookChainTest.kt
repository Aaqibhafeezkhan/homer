package homer.app.services

import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.HookChainFixture
import homer.app.models.Agent
import homer.app.models.AgentHook
import homer.app.models.ProcessStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The status-hook chain end to end over a real database and no stand-in for any link: a status
 * write in [ProcessManager] records its hook rows inside the write's own transaction, hands them
 * to a real [HookService] after the commit, and that fires them through `HookFireService` into a
 * start. The DI wiring resolves `HookService` at call time for exactly this shape, so this is the
 * chain production runs. See ADR-0106.
 */
class ProcessHookChainTest {
    private fun agent(
        name: String,
        vararg hooks: Pair<ProcessStatus, String>,
    ): Agent = AgentFixtures.simpleAgent(name = name).copy(hooks = hooks.map { AgentHook(it.first, it.second) })

    /** A declaring agent with [hooks], plus every agent they name, all loaded. */
    private fun chain(vararg hooks: Pair<ProcessStatus, String>): HookChainFixture =
        HookChainFixture(
            agents =
                buildMap {
                    put("declarer", agent("declarer", *hooks))
                    hooks.map { it.second }.distinct().forEach { put(it, agent(it)) }
                },
        )

    @Test
    fun `a run reaching CREATED, WORKING and FINISHED fires each status's hook once, in that order`() =
        runTest {
            val f =
                chain(
                    ProcessStatus.CREATED to "notifier-created",
                    ProcessStatus.WORKING to "notifier-working",
                    ProcessStatus.FINISHED to "notifier-finished",
                )

            val id = f.processManager.obtainProcessId("declarer", owner = "alice")
            f.processManager.setProcessStatus(id, ProcessStatus.WORKING)
            f.processManager.compareAndSetStatus(id, setOf(ProcessStatus.WORKING), ProcessStatus.FINISHED)

            assertEquals(listOf("notifier-created", "notifier-working", "notifier-finished"), f.started)
        }

    @Test
    fun `the guarded CREATED re-assert a run's own start writes does not fire the CREATED hook again`() =
        runTest {
            // What ProcessExecutor.runJob writes right after the INSERT, so a kill that landed
            // first is not reset: the same status written twice is one reach of it, so the hook
            // entry fires exactly once. See ADR-0106.
            val f = chain(ProcessStatus.CREATED to "notifier-created")

            val id = f.processManager.obtainProcessId("declarer", owner = "alice")
            f.processManager.compareAndSetStatus(id, setOf(ProcessStatus.CREATED), ProcessStatus.CREATED)

            assertEquals(listOf("notifier-created"), f.started)
        }

    @Test
    fun `a failed run fires its FAILED hook and not its FINISHED hook`() =
        runTest {
            val f =
                chain(
                    ProcessStatus.FINISHED to "notifier-finished",
                    ProcessStatus.FAILED to "notifier-failed",
                )

            val id = f.processManager.obtainProcessId("declarer", owner = "alice")
            f.processManager.setProcessStatus(id, ProcessStatus.WORKING)
            f.processManager.compareAndSetStatus(id, setOf(ProcessStatus.WORKING), ProcessStatus.FAILED)

            assertEquals(listOf("notifier-failed"), f.started)
        }

    @Test
    fun `a killed run fires its KILLED hook and not its FINISHED hook`() =
        runTest {
            val f =
                chain(
                    ProcessStatus.FINISHED to "notifier-finished",
                    ProcessStatus.KILLED to "notifier-killed",
                )

            val id = f.processManager.obtainProcessId("declarer", owner = "alice")
            f.processManager.setProcessStatus(id, ProcessStatus.WORKING)
            f.processManager.killProcess(id, "operator kill")

            assertEquals(listOf("notifier-killed"), f.started)
        }

    @Test
    fun `a status the declaring agent names no hook for starts nothing`() =
        runTest {
            val f = chain(ProcessStatus.FINISHED to "notifier-finished")

            val id = f.processManager.obtainProcessId("declarer", owner = "alice")
            f.processManager.setProcessStatus(id, ProcessStatus.WORKING)
            f.processManager.setProcessStatus(id, ProcessStatus.FAILED)

            assertEquals(emptyList(), f.started)
        }

    @Test
    fun `a guard-rejected transition fires nothing`() =
        runTest {
            // A kill landed first: the FINISHED write is refused, and a refused write records no
            // hook row to fire. See ADR-0106.
            val f = chain(ProcessStatus.FINISHED to "notifier-finished")

            val id = f.processManager.obtainProcessId("declarer", owner = "alice")
            f.processManager.setProcessStatus(id, ProcessStatus.WORKING)
            f.processManager.killProcess(id, "operator kill")
            f.processManager.compareAndSetStatus(id, setOf(ProcessStatus.WORKING), ProcessStatus.FINISHED)

            assertEquals(emptyList(), f.started)
        }
}
