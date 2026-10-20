package homer.app.models

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmPriceConfigTest {
    private fun cfg(
        url: String = "https://spend.example/api/session/{sessionId}",
        costPath: String = "$.spend",
        callsPath: String? = null,
        requestsPath: String? = null,
        startedPath: String? = null,
        requestSpendPath: String? = null,
        windowGraceMs: Long = 5000,
        timeoutMs: Long = 3000,
        headers: Map<String, String> = emptyMap(),
    ) = SessionSpendConfig(
        url = url,
        headers = headers,
        costPath = costPath,
        callsPath = callsPath,
        requestsPath = requestsPath,
        startedPath = startedPath,
        requestSpendPath = requestSpendPath,
        windowGraceMs = windowGraceMs,
        timeoutMs = timeoutMs,
    )

    @Test
    fun `valid config is accepted with defaults`() {
        val c = SessionSpendConfig(url = "https://h/{sessionId}", costPath = "$.spend")
        assertEquals(3000, c.timeoutMs)
        assertTrue(c.headers.isEmpty())
    }

    @Test
    fun `url without sessionId placeholder is rejected`() {
        val ex = assertThrows<IllegalArgumentException> { cfg(url = "https://h/api/session") }
        assertTrue(ex.message!!.contains("{sessionId}"))
    }

    @Test
    fun `url with two placeholders is rejected`() {
        assertThrows<IllegalArgumentException> { cfg(url = "https://h/{sessionId}/{sessionId}") }
    }

    @Test
    fun `plaintext http to a remote host is rejected`() {
        val ex = assertThrows<IllegalArgumentException> { cfg(url = "http://spend.example/{sessionId}") }
        assertTrue(ex.message!!.contains("https"))
    }

    @Test
    fun `plaintext http to loopback is accepted`() {
        cfg(url = "http://localhost:8081/s/{sessionId}")
        cfg(url = "http://127.0.0.1/s/{sessionId}")
        cfg(url = "http://[::1]:9/s/{sessionId}")
    }

    @Test
    fun `non-http scheme is rejected`() {
        assertThrows<IllegalArgumentException> { cfg(url = "ftp://h/{sessionId}") }
    }

    @Test
    fun `sessionId in the host is rejected`() {
        assertThrows<IllegalArgumentException> { cfg(url = "https://{sessionId}/api") }
    }

    @Test
    fun `sessionId in the port is rejected`() {
        val ex = assertThrows<IllegalArgumentException> { cfg(url = "https://h:{sessionId}/api") }
        assertTrue(ex.message!!.contains("host, port or userinfo"))
    }

    @Test
    fun `an underscore hostname is accepted`() {
        // java.net.URI resolves a null host for this RFC-3986-legal reg-name (it can't
        // parse it as server-based syntax); the SSRF check must not misread that as "no
        // host" and reject an otherwise-safe URL.
        cfg(url = "https://spend_svc.internal/session/{sessionId}")
    }

    @Test
    fun `sessionId in the userinfo is rejected`() {
        assertThrows<IllegalArgumentException> { cfg(url = "https://{sessionId}@h/api") }
    }

    @Test
    fun `sessionId in the fragment is rejected`() {
        val ex = assertThrows<IllegalArgumentException> { cfg(url = "https://h/api#{sessionId}") }
        assertTrue(ex.message!!.contains("fragment"))
    }

    @Test
    fun `sessionId in the path is accepted`() {
        cfg(url = "https://h/api/session/{sessionId}")
    }

    @Test
    fun `sessionId in the query is accepted`() {
        cfg(url = "https://h/spend?session_id={sessionId}")
    }

    @Test
    fun `timeout bounds are enforced`() {
        assertThrows<IllegalArgumentException> { cfg(timeoutMs = 0) }
        assertThrows<IllegalArgumentException> { cfg(timeoutMs = 30001) }
        cfg(timeoutMs = 1)
        cfg(timeoutMs = 30000)
    }

    @Test
    fun `invalid JSONPath is rejected at construction`() {
        assertThrows<IllegalArgumentException> { cfg(costPath = "$[") }
    }

    @Test
    fun `callsPath defaults to null`() {
        val c = cfg()
        assertEquals(null, c.callsPath)
    }

    @Test
    fun `valid callsPath is accepted`() {
        val c = cfg(callsPath = "$.calls")
        assertEquals("$.calls", c.callsPath)
    }

    @Test
    fun `invalid callsPath is rejected at construction`() {
        val ex = assertThrows<IllegalArgumentException> { cfg(callsPath = "$[") }
        assertTrue(ex.message!!.contains("llmPrice.sessionSpend.callsPath"))
    }

    @Test
    fun `window keys default to null`() {
        val c = cfg()
        assertEquals(null, c.requestsPath)
        assertEquals(null, c.startedPath)
        assertEquals(null, c.requestSpendPath)
        assertEquals(5000L, c.windowGraceMs)
    }

    @Test
    fun `a full window trio is accepted`() {
        val c = cfg(requestsPath = "$.requests", startedPath = "$.started", requestSpendPath = "$.spend")
        assertEquals("$.requests", c.requestsPath)
    }

    @Test
    fun `an invalid JSONPath in any window key is rejected at construction`() {
        assertThrows<IllegalArgumentException> {
            cfg(requestsPath = "$[", startedPath = "$.started", requestSpendPath = "$.spend")
        }
        assertThrows<IllegalArgumentException> {
            cfg(requestsPath = "$.requests", startedPath = "$[", requestSpendPath = "$.spend")
        }
        assertThrows<IllegalArgumentException> {
            cfg(requestsPath = "$.requests", startedPath = "$.started", requestSpendPath = "$[")
        }
    }

    @Test
    fun `a partially-set window trio is rejected`() {
        val ex =
            assertThrows<IllegalArgumentException> {
                cfg(requestsPath = "$.requests")
            }
        assertTrue(ex.message!!.contains("all be set together"))
        assertThrows<IllegalArgumentException> { cfg(requestsPath = "$.requests", startedPath = "$.started") }
        assertThrows<IllegalArgumentException> { cfg(startedPath = "$.started") }
    }

    @Test
    fun `a negative window grace is rejected`() {
        assertThrows<IllegalArgumentException> { cfg(windowGraceMs = -1) }
    }

    @Test
    fun `toString redacts header values but keeps keys`() {
        val s = cfg(headers = mapOf("Authorization" to "Bearer verysecret")).toString()
        assertTrue(s.contains("Authorization"))
        assertFalse(s.contains("verysecret"))
    }

    @Test
    fun `withResolvedHeaders maps every value`() {
        val c = cfg(headers = mapOf("A" to "@SYSENV:X", "B" to "plain"))
        val r = c.withResolvedHeaders { if (it == "@SYSENV:X") "resolved" else it }
        assertEquals(mapOf("A" to "resolved", "B" to "plain"), r.headers)
    }

    @Test
    fun `AppConfig llmPrice defaults to null`() {
        val c =
            AppConfig(
                server = Server(apiAuth = AuthConfig.Unsecured),
                agentsSearchPath = "/a",
                workspaceRootPath = "/w",
                databasePath = "/h.db",
            )
        assertEquals(null, c.llmPrice)
    }
}
