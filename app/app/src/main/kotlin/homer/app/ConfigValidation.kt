package homer.app

import homer.app.exceptions.ConfigurationException
import homer.app.infra.SysEnvResolver
import homer.app.infra.matchesTokenConstantTime
import homer.app.models.Action
import homer.app.models.Agent
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.LOOPBACK_HTTP_URL
import homer.app.models.OpenTelemetryExport
import homer.app.models.ParamSource
import homer.app.models.RunnerConfig
import homer.app.models.flattenAuthSchemes
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("homer.app.Application")

/**
 * Resolves `@SYSENV:VAR` placeholders inside every secret-bearing field of an
 * [AuthConfig] tree. Recurses into [AuthConfig.Combined] so each inner scheme
 * is resolved. Every variant that carries operator-provided secrets must be
 * covered here — adding a new field with a `@SYSENV`-style placeholder
 * elsewhere in the model and forgetting to resolve it here would silently
 * pass the literal `"@SYSENV:..."` string to the consumer (bcrypt, HMAC,
 * bearer comparison) and look like "invalid credentials" at runtime.
 */
internal fun resolveAuth(
    auth: AuthConfig,
    sysEnvResolver: SysEnvResolver,
): AuthConfig =
    when (auth) {
        is AuthConfig.Bearer -> {
            auth.copy(
                tokens =
                    auth.tokens.mapValues { (_, cfg) ->
                        cfg.copy(token = sysEnvResolver.resolve(cfg.token))
                    },
            )
        }

        is AuthConfig.Hmac -> {
            auth.copy(
                keys =
                    auth.keys.mapValues { (_, cfg) ->
                        cfg.copy(secret = sysEnvResolver.resolve(cfg.secret))
                    },
            )
        }

        is AuthConfig.Password -> {
            auth.copy(
                users =
                    auth.users.mapValues { (_, cfg) ->
                        cfg.copy(passwordHash = sysEnvResolver.resolve(cfg.passwordHash))
                    },
                sessionSecret = sysEnvResolver.resolve(auth.sessionSecret),
                sessionSecretPrevious = auth.sessionSecretPrevious?.let(sysEnvResolver::resolve),
            )
        }

        is AuthConfig.Combined -> {
            auth.copy(schemes = auth.schemes.map { resolveAuth(it, sysEnvResolver) })
        }

        is AuthConfig.Unsecured -> {
            auth
        }
    }

/**
 * Resolves `@SYSENV:VAR` placeholders in every remote runner's `env` map, the
 * same contract `globalEnv` gets. Resolution is fail-fast like the resolver's
 * other callers: an unresolvable placeholder refuses startup rather than
 * shipping a literal `@SYSENV:...` string to the remote host.
 */
internal fun resolveRunnerEnvs(
    runners: Map<String, RunnerConfig>,
    sysEnvResolver: SysEnvResolver,
): Map<String, RunnerConfig> =
    runners.mapValues { (alias, runner) ->
        when (runner) {
            is RunnerConfig.Ssh -> {
                runner.copy(env = sysEnvResolver.resolveMapValues(runner.env))
            }

            is RunnerConfig.Kubernetes -> {
                runner.copy(env = sysEnvResolver.resolveMapValues(runner.env))
            }

            is RunnerConfig.External -> {
                runner.copy(
                    token = sysEnvResolver.resolve(runner.token),
                    env = sysEnvResolver.resolveMapValues(runner.env),
                )
            }
        }
    }

/**
 * Resolves `@SYSENV:VAR` placeholders in `server.corsAllowedOrigins` and refuses
 * an entry that does not resolve to a bare `http(s)://host[:port]` origin, so a
 * variable can complete an origin but never add a path, credentials or another
 * host. Not a secret: no name set is recorded. The error names the raw entry,
 * never the resolved value.
 */
internal fun resolveCorsOrigins(
    origins: List<String>,
    sysEnvResolver: SysEnvResolver,
): List<String> =
    origins.map { raw ->
        sysEnvResolver.resolve(raw).also { resolved ->
            check(isCorsOrigin(resolved)) {
                val via = if (resolved == raw) "" else " after @SYSENV resolution"
                "server.corsAllowedOrigins entry '$raw' is not an origin$via: expected http(s)://host[:port]"
            }
        }
    }

private const val MAX_PORT = 65535
private val ORIGIN_SCHEMES = setOf("http", "https")

/** No port, or one a browser origin can carry: port 0 would be read as no port and allow the scheme default. */
private fun hasOriginPort(uri: URI): Boolean = uri.port == -1 || uri.port in 1..MAX_PORT

