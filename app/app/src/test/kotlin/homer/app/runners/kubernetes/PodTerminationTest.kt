package homer.app.runners.kubernetes

import homer.app.models.PodTermination
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.codepoints
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1ContainerState
import io.kubernetes.client.openapi.models.V1ContainerStateTerminated
import io.kubernetes.client.openapi.models.V1ContainerStatus
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodStatus
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A CoreV1Api whose pod-status read returns [pod], or throws [error] when set. */
internal fun podStatusApi(
    pod: V1Pod? = null,
    error: Exception? = null,
): CoreV1Api {
    val req = mockk<CoreV1Api.APIreadNamespacedPodStatusRequest>()
    if (error != null) every { req.execute() } throws error else every { req.execute() } returns pod!!
    return mockk { every { readNamespacedPodStatus(any(), any()) } returns req }
}

internal fun podWithStatus(
    phase: String,
    reason: String? = null,
    message: String? = null,
    mainTerminated: V1ContainerStateTerminated? = null,
    deleting: Boolean = false,
): V1Pod =
    V1Pod()
        .metadata(V1ObjectMeta().deletionTimestamp(if (deleting) OffsetDateTime.now() else null))
        .status(
            V1PodStatus()
                .phase(phase)
                .reason(reason)
                .message(message)
                .containerStatuses(
                    listOf(
                        V1ContainerStatus().name("main").state(V1ContainerState().terminated(mainTerminated)),
                    ),
                ),
        )

class PodTerminationTest {
    private fun read(
        pod: V1Pod? = null,
        error: Exception? = null,
    ) = readPodTermination(podStatusApi(pod, error), "ns", "p")

    @Test
    fun `a deadline-exceeded pod reports DeadlineExceeded`() {
        val t = read(podWithStatus("Failed", "DeadlineExceeded", "Pod was active on the node longer than the specified deadline"))
        assertEquals(
            PodTermination("DeadlineExceeded", "Pod was active on the node longer than the specified deadline"),
            t,
        )
    }

    @Test
    fun `an OOMKilled main container reports its reason and message`() {
        val t = read(podWithStatus("Running", mainTerminated = V1ContainerStateTerminated().reason("OOMKilled").message("out of memory")))
        assertEquals(PodTermination("OOMKilled", "out of memory"), t)
    }

    @Test
    fun `an agent-written multi-line termination message becomes one capped line`() {
        val written = "first line\r\n\tsecond\u0000line  " + "x".repeat(2000)
        val t = read(podWithStatus("Running", mainTerminated = V1ContainerStateTerminated().reason("Error").message(written)))!!
        assertEquals("first line second line " + "x".repeat(TERMINATION_MESSAGE_MAX_CHARS - 23), t.message)
        assertEquals("Error", t.reason)
    }

    @Test
    fun `a termination message of only whitespace falls back to the phase`() {
        val t = read(podWithStatus("Failed", "Evicted", " \n\t "))
        assertEquals(PodTermination("Evicted", "Pod phase Failed"), t)
    }

    @Test
    fun `any termination message is reduced to one line of at most the cap`() =
        runTest {
            val special =
                Arb.element(
                    listOf('\n', '\r', '\t', '\u000b', '\u0000', '\u0085', '\u2028', '\u2029', ' ').map { Codepoint(it.code) },
                )
            checkAll(Arb.string(0..1500, Arb.choice(Arb.codepoints(), special))) { written ->
                val line = written.toOneLine()
                assertTrue(line.none { it == '\n' || it == '\r' || it == '\u2028' || it == '\u2029' || it == '\u0085' }, line)
                assertTrue(line.length <= TERMINATION_MESSAGE_MAX_CHARS, "length ${line.length}")
                assertEquals(line.trim(), line)
            }
        }

    @Test
    fun `an evicted pod reports Evicted`() {
        val t = read(podWithStatus("Failed", "Evicted", "The node was low on resource: memory."))
        assertEquals(PodTermination("Evicted", "The node was low on resource: memory."), t)
    }

    @Test
    fun `a deletionTimestamp on a running pod reports PodTerminating`() {
        assertEquals(PodTermination("PodTerminating", "Pod phase Running"), read(podWithStatus("Running", deleting = true)))
    }

    @Test
    fun `a failed pod without a reason falls back to its phase`() {
        assertEquals(PodTermination("Failed", "Pod phase Failed"), read(podWithStatus("Failed")))
    }

    @Test
    fun `a 404 reports PodDeleted`() {
        assertEquals(PodTermination("PodDeleted", "The pod no longer exists"), read(error = ApiException(404, "gone")))
    }

    @Test
    fun `a healthy running pod gives null`() {
        assertNull(read(podWithStatus("Running")))
    }

    @Test
    fun `a pod without a status gives null`() {
        assertNull(read(V1Pod()))
    }

    @Test
    fun `a 500 gives null`() {
        assertNull(read(error = ApiException(500, "boom")))
    }

    @Test
    fun `a non-API read error gives null`() {
        assertNull(read(error = IllegalStateException("socket")))
    }

    @Test
    fun `summary names maxRunSeconds for a deadline`() {
        assertEquals(
            "maxRunSeconds (10800 s) exceeded (DeadlineExceeded)",
            PodTermination("DeadlineExceeded", "x").summary(10800),
        )
    }

    @Test
    fun `summary of a deadline without maxRunSeconds falls back to the message`() {
        assertEquals("x (DeadlineExceeded)", PodTermination("DeadlineExceeded", "x").summary(null))
    }

    @Test
    fun `summary of another reason is message and reason`() {
        assertEquals("m (OOMKilled)", PodTermination("OOMKilled", "m").summary(10800))
    }
}
