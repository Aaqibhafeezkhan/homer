package homer.app.models

/**
 * Shape rules for runner tags: what a runner serves and what an agent requests
 * through `runnerTags`. Distinct from run subject tags (`TagValidation`,
 * ADR-0060), which are `key:value` labels on a finished run. See ADR-0066.
 */
object RunnerTagValidation {
    val PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$")
    const val MAX_TAGS = 32

    /**
     * Validates [tags] and returns them as a set. [owner] prefixes every message
     * (e.g. `runner 'k8s-dev'`, `Agent 'deploy'`) so a config-load failure names
     * the offending block.
     */
    fun validate(
        owner: String,
        tags: Collection<String>,
    ): Set<String> {
        require(tags.size <= MAX_TAGS) { "$owner: at most $MAX_TAGS runner tags are allowed, got ${tags.size}" }
        tags.forEach { tag ->
            require(PATTERN.matches(tag)) { "$owner: runner tag '$tag' does not match $PATTERN" }
        }
        val set = tags.toSet()
        require(set.size == tags.size) { "$owner: duplicate runner tags in $tags" }
        return set
    }
}
