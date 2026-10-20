package homer.app.runners.queue

import homer.app.runners.RunnerSession

/**
 * Session handed to a tag-filtered run: the serving runner's per-runner `env`
 * arrives through RunnerSession.env because the run's workspace env was composed
 * before the runner was known. Session-contributed values (e.g. HMR_API_URL) win
 * over the runner's config env. Alias-filtered runs never get this wrapper: their
 * runner env was layered by WorkspaceConfigFactory already. See ADR-0066.
 */
internal class TagRoutedSession(
    private val delegate: RunnerSession,
    runnerEnv: Map<String, String>,
) : RunnerSession by delegate {
    override val env: Map<String, String> = runnerEnv + delegate.env
}
