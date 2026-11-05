package homer.app.infra

import homer.app.exceptions.ValidationException
import io.ktor.http.Parameters
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReservedEnvNamesTest {
    @Test
    fun `isReserved covers bash startup-file vars`() {
        // Non-interactive `bash -c` (WhenEvaluator) sources BASH_ENV before the
        // expression, and ENV in POSIX mode — a caller-set value is code execution.
        assertTrue(ReservedEnvNames.isReserved("BASH_ENV"))
        assertTrue(ReservedEnvNames.isReserved("ENV"))
        // Case-sensitive, like the rest of the set.
        assertFalse(ReservedEnvNames.isReserved("bash_env"))
    }

    @Test
    fun `reservedControlParams drops reserved keys that are not control markers`() {
        val params =
            Parameters.build {
                append("PATH", "/tmp/evil:/usr/bin")
                append("LD_PRELOAD", "/tmp/evil.so")
                append("HMR_PROCESS_ID", "999")
                append("HMR_CALLBACK_TOKEN", "stolen")
                // Reserved and HMR_LG_-prefixed, but it seeds the graph's fresh-run
                // input rather than steering a resume — callers must not set it.
                append("HMR_LG_INPUT", """{"seeded":true}""")
                append("HMR_LG_THREAD", "t-1")
            }

        assertEquals(mapOf("HMR_LG_THREAD" to "t-1"), reservedControlParams(params))
    }

    @Test
    fun `map overload drops reserved keys that are not control markers`() {
        val params = mapOf("PATH" to "/tmp/evil", "HMR_LG_RESUME" to "payload", "note" to "keep-out")
        assertEquals(mapOf("HMR_LG_RESUME" to "payload"), reservedControlParams(params))
    }

    @Test
    fun `reservedControlParams keeps the langgraph control markers`() {
        val params =
            Parameters.build {
                append("HMR_LG_THREAD", "t-1")
                append("HMR_LG_RESUME", "payload")
                append("HMR_LG_INTERRUPT", "int-1")
                append("WORKER_RESULT", "done")
                append("custom", "x")
            }

        assertEquals(
            mapOf(
                "HMR_LG_THREAD" to "t-1",
                "HMR_LG_RESUME" to "payload",
                "HMR_LG_INTERRUPT" to "int-1",
            ),
            reservedControlParams(params),
        )
    }

    @Test
    fun `reservedControlParams is empty when no reserved keys are present`() {
        val params =
            Parameters.build {
                append("WORKER_RESULT", "done")
                append("custom", "x")
            }
        assertEquals(emptyMap(), reservedControlParams(params))
    }

    @Test
    fun `reservedControlParams takes the last value for a multi-valued key`() {
        val params =
            Parameters.build {
                append("HMR_LG_THREAD", "first")
                append("HMR_LG_THREAD", "last")
            }
        assertEquals(mapOf("HMR_LG_THREAD" to "last"), reservedControlParams(params))
    }

    @Test
    fun `validatePayloadParam rejects a blank, colliding or reserved name and admits a control marker`() {
        val blank = assertFailsWith<ValidationException> { validatePayloadParam(" ", "answerParam", emptyMap()) }
        assertEquals("Validation error for 'answerParam': answerParam must not be blank", blank.message)
        val collision = assertFailsWith<ValidationException> { validatePayloadParam("X", "resumeParam", mapOf("X" to "1")) }
        assertEquals("Validation error for 'resumeParam': resumeParam 'X' must not be a key in params", collision.message)
        val reserved = assertFailsWith<ValidationException> { validatePayloadParam("PATH", "resumeParam", emptyMap()) }
        assertEquals("resumeParam", reserved.field)
        assertTrue(reserved.message!!.endsWith("resumeParam 'PATH' must not target a reserved environment name"))

        validatePayloadParam("HMR_LG_RESUME", "resumeParam", emptyMap())
        validatePayloadParam("ANSWER", "answerParam", mapOf("OTHER" to "1"))
    }

    @Test
    fun `isReserved rejects every exact host-controlled name`() {
        listOf("PATH", "HOME", "USER", "SHELL", "LANG", "TZ", "PWD", "OLDPWD", "IFS", "BASH_ENV", "ENV").forEach {
            assertTrue(ReservedEnvNames.isReserved(it), "$it must be reserved")
        }
    }

    @Test
    fun `isReserved rejects the HMR_, LD_, JAVA_ and LC_ prefixes`() {
        listOf("HMR_AGENT_NAME", "LD_PRELOAD", "JAVA_TOOL_OPTIONS", "LC_ALL").forEach {
            assertTrue(ReservedEnvNames.isReserved(it), "$it must be reserved")
        }
        // The prefixes are case-sensitive like the exact names.
        listOf("hmr_agent_name", "ld_preload", "java_home", "lc_all").forEach {
            assertFalse(ReservedEnvNames.isReserved(it), "$it must not be reserved")
        }
    }

    @Test
    fun `isReserved accepts ordinary names, including look-alikes of reserved ones`() {
        listOf("MY_VAR", "API_TOKEN", "PATHS", "HOMER", "XHMR_A", "HMR", "LDAP_URL", "JAVAC", "LCD", "X_PATH").forEach {
            assertFalse(ReservedEnvNames.isReserved(it), "$it must not be reserved")
        }
    }
}
