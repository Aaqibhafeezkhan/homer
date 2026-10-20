package homer.app.models

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppConfigSerializerTest {
    private val json =
        Json {
            isLenient = true
            encodeDefaults = true
        }
    private val yamlWithPropertyPolymorphism =
        Yaml(
            configuration = YamlConfiguration(polymorphismStyle = PolymorphismStyle.Property),
        )

    // ===== JSON Tests =====

    @Test
    fun `JSON flat string deserializes to TokenConfig with no endpoints`() {
        val input = "\"my-secret-token\""
        val result = json.decodeFromString(TokenConfig.serializer(), input)
        assertEquals("my-secret-token", result.token)
        assertNull(result.endpoints)
    }

    @Test
    fun `JSON object deserializes to TokenConfig with specified endpoints`() {
        val input = """{"token":"ci-token","endpoints":["/api/v1/agent/*"]}"""
        val result = json.decodeFromString(TokenConfig.serializer(), input)
        assertEquals("ci-token", result.token)
        assertEquals(listOf("/api/v1/agent/*"), result.endpoints)
    }

    @Test
    fun `JSON object missing endpoints defaults to null`() {
        val input = """{"token":"some-token"}"""
        val result = json.decodeFromString(TokenConfig.serializer(), input)
        assertEquals("some-token", result.token)
        assertNull(result.endpoints)
    }

    @Test
    fun `JSON mixed old and new format in Bearer tokens map`() {
        val input =
            """
            {
              "type": "bearer",
              "tokens": {
                "admin": {"token": "admin-secret", "endpoints": ["*"]},
                "ci": {"token": "ci-token", "endpoints": ["/api/v1/agent/*"]},
                "legacy": "flat-token"
              }
            }
            """.trimIndent()
        val result = json.decodeFromString(AuthConfig.serializer(), input) as AuthConfig.Bearer
        assertEquals("admin-secret", result.tokens["admin"]!!.token)
        assertEquals(listOf("*"), result.tokens["admin"]!!.endpoints)
        assertEquals("ci-token", result.tokens["ci"]!!.token)
        assertEquals(listOf("/api/v1/agent/*"), result.tokens["ci"]!!.endpoints)
        assertEquals("flat-token", result.tokens["legacy"]!!.token)
        assertNull(result.tokens["legacy"]!!.endpoints)
    }

    // ===== YAML Tests =====

    @Test
    fun `YAML scalar deserializes to TokenConfig with no endpoints`() {
        val input = "my-secret-token"
        val result = Yaml.default.decodeFromString(TokenConfig.serializer(), input)
        assertEquals("my-secret-token", result.token)
        assertNull(result.endpoints)
    }

    @Test
    fun `YAML map deserializes to TokenConfig with specified endpoints`() {
        val input =
            """
            token: ci-token
            endpoints:
              - /api/v1/agent/*
            """.trimIndent()
        val result = Yaml.default.decodeFromString(TokenConfig.serializer(), input)
        assertEquals("ci-token", result.token)
        assertEquals(listOf("/api/v1/agent/*"), result.endpoints)
    }

    @Test
    fun `YAML map missing endpoints defaults to null`() {
        val input = "token: some-token"
        val result = Yaml.default.decodeFromString(TokenConfig.serializer(), input)
        assertEquals("some-token", result.token)
        assertNull(result.endpoints)
    }

    @Test
    fun `YAML mixed old and new format in Bearer tokens map`() {
        val input =
            """
            type: bearer
            tokens:
              admin:
                token: admin-secret
                endpoints:
                  - "*"
              ci:
                token: ci-token
                endpoints:
                  - /api/v1/agent/*
              legacy: flat-token
            """.trimIndent()
        val result = yamlWithPropertyPolymorphism.decodeFromString(AuthConfig.serializer(), input) as AuthConfig.Bearer
        assertEquals("admin-secret", result.tokens["admin"]!!.token)
        assertEquals(listOf("*"), result.tokens["admin"]!!.endpoints)
        assertEquals("ci-token", result.tokens["ci"]!!.token)
        assertEquals(listOf("/api/v1/agent/*"), result.tokens["ci"]!!.endpoints)
        assertEquals("flat-token", result.tokens["legacy"]!!.token)
        assertNull(result.tokens["legacy"]!!.endpoints)
    }

    // ===== Blank bearer tokens are rejected (empty-Bearer auth bypass) =====

    @Test
    fun `TokenConfig rejects blank token`() {
        val ex = assertFailsWith<IllegalArgumentException> { TokenConfig(token = "") }
        assertTrue("token" in (ex.message ?: ""))
        assertFailsWith<IllegalArgumentException> { TokenConfig(token = "   ") }
    }

    @Test
    fun `JSON flat empty string token is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString(TokenConfig.serializer(), "\"\"")
        }
    }

    @Test
    fun `JSON object with empty token is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString(TokenConfig.serializer(), """{"token":""}""")
        }
    }

    @Test
    fun `YAML scalar empty token is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Yaml.default.decodeFromString(TokenConfig.serializer(), "\"\"")
        }
    }

    @Test
    fun `YAML map with blank token is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Yaml.default.decodeFromString(TokenConfig.serializer(), "token: \"  \"")
        }
    }

    // ===== HMAC config model =====

    @Test
    fun `HmacKeyConfig defaults`() {
        val cfg = HmacKeyConfig(secret = "s")
        assertNull(cfg.endpoints)
        assertEquals("X-Homer-Signature", cfg.signatureHeader)
        assertEquals(HmacAlgorithm.SHA256, cfg.algorithm)
        assertEquals(HmacEncoding.HEX, cfg.encoding)
        assertEquals("", cfg.prefix)
        assertEquals("{{body}}", cfg.payloadTemplate)
        assertNull(cfg.timestampHeader)
        assertEquals(300L, cfg.replayToleranceSec)
    }

    @Test
    fun `HmacKeyConfig rejects blank secret`() {
        val ex = assertFailsWith<IllegalArgumentException> { HmacKeyConfig(secret = "") }
        assertTrue("secret" in (ex.message ?: ""))
        assertFailsWith<IllegalArgumentException> { HmacKeyConfig(secret = "   ") }
    }

    @Test
    fun `HmacKeyConfig rejects blank signatureHeader`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                HmacKeyConfig(secret = "s", signatureHeader = " ")
            }
        assertTrue("signatureHeader" in (ex.message ?: ""))
    }

    @Test
    fun `HmacKeyConfig rejects non-positive replayToleranceSec`() {
        assertFailsWith<IllegalArgumentException> { HmacKeyConfig(secret = "s", replayToleranceSec = 0) }
        assertFailsWith<IllegalArgumentException> { HmacKeyConfig(secret = "s", replayToleranceSec = -1) }
    }

    @Test
    fun `HmacKeyConfig rejects unknown template token`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                HmacKeyConfig(secret = "s", payloadTemplate = "{{body}}.{{nonce}}")
            }
        assertTrue("nonce" in (ex.message ?: ""))
    }

    @Test
    fun `HmacKeyConfig rejects timestamp token without timestampHeader`() {
        assertFailsWith<IllegalArgumentException> {
            HmacKeyConfig(secret = "s", payloadTemplate = "{{timestamp}}.{{body}}", timestampHeader = null)
        }
    }

    @Test
    fun `HmacKeyConfig accepts timestamp token when timestampHeader set`() {
        HmacKeyConfig(secret = "s", payloadTemplate = "{{timestamp}}.{{body}}", timestampHeader = "X-Stamp")
    }

    @Test
    fun `HmacKeyConfig rejects timestampHeader without timestamp token in template`() {
        // An unbound timestamp is attacker-controlled: a captured signature
        // could be replayed forever with a fresh header value.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                HmacKeyConfig(secret = "s", timestampHeader = "X-Ts")
            }
        assertTrue("{{timestamp}}" in (ex.message ?: ""))
    }

    @Test
    fun `secret-bearing configs redact secrets in toString`() {
        assertFalse("topsecret" in HmacKeyConfig(secret = "topsecret").toString())
        assertFalse("tok-abc" in TokenConfig(token = "tok-abc").toString())
        assertFalse("sk-xyz" in LangfuseConfig(secretKey = "sk-xyz").toString())
        assertFalse("hash-123" in PasswordUserConfig(passwordHash = "hash-123").toString())
        val password =
            AuthConfig.Password(
                users = mapOf("alice" to PasswordUserConfig(passwordHash = "hash-123")),
                sessionSecret = "sess-secret",
                sessionSecretPrevious = "old-secret",
            )
        val s = password.toString()
        assertFalse("sess-secret" in s)
        assertFalse("old-secret" in s)
        assertFalse("hash-123" in s)
        // A blank secret must not render as a fake value.
        assertTrue("***" !in LangfuseConfig().toString())
    }

    @Test
    fun `AuthConfig Combined rejects empty schemes`() {
        assertFailsWith<IllegalArgumentException> { AuthConfig.Combined(schemes = emptyList()) }
    }

    @Test
    fun `AuthConfig Combined rejects nested Combined`() {
        val inner = AuthConfig.Combined(listOf(AuthConfig.Bearer(emptyMap())))
        assertFailsWith<IllegalArgumentException> { AuthConfig.Combined(listOf(inner)) }
    }

    @Test
    fun `AuthConfig Combined rejects Unsecured`() {
        assertFailsWith<IllegalArgumentException> {
            AuthConfig.Combined(listOf(AuthConfig.Unsecured, AuthConfig.Bearer(emptyMap())))
        }
    }

    @Test
    fun `AuthConfig Combined rejects two Bearer entries`() {
        assertFailsWith<IllegalArgumentException> {
            AuthConfig.Combined(listOf(AuthConfig.Bearer(emptyMap()), AuthConfig.Bearer(emptyMap())))
        }
    }

    @Test
    fun `AuthConfig Combined rejects two Hmac entries`() {
        val hmac = AuthConfig.Hmac(keys = mapOf("ci" to HmacKeyConfig(secret = "s")))
        assertFailsWith<IllegalArgumentException> { AuthConfig.Combined(listOf(hmac, hmac)) }
    }

    @Test
    fun `AuthConfig Combined rejects two Password entries`() {
        val password =
            AuthConfig.Password(
                users = mapOf("alice" to PasswordUserConfig(passwordHash = "hash")),
                sessionSecret = "sess-secret",
            )
        assertFailsWith<IllegalArgumentException> { AuthConfig.Combined(listOf(password, password)) }
    }

    @Test
    fun `AuthConfig Combined accepts one Bearer, one Hmac and one Password`() {
        AuthConfig.Combined(
            listOf(
                AuthConfig.Bearer(emptyMap()),
                AuthConfig.Hmac(keys = mapOf("ci" to HmacKeyConfig(secret = "s"))),
                AuthConfig.Password(
                    users = mapOf("alice" to PasswordUserConfig(passwordHash = "hash")),
                    sessionSecret = "sess-secret",
                ),
            ),
        )
    }

    @Test
    fun `AuthConfig Bearer rejects a token key with the reserved session prefix`() {
        // The key becomes the principal name verbatim; "session:" is reserved for
        // cookie auth (the CSRF guard keys on it). See ADR-0016.
        assertFailsWith<IllegalArgumentException> {
            AuthConfig.Bearer(tokens = mapOf("session:ci" to TokenConfig(token = "t")))
        }
    }

    @Test
    fun `AuthConfig Bearer rejects a token key with the reserved hmac prefix`() {
        // The key becomes the principal name verbatim; "hmac:" is reserved for
        // HMAC key principals in a combined config. See ADR-0016.
        assertFailsWith<IllegalArgumentException> {
            AuthConfig.Bearer(tokens = mapOf("hmac:foo" to TokenConfig(token = "t")))
        }
    }

    @Test
    fun `AuthConfig Bearer rejects a token key with the reserved system prefix`() {
        // The key becomes the principal name verbatim; "system:" is reserved for
        // internal synthetic owners (e.g. "system:cron"). A bearer token named
        // "system:cron" would collide with the cron owner and gain owner-based
        // visibility/control over all cron runs. See ADR-0016.
        assertFailsWith<IllegalArgumentException> {
            AuthConfig.Bearer(tokens = mapOf("system:cron" to TokenConfig(token = "t")))
        }
    }

    @Test
    fun `AuthConfig Bearer accepts an ordinary token key`() {
        AuthConfig.Bearer(tokens = mapOf("ci" to TokenConfig(token = "t")))
    }

    @Test
    fun `AuthConfig Hmac round-trips YAML with defaults`() {
        val input =
            """
            type: hmac
            keys:
              vikunja:
                secret: shh
                signatureHeader: X-Vikunja-Signature
                endpoints:
                  - /api/v1/agent/vikunja
            """.trimIndent()
        val parsed = yamlWithPropertyPolymorphism.decodeFromString(AuthConfig.serializer(), input)
        assertTrue(parsed is AuthConfig.Hmac, "expected AuthConfig.Hmac, got $parsed")
        val key = parsed.keys.getValue("vikunja")
        assertEquals("shh", key.secret)
        assertEquals("X-Vikunja-Signature", key.signatureHeader)
        assertEquals(HmacAlgorithm.SHA256, key.algorithm)
        assertEquals(listOf("/api/v1/agent/vikunja"), key.endpoints)
    }

    // ===== Server rate-limit config =====

    @Test
    fun `Server defaults include loginRateLimit of 5 per 60s`() {
        val server = Server()
        assertEquals(5, server.loginRateLimit.limit, "login limit default must stay 5 (behavior-preserving)")
        assertEquals(60L, server.loginRateLimit.refillPeriod)
        // The sibling limiters keep their own distinct defaults.
        assertEquals(1000, server.rateLimit.limit)
        assertEquals(300, server.perCallerRateLimit.limit)
    }

    @Test
    fun `Server YAML parses a custom loginRateLimit and defaults the omitted siblings`() {
        val input =
            """
            loginRateLimit:
              limit: 30
              refillPeriod: 120
            """.trimIndent()
        val parsed = yamlWithPropertyPolymorphism.decodeFromString(Server.serializer(), input)
        assertEquals(30, parsed.loginRateLimit.limit)
        assertEquals(120L, parsed.loginRateLimit.refillPeriod)
        // Omitted → defaults, not zeroed.
        assertEquals(1000, parsed.rateLimit.limit)
        assertEquals(300, parsed.perCallerRateLimit.limit)
    }

    // ===== apiAuth explicit-opt-in contract =====

    @Test
    fun `Server YAML omitting apiAuth parses to null, not silently Unsecured`() {
        // Omitted must be distinguishable from an explicit unsecured opt-in so
        // startup can fail on the former. See ADR-0036.
        val parsed = yamlWithPropertyPolymorphism.decodeFromString(Server.serializer(), "port: 9999")
        assertNull(parsed.apiAuth)
    }

    @Test
    fun `Server YAML with explicit unsecured apiAuth parses to Unsecured`() {
        // Yaml.default is what main() parses server config with (kaml tag style).
        val input =
            """
            apiAuth: !<unsecured> {}
            """.trimIndent()
        val parsed = Yaml.default.decodeFromString(Server.serializer(), input)
        assertEquals(AuthConfig.Unsecured, parsed.apiAuth)
    }

    // ===== Langfuse config defaults =====

    @Test
    fun `LangfuseConfig defaults to empty strings`() {
        val cfg = LangfuseConfig()
        assertEquals("", cfg.serverUrl)
        assertEquals("", cfg.publicKey)
        assertEquals("", cfg.secretKey)
    }

    @Test
    fun `AppConfig deserializes without langfuse and gets empty default`() {
        val input =
            """
            {
              "agentsSearchPath": "/agents",
              "workspaceRootPath": "/workspaces",
              "databasePath": "/var/lib/homer/homer.db"
            }
            """.trimIndent()
        val cfg = json.decodeFromString(AppConfig.serializer(), input)
        assertEquals(LangfuseConfig(), cfg.langfuse)
    }

    // ===== LangGraph state dir resolution =====

    private fun appConfig(
        databasePath: String,
        stateDir: String? = null,
    ) = AppConfig(
        server = Server(langGraph = LangGraphConfig(stateDir = stateDir)),
        agentsSearchPath = "/agents",
        workspaceRootPath = "/workspaces",
        databasePath = databasePath,
        langfuse = LangfuseConfig(serverUrl = "http://lf", publicKey = "p", secretKey = "s"),
    )

    @Test
    fun `resolveLangGraphStateDir derives langgraph subdir beside databasePath when stateDir null`() {
        val resolved = appConfig(databasePath = "/var/lib/homer/homer.db").resolveLangGraphStateDir("ci")
        assertEquals("/var/lib/homer/langgraph/ci", resolved)
    }

    @Test
    fun `resolveLangGraphStateDir returns explicit stateDir when set`() {
        val resolved =
            appConfig(databasePath = "/var/lib/homer/homer.db", stateDir = "/srv/checkpoints")
                .resolveLangGraphStateDir("ci")
        assertEquals("/srv/checkpoints/ci", resolved)
    }

    @Test
    fun `resolveLangGraphStateDir separates owners and buckets unowned runs`() {
        val config = appConfig(databasePath = "/var/lib/homer/homer.db", stateDir = "/srv/checkpoints")
        assertEquals("/srv/checkpoints/alice", config.resolveLangGraphStateDir("alice"))
        assertEquals("/srv/checkpoints/bob", config.resolveLangGraphStateDir("bob"))
        assertEquals("/srv/checkpoints/_unowned", config.resolveLangGraphStateDir(null))
        assertEquals("/srv/checkpoints/_unowned", config.resolveLangGraphStateDir(""))
    }

    @Test
    fun `resolveLangGraphStateDir hashes owner names that could escape or collide`() {
        val config = appConfig(databasePath = "/var/lib/homer/homer.db", stateDir = "/srv/checkpoints")
        // Traversal and separators must not escape the configured root.
        for (hostile in listOf("../../etc", "..", ".", "a/b", "a\\b", "with space", "x".repeat(65))) {
            val resolved = config.resolveLangGraphStateDir(hostile)
            assertTrue(
                resolved.startsWith("/srv/checkpoints/"),
                "owner '$hostile' escaped the root: $resolved",
            )
            assertEquals(1, resolved.removePrefix("/srv/checkpoints/").split("/").size)
        }
        // Distinct names that would sanitize onto one segment stay distinct.
        assertNotEquals(
            config.resolveLangGraphStateDir("a/b"),
            config.resolveLangGraphStateDir("a_b"),
        )
    }

    @Test
    fun `resolveLangGraphStateDir keeps the unowned bucket unreachable by any principal`() {
        val config = appConfig(databasePath = "/var/lib/homer/homer.db", stateDir = "/srv/checkpoints")
        val unowned = config.resolveLangGraphStateDir(null)
        // A principal named exactly like the reserved bucket must not land in it.
        for (name in listOf("_unowned", "_UNOWNED", "_unowned ", "_h-deadbeef")) {
            assertNotEquals(unowned, config.resolveLangGraphStateDir(name), "'$name' reached the unowned bucket")
        }
    }

    @Test
    fun `resolveLangGraphStateDir keeps hashed and verbatim segments in separate namespaces`() {
        val config = appConfig(databasePath = "/var/lib/homer/homer.db", stateDir = "/srv/checkpoints")
        // A verbatim segment can never start with '_', so it cannot spell a hashed one.
        val hashed = config.resolveLangGraphStateDir("needs hashing")
        assertTrue(hashed.removePrefix("/srv/checkpoints/").startsWith("_h-"), "hashed segment must be marked: $hashed")
        for (name in listOf("_h-0011223344556677", "a1b2c3d4e5f6a7b8", "deadbeefdeadbeef")) {
            assertNotEquals(hashed, config.resolveLangGraphStateDir(name), "'$name' collided with a hashed segment")
        }
    }

    @Test
    fun `resolveLangGraphStateDir separates owners differing only in case`() {
        // Same directory on a case-insensitive filesystem if either were verbatim.
        val config = appConfig(databasePath = "/var/lib/homer/homer.db", stateDir = "/srv/checkpoints")
        assertNotEquals(
            config.resolveLangGraphStateDir("alice").lowercase(),
            config.resolveLangGraphStateDir("Alice").lowercase(),
        )
    }

    @Test
    fun `langGraph defaults to null stateDir`() {
        assertNull(Server().langGraph.stateDir)
    }

    @Test
    fun `AuthConfig Combined round-trips JSON`() {
        val input =
            """
            {"type":"combined","schemes":[
              {"type":"bearer","tokens":{"admin":{"token":"t","endpoints":["*"]}}},
              {"type":"hmac","keys":{"v":{"secret":"s","signatureHeader":"X-Vikunja-Signature","endpoints":["/x"]}}}
            ]}
            """.trimIndent()
        val parsed = json.decodeFromString(AuthConfig.serializer(), input)
        assertTrue(parsed is AuthConfig.Combined, "expected AuthConfig.Combined, got $parsed")
        assertEquals(2, parsed.schemes.size)
    }

    // ===== costTrackedTagKeys validation (ADR-0062) =====

    private fun appConfigWithCostTrackedTagKeys(keys: List<String>) =
        AppConfig(
            server = Server(),
            agentsSearchPath = "/agents",
            workspaceRootPath = "/workspaces",
            databasePath = "/var/lib/homer/homer.db",
            costTrackedTagKeys = keys,
        )

    @Test
    fun `costTrackedTagKeys rejects a key with an illegal charset`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                appConfigWithCostTrackedTagKeys(listOf("ticket id"))
            }
        assertTrue("costTrackedTagKeys" in (ex.message ?: ""))
    }

    @Test
    fun `costTrackedTagKeys rejects more than MAX_TAG_QUERY_PARAMS entries`() {
        val tooMany = (1..TagValidation.MAX_TAG_QUERY_PARAMS + 1).map { "key$it" }
        val ex =
            assertFailsWith<IllegalArgumentException> {
                appConfigWithCostTrackedTagKeys(tooMany)
            }
        assertTrue("costTrackedTagKeys" in (ex.message ?: ""))
    }

    @Test
    fun `costTrackedTagKeys accepts a valid list at the cap`() {
        val atCap = (1..TagValidation.MAX_TAG_QUERY_PARAMS).map { "key$it" }
        val cfg = appConfigWithCostTrackedTagKeys(atCap)
        assertEquals(atCap, cfg.costTrackedTagKeys)
    }
}
