package homer.app.models

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class AgentHookPropertyTest {
    /** No status name is spellable from these: only `B` is uppercase and no status has one. */
    private val nameChars = listOf('a', 'B', '9', '-', '_')

    @Test
    fun `any status and valid agent name round-trips through the one-key wire form`() =
        runTest {
            checkAll(Arb.element(ProcessStatus.entries), Arb.list(Arb.element(nameChars), 1..12)) { status, chars ->
                val hook = AgentHook(status, chars.joinToString(""))

                val encoded = Json.encodeToString(AgentHook.serializer(), hook)

                assertEquals("""{"${status.name}":"${hook.agent}"}""", encoded)
                assertEquals(hook, Json.decodeFromString(AgentHook.serializer(), encoded))
            }
        }

    @Test
    fun `a decode error names only the offending key, never the value`() =
        runTest {
            // The value is an untrusted definition's string and the error text reaches the load
            // log and the definition-write response, so the message must not echo it. §12 rule 7.
            checkAll(
                Arb.list(Arb.element(nameChars), 1..12),
                Arb.list(Arb.element(nameChars), 1..12),
            ) { keyChars, valueChars ->
                val key = keyChars.joinToString("")
                val value = valueChars.joinToString("")

                val ex =
                    assertThrows<SerializationException> {
                        Json.decodeFromString(AgentHook.serializer(), """{"$key":"$value"}""")
                    }

                assertEquals("hook key '$key' is not a run status", ex.message)
            }
        }

    @Test
    fun `an entry that is not exactly one key is rejected for its count`() =
        runTest {
            checkAll(Arb.list(Arb.list(Arb.element(nameChars), 1..8), 2..4)) { keyLists ->
                // The suffix keeps the keys distinct, so the map really has every key generated.
                val entry = keyLists.mapIndexed { i, chars -> "${chars.joinToString("")}-$i" }.associateWith { "notifier" }

                val ex =
                    assertThrows<SerializationException> {
                        Json.decodeFromString(AgentHook.serializer(), Json.encodeToString(entry))
                    }

                assertEquals("a hook entry must have exactly one key (a run status); got ${entry.size}", ex.message)
            }
        }

    @Test
    fun `a non-string value is refused by the wire type`() {
        // The wire form is a string-to-string map, so a value that is not a string never
        // reaches the serializer's own checks — the type error is the refusal.
        assertThrows<SerializationException> {
            Json.decodeFromString(AgentHook.serializer(), """{"FINISHED":123}""")
        }
    }
}
