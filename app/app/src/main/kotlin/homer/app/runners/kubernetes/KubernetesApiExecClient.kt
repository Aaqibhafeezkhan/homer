package homer.app.runners.kubernetes

import io.kubernetes.client.Exec as UpstreamExec

/**
 * Production implementation of KubernetesExecClient that delegates to
 * the upstream io.kubernetes.client.Exec helper. The seam interface
 * keeps KubernetesShellExecutor unit-testable without a real cluster.
 */
class KubernetesApiExecClient(
    private val upstream: UpstreamExec,
) : KubernetesExecClient {
    override fun start(
        namespace: String,
        podName: String,
        argv: List<String>,
        stdin: Boolean,
    ): Process {
        val argvArray = argv.toTypedArray()
        return upstream.exec(namespace, podName, argvArray, stdin, false)
    }
}
