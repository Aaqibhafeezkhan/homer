package homer.app.runners.queue

import homer.app.runners.RunDeadline
import homer.app.runners.RunnerSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PermitReleasingSessionTest {
    @Test
    fun `close delegates and releases exactly once`() {
        val delegate = mockk<RunnerSession>(relaxed = true)
        var released = 0
        val session = PermitReleasingSession(delegate) { released++ }

        session.close()
        session.close()

        verify(exactly = 2) { delegate.close() }
        assertEquals(1, released)
    }

    @Test
    fun `a stop step that throws still releases the permit`() {
        val delegate = mockk<RunnerSession>()
        every { delegate.close() } throws IllegalStateException("stop step failed")
        var released = 0
        val session = PermitReleasingSession(delegate) { released++ }

        assertThrows<IllegalStateException> { session.close() }

        assertEquals(1, released, "the permit is freed however close() ends")
    }

    @Test
    fun `the run deadline is delegated, null included`() {
        val delegate = mockk<RunnerSession>(relaxed = true)
        val deadline = RunDeadline(softEpochSec = 1_000, hardEpochSec = 1_120)

        every { delegate.runDeadline } returns deadline
        assertEquals(deadline, PermitReleasingSession(delegate) {}.runDeadline)

        every { delegate.runDeadline } returns null
        assertNull(PermitReleasingSession(delegate) {}.runDeadline)
    }

    @Test
    fun `TagRoutedSession delegates the run deadline, null included`() {
        val delegate = mockk<RunnerSession>(relaxed = true)
        val deadline = RunDeadline(softEpochSec = 1_000, hardEpochSec = 1_120)

        every { delegate.runDeadline } returns deadline
        assertEquals(deadline, TagRoutedSession(delegate, emptyMap()).runDeadline)

        every { delegate.runDeadline } returns null
        assertNull(TagRoutedSession(delegate, emptyMap()).runDeadline)
    }
}
