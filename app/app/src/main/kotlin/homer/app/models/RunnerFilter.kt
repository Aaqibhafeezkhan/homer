package homer.app.models

/**
 * What a queued run is waiting for: one runner by alias, or any runner whose
 * tags cover the requested set. See ADR-0066. [Tags] validates tag shape at construction time.
 */
sealed interface RunnerFilter {
    fun describe(): String

    data class Alias(
        val alias: String,
    ) : RunnerFilter {
        init {
            require(alias.isNotBlank()) { "runner alias must not be blank" }
        }

        override fun describe(): String = "runner '$alias'"
    }

    data class Tags(
        val tags: Set<String>,
    ) : RunnerFilter {
        init {
            require(tags.isNotEmpty()) { "runner tags filter must not be empty" }
            RunnerTagValidation.validate("runner tags filter", tags)
        }

        override fun describe(): String = "tags ${tags.sorted()}"
    }
}
