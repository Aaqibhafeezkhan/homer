package homer.app.infra

import com.jayway.jsonpath.Configuration

/**
 * Text form of a value a JsonPath selected — the one conversion every call site shares, so the
 * `resultEnvParam`, input and `!<agent>` `queryParams` transformers cannot diverge.
 *
 * The provider hands back its own types: a Map for a JSON object and a List for a JSON array, whose
 * `toString()` is Java text (`{a=1}`), not JSON. Those are re-serialized compactly with the provider
 * that parsed them. Scalars keep their current text, so a string stays raw and a number or boolean
 * stays its JSON text. A JSON null or an absent path yields null and the caller omits the value.
 */
internal fun jsonPathText(value: Any?): String? =
    when (value) {
        null -> {
            null
        }

        is String -> {
            value
        }

        is Map<*, *>, is Collection<*>, is Array<*> -> {
            Configuration.defaultConfiguration().jsonProvider().toJson(value)
        }

        else -> {
            value.toString()
        }
    }
