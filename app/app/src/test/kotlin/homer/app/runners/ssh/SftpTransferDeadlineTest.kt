package homer.app.runners.ssh

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.sftp.client.SftpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

class SftpTransferDeadlineTest {
    private val logger = LoggerFactory.getLogger(SftpTransferDeadlineTest::class.java)
    private val closed = AtomicBoolean(false)
    private val session =
        mockk<ClientSession>(relaxed = true) {
            every { close(true) } answers {
                closed.set(true)
                mockk(relaxed = true)
            }
        }

    @Test
    fun `without a timeout the block runs unbounded and the session stays open`() =
        runBlocking {
            val deadline = SftpTransferDeadline(session, transferTimeout = null, openSftp = { mockk(relaxed = true) }, logger = logger)

            assertEquals(42, deadline.withTransferTimeout { 42 })
            verify(exactly = 0) { session.close(true) }
        }

    @Test
    fun `a stalled call is unblocked by closing the session and reported as a timeout`() =
        runBlocking {
            val deadline =
                SftpTransferDeadline(session, transferTimeout = 100.milliseconds, openSftp = { mockk(relaxed = true) }, logger = logger)

            val e =
                assertThrows<IOException> {
                    runBlocking {
                        deadline.withTransferTimeout {
                            // Like a blocked JVM read: returns only once the watchdog tears the session down.
                            while (!closed.get()) Thread.sleep(10)
                            throw IOException("session closed")
                        }
                    }
                }
            assertContains(e.message.orEmpty(), "timed out after")
        }

    @Test
    fun `a run killed past the deadline ends cancelled, not as a transfer timeout`() =
        runBlocking<Unit> {
            val deadline =
                SftpTransferDeadline(session, transferTimeout = 100.milliseconds, openSftp = { mockk(relaxed = true) }, logger = logger)
            // What withTransferTimeout itself throws, captured inside the cancelled caller.
            val thrown = CompletableDeferred<Throwable?>()
            launch {
                val caller = coroutineContext.job
                val result =
                    runCatching {
                        deadline.withTransferTimeout {
                            while (!closed.get()) Thread.sleep(10) // the watchdog has fired
                            caller.cancel()
                            throw CancellationException("run killed")
                        }
                    }
                thrown.complete(result.exceptionOrNull())
            }.join()

            assertIs<CancellationException>(thrown.await())
        }

    @Test
    fun `the client is released after the block, and a failed release sheds the session`() =
        runBlocking {
            val sftp = mockk<SftpClient>(relaxed = true)
            val deadline = SftpTransferDeadline(session, transferTimeout = null, openSftp = { sftp }, logger = logger)

            assertEquals("done", deadline.withSftp { "done" })
            verify { sftp.close() }
            verify(exactly = 0) { session.close(true) }

            every { sftp.close() } throws IOException("close failed")
            assertEquals("again", deadline.withSftp { "again" })
            verify { session.close(true) }
        }
}
