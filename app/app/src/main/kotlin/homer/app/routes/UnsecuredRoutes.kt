package homer.app.routes

import homer.app.auth.HomerSession
import homer.app.infra.PasswordVerifier
import homer.app.models.AuthConfig
import homer.app.models.ErrorResponse
import homer.app.models.LivenessResponse
import homer.app.models.LoginRequest
import homer.app.models.LoginResponse
import homer.app.models.MeResponse
import homer.app.models.ReadinessResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set

/**
 * The unsecured /api/v1 routes: liveness/readiness probes and the
 * /api/v1/auth login/logout/me endpoints — mounted outside the
 * bearer/HMAC authenticate wrapper so Kubernetes probes and the cookie
 * auth handshake work without a token.
 */
internal fun Route.apiV1UnsecuredRoutes(deps: ApiDeps) {
    // Probe endpoints must be reachable without a bearer token — kubelet
    // and most reverse-proxy upstream checks don't send Authorization
    // headers. Mounted **outside** the auth wrapper so Kubernetes can
    // hit them on a bearer-secured deployment. Audit P1 #12.
    probeRoutes(deps)

    // Auth routes — mounted OUTSIDE the authenticate(...) wrapper:
    //  • /login is by definition unauthenticated;
    //  • /logout must be idempotent and reachable even with a stale cookie;
    //  • /me reads the session cookie directly via call.sessions, so it
    //    doesn't need (and doesn't want) the Bearer/HMAC FirstSuccessful
    //    path — that path would mis-authenticate a stale bearer + valid
    //    cookie request as the bearer principal.
    // Audit P0 #6 (long-term) / ADR-0011.
    val passwordScheme = deps.passwordScheme
    if (passwordScheme != null) {
        sessionAuthRoutes(deps, passwordScheme)
    }
}

/** `GET /api/v1/livez` and `GET /api/v1/readyz`: the liveness and readiness probes. */
private fun Route.probeRoutes(deps: ApiDeps) {
    with(deps) {
        route("/api/v1") {
            // Liveness — always 200. Wire `livenessProbe` here. Never wire
            // it to /readyz: a 503 during drain would restart the pod
            // mid-drain and defeat server.shutdownDrainSec.
            get("/livez") {
                call.respond(LivenessResponse())
            }
            // Readiness — 503 once ApplicationStopping fires, 200 otherwise.
            // Wire `readinessProbe` and any upstream reverse-proxy health
            // check here so the LB removes this pod from rotation as soon
            // as drain starts while in-flight jobs finish.
            get("/readyz") {
                if (isDraining.get()) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ReadinessResponse(status = "draining"))
                } else {
                    call.respond(ReadinessResponse(status = "ready"))
                }
            }
        }
    }
}

/** `POST /api/v1/auth/login`, `POST /api/v1/auth/logout` and `GET /api/v1/auth/me`: the session-cookie handshake. */
private fun Route.sessionAuthRoutes(
    deps: ApiDeps,
    passwordScheme: AuthConfig.Password,
) {
    with(deps) {
        route("/api/v1/auth") {
            rateLimit(RateLimitName("login")) {
                post("/login") {
                    val req = call.receiveOr400<LoginRequest>("Invalid login body: expected { username, password }")
                    if (!credentialsMatch(passwordVerifier, passwordScheme, req)) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse(msg = "Invalid credentials"))
                        return@post
                    }
                    val now =
                        java.time.Instant
                            .now()
                            .epochSecond
                    call.sessions.set(
                        HomerSession(
                            username = req.username,
                            exp = now + passwordScheme.sessionTtlMin.toLong() * 60L,
                        ),
                    )
                    call.respond(LoginResponse(username = req.username))
                }
            }
            post("/logout") {
                call.sessions.clear<HomerSession>()
                call.respond(HttpStatusCode.NoContent)
            }
            get("/me") {
                val session = call.sessions.get<HomerSession>()
                if (session == null ||
                    session.exp <=
                    java.time.Instant
                        .now()
                        .epochSecond
                ) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(msg = "Not authenticated"))
                    return@get
                }
                val user = passwordScheme.users[session.username]
                if (user == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(msg = "User no longer exists"))
                    return@get
                }
                call.respond(MeResponse(username = session.username, role = user.role, grants = user.grants))
            }
        }
    }
}

/**
 * Whether [req] names a user of [passwordScheme] and that user's password. Constant-time-ish: when
 * the user does not exist, still run a bcrypt verify against a dummy hash so a 401 takes the same
 * wall-clock time as wrong-password. The dummy is bound to the configured `bcryptCost` (memoized in
 * PasswordVerifier) — a fixed cost-12 constant would leak which usernames exist whenever the
 * operator deviates from 12.
 */
private fun credentialsMatch(
    passwordVerifier: PasswordVerifier,
    passwordScheme: AuthConfig.Password,
    req: LoginRequest,
): Boolean {
    val user = passwordScheme.users[req.username]
    return if (user == null) {
        passwordVerifier.verify(
            req.password,
            passwordVerifier.dummyHashAtCost(passwordScheme.bcryptCost),
        )
        false
    } else {
        passwordVerifier.verify(req.password, user.passwordHash)
    }
}
