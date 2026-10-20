package homer.app.fixtures

import homer.app.apiJson
import homer.app.buildBearerProvider
import homer.app.configureStatusPages
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.Server
import homer.app.models.TokenConfig
import homer.app.routes.ApiDeps
import homer.app.routes.apiV1Routes
import homer.app.routes.callbackRoutes
import homer.app.services.Authorizer
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.mockk.every
import io.mockk.mockk

/**
 * Mounts the production [apiV1Routes] inside a `testApplication` with a
 * relaxed [ApiDeps] mock; [stubDeps] wires the real dependencies a test
 * needs (e.g. `every { processManager } returns realPm`). ContentNegotiation
 * is installed routing-scoped with the production [apiJson], matching the
 * application wiring. Single edit point for every route-level test suite.
 */
internal fun ApplicationTestBuilder.mountApiV1Routes(stubDeps: ApiDeps.() -> Unit = {}): ApiDeps {
    val deps =
        mockk<ApiDeps>(relaxed = true) {
            // Unauthenticated/no-grants by default, matching unsecured-mode
            // production behavior — every Authorizer check passes. Tests that
            // exercise grants enforcement stub `authorizer` themselves.
            every { authorizer } returns Authorizer(emptyMap(), emptyMap(), enabled = false)
            stubDeps()
        }
    application {
        // Matches production wiring: handlers signal error statuses by
        // throwing HomerException subclasses (e.g. QuestionNotFound → 404).
        configureStatusPages()
        routing {
            install(ContentNegotiation) { json(apiJson) }
            apiV1Routes(deps)
        }
    }
    return deps
}

/**
 * Same shape as [mountApiV1Routes] for the token-authenticated
 * [callbackRoutes]. StatusPages is installed because the handlers signal
 * 404/409/429 via HomerException subclasses, matching production wiring.
 */
internal fun ApplicationTestBuilder.mountCallbackRoutes(stubDeps: ApiDeps.() -> Unit = {}): ApiDeps {
    val deps = mockk<ApiDeps>(relaxed = true, block = stubDeps)
    application {
        configureStatusPages()
        routing {
            install(ContentNegotiation) { json(apiJson) }
            callbackRoutes(deps)
        }
    }
    return deps
}

/** The production [Authorizer] for a bearer-only config holding [tokens]. */
internal fun authorizerFor(tokens: Map<String, TokenConfig>): Authorizer =
    Authorizer.from(
        AppConfig(
            server = Server(apiAuth = AuthConfig.Bearer(tokens = tokens)),
            agentsSearchPath = "unused",
            workspaceRootPath = "unused",
            databasePath = "unused",
        ),
    )

/**
 * Mounts the production [apiV1Routes] behind the production bearer provider
 * (`buildBearerProvider`) for [tokens], nested in `authenticate(...)` as
 * `configureRouting` does, so grants and owner tests run the real
 * authentication → principal → Authorizer path.
 */
internal fun Application.installGrantsApp(
    tokens: Map<String, TokenConfig>,
    deps: ApiDeps,
) {
    install(Authentication) { buildBearerProvider(AuthConfig.Bearer(tokens = tokens)) }
    configureStatusPages()
    routing {
        install(ContentNegotiation) { json(apiJson) }
        authenticate("auth-bearer", strategy = AuthenticationStrategy.FirstSuccessful) {
            apiV1Routes(deps)
        }
    }
}
