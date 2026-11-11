package homer.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import homer.app.exceptions.ConfigurationException
import homer.app.models.Action
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.ExtraUploadSpec
import homer.app.models.Grant
import homer.app.models.HmacKeyConfig
import homer.app.models.LangfuseConfig
import homer.app.models.OpenTelemetryExport
import homer.app.models.PasswordUserConfig
import homer.app.models.RunnerConfig
import homer.app.models.Server
import homer.app.models.TokenConfig
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * The boot-time validator wiring: every config a `validate*` refuses must still be refused
 * through the real startup path (`configureServer` → `validateStartupConfig`), so deleting a
 * `validate*` call from `validateStartupConfig` fails a test instead of silently shipping a
 * misconfigured deployment (ADR-0047 rule 8). The validators' own cases stay in their
 * per-validator test classes; these boot the whole module.
 *
 * Every secret, token and HMAC key here is an inline fixture, never echoed into an assertion
 * message (root §12 rule 7).
 */
@ExtendWith(ResetDrainingExtension::class)
class StartupValidationWiringTest {
    @TempDir
    lateinit var agentsDir: File

    @TempDir
    lateinit var workspaceDir: File

    private fun config(
        apiAuth: AuthConfig? = AuthConfig.Unsecured,
        corsAllowedOrigins: List<String> = emptyList(),
        runners: Map<String, RunnerConfig> = emptyMap(),
        langfuse: LangfuseConfig = LangfuseConfig(serverUrl = "http://localhost:9", publicKey = "pk-test", secretKey = "sk-test"),
        openTelemetryExport: OpenTelemetryExport = OpenTelemetryExport.LOG,
    ) = AppConfig(
        server = Server(apiAuth = apiAuth, corsAllowedOrigins = corsAllowedOrigins),
        agentsSearchPath = agentsDir.absolutePath,
        workspaceRootPath = workspaceDir.absolutePath,
        databasePath = File(workspaceDir, "homer.db").absolutePath,
        langfuse = langfuse,
        openTelemetryExport = openTelemetryExport,
        runners = runners,
    )

    /** Boots [cfg] through the real startup path and returns the refusal the boot threw. */
    private inline fun <reified T : Throwable> assertBootRefused(cfg: AppConfig): T =
        assertThrows<T> {
            testApplication {
                application { configureServer(cfg) }
                startApplication()
            }
        }

    @Test
    fun `a legacy endpoints principal is refused at startup`() {
        val cfg =
            config(
                apiAuth =
                    AuthConfig.Bearer(
                        tokens = mapOf("old" to TokenConfig(token = "legacy-fixture-token", endpoints = listOf("*"))),
                    ),
            )
        val ex = assertBootRefused<ConfigurationException>(cfg)
        assertTrue(ex.message!!.contains("grants"), "got: ${ex.message}")
    }

    @Test
    fun `an omitted apiAuth is refused at startup`() {
        val ex = assertBootRefused<ConfigurationException>(config(apiAuth = null))
        assertTrue(ex.message!!.contains("!<unsecured>"), "got: ${ex.message}")
    }

    @Test
    fun `a runner domain token equal to an apiAuth credential is refused at startup`() {
        val shared = "shared-credential-fixture-0123456789"
        val cfg =
            config(
                apiAuth = AuthConfig.Bearer(tokens = mapOf("ops" to TokenConfig(token = shared, role = "admin"))),
                runners = mapOf("workers" to RunnerConfig.External(token = shared)),
            )
        val ex = assertBootRefused<IllegalStateException>(cfg)
        assertTrue(ex.message!!.contains("runner 'workers'"), "got: ${ex.message}")
        assertEquals(false, ex.message!!.contains(shared), "the credential value must not be echoed")
    }

    @Test
    fun `a too-short runner domain token is refused at startup (CWE-331)`() {
        val cfg =
            config(
                apiAuth = AuthConfig.Bearer(tokens = mapOf("ops" to TokenConfig(token = "ops-fixture-token", role = "admin"))),
                runners = mapOf("workers" to RunnerConfig.External(token = "short-runner-token")),
            )
        val ex = assertBootRefused<ConfigurationException>(cfg)
        assertTrue(ex.message!!.contains("runner 'workers'"), "got: ${ex.message}")
    }

