package homer.app.di

import homer.app.models.AppConfig
import homer.app.models.RunnerConfig
import homer.app.runners.RunnerRegistry
import homer.app.runners.external.ExternalRunnerDomain
import homer.app.runners.kubernetes.KubernetesApiExecClient
import homer.app.runners.kubernetes.KubernetesRunner
import homer.app.runners.kubernetes.RunnerStateSink
import homer.app.runners.local.LocalRunner
import homer.app.runners.queue.RegisteredRunner
import homer.app.runners.queue.RunnerClaimQueue
import homer.app.runners.ssh.SshRunner
import homer.app.runners.ssh.applySshClientTiming
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.util.Config
import io.opentelemetry.api.trace.Tracer
import org.apache.sshd.client.SshClient
import kotlin.time.Duration.Companion.seconds
import io.kubernetes.client.Exec as KubernetesExec

/**
 * The [RunnerRegistry] of [appConfig]: the local runner, one entry per Kubernetes and SSH runner,
 * and one [ExternalRunnerDomain] per pull-mode domain, all sharing one claim queue. [k8sInstanceId]
 * scopes the Kubernetes runners' startup sweep to this instance's pods.
 */
internal fun buildRunnerRegistry(
    appConfig: AppConfig,
    stateSink: RunnerStateSink,
    tracer: Tracer,
    k8sInstanceId: String,
): RunnerRegistry {
    val queue = RunnerClaimQueue(maxPending = appConfig.server.maxPendingRunnerClaims)
    return RunnerRegistry(
        entries = registeredRunners(appConfig, stateSink, tracer, k8sInstanceId),
        externalDomains = externalRunnerDomains(appConfig, queue),
        queue = queue,
        defaultClaimTimeout = appConfig.server.runnerClaimTimeoutSec.seconds,
        localRunnerDisabled = !appConfig.server.localRunner.enabled,
    )
}

/** One [ExternalRunnerDomain] per configured pull-mode runner, claiming from [queue]. */
private fun externalRunnerDomains(
    appConfig: AppConfig,
    queue: RunnerClaimQueue,
): List<ExternalRunnerDomain> =
    appConfig.runners.mapNotNull { (alias, cfg) ->
        (cfg as? RunnerConfig.External)?.let {
            ExternalRunnerDomain(
                alias,
                it,
                queue,
                appConfig.sysEnvBackedRunnerEnvNames[alias] ?: emptySet(),
            )
        }
    }

/** The push-mode runners: the local one first (unless disabled), then each Kubernetes and SSH runner in config order. */
private fun registeredRunners(
    appConfig: AppConfig,
    stateSink: RunnerStateSink,
    tracer: Tracer,
    k8sInstanceId: String,
): List<RegisteredRunner> =
    buildList {
        // Local commands reach Homer's own API (homer-ask helper,
        // RunAgent) on the loopback unless the operator already
        // provides HMR_API_URL via globalEnv / agent env.
        if (appConfig.server.localRunner.enabled) {
            add(
                RegisteredRunner(
                    LocalRunner(defaultApiUrl = "http://127.0.0.1:${appConfig.server.port}"),
                    tags =
                        appConfig.server.localRunner.tags
                            .toSet(),
                    concurrency = appConfig.server.localRunner.concurrency,
                ),
            )
        }
        appConfig.runners.forEach { (alias, cfg) ->
            val secretNames = appConfig.sysEnvBackedRunnerEnvNames[alias] ?: emptySet()
            when (cfg) {
                is RunnerConfig.Kubernetes -> {
                    add(
                        RegisteredRunner(
                            buildKubernetesRunner(alias, cfg, stateSink, tracer, k8sInstanceId, secretNames),
                            tags = cfg.tags.toSet(),
                            concurrency = cfg.concurrency,
                        ),
                    )
                }

                is RunnerConfig.Ssh -> {
                    add(
                        RegisteredRunner(
                            buildSshRunner(alias, cfg, secretNames),
                            tags = cfg.tags.toSet(),
                            concurrency = cfg.concurrency,
                        ),
                    )
                }

                is RunnerConfig.External -> {} // registered as a domain, see externalRunnerDomains
            }
        }
    }

private fun buildKubernetesRunner(
    alias: String,
    cfg: RunnerConfig.Kubernetes,
    stateSink: RunnerStateSink,
    tracer: Tracer,
    instanceId: String,
    sysEnvBackedEnvNames: Set<String> = emptySet(),
): KubernetesRunner {
    val apiClient: ApiClient =
        if (cfg.kubeconfig != null) {
            Config.fromConfig(cfg.kubeconfig)
        } else {
            runCatching { Config.fromCluster() }.getOrElse { Config.defaultClient() }
        }
    val api = CoreV1Api(apiClient)
    val upstreamExec = KubernetesExec(apiClient)
    val execClient = KubernetesApiExecClient(upstreamExec)
    return KubernetesRunner(
        alias = alias,
        config = cfg,
        api = api,
        execClient = execClient,
        stateSink = stateSink,
        tracer = tracer,
        instanceId = instanceId,
        sysEnvBackedEnvNames = sysEnvBackedEnvNames,
    )
}

// A new SshClient per alias, not a shared one: SshRunner.defaultConnect sets
// client.serverKeyVerifier per config, which would race across aliases with
// different strictHostKeyChecking settings if the client were shared.
private fun buildSshRunner(
    alias: String,
    cfg: RunnerConfig.Ssh,
    sysEnvBackedEnvNames: Set<String> = emptySet(),
): SshRunner {
    require(!(cfg.strictHostKeyChecking && cfg.knownHostsPath == null)) {
        "ssh runner '$alias': strictHostKeyChecking=true requires knownHostsPath"
    }
    val client =
        SshClient.setUpDefaultClient().also {
            applySshClientTiming(it, cfg)
            it.start()
        }
    return SshRunner(alias = alias, config = cfg, client = client, sysEnvBackedEnvNames = sysEnvBackedEnvNames)
}
