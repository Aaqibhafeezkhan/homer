package homer.app.services

import homer.app.fixtures.PersistenceTestSupport
import homer.app.models.ExecutionInfo
import homer.app.models.ProcessStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessManagerEventsTest {
    private fun execution(label: String) =
        ExecutionInfo(
            label = label,
            start = 1000L,
            end = 2000L,
            resultCode = 0,
            stdOut = null,
            stdErr = null,
            errMsg = null,
        )

    @Test
    fun `setProcessStatus emits StatusChanged`() =
        runTest {
            val broadcaster = ProcessEventBroadcaster()
            val pm = PersistenceTestSupport.processManager(broadcaster = broadcaster)
            val id = pm.obtainProcessId("agent-a")

            val received =
                async {
                    broadcaster.subscribe().take(1).toList()
                }
            yield()
            pm.setProcessStatus(id, ProcessStatus.WORKING)

            val events = received.await()
            assertEquals(1, events.size)
            val event = events[0] as ProcessEvent.StatusChanged
            assertEquals(id, event.processId)
            assertEquals(ProcessStatus.WORKING, event.status)
            assertEquals("agent-a", event.agentName)
        }

    @Test
    fun `setCurrentCommand emits CurrentCommandChanged`() =
        runTest {
            val broadcaster = ProcessEventBroadcaster()
            val pm = PersistenceTestSupport.processManager(broadcaster = broadcaster)
            val id = pm.obtainProcessId("agent-a")

            val received =
                async {
                    broadcaster.subscribe().take(1).toList()
                }
            yield()
            pm.setCurrentCommand(id, "deploy", 1234L)

            val event = received.await()[0] as ProcessEvent.CurrentCommandChanged
            assertEquals(id, event.processId)
            assertEquals("deploy", event.label)
            assertEquals(1234L, event.startedAt)
        }

    @Test
    fun `completeStep with a next label emits exactly one CurrentCommandChanged carrying it`() =
        runTest {
            val broadcaster = ProcessEventBroadcaster()
            val pm = PersistenceTestSupport.processManager(broadcaster = broadcaster)
            val id = pm.obtainProcessId("agent-a")

            val received =
                async {
                    broadcaster.subscribe().take(1).toList()
                }
            yield()
            pm.completeStep(id, execution("step-1"), nextLabel = "step-2", nextStart = 4242L)

            // One event for the boundary, not the two an addExecution-then-clear pair emitted.
            val events = received.await()
            assertEquals(1, events.size)
            val event = events[0] as ProcessEvent.CurrentCommandChanged
            assertEquals(id, event.processId)
            assertEquals("step-2", event.label)
            assertEquals(4242L, event.startedAt)
            assertEquals(listOf("step-1"), pm.getProcessExecutions(id).map { it.label })
        }

    @Test
    fun `completeStep with a null label emits exactly one clear`() =
        runTest {
            val broadcaster = ProcessEventBroadcaster()
            val pm = PersistenceTestSupport.processManager(broadcaster = broadcaster)
            val id = pm.obtainProcessId("agent-a")
            pm.setCurrentCommand(id, "step-1", 1L)

            val received =
                async {
                    broadcaster.subscribe().take(1).toList()
                }
            yield()
            pm.completeStep(id, execution("step-1"), nextLabel = null, nextStart = null)

            val event = received.await()[0] as ProcessEvent.CurrentCommandChanged
            assertEquals(id, event.processId)
            assertEquals(null, event.label)
            assertEquals(null, event.startedAt)
        }

    @Test
    fun `completeStep on a removed row emits nothing`() =
        runTest {
            val broadcaster = ProcessEventBroadcaster()
            val pm = PersistenceTestSupport.processManager(broadcaster = broadcaster)
            val id = pm.obtainProcessId("agent-a")
            pm.removeProcess(id)

            val received =
                async {
                    broadcaster.subscribe().take(1).toList()
                }
            yield()
            pm.completeStep(id, execution("step-1"), nextLabel = "step-2", nextStart = 1L)

            // The guard: a concurrent kill/eviction between step start and end leaves
            // nothing written and no event to broadcast.
            assertNull(withTimeoutOrNull(50) { received.await() })
            received.cancel()
        }

    @Test
    fun `completeStep on a killed row records the execution but emits no command event`() =
        runTest {
            val broadcaster = ProcessEventBroadcaster()
            val pm = PersistenceTestSupport.processManager(broadcaster = broadcaster)
            val id = pm.obtainProcessId("agent-a")
            pm.setProcessStatus(id, ProcessStatus.WORKING)
            pm.killProcess(id)

            val received =
                async {
                    broadcaster.subscribe().take(1).toList()
                }
            yield()
            pm.completeStep(id, execution("step-1"), nextLabel = "step-2", nextStart = 4242L)

            // The row is terminal, so the boundary announces nothing — a late step must
            // not resurrect a current command the kill already cleared.
            assertNull(withTimeoutOrNull(50) { received.await() })
            received.cancel()
            assertEquals(listOf("step-1"), pm.getProcessExecutions(id).map { it.label })
        }

    @Test
    fun `setCurrentCommand on a terminal row emits nothing`() =
        runTest {
            val broadcaster = ProcessEventBroadcaster()
            val pm = PersistenceTestSupport.processManager(broadcaster = broadcaster)
            val id = pm.obtainProcessId("agent-a")
            pm.setProcessStatus(id, ProcessStatus.WORKING)
            pm.killProcess(id, "test-kill")
            // Drain the kill's own events before the step-start write is attempted.
            yield()

            val received =
                async {
                    broadcaster.subscribe().take(1).toList()
                }
            yield()
            pm.setCurrentCommand(id, "step-1", 1L)
            val emitted = withTimeoutOrNull(50) { received.await() } != null
            received.cancel()

            // The step-start write is guarded on the row still being live, so a terminal
            // row is never handed a running command and no event is broadcast.
            assertTrue(!emitted, "a terminal row must not emit CurrentCommandChanged")
            assertNull(pm.getProcessInfo(id)?.currentCommand)
        }

    @Test
    fun `killProcess emits StatusChanged KILLED and currentCommand null`() =
        runTest {
            val broadcaster = ProcessEventBroadcaster()
            val pm = PersistenceTestSupport.processManager(broadcaster = broadcaster)
            val id = pm.obtainProcessId("agent-a")
            pm.setProcessStatus(id, ProcessStatus.WORKING)

            val received =
                async {
                    broadcaster.subscribe().take(2).toList()
                }
            yield()
            pm.killProcess(id, "test-kill")

            val events = received.await()
            val statuses = events.filterIsInstance<ProcessEvent.StatusChanged>()
            val cmds = events.filterIsInstance<ProcessEvent.CurrentCommandChanged>()
            assertTrue(statuses.any { it.status == ProcessStatus.KILLED })
            assertTrue(cmds.any { it.label == null && it.startedAt == null })
        }
}
