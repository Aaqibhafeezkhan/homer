package homer.app.runners.queue

import homer.app.runners.RunnerSession
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

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
}
