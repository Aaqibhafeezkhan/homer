package homer.app.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

@Serializable
data class AppConfig(
    val server: Server = Server(),
    val agentsSearchPath: String,
    val workspaceRootPath: String,
    /**
     * Filesystem path to the SQLite database holding durable orchestrator state
     * (process records + operator questions). Created if absent. Required, no
     * default — like `agentsSearchPath`/`workspaceRootPath` — and
     * `@SYSENV:`-resolvable. In containers it MUST live on a persisted volume,
     * not the ephemeral container filesystem, or process history and open
     * questions are lost on restart. Interrupted in-flight runs are reconciled
     * to `UNKNOWN` on boot. See ADR-0020.
     */
    val databasePath: String,
    val langfuse: LangfuseConfig = LangfuseConfig(),
    val globalEnv: Map<String, String> = emptyMap(),
    /**
     * Host-JVM environment variable names that are layered underneath
     * `globalEnv` when composing a child process's environment. The
     * default keeps `PATH`, `HOME`, `LANG`, `TZ` available so typical
     * agents (`claude`, `bash`, locale-aware tools) work out of the
     * box; anything else from `System.getenv()` is dropped unless the
     * operator adds it here OR forwards it explicitly through
     * `globalEnv` / `agent.env` via `@SYSENV:`.
     *
     * Applies to the built-in `local` runner only: the values describe
     * Homer's own host, so they are never forwarded to a remote
     * (`ssh`/`kubernetes`) runner — supply per-host values in that
     * runner's `env` instead.
     *
     * Listed keys missing from the host env are silently skipped.
     * Audit P0 #2.
     */
    val parentEnvAllowlist: List<String> = listOf("PATH", "HOME", "LANG", "TZ"),
    val openTelemetryExport: OpenTelemetryExport = OpenTelemetryExport.LANGFUSE,
    val runners: Map<String, RunnerConfig> = emptyMap(),
    val costLimits: CostLimits = CostLimits(),
    /**
     * Optional pricing of Claude runs from sources other than the CLI's own estimate.
     * Null = price every attempt from `total_cost_usd`, exactly the historical behaviour.
     * See ADR-0061.
     */
    val llmPrice: LlmPriceConfig? = null,
    /**
     * Subject-tag keys queryable via `GET /api/v1/costs/by-tag/{key}`; empty = none
     * queryable. Validated against [homer.app.models.TagValidation.KEY_PATTERN] and
     * capped at [homer.app.models.TagValidation.MAX_TAG_QUERY_PARAMS]. See ADR-0062.
     */
    val costTrackedTagKeys: List<String> = emptyList(),
    /**
     * Runtime-only: names to redact for `globalEnv` — the keys whose *raw* value
     * referenced `@SYSENV:`, plus the host variables they referenced (a composite
     * like `"Bearer @SYSENV:TOKEN"` needs both). Not a config-file field —
     * populated by `Application` while it resolves the config, and used to seed
     * telemetry redaction, since a value an operator pulled from the host env is
     * by construction a secret. See `SysEnvResolver.secretNamesFor`.
     */
    @Transient
    val sysEnvBackedGlobalEnvNames: Set<String> = emptySet(),
    /**
     * Runtime-only, per runner alias: names to redact for that runner's `env` —
     * the same contract [sysEnvBackedGlobalEnvNames] provides for `globalEnv`.
     * Not a config-file field — populated by `Application` while it resolves the
     * config. See `SysEnvResolver.secretNamesFor`.
     */
    @Transient
    val sysEnvBackedRunnerEnvNames: Map<String, Set<String>> = emptyMap(),
) {
    init {
        require("local" !in runners.keys) {
            "Runner alias 'local' is reserved for the built-in local runner and must not appear in AppConfig.runners."
        }
        // Alias-aware so config-load violations name the runner (ADR-0042).
        runners.forEach { (alias, runner) ->
            when (runner) {
                is RunnerConfig.Ssh -> {
                    runner.validateExtraUploads(alias)
                    runner.validateTransportTiming(alias)
                    runner.validateEnv(alias)
                    runner.validateQueueFacets(alias)
                    runner.validateRemoteWorkspaceRoot(alias)
                }

                is RunnerConfig.Kubernetes -> {
                    runner.validateExtraUploads(alias)
                    runner.validateEnv(alias)
                    runner.validateQueueFacets(alias)
                }

                is RunnerConfig.External -> {
                    runner.validateExtraUploads(alias)
                    runner.validateEnv(alias)
                    runner.validateFacets(alias)
                }
            }
        }
        val domainTokens = runners.values.filterIsInstance<RunnerConfig.External>().map { it.token }
        require(domainTokens.toSet().size == domainTokens.size) {
            "Two external runner domains share a token value; every domain needs its own token."
        }
        require(costTrackedTagKeys.size <= TagValidation.MAX_TAG_QUERY_PARAMS) {
            "costTrackedTagKeys must not exceed ${TagValidation.MAX_TAG_QUERY_PARAMS} entries, " +
                "found ${costTrackedTagKeys.size}."
        }
        costTrackedTagKeys.forEach { key ->
            require(TagValidation.KEY_PATTERN.matches(key)) {
                "costTrackedTagKeys entry '$key' does not match ${TagValidation.KEY_PATTERN}."
            }
        }
    }

    /**
     * Absolute path to [owner]'s durable LangGraph checkpoint directory: the
     * configured root (`server.langGraph.stateDir`, else a `langgraph`
     * subdirectory beside [databasePath]) plus a per-owner segment.
     *
     * The per-owner split is a security boundary, not a layout preference: a
     * thread id read from the run's `langgraph.json` artifact is agent-writable,
     * so a forged id must not be able to address another principal's
     * checkpoints. Scoping the store means a foreign id simply is not in the
     * database that gets opened. See ADR-0046.
     *
     * Owner is copied down run chains (ADR-0038), so resumes and continuations
     * of the same chain resolve to the same directory. Pure path computation —
     * does not create the directory.
     */
    fun resolveLangGraphStateDir(owner: String?): String {
        val root =
            server.langGraph.stateDir?.let { Path.of(it) }
                ?: (Path.of(databasePath).toAbsolutePath().parent ?: Path.of(".").toAbsolutePath())
                    .resolve("langgraph")
        return root
            .resolve(ownerStateSegment(owner))
            .toAbsolutePath()
            .normalize()
            .toString()
    }
}

