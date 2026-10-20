// app/app/src/main/kotlin/homer/app/models/PurgeAfterParser.kt
package homer.app.models

object PurgeAfterParser {
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L
    private const val SECONDS_PER_DAY = 86_400L
    private const val SECONDS_PER_WEEK = 604_800L

    /** [field] names the config key in error messages; other duration fields reuse this grammar. */
    fun toSeconds(
        value: String,
        field: String = "purgeAfter",
    ): Long {
        if (value.isEmpty()) throw IllegalArgumentException("$field must not be empty")
        val unit = value.last()
        if (!unit.isLetter()) {
            // No unit suffix — mimic NumberFormatException as if parsing the full string
            throw NumberFormatException("For input string: \"$value\"")
        }
        val amount = value.dropLast(1).toLong() // throws NumberFormatException if not a number
        if (amount <= 0) throw IllegalArgumentException("$field amount must be positive, got $amount")
        return when (unit) {
            'm' -> amount * SECONDS_PER_MINUTE
            'h' -> amount * SECONDS_PER_HOUR
            'd' -> amount * SECONDS_PER_DAY
            'w' -> amount * SECONDS_PER_WEEK
            else -> throw IllegalArgumentException("Invalid $field unit '$unit'. Supported: m, h, d, w")
        }
    }
}
