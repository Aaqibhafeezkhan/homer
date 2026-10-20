package homer.app.infra

class SysEnvResolver {
    private val pattern = Regex("@SYSENV:([A-Za-z_][A-Za-z0-9_]*)")

    fun resolve(value: String): String =
        pattern.replace(value) { matchResult ->
            val varName = matchResult.groupValues[1]
            System.getenv(varName)
                ?: error("System environment variable '$varName' not found (referenced as '@SYSENV:$varName')")
        }

    fun resolveMapValues(map: Map<String, String>): Map<String, String> = map.mapValues { (_, v) -> resolve(v) }

    /**
     * Names to redact for [map], for callers marking host-sourced values secret.
     * Call this on the *raw* map, before [resolveMapValues] — afterwards the
     * placeholders are gone.
     *
     * Two kinds of name, because [resolve] substitutes *inside* a string rather
     * than replacing the whole value. For `TOKEN: "@SYSENV:REPO_TOKEN"` the
     * resolved value is the credential itself, so the map key suffices. For
     * `HEADER: "Bearer @SYSENV:REPO_TOKEN"` it is not: the resolved value is
     * `Bearer <token>`, and output containing the bare token — a script that
     * stripped the prefix, a tool echoing just the credential — would match no
     * needle and reach telemetry in the clear. So the referenced host variable
     * names ride along too, and `SecretRedactor` falls back to the host env to
     * resolve them.
     */
    fun secretNamesFor(map: Map<String, String>): Set<String> =
        map.filterValues { pattern.containsMatchIn(it) }.keys +
            map.values.flatMap { value -> pattern.findAll(value).map { it.groupValues[1] } }
}
