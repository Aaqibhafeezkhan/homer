package homer.app.services

import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.TestFixtures
import homer.app.fixtures.all
import homer.app.models.QuestionsConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.RequestMeta
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuestionsMcpToolFactoryTest {
    private val connection = mockk<ClientConnection>(relaxed = true)

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    private fun service(config: QuestionsConfig = QuestionsConfig()) = PersistenceTestSupport.questionsService(config = config)

    private fun factory(
        service: QuestionsService,
        config: QuestionsConfig = QuestionsConfig(),
        dispatchService: DispatchService = mockk(relaxed = true),
    ) = QuestionsMcpToolFactory(
        questionsService = service,
        questionsConfig = config,
        dispatchService = dispatchService,
        logger = TestFixtures.mockLogger(),
    )

    private fun request(
        args: JsonObject? = null,
        progressToken: Int? = null,
    ) = CallToolRequest(
        CallToolRequestParams(
            name = "test",
            arguments = args,
            meta = progressToken?.let { RequestMeta(buildJsonObject { put("progressToken", it) }) },
        ),
    )

    private fun askArgs(
        text: String = "Proceed?",
        ttlSec: Int? = null,
    ) = buildJsonObject {
        put("text", text)
        ttlSec?.let { put("ttlSec", it) }
    }

    private fun firstText(result: CallToolResult): String = (result.content.first() as TextContent).text

    private fun structured(
        result: CallToolResult,
        key: String,
    ): String? = (result.structuredContent?.get(key) as? JsonPrimitive)?.content

    @Test
    fun `buildServer registers the four question tools`() {
        val server = factory(service()).buildServer(processId = 1)

        assertEquals(setOf("ask_user", "check_answer", "ask_user_and_wait", "ask_and_dispatch"), server.tools.keys)
    }

    @Test
    fun `ask_user creates an open question and instructs polling`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")

            val args =
                buildJsonObject {
                    put("text", "Deploy **now**?")
                    put(
                        "options",
                        buildJsonArray {
                            add(JsonPrimitive("yes"))
                            add(JsonPrimitive("no"))
                        },
                    )
                }
            val result = factory(service).askUser(1, request(args))

            assertEquals(false, result.isError == true)
            val questionId = assertNotNull(structured(result, "questionId"))
            assertEquals("OPEN", structured(result, "status"))
            assertTrue("check_answer" in firstText(result), "result must instruct re-polling: ${firstText(result)}")
            val question = assertNotNull(service.get(questionId))
            assertEquals("Deploy **now**?", question.text)
            assertEquals(listOf("yes", "no"), question.options)
        }

    @Test
    fun `ask_user without text is a tool error`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")

            val result = factory(service).askUser(1, request(buildJsonObject { }))

            assertEquals(true, result.isError)
        }

    @Test
    fun `ask_user on a terminal process is a tool error`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")
            service.expireForProcess(1)

            val result = factory(service).askUser(1, request(askArgs()))

            assertEquals(true, result.isError)
        }

    @Test
    fun `check_answer returns the answer once given`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")
            val question = service.ask(1, "Proceed?")
            service.answer(question.id, "yes, go")

            val result =
                factory(service).checkAnswer(1, request(buildJsonObject { put("questionId", question.id) }))

            assertEquals("ANSWERED", structured(result, "status"))
            assertEquals("yes, go", structured(result, "answer"))
            assertTrue("yes, go" in firstText(result))
        }

    @Test
    fun `check_answer waits for an answer mid-poll`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")
            val question = service.ask(1, "Proceed?")
            launch {
                delay(2_000)
                service.answer(question.id, "late yes")
            }

            val result =
                factory(service).checkAnswer(
                    1,
                    request(
                        buildJsonObject {
                            put("questionId", question.id)
                            put("waitSec", 10)
                        },
                    ),
                )

            assertEquals("ANSWERED", structured(result, "status"))
            assertEquals(2_000, currentTime)
        }

    @Test
    fun `check_answer clamps waitSec to 50 seconds and stays resumable`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")
            val question = service.ask(1, "Proceed?")

            val result =
                factory(service).checkAnswer(
                    1,
                    request(
                        buildJsonObject {
                            put("questionId", question.id)
                            put("waitSec", 500)
                        },
                    ),
                )

            assertEquals("OPEN", structured(result, "status"))
            assertNull(structured(result, "answer"))
            assertTrue("check_answer" in firstText(result), "OPEN result must instruct re-polling")
            assertEquals(50_000, currentTime)
        }

    @Test
    fun `check_answer reads unknown and foreign questions as the same not found error`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")
            service.registerProcess(2, "agent-b")
            val foreign = service.ask(2, "other process question")
            val factory = factory(service)

            val unknown = factory.checkAnswer(1, request(buildJsonObject { put("questionId", "nope") }))
            val crossProcess = factory.checkAnswer(1, request(buildJsonObject { put("questionId", foreign.id) }))

            assertEquals(true, unknown.isError)
            assertEquals(true, crossProcess.isError)
            assertEquals(
                firstText(unknown).replace("nope", "X"),
                firstText(crossProcess).replace(foreign.id, "X"),
                "foreign ids must be indistinguishable from unknown ones",
            )
        }

    @Test
    fun `ask_user_and_wait returns the answer and sends progress between chunks`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")
            launch {
                delay(40_000)
                val open = service.all(QuestionsService.QuestionStatus.OPEN).single()
                service.answer(open.id, "answered mid-hold")
            }

            val result =
                factory(service).askUserAndWait(connection, 1, request(askArgs(), progressToken = 7))

            assertEquals("ANSWERED", structured(result, "status"))
            assertEquals("answered mid-hold", structured(result, "answer"))
            // 40s of 15s chunks = notifications after 15s and 30s.
            coVerify(exactly = 2) { connection.notification(any(), any()) }
        }

    @Test
    fun `ask_user_and_wait without a progressToken sends no notifications`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")
            launch {
                delay(20_000)
                val open = service.all(QuestionsService.QuestionStatus.OPEN).single()
                service.answer(open.id, "ok")
            }

            val result = factory(service).askUserAndWait(connection, 1, request(askArgs()))

            assertEquals("ANSWERED", structured(result, "status"))
            coVerify(exactly = 0) { connection.notification(any(), any()) }
        }

    @Test
    fun `ask_user_and_wait times out OPEN with re-poll instructions`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")

            val result =
                factory(service).askUserAndWait(connection, 1, request(askArgs(ttlSec = 30)))

            assertEquals("OPEN", structured(result, "status"))
            assertTrue("check_answer" in firstText(result), "timeout must instruct re-polling: ${firstText(result)}")
            assertEquals(30_000, currentTime)
        }

    @Test
    fun `ask_user_and_wait clamps ttl to maxWaitSec`() =
        runTest {
            val config = QuestionsConfig(maxWaitSec = 60)
            val service = service(config)
            service.registerProcess(1, "agent-a")

            val result =
                factory(service, config).askUserAndWait(connection, 1, request(askArgs(ttlSec = 5_000)))

            assertEquals("OPEN", structured(result, "status"))
            assertEquals(60_000, currentTime)
        }

    @Test
    fun `ask_user_and_wait reports expiry when the process turns terminal`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")
            launch {
                delay(5_000)
                service.expireForProcess(1)
            }

            val result = factory(service).askUserAndWait(connection, 1, request(askArgs()))

            assertEquals("EXPIRED", structured(result, "status"))
            assertTrue("EXPIRED" in firstText(result))
        }

    @Test
    fun `ask_and_dispatch tool creates a dispatch and returns ids`() =
        runTest {
            val dispatchService = mockk<DispatchService>()
            coEvery {
                dispatchService.create(5, "q", emptyList(), "handler", "reply", mapOf("p1" to "v1"), null)
            } returns DispatchService.CreateResult(dispatchId = 11, questionId = "qid-1")
            val factory = factory(service(), dispatchService = dispatchService)

            val args =
                buildJsonObject {
                    put("text", "q")
                    put("agent", "handler")
                    put("answerParam", "reply")
                    put("params", buildJsonObject { put("p1", "v1") })
                }
            val result = factory.askAndDispatch(processId = 5, request(args))

            assertFalse(result.isError ?: false)
            assertTrue(firstText(result).contains("qid-1"))
        }
}
