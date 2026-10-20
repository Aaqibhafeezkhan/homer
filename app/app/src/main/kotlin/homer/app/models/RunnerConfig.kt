package homer.app.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How long Homer holds a pull runner's `claim` or `next` long-poll before answering 204.
 * `RunnerProtocol.POLL_SEC` is defined as this, so the heartbeat floor below and the protocol
 * cannot drift apart.
 */
const val RUNNER_LONG_POLL_SEC = 30

/**
 * Per-alias runner backend configuration. Declared by operators in
 * AppConfig.runners. The built-in "local" runner is not a variant of
 * this class and must not appear in the map.
 */
@Serializable
sealed class RunnerConfig {
    @Serializable
    @SerialName("kubernetes")
    data class Kubernetes(
        val kubeconfig: String? = null,
        val namespace: String = "default",
        val image: String,
        val shell: String = "/bin/sh",
        val resources: ResourceSpec = ResourceSpec(),
        val volumes: List<VolumeSpec> = emptyList(),
        val annotations: Map<String, String> = emptyMap(),
        val initContainers: List<ContainerSpec> = emptyList(),
        val podStartTimeoutSeconds: Long = 120,
        val keepPodOnFailure: Boolean = false,
        val keepPodTtlSeconds: Long = 3600,
        val homerApiUrl: String? = null,
        /** Per-runner env, layered between `globalEnv` and agent `env`. See SERVER.md, "Remote runners". */
        val env: Map<String, String> = emptyMap(),
        val imagePullSecrets: List<String> = emptyList(),
        val serviceAccountName: String? = null,
        val securityContext: SecurityContextSpec? = null,
        val syncExcludes: List<String> = emptyList(),
        val extraUploads: List<ExtraUploadSpec> = emptyList(),
        override val tags: List<String> = emptyList(),
        override val concurrency: Int? = null,
    ) : RunnerConfig(),
        RunnerQueueFacets {
        /** Same contract as Ssh.validateExtraUploads — see that KDoc and ADR-0042/0043. */
        internal fun validateExtraUploads(alias: String) = validateExtraUploadSpecs(alias, extraUploads)

        /** Same shape rules as Ssh.validateEnv — see that KDoc. */
        internal fun validateEnv(alias: String) = validateRunnerEnv(alias, env)
    }

    @Serializable
    @SerialName("ssh")
    data class Ssh(
        val host: String,
        val port: Int = 22,
        val user: String,
        val auth: SshAuth,
        val knownHostsPath: String? = null,
        val strictHostKeyChecking: Boolean = true,
        val remoteWorkspaceRoot: String,
        val homerApiUrl: String? = null,
        /** Per-runner env, layered between `globalEnv` and agent `env`. See SERVER.md, "Remote runners". */
        val env: Map<String, String> = emptyMap(),
        val shell: String = "/bin/sh",
        val connectTimeoutSeconds: Long = 30,
        val syncExcludes: List<String> = emptyList(),
        val extraUploads: List<ExtraUploadSpec> = emptyList(),
        /** MINA client idle timeout in seconds; `null` (the default) disables it. See SERVER.md. */
        val idleTimeoutSeconds: Long? = null,
        /** Client heartbeat interval in seconds; default `30`, `null` disables keepalives. */
        val heartbeatIntervalSeconds: Long? = 30,
        /**
         * Per-file SFTP transfer deadline in seconds; default `300`, `null`
         * disables the bound. See SERVER.md.
         */
        val sftpTransferTimeoutSeconds: Long? = 300,
        override val tags: List<String> = emptyList(),
        override val concurrency: Int? = null,
    ) : RunnerConfig(),
        RunnerQueueFacets {
        /**
         * Shape rules for extraUploads (relative remote, reserved paths,
         * uniqueness, non-HMR_ env). Alias-aware and invoked from AppConfig's
         * init — still parse-time for any decoded config — so violations name
         * the runner alias. See ADR-0042.
         */
        internal fun validateExtraUploads(alias: String) = validateExtraUploadSpecs(alias, extraUploads)

        /** Alias-aware shape rules for the per-runner `env` keys. */
        internal fun validateEnv(alias: String) = validateRunnerEnv(alias, env)

        /** Likewise alias-aware shape rules for the transport timing fields. */
        internal fun validateTransportTiming(alias: String) {
            idleTimeoutSeconds?.let {
                require(it >= 1) { "runner '$alias' idleTimeoutSeconds must be >= 1 when set; got $it" }
            }
            heartbeatIntervalSeconds?.let {
                require(it >= 1) { "runner '$alias' heartbeatIntervalSeconds must be >= 1 when set; got $it" }
            }
            sftpTransferTimeoutSeconds?.let {
                require(it >= 1) { "runner '$alias' sftpTransferTimeoutSeconds must be >= 1 when set; got $it" }
            }
        }

        /** `remoteWorkspaceRoot` must be a safe `rm -rf` target — see [assertSafeRemoteRoot]. */
        internal fun validateRemoteWorkspaceRoot(alias: String) =
            assertSafeRemoteRoot("runner '$alias' remoteWorkspaceRoot", remoteWorkspaceRoot, minDepth = 1)
    }

