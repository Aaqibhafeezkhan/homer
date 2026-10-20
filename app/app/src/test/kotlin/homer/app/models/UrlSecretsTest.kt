package homer.app.models

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlSecretsTest {
    @Test
    fun `stripUrlSecrets keeps a reg-name host the URI host field misses`() {
        // A legal reg-name host (`homer_internal`, accepted by isHttpsOrLoopbackHttpUrl)
        // gives a null `URI.host`; the rebuild must go through the raw authority, or the
        // host is dropped from the sanitized form.
        assertEquals("https://homer_internal/api", stripUrlSecrets("https://homer_internal/api?x=1"))
        assertEquals("https://homer_internal", stripUrlSecrets("https://homer_internal?x=1"))
        assertEquals("https://homer_internal:8443", stripUrlSecrets("https://homer_internal:8443?a=1"))
        // And with userinfo, only the userinfo goes.
        assertEquals("https://homer_internal/api", stripUrlSecrets("https://user:pw@homer_internal/api?x=1"))
    }

    @Test
    fun `stripUrlSecrets returns null for a value that will not parse`() {
        // The API sink drops the link rather than emit a placeholder string where a URL
        // is expected.
        assertEquals(null, stripUrlSecrets("http://ho st:/// not a uri"))
    }
}
