package homer.app

import homer.app.infra.SysEnvResolver
import homer.app.models.AuthConfig
import homer.app.models.HmacAlgorithm
import homer.app.models.HmacEncoding
import homer.app.models.HmacKeyConfig
import homer.app.models.PasswordUserConfig
import homer.app.models.TokenConfig
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins [resolveAuth]'s coverage of every secret-bearing field on every
 * [AuthConfig] variant. The original cookie-auth landing resolved
 * `sessionSecret` but forgot `users[*].passwordHash`, which meant operators
 * using `passwordHash: "@SYSENV:..."` would have bcrypt verify against the
 * literal placeholder string and get an opaque "Invalid credentials" 401.
 */
class ResolveAuthTest {
    private val resolver = SysEnvResolver()

    @Test
    fun `Password resolves users passwordHash, sessionSecret, and sessionSecretPrevious`() {
        // Pick env vars guaranteed to be set in any JVM process. Their exact
        // values aren't checked — only that resolution actually happened
        // (i.e. the string no longer starts with the `@SYSENV:` prefix).
        val varName = "PATH"
        val expected = System.getenv(varName) ?: error("$varName must be set for this test")

        val unresolved =
            AuthConfig.Password(
                users =
                    mapOf(
                        "admin" to PasswordUserConfig(passwordHash = "@SYSENV:$varName", endpoints = listOf("*")),
                        "reader" to PasswordUserConfig(passwordHash = "@SYSENV:$varName"),
                    ),
                sessionSecret = "@SYSENV:$varName",
                sessionSecretPrevious = "@SYSENV:$varName",
                sessionTtlMin = 60,
                cookieSecure = false,
            )

        val resolved = resolveAuth(unresolved, resolver) as AuthConfig.Password
        assertEquals(expected, resolved.users["admin"]!!.passwordHash, "passwordHash must be resolved")
        assertEquals(expected, resolved.users["reader"]!!.passwordHash, "passwordHash must be resolved for every user")
        assertEquals(expected, resolved.sessionSecret)
        assertEquals(expected, resolved.sessionSecretPrevious)
    }

    @Test
    fun `Bearer resolves tokens token field`() {
        val varName = "PATH"
        val expected = System.getenv(varName) ?: error("$varName must be set for this test")
        val resolved =
            resolveAuth(
                AuthConfig.Bearer(tokens = mapOf("admin" to TokenConfig(token = "@SYSENV:$varName"))),
                resolver,
            ) as AuthConfig.Bearer
        assertEquals(expected, resolved.tokens["admin"]!!.token)
    }

    @Test
    fun `Bearer rejects a SYSENV token that resolves to blank`() {
        // TokenConfig validates in init, and resolveAuth rebuilds via copy(),
        // so an env var holding an empty secret must fail startup, not
        // silently configure a token that matches `Authorization: Bearer `.
        val blankResolver = mockk<SysEnvResolver>()
        every { blankResolver.resolve(any()) } returns ""
        assertFailsWith<IllegalArgumentException> {
            resolveAuth(
                AuthConfig.Bearer(tokens = mapOf("admin" to TokenConfig(token = "@SYSENV:EMPTY_VAR"))),
                blankResolver,
            )
        }
    }

    @Test
    fun `Hmac resolves keys secret field`() {
        val varName = "PATH"
        val expected = System.getenv(varName) ?: error("$varName must be set for this test")
        val resolved =
            resolveAuth(
                AuthConfig.Hmac(
                    keys =
                        mapOf(
                            "wh" to
                                HmacKeyConfig(
                                    secret = "@SYSENV:$varName",
                                    algorithm = HmacAlgorithm.SHA256,
                                    encoding = HmacEncoding.HEX,
                                ),
                        ),
                ),
                resolver,
            ) as AuthConfig.Hmac
        assertEquals(expected, resolved.keys["wh"]!!.secret)
    }

    @Test
    fun `Hmac rejects a SYSENV secret that resolves to blank`() {
        // Same guarantee as the bearer case above: an env var holding an
        // empty secret must fail startup, not configure a public HMAC key
        // any client can sign with.
        val blankResolver = mockk<SysEnvResolver>()
        every { blankResolver.resolve(any()) } returns ""
        assertFailsWith<IllegalArgumentException> {
            resolveAuth(
                AuthConfig.Hmac(keys = mapOf("wh" to HmacKeyConfig(secret = "@SYSENV:EMPTY_VAR"))),
                blankResolver,
            )
        }
    }

    @Test
    fun `Combined recurses into every inner scheme`() {
        val varName = "PATH"
        val expected = System.getenv(varName) ?: error("$varName must be set for this test")
        val resolved =
            resolveAuth(
                AuthConfig.Combined(
                    schemes =
                        listOf(
                            AuthConfig.Bearer(tokens = mapOf("admin" to TokenConfig(token = "@SYSENV:$varName"))),
                            AuthConfig.Password(
                                users = mapOf("admin" to PasswordUserConfig(passwordHash = "@SYSENV:$varName")),
                                sessionSecret = "@SYSENV:$varName",
                                cookieSecure = false,
                            ),
                        ),
                ),
                resolver,
            ) as AuthConfig.Combined
        val bearer = resolved.schemes.filterIsInstance<AuthConfig.Bearer>().single()
        val password = resolved.schemes.filterIsInstance<AuthConfig.Password>().single()
        assertEquals(expected, bearer.tokens["admin"]!!.token)
        assertEquals(expected, password.users["admin"]!!.passwordHash)
        assertEquals(expected, password.sessionSecret)
    }

    @Test
    fun `Unsecured passes through unchanged`() {
        assertEquals(AuthConfig.Unsecured, resolveAuth(AuthConfig.Unsecured, resolver))
    }

    @Test
    fun `sessionSecretPrevious null stays null`() {
        val resolved =
            resolveAuth(
                AuthConfig.Password(
                    users = mapOf("a" to PasswordUserConfig(passwordHash = "@SYSENV:PATH")),
                    sessionSecret = "@SYSENV:PATH",
                    sessionSecretPrevious = null,
                    cookieSecure = false,
                ),
                resolver,
            ) as AuthConfig.Password
        assertEquals(null, resolved.sessionSecretPrevious)
    }
}
