package homer.app.infra

import homer.app.EnvConstants.ARTIFACTS_DIR
import homer.app.EnvConstants.CALLBACK_TOKEN
import homer.app.EnvConstants.INVOCATION_CHAIN
import homer.app.EnvConstants.PARENT_PROCESS_ID
import homer.app.EnvConstants.PROCESS_ID
import homer.app.EnvConstants.ROOT_PROCESS_ID
import homer.app.EnvConstants.WORK_DIR
import homer.app.exceptions.WorkspaceException
import java.nio.file.Path
import kotlin.io.path.Path
import homer.app.models.Workspace as WorkspaceAgentConfig

class WorkspaceConfigFactory(
    private val workspaceRoot: String,
    private val globalEnv: Map<String, String>,
    private val textCompiler: TextCompiler,
    private val parentEnvAllowlist: List<String> = emptyList(),
    // Injected for testability; production wiring passes `System::getenv`.
    private val hostEnvSupplier: () -> Map<String, String> = { emptyMap() },
    private val maxOutputBytes: Long = Long.MAX_VALUE,
    // `globalEnv` keys the operator sourced from the host env via `@SYSENV:`.
    // Folded into every run's redacted set: pulling a value out of the host env
    // is the operator declaring it a credential, and Homer must not export it.
    private val sysEnvBackedGlobalEnvNames: Set<String> = emptySet(),
    // Same contract, per runner alias: names behind a runner's `env` whose raw
    // value referenced `@SYSENV:`. Resolved by alias in createWorkspaceConfig.
    private val sysEnvBackedRunnerEnvNames: Map<String, Set<String>> = emptyMap(),
) {
    fun createWorkspaceConfig(
        processId: Int,
        agentEnv: Map<String, String>,
        lockKeyTemplate: String,
        queryParams: Map<String, String>,
        workspaceAgentConfig: WorkspaceAgentConfig,
        invocationChain: List<String> = emptyList(),
        ancestorLockKeys: List<String> = emptyList(),
        secretEnvNames: Set<String> = emptySet(),
        parentProcessId: Int? = null,
        rootProcessId: Int? = null,
        // Per-runner env from the agent's RunnerConfig; layered between
        // globalEnv and agentEnv. Remote runners carry their host's values here.
        runnerEnv: Map<String, String> = emptyMap(),
        // False for remote runners: allowlisted host env keys describe Homer's
        // own host, not the remote one, and must not be forwarded. See
        // SERVER.md's `parentEnvAllowlist` section.
        includeParentEnv: Boolean = true,
        // Resolves [sysEnvBackedRunnerEnvNames]; the factory holds the alias-keyed
        // map because the `@SYSENV:` placeholders are resolved before it is built.
        runnerAlias: String = "",
    ): WorkspaceConfig {
        // A blank alias silently drops every @SYSENV:-backed runner env name from
        // the redacted set. The production call site always passes runner.alias (the
        // queued runner's alias); guard against a future call site that omits it while
        // runner env names exist.
        require(runnerAlias.isNotBlank() || sysEnvBackedRunnerEnvNames.isEmpty()) {
            "runnerAlias must not be blank when sysEnvBackedRunnerEnvNames is non-empty"
        }
        val hostEnv = hostEnvSupplier()
        val env =
            composeEnv(
                hostEnv,
                includeParentEnv,
                runnerEnv,
                agentEnv,
                queryParams,
                processId,
                invocationChain,
                parentProcessId,
                rootProcessId,
            )

        val serverRoot = Path(textCompiler.replaceArguments(workspaceRoot, globalEnv))
        val requestedRoot =
            serverRoot.resolve(textCompiler.replaceArguments(workspaceAgentConfig.path, env))
        val projectRoot = resolveProjectRoot(serverRoot, requestedRoot, workspaceAgentConfig.path)
        val artifacts: Path = projectRoot.resolve("artifacts")
        val workdir: Path = projectRoot.resolve("workdir")
        return WorkspaceConfig(
            serverRoot = serverRoot,
            projectRoot = projectRoot,
            artifacts = artifacts,
            workdir = workdir,
            env =
                env +
                    buildMap {
                        put(ARTIFACTS_DIR, artifacts.toString())
                        put(WORK_DIR, workdir.toString())
                    },
            clearAfterFinish = workspaceAgentConfig.cleanAfterFinish,
            lockKey = textCompiler.replaceArguments(lockKeyTemplate, env),
            ancestorLockKeys = ancestorLockKeys,
            maxOutputBytes = maxOutputBytes,
            parentEnvLayerNames = if (includeParentEnv) parentEnvLayerNames(hostEnv, env) else emptySet(),
            // Union, not replace: the agent's declared `secret: true` inputs plus
            // every host-sourced global, plus the per-process callback token Homer
            // mints itself (appended to env after this call — the redactor resolves
            // names to values at redact time, so a name registered here still hits).
            secretEnvNames =
                secretEnvNames +
                    sysEnvBackedGlobalEnvNames +
                    (sysEnvBackedRunnerEnvNames[runnerAlias] ?: emptySet()) +
                    CALLBACK_TOKEN,
        )
    }

    /**
     * The run's env, lowest precedence first: the allowlisted host env (only when
     * [includeParentEnv]), globalEnv, [runnerEnv], [agentEnv], [queryParams], then the
     * `HMR_*` ids Homer sets itself.
     */
    private fun composeEnv(
        hostEnv: Map<String, String>,
        includeParentEnv: Boolean,
        runnerEnv: Map<String, String>,
        agentEnv: Map<String, String>,
        queryParams: Map<String, String>,
        processId: Int,
        invocationChain: List<String>,
        parentProcessId: Int?,
        rootProcessId: Int?,
    ): Map<String, String> =
        buildMap {
            // Lowest precedence: only the operator-allowlisted host env keys.
            if (includeParentEnv) {
                parentEnvAllowlist.forEach { key ->
                    hostEnv[key]?.let { put(key, it) }
                }
            }
            putAll(globalEnv)
            putAll(runnerEnv)
            putAll(agentEnv)
            putAll(queryParams)
            put(PROCESS_ID, processId.toString())
            put(INVOCATION_CHAIN, invocationChain.joinToString(","))
            // Cleared at the root, not just skipped: an agent `env:` entry or an
            // allowlisted host variable under this name must not pose as a parent.
            if (parentProcessId != null) put(PARENT_PROCESS_ID, parentProcessId.toString()) else remove(PARENT_PROCESS_ID)
            // Same removal rule as the parent id: absent — not empty — at the root.
            if (rootProcessId != null) put(ROOT_PROCESS_ID, rootProcessId.toString()) else remove(ROOT_PROCESS_ID)
        }

    /**
     * The per-run directory [requestedRoot] names (from the agent's [workspacePath]
     * template), refused unless it is a real, symlink-free subdirectory of [serverRoot].
     */
    private fun resolveProjectRoot(
        serverRoot: Path,
        requestedRoot: Path,
        workspacePath: String,
    ): Path {
        val projectRoot: Path =
            try {
                PathSafety.assertContained(serverRoot, requestedRoot)
            } catch (e: SecurityException) {
                throw WorkspaceException(
                    "workspace path '$workspacePath' resolves outside the workspace root",
                    e,
                )
            }
        // Strict descent: a template (or a query-param value) that normalizes back
        // to the server root itself — e.g. "{{HMR_PROCESS_ID}}/.." or "" — passes
        // containment (root contains itself) but would make projectRoot == serverRoot,
        // so a later purge/eviction deleting projectRoot would wipe every run's
        // workspace. Require a real subdirectory. Security follow-up; root §12.4.
        //
        // Compare both the canonical and lexical forms: once the workspace root
        // exists on disk, assertContained returns its toRealPath() (symlink-resolved),
        // while serverRoot itself is never canonicalized — so on a host where
        // workspaceRootPath sits behind a symlink (e.g. /tmp -> /private/tmp on
        // Darwin/CI), a naive `projectRoot == serverRoot` silently fails to fire.
        val normalizedServerRoot = serverRoot.toAbsolutePath().normalize()
        if (projectRoot == normalizedServerRoot || requestedRoot.toAbsolutePath().normalize() == normalizedServerRoot) {
            throw WorkspaceException(
                "workspace path '$workspacePath' must resolve to a subdirectory of the workspace root, not the root itself",
            )
        }
        // Reject a per-run path reached through a symlink component: assertContained
        // follows symlinks and only checks the target stays in root, so a symlink an
        // earlier same-UID run plants at a predictable future per-run path would alias
        // two runs onto one directory across owner boundaries. §12.
        // Pass the lexical requestedRoot (not the symlink-resolved projectRoot) so the
        // walk lstat's each real component under serverRoot.
        try {
            PathSafety.assertNoSymlinkComponentsBelow(serverRoot, requestedRoot)
        } catch (e: SecurityException) {
            throw WorkspaceException(
                "workspace path '$workspacePath' resolves through a symlink; " +
                    "per-run workspace paths must not traverse symlinks",
                e,
            )
        }
        return projectRoot
    }

    /**
     * Allowlist keys still holding the host value they were layered with —
     * a higher layer (globalEnv/runnerEnv/agentEnv) overrode the rest. This
     * set, not the raw allowlist, is what a cross-runner step strips before
     * handing the env to a remote target: an explicitly re-set value is the
     * operator's own and must survive.
     */
    private fun parentEnvLayerNames(
        hostEnv: Map<String, String>,
        env: Map<String, String>,
    ): Set<String> = parentEnvAllowlist.filterTo(mutableSetOf()) { key -> hostEnv[key] != null && env[key] == hostEnv[key] }
}

data class WorkspaceConfig(
    val serverRoot: Path,
    val projectRoot: Path,
    val artifacts: Path,
    val workdir: Path,
    val env: Map<String, String>,
    val clearAfterFinish: Boolean,
    val lockKey: String,
    // Lock keys held by ancestors in this run's invocation chain; used for
    // acceptParentLock pass-through and forwarded to descendants by RunAgentExecutor.
    val ancestorLockKeys: List<String> = emptyList(),
    val maxOutputBytes: Long = Long.MAX_VALUE,
    // Env var names whose values should be redacted from telemetry (see
    // LlmHarnessExecutor's SecretRedactor use); populated from `Param.secret` inputs.
    val secretEnvNames: Set<String> = emptySet(),
    // Allowlisted host-env keys still carrying the host value they were layered
    // with. RunnerCmdExecutor strips exactly these before handing this env to a
    // remote runnerCmd target — the host values describe Homer's machine, not
    // the target's (same rule that keeps them out of a remote run's composed
    // env; ADR-0065). Keys the operator re-set via globalEnv/runnerEnv/agentEnv
    // are absent and survive the strip.
    val parentEnvLayerNames: Set<String> = emptySet(),
)
