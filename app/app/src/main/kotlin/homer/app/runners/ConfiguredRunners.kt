package homer.app.runners

/**
 * The configured runners by alias, as the raw backends: what a maintenance pass over them (the
 * Kubernetes startup sweep) reads. Never a run path — a run reaches a runner through the claim
 * queue (`RunnerRegistry.forAgent`). Implemented by `RunnerRegistry`. See ADR-0066, ADR-0083.
 */
interface ConfiguredRunners {
    fun aliases(): Set<String>

    /** The raw backend configured under [alias]; throws for an unknown alias. */
    fun get(alias: String): Runner
}
