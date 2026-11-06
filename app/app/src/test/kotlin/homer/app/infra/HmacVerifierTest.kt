package homer.app.infra

import homer.app.models.HmacAlgorithm
import homer.app.models.HmacEncoding
import homer.app.models.HmacKeyConfig
import org.junit.jupiter.api.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HmacVerifierTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun sign(
        secret: String,
        algorithm: String,
        payload: ByteArray,
    ): ByteArray =
        Mac
            .getInstance(algorithm)
            .apply {
                init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), algorithm))
            }.doFinal(payload)

    private fun verifier(now: Long = 1_700_000_000_000L) = HmacVerifier(clock = { now })

    @Test
    fun `accepts Vikunja-style request`() {
        val body = """{"event":"task.created","id":42}""".toByteArray(Charsets.UTF_8)
        val secret = "topsecret"
        val sig = hex(sign(secret, "HmacSHA256", body))
        val cfg = HmacKeyConfig(secret = secret, signatureHeader = "X-Vikunja-Signature")
        val headers = mapOf("X-Vikunja-Signature" to sig)

        val result =
            verifier().verify(
                keys = mapOf("vikunja" to cfg),
                method = "POST",
                path = "/api/v1/agent/x",
                headers = { headers[it] },
                rawBody = body,
            )

        assertTrue(result is HmacVerifier.Result.Authenticated, "expected Authenticated, got $result")
        assertEquals("vikunja", result.keyId)
    }

    @Test
    fun `binds query string when template uses the query token`() {
        val secret = "s"
        val body = """{"x":1}""".toByteArray()
        val query = "agent=deploy&env=prod"
        val cfg =
            HmacKeyConfig(
                secret = secret,
                signatureHeader = "X-Sig",
                payloadTemplate = "{{method}}.{{path}}.{{query}}.{{body}}",
            )
        val signed = "POST./p.$query.${body.toString(Charsets.UTF_8)}".toByteArray()
        val sig = hex(sign(secret, "HmacSHA256", signed))

        val accepted =
            verifier().verify(
                keys = mapOf("k" to cfg),
                method = "POST",
                path = "/p",
                query = query,
                headers = { if (it == "X-Sig") sig else null },
                rawBody = body,
            )
        assertTrue(accepted is HmacVerifier.Result.Authenticated, "matching query must verify, got $accepted")

        val tampered =
            verifier().verify(
                keys = mapOf("k" to cfg),
                method = "POST",
                path = "/p",
                query = "agent=deploy&env=EVIL",
                headers = { if (it == "X-Sig") sig else null },
                rawBody = body,
            )
        assertTrue(tampered is HmacVerifier.Result.Rejected, "a modified query must fail when {{query}} is signed")
    }

    @Test
    fun `rejects tampered body`() {
        val secret = "s"
        val realBody = """{"x":1}""".toByteArray()
        val sig = hex(sign(secret, "HmacSHA256", realBody))
        val cfg = HmacKeyConfig(secret = secret, signatureHeader = "X-Sig")

        val result =
            verifier().verify(
                keys = mapOf("k" to cfg),
                method = "POST",
                path = "/p",
                headers = { if (it == "X-Sig") sig else null },
                rawBody = """{"x":2}""".toByteArray(),
            )

        assertEquals(HmacVerifier.Result.Rejected("hmac: no matching key"), result)
    }

    @Test
    fun `rejects missing header`() {
        val cfg = HmacKeyConfig(secret = "s", signatureHeader = "X-Sig")
        val result =
            verifier().verify(
                keys = mapOf("k" to cfg),
                method = "POST",
                path = "/p",
                headers = { null },
                rawBody = byteArrayOf(),
            )
        assertEquals(HmacVerifier.Result.Rejected("hmac: no matching key"), result)
    }

    @Test
    fun `accepts GitHub-style with sha256 prefix`() {
        val body = "payload".toByteArray()
        val secret = "ghs"
        val sig = "sha256=" + hex(sign(secret, "HmacSHA256", body))
        val cfg =
            HmacKeyConfig(
                secret = secret,
                signatureHeader = "X-Hub-Signature-256",
                prefix = "sha256=",
            )
        val result =
            verifier().verify(
                keys = mapOf("gh" to cfg),
                method = "POST",
                path = "/p",
                headers = { if (it == "X-Hub-Signature-256") sig else null },
                rawBody = body,
            )
        assertTrue(result is HmacVerifier.Result.Authenticated)
    }

    @Test
    fun `rejects missing prefix when configured`() {
        val body = "p".toByteArray()
        val secret = "ghs"
        val sig = hex(sign(secret, "HmacSHA256", body)) // no prefix
        val cfg = HmacKeyConfig(secret = secret, signatureHeader = "X-Sig", prefix = "sha256=")
        val result =
            verifier().verify(
                keys = mapOf("gh" to cfg),
                method = "POST",
                path = "/p",
                headers = { if (it == "X-Sig") sig else null },
                rawBody = body,
            )
        assertEquals(HmacVerifier.Result.Rejected("hmac: no matching key"), result)
    }

    @Test
    fun `accepts Stripe-style timestamp dot body with base64`() {
        val body = """{"id":"evt_1"}""".toByteArray()
        val tsSec = 1_700_000_000L
        val secret = "stripekey"
        val payload = "$tsSec.${body.toString(Charsets.UTF_8)}".toByteArray()
        val sig =
            java.util.Base64
                .getEncoder()
                .encodeToString(sign(secret, "HmacSHA256", payload))
        val cfg =
            HmacKeyConfig(
                secret = secret,
                signatureHeader = "Stripe-Signature",
                timestampHeader = "Stripe-Signature-Timestamp",
                payloadTemplate = "{{timestamp}}.{{body}}",
                encoding = HmacEncoding.BASE64,
            )
        val headers =
            mapOf(
                "Stripe-Signature" to sig,
                "Stripe-Signature-Timestamp" to tsSec.toString(),
            )
        val v = HmacVerifier(clock = { tsSec * 1000 + 60_000 })
        val result =
            v.verify(
                keys = mapOf("stripe" to cfg),
                method = "POST",
                path = "/p",
                headers = { headers[it] },
                rawBody = body,
            )
        assertTrue(result is HmacVerifier.Result.Authenticated)
    }

    @Test
    fun `rejects stale timestamp outside tolerance`() {
        val body = "x".toByteArray()
        val tsSec = 1_700_000_000L
        val secret = "k"
        val payload = "$tsSec.${body.toString(Charsets.UTF_8)}".toByteArray()
        val sig = hex(sign(secret, "HmacSHA256", payload))
        val cfg =
            HmacKeyConfig(
                secret = secret,
                signatureHeader = "X-Sig",
                timestampHeader = "X-Ts",
                payloadTemplate = "{{timestamp}}.{{body}}",
                replayToleranceSec = 60,
            )
        val v = HmacVerifier(clock = { tsSec * 1000 + 120_000 }) // 2 min late
        val result =
            v.verify(
                keys = mapOf("k" to cfg),
                method = "POST",
                path = "/p",
                headers = { mapOf("X-Sig" to sig, "X-Ts" to tsSec.toString())[it] },
                rawBody = body,
            )
        assertEquals(HmacVerifier.Result.Rejected("hmac: no matching key"), result)
    }

    @Test
    fun `tries multiple keys sharing one header`() {
        val body = "b".toByteArray()
        val sigB = hex(sign("kB", "HmacSHA256", body))
        val cfgA = HmacKeyConfig(secret = "kA", signatureHeader = "X-Sig")
        val cfgB = HmacKeyConfig(secret = "kB", signatureHeader = "X-Sig")
        val result =
            verifier().verify(
                keys = linkedMapOf("a" to cfgA, "b" to cfgB),
                method = "POST",
                path = "/p",
                headers = { if (it == "X-Sig") sigB else null },
                rawBody = body,
            )
        assertTrue(result is HmacVerifier.Result.Authenticated && result.keyId == "b")
    }

    @Test
    fun `rejects malformed hex without throwing`() {
        val cfg = HmacKeyConfig(secret = "s", signatureHeader = "X-Sig")
        val result =
            verifier().verify(
                keys = mapOf("k" to cfg),
                method = "POST",
                path = "/p",
                headers = { if (it == "X-Sig") "zzzz-not-hex" else null },
                rawBody = byteArrayOf(),
            )
        assertEquals(HmacVerifier.Result.Rejected("hmac: no matching key"), result)
    }

    @Test
    fun `sha512 algorithm works`() {
        val secret = "s"
        val body = "b".toByteArray()
        val sig = hex(sign(secret, "HmacSHA512", body))
        val cfg =
            HmacKeyConfig(
                secret = secret,
                signatureHeader = "X-Sig",
                algorithm = HmacAlgorithm.SHA512,
            )
        val result =
            verifier().verify(
                keys = mapOf("k" to cfg),
                method = "POST",
                path = "/p",
                headers = { if (it == "X-Sig") sig else null },
                rawBody = body,
            )
        assertTrue(result is HmacVerifier.Result.Authenticated)
    }

    @Test
    fun `rejects a replayed signature within the freshness window`() {
        val body = "x".toByteArray()
        val tsSec = 1_700_000_000L
        val secret = "k"
        val payload = "$tsSec.${body.toString(Charsets.UTF_8)}".toByteArray()
        val sig = hex(sign(secret, "HmacSHA256", payload))
        val cfg =
            HmacKeyConfig(
                secret = secret,
                signatureHeader = "X-Sig",
                timestampHeader = "X-Ts",
                payloadTemplate = "{{timestamp}}.{{body}}",
                replayToleranceSec = 60,
            )
        val v = HmacVerifier(clock = { tsSec * 1000 + 1_000 }, replayCache = HmacReplayCache(clock = { tsSec * 1000 + 1_000 }))
        val headers = mapOf("X-Sig" to sig, "X-Ts" to tsSec.toString())
        val first = v.verify(keys = mapOf("k" to cfg), method = "POST", path = "/p", headers = { headers[it] }, rawBody = body)
        val second = v.verify(keys = mapOf("k" to cfg), method = "POST", path = "/p", headers = { headers[it] }, rawBody = body)
        assertTrue(first is HmacVerifier.Result.Authenticated, "first use should authenticate, got $first")
        assertEquals(HmacVerifier.Result.Rejected("hmac: signature replayed within the freshness window"), second)
    }

    @Test
    fun `rejects a replayed signature submitted with different hex case`() {
        // The HEX decoder is case-insensitive, so the same MAC re-cased decodes
        // to identical bytes and re-verifies — but if the replay cache is keyed on
        // the raw signature string, the re-cased copy evades dedup. Key must be
        // the canonical decoded bytes.
        val body = "x".toByteArray()
        val tsSec = 1_700_000_000L
        val secret = "k"
        val payload = "$tsSec.${body.toString(Charsets.UTF_8)}".toByteArray()
        val sigLower = hex(sign(secret, "HmacSHA256", payload))
        val sigUpper = sigLower.uppercase()
        val cfg =
            HmacKeyConfig(
                secret = secret,
                signatureHeader = "X-Sig",
                timestampHeader = "X-Ts",
                payloadTemplate = "{{timestamp}}.{{body}}",
                replayToleranceSec = 60,
            )
        val v = HmacVerifier(clock = { tsSec * 1000 + 1_000 }, replayCache = HmacReplayCache(clock = { tsSec * 1000 + 1_000 }))

        fun call(sig: String) =
            v.verify(
                keys = mapOf("k" to cfg),
                method = "POST",
                path = "/p",
                headers = { mapOf("X-Sig" to sig, "X-Ts" to tsSec.toString())[it] },
                rawBody = body,
            )
        assertTrue(call(sigLower) is HmacVerifier.Result.Authenticated, "first use should authenticate")
        assertTrue(call(sigUpper) is HmacVerifier.Result.Rejected, "re-cased replay must be rejected")
    }

    @Test
    fun `does not dedupe when no timestampHeader is configured`() {
        // Without a bounded freshness window there is nothing to expire the cache,
        // so signature dedup is intentionally off; identical requests still pass.
        val body = "b".toByteArray()
        val secret = "s"
        val sig = hex(sign(secret, "HmacSHA256", body))
        val cfg = HmacKeyConfig(secret = secret, signatureHeader = "X-Sig")
        val v = HmacVerifier(clock = { 1_700_000_000_000L }, replayCache = HmacReplayCache(clock = { 1_700_000_000_000L }))
        val headers = mapOf("X-Sig" to sig)
        val first = v.verify(keys = mapOf("k" to cfg), method = "POST", path = "/p", headers = { headers[it] }, rawBody = body)
        val second = v.verify(keys = mapOf("k" to cfg), method = "POST", path = "/p", headers = { headers[it] }, rawBody = body)
        assertTrue(first is HmacVerifier.Result.Authenticated)
        assertTrue(second is HmacVerifier.Result.Authenticated, "no timestamp -> no dedup, got $second")
    }

    private val noMatch = "hmac: no matching key"

    private fun timestampedConfig(tolerance: Long = 60) =
        HmacKeyConfig(
            secret = "k",
            signatureHeader = "X-Sig",
            timestampHeader = "X-Ts",
            payloadTemplate = "{{timestamp}}.{{body}}",
            replayToleranceSec = tolerance,
        )

    /** Verifies a request signed over [tsHeader] (the raw header text), with the verifier clock at [nowMillis]. */
    private fun verifyTimestamped(
        tsHeader: String,
        nowMillis: Long,
        cache: HmacReplayCache? = null,
    ): HmacVerifier.Result {
        val sig = hex(sign("k", "HmacSHA256", "$tsHeader.x".toByteArray()))
        val headers = mapOf("X-Sig" to sig, "X-Ts" to tsHeader)
        return HmacVerifier(clock = { nowMillis }, replayCache = cache)
            .verify(
                keys = mapOf("k" to timestampedConfig()),
                method = "POST",
                path = "/p",
                headers = { headers[it] },
                rawBody = "x".toByteArray(),
            )
    }

    @Test
    fun `a timestamp exactly at the tolerance is accepted and one second over is rejected`() {
        val tsSec = 1_700_000_000L
        assertTrue(verifyTimestamped(tsSec.toString(), tsSec * 1000 + 60_000) is HmacVerifier.Result.Authenticated)
        val over = verifyTimestamped(tsSec.toString(), tsSec * 1000 + 61_000)
        assertEquals(HmacVerifier.Result.Rejected(noMatch), over)
    }

    @Test
    fun `a timestamp from the future beyond the tolerance is rejected`() {
        val tsSec = 1_700_000_000L
        val result = verifyTimestamped(tsSec.toString(), tsSec * 1000 - 120_000)
        assertEquals(HmacVerifier.Result.Rejected(noMatch), result)
        assertTrue(verifyTimestamped(tsSec.toString(), tsSec * 1000 - 60_000) is HmacVerifier.Result.Authenticated)
    }

    @Test
    fun `a non-numeric timestamp header is rejected`() {
        val result = verifyTimestamped("yesterday", 1_700_000_000_000L)
        assertEquals(HmacVerifier.Result.Rejected(noMatch), result)
    }

    @Test
    fun `a non-numeric or missing timestamp is not read as the epoch even when the clock stands at zero`() {
        val rejected = verifyTimestamped("yesterday", nowMillis = 0L)
        assertEquals(noMatch, (rejected as HmacVerifier.Result.Rejected).reason)

        val sig = hex(sign("k", "HmacSHA256", ".x".toByteArray()))
        val missing =
            HmacVerifier(clock = { 0L }).verify(
                keys = mapOf("k" to timestampedConfig()),
                method = "POST",
                path = "/p",
                headers = { if (it == "X-Sig") sig else null },
                rawBody = "x".toByteArray(),
            )
        assertEquals(noMatch, (missing as HmacVerifier.Result.Rejected).reason)
    }

    @Test
    fun `a missing timestamp header is rejected`() {
        val sig = hex(sign("k", "HmacSHA256", ".x".toByteArray()))
        val result =
            HmacVerifier(clock = { 1_700_000_000_000L }).verify(
                keys = mapOf("k" to timestampedConfig()),
                method = "POST",
                path = "/p",
                headers = { if (it == "X-Sig") sig else null },
                rawBody = "x".toByteArray(),
            )
        assertEquals(HmacVerifier.Result.Rejected(noMatch), result)
    }

    @Test
    fun `a timestamp in seconds and one in milliseconds both denote the same instant`() {
        val nowMillis = 1_700_000_000_000L
        assertTrue(verifyTimestamped("1700000000", nowMillis) is HmacVerifier.Result.Authenticated)
        assertTrue(verifyTimestamped("1700000000000", nowMillis) is HmacVerifier.Result.Authenticated)
    }

    @Test
    fun `the seconds-versus-milliseconds cutoff treats exactly 10^12 as seconds`() {
        // 10^12 read as milliseconds would be a valid "now" for this clock; as seconds it is
        // 10^15 ms, far in the future, so it must be rejected.
        val cutoff = 1_000_000_000_000L
        assertEquals(HmacVerifier.Result.Rejected(noMatch), verifyTimestamped(cutoff.toString(), cutoff))
        assertTrue(verifyTimestamped((cutoff + 1).toString(), cutoff + 1) is HmacVerifier.Result.Authenticated)
    }

    @Test
    fun `a replay later than the tolerance but within twice it is still rejected`() {
        val tsSec = 1_700_000_000L
        val verifierNow = tsSec * 1000 + 1_000
        var cacheNow = verifierNow
        val cache = HmacReplayCache(clock = { cacheNow })
        val first = verifyTimestamped(tsSec.toString(), verifierNow, cache)
        assertTrue(first is HmacVerifier.Result.Authenticated, "first use should authenticate, got $first")

        cacheNow = verifierNow + 90_000 // beyond the 60 s tolerance, inside 2 x tolerance
        val replay = verifyTimestamped(tsSec.toString(), verifierNow, cache)
        assertEquals(HmacVerifier.Result.Rejected("hmac: signature replayed within the freshness window"), replay)

        cacheNow = verifierNow + 121_000 // past 2 x tolerance the marker has lapsed
        assertTrue(verifyTimestamped(tsSec.toString(), verifierNow, cache) is HmacVerifier.Result.Authenticated)
    }

    @Test
    fun `a signature with an odd number of hex digits is rejected even when its even prefix is the valid MAC`() {
        val body = "b".toByteArray()
        val sig = hex(sign("s", "HmacSHA256", body))
        val cfg = HmacKeyConfig(secret = "s", signatureHeader = "X-Sig")
        val result =
            verifier().verify(
                keys = mapOf("k" to cfg),
                method = "POST",
                path = "/p",
                headers = { if (it == "X-Sig") sig + "0" else null },
                rawBody = body,
            )
        assertEquals(HmacVerifier.Result.Rejected(noMatch), result)
    }

    @Test
    fun `a signature with a non-hex character is rejected even when it would decode to the valid MAC`() {
        // Without the hex check, Character.digit('g', 16) == -1 turns the pair "0g" into 0xff,
        // so a MAC containing an "ff" byte would verify with that byte spelled "0g".
        val cfg = HmacKeyConfig(secret = "s", signatureHeader = "X-Sig")
        val body =
            generateSequence(0) {
                it + 1
            }.map { "body-$it".toByteArray() }.first { hex(sign("s", "HmacSHA256", it)).chunked(2).contains("ff") }
        val pairs = hex(sign("s", "HmacSHA256", body)).chunked(2).toMutableList()
        pairs[pairs.indexOf("ff")] = "0g"
        val forged = pairs.joinToString("")
        val result =
            verifier().verify(
                keys = mapOf("k" to cfg),
                method = "POST",
                path = "/p",
                headers = { if (it == "X-Sig") forged else null },
                rawBody = body,
            )
        assertEquals(HmacVerifier.Result.Rejected(noMatch), result)
    }
}