/** Bucket for LangGraph state of runs with no owner (unsecured mode, agentdebug). */
private const val UNOWNED_STATE_SEGMENT = "_unowned"

/** Marks a hashed segment, keeping hashes in their own namespace. */
private const val HASHED_STATE_SEGMENT_PREFIX = "_h-"

/**
 * Filesystem-safe directory segment for a principal name, in a namespace where no
 * two distinct principals can meet.
 *
 * A segment is used verbatim — keeping the layout legible to operators — only when
 * it is lowercase, at most 64 characters, drawn from `[a-z0-9._-]`, and does not
 * start with `_` or `.`. Everything else is hashed under [HASHED_STATE_SEGMENT_PREFIX].
 * Those three rules each close a collision:
 *
 * - leading `_` is reserved, so no principal can reach [UNOWNED_STATE_SEGMENT] or
 *   impersonate a hashed segment;
 * - lowercase-only keeps names differing only in case apart on a case-insensitive
 *   filesystem (macOS dev machines);
 * - the character set plus the leading-`.` rule keeps `..`, `.`, and anything
 *   holding a separator from escaping the root.
 *
 * The hash is 128 bits, so distinct hashed names do not meet by birthday either.
 */
private fun ownerStateSegment(owner: String?): String {
    if (owner.isNullOrEmpty()) return UNOWNED_STATE_SEGMENT
    val verbatim =
        owner.length <= 64 &&
            owner.all { it in 'a'..'z' || it in '0'..'9' || it in "._-" } &&
            !owner.startsWith("_") &&
            !owner.startsWith(".")
    if (verbatim) return owner
    val digest = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray())
    return HASHED_STATE_SEGMENT_PREFIX + HexFormat.of().formatHex(digest, 0, 16)
}

