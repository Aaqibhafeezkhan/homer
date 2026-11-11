package homer.app

import homer.app.exceptions.ConfigurationException
import homer.app.models.Action
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.Grant
import homer.app.models.LangfuseConfig
import homer.app.models.OpenTelemetryExport
import homer.app.models.PasswordUserConfig
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

    /** A password scheme with the single user [name]; the secret is a dummy, only the principal validation is under test. */
    private fun passwordAuth(
        name: String,
        user: PasswordUserConfig,
    ) = AuthConfig.Password(
        users = mapOf(name to user),
        sessionSecret = "secret-key-v1-aaaaaaaaaaaaaaaaaa",
    )

    @Test
    fun `a password user with endpoints fails startup naming grants`() {
        val auth = passwordAuth("old", PasswordUserConfig(passwordHash = "irrelevant", endpoints = listOf("*")))
        val ex = assertFailsWith<ConfigurationException> { validateNoLegacyEndpoints(cfg(auth)) }
        assertTrue(ex.message!!.contains("grants"))
        assertTrue(ex.message!!.contains("role: admin"))
    }

    @Test
    fun `a password user with neither role nor grants fails startup`() {
        val auth = passwordAuth("empty", PasswordUserConfig(passwordHash = "irrelevant"))
        assertFailsWith<ConfigurationException> { validateNoLegacyEndpoints(cfg(auth)) }
    }

    @Test
    fun `a password user with grants passes`() {
        val auth =
            passwordAuth(
                "ci",
                PasswordUserConfig(
                    passwordHash = "irrelevant",
                    grants = listOf(Grant(listOf("*"), listOf(Action.RUN))),
                ),
            )
        validateNoLegacyEndpoints(cfg(auth))
    }
}