/** True when [value] is exactly `scheme://host[:port]` (an optional trailing `/` allowed), scheme http or https. */
internal fun isCorsOrigin(value: String): Boolean {
    val uri =
        try {
            URI(value)
        } catch (_: URISyntaxException) {
            return false
        }
    val scheme = uri.scheme?.lowercase()
    if (scheme !in ORIGIN_SCHEMES || uri.host == null || !hasOriginPort(uri)) return false
    val port = if (uri.port == -1) "" else ":${uri.port}"
    // Rebuilding the origin from its parts rejects userinfo, a path, a query and a fragment.
    return value.removeSuffix("/").equals("$scheme://${uri.host}$port", ignoreCase = true)
}

/**
 * Fail-fast on the removed `endpoints` path allowlist (ADR-0037). Each principal
 * must declare `role: admin` or a non-empty `grants` list. The message names the
 * mechanical migration so an operator can convert in place.
 */
internal fun validateNoLegacyEndpoints(config: AppConfig) {
    fun check(
        kind: String,
        name: String,
        p: homer.app.models.Principaled,
    ) {
        if (p.endpoints != null) {
            throw ConfigurationException(
                "$kind '$name' uses the removed 'endpoints' allowlist. Migrate to grants: " +
                    "[\"*\"] -> `role: admin`; `/api/v1/agent/X` -> `grants: [{agents: [X], actions: [run]}]`. See ADR-0037.",
            )
        }
        if (p.role == null && p.grants.isEmpty()) {
            throw ConfigurationException(
                "$kind '$name' has neither `role` nor `grants`; it could authenticate but do nothing. " +
                    "Add `role: admin` or a `grants` list. See ADR-0037.",
            )
        }
    }
    flattenAuthSchemes(config.server.apiAuth).forEach { s ->
        when (s) {
            is AuthConfig.Bearer -> {
                s.tokens.forEach { (n, c) -> check("bearer token", n, c) }
            }

            is AuthConfig.Hmac -> {
                s.keys.forEach { (n, c) -> check("hmac key", n, c) }
            }

            is AuthConfig.Password -> {
                s.users.forEach { (n, c) -> check("password user", n, c) }
            }

            else -> {}
        }
    }
}

/**
 * Fail-fast on an extraUploads entry whose local path is missing or unreadable —
 * a misdeclared deployment dependency should stop the server at boot, not fail
 * runs later. Shape rules (relative remote, reserved paths, uniqueness) are
 * enforced at parse time in the models. See ADR-0042.
 */
internal fun validateExtraUploads(config: AppConfig) {
    config.runners.forEach { (alias, runner) ->
        val extraUploads =
            when (runner) {
                is RunnerConfig.Ssh -> runner.extraUploads
                is RunnerConfig.Kubernetes -> runner.extraUploads
                is RunnerConfig.External -> runner.extraUploads
            }
        extraUploads.forEach { spec ->
            if (!Files.isReadable(Path.of(spec.local))) {
                throw ConfigurationException(
                    "runner '$alias' extraUploads.local '${spec.local}' does not exist or is not readable.",
                )
            }
        }
    }
}

/**
 * Fail-fast when the config omits `server.apiAuth` entirely: the old silent
 * Unsecured default meant a config typo (or a fresh minimal config) exposed
 * every endpoint without auth. Running unsecured is still supported, but only
 * as an explicit opt-in. See ADR-0036.
 */
internal fun validateExplicitApiAuth(config: AppConfig) {
    if (config.server.apiAuth == null) {
        throw ConfigurationException(
            "server.apiAuth is not set. Configure an auth scheme (bearer/hmac/password/combined), " +
                "or explicitly opt in to an unsecured API with `apiAuth: !<unsecured>` (YAML) / " +
                "`\"apiAuth\": {\"type\": \"unsecured\"}` (JSON). See SERVER.md and ADR-0036.",
        )
    }
}

/**
 * A runner-domain token and an operator `apiAuth` credential are distinct principal
 * classes (ADR-0066): the runner routes accept only the former, every other route
 * only the latter. Equal values would let one credential satisfy both boundaries, so
 * the collision is refused at startup. The message names the alias, never the value.
 *
 * Every secret an `apiAuth` scheme accepts as proof counts, not just bearer tokens:
 * an HMAC key secret signs arbitrary requests, and a session signing secret (current
 * or rotated-out) mints a valid cookie for any configured user. A password hash is
 * deliberately absent — it is never presented as a credential.
 */