    @Test
    fun `cookie auth without pinned CORS origins is refused at startup`() {
        val cfg =
            config(
                apiAuth =
                    AuthConfig.Password(
                        users = mapOf("alice" to PasswordUserConfig(passwordHash = "bcrypt-hash-fixture", role = "admin")),
                        sessionSecret = "session-secret-fixture-0123456789abcdef",
                    ),
            )
        val ex = assertBootRefused<ConfigurationException>(cfg)
        assertTrue(ex.message!!.contains("corsAllowedOrigins"), "got: ${ex.message}")
    }

    @Test
    fun `an HMAC key whose signature is replayable across agents is refused at startup`() {
        val cfg =
            config(
                apiAuth =
                    AuthConfig.Hmac(
                        keys = mapOf("hook" to HmacKeyConfig(secret = "hmac-secret-fixture", role = "admin")),
                    ),
            )
        val ex = assertBootRefused<ConfigurationException>(cfg)
        assertTrue(ex.message!!.contains("{{path}}"), "got: ${ex.message}")
    }

    @Test
    fun `a too-short session signing secret is refused at startup`() {
        val cfg =
            config(
                apiAuth =
                    AuthConfig.Password(
                        users = mapOf("alice" to PasswordUserConfig(passwordHash = "bcrypt-hash-fixture", role = "admin")),
                        sessionSecret = "short-secret",
                    ),
                corsAllowedOrigins = listOf("http://console.example.com"),
            )
        val ex = assertBootRefused<ConfigurationException>(cfg)
        assertTrue(ex.message!!.contains("at least 32 characters"), "got: ${ex.message}")
    }

    @Test
    fun `a plaintext non-loopback Langfuse URL is refused at startup`() {
        val cfg =
            config(
                langfuse = LangfuseConfig(serverUrl = "http://langfuse.example.com", publicKey = "pk-test", secretKey = "sk-test"),
                openTelemetryExport = OpenTelemetryExport.LANGFUSE,
            )
        val ex = assertBootRefused<ConfigurationException>(cfg)
        assertTrue(ex.message!!.contains("https://"), "got: ${ex.message}")
    }

    @Test
    fun `a runner extraUploads entry with an unreadable local path is refused at startup`() {
        val cfg =
            config(
                runners =
                    mapOf(
                        "workers" to
                            RunnerConfig.External(
                                token = "workers-fixture-token-0123456789",
                                // The local path is checked only at the wiring point: the shape
                                // rules run in the model's init, the filesystem check does not.
                                extraUploads = listOf(ExtraUploadSpec(local = "/nonexistent/homer/extra", remote = "inputs")),
                            ),
                    ),
            )
        val ex = assertBootRefused<ConfigurationException>(cfg)
        assertTrue(ex.message!!.contains("extraUploads"), "got: ${ex.message}")
        assertTrue(ex.message!!.contains("runner 'workers'"), "got: ${ex.message}")
    }

    @Test
    fun `an HMAC key with no replay window warns at startup but still boots`() {
        val appLogger = LoggerFactory.getLogger("homer.app.Application") as LogbackLogger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        appLogger.addAppender(appender)
        try {
            val cfg =
                config(
                    apiAuth =
                        AuthConfig.Hmac(
                            keys =
                                mapOf(
                                    "hook" to
                                        HmacKeyConfig(
                                            secret = "hmac-secret-fixture",
                                            payloadTemplate = "{{method}}.{{path}}.{{body}}",
                                            grants = listOf(Grant(agents = listOf("webhook"), actions = listOf(Action.RUN))),
                                        ),
                                ),
                        ),
                )
            testApplication {
                application { configureServer(cfg) }
                startApplication()
            }

            val warnings =
                appender.list
                    .filter { it.level == Level.WARN }
                    .map { it.formattedMessage }
            assertTrue(
                warnings.any { it.contains("has no timestampHeader") },
                "the non-fatal replay-window warning must still be emitted; got $warnings",
            )
        } finally {
            appLogger.detachAppender(appender)
        }
    }

    @Test
    fun `a valid config still boots and serves`() =
        testApplication {
            application { configureServer(config()) }

            assertEquals(HttpStatusCode.OK, client.get("/api/v1/agents").status)
        }
}
