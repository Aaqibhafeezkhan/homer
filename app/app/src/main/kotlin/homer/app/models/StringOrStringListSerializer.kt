package homer.app.models

/**
 * Accepts a field as either a scalar string (mapped to a single-element list) or a
 * list of strings (passed through unchanged). Internal representation is always
 * List<String>.
 *
 * Unlike [CmdArgsSerializer], a scalar maps to `listOf(content)` — it must NOT
 * POSIX-tokenize, or a path containing spaces would be wrongly split into multiple values.
 */
object StringOrStringListSerializer : ScalarOrStringListSerializer(
    "homer.app.models.StringOrStringList",
    subject = "value",
    elementPrefix = "",
) {
    override fun fromScalar(content: String): List<String> = listOf(content)
}
