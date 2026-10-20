package homer.app.runners.kubernetes

import homer.app.models.CapabilitiesSpec
import homer.app.models.ContainerSpec
import homer.app.models.ResourceSpec
import homer.app.models.RunnerConfig
import homer.app.models.SeccompProfileSpec
import homer.app.models.SecurityContextSpec
import homer.app.models.VolumeMountSpec
import homer.app.models.VolumeSource
import homer.app.models.VolumeSpec
import homer.app.runners.RunnerSessionParams
import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.models.V1Capabilities
import io.kubernetes.client.openapi.models.V1Container
import io.kubernetes.client.openapi.models.V1EnvVar
import io.kubernetes.client.openapi.models.V1LocalObjectReference
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodSecurityContext
import io.kubernetes.client.openapi.models.V1PodSpec
import io.kubernetes.client.openapi.models.V1ResourceRequirements
import io.kubernetes.client.openapi.models.V1SeccompProfile
import io.kubernetes.client.openapi.models.V1SecurityContext
import io.kubernetes.client.openapi.models.V1Volume
import io.kubernetes.client.openapi.models.V1VolumeMount

/** Label keys Homer uses on every pod it creates. */
object PodLabels {
    const val MANAGED_BY = "app.kubernetes.io/managed-by"
    const val MANAGED_BY_VALUE = "homer"
    const val AGENT = "homer.app/agent"
    const val PROCESS_ID = "homer.app/process-id"
    const val RUNNER = "homer.app/runner"

    /** Stable per-instance id ([ServerMetaRepository]); scopes the startup sweep to this instance's pods. */
    const val INSTANCE = "homer.app/instance"

    /** Set on a pod kept via `keepPodOnFailure`; the startup sweep skips pods carrying it. */
    const val KEPT = "homer.app/kept"
}