/**
 * Soft caps on Claude spend. Both fields are optional — omitting a cap disables
 * enforcement of that window. Caps are evaluated pre-flight (before each Claude
 * invocation) against a rolling sliding window: 60 s for the global cap, 24 h
 * for the per-agent cap. Spend is backed by the durable `cost_entries` SQLite
 * ledger, so both windows survive a restart. See ADR-0035.
 */
@Serializable
data class CostLimits(
    val globalUsdPerMin: Double? = null,
    val perAgentUsdPerDay: Map<String, Double> = emptyMap(),
) {
    init {
        require(globalUsdPerMin == null || globalUsdPerMin > 0.0) {
            "costLimits.globalUsdPerMin must be > 0 (or null to disable); got $globalUsdPerMin"
        }
        perAgentUsdPerDay.forEach { (agent, cap) ->
            require(cap > 0.0) {
                "costLimits.perAgentUsdPerDay['$agent'] must be > 0; got $cap"
            }
        }
    }
}

/** Container for the pricing sources. Only `sessionSpend` exists today; a per-model rate table is a deliberate later rung. */
@Serializable
data class LlmPriceConfig(
    val sessionSpend: SessionSpendConfig? = null,
)

/**
 * Prices a Claude attempt by asking an external spend API for the session's total.
 * Provider-agnostic on purpose — there is no standard session-spend endpoint, so the
 * shape is a URL template, optional headers and a JSONPath. See ADR-0061.
 */
