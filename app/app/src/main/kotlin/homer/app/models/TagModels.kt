package homer.app.models

import kotlinx.serialization.Serializable

/**
 * Body of `POST /api/v1/callback/tags` — a single `key:value` subject tag a running
 * command declares against its own run. The run is identified by the callback token
 * alone (ADR-0019); there is deliberately no process id in the body. See ADR-0060.
 */
@Serializable
data class DeclareTagRequest(
    val tag: String,
)

/**
 * Response of `POST /api/v1/callback/tags` — the stored tag and the run's resulting
 * runtime-tag count. Re-declaring an existing tag collapses to the same set, so [count]
 * is idempotent across repeats. See ADR-0060.
 */
@Serializable
data class DeclareTagResponse(
    val tag: String,
    val count: Int,
)
