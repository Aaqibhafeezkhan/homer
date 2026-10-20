package homer.app.models

/**
 * Shared character sets and ceilings for run subject tags, so the four surfaces that
 * enforce them — the `fromInputs` derivation ([homer.app.infra.langfuseTraceTags]), agent-load
 * validation ([homer.app.services.AgentsService]), the runtime-tag callback route, and the `?tag=` query
 * cap on `/status/all` — cannot drift from one another. See ADR-0060.
 */
internal object TagValidation {
    const val MAX_TAG_VALUE_LENGTH = 128

    /**
     * A tag value: `^[A-Za-z0-9._-]{1,128}$` (ADR-0060 decision 4). Colon-free so a
     * `key:value` tag splits on its first `:` unambiguously, and `/`-free so a value needs
     * no escaping in a `?tag=` query and a path-shaped subject cannot masquerade as a tag —
     * a `group/repo` project path is dropped and must be tagged by numeric id instead.
     * Out-of-charset/oversized values are dropped, never truncated or mangled — a
     * rewritten value matches no filter and misrepresents the run.
     */
    val VALUE_PATTERN = Regex("^[A-Za-z0-9._-]{1,$MAX_TAG_VALUE_LENGTH}$")

    /**
     * A tag key — a `fromInputs` paramName or a `runtime` key: `^[A-Za-z0-9._-]{1,64}$`.
     * Colon-free like the value, and bounded so a stored `key:value` never exceeds
     * [MAX_TAG_QUERY_LENGTH] and stays URL-safe (denial_of_service / CWE-400).
     */
    val KEY_PATTERN = Regex("^[A-Za-z0-9._-]{1,64}$")

    /** Per-run ceiling on runtime-declared tags; a write past it is refused (400). §12 rule 4. */
    const val MAX_RUNTIME_TAGS_PER_RUN = 20

    /** Per-request ceiling on `?tag=` params on `/status/all`; over it is refused (400). §12 rule 4. */
    const val MAX_TAG_QUERY_PARAMS = 10

    /**
     * Longest a stored `key:value` tag can be — the runtime-key bound (64) plus the `:`
     * separator plus the value bound (128). A `?tag=` query value longer than this can
     * match no stored tag, so it is refused (400) before it reaches the DB bind, bounding
     * the per-value work a caller can force. §12 rule 4 (denial_of_service / CWE-400).
     */
    const val MAX_TAG_QUERY_LENGTH = 64 + 1 + MAX_TAG_VALUE_LENGTH

    /**
     * Shortest secret value the tag guards match against (write-time refusal and
     * read-time screen). A shorter secret cannot be protected by matching and would
     * refuse nearly every value (§12: availability is in scope) — the same floor and
     * reasoning as the artifact-stem screen. See ADR-0060.
     */
    const val SECRET_NEEDLE_MIN_LENGTH = MIN_SCREENED_SECRET_LENGTH
}