@Serializable
data class SessionSpendConfig(
    /** Must contain `{sessionId}` exactly once, in the path or query — not the host, port, userinfo or fragment. */
    val url: String,
    /** Sent verbatim on the GET. Values are `@SYSENV:`-resolvable. Never logged. */
    val headers: Map<String, String> = emptyMap(),
    /** Jayway JSONPath yielding one number or a list of numbers (summed). */
    val costPath: String,
    /**
     * Optional Jayway JSONPath yielding one number: how many model calls of the
     * session the endpoint has ingested so far (e.g. `$.calls`). When set, Homer
     * re-polls after the CLI exits until that count reaches the calls the CLI
     * made, charging a partial (marked incomplete) figure otherwise. Null keeps
     * today's single-lookup behaviour. See ADR-0061.
     */
    val callsPath: String? = null,
    /**
     * Optional Jayway JSONPath yielding the response's per-request rows (e.g. `$.requests`).
     * When set together with [startedPath] and [requestSpendPath], Homer prices each attempt
     * from only the rows whose start time falls inside that attempt's window, instead of the
     * session-cumulative [costPath] figure — an agent that resumes its session with
     * `--continue` reuses one session id across runs, so [costPath] would charge every run the
     * running total of all earlier runs. All three window keys must be set or none; a
     * half-configured window is rejected at startup. See ADR-0061.
     */
    val requestsPath: String? = null,
    /** Optional Jayway JSONPath into one [requestsPath] row yielding its start time, e.g. `$.started`. */
    val startedPath: String? = null,
    /** Optional Jayway JSONPath into one [requestsPath] row yielding its cost, e.g. `$.spend`. */
    val requestSpendPath: String? = null,
    /**
     * Milliseconds added on each side of the attempt window when matching [requestsPath] rows,
     * absorbing clock skew between Homer and the endpoint and ingestion delay around the
     * boundaries. Only used when the window keys are set.
     */
    val windowGraceMs: Long = 5000,
    /** Whole-request timeout. */
    val timeoutMs: Long = 3000,
) {
    init {
        val placeholders = Regex(Regex.escape(SESSION_ID_PLACEHOLDER)).findAll(url).count()
        require(placeholders == 1) {
            "llmPrice.sessionSpend.url must contain $SESSION_ID_PLACEHOLDER exactly once; got $placeholders occurrence(s)"
        }
        val lower = url.lowercase()
        require(lower.startsWith("https://") || lower.startsWith("http://")) {
            "llmPrice.sessionSpend.url must use https:// (or http:// to a loopback host)"
        }
        if (lower.startsWith("http://")) {
            require(LOOPBACK_HTTP_URL.containsMatchIn(url)) {
                "llmPrice.sessionSpend.url uses plaintext http:// to a non-loopback host; the request carries the " +
                    "configured headers (typically a bearer token). Use https:// or a localhost endpoint."
            }
        }

        // [server_side_request_forgery / CWE-918] sessionId is read from agent-controlled
        // stdout (§12 rule 2); if it lands in the authority or fragment instead of the path
        // or query, a crafted session id chooses the request host and exfiltrates the
        // configured bearer header. Probe by substituting two different single-char values
        // and comparing what URI resolves for authority/fragment — a difference means the
        // placeholder sits inside one of them.
        fun parseUri(s: String): java.net.URI =
            try {
                java.net.URI(s)
            } catch (e: java.net.URISyntaxException) {
                throw IllegalArgumentException("llmPrice.sessionSpend.url is not a valid URL: ${e.reason}", e)
            }
        // Authority/fragment comparison first: java.net.URI resolves a null host for an
        // RFC-3986-legal reg-name it can't parse as server-based (e.g. an underscore in a
        // Kubernetes-style service name), so gating on `host != null` would reject a safe
        // URL and misreport an authority-placed placeholder as "no host". A blank authority
        // is the only case that actually means "no host".
        val probeX = parseUri(url.replace(SESSION_ID_PLACEHOLDER, "x"))
        val probeY = parseUri(url.replace(SESSION_ID_PLACEHOLDER, "y"))
        require(probeX.authority == probeY.authority) {
            "llmPrice.sessionSpend.url must place {sessionId} in the path or query, not in the host, port or userinfo"
        }
        require(probeX.fragment == probeY.fragment) {
            "llmPrice.sessionSpend.url must place {sessionId} in the path or query, not in the fragment"
        }
        require(!probeX.authority.isNullOrBlank()) { "llmPrice.sessionSpend.url is not a valid URL: no host" }
        require(timeoutMs in 1..MAX_TIMEOUT_MS) {
            "llmPrice.sessionSpend.timeoutMs must be in 1..$MAX_TIMEOUT_MS; got $timeoutMs"
        }
        try {
            com.jayway.jsonpath.JsonPath
                .compile(costPath)
        } catch (e: com.jayway.jsonpath.InvalidPathException) {
            throw IllegalArgumentException("llmPrice.sessionSpend.costPath is not a valid JSONPath: ${e.message}", e)
        }
        if (callsPath != null) {
            try {
                com.jayway.jsonpath.JsonPath
                    .compile(callsPath)
            } catch (e: com.jayway.jsonpath.InvalidPathException) {
                throw IllegalArgumentException("llmPrice.sessionSpend.callsPath is not a valid JSONPath: ${e.message}", e)
            }
        }
        // The window keys price an attempt from its own requests; a partial trio would either
        // silently ignore the configured keys or price a window from an unreadable field, so
        // require all three or none.
        val windowKeys = listOf("requestsPath" to requestsPath, "startedPath" to startedPath, "requestSpendPath" to requestSpendPath)
        val setWindowKeys = windowKeys.filter { it.second != null }
        require(setWindowKeys.isEmpty() || setWindowKeys.size == windowKeys.size) {
            "llmPrice.sessionSpend.requestsPath, startedPath and requestSpendPath must all be set together or all left null; got " +
                setWindowKeys.joinToString { it.first }
        }
        windowKeys.forEach { (name, path) ->
            if (path != null) {
                try {
                    com.jayway.jsonpath.JsonPath
                        .compile(path)
                } catch (e: com.jayway.jsonpath.InvalidPathException) {
                    throw IllegalArgumentException("llmPrice.sessionSpend.$name is not a valid JSONPath: ${e.message}", e)
                }
            }
        }
        require(windowGraceMs >= 0) {
            "llmPrice.sessionSpend.windowGraceMs must be >= 0; got $windowGraceMs"
        }
    }

    fun withResolvedHeaders(resolve: (String) -> String): SessionSpendConfig = copy(headers = headers.mapValues { (_, v) -> resolve(v) })

    // Header values are credentials; keep them out of any accidental config dump.
    override fun toString(): String =
        "SessionSpendConfig(url=$url, headers=${headers.keys.associateWith { "***" }}, costPath=$costPath, " +
            "callsPath=$callsPath, requestsPath=$requestsPath, startedPath=$startedPath, " +
            "requestSpendPath=$requestSpendPath, windowGraceMs=$windowGraceMs, timeoutMs=$timeoutMs)"

    companion object {
        const val SESSION_ID_PLACEHOLDER = "{sessionId}"
        const val MAX_TIMEOUT_MS = 30_000L
    }
}