class PodSpecBuilder(
    private val runnerAlias: String,
    private val config: RunnerConfig.Kubernetes,
    private val instanceId: String = "",
) {
    fun build(
        params: RunnerSessionParams,
        podName: String,
    ): V1Pod {
        val workspaceMounts = workspaceMounts()
        val containerSc = config.securityContext?.let(::toContainerSecurityContext)
        return V1Pod()
            .metadata(buildMetadata(params, podName))
            .spec(buildPodSpec(workspaceMounts, containerSc))
    }

    private fun buildMetadata(
        params: RunnerSessionParams,
        podName: String,
    ): V1ObjectMeta {
        val labels =
            buildMap {
                put(PodLabels.MANAGED_BY, PodLabels.MANAGED_BY_VALUE)
                put(PodLabels.AGENT, params.agentName)
                put(PodLabels.PROCESS_ID, params.processId.toString())
                put(PodLabels.RUNNER, runnerAlias)
                // Stamp the instance id only when set, so the sweep can scope to it.
                // Blank (agentdebug / unconfigured) keeps today's label set exactly.
                if (instanceId.isNotBlank()) put(PodLabels.INSTANCE, instanceId)
            }
        // Operator-declared passthrough (sidecar injectors, scrape config); null
        // when unconfigured so an existing deployment's pod metadata is unchanged.
        val annotations = config.annotations.takeIf { it.isNotEmpty() }
        return V1ObjectMeta()
            .name(podName)
            .namespace(config.namespace)
            .labels(labels)
            .annotations(annotations)
    }

    private fun buildMainContainer(
        workspaceMounts: List<V1VolumeMount>,
        containerSc: V1SecurityContext?,
    ): V1Container =
        V1Container()
            .name(MAIN_CONTAINER_NAME)
            .image(config.image)
            .command(listOf(config.shell, "-c", "sleep infinity"))
            .workingDir(KubernetesRunnerSession.REMOTE_WORKDIR)
            .resources(toResourceRequirements(config.resources))
            .volumeMounts(workspaceMounts + config.volumes.map(::toVolumeMount))
            .securityContext(containerSc)

    private fun buildPodSpec(
        workspaceMounts: List<V1VolumeMount>,
        containerSc: V1SecurityContext?,
    ): V1PodSpec {
        val initContainers =
            config.initContainers.takeIf { it.isNotEmpty() }?.map { spec ->
                toInitContainer(spec, workspaceMounts, containerSc)
            }
        return V1PodSpec()
            .restartPolicy("Never")
            .containers(listOf(buildMainContainer(workspaceMounts, containerSc)))
            .initContainers(initContainers)
            .volumes(listOf(workspaceVolume()) + config.volumes.map(::toVolume))
            .serviceAccountName(config.serviceAccountName)
            .imagePullSecrets(
                config.imagePullSecrets.takeIf { it.isNotEmpty() }?.map { V1LocalObjectReference().name(it) },
            ).securityContext(config.securityContext?.let(::toPodSecurityContext))
    }

    private fun workspaceMounts(): List<V1VolumeMount> =
        listOf(
            V1VolumeMount()
                .name(WORKSPACE_VOLUME_NAME)
                .mountPath(KubernetesRunnerSession.REMOTE_PROJECT_ROOT),
            // Second mount of the same volume: kubelet creates the subPath dir,
            // so the container's workingDir exists at start (runc rejects a
            // missing cwd).
            V1VolumeMount()
                .name(WORKSPACE_VOLUME_NAME)
                .mountPath(KubernetesRunnerSession.REMOTE_WORKDIR)
                .subPath("workdir"),
        )

    private fun workspaceVolume(): V1Volume =
        V1Volume()
            .name(WORKSPACE_VOLUME_NAME)
            .emptyDir(
                io.kubernetes.client.openapi.models
                    .V1EmptyDirVolumeSource(),
            )

    private fun toEnvVars(env: Map<String, String>): List<V1EnvVar> = env.map { (k, v) -> V1EnvVar().name(k).value(v) }

    private fun toResourceRequirements(spec: ResourceSpec): V1ResourceRequirements =
        V1ResourceRequirements()
            .requests(spec.requests.mapValues { Quantity(it.value) })
            .limits(spec.limits.mapValues { Quantity(it.value) })

    private fun toVolume(spec: VolumeSpec): V1Volume {
        val v = V1Volume().name(spec.name)
        return when (val src = spec.source) {
            is VolumeSource.HostPath -> {
                v.hostPath(
                    io.kubernetes.client.openapi.models
                        .V1HostPathVolumeSource()
                        .path(src.path),
                )
            }

            is VolumeSource.ConfigMap -> {
                v.configMap(
                    io.kubernetes.client.openapi.models
                        .V1ConfigMapVolumeSource()
                        .name(src.name),
                )
            }

            is VolumeSource.Secret -> {
                v.secret(
                    io.kubernetes.client.openapi.models
                        .V1SecretVolumeSource()
                        .secretName(src.secretName),
                )
            }

            is VolumeSource.Pvc -> {
                v.persistentVolumeClaim(
                    io.kubernetes.client.openapi.models
                        .V1PersistentVolumeClaimVolumeSource()
                        .claimName(src.claimName)
                        .readOnly(src.readOnly),
                )
            }

            is VolumeSource.EmptyDir -> {
                v.emptyDir(
                    io.kubernetes.client.openapi.models
                        .V1EmptyDirVolumeSource(),
                )
            }
        }
    }

    private fun toVolumeMount(spec: VolumeSpec): V1VolumeMount =
        V1VolumeMount()
            .name(spec.name)
            .mountPath(spec.mountPath)
            .readOnly(spec.readOnly)

    private fun toInitContainer(
        spec: ContainerSpec,
        workspaceMounts: List<V1VolumeMount>,
        securityContext: V1SecurityContext?,
    ): V1Container =
        V1Container()
            .name(spec.name)
            .image(spec.image)
            .command(spec.command.ifEmpty { null })
            .args(spec.args.ifEmpty { null })
            .env(toEnvVars(spec.env))
            .volumeMounts(
                workspaceMounts + spec.volumeMounts.map(::toInitVolumeMount),
            ).securityContext(securityContext)

    private fun toPodSecurityContext(spec: SecurityContextSpec): V1PodSecurityContext =
        V1PodSecurityContext()
            .runAsUser(spec.runAsUser)
            .runAsGroup(spec.runAsGroup)
            .runAsNonRoot(spec.runAsNonRoot)
            .fsGroup(spec.fsGroup)
            .seccompProfile(spec.seccompProfile?.let(::toSeccompProfile))

    private fun toContainerSecurityContext(spec: SecurityContextSpec): V1SecurityContext =
        V1SecurityContext()
            .runAsUser(spec.runAsUser)
            .runAsGroup(spec.runAsGroup)
            .runAsNonRoot(spec.runAsNonRoot)
            .allowPrivilegeEscalation(spec.allowPrivilegeEscalation)
            .readOnlyRootFilesystem(spec.readOnlyRootFilesystem)
            .capabilities(spec.capabilities?.let(::toCapabilities))
            .seccompProfile(spec.seccompProfile?.let(::toSeccompProfile))

    private fun toCapabilities(spec: CapabilitiesSpec): V1Capabilities? {
        if (spec.add.isEmpty() && spec.drop.isEmpty()) return null
        return V1Capabilities()
            .add(spec.add.ifEmpty { null })
            .drop(spec.drop.ifEmpty { null })
    }

    private fun toSeccompProfile(spec: SeccompProfileSpec): V1SeccompProfile =
        V1SeccompProfile()
            .type(spec.type)
            .localhostProfile(spec.localhostProfile)

    private fun toInitVolumeMount(spec: VolumeMountSpec): V1VolumeMount =
        V1VolumeMount()
            .name(spec.name)
            .mountPath(spec.mountPath)
            .readOnly(spec.readOnly)
            .subPath(spec.subPath)

    companion object {
        const val WORKSPACE_VOLUME_NAME = "homer-workspace"
        const val MAIN_CONTAINER_NAME = "main"
    }
}
