package homer.app.runners

/**
 * What `RunnerCmdExecutor` needs to run a command on another runner: the queued view of the runner a
 * `runnerCmd` names, which every run path must go through. Implemented by [RunnerRegistry]. See
 * ADR-0034 and ADR-0083.
 */
fun interface RunnerLookup {
    /** The queued runner for [alias], under the registry's default claim timeout; throws for an unknown alias. */
    fun forAlias(alias: String): Runner
}