/**
 * The only hosts a secret-carrying URL may reach over plain `http://`: `langfuse.serverUrl`,
 * `llmPrice.sessionSpend.url`, `homer-runner --url` and the external runner `homerApiUrl`
 * all use this one definition, so the rules cannot drift apart. Userinfo is refused along
 * with every other host.
 */
val LOOPBACK_HTTP_URL = Regex("^http://(localhost|127\\.0\\.0\\.1|\\[::1])(?::\\d+)?(/|$)", RegexOption.IGNORE_CASE)

/**
 * `https://` with a host, or plain `http://` matching [LOOPBACK_HTTP_URL], and no userinfo.
 * Gates on the authority, not `URI.host`: that is null for a legal reg-name such as
 * `homer_internal`.
 *
 * A userinfo component (`https://user:pass@host`) is refused, not stripped: every caller of
 * this rule sends a credential (the runners send their callback or domain token), so a URL
 * carrying different embedded credentials has no legitimate use and would leak them through
 * any string form. CWE-522.
 */
fun isHttpsOrLoopbackHttpUrl(url: String): Boolean {
    val uri = runCatching { java.net.URI(url) }.getOrNull()
    val authority = uri?.authority
    if (authority != null && authority.contains('@')) return false
    return (uri?.scheme.equals("https", ignoreCase = true) && !authority.isNullOrBlank()) ||
        LOOPBACK_HTTP_URL.containsMatchIn(url)
}

@Serializable
data class LangfuseConfig(
    val serverUrl: String = "",
    val publicKey: String = "",
    val secretKey: String = "",
    /**
     * Langfuse project id, used only to build a deep link from a run to its trace
     * (`{serverUrl}/project/{projectId}/traces/{traceId}`). Langfuse derives the
     * project from the API key pair and does not expose it over OTLP, so it has to
     * be configured; copy it out of any Langfuse URL. Blank = no trace links.
     */
    val projectId: String = "",
) {
    // Redacted so a stray `logger.info("config=$config")` or a serialization-error
    // message can't leak the secret into app.log.
    override fun toString() = "LangfuseConfig(serverUrl=$serverUrl, publicKey=$publicKey, secretKey=${redactSecret(secretKey)})"
}

/** Masks a secret for `toString()`: `***` when set, empty when blank (so it doesn't imply a value). */
internal fun redactSecret(value: String?): String = if (value.isNullOrEmpty()) "" else "***"

enum class OpenTelemetryExport {
    LANGFUSE,
    LOG,
}
