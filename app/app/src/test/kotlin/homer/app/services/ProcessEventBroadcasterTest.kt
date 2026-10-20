package homer.app.services

import homer.app.models.ProcessStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessEventBroadcasterTest {
    @Test
    fun `emit delivers event to active subscriber`() =
        runTest {
            val broadcaster = ProcessEventBroadcaster()
            val received =
                async {
                    broadcaster.subscribe().first()
                }
            yield()
            val event = ProcessEvent.StatusChanged(1, ProcessStatus.WORKING, "agent-a")
            broadcaster.emit(event)
            assertEquals(event, received.await())
        }

    @Test
    fun `multiple subscribers each receive the same event`() =
        runTest {
            val broadcaster = ProcessEventBroadcaster()
            val sub1 = async { broadcaster.subscribe().first() }
            val sub2 = async { broadcaster.subscribe().first() }
            yield()
            val event = ProcessEvent.AgentsReloaded(listOf("a"), emptyList(), emptyList())
            broadcaster.emit(event)
            assertEquals(event, sub1.await())
            assertEquals(event, sub2.await())
        }

    @Test
    fun `events emitted before subscription are not replayed`() =
        runTest {
            val broadcaster = ProcessEventBroadcaster()
            broadcaster.emit(ProcessEvent.AgentsReloaded(listOf("ignored"), emptyList(), emptyList()))
            val deliver =
                async {
                    broadcaster.subscribe().take(1).toList()
                }
            yield()
            val visible = ProcessEvent.AgentsReloaded(listOf("visible"), emptyList(), emptyList())
            broadcaster.emit(visible)
            assertEquals(listOf(visible), deliver.await())
        }

    @Test
    fun `subscribe counts events dropped when a consumer falls behind (audit P2 #22)`() =
        runTest {
            // Tiny buffer so the consumer overflows quickly once it is behind.
            val broadcaster = ProcessEventBroadcaster(extraBufferCapacity = 2)
            val received = mutableListOf<ProcessEvent>()
            val job =
                launch {
                    broadcaster.subscribe().collect { received.add(it) }
                }
            yield() // subscriber registers

            // Deliver one event so the subscriber establishes its sequence baseline.
            broadcaster.emit(ProcessEvent.StatusChanged(0, ProcessStatus.WORKING, "agent-a"))
            advanceUntilIdle()

            // Burst far more than the buffer can hold while the subscriber is parked:
            // tryEmit never yields to the collector, so these accumulate and DROP_OLDEST
            // evicts the middle of the sequence.
            repeat(20) { i ->
                broadcaster.emit(
                    ProcessEvent.StatusChanged(i + 1, ProcessStatus.WORKING, "agent-a"),
                )
            }
            advanceUntilIdle() // subscriber resumes and sees the sequence gap
            job.cancel()

            assertTrue(
                broadcaster.droppedEventCount > 0,
                "expected droppedEventCount > 0 after the consumer fell behind, " +
                    "got ${broadcaster.droppedEventCount}",
            )
        }
}
