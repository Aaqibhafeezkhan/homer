package homer.app.config

import homer.app.models.LlmPriceConfig

/**
 * Resolves `@SYSENV:VAR` placeholders in `llmPrice.sessionSpend.headers`. Null-safe.
 *
 * A public top-level function (rather than `internal` in `ConfigValidation.kt`) so it can be
 * shared with `agentdebug`, which is a separate Gradle module and cannot see `internal`
 * members of `:app`.
 */
fun resolveLlmPrice(
    llmPrice: LlmPriceConfig?,
    resolve: (String) -> String,
): LlmPriceConfig? = llmPrice?.copy(sessionSpend = llmPrice.sessionSpend?.withResolvedHeaders(resolve))
