package homer.app.auth

import homer.app.exceptions.CsrfValidationException
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.header

/** Custom request header required on cookie/session-authenticated requests. */
const val CSRF_HEADER = "X-Homer-CSRF"

/**
 * Route-scoped CSRF guard. Installed inside the `authenticate` block; the
 * [AuthenticationChecked] hook fires after authentication has populated the
 * principal (an `onCall` hook would run before it and always see a null
 * principal).
 *
 * Scoped to `session:` principals only ([SessionAuth] tags cookie sessions
 * `session:<user>`): browsers auto-attach the session cookie to cross-site
 * requests (GET navigations / `<img>` loads under the default `SameSite=Lax`,
 * and every cross-site request when `cookieSameSite=None` for a cross-origin
 * console) but cannot set a custom request header cross-origin, so requiring
 * [CSRF_HEADER] blocks the drive-by surface regardless of SameSite.
 * Bearer/HMAC callers — and unauthenticated pre-login calls — are a no-op: their
 * credential is not browser-ambient, so the documented GET webhook/curl trigger
 * keeps working. The console (the only legitimate session client, ADR-0011)
 * sends the header on every request, so guarding *all* session methods (not just
 * unsafe ones) has zero legitimate breakage and also covers the GET-execute and
 * SSE-GET paths without per-path logic. See ADR-0016.
 */
val CsrfProtection =
    createRouteScopedPlugin("CsrfProtection") {
        on(AuthenticationChecked) { call ->
            val principalName = call.principal<UserIdPrincipal>()?.name
            if (principalName?.startsWith(SESSION_PRINCIPAL_PREFIX) == true && call.request.header(CSRF_HEADER).isNullOrBlank()) {
                throw CsrfValidationException(
                    "Missing required $CSRF_HEADER header on a session-authenticated request " +
                        "(cookie-auth CSRF protection; see ADR-0016).",
                )
            }
        }
    }
