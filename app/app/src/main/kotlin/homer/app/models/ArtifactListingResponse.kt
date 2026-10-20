package homer.app.models

import kotlinx.serialization.Serializable

@Serializable
data class ArtifactEntry(
    val path: String,
    val size: Long,
    val modified: Long,
)

@Serializable
data class ArtifactListingResponse(
    val processId: Int,
    val artifactsDir: String,
    val files: List<ArtifactEntry>,
    /** True when the listing was capped and `files` omits some entries. */
    val truncated: Boolean = false,
)
