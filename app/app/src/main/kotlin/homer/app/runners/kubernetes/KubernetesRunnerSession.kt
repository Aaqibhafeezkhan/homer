package homer.app.runners.kubernetes

import homer.app.infra.ShellExecutor
import homer.app.models.ExtraUploadSpec
import homer.app.runners.DownloadMode
import homer.app.runners.RemotePathTranslator
import homer.app.runners.RunnerSession
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

class KubernetesRunnerSession(
    val podName: String,
    val namespace: String,
    private val api: CoreV1Api,
    override val shell: ShellExecutor,
    private val keepPodOnFailure: Boolean,
    private val keepPodTtlSeconds: Long,
    override val runnerAlias: String,
    private val sync: KubernetesWorkspaceSync,
    private val translator: RemotePathTranslator,
    private val initialEnv: Map<String, String>,
    private val syncExcludes: List<String> = emptyList(),
    override val extraUploads: List<ExtraUploadSpec> = emptyList(),
    override val env: Map<String, String> = emptyMap(),
    private val tracer: Tracer? = null,
    // The run's context, captured at openSession. close() happens after the run's
    // own scope has unwound, so there is no current context to inherit — without
    // this the close span becomes a NEW ROOT, and a new root is sampled on its own
    // rather than following the run. That is what `telemetry.export: OFF` relies on
    // to mute a run: the root is valid but unsampled and ParentBased drops the
    // subtree. An unparented span escapes it. See ADR-0050.
    private val parentContext: Context = Context.root(),
) : RunnerSession {
    private val closed = AtomicBoolean(false)
    private var failed: Boolean = false

    override val metadata: Map<String, String>
        get() = mapOf("pod.name" to podName)

    override fun markFailed() {
        failed = true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val mode = cleanupMode()
        val span = closeSpan(mode)
        try {
            cleanUpPod(mode)
            span?.setStatus(StatusCode.OK)
        } catch (e: Throwable) {
            span?.recordException(e)
            span?.setStatus(StatusCode.ERROR, e.message ?: "runner.session.close failed")
            throw e
        } finally {
            span?.end()
        }
    }

    /** `ttl` keeps a failed run's pod for debugging when configured to; else `delete`. */
    private fun cleanupMode(): String = if (failed && keepPodOnFailure) "ttl" else "delete"

    private fun closeSpan(mode: String): Span? {
        val finalPhase = if (mode == "ttl") "KEPT_FOR_DEBUG" else "DELETED"
        return tracer
            ?.spanBuilder("runner.session.close")
            ?.setParent(parentContext)
            ?.startSpan()
            ?.apply {
                setAttribute("runner.alias", runnerAlias)
                setAttribute("pod.namespace", namespace)
                setAttribute("pod.name", podName)
                setAttribute("pod.cleanup_mode", mode)
                setAttribute("pod.final_phase", finalPhase)
            }
    }

    private fun cleanUpPod(mode: String) {
        if (mode == "ttl") {
            PodCleanup.keepWithTtl(api, namespace, podName, keepPodTtlSeconds)
        } else {
            PodCleanup.delete(api, namespace, podName)
        }
    }

    override suspend fun createWorkspace(): Map<String, String> =
        withContext(Dispatchers.IO) {
            // mkdir -p creates the projectRoot too; workdir must exist before
            // any command (a runnerCmd check may run before uploads).
            sync.mkdirs(REMOTE_WORKDIR)
            translator.overrides(initialEnv) +
                extraUploads.mapNotNull { spec -> spec.env?.let { it to "$REMOTE_PROJECT_ROOT/${spec.remote}" } }
        }

    override suspend fun upload(
        local: Path,
        remoteRelative: String,
        excludes: List<String>,
    ) = withContext(Dispatchers.IO) {
        val remote = remotePath(remoteRelative)
        // The pod pre-creates workdir as a root-owned kubelet subPath dir, so a workdir tar
        // directory entry makes GNU tar fail restoring its mtime under runAsNonRoot. Kept
        // workspaces need workdir content retained between runs, so it can't be excluded
        // outright: omit only the top-level directory entry itself (root-relative, so a
        // workspace-authored nested workdir still gets its own entry) — tar then writes
        // its contents without ever touching the pre-created directory. See ADR-0054.
        val omitDirEntries = if (remote == REMOTE_PROJECT_ROOT) setOf("workdir") else emptySet()
        sync.upload(local, remote, syncExcludes + excludes, omitDirEntries)
    }

    override suspend fun download(
        remoteRelative: String,
        local: Path,
        mode: DownloadMode,
    ) = withContext(Dispatchers.IO) {
        sync.download(remotePath(remoteRelative), local, mode = mode)
    }

    // cleanWorkspace(): deliberately the no-op default — the emptyDir dies
    // with the pod, and a kept-for-debug pod retains its workspace (ADR-0043).

    private fun remotePath(relative: String): String =
        if (relative == "." || relative.isEmpty()) REMOTE_PROJECT_ROOT else "$REMOTE_PROJECT_ROOT/${relative.trimStart('/')}"

    companion object {
        // Fixed in-pod workspace root; pod-per-run means no collisions (ADR-0043).
        const val REMOTE_PROJECT_ROOT = "/homer/workspace"
        const val REMOTE_WORKDIR = "$REMOTE_PROJECT_ROOT/workdir"
    }
}
