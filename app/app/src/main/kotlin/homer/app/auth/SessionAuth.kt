package homer.app.auth

import homer.app.models.AuthConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.response.respond
import io.ktor.server.sessions.SessionTransportTransformer
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.get
import io.ktor.server.sessions.serialization.KotlinxSessionSerializer
import io.ktor.server.sessions.sessions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Payload of the HttpOnly session cookie issued by `POST /api/v1/auth/login`.
 *
 * The cookie is HMAC-signed (not encrypted) via `SessionTransportTransformerMessageAuthentication`,
 * so anything in here is readable by the holder — never put a real secret in.
 * `ver` future-proofs format migrations: bump it when the schema changes and the
 * provider can reject old payloads on sight.
 *
 * See ADR-0011 for the rationale.
 */
@Serializable
data class HomerSession(
    val username: String,
    val exp: Long,
    val ver: Int = 1,
)

/**
 * Prefix stamped on the [UserIdPrincipal] name for a cookie/session login.
 * Encodes the auth scheme in the principal name so downstream guards (CSRF,
 * rate-limit keying) can tell a browser-ambient cookie identity apart from
 * bearer/HMAC. Reserved — Bearer/HMAC config keys may not start with it. See ADR-0016.
 */
const val SESSION_PRINCIPAL_PREFIX = "session:"

/**
 * `SessionTransportTransformer` that signs writes with `currentKey` and accepts
 * reads signed with either `currentKey` or `previousKey`. Ktor's built-in MAC
 * transformer is single-key; chaining two of them is composition (sign with A
 * then with B), not multi-key fallback. This wrapper does what zero-downtime
 * rotation actually requires: try each key on read until one verifies.
 *
 * Rotation flow:
 *  1. set `sessionSecretPrevious = <old>`, `sessionSecret = <new>`, deploy
 *  2. wait `sessionTtlMin`, drop `sessionSecretPrevious`
 *
 * See ADR-0011.
 */
internal class MultiKeyMacTransformer(
    private val current: SessionTransportTransformerMessageAuthentication,
    private val previous: SessionTransportTransformerMessageAuthentication?,
) : SessionTransportTransformer {
    override fun transformRead(transportValue: String): String? =
        current.transformRead(transportValue) ?: previous?.transformRead(transportValue)

    override fun transformWrite(transportValue: String): String = current.transformWrite(transportValue)
}

/**
 * Install the `Sessions` plugin for password auth. Must be called BEFORE
 * `install(Authentication)` — Ktor enforces that order at plugin-install time.
 */
fun Application.installPasswordSessions(scheme: AuthConfig.Password) {
    val current =
        SessionTransportTransformerMessageAuthentication(
            scheme.sessionSecret.toByteArray(Charsets.UTF_8),
            "HmacSHA256",
        )
    val previous =
        scheme.sessionSecretPrevious?.let { prev ->
            SessionTransportTransformerMessageAuthentication(
                prev.toByteArray(Charsets.UTF_8),
                "HmacSHA256",
            )
        }
    install(Sessions) {
        cookie<HomerSession>(scheme.cookieName) {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = scheme.cookieSecure
            cookie.maxAgeInSeconds = scheme.sessionTtlMin.toLong() * 60L
            cookie.extensions["SameSite"] = scheme.cookieSameSite.cookieValue
            serializer = KotlinxSessionSerializer(HomerSession.serializer(), Json)
            transform(MultiKeyMacTransformer(current, previous))
        }
    }
}

/**
 * Register the `auth-session` provider inside the existing `install(Authentication)` block.
 * Shape mirrors the `auth-bearer` provider in AuthWiring.kt: authenticates only,
 * authorization (grants) is enforced at the route. See ADR-0037.
 *
 * Steps:
 * 1. Read `call.sessions.get<HomerSession>()`. Missing or unsigned → 401.
 * 2. Reject if `exp <= now` (cookie expired).
 * 3. Look up `users[session.username]` — absent (e.g. user revoked since login) → 401.
 *
 * Caller pushes `providerName` into the `authNames` list so the route wrapper
 * includes auth-session in `FirstSuccessful`.
 */
fun AuthenticationConfig.passwordSessionProvider(
    providerName: String,
    scheme: AuthConfig.Password,
) {
    provider(providerName) {
        authenticate { context ->
            val call = context.call
            val session = call.sessions.get<HomerSession>()
            if (session == null) {
                context.challenge("SessionAuth", AuthenticationFailedCause.NoCredentials) { challenge, c ->
                    c.respond(HttpStatusCode.Unauthorized)
                    challenge.complete()
                }
                return@authenticate
            }
            if (session.exp <= Instant.now().epochSecond) {
                context.challenge("SessionAuth", AuthenticationFailedCause.InvalidCredentials) { challenge, c ->
                    c.respond(HttpStatusCode.Unauthorized)
                    challenge.complete()
                }
                return@authenticate
            }
            val user = scheme.users[session.username]
            if (user == null) {
                context.challenge("SessionAuth", AuthenticationFailedCause.InvalidCredentials) { challenge, c ->
                    c.respond(HttpStatusCode.Unauthorized)
                    challenge.complete()
                }
                return@authenticate
            }
            context.principal(UserIdPrincipal("$SESSION_PRINCIPAL_PREFIX${session.username}"))
        }
    }
}
