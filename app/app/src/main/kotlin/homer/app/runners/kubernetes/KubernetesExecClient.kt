package homer.app.runners.kubernetes

/** io.kubernetes.client.Exec's initial statusCode, returned when the exec websocket closed without a status. */
internal const val EXEC_NO_EXIT_STATUS_SENTINEL = -1975219

/**
 * Seam over io.kubernetes.client.Exec so KubernetesShellExecutor can
 * be unit-tested without a real cluster. Returning java.lang.Process
 * matches what the upstream client produces — its stdout/stderr/stdin
 * streams are wired to the remote pod via a websocket.
 */
fun interface KubernetesExecClient {
    fun start(
        namespace: String,
        podName: String,
        argv: List<String>,
        stdin: Boolean,
    ): Process
}
