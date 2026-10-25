package homer.app

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.pattern
import io.kotest.property.arbitrary.printableAscii
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `isCorsOrigin` is the gate a `@SYSENV`-completed `corsAllowedOrigins` entry passes at startup (#29). */
class CorsOriginPropertyTest {
    private val schemes = Arb.element("http", "https")
    private val hosts = Arb.pattern("[a-z]([a-z0-9-]{0,8}[a-z0-9])?(\\.[a-z]{2,5}){0,2}")
    private val ports = Arb.int(1..65535)

    @Test
    fun `every scheme host and port origin is accepted, with or without a trailing slash`() =
        runTest {
            checkAll(schemes, hosts, ports) { scheme, host, port ->
                assertTrue(isCorsOrigin("$scheme://$host:$port"), "$scheme://$host:$port")
                assertTrue(isCorsOrigin("$scheme://$host:$port/"), "$scheme://$host:$port/")
            }
        }

    @Test
    fun `an origin followed by a path, query, fragment or preceded by userinfo is refused`() =
        runTest {
            val extras = Arb.element("/x", "/x/", "?q=1", "#f", "/?", "//evil.example")
            checkAll(schemes, hosts, ports, extras) { scheme, host, port, extra ->
                assertFalse(isCorsOrigin("$scheme://$host:$port$extra"), "$scheme://$host:$port$extra")
                assertFalse(isCorsOrigin("$scheme://user@$host:$port"), "userinfo before $host")
            }
        }

    @Test
    fun `whatever a variable resolves to, an accepted origin still names the host the config wrote`() =
        runTest {
            // The #29 shape: the config fixes scheme and host, the environment supplies the port.
            checkAll(Arb.string(0..16, Codepoint.printableAscii())) { value ->
                val origin = "http://localhost:$value"
                if (isCorsOrigin(origin)) {
                    assertEquals("localhost", URI(origin).host, "value '$value'")
                    assertTrue(value.trimEnd('/').toInt() in 1..65535, "value '$value'")
                }
            }
        }

    @Test
    fun `a port outside 1 to 65535 is refused`() =
        runTest {
            val outside = Arb.choice(Arb.constant(0), Arb.int(65536..9_999_999))
            checkAll(schemes, hosts, outside) { scheme, host, port ->
                assertFalse(isCorsOrigin("$scheme://$host:$port"), "$scheme://$host:$port")
            }
        }

    @Test
    fun `scheme and host match case-insensitively`() {
        assertTrue(isCorsOrigin("HTTPS://LocalHost:3000"))
        assertTrue(isCorsOrigin("Http://Console.Example.com/"))
    }

    @Test
    fun `a port above 65535, port 0, an empty port and a non-http scheme are refused`() {
        assertFalse(isCorsOrigin("http://localhost:65536"))
        assertFalse(isCorsOrigin("http://localhost:0"))
        assertFalse(isCorsOrigin("http://localhost:"))
        assertFalse(isCorsOrigin("http://localhost:abc"))
        assertFalse(isCorsOrigin("ftp://localhost:21"))
        assertFalse(isCorsOrigin("localhost:3000"))
        assertFalse(isCorsOrigin("http://local host"))
        assertTrue(isCorsOrigin("https://console.example.com"))
        assertTrue(isCorsOrigin("http://[::1]:3000"))
    }
}
