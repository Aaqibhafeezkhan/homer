package homer.app.runners.local

import homer.app.EnvConstants
import homer.app.runners.Runner
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams

/**
 * Built-in runner that executes commands on Homer's own host via
 * LocalShellExecutor. Always registered under the alias "local".
 *
 * When [defaultApiUrl] is set, sessions contribute it as `HMR_API_URL`
 * unless the workspace env already provides one (operator `globalEnv` /
 * agent `env` wins) — local commands like the `homer-ask` helper then
 * reach Homer's own API without configuration.
 */
class LocalRunner(
    private val defaultApiUrl: String? = null,
) : Runner {
    override val alias: String = ALIAS

    override val isRemote: Boolean = false

    override val runnerEnv: Map<String, String> = emptyMap()

    override val sysEnvBackedEnvNames: Set<String> = emptySet()

    override suspend fun openSession(params: RunnerSessionParams): RunnerSession =
        LocalRunnerSession(
            workdir = params.workspace.workdir,
            maxOutputBytes = params.workspace.maxOutputBytes,
            runnerAlias = alias,
            env =
                if (defaultApiUrl != null && EnvConstants.API_URL !in params.env) {
                    mapOf(EnvConstants.API_URL to defaultApiUrl)
                } else {
                    emptyMap()
                },
        )

    companion object {
        const val ALIAS = "local"
    }
}
