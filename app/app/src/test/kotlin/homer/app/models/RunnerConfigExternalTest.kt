package homer.app.models

import com.charleskorn.kaml.Yaml
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RunnerConfigExternalTest {
    private fun config(runnersYaml: String): AppConfig =
        Yaml.default.decodeFromString(
            AppConfig.serializer(),
            """
            server:
              apiAuth: !<unsecured> {}
            agentsSearchPath: /tmp/agents
            workspaceRootPath: /tmp/ws
            databasePath: /tmp/homer.db
            runners:
            $runnersYaml
            """.trimIndent(),
        )

    @Test
    fun `external domain parses with defaults`() {
        val cfg =
            config(
                """
              workers: !<external>
                token: secret-1
                """,
            )
        val ext = cfg.runners["workers"] as RunnerConfig.External
        assertEquals("secret-1", ext.token)
        assertNull(ext.homerApiUrl)
        assertNull(ext.allowedTags)
        assertEquals(60, ext.heartbeatTimeoutSec)
        assertEquals(32, ext.maxInstances)
        assertEquals(16, ext.maxConcurrencyPerInstance)
        assertEquals(300, ext.transferTimeoutSec)
        assertEquals(1L * 1024 * 1024 * 1024, ext.maxTransferBytes)
        assertEquals(emptyMap(), ext.env)
    }

    @Test
    fun `external domain rejects a blank token`() {
        val ex = assertThrows<IllegalArgumentException> { RunnerConfig.External(token = "  ") }
        assertEquals(true, ex.message!!.contains("token must not be blank"))
    }

    @Test
    fun `external domain validates allowedTags shape and limits`() {
        assertThrows<IllegalArgumentException> {
            RunnerConfig
                .External(
                    token = "t",
                    allowedTags = listOf("bad tag"),
                ).validateFacets("workers")
        }
        assertThrows<IllegalArgumentException> { RunnerConfig.External(token = "t", heartbeatTimeoutSec = 0).validateFacets("workers") }
        assertThrows<IllegalArgumentException> { RunnerConfig.External(token = "t", maxInstances = 0).validateFacets("workers") }
        assertThrows<IllegalArgumentException> {
            RunnerConfig
                .External(
                    token = "t",
                    maxConcurrencyPerInstance = 0,
                ).validateFacets("workers")
        }
        assertThrows<IllegalArgumentException> { RunnerConfig.External(token = "t", transferTimeoutSec = 0).validateFacets("workers") }
        assertThrows<IllegalArgumentException> { RunnerConfig.External(token = "t", maxTransferBytes = 0).validateFacets("workers") }
        RunnerConfig.External(token = "t", allowedTags = listOf("linux", "gpu")).validateFacets("workers")
    }

    @Test
    fun `external env follows the runner env rules`() {
        val ex =
            assertThrows<IllegalArgumentException> {
                config(
                    """
                  workers: !<external>
                    token: secret-1
                    env: { HMR_X: "1" }
                    """,
                )
            }
        assertEquals(true, ex.message!!.contains("reserved HMR_ prefix"))
    }

    @Test
    fun `two domains may not share a token value`() {
        val ex =
            assertThrows<IllegalArgumentException> {
                config(
                    """
                  a: !<external>
                    token: same
                  b: !<external>
                    token: same
                    """,
                )
            }
        assertEquals(true, ex.message!!.contains("share a token"))
    }

    @Test
    fun `a plaintext homerApiUrl to a non-loopback host is refused without echoing it (CWE-319)`() {
        val ex =
            assertThrows<IllegalArgumentException> {
                config(
                    """
                  workers: !<external>
                    token: secret-1
                    homerApiUrl: http://user:pw@homer.internal:8080
                    """,
                )
            }
        assertEquals(true, ex.message!!.contains("runner 'workers' homerApiUrl must be https://"), ex.message)
        assertFalse(ex.message!!.contains("user:pw"), "the URL may hold userinfo and must not be echoed")
    }

    @Test
    fun `a homerApiUrl carrying userinfo is refused without echoing it (CWE-522)`() {
        listOf("https://user:pw@homer.internal", "http://user:pw@localhost:8080").forEach { url ->
            val ex =
                assertThrows<IllegalArgumentException>(url) {
                    config(
                        """
                      workers: !<external>
                        token: secret-1
                        homerApiUrl: $url
                        """,
                    )
                }
            assertEquals(true, ex.message!!.contains("without embedded credentials"), ex.message)
            assertFalse(ex.message!!.contains("user:pw"), "the URL holds a credential and must not be echoed")
        }
    }

    @Test
    fun `toString redacts the homerApiUrl`() {
        val s = RunnerConfig.External(token = "t", homerApiUrl = "https://u:p@h/api?token=s3cret#f").toString()
        assertFalse(s.contains("u:p"), s)
        assertFalse(s.contains("s3cret"), s)
        assertFalse(s.contains("#f"), s)
        assertEquals(true, s.contains("https://h/api"), s)
    }

    @Test
    fun `toString keeps a reg-name host the URI host field misses`() {
        // `homer_internal` is a legal homerApiUrl host (isHttpsOrLoopbackHttpUrl accepts
        // it) but gives a null `URI.host`, which the redaction must not turn into a
        // mangled or missing URL.
        val s = RunnerConfig.External(token = "t", homerApiUrl = "https://homer_internal:8443?x=1").toString()
        assertEquals(true, s.contains("https://homer_internal:8443"), s)
    }

    @Test
    fun `an https or loopback http homerApiUrl is accepted`() {
        listOf("https://homer.internal", "https://homer_internal:8443", "http://127.0.0.1:8080", "http://localhost:8080/").forEach { url ->
            config(
                """
              workers: !<external>
                token: secret-1
                homerApiUrl: $url
                """,
            )
        }
    }

    @Test
    fun `a heartbeatTimeoutSec below the long-poll window plus slack is refused`() {
        val ex =
            assertThrows<IllegalArgumentException> {
                config(
                    """
                  workers: !<external>
                    token: secret-1
                    heartbeatTimeoutSec: 34
                    """,
                )
            }
        assertEquals(true, ex.message!!.contains("heartbeatTimeoutSec must be at least 35"), ex.message)
    }

    @Test
    fun `a heartbeatTimeoutSec at the long-poll window plus slack is accepted`() {
        val cfg =
            config(
                """
              workers: !<external>
                token: secret-1
                heartbeatTimeoutSec: 35
                """,
            )
        assertEquals(35, (cfg.runners["workers"] as RunnerConfig.External).heartbeatTimeoutSec)
    }

    @Test
    fun `external domain parses extraUploads`() {
        val cfg =
            config(
                """
              workers: !<external>
                token: secret-1
                extraUploads:
                  - { local: /app/scripts, remote: .homer/scripts, env: SCRIPTS_DIR }
                """,
            )
        val ext = cfg.runners["workers"] as RunnerConfig.External
        assertEquals(listOf(ExtraUploadSpec("/app/scripts", ".homer/scripts", "SCRIPTS_DIR")), ext.extraUploads)
    }

    @Test
    fun `external extraUploads follow the ssh shape rules and name the domain`() {
        val ex =
            assertThrows<IllegalArgumentException> {
                config(
                    """
                  workers: !<external>
                    token: secret-1
                    extraUploads:
                      - { local: /app/scripts, remote: .homer/agent/scripts }
                    """,
                )
            }
        assertEquals(true, ex.message!!.contains("runner 'workers'"), ex.message)
        assertEquals(true, ex.message!!.contains("reserved path '.homer/agent'"), ex.message)
    }

    @Test
    fun `toString lists extraUploads by remote path only`() {
        val s =
            RunnerConfig
                .External(
                    token = "very-secret-token",
                    extraUploads = listOf(ExtraUploadSpec("/app/secrets-load.sh", ".homer/scripts", "SCRIPTS_DIR")),
                ).toString()
        assertEquals(true, s.contains("extraUploads=[.homer/scripts]"), s)
        assertFalse(s.contains("/app/secrets-load.sh"), s)
        assertFalse(s.contains("SCRIPTS_DIR"), s)
    }

    @Test
    fun `toString never prints the token`() {
        val s = RunnerConfig.External(token = "very-secret-token").toString()
        assertFalse(s.contains("very-secret-token"), s)
    }
}
