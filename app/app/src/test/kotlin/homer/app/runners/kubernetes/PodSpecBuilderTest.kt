package homer.app.runners.kubernetes

import homer.app.fixtures.TestFixtures
import homer.app.models.CapabilitiesSpec
import homer.app.models.ContainerSpec
import homer.app.models.ResourceSpec
import homer.app.models.RunnerConfig
import homer.app.models.SeccompProfileSpec
import homer.app.models.SecurityContextSpec
import homer.app.models.VolumeSource
import homer.app.models.VolumeSpec
import homer.app.runners.RunnerSessionParams
import io.opentelemetry.context.Context
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PodSpecBuilderTest {
    private val config =
        RunnerConfig.Kubernetes(
            namespace = "homer",
            image = "ubuntu:24.04",
            shell = "/bin/sh",
            resources = ResourceSpec(requests = mapOf("cpu" to "100m"), limits = mapOf("memory" to "2Gi")),
            volumes =
                listOf(
                    VolumeSpec(
                        name = "scripts",
                        mountPath = "/scripts",
                        readOnly = true,
                        source = VolumeSource.ConfigMap("homer-shared-scripts"),
                    ),
                ),
            initContainers =
                listOf(
                    ContainerSpec(
                        name = "chown",
                        image = "busybox:1.36",
                        command = listOf("sh", "-c", "chown -R 1000:1000 /work"),
                    ),
                ),
            imagePullSecrets = listOf("registry-secret"),
            serviceAccountName = "homer-runner",
        )

    @Test
    fun `mounts an emptyDir workspace at the fixed root with a workdir subPath mount`() {
        val pod = PodSpecBuilder("k8s-dev", config).build(params(), "homer-1-abcdef")

        val volume = pod.spec!!.volumes!!.single { it.name == PodSpecBuilder.WORKSPACE_VOLUME_NAME }
        assertNotNull(volume.emptyDir)
        assertNull(volume.persistentVolumeClaim)

        val main = pod.spec!!.containers.single()
        val mounts = main.volumeMounts!!.filter { it.name == PodSpecBuilder.WORKSPACE_VOLUME_NAME }
        assertEquals(
            listOf(KubernetesRunnerSession.REMOTE_PROJECT_ROOT, KubernetesRunnerSession.REMOTE_WORKDIR),
            mounts.map { it.mountPath },
        )
        assertEquals("workdir", mounts[1].subPath)
        assertEquals(KubernetesRunnerSession.REMOTE_WORKDIR, main.workingDir)
    }

    @Test
    fun `builds fully-populated pod spec with all configured fields`() {
        val pod = PodSpecBuilder("k8s-dev", config).build(params(), "homer-1-abcdef")

        assertEquals("homer-1-abcdef", pod.metadata!!.name)
        assertEquals("homer", pod.metadata!!.namespace)

        val spec = pod.spec!!
        assertEquals("Never", spec.restartPolicy)
        assertEquals("homer-runner", spec.serviceAccountName)
        assertEquals(listOf("registry-secret"), spec.imagePullSecrets!!.map { it.name })

        val mainContainer = spec.containers.single()
        assertEquals(PodSpecBuilder.MAIN_CONTAINER_NAME, mainContainer.name)
        assertEquals("ubuntu:24.04", mainContainer.image)
        assertEquals(listOf("/bin/sh", "-c", "sleep infinity"), mainContainer.command)
        assertEquals("100m", mainContainer.resources!!.requests!!["cpu"]!!.toSuffixedString())
        assertEquals("2Gi", mainContainer.resources!!.limits!!["memory"]!!.toSuffixedString())
    }

    @Test
    fun `applies standard labels`() {
        val pod = PodSpecBuilder("k8s-dev", config).build(params(), "homer-1-abcdef")
        val labels = pod.metadata!!.labels!!
        assertEquals("homer", labels[PodLabels.MANAGED_BY])
        assertEquals("demo", labels[PodLabels.AGENT])
        assertEquals("1", labels[PodLabels.PROCESS_ID])
        assertEquals("k8s-dev", labels[PodLabels.RUNNER])
    }

    @Test
    fun `stamps the instance label when an instanceId is set`() {
        val pod = PodSpecBuilder("k8s-dev", config, instanceId = "inst-xyz").build(params(), "homer-1-abcdef")
        assertEquals("inst-xyz", pod.metadata!!.labels!![PodLabels.INSTANCE])
    }

    @Test
    fun `omits the instance label when instanceId is blank`() {
        val pod = PodSpecBuilder("k8s-dev", config).build(params(), "homer-1-abcdef")
        assertTrue(!pod.metadata!!.labels!!.containsKey(PodLabels.INSTANCE))
    }

    @Test
    fun `applies configured pod annotations`() {
        val annotated =
            config.copy(
                annotations =
                    mapOf(
                        "vault.hashicorp.com/agent-inject" to "true",
                        "vault.hashicorp.com/role" to "homer-runner",
                    ),
            )
        val pod = PodSpecBuilder("k8s-dev", annotated).build(params(), "homer-1-abcdef")

        assertEquals(
            mapOf(
                "vault.hashicorp.com/agent-inject" to "true",
                "vault.hashicorp.com/role" to "homer-runner",
            ),
            pod.metadata!!.annotations,
        )
    }

    @Test
    fun `omits annotations when none are configured`() {
        val pod = PodSpecBuilder("k8s-dev", config).build(params(), "homer-1-abcdef")
        assertNull(pod.metadata!!.annotations)
    }

    @Test
    fun `includes init containers and extra volumes`() {
        val pod = PodSpecBuilder("k8s-dev", config).build(params(), "homer-1-abcdef")
        assertEquals(1, pod.spec!!.initContainers!!.size)
        val initContainer = pod.spec!!.initContainers!!.first()
        assertEquals("chown", initContainer.name)
        val volumeNames = pod.spec!!.volumes!!.map { it.name }
        assertTrue(PodSpecBuilder.WORKSPACE_VOLUME_NAME in volumeNames)
        assertTrue("scripts" in volumeNames)

        val initMounts = initContainer.volumeMounts!!.filter { it.name == PodSpecBuilder.WORKSPACE_VOLUME_NAME }
        assertEquals(
            listOf(KubernetesRunnerSession.REMOTE_PROJECT_ROOT, KubernetesRunnerSession.REMOTE_WORKDIR),
            initMounts.map { it.mountPath },
        )
        assertEquals("workdir", initMounts[1].subPath)
    }

    @Test
    fun `maps every extra volume source onto its pod volume`() {
        fun volume(
            name: String,
            source: VolumeSource,
        ) = VolumeSpec(name = name, mountPath = "/$name", source = source)
        val withVolumes =
            config.copy(
                volumes =
                    listOf(
                        volume("host", VolumeSource.HostPath("/var/cache")),
                        volume("cm", VolumeSource.ConfigMap("scripts-cm")),
                        volume("sec", VolumeSource.Secret("api-secret")),
                        volume("claim", VolumeSource.Pvc("cache-pvc", readOnly = true)),
                        volume("scratch", VolumeSource.EmptyDir),
                    ),
            )

        val volumes =
            PodSpecBuilder("k8s-dev", withVolumes)
                .build(params(), "homer-1-abcdef")
                .spec!!
                .volumes!!
                .associateBy { it.name }

        assertEquals("/var/cache", volumes.getValue("host").hostPath!!.path)
        assertEquals("scripts-cm", volumes.getValue("cm").configMap!!.name)
        assertEquals("api-secret", volumes.getValue("sec").secret!!.secretName)
        assertEquals("cache-pvc", volumes.getValue("claim").persistentVolumeClaim!!.claimName)
        assertEquals(true, volumes.getValue("claim").persistentVolumeClaim!!.readOnly)
        assertNotNull(volumes.getValue("scratch").emptyDir)
        assertNull(volumes.getValue("scratch").hostPath)
    }

    @Test
    fun `does not bake the workspace env into the container spec`() {
        val pod =
            PodSpecBuilder("k8s-dev", config).build(
                params(env = mapOf("SECRET" to "s3cr3t", "HMR_WORK_DIR" to "/x")),
                "homer-1-abcdef",
            )
        assertTrue(
            pod.spec!!
                .containers
                .single()
                .env
                .isNullOrEmpty(),
        )
    }

    @Test
    fun `omits security context when not configured`() {
        val pod = PodSpecBuilder("k8s-dev", config).build(params(), "homer-1-abcdef")

        assertNull(pod.spec!!.securityContext)
        assertNull(
            pod.spec!!
                .containers
                .first()
                .securityContext,
        )
        assertNull(
            pod.spec!!
                .initContainers!!
                .first()
                .securityContext,
        )
    }

    @Test
    fun `applies pod-level security context fields when configured`() {
        val withSc =
            config.copy(
                securityContext =
                    SecurityContextSpec(
                        runAsUser = 1000,
                        runAsGroup = 2000,
                        runAsNonRoot = true,
                        fsGroup = 3000,
                        seccompProfile = SeccompProfileSpec(type = "RuntimeDefault"),
                    ),
            )

        val pod = PodSpecBuilder("k8s-dev", withSc).build(params(), "homer-1-abcdef")
        val podSc = pod.spec!!.securityContext!!

        assertEquals(1000L, podSc.runAsUser)
        assertEquals(2000L, podSc.runAsGroup)
        assertEquals(true, podSc.runAsNonRoot)
        assertEquals(3000L, podSc.fsGroup)
        assertEquals("RuntimeDefault", podSc.seccompProfile!!.type)
    }

    @Test
    fun `applies container-level security context to main and init containers`() {
        val withSc =
            config.copy(
                securityContext =
                    SecurityContextSpec(
                        runAsUser = 1000,
                        runAsGroup = 2000,
                        runAsNonRoot = true,
                        allowPrivilegeEscalation = false,
                        readOnlyRootFilesystem = true,
                        capabilities = CapabilitiesSpec(drop = listOf("ALL"), add = listOf("NET_BIND_SERVICE")),
                        seccompProfile = SeccompProfileSpec(type = "RuntimeDefault"),
                    ),
            )

        val pod = PodSpecBuilder("k8s-dev", withSc).build(params(), "homer-1-abcdef")
        val mainSc =
            pod.spec!!
                .containers
                .first()
                .securityContext!!
        val initSc =
            pod.spec!!
                .initContainers!!
                .first()
                .securityContext!!

        for (sc in listOf(mainSc, initSc)) {
            assertEquals(1000L, sc.runAsUser)
            assertEquals(2000L, sc.runAsGroup)
            assertEquals(true, sc.runAsNonRoot)
            assertEquals(false, sc.allowPrivilegeEscalation)
            assertEquals(true, sc.readOnlyRootFilesystem)
            assertEquals(listOf("ALL"), sc.capabilities!!.drop)
            assertEquals(listOf("NET_BIND_SERVICE"), sc.capabilities!!.add)
            assertEquals("RuntimeDefault", sc.seccompProfile!!.type)
        }
    }

    private fun params(
        workspace: homer.app.infra.WorkspaceConfig = TestFixtures.mockWorkspaceConfig(),
        env: Map<String, String> = emptyMap(),
    ): RunnerSessionParams =
        RunnerSessionParams(
            processId = 1,
            agentName = "demo",
            workspace = workspace,
            env = env,
            parentContext = Context.current(),
        )
}
