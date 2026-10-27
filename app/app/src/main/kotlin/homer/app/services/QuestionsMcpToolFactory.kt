package homer.app.services

import homer.app.infra.runTool
import homer.app.infra.toolError
import homer.app.infra.toolResult
import homer.app.models.QuestionsConfig
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.Logger

/**
 * Builds the per-request MCP [Server] for the `/mcp/questions` endpoint:
 * five tools (`ask_user`, `check_answer`, `ask_user_and_wait`, `ask_and_dispatch`,
 * `check_messages`) closed over the processId resolved from the caller's
 * `HMR_CALLBACK_TOKEN`. A fresh Server is built per POST, same stateless pattern as
 * [McpToolFactory]. See ADR-0019 and ADR-0097.
 */
internal class QuestionsMcpToolFactory(
    private val questionsService: QuestionsService,
    private val questionsConfig: QuestionsConfig,
    private val dispatchService: DispatchService,
    private val runMessagesService: RunMessagesService,
    private val logger: Logger,
    private val holdChunkSec: Int = HOLD_CHUNK_SEC,
) {
    fun buildServer(processId: Int): Server {
        val server =
            Server(
                serverInfo =
                    Implementation(
                        name = "homer-questions",
                        version = System.getenv("HOMER_VERSION") ?: "dev",
                    ),
                options =
                    ServerOptions(
                        capabilities = ServerCapabilities(tools = ServerCapabilities.Tools()),
                    ),
            )
        server.addTools(
            listOf(
                askUserTool(processId),
                checkAnswerTool(processId),
                askUserAndWaitTool(processId),
                askAndDispatchTool(processId),
                checkMessagesTool(processId),
            ),
        )
        return server
    }

    /** The `ask_user` tool: ask without blocking, then poll with `check_answer`. */
    private fun askUserTool(processId: Int): RegisteredTool =
        RegisteredTool(
            Tool(
                name = "ask_user",
                description =
                    "Ask the human operator a question without blocking. The question shows up in the " +
                        "Homer console; this returns a question id immediately. Poll with " +
                        "check_answer(questionId, waitSec=$CHECK_MAX_WAIT_SEC) repeatedly until the status " +
                        "is ANSWERED. Prefer ask_user_and_wait when you cannot proceed without the answer.",
                inputSchema = questionSchema(),
            ),
        ) { request -> askUser(processId, request) }

    /** The `check_answer` tool: check, or briefly wait for, an earlier question's answer. */
    private fun checkAnswerTool(processId: Int): RegisteredTool =
        RegisteredTool(
            Tool(
                name = "check_answer",
                description =
                    "Check (or briefly wait for) the operator's answer to a question asked earlier. " +
                        "Holds the call up to waitSec seconds (max $CHECK_MAX_WAIT_SEC). If the returned " +
                        "status is still OPEN — or this call fails — call check_answer again with the same " +
                        "questionId: the question and its eventual answer are never lost between calls.",
                inputSchema = checkAnswerSchema(),
            ),
        ) { request -> checkAnswer(processId, request) }

    /** The `ask_user_and_wait` tool: ask and hold the call until answered or `ttlSec` passes. */
    private fun askUserAndWaitTool(processId: Int): RegisteredTool =
        RegisteredTool(
            Tool(
                name = "ask_user_and_wait",
                description =
                    "Ask the human operator a question and block until it is answered, up to ttlSec " +
                        "seconds (default and cap: ${questionsConfig.maxWaitSec}). If this call times out " +
                        "or fails, the question is NOT lost: call " +
                        "check_answer(questionId, waitSec=$CHECK_MAX_WAIT_SEC) repeatedly to pick up the answer.",
                inputSchema = questionSchema(ttlCapSec = questionsConfig.maxWaitSec),
            ),
        ) { request -> askUserAndWait(this, processId, request) }

    /** The `ask_and_dispatch` tool: ask, and start another agent once the operator answers. */
    private fun askAndDispatchTool(processId: Int): RegisteredTool =
        RegisteredTool(
            Tool(
                name = "ask_and_dispatch",
                description =
                    "Ask the operator a question and, when they answer, start another agent with the " +
                        "given params plus the answer — without waiting. Returns immediately with a " +
                        "dispatchId and questionId. Use when a human decision should hand off to a " +
                        "follow-up agent rather than block this run.",
                inputSchema = dispatchSchema(),
            ),
        ) { request -> askAndDispatch(processId, request) }

    /** The `check_messages` tool: take the operator messages queued for this run. */
    private fun checkMessagesTool(processId: Int): RegisteredTool =
        RegisteredTool(
            Tool(
                name = "check_messages",
                description =
                    "Check for messages the operator sent you while you work. Returns every message queued " +
                        "since the last check, each exactly once, or says there are none. Call it between steps " +
                        "of long work so a correction from the operator reaches you before you continue.",
                inputSchema = ToolSchema(properties = buildJsonObject { }),
            ),
        ) { checkMessages(processId) }

    /** Takes the run's queued messages; a closed run is a tool error and its messages stay queued. */
    internal suspend fun checkMessages(processId: Int): CallToolResult =
        runTool {
            val registration = questionsService.registrationFor(processId)
            if (registration == null || registration.closed) {
                return@runTool toolError("Process $processId is no longer accepting messages")
            }
            val messages = runMessagesService.take(processId)
            val text = if (messages.isEmpty()) "No messages from the operator." else RunMessageHookOutput.contextText(messages)
            CallToolResult(content = listOf(TextContent(text)))
        }

    internal suspend fun askUser(
        processId: Int,
        request: CallToolRequest,
    ): CallToolResult =
        runTool {
            val question = askFromRequest(processId, request)
            questionToolResult(
                "Asked the operator question ${question.id} (status OPEN). Poll with " +
                    "check_answer(questionId=\"${question.id}\", waitSec=$CHECK_MAX_WAIT_SEC) until it is ANSWERED.",
                question,
            )
        }

    internal suspend fun checkAnswer(
        processId: Int,
        request: CallToolRequest,
    ): CallToolResult =
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val id =
                (args["questionId"] as? JsonPrimitive)?.content
                    ?: return@runTool toolError("Missing required argument 'questionId'")
            val waitSec =
                ((args["waitSec"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0)
                    .toInt()
                    .coerceIn(0, CHECK_MAX_WAIT_SEC)
            // Ownership check BEFORE waiting: a foreign question id must fail
            // immediately and read identically to an unknown one (no probing).
            val current = questionsService.get(id)
            if (current == null || current.processId != processId) {
                return@runTool toolError("Question $id not found")
            }
            val question =
                questionsService.awaitAnswer(id, waitSec)
                    ?: return@runTool toolError("Question $id not found")
            questionResult(question)
        }

    internal suspend fun askUserAndWait(
        connection: ClientConnection,
        processId: Int,
        request: CallToolRequest,
    ): CallToolResult =
        runTool {
            val ttlSec = requestedTtlSec(request)
            val question = askFromRequest(processId, request)
            val progressToken = request.params.meta?.progressToken

            // Hold in short chunks rather than one long await: between chunks a
            // progress notification keeps bytes flowing on the SSE response so
            // spec-compliant clients reset their request timeout (ADR-0019).
            var waitedSec = 0
            var latest = question
            while (latest.status == QuestionsService.QuestionStatus.OPEN && waitedSec < ttlSec) {
                val chunkSec = minOf(holdChunkSec, ttlSec - waitedSec)
                latest =
                    questionsService.awaitAnswer(question.id, chunkSec)
                        ?: return@runTool toolError("Question ${question.id} not found")
                waitedSec += chunkSec
                if (latest.status == QuestionsService.QuestionStatus.OPEN && waitedSec < ttlSec) {
                    sendProgress(connection, progressToken, question.id, waitedSec, ttlSec)
                }
            }

            if (latest.status == QuestionsService.QuestionStatus.OPEN) {
                questionToolResult(
                    "No answer after ${ttlSec}s; question ${question.id} is still OPEN. Call " +
                        "check_answer(questionId=\"${question.id}\", waitSec=$CHECK_MAX_WAIT_SEC) repeatedly " +
                        "to keep waiting — the question and its eventual answer are not lost.",
                    latest,
                )
            } else {
                questionResult(latest)
            }
        }

    /** The request's `ttlSec`, clamped to 1..`maxWaitSec`; `maxWaitSec` when absent or not a number. */
    private fun requestedTtlSec(request: CallToolRequest): Int {
        val args = request.arguments ?: JsonObject(emptyMap())
        return ((args["ttlSec"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: questionsConfig.maxWaitSec)
            .coerceIn(1, questionsConfig.maxWaitSec)
    }

    private suspend fun askFromRequest(
        processId: Int,
        request: CallToolRequest,
    ): QuestionsService.Question {
        val args = request.arguments ?: JsonObject(emptyMap())
        val text =
            (args["text"] as? JsonPrimitive)?.content
                ?: throw IllegalArgumentException("Missing required argument 'text'")
        val options =
            (args["options"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                .orEmpty()
        return questionsService.ask(processId, text, options)
    }

    /**
     * Best-effort `notifications/progress`. Claude Code (TS SDK) sets the
     * progressToken to the JSON-RPC request id, so it doubles as the
     * relatedRequestId that routes the event onto this POST's SSE stream;
     * for clients with exotic tokens the send fails and is skipped.
     */
    private suspend fun sendProgress(
        connection: ClientConnection,
        progressToken: RequestId?,
        questionId: String,
        waitedSec: Int,
        ttlSec: Int,
    ) {
        if (progressToken == null) return
        try {
            connection.notification(
                ProgressNotification(
                    ProgressNotificationParams(
                        progressToken = progressToken,
                        progress = waitedSec.toDouble(),
                        total = ttlSec.toDouble(),
                        message = "Waiting for the operator to answer question $questionId (${waitedSec}s/${ttlSec}s)",
                    ),
                ),
                progressToken,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("Progress notification for question {} not delivered: {}", questionId, e.message)
        }
    }

    private fun questionResult(question: QuestionsService.Question): CallToolResult =
        when (question.status) {
            QuestionsService.QuestionStatus.ANSWERED -> {
                questionToolResult("The operator answered question ${question.id}: ${question.answer}", question)
            }

            QuestionsService.QuestionStatus.EXPIRED -> {
                questionToolResult(
                    "Question ${question.id} EXPIRED without an answer: the run reached a terminal state. Do not re-ask.",
                    question,
                )
            }

            QuestionsService.QuestionStatus.OPEN -> {
                questionToolResult(
                    "Question ${question.id} is still OPEN (no answer yet). Call " +
                        "check_answer(questionId=\"${question.id}\", waitSec=$CHECK_MAX_WAIT_SEC) again — " +
                        "the question and its eventual answer are not lost.",
                    question,
                )
            }
        }

    internal suspend fun askAndDispatch(
        processId: Int,
        request: CallToolRequest,
    ): CallToolResult =
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val text =
                (args["text"] as? JsonPrimitive)?.content
                    ?: return@runTool toolError("Missing required argument 'text'")
            val agent =
                (args["agent"] as? JsonPrimitive)?.content
                    ?: return@runTool toolError("Missing required argument 'agent'")
            val answerParam = (args["answerParam"] as? JsonPrimitive)?.content ?: "answer"
            val options =
                (args["options"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    .orEmpty()
            val params =
                (args["params"] as? JsonObject)
                    ?.entries
                    ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }
                    ?.toMap()
                    .orEmpty()
            val ttlSec = (args["ttlSec"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
            val result = dispatchService.create(processId, text, options, agent, answerParam, params, ttlSec)
            CallToolResult(
                content =
                    listOf(
                        TextContent(
                            "Registered dispatch ${result.dispatchId}: question ${result.questionId} will start " +
                                "agent '$agent' once answered.",
                        ),
                    ),
                structuredContent =
                    buildJsonObject {
                        put("dispatchId", result.dispatchId)
                        put("questionId", result.questionId)
                    },
            )
        }

    /** A tool result whose structured content is [question]'s id, status and answer. */
    private fun questionToolResult(
        text: String,
        question: QuestionsService.Question,
    ): CallToolResult =
        toolResult(
            text,
            buildJsonObject {
                put("questionId", question.id)
                put("status", question.status.name)
                question.answer?.let { put("answer", it) }
            },
        )

    companion object {
        const val CHECK_MAX_WAIT_SEC = 50
        private const val HOLD_CHUNK_SEC = 15
    }
}

/** The `check_answer` input: the question id and an optional, clamped hold. */
private fun checkAnswerSchema(): ToolSchema =
    ToolSchema(
        properties =
            buildJsonObject {
                put(
                    "questionId",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Question id returned by ask_user or ask_user_and_wait")
                    },
                )
                put(
                    "waitSec",
                    buildJsonObject {
                        put("type", "number")
                        put(
                            "description",
                            "Seconds to hold this call waiting for the answer " +
                                "(0..${QuestionsMcpToolFactory.CHECK_MAX_WAIT_SEC}, clamped). Omit for an instant check.",
                        )
                    },
                )
            },
        required = listOf("questionId"),
    )

/** The `ask_user` / `ask_user_and_wait` input; [ttlCapSec] adds the `ttlSec` hold, capped there. */
private fun questionSchema(ttlCapSec: Int? = null): ToolSchema =
    ToolSchema(
        properties =
            buildJsonObject {
                put(
                    "text",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "The question for the operator. Markdown is rendered in the console.")
                    },
                )
                put(
                    "options",
                    buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put(
                            "description",
                            "Optional predefined answers shown as buttons; the operator can still type a custom answer.",
                        )
                    },
                )
                if (ttlCapSec != null) {
                    put(
                        "ttlSec",
                        buildJsonObject {
                            put("type", "number")
                            put(
                                "description",
                                "Max seconds to block waiting for the answer " +
                                    "(default and cap: $ttlCapSec).",
                            )
                        },
                    )
                }
            },
        required = listOf("text"),
    )

/** The `ask_and_dispatch` input: the question, the agent to start and what to pass it. */
private fun dispatchSchema(): ToolSchema =
    ToolSchema(
        properties =
            buildJsonObject {
                put(
                    "text",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "The question for the operator (Markdown).")
                    },
                )
                put(
                    "agent",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Agent to start when the question is answered.")
                    },
                )
                put(
                    "answerParam",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Input name the answer is passed under (default 'answer').")
                    },
                )
                put(
                    "options",
                    buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Optional predefined answers.")
                    },
                )
                put(
                    "params",
                    buildJsonObject {
                        put("type", "object")
                        put("description", "Extra inputs (string values) passed to the started agent.")
                    },
                )
                put(
                    "ttlSec",
                    buildJsonObject {
                        put("type", "number")
                        put("description", "Optional: cancel if unanswered after this many seconds.")
                    },
                )
            },
        required = listOf("text", "agent"),
    )
