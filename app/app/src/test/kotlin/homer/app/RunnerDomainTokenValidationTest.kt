package homer.app

import homer.app.exceptions.ConfigurationException
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.HmacKeyConfig
import homer.app.models.PasswordUserConfig
import homer.app.models.RunnerConfig
import homer.app.models.Server
import homer.app.models.TokenConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class RunnerDomainTokenValidationTest {
    private val user = PasswordUserConfig(passwordHash = "bcrypt-hash-placeholder", role = "admin")

    private fun config(
        auth: AuthConfig,
        domain: String,
    ) = AppConfig(
        server = Server(apiAuth = auth),
        agentsSearchPath = "unused",
        workspaceRootPath = "unused",
        databasePath = "unused",
        runners = mapOf("workers" to RunnerConfig.External(token = domain)),
    )

    private fun bearer(token: String) = AuthConfig.Bearer(tokens = mapOf("ops" to TokenConfig(token = token, role = "admin")))

    private fun hmac(secret: String) = AuthConfig.Hmac(keys = mapOf("hook" to HmacKeyConfig(secret = secret, role = "admin")))

    private fun password(
        secret: String,
        previous: String? = null,
    ) = AuthConfig.Password(users = mapOf("alice" to user), sessionSecret = secret, sessionSecretPrevious = previous)

    private fun assertRefused(
        auth: AuthConfig,
        domain: String,
    ) {
        val ex = assertThrows<IllegalStateException> { validateRunnerDomainTokens(config(auth, domain)) }
        assertEquals(true, ex.message!!.contains("runner 'workers'"), "the message names the alias, got: ${ex.message}")
        assertEquals(false, ex.message!!.contains(domain), "the credential value must not be echoed")
    }

    @Test
    fun `a domain token equal to an apiAuth bearer token fails startup`() = assertRefused(bearer("shared"), domain = "shared")

    @Test
    fun `a domain token equal to an HMAC key secret fails startup`() = assertRefused(hmac("shared"), domain = "shared")

    @Test
    fun `a domain token equal to a session signing secret fails startup`() = assertRefused(password("shared"), domain = "shared")

    @Test
    fun `a domain token equal to a rotated-out session signing secret fails startup`() =
        assertRefused(password("current", previous = "shared"), domain = "shared")

    @Test
    fun `a collision inside a combined scheme fails startup`() =
        assertRefused(AuthConfig.Combined(schemes = listOf(bearer("ops-token"), hmac("shared"))), domain = "shared")

    @Test
    fun `distinct credential values pass`() {
        validateRunnerDomainTokens(
            config(
                AuthConfig.Combined(schemes = listOf(bearer("ops-token"), hmac("hmac-secret"), password("session-secret"))),
                domain = "domain-token",
            ),
        )
    }

    @Test
    fun `a domain token shorter than 32 characters fails startup without echoing it (CWE-331)`() {
        val short = "a".repeat(31)
        val ex = assertThrows<ConfigurationException> { validateRunnerDomainTokenStrength(config(bearer("ops-token"), domain = short)) }
        assertEquals(true, ex.message!!.contains("runner 'workers'"), "the message names the alias, got: ${ex.message}")
        assertEquals(false, ex.message!!.contains(short), "the credential value must not be echoed")
    }

    @Test
    fun `a 32-character domain token passes the strength check`() {
        validateRunnerDomainTokenStrength(config(bearer("ops-token"), domain = "a".repeat(32)))
    }
}
