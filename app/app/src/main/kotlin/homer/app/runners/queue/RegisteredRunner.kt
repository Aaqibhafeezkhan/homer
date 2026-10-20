package homer.app.runners.queue

import homer.app.runners.Runner

/** A configured runner with its claim-queue facets (`tags`, `concurrency`; null = unbounded). */
data class RegisteredRunner(
    val runner: Runner,
    val tags: Set<String> = emptySet(),
    val concurrency: Int? = null,
)
