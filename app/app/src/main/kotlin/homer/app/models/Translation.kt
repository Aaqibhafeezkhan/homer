package homer.app.models

/**
 * One harness stdout line read into the canonical [LlmEvent] model: what `LlmHarness.translate`
 * returns and what the stream readers (`translateStream`, `scanLlmAccounting`, `LlmEventStreamService`) consume.
 * It lives in the kernel because both the harness adapters (Executors) and those readers (Infra)
 * use it (ADR-0070, ADR-0083).
 */
sealed interface Translation {
    data class Events(
        val events: List<LlmEvent>,
    ) : Translation

    /** A line Homer does not act on. Counted, never an error. */
    data object Ignored : Translation

    /** A line of a known kind whose shape the adapter could not read — a schema-drift signal. */
    data class Malformed(
        val reason: String,
    ) : Translation
}
