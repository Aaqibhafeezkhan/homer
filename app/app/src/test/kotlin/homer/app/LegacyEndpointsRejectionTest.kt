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

class LegacyEndpointsRejectionTest {
    private fun cfg(apiAuth: AuthConfig) =
        AppConfig(
            server = Server(apiAuth = apiAuth),
            agentsSearchPath = "/a",
            workspaceRootPath = "/w",
            databasePath = "/d.db",
            langfuse = LangfuseConfig("http://localhost", "pk", "sk"),
            openTelemetryExport = OpenTelemetryExport.LOG,
        )

    @Test
    fun `a token with endpoints fails startup naming grants`() {
        val auth = AuthConfig.Bearer(mapOf("old" to TokenConfig(token = "t", endpoints = listOf("*"))))
        val ex = assertFailsWith<ConfigurationException> { validateNoLegacyEndpoints(cfg(auth)) }
        assertTrue(ex.message!!.contains("grants"))
        assertTrue(ex.message!!.contains("role: admin"))
    }

    @Test
    fun `a principal with neither role nor grants fails startup`() {
        val auth = AuthConfig.Bearer(mapOf("empty" to TokenConfig(token = "t")))
        assertFailsWith<ConfigurationException> { validateNoLegacyEndpoints(cfg(auth)) }
    }

    @Test
    fun `a token with grants passes`() {
        val auth =
            AuthConfig.Bearer(
                mapOf(
                    "ci" to
                        TokenConfig(token = "t", grants = listOf(homer.app.models.Grant(listOf("*"), listOf(homer.app.models.Action.RUN)))),
                ),
            )
        validateNoLegacyEndpoints(cfg(auth))
    }
}