    /**
     * A trust domain for pull-mode runner instances (ADR-0066). Holds the token
     * instances authenticate with and the limits that bound them; instances
     * announce their own tags at registration, so this entry has no `tags` or
     * `concurrency` of its own.
     */
    @Serializable
    @SerialName("external")
    data class External(
        val token: String,
        /** Homer URL reachable from the instances; exposed to runs as HMR_API_URL. */
        val homerApiUrl: String? = null,
        /** Tags an instance may announce; `null` = any tag. A typo guard, not a gate. */
        val allowedTags: List<String>? = null,
        val heartbeatTimeoutSec: Long = 60,
        val maxInstances: Int = 32,
        val maxConcurrencyPerInstance: Int = 16,
        /** Per tar transfer (upload or download), in seconds. */
        val transferTimeoutSec: Long = 300,
        /** Largest tar an instance may post for one download op. §12 rule 4. */
        val maxTransferBytes: Long = 1L * 1024 * 1024 * 1024,
        /** Per-domain env, same contract as `Ssh.env` / `Kubernetes.env`. */
        val env: Map<String, String> = emptyMap(),
        /** Same contract as `Ssh.extraUploads` (ADR-0042), sent to whichever instance claims the run. */
        val extraUploads: List<ExtraUploadSpec> = emptyList(),
    ) : RunnerConfig() {
        init {
            require(token.isNotBlank()) { "external runner token must not be blank" }
        }

        companion object {
            /** Slack added to [RUNNER_LONG_POLL_SEC] for the `heartbeatTimeoutSec` floor. */
            internal const val HEARTBEAT_SLACK_SEC = 5
        }

        internal fun validateEnv(alias: String) = validateRunnerEnv(alias, env)

        /** Same contract as Ssh.validateExtraUploads — see that KDoc and ADR-0042. */
        internal fun validateExtraUploads(alias: String) = validateExtraUploadSpecs(alias, extraUploads)

        internal fun validateFacets(alias: String) {
            allowedTags?.let { RunnerTagValidation.validate("runner '$alias' allowedTags", it) }
            // touch() runs at poll start and an idle poll returns after POLL_SEC, leaving only
            // HEARTBEAT_SLACK_SEC for the next poll's RTT/GC. See ADR-0066.
            require(heartbeatTimeoutSec >= RUNNER_LONG_POLL_SEC + HEARTBEAT_SLACK_SEC) {
                val floor = RUNNER_LONG_POLL_SEC + HEARTBEAT_SLACK_SEC
                "runner '$alias' heartbeatTimeoutSec must be at least $floor " +
                    "(the ${RUNNER_LONG_POLL_SEC} s long-poll window plus $HEARTBEAT_SLACK_SEC s slack); got $heartbeatTimeoutSec"
            }
            require(maxInstances >= 1) { "runner '$alias' maxInstances must be >= 1; got $maxInstances" }
            require(maxConcurrencyPerInstance >= 1) {
                "runner '$alias' maxConcurrencyPerInstance must be >= 1; got $maxConcurrencyPerInstance"
            }
            require(transferTimeoutSec >= 1) { "runner '$alias' transferTimeoutSec must be >= 1; got $transferTimeoutSec" }
            require(maxTransferBytes >= 1) { "runner '$alias' maxTransferBytes must be >= 1; got $maxTransferBytes" }
            // Runs send their callback token here from outside Homer's network; the message never
            // echoes the URL, which may hold userinfo. CWE-319.
            homerApiUrl?.let {
                require(isHttpsOrLoopbackHttpUrl(it)) {
                    "runner '$alias' homerApiUrl must be https://, or http:// to a loopback host, " +
                        "without embedded credentials: runs send their callback token there"
                }
            }
        }

        override fun toString() =
            "External(token=${redactSecret(token)}, homerApiUrl=${homerApiUrl?.let(::stripUrlSecrets)}, allowedTags=$allowedTags, " +
                "heartbeatTimeoutSec=$heartbeatTimeoutSec, maxInstances=$maxInstances, " +
                "maxConcurrencyPerInstance=$maxConcurrencyPerInstance, transferTimeoutSec=$transferTimeoutSec, " +
                "maxTransferBytes=$maxTransferBytes, env=${env.keys}, extraUploads=${extraUploads.map { it.remote }})"
    }
}

