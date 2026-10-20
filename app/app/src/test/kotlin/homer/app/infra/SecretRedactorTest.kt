package homer.app.infra

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SecretRedactorTest {
    @Test
    fun `redacts only declared secret values`() {
        val r = SecretRedactor.from(mapOf("A" to "sekret", "B" to "public"), setOf("A"))
        assertEquals("x *** y *** z", r.redact("x sekret y sekret z"))
        // B is not flagged secret, so its value is left intact.
        assertEquals("public stays", r.redact("public stays"))
    }

    @Test
    fun `redacts JSON-escaped occurrences of a multi-line secret`() {
        val secret = "-----BEGIN KEY-----\nabc\n-----END KEY-----"
        val r = SecretRedactor.from(mapOf("KEY" to secret), setOf("KEY"))
        val encoded = buildJsonObject { put("result", "leaked $secret here") }.toString()
        assertEquals("""{"result":"leaked *** here"}""", r.redact(encoded))
    }

    @Test
    fun `redacts JSON-escaped occurrences of a secret with quotes and backslashes`() {
        val secret = "pa\"ss\\word"
        val r = SecretRedactor.from(mapOf("PW" to secret), setOf("PW"))
        val encoded = buildJsonObject { put("output", "use $secret now") }.toString()
        assertEquals("""{"output":"use *** now"}""", r.redact(encoded))
        // The raw (unescaped) form is still redacted from plain text.
        assertEquals("use *** now", r.redact("use $secret now"))
    }

    @Test
    fun `redacts a double-encoded secret (JSON document stored as a string value)`() {
        val secret = "line1\nline2"
        val r = SecretRedactor.from(mapOf("S" to secret), setOf("S"))
        // Inner document contains the secret; the outer object stores it as a string
        // value, so the secret ends up escaped twice.
        val inner = buildJsonObject { put("token", secret) }.toString()
        val outer = buildJsonObject { put("payload", inner) }.toString()
        assertEquals("""{"payload":"{\"token\":\"***\"}"}""", r.redact(outer))
    }

    @Test
    fun `NONE and empty or missing secrets are no-ops`() {
        assertEquals("unchanged", SecretRedactor.NONE.redact("unchanged"))
        // Explicit empty host env: `from` now falls back to it for a name that is
        // not an env key, so leaving it to the real host would make this host-dependent.
        assertSame(SecretRedactor.NONE, SecretRedactor.from(mapOf("A" to ""), setOf("A"), hostEnv = { null }))
        assertSame(SecretRedactor.NONE, SecretRedactor.from(emptyMap(), setOf("A"), hostEnv = { null }))
    }

    @Test
    fun `masks the bare host credential inside a composite env value`() {
        // The gap this closes: `HEADER: "Bearer @SYSENV:REPO_TOKEN"` resolves to
        // `Bearer <token>`, so only that whole string was a needle. Output holding
        // just the token — a script that stripped the prefix, a tool echoing the
        // credential alone — matched nothing and reached telemetry in the clear.
        val token = "tok-ABCDEF123456"
        val r =
            SecretRedactor.from(
                env = mapOf("HEADER" to "Bearer $token"),
                // What SysEnvResolver.secretNamesFor returns for that raw value.
                secretEnvNames = setOf("HEADER", "REPO_TOKEN"),
                hostEnv = { name -> token.takeIf { name == "REPO_TOKEN" } },
            )

        assertEquals("auth: ***", r.redact("auth: Bearer $token"))
        assertEquals("token is ***", r.redact("token is $token"))
    }

    @Test
    fun `minNeedleLength excludes short needles from the value screen`() {
        // ArtifactNameResolver's use case: a short @SYSENV-sourced global ("0", "1",
        // "prod") must not become a needle, or it would match as a substring of nearly
        // any rendered stem and hard-fail every command.
        val r =
            SecretRedactor.from(
                env = mapOf("SHORT" to "0", "LONG" to "s3cr3t-value-long-enough"),
                secretEnvNames = setOf("SHORT", "LONG"),
                minNeedleLength = 8,
            )
        assertEquals("cmd_0", r.redact("cmd_0"))
        assertEquals("saw ***", r.redact("saw s3cr3t-value-long-enough"))
    }

    @Test
    fun `an env key shadows a same-named host variable`() {
        // The workspace value is the one the child process can echo; the host value
        // under the same name is not what lands in the command env.
        val r =
            SecretRedactor.from(
                env = mapOf("TOKEN" to "workspace-value"),
                secretEnvNames = setOf("TOKEN"),
                hostEnv = { "host-value" },
            )

        assertEquals("saw ***", r.redact("saw workspace-value"))
        assertEquals("saw host-value", r.redact("saw host-value"))
    }
}
