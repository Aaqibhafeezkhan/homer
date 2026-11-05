package homer.app.infra

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReservedHeadersTest {
    @Test
    fun `every exact reserved header is reserved, whatever its case`() {
        listOf(
            "authorization",
            "host",
            "content-type",
            "content-length",
            "connection",
            "transfer-encoding",
            "upgrade",
            "cookie",
            "set-cookie",
        ).forEach {
            assertTrue(ReservedHeaders.isReserved(it), "$it must be reserved")
            assertTrue(ReservedHeaders.isReserved(it.uppercase()), "${it.uppercase()} must be reserved")
        }
        assertTrue(ReservedHeaders.isReserved("Authorization"))
        assertTrue(ReservedHeaders.isReserved("Content-Type"))
    }

    @Test
    fun `x-homer- and x-forwarded- prefixed headers are reserved case-insensitively`() {
        assertTrue(ReservedHeaders.isReserved("X-Homer-Signature"))
        assertTrue(ReservedHeaders.isReserved("x-homer-anything"))
        assertTrue(ReservedHeaders.isReserved("X-Forwarded-For"))
        assertTrue(ReservedHeaders.isReserved("X-FORWARDED-PROTO"))
    }

    @Test
    fun `ordinary headers are not reserved`() {
        listOf("X-Custom", "Accept", "User-Agent", "X-Homer", "X-Forwarded", "X-Request-Id", "Hosted", "Cookies").forEach {
            assertFalse(ReservedHeaders.isReserved(it), "$it must not be reserved")
        }
    }
}