/**
 * A remote path Homer will hand to `rm -rf` must be absolute, normalized (no
 * empty, `.` or `..` segments) and at least [minDepth] segments deep, so a
 * blank, `/` or traversing value can never widen a `cleanAfterFinish` sweep
 * to the host. Checked at config load for `remoteWorkspaceRoot` (depth ≥ 1)
 * and again on the composed project root before the command runs (depth ≥ 2).
 * [security_misconfiguration / CWE-732] — see ADR-0067.
 */
internal fun assertSafeRemoteRoot(
    owner: String,
    path: String,
    minDepth: Int,
) {
    require(path.startsWith("/")) { "$owner must be an absolute path; got '$path'" }
    val segments = path.split('/').drop(1)
    require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
        "$owner must be normalized (no empty, '.' or '..' segments); got '$path'"
    }
    require(segments.size >= minDepth) { "$owner must be at least $minDepth segment(s) below '/'; got '$path'" }
}

/**
 * Facets of the built-in `local` runner as a claim-queue consumer
 * (`server.localRunner`). Same rules as `tags`/`concurrency` on `ssh` and
 * `kubernetes`. See ADR-0066.
 */
@Serializable
data class LocalRunnerConfig(
    val tags: List<String> = emptyList(),
    val concurrency: Int? = null,
) {
    init {
        validateRunnerQueueFacets("server.localRunner", tags, concurrency)
    }
}

/** The claim-queue facets an `ssh` or `kubernetes` runner declares in its own entry (ADR-0066). */
sealed interface RunnerQueueFacets {
    /** Tags this runner serves for tag-filtered runs; empty = alias-addressed only. See ADR-0066. */
    val tags: List<String>

    /** Permits for the claim queue; `null` = unbounded (the historical behaviour). See ADR-0066. */
    val concurrency: Int?
}

/** Shape rules for [RunnerQueueFacets.tags] and [RunnerQueueFacets.concurrency], naming the runner [alias]. */
internal fun RunnerQueueFacets.validateQueueFacets(alias: String) = validateRunnerQueueFacets("runner '$alias'", tags, concurrency)

/** Shared shape rules for a runner's claim-queue facets (ADR-0066). */
internal fun validateRunnerQueueFacets(
    owner: String,
    tags: List<String>,
    concurrency: Int?,
) {
    RunnerTagValidation.validate(owner, tags)
    concurrency?.let {
        require(it >= 1) { "$owner: concurrency must be >= 1 when set; got $it" }
    }
}

/** Shape rules shared by every runner that declares extraUploads (ADR-0042). */
internal fun validateExtraUploadSpecs(
    alias: String,
    extraUploads: List<ExtraUploadSpec>,
) {
    extraUploads.forEach { it.validate(alias) }
    val dupRemotes = extraUploads.groupBy { it.remote }.filterValues { it.size > 1 }.keys
    require(dupRemotes.isEmpty()) { "runner '$alias' extraUploads has duplicate remote paths: $dupRemotes" }
    val dupEnvs =
        extraUploads
            .mapNotNull { it.env }
            .groupBy { it }
            .filterValues { it.size > 1 }
            .keys
    require(dupEnvs.isEmpty()) { "runner '$alias' extraUploads has duplicate env names: $dupEnvs" }
}

/**
 * Shape rules for a runner's per-runner `env` map: keys must be non-blank and
 * must not use the reserved `HMR_` prefix — the same rule `extraUploads.env`
 * enforces on its single name. Alias-aware and invoked from AppConfig's init,
 * so violations name the runner.
 */
internal fun validateRunnerEnv(
    alias: String,
    env: Map<String, String>,
) {
    env.forEach { (key, _) ->
        require(key.isNotBlank()) { "runner '$alias' env keys must not be blank" }
        require(!key.startsWith("HMR_")) {
            "runner '$alias' env key '$key' must not use the reserved HMR_ prefix"
        }
    }
}

/**
 * One operator-declared host path materialized into every remote workspace on
 * this runner. `remote` is resolved against the remote projectRoot; `env`
 * (optional) is repointed at the remote copy via the createWorkspace()
 * override channel. See ADR-0042.
 */
