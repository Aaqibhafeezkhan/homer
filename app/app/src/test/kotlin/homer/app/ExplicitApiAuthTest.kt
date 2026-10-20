package homer.app

import homer.app.exceptions.ConfigurationException
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.LangfuseConfig
import homer.app.models.OpenTelemetryExport
import homer.app.models.Server
import homer.app.models.TokenConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Secure-by-default (ADR-0036): a config that omits `server.apiAuth` must fail
 * startup instead of silently running unsecured. Running without auth requires
 * the explicit `apiAuth: !<unsecured>` opt-in.
 */
class ExplicitApiAuthTest {
    private fun configWith(apiAuth: AuthConfig?) =
        AppConfig(
            server = Server(apiAuth = apiAuth),
            agentsSearchPath = "/tmp/agents",
            workspaceRootPath = "/tmp/workspace",
            databasePath = "/tmp/homer-test.db",
            langfuse = LangfuseConfig(serverUrl = "http://localhost", publicKey = "pk", secretKey = "sk"),
            openTelemetryExport = OpenTelemetryExport.LOG,
        )

    @Test
    fun `omitted apiAuth throws with a message naming the opt-in`() {
        val ex =
            assertFailsWith<ConfigurationException> {
                validateExplicitApiAuth(configWith(apiAuth = null))
            }
        assertTrue(ex.message?.contains("!<unsecured>") == true, "message must show the explicit opt-in")
    }

    @Test
    fun `explicit Unsecured does not throw`() {
        validateExplicitApiAuth(configWith(AuthConfig.Unsecured))
    }

    @Test
    fun `configured bearer auth does not throw`() {
        validateExplicitApiAuth(configWith(AuthConfig.Bearer(tokens = mapOf("ci" to TokenConfig(token = "t")))))
    }
}
