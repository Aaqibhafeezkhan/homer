package homer.app.runners.external

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class OpMailboxTest {
    @Test
    fun `ops come out in submission order and results complete by id`() =
        runTest {
            val mb = OpMailbox()
            val d1 = mb.submit(RunnerOp.CreateWorkspace("a"))
            val d2 = mb.submit(RunnerOp.CleanWorkspace("b"))

            assertEquals("a", mb.next(1.seconds)!!.opId)
            assertEquals("b", mb.next(1.seconds)!!.opId)
            assertTrue(mb.complete(OpResult("b")))
            assertTrue(mb.complete(OpResult("a", root = "/r")))
            assertEquals("/r", d1.await().root)
            assertTrue(d2.await().ok)
            assertFalse(mb.complete(OpResult("a")), "a second result for the same op is ignored")
        }

    @Test
    fun `next returns null after the poll timeout without losing later ops`() =
        runTest {
            val mb = OpMailbox()
            assertNull(mb.next(1.seconds))
            mb.post(RunnerOp.Cancel("x"))
            assertEquals("x", mb.next(1.seconds)!!.opId)
        }

    @Test
    fun `fail completes every pending op exceptionally and refuses new ones but keeps buffered ops`() =
        runTest {
            val mb = OpMailbox()
            val pending = mb.submit(RunnerOp.CreateWorkspace("a"))
            mb.post(RunnerOp.Close("c", "bye"))

            mb.fail(ExternalRunnerException("instance lost"))

            val failure = async { runCatching { pending.await() } }
            assertTrue(failure.await().exceptionOrNull() is ExternalRunnerException)
            val late = mb.submit(RunnerOp.CleanWorkspace("z"))
            assertTrue(late.isCompleted && late.getCompletionExceptionOrNull() is ExternalRunnerException)
            assertEquals("a", mb.next(1.seconds)!!.opId, "already-buffered ops stay receivable so a close reaches the instance")
            assertEquals("c", mb.next(1.seconds)!!.opId)
            assertNull(mb.next(1.seconds))
        }
}
