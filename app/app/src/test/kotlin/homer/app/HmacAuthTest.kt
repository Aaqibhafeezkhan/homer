package homer.app

import homer.app.infra.HmacReplayCache
import homer.app.infra.HmacVerifier
import homer.app.models.AuthConfig
import homer.app.models.HmacKeyConfig
import homer.app.models.TokenConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.util.AttributeKey
import org.junit.jupiter.api.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for HMAC and Combined authentication.
 *
 * Installs the production auth plugins and providers from AuthWiring.kt
 * (without requiring a full AgentsService stack on disk) so the real provider
 * plumbing is exercised end-to-end through the Ktor pipeline.
 */
class HmacAuthTest {
    private fun hexSha256(
        secret: String,
        body: String,
    ): String {
        val mac =
            Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            }
        return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * Stand up the production auth plugins in `configureAuth`'s order —
     * DoubleReceive, [hmacBodyCapturePlugin], then Authentication with
     * [buildBearerProvider] / [buildHmacProvider] over an [HmacVerifier] built
     * as production builds it — plus a minimal set of protected routes used by
     * the assertions below. [onHmacBodyCaptured] is test-only instrumentation:
     * called with the size of every body the capture plugin stored for the
     * HMAC provider.
     */
    private fun Application.installAuthApp(
        schemes: List<AuthConfig>,
        onHmacBodyCaptured: (Int) -> Unit = {},
    ) {
        val hmacBodyKey = AttributeKey<ByteArray>("HmacBody")

        if (schemes.any { it is AuthConfig.Hmac }) {
            install(DoubleReceive)
            install(hmacBodyCapturePlugin(hmacBodyKey))
            // Installed after the capture, so its onCall runs next and sees what it stored.
            install(
                createApplicationPlugin(name = "HmacBodyCaptureProbe") {
                    onCall { call -> call.attributes.getOrNull(hmacBodyKey)?.let { onHmacBodyCaptured(it.size) } }
                },
            )
        }

        val hmacVerifier = HmacVerifier(replayCache = HmacReplayCache())
        val authNames = mutableListOf<String>()

        install(Authentication) {
            schemes.forEach { scheme ->
                when (scheme) {
                    is AuthConfig.Bearer -> {
                        buildBearerProvider(scheme)
                        authNames += "auth-bearer"
                    }

                    is AuthConfig.Hmac -> {
                        buildHmacProvider(scheme, hmacVerifier, hmacBodyKey)
                        authNames += "auth-hmac"
                    }

                    is AuthConfig.Password,
                    is AuthConfig.Combined,
                    is AuthConfig.Unsecured,
                    -> {
                        error("unreachable: schemes does not contain Password, Combined, or Unsecured")
                    }
                }
            }
        }

        routing {
            authenticate(*authNames.toTypedArray(), strategy = AuthenticationStrategy.FirstSuccessful) {
                get("/api/v1/agents") { call.respond(HttpStatusCode.OK) }
                post("/api/v1/agent/example") {
                    // Re-read body to prove DoubleReceive plus the capture plugin haven't broken downstream access.
                    val body = call.receive<ByteArray>().toString(Charsets.UTF_8)
                    call.respond(HttpStatusCode.OK, body)
                }
                post("/api/v1/agent/allowed") {
                    val body = call.receive<ByteArray>().toString(Charsets.UTF_8)
                    call.respond(HttpStatusCode.OK, body)
                }
                post("/api/v1/agent/other") {
                    val body = call.receive<ByteArray>().toString(Charsets.UTF_8)
                    call.respond(HttpStatusCode.OK, body)
                }
            }
            // Mounted OUTSIDE authenticate(...), same as the real runner-protocol routes
            // (ADR-0066): no domain-token check needed for this test, only that
            // HmacBodyCapture never buffers this path's body.
            post("/api/v1/runner/sessions/x/ops/y/tar") {
                call.receive<ByteArray>()
                call.respond(HttpStatusCode.OK)
            }
        }
    }

