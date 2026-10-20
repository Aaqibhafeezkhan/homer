package homer.app.runners.kubernetes

import io.kubernetes.client.openapi.models.V1ContainerStatus
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PodStatusTest {
    private fun pod(
        phase: String?,
        mainReady: Boolean = false,
        message: String? = null,
    ) = V1Pod().status(
        V1PodStatus()
            .phase(phase)
            .message(message)
            .containerStatuses(listOf(V1ContainerStatus().name(PodSpecBuilder.MAIN_CONTAINER_NAME).ready(mainReady))),
    )

    @Test
    fun `a pod is ready only when running with its main container ready`() {
        assertTrue(pod("Running", mainReady = true).isReady())
        assertFalse(pod("Running", mainReady = false).isReady())
        assertFalse(pod("Pending", mainReady = true).isReady())
        assertFalse(V1Pod().isReady())
    }

    @Test
    fun `failed and succeeded are terminal phases and carry the pod's message`() {
        assertTrue(pod("Failed", message = "OOMKilled").isTerminalFailure())
        assertTrue(pod("Succeeded").isTerminalFailure())
        assertFalse(pod("Running").isTerminalFailure())
        assertEquals("OOMKilled", pod("Failed", message = "OOMKilled").terminalMessage())
        assertNull(V1Pod().terminalMessage())
    }
}
