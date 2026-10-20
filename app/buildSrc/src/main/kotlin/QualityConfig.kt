import groovy.json.JsonSlurper

/** Reads `tools/quality/checks.json`, the one place the quality-check settings live (ADR-0082). */
object QualityConfig {
    /** The `threshold` of the `crap` check, or null when the file does not name one. */
    fun crapThreshold(checksJson: String): Double? {
        val checks = (JsonSlurper().parseText(checksJson) as? Map<*, *>)?.get("checks") as? List<*> ?: return null
        val crap = checks.filterIsInstance<Map<*, *>>().firstOrNull { it["name"] == "crap" } ?: return null
        return (crap["threshold"] as? Number)?.toDouble()
    }
}
