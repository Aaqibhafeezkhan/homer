package homer.app.runners

import homer.app.infra.WorkspaceConfig
import io.opentelemetry.context.Context

/**
 * Execution backend that produces per-run sessions.
 * Implementations map agents to concrete execution environments
 * (local process, Kubernetes pod, ...).
 */
interface Runner {
    val alias: String

    /**
     * Whether commands run on a machine other than Homer's own host. The
     * dispatcher strips `parentEnvAllowlist` host-env keys from the session
     * env for a remote runner: those values describe Homer's host, not the
     * remote one. The same rule applies to a cross-runner `runnerCmd` step:
     * its target runner receives the requesting env minus the host layer.
     * See SERVER.md, `parentEnvAllowlist`, and ADR-0065.
     */
    val isRemote: Boolean

    /**
     * Operator-declared per-runner env (from the runner's `config.env`),
     * layered between `globalEnv` and agent `env` when the workspace config
     * is composed. See SERVER.md, "Remote runners".
     */
    val runnerEnv: Map<String, String>

    /**
     * Names of env keys in [runnerEnv] whose raw config values used `@SYSENV:`.
     * Used by [RunnerCmdExecutor] to augment the telemetry redactor with the
     * target runner's credentials when `command.runner` differs from the agent's
     * own runner. See ADR-0047 §12 rule 7. // CWE-200
     */
    val sysEnvBackedEnvNames: Set<String>

    suspend fun openSession(params: RunnerSessionParams): RunnerSession
}

data class RunnerSessionParams(
    val processId: Int,
    val agentName: String,
    val workspace: WorkspaceConfig,
    val env: Map<String, String>,
    val parentContext: Context,
)
