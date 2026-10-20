package homer.app

import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.RunnerConfig
import homer.app.models.flattenAuthSchemes
import homer.app.routes.matchRunnerDomain
import homer.app.routes.runnerBearerToken
import homer.app.runners.external.RunnerProtocol
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitConfig
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.RateLimiter
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import kotlin.time.Duration.Companion.seconds

/**
 * Kubernetes probe endpoints (`/api/v1/livez`, `/api/v1/readyz`), exempt from the
 * server-wide rate limiter. Exact-match only: `/health`, `/heartbeat` and the
 * `stream` / `runner` paths share the [isNoisyAccessPath] family but must keep
 * their ceiling. Why: `app/SERVER.md`, "Liveness & readiness probes" (§12).
 */
internal fun isProbePath(path: String): Boolean = path == "/api/v1/livez" || path == "/api/v1/readyz"

/**
 * Request keys for the global limiter. Its whole key space is these three constants
 * plus one [RunnerDomainLimitKey] per configured domain, so it caches a bounded set
 * of limiter instances (§12 rule 4). See ADR-0014, amendment 2026-09-30.
 */
internal const val PROBE_LIMIT_KEY = "probe"
internal const val LOGIN_LIMIT_KEY = "login"
internal const val GLOBAL_LIMIT_KEY = "global"

/** A verified runner-protocol request's key: the domain alias only, never the token (Ktor logs keys at trace). */
internal data class RunnerDomainLimitKey(
    val alias: String,
)

/** A configured domain's alias and token, as the global limiter's key function matches them. */
internal class RunnerDomainToken(
    val alias: String,
    val token: String,
)

/**
 * Requests one claim slot (one instance at one unit of `concurrency`) may make per
 * minute. An idle slot polls 2 times a minute (its single long poll — `claim`, or `next`
 * while it holds a session — is held the full [RunnerProtocol.POLL_SEC] and re-issued at
 * once); the rest is headroom for ops and output chunks. The numbers: ADR-0014,
 * amendment 2026-09-30.
 */
internal const val RUNNER_SLOT_REQUESTS_PER_MINUTE = 1000

/** A domain's bucket per minute: every slot it may run at once, saturating at `Int.MAX_VALUE`. */
internal fun runnerDomainRateLimit(domain: RunnerConfig.External): Int =
    (domain.maxInstances.toLong() * domain.maxConcurrencyPerInstance)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .times(RUNNER_SLOT_REQUESTS_PER_MINUTE)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

private const val RUNNER_PROTOCOL_PREFIX = "/api/v1/runner/"
private const val LOGIN_PATH = "/api/v1/auth/login"

/**
 * The global limiter's key. Probes are exempt; a mounted `POST /api/v1/auth/login`
 * moves to one aggregate login bucket (a constant key, on top of its per-peer
 * `login` bucket); a runner-protocol request moves to its domain's bucket only when
 * its token verifies through [matchRunnerDomain], the routes' own constant-time
 * match. Anything else, a missing or invalid token included, keeps the shared global
 * bucket. ADR-0014, amendment 2026-09-30 (§12).
 */
internal fun globalRateLimitKey(
    method: HttpMethod,
    path: String,
    runnerToken: String?,
    runnerDomains: List<RunnerDomainToken>,
    loginMounted: Boolean,
): Any =
    when {
        isProbePath(path) -> {
            PROBE_LIMIT_KEY
        }

        loginMounted && method == HttpMethod.Post && path == LOGIN_PATH -> {
            LOGIN_LIMIT_KEY
        }

        path.startsWith(RUNNER_PROTOCOL_PREFIX) -> {
            matchRunnerDomain(runnerDomains, runnerToken) { it.token }?.let { RunnerDomainLimitKey(it.alias) } ?: GLOBAL_LIMIT_KEY
        }

        else -> {
            GLOBAL_LIMIT_KEY
        }
    }

/**
 * The caller identity the `perCaller` rate limiter keys on, shared with the
 * per-caller SSE stream cap: the authenticated principal's name, else the TCP
 * peer host (unsecured mode). See ADR-0014.
 */
internal fun ApplicationCall.callerKey(): String = principal<UserIdPrincipal>()?.name ?: request.local.remoteHost

/**
 * Installs the global + per-route RateLimit plugin.
 *
 * Extracted verbatim from the inline `configureServer` install; install order
 * relative to the other plugins is preserved by the caller.
 */
