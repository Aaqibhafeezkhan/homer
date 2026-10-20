package homer.app.infra

/**
 * Header names that an agent's Param(src = HEADER) cannot bind to.
 * Matched case-insensitively. Validated at agent scan time so
 * misconfiguration surfaces at startup, not per request.
 */
object ReservedHeaders {
    private val exact =
        setOf(
            "authorization",
            "host",
            "content-type",
            "content-length",
            "connection",
            "transfer-encoding",
            "upgrade",
            "cookie",
            "set-cookie",
        )
    private val prefixes = listOf("x-homer-", "x-forwarded-")

    fun isReserved(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in exact) return true
        return prefixes.any { lower.startsWith(it) }
    }
}