internal fun validateRunnerDomainTokens(config: AppConfig) {
    val apiAuthSecrets =
        flattenAuthSchemes(config.server.apiAuth).flatMap { scheme ->
            when (scheme) {
                is AuthConfig.Bearer -> scheme.tokens.values.map { it.token }
                is AuthConfig.Hmac -> scheme.keys.values.map { it.secret }
                is AuthConfig.Password -> listOfNotNull(scheme.sessionSecret, scheme.sessionSecretPrevious)
                is AuthConfig.Combined, is AuthConfig.Unsecured -> emptyList()
            }
        }
    config.runners.forEach { (alias, cfg) ->
        if (cfg is RunnerConfig.External && apiAuthSecrets.any { matchesTokenConstantTime(it, cfg.token) }) {
            error("Configuration error: runner '$alias' token equals an apiAuth credential; use a distinct value")
        }
    }
}

/**
 * Fail-fast when a cookie/session ([AuthConfig.Password]) scheme is configured
 * without pinned CORS origins: cookie auth needs credentialed CORS, which
 * [configureCors] enables only for pinned origins, so an empty list would
 * silently break the console. See ADR-0016.
 */
internal fun validateCorsForCookieAuth(config: AppConfig) {
    val hasPasswordAuth = flattenAuthSchemes(config.server.apiAuth).any { it is AuthConfig.Password }
    if (hasPasswordAuth && config.server.corsAllowedOrigins.isEmpty()) {
        throw ConfigurationException(
            "AuthConfig.Password is configured but server.corsAllowedOrigins is empty. " +
                "Credentialed CORS is only enabled for explicitly pinned origins, so set " +
                "server.corsAllowedOrigins to the console origin(s) (e.g. \"https://console.example.com\"). " +
                "See ADR-0016.",
        )
    }
}

/**
 * Fail-fast on an HMAC key whose signature does not bind the request path while
 * its grants reach more than one agent — via `role: admin`, a grant whose
 * `agents` contains a `*` pattern, or grants naming two or more distinct exact
 * agents. Such a signature is portable: a request captured for one agent replays
 * against every other agent the key can reach. The signature scheme itself is
 * unchanged (existing valid signatures still verify), so this only rejects the
 * genuinely-dangerous config at startup; the operator narrows the key's grants
 * or binds the path. See M3/M4 in the 2026-07-12 security review, re-expressed on
 * grants per ADR-0037.
 */
internal fun validateHmacSignatureScope(config: AppConfig) {
    flattenAuthSchemes(config.server.apiAuth)
        .filterIsInstance<AuthConfig.Hmac>()
        .forEach { hmac ->
            hmac.keys.forEach { (keyId, cfg) ->
                val bindsPath = "{{path}}" in cfg.payloadTemplate
                val agentPatterns = cfg.grants.flatMap { it.agents }.toSet()
                val reachesMultipleAgents =
                    cfg.role == "admin" ||
                        agentPatterns.any { "*" in it } ||
                        agentPatterns.size > 1
                if (!bindsPath && reachesMultipleAgents) {
                    throw ConfigurationException(
                        "HMAC key '$keyId' signs a payload that does not bind {{path}} while its grants reach " +
                            "multiple agents; a captured signature is then replayable across them. Bind the path " +
                            "in payloadTemplate (e.g. \"{{method}}.{{path}}.{{body}}\") or narrow the key's grants.",
                    )
                }
            }
        }
}

/**
 * Fail-fast on a session signing secret shorter than [MIN_SESSION_SECRET_LEN]
 * characters. `sessionSecret` (and the rotation key `sessionSecretPrevious`) is
 * used raw as the HMAC-SHA256 key for the session cookie; a short secret is
 * brute-forceable, letting an attacker forge session cookies. Runs on the
 * *resolved* config (after `@SYSENV` substitution), so an operator referencing a
 * strong secret via a short `@SYSENV:VAR` placeholder is fine — only the actual
 * value's length is checked.
 */
internal fun validateSessionSecretStrength(config: AppConfig) {
    flattenAuthSchemes(config.server.apiAuth)
        .filterIsInstance<AuthConfig.Password>()
        .forEach { pw ->
            listOfNotNull(
                "sessionSecret" to pw.sessionSecret,
                pw.sessionSecretPrevious?.let { "sessionSecretPrevious" to it },
            ).forEach { (field, value) ->
                if (value.length < MIN_SESSION_SECRET_LEN) {
                    throw ConfigurationException(
                        "AuthConfig.Password.$field must be at least $MIN_SESSION_SECRET_LEN characters " +
                            "(it is the HMAC key for the session cookie); generate one with e.g. `openssl rand -hex 32`.",
                    )
                }
            }
        }
}

