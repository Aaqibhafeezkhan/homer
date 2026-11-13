package homer.app.services

import homer.app.exceptions.DispatchAgentNotFoundException
import homer.app.exceptions.DispatchGroupTooLargeException
import homer.app.fixtures.TestFixtures.firstText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `ask_and_dispatch_group` MCP tool: its argument parsing, the call it makes into
 * [DispatchGroupService], and the refusals it turns into tool errors. The service's own gates are
 * [DispatchGroupServiceTest]'s. See ADR-0105.
 */
class QuestionsMcpGroupToolTest {
    private val dispatchGroupService = mockk<DispatchGroupService>(relaxed = true)

    private fun request(args: JsonObject? = null) =
        CallToolRequest(CallToolRequestParams(name = "ask_and_dispatch_group", arguments = args))

    private fun args(
        questions: JsonArray? = buildJsonArray { add(question("q1")) },
        agent: String? = "handler",
        answerParam: String? = null,
        params: JsonObject? = null,
        ttlSec: Int? = null,
    ) = buildJsonObject {
        questions?.let { put("questions", it) }
        agent?.let { put("agent", it) }
        answerParam?.let { put("answerParam", it) }
        params?.let { put("params", it) }
        ttlSec?.let { put("ttlSec", it) }
    }

    private fun question(
        text: String?,
        options: List<String> = emptyList(),
    ) = buildJsonObject {
        text?.let { put("text", it) }
        if (options.isNotEmpty()) {
            put("options", buildJsonArray { options.forEach { add(JsonPrimitive(it)) } })
        }
    }

    private suspend fun call(args: JsonObject?) = askAndDispatchGroup(dispatchGroupService, 5, request(args))

    @Test
    fun `ask_and_dispatch_group registers the group and returns the dispatch and question ids`() =
        runTest {
            coEvery {
                dispatchGroupService.create(5, any(), "handler", "reply", mapOf("p1" to "v1"), 60)
            } returns DispatchGroupService.CreateResult(11, listOf("qid-1", "qid-2"))
            val args =
                args(
                    questions =
                        buildJsonArray {
                            add(question("q1", listOf("a", "b")))
                            add(question("q2"))
                        },
                    answerParam = "reply",
                    params = buildJsonObject { put("p1", "v1") },
                    ttlSec = 60,
                )

            val result = call(args)

            assertFalse(result.isError ?: false)
            assertTrue(firstText(result).contains("qid-1, qid-2"))
            val structured = result.structuredContent
            assertEquals("11", (structured?.get("dispatchId") as? JsonPrimitive)?.content)
            assertEquals(
                listOf("qid-1", "qid-2"),
                (structured?.get("questionIds") as? JsonArray)?.map { (it as JsonPrimitive).content },
            )
        }

    @Test
    fun `ask_and_dispatch_group defaults answerParam and parses the question options`() =
        runTest {
            val specSlot = mutableListOf<List<DispatchGroupService.QuestionSpec>>()
            coEvery { dispatchGroupService.create(5, capture(specSlot), any(), any(), any(), any()) } returns
                DispatchGroupService.CreateResult(1, listOf("qid-1"))

            call(args(questions = buildJsonArray { add(question("q1", listOf("a", "b"))) }))

            assertEquals(listOf(DispatchGroupService.QuestionSpec("q1", listOf("a", "b"))), specSlot.single())
            coVerify { dispatchGroupService.create(5, any(), "handler", "answer", emptyMap(), null) }
        }

    // A group is registered atomically, so a malformed question must fail the whole call rather
    // than register the questions that did parse.
    @Test
    fun `ask_and_dispatch_group refuses a malformed question without registering anything`() =
        runTest {
            val cases =
                listOf(
                    args(questions = null),
                    args(questions = buildJsonArray {}),
                    args(questions = buildJsonArray { add(JsonPrimitive("q1")) }),
                    args(questions = buildJsonArray { add(question(text = null)) }),
                    args(agent = null),
                )

            for (case in cases) {
                val result = call(case)
                assertTrue(result.isError == true, "args=$case")
            }
            coVerify(exactly = 0) { dispatchGroupService.create(any(), any(), any(), any(), any(), any()) }
        }

    // The service's size refusal reaches the agent as a tool error, not as a failed MCP call.
    @Test
    fun `ask_and_dispatch_group reports the size refusal as a tool error`() =
        runTest {
            coEvery { dispatchGroupService.create(any(), any(), any(), any(), any(), any()) } throws
                DispatchGroupTooLargeException(8)

            val result = call(args())

            assertTrue(result.isError == true)
            assertTrue(firstText(result).contains("8"))
        }

    // An unknown (or unauthorized) target reaches the agent as a tool error, exactly as the
    // single-question tool reports it: `runTool` turns the service's exception into an isError
    // result rather than failing the MCP call.
    @Test
    fun `ask_and_dispatch_group reports an unknown agent as a tool error`() =
        runTest {
            coEvery { dispatchGroupService.create(any(), any(), any(), any(), any(), any()) } throws
                DispatchAgentNotFoundException("nope")

            val result = call(args(agent = "nope"))

            assertTrue(result.isError == true)
            assertTrue(firstText(result).contains("nope"))
        }

    @Test
    fun `the tool advertises the configured group bound`() =
        runTest {
            every { dispatchGroupService.maxGroupSize } returns 3
            val tool = askAndDispatchGroupTool(dispatchGroupService, processId = 5)

            assertEquals("ask_and_dispatch_group", tool.tool.name)
            assertTrue(tool.tool.description!!.contains("At most 3 questions per group."))
            assertEquals(listOf("questions", "agent"), tool.tool.inputSchema.required)
        }
}
