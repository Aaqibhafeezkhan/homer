package homer.app.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ParamType {
    // Legacy generation — deprecated (load-time WARN in AgentsService). Semantics
    // are frozen until the removal release: STRING rejects values containing a
    // space, NUMBER accepts only Int-parseable values. See ADR-0031.
    STRING,
    NUMBER,
    ANY,

    // Typed generation — lowercase in agent files. Value semantics + the
    // constraint fields on Param are owned by ParamValidator.
    @SerialName("string")
    STR,

    @SerialName("integer")
    INT,

    @SerialName("number")
    NUM,

    @SerialName("boolean")
    BOOL,
    ;

    val isLegacy: Boolean
        get() = this == STRING || this == NUMBER || this == ANY

    /** The spelling used in agent files — for user-facing error messages. */
    val wireName: String
        get() =
            when (this) {
                STR -> "string"
                INT -> "integer"
                NUM -> "number"
                BOOL -> "boolean"
                else -> name
            }
}

@Serializable
enum class ParamSource {
    @SerialName("query")
    QUERY,

    @SerialName("body")
    BODY,

    @SerialName("header")
    HEADER,
}

@Serializable
data class Param(
    val paramName: String,
    val envName: String = paramName,
    val src: ParamSource = ParamSource.QUERY,
    val type: ParamType = ParamType.STRING,
    val required: Boolean = true,
    val transformer: Transformer = Transformer.Pass,
    // Redact this input's value in API responses and telemetry. The value
    // still reaches the run's env and is stored in the server database
    // (retry / dispatch re-fire need it).
    val secret: Boolean = false,
    // Typed-generation constraints (lowercase types only). Modeled as strings;
    // ParamValidator interprets them per type. Applicability and well-formedness
    // are checked at agent load (ParamValidator.validateDeclaration).
    val values: List<String>? = null,
    val pattern: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val default: String? = null,
)

@Serializable
sealed class Transformer {
    @Serializable
    @SerialName("pass")
    object Pass : Transformer()

    @Serializable
    @SerialName("jsonPath")
    data class JsonPath(
        val path: String,
    ) : Transformer()
}
