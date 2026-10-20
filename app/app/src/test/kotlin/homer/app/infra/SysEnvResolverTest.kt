package homer.app.infra

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SysEnvResolverTest {
    private val resolver = SysEnvResolver()

    @Test
    fun `resolve returns unchanged string when no @SYSENV reference present`() {
        val result = resolver.resolve("plain-value")

        assertEquals("plain-value", result)
    }

    @Test
    fun `resolve replaces @SYSENV reference with system env variable value`() {
        val varName = "PATH"
        val expected = System.getenv(varName) ?: error("PATH must be set in test environment")

        val result = resolver.resolve("@SYSENV:$varName")

        assertEquals(expected, result)
    }

    @Test
    fun `resolve throws IllegalStateException when referenced variable does not exist`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                resolver.resolve("@SYSENV:THIS_VAR_DEFINITELY_DOES_NOT_EXIST_12345")
            }

        assertTrue(ex.message!!.contains("THIS_VAR_DEFINITELY_DOES_NOT_EXIST_12345"))
    }

    @Test
    fun `resolve substitutes @SYSENV within a larger string`() {
        val varName = "PATH"
        val pathValue = System.getenv(varName) ?: error("PATH must be set in test environment")

        val result = resolver.resolve("prefix_@SYSENV:$varName/suffix")

        assertEquals("prefix_$pathValue/suffix", result)
    }

    @Test
    fun `resolveMapValues resolves all values in map`() {
        val varName = "PATH"
        val pathValue = System.getenv(varName) ?: error("PATH must be set in test environment")

        val input =
            mapOf(
                "key1" to "static-value",
                "key2" to "@SYSENV:$varName",
            )

        val result = resolver.resolveMapValues(input)

        assertEquals("static-value", result["key1"])
        assertEquals(pathValue, result["key2"])
    }

    @Test
    fun `resolveMapValues throws when any value references missing variable`() {
        val input =
            mapOf(
                "key1" to "static-value",
                "key2" to "@SYSENV:THIS_VAR_DEFINITELY_DOES_NOT_EXIST_12345",
            )

        assertFailsWith<IllegalStateException> {
            resolver.resolveMapValues(input)
        }
    }

    @Test
    fun `resolveMapValues returns empty map unchanged`() {
        val result = resolver.resolveMapValues(emptyMap())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `secretNamesFor returns the referencing keys and the host variables they reference`() {
        val raw =
            mapOf(
                "TOKEN" to "@SYSENV:PATH",
                "HEADER" to "Bearer @SYSENV:PATH",
                "PLAIN" to "not-a-secret",
                "EMPTY" to "",
            )

        // `PATH` is the host variable name, not a key of the map: without it a
        // composite value leaves the bare credential unredacted. See SecretRedactorTest.
        assertEquals(setOf("TOKEN", "HEADER", "PATH"), resolver.secretNamesFor(raw))
    }

    @Test
    fun `secretNamesFor collects every host variable a single value references`() {
        val raw = mapOf("URL" to "https://@SYSENV:USER:@SYSENV:PASS@example.com")

        assertEquals(setOf("URL", "USER", "PASS"), resolver.secretNamesFor(raw))
    }

    @Test
    fun `secretNamesFor returns nothing when no value references @SYSENV`() {
        assertEquals(emptySet(), resolver.secretNamesFor(mapOf("PLAIN" to "literal", "EMPTY" to "")))
    }
}
