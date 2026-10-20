package homer.app.runners.kubernetes

import io.kubernetes.client.openapi.models.V1Pod

/** Running, every init container exited 0, and the main container reports ready. */
internal fun V1Pod.isReady(): Boolean {
    val status = status ?: return false
    if (status.phase != "Running") return false
    val initsOk = status.initContainerStatuses.orEmpty().all { (it.state?.terminated?.exitCode ?: -1) == 0 }
    val mainReady = status.containerStatuses.orEmpty().any { it.ready == true && it.name == PodSpecBuilder.MAIN_CONTAINER_NAME }
    return initsOk && mainReady
}

/** A phase the pod never leaves: a session pod that reached it can no longer start. */
internal fun V1Pod.isTerminalFailure(): Boolean {
    val phase = status?.phase
    return phase == "Failed" || phase == "Succeeded"
}

internal fun V1Pod.terminalMessage(): String? = status?.message