@Serializable
data class ExtraUploadSpec(
    val local: String,
    val remote: String,
    val env: String? = null,
) {
    internal fun validate(alias: String) {
        require(local.isNotBlank()) { "runner '$alias' extraUploads.local must not be blank" }
        require(remote.isNotBlank()) { "runner '$alias' extraUploads.remote must not be blank" }
        require(!remote.startsWith("/")) { "runner '$alias' extraUploads.remote must be relative to the remote projectRoot; got '$remote'" }
        val segments = remote.split('/').filter { it.isNotEmpty() && it != "." }
        require(segments.isNotEmpty()) { "runner '$alias' extraUploads.remote must name a path; got '$remote'" }
        require(".." !in segments) { "runner '$alias' extraUploads.remote must not contain '..'; got '$remote'" }
        val normalized = segments.joinToString("/")
        // Only the canonical spelling is accepted: './'/'//'/trailing-slash
        // variants would evade duplicate detection and leak into env values.
        require(remote == normalized) {
            "runner '$alias' extraUploads.remote must be in normalized form '$normalized'; got '$remote'"
        }
        RESERVED_REMOTE_PATHS.forEach { reserved ->
            // Ancestors are reserved too: directory uploads merge into existing
            // dirs, so an extra targeting '.homer' would clobber '.homer/bin' etc.
            require(
                normalized != reserved &&
                    !normalized.startsWith("$reserved/") &&
                    !reserved.startsWith("$normalized/"),
            ) {
                "runner '$alias' extraUploads.remote '$remote' collides with reserved path '$reserved' (see ADR-0042)"
            }
        }
        env?.let {
            require(it.isNotBlank()) { "runner '$alias' extraUploads.env must not be blank when set" }
            require(!it.startsWith("HMR_")) { "runner '$alias' extraUploads.env must not use the reserved HMR_ prefix; got '$it'" }
        }
    }

    companion object {
        // workdir/artifacts are synced down; .homer/agent is the uploaded agent
        // dir; .homer/bin + .homer/mcp.json are the question tooling uploaded
        // with the workspace — an extra landing there would overwrite it.
        private val RESERVED_REMOTE_PATHS =
            listOf("workdir", "artifacts", ".homer/agent", ".homer/bin", ".homer/mcp.json")
    }
}

@Serializable
sealed class SshAuth {
    @Serializable
    @SerialName("key")
    data class KeyFile(
        val privateKeyPath: String,
        val passphrase: String? = null,
    ) : SshAuth()

    @Serializable
    @SerialName("agent")
    object Agent : SshAuth()
}

/**
 * Pod- and container-level security context. Single source of truth so
 * fields that PSA enforces at both levels (runAsUser, runAsGroup,
 * runAsNonRoot, seccompProfile) cannot drift between them.
 */
@Serializable
data class SecurityContextSpec(
    val runAsUser: Long? = null,
    val runAsGroup: Long? = null,
    val runAsNonRoot: Boolean? = null,
    val fsGroup: Long? = null,
    val allowPrivilegeEscalation: Boolean? = null,
    val readOnlyRootFilesystem: Boolean? = null,
    val capabilities: CapabilitiesSpec? = null,
    val seccompProfile: SeccompProfileSpec? = null,
)

@Serializable
data class CapabilitiesSpec(
    val add: List<String> = emptyList(),
    val drop: List<String> = emptyList(),
)

@Serializable
data class SeccompProfileSpec(
    val type: String,
    val localhostProfile: String? = null,
)

@Serializable
data class ResourceSpec(
    val requests: Map<String, String> = emptyMap(),
    val limits: Map<String, String> = emptyMap(),
)

@Serializable
data class VolumeSpec(
    val name: String,
    val mountPath: String,
    val readOnly: Boolean = false,
    val source: VolumeSource,
)

@Serializable
sealed class VolumeSource {
    @Serializable
    @SerialName("hostPath")
    data class HostPath(
        val path: String,
    ) : VolumeSource()

    @Serializable
    @SerialName("configMap")
    data class ConfigMap(
        val name: String,
    ) : VolumeSource()

    @Serializable
    @SerialName("secret")
    data class Secret(
        val secretName: String,
    ) : VolumeSource()

    @Serializable
    @SerialName("pvc")
    data class Pvc(
        val claimName: String,
        val readOnly: Boolean = false,
    ) : VolumeSource()

    @Serializable
    @SerialName("emptyDir")
    object EmptyDir : VolumeSource()
}

@Serializable
data class ContainerSpec(
    val name: String,
    val image: String,
    val command: List<String> = emptyList(),
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val volumeMounts: List<VolumeMountSpec> = emptyList(),
)

@Serializable
data class VolumeMountSpec(
    val name: String,
    val mountPath: String,
    val readOnly: Boolean = false,
    val subPath: String? = null,
)