    @Test
    fun `hmac mode accepts request with valid Vikunja signature`() =
        testApplication {
            val secret = "shh"
            val body = """{"event":"task.created"}"""
            val sig = hexSha256(secret, body)
            application {
                installAuthApp(
                    listOf(
                        AuthConfig.Hmac(
                            keys =
                                mapOf(
                                    "vikunja" to
                                        HmacKeyConfig(
                                            secret = secret,
                                            endpoints = listOf("/api/v1/agent/*"),
                                            signatureHeader = "X-Vikunja-Signature",
                                        ),
                                ),
                        ),
                    ),
                )
            }
            val resp =
                client.post("/api/v1/agent/example") {
                    header("X-Vikunja-Signature", sig)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(body, resp.bodyAsText(), "Route handler must see the original body, not consumed.")
        }

    @Test
    fun `hmac mode rejects tampered body with 401`() =
        testApplication {
            val secret = "shh"
            val realBody = """{"x":1}"""
            val sig = hexSha256(secret, realBody)
            application {
                installAuthApp(
                    listOf(
                        AuthConfig.Hmac(
                            keys = mapOf("k" to HmacKeyConfig(secret = secret, signatureHeader = "X-Sig")),
                        ),
                    ),
                )
            }
            val resp =
                client.post("/api/v1/agent/example") {
                    header("X-Sig", sig)
                    contentType(ContentType.Application.Json)
                    setBody("""{"x":2}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }

    @Test
    fun `hmac mode authenticates on a path outside key (deprecated) endpoints scope`() =
        testApplication {
            // A valid signature now authenticates regardless of the key's endpoints
            // field — that field is deprecated and unenforced here; authorization
            // by grants moves to the route (ADR-0037).
            val secret = "shh"
            val body = "{}"
            val sig = hexSha256(secret, body)
            application {
                installAuthApp(
                    listOf(
                        AuthConfig.Hmac(
                            keys =
                                mapOf(
                                    "k" to
                                        HmacKeyConfig(
                                            secret = secret,
                                            signatureHeader = "X-Sig",
                                            endpoints = listOf("/api/v1/agent/allowed"),
                                        ),
                                ),
                        ),
                    ),
                )
            }
            val resp =
                client.post("/api/v1/agent/other") {
                    header("X-Sig", sig)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.OK, resp.status)
        }

    @Test
    fun `combined mode admits Bearer or HMAC requests`() =
        testApplication {
            val secret = "shh"
            val token = "admin-token"
            val body = "{}"
            val sig = hexSha256(secret, body)
            application {
                installAuthApp(
                    listOf(
                        AuthConfig.Bearer(tokens = mapOf("admin" to TokenConfig(token = token))),
                        AuthConfig.Hmac(keys = mapOf("k" to HmacKeyConfig(secret = secret, signatureHeader = "X-Sig"))),
                    ),
                )
            }
            val bearerResp =
                client.get("/api/v1/agents") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            assertEquals(HttpStatusCode.OK, bearerResp.status, "Bearer scheme should admit /api/v1/agents")

            val hmacResp =
                client.post("/api/v1/agent/example") {
                    header("X-Sig", sig)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.OK, hmacResp.status, "HMAC scheme should admit /api/v1/agent/example")
        }

    @Test
    fun `combined mode rejects credential-less request with 401`() =
        testApplication {
            application {
                installAuthApp(
                    listOf(
                        AuthConfig.Bearer(tokens = mapOf("admin" to TokenConfig(token = "t"))),
                        AuthConfig.Hmac(keys = mapOf("k" to HmacKeyConfig(secret = "s", signatureHeader = "X-Sig"))),
                    ),
                )
            }
            val resp = client.get("/api/v1/agents")
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }

    @Test
    fun `hmac auth does not consume body — downstream route still sees JSON`() =
        testApplication {
            // Regression: DoubleReceive + the body-capture plugin must allow the
            // handler to re-read the same bytes the auth provider already consumed.
            val secret = "shh"
            val body = """{"foo":"bar"}"""
            val sig = hexSha256(secret, body)
            application {
                installAuthApp(
                    listOf(
                        AuthConfig.Hmac(
                            keys = mapOf("k" to HmacKeyConfig(secret = secret, signatureHeader = "X-Sig")),
                        ),
                    ),
                )
            }
            val resp =
                client.post("/api/v1/agent/example") {
                    header("X-Sig", sig)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(resp.bodyAsText().contains("\"foo\":\"bar\""), "Handler did not see the original body")
        }

    @Test
    fun `over-cap body is rejected with 413 before the HMAC capture reads it`() =
        testApplication {
            // Audit H1: HmacBodyCapture reads the ENTIRE body application-scoped
            // (onCall), before any routing-scoped plugin attaches. RequestBodyLimit
            // must therefore be application-level — same placement as
            // configureServer — or signed-webhook bodies are still read unbounded.
            // The status alone can't prove that (a routing-scoped limit also 413s,
            // just after the unbounded read), so the capture is instrumented and
            // must have read nothing.
            val secret = "shh"
            val bigBody = """{"event":"${"x".repeat(2048)}"}"""
            val sig = hexSha256(secret, bigBody)
            val capturedSizes = mutableListOf<Int>()
            application {
                install(RequestBodyLimit) {
                    bodyLimit { 1024 }
                }
                configureStatusPages()
                installAuthApp(
                    listOf(
                        AuthConfig.Hmac(
                            keys = mapOf("k" to HmacKeyConfig(secret = secret, signatureHeader = "X-Sig")),
                        ),
                    ),
                    onHmacBodyCaptured = { capturedSizes += it },
                )
            }
            val resp =
                client.post("/api/v1/agent/example") {
                    header("X-Sig", sig)
                    contentType(ContentType.Application.Json)
                    setBody(bigBody)
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, resp.status)
            assertTrue(
                capturedSizes.isEmpty(),
                "HmacBodyCapture read the over-cap body into memory ($capturedSizes bytes) — " +
                    "RequestBodyLimit must reject the request before the capture's receive",
            )
        }

    @Test
    fun `HmacBodyCapture skips runner-protocol paths but still captures HMAC-protected routes`() =
        testApplication {
            // §12 rule 4: runner-protocol routes are mounted outside authenticate(...) and
            // can never be satisfied by an HMAC provider, so a body captured there is pure
            // waste — and with the lifted per-route body limit (maxTransferBytes, up to
            // 1 GiB) it would be an unauthenticated heap allocation. HmacBodyCapture must
            // skip these paths entirely, while still capturing everywhere else.
            val secret = "shh"
            val capturedSizes = mutableListOf<Int>()
            application {
                installAuthApp(
                    listOf(
                        AuthConfig.Hmac(
                            keys = mapOf("k" to HmacKeyConfig(secret = secret, signatureHeader = "X-Sig")),
                        ),
                    ),
                    onHmacBodyCaptured = { capturedSizes += it },
                )
            }
            val runnerResp = client.post("/api/v1/runner/sessions/x/ops/y/tar") { setBody(ByteArray(1024)) }
            assertEquals(HttpStatusCode.OK, runnerResp.status)
            assertTrue(capturedSizes.isEmpty(), "HmacBodyCapture must not buffer a runner-protocol path's body")

            val body = "{}"
            val sig = hexSha256(secret, body)
            val hmacResp =
                client.post("/api/v1/agent/example") {
                    header("X-Sig", sig)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.OK, hmacResp.status)
            assertTrue(capturedSizes.isNotEmpty(), "HmacBodyCapture must still capture an HMAC-protected route's body")
        }

    @Test
    fun `hmac provider binds the query string when the template uses the query token`() =
        testApplication {
            // The provider passes the raw query string to the verifier, so a key whose
            // template binds {{query}} rejects a signature replayed on another query.
            val secret = "shh"
            val body = "{}"
            val sig = hexSha256(secret, "a=1|$body")
            application {
                installAuthApp(
                    listOf(
                        AuthConfig.Hmac(
                            keys =
                                mapOf(
                                    "k" to
                                        HmacKeyConfig(
                                            secret = secret,
                                            signatureHeader = "X-Sig",
                                            payloadTemplate = "{{query}}|{{body}}",
                                        ),
                                ),
                        ),
                    ),
                )
            }
            val signed =
                client.post("/api/v1/agent/example?a=1") {
                    header("X-Sig", sig)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.OK, signed.status)

            val otherQuery =
                client.post("/api/v1/agent/example?a=2") {
                    header("X-Sig", sig)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Unauthorized, otherQuery.status, "signature must not carry over to another query")
        }

    @Test
    fun `hmac provider rejects a timestamped signature replayed within its window`() =
        testApplication {
            // Production builds the verifier with an HmacReplayCache (M13): the same
            // timestamped signature authenticates once, then is refused as a replay.
            val secret = "shh"
            val body = "{}"
            val ts = (System.currentTimeMillis() / 1000).toString()
            val sig = hexSha256(secret, "$ts.$body")
            application {
                installAuthApp(
                    listOf(
                        AuthConfig.Hmac(
                            keys =
                                mapOf(
                                    "k" to
                                        HmacKeyConfig(
                                            secret = secret,
                                            signatureHeader = "X-Sig",
                                            payloadTemplate = "{{timestamp}}.{{body}}",
                                            timestampHeader = "X-Ts",
                                        ),
                                ),
                        ),
                    ),
                )
            }
            val send =
                suspend {
                    client.post("/api/v1/agent/example") {
                        header("X-Sig", sig)
                        header("X-Ts", ts)
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                }
            assertEquals(HttpStatusCode.OK, send().status)
            assertEquals(HttpStatusCode.Unauthorized, send().status, "a replayed signature must be rejected")
        }
}