private const val MIN_SESSION_SECRET_LEN = 32

/**
 * Fail-fast on a runner-domain token shorter than [MIN_RUNNER_TOKEN_LEN] characters,
 * on the resolved config like [validateSessionSecretStrength]. The token is the only
 * proof the runner protocol routes ask for, and a match drives commands on the instances, so
 * it must not be guessable. The message names the alias, never the value.
 * CWE-331.
 */
internal fun validateRunnerDomainTokenStrength(config: AppConfig) {
    config.runners.forEach { (alias, cfg) ->
        if (cfg is RunnerConfig.External && cfg.token.length < MIN_RUNNER_TOKEN_LEN) {
            throw ConfigurationException(
                "runner '$alias' token must be at least $MIN_RUNNER_TOKEN_LEN characters; " +
                    "generate one with e.g. `openssl rand -hex 32`.",
            )
        }
    }
}

private const val MIN_RUNNER_TOKEN_LEN = 32

/**
 * Fail-fast when Langfuse export is enabled but `serverUrl` is plaintext `http://`
 * to a non-loopback host: the OTLP export carries the Langfuse public+secret keys
 * and (non-secret) prompt/tool content, so cleartext to a remote host leaks them.
 * Loopback `http://` is allowed (local collector). See M12.
 */
internal fun validateLangfuseServerUrl(config: AppConfig) {
    if (config.openTelemetryExport != OpenTelemetryExport.LANGFUSE) return
    val url = config.langfuse.serverUrl
    if (url.isBlank() || !url.startsWith("http://", ignoreCase = true)) return
    if (!LOOPBACK_HTTP_URL.containsMatchIn(url)) {
        throw ConfigurationException(
            "langfuse.serverUrl uses plaintext http:// to a non-loopback host ($url); the OTLP export " +
                "carries the Langfuse public+secret keys and prompt content. Use https:// (or a localhost " +
                "collector). See the 2026-07-12 security review (M12).",
        )
    }
}

/**
 * Startup WARN for each HMAC key without a `timestampHeader`: with no freshness
 * window a captured signature replays indefinitely. Non-fatal — some webhook
 * senders (e.g. Vikunja) sign no timestamp — but the operator should know. See M13.
 */
internal fun warnOnHmacReplayWindow(
    config: AppConfig,
    log: org.slf4j.Logger = logger,
) {
    flattenAuthSchemes(config.server.apiAuth)
        .filterIsInstance<AuthConfig.Hmac>()
        .forEach { hmac ->
            hmac.keys.forEach { (keyId, cfg) ->
                if (cfg.timestampHeader == null) {
                    log.warn(
                        "HMAC key '{}' has no timestampHeader, so a captured signature replays indefinitely. " +
                            "Set timestampHeader and add {{timestamp}} to payloadTemplate for replay resistance.",
                        keyId,
                    )
                }
            }
        }
}

/**
 * Startup WARN for each HMAC key that can run an agent reading inputs from the
 * query string while its `payloadTemplate` omits `{{query}}`: a captured
 * signature then replays with altered query parameters, tampering with those
 * inputs. Non-fatal — query signing is opt-in (M3 in the 2026-07-12 review), so
 * whether to bind it is the operator's choice — but the operator should know the
 * exposure.
 */
internal fun warnOnUnsignedQueryInputs(
    config: AppConfig,
    agents: Collection<Agent>,
    log: org.slf4j.Logger = logger,
) {
    val queryAgents =
        agents.filter { a -> a.effectiveInputs.orEmpty().any { it.src == ParamSource.QUERY } }.map { it.name }
    if (queryAgents.isEmpty()) return
    flattenAuthSchemes(config.server.apiAuth)
        .filterIsInstance<AuthConfig.Hmac>()
        .forEach { hmac ->
            hmac.keys.forEach { (keyId, cfg) ->
                if ("{{query}}" in cfg.payloadTemplate) return@forEach
                val reachable =
                    queryAgents.filter { name ->
                        cfg.role == "admin" || cfg.grants.any { it.matches(name, Action.RUN) }
                    }
                if (reachable.isNotEmpty()) {
                    log.warn(
                        "HMAC key '{}' can run agent(s) that read inputs from the query string ({}), but its " +
                            "payloadTemplate does not bind {{query}}; a captured signature replays with altered " +
                            "query params. Add {{query}} to payloadTemplate " +
                            "(e.g. \"{{method}}.{{path}}.{{query}}.{{body}}\").",
                        keyId,
                        reachable.joinToString(", "),
                    )
                }
            }
        }
}
