package homer.app.services

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.codepoints
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunMessageHookOutputTest {
    private fun msg(text: String) =
        RunMessage(
            id = "m-$text".take(8),
            processId = 1,
            text = text,
            status = RunMessageStatus.DELIVERED,
            sender = "alice",
            createdAt = 1,
            deliveredAt = 2,
        )

    @Test
    fun `contextText for one message`() {
        assertEquals(
            "The operator sent you a message while you were working. Take it into account before you continue.\n\n" +
                "Message from the operator:\nstop touching prod",
            RunMessageHookOutput.contextText(listOf(msg("stop touching prod"))),
        )
    }

    @Test
    fun `contextText for three messages joins them with a blank line and counts them`() {
        assertEquals(
            "The operator sent you 3 messages while you were working. Take them into account before you continue.\n\n" +
                "Message from the operator:\none\n\n" +
                "Message from the operator:\ntwo\n\n" +
                "Message from the operator:\nthree",
            RunMessageHookOutput.contextText(listOf(msg("one"), msg("two"), msg("three"))),
        )
    }

    @Test
    fun `render PostToolUse puts the context into hookSpecificOutput`() {
        val messages = listOf(msg("hi"))
        assertEquals(
            Json.parseToJsonElement(
                """{"hookSpecificOutput":{"hookEventName":"PostToolUse","additionalContext":""" +
                    Json.encodeToString(RunMessageHookOutput.contextText(messages)) + "}}",
            ),
            RunMessageHookOutput.render(HookEvent.POST_TOOL_USE, messages),
        )
    }

    @Test
    fun `render Stop blocks with the context as the reason`() {
        val messages = listOf(msg("hi"))
        assertEquals(
            Json.parseToJsonElement(
                """{"decision":"block","reason":""" + Json.encodeToString(RunMessageHookOutput.contextText(messages)) + "}",
            ),
            RunMessageHookOutput.render(HookEvent.STOP, messages),
        )
    }

    @Test
    fun `HookEvent parses its query parameter value and nothing else`() {
        assertEquals(HookEvent.POST_TOOL_USE, HookEvent.fromParam("post-tool-use"))
        assertEquals(HookEvent.STOP, HookEvent.fromParam("stop"))
        assertEquals(null, HookEvent.fromParam("PostToolUse"))
    }

    @Test
    fun `any message text renders to valid hook JSON that decodes back to the text verbatim`() =
        runTest {
            val special = Arb.element(listOf('"', '\\', '\n', '\r', '\t', '\u0000', '\u001f', ' ', '/').map { Codepoint(it.code) })
            val texts = Arb.string(0..64, Arb.choice(Arb.codepoints(), special))
            checkAll(Arb.enum<HookEvent>(), texts) { event, text ->
                val rendered = RunMessageHookOutput.render(event, listOf(msg(text))).toString()
                val decoded = Json.parseToJsonElement(rendered).jsonObject
                val context =
                    when (event) {
                        HookEvent.POST_TOOL_USE -> decoded.getValue("hookSpecificOutput").jsonObject.getValue("additionalContext")
                        HookEvent.STOP -> decoded.getValue("reason")
                    }.jsonPrimitive.content
                assertTrue(context.endsWith("Message from the operator:\n$text"), "decoded context must carry the text verbatim")
            }
        }
}