internal fun Application.configureRateLimiting(config: AppConfig) {
    val runnerDomains =
        config.runners.mapNotNull { (alias, cfg) -> (cfg as? RunnerConfig.External)?.let { RunnerDomainToken(alias, it.token) } }
    val runnerLimits =
        config.runners.mapNotNull { (alias, cfg) -> (cfg as? RunnerConfig.External)?.let { alias to runnerDomainRateLimit(it) } }.toMap()
    // Must match the mount condition of the login route: with no route, the path
    // stays in the global bucket like any other 404.
    val loginMounted = flattenAuthSchemes(config.server.apiAuth).any { it is AuthConfig.Password }
    install(RateLimit) {
        // Ktor caches one limiter instance per (provider, key), so every exemption is
        // a key from a bounded set (see globalRateLimitKey), never raw request data.
        // Everything not exempt still shares the one global bucket. ADR-0014, §12.
        global {
            requestKey { call ->
                globalRateLimitKey(
                    call.request.httpMethod,
                    call.request.path(),
                    call.request.runnerBearerToken(),
                    runnerDomains,
                    loginMounted,
                )
            }
            rateLimiter { _, key -> globalRateLimiter(key, config, runnerLimits) }
        }
        registerLoginLimiter(config)
        registerPerCallerLimiter(config)
    }
}

/** The global limiter's bucket for [key], one of the [globalRateLimitKey] keys. */
private fun globalRateLimiter(
    key: Any,
    config: AppConfig,
    runnerLimits: Map<String, Int>,
): RateLimiter =
    when (key) {
        PROBE_LIMIT_KEY -> {
            RateLimiter.Unlimited
        }

        // The aggregate login ceiling: one bucket for every peer, the size of
        // the global one, so per-peer keys cannot multiply it (§12 rule 4).
        LOGIN_LIMIT_KEY -> {
            RateLimiter.default(
                limit = config.server.rateLimit.limit,
                refillPeriod = config.server.rateLimit.refillPeriod.seconds,
            )
        }

        is RunnerDomainLimitKey -> {
            RateLimiter.default(limit = runnerLimits.getValue(key.alias), refillPeriod = 60.seconds)
        }

        else -> {
            RateLimiter.default(
                limit = config.server.rateLimit.limit,
                refillPeriod = config.server.rateLimit.refillPeriod.seconds,
            )
        }
    }

/**
 * Stricter per-IP cap on POST /api/v1/auth/login to defend against
 * brute-force credential guessing (configurable via
 * `server.loginRateLimit`, default 5/min). The global limiter is
 * intentionally generous for normal API traffic and would not catch a
 * 1-RPS guesser. Audit P0 #6 / ADR-0011. Login leaves the shared global
 * bucket for an aggregate login bucket of its own, which bounds the total
 * across peers (ADR-0014, amendment 2026-09-30).
 *
 * Keying on `local.remoteHost` (the direct socket peer) rather than
 * `origin.remoteHost` is intentional: `origin` would be X-Forwarded-For
 * if `XForwardedHeaders` is ever installed, which an attacker can spoof
 * by rotating headers. Until the deployment story includes a trusted
 * reverse proxy and an explicit decision to honor its headers here,
 * the limiter stays bound to the actual TCP peer. NOTE: behind a
 * reverse proxy the peer is the proxy, so this becomes one shared
 * bucket for all clients — raise `server.loginRateLimit` accordingly.
 */
private fun RateLimitConfig.registerLoginLimiter(config: AppConfig) {
    register(RateLimitName("login")) {
        rateLimiter(
            limit = config.server.loginRateLimit.limit,
            refillPeriod = config.server.loginRateLimit.refillPeriod.seconds,
        )
        requestKey { call -> call.request.local.remoteHost }
    }
}

/**
 * Per-caller bucket on the secured /api/v1 routes, layered under the global
 * ceiling. Keyed on the UserIdPrincipal every auth scheme sets, falling back
 * to the TCP peer IP in unsecured mode. See ADR-0014 (audit P3 #37).
 */
private fun RateLimitConfig.registerPerCallerLimiter(config: AppConfig) {
    register(RateLimitName("perCaller")) {
        rateLimiter(
            limit = config.server.perCallerRateLimit.limit,
            refillPeriod = config.server.perCallerRateLimit.refillPeriod.seconds,
        )
        requestKey { call -> call.callerKey() }
    }
}

/**
 * Runner tar posts carry whole workspaces and are bounded by the presented
 * token's own domain `maxTransferBytes`; output chunks and `next` bodies are
 * bounded by the protocol. Everything else keeps `server.maxRequestBodyBytes`.
 */
internal fun runnerRouteBodyLimit(
    config: AppConfig,
    path: String,
    token: String?,
): Long {
    val default = config.server.maxRequestBodyBytes
    if (!path.startsWith("/api/v1/runner/sessions/")) return default
    val domains = config.runners.values.filterIsInstance<RunnerConfig.External>()
    if (domains.isEmpty()) return default
    return when {
        // The CALLER's ceiling, not the widest configured one: this limit is spent before
        // the route authenticates, so resolving the domain from the presented token here
        // is what stops an unmatched caller from claiming the largest domain's bound.
        path.endsWith("/tar") -> matchRunnerDomain(domains, token) { it.token }?.maxTransferBytes ?: default

        path.endsWith("/output") -> RunnerProtocol.MAX_OUTPUT_CHUNK_BYTES.toLong()

        path.endsWith("/next") -> RunnerProtocol.MAX_NEXT_BODY_BYTES.toLong()

        else -> default
    }
}
