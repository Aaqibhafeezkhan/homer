package homer.app.models

import kotlinx.serialization.json.JsonPrimitive

/**
 * Accepts `cmd` as either a scalar string (tokenized with POSIX-style quote handling)
 * or a list of strings (passed through unchanged). Internal representation is always
 * List<String>.
 */
object CmdArgsSerializer : ScalarOrStringListSerializer("homer.app.models.CmdArgs", subject = "cmd", elementPrefix = "cmd ") {
    override fun fromScalar(content: String): List<String> = CmdArgsTokenizer.tokenize(content)

    override fun acceptsJsonScalar(element: JsonPrimitive): Boolean = element.isString || element.content.isNotEmpty()
}
