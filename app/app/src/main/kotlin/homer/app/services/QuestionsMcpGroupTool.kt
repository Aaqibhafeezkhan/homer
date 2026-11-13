package homer.app.services

import homer.app.infra.runTool
import homer.app.infra.toolError
import homer.app.infra.toolResult
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The `ask_and_dispatch_group` question tool, kept beside QuestionsMcpToolFactory rather than in
// it: the factory is a list of tools, this one has a schema and an argument parser of its own.

/**
 * The `ask_and_dispatch_group` tool: register N questions as one dispatch, answered
 * independently, and start [DispatchGroupService]'s target agent once — with every answer in
 * question order — after the last of them. See ADR-0109.
 */
internal fun askAndDispatchGroupTool(
    dispatchGroupService: DispatchGroupService,
    processId: Int,
): RegisteredTool =
    RegisteredTool(
        Tool(
            name = "ask_and_dispatch_group",
            description =
                "Ask the operator several questions at once and, when they have answered every one, " +
                    "start another agent with the given params plus all the answers, in the order you " +
                    "asked them, as a JSON array. Returns immediately with a dispatchId and one " +
                    "questionId per question. Use when several independent decisions together should " +
                    "hand off to a follow-up agent. At most " +
                    "${dispatchGroupService.maxGroupSize} questions per group.",
            inputSchema = dispatchGroupSchema(dispatchGroupService.maxGroupSize),
        ),
    ) { request -> askAndDispatchGroup(dispatchGroupService, processId, request) }

/**
 * The `ask_and_dispatch_group` handler. Its questions are parsed one by one, and a malformed one
 * fails the whole call: the group is registered atomically, so a partly-parsed list must never
 * reach [DispatchGroupService.create].
 */
internal suspend fun askAndDispatchGroup(
    dispatchGroupService: DispatchGroupService,
    processId: Int,
    request: CallToolRequest,
): CallToolResult =
    runTool {
        val args = request.arguments ?: JsonObject(emptyMap())
        val specs =
            (args["questions"] as? JsonArray)
                ?: return@runTool toolError("Missing required argument 'questions'")
        if (specs.isEmpty()) return@runTool toolError("'questions' must hold at least one question")
        val questions =
            specs.map { element ->
                val spec = element as? JsonObject ?: return@runTool toolError("Each question must be an object")
                DispatchGroupService.QuestionSpec(
                    text =
                        (spec["text"] as? JsonPrimitive)?.content
                            ?: return@runTool toolError("Each question needs a 'text'"),
                    options =
                        (spec["options"] as? JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.content }
                            .orEmpty(),
                )
            }
        val agent =
            (args["agent"] as? JsonPrimitive)?.content
                ?: return@runTool toolError("Missing required argument 'agent'")
        val answerParam = (args["answerParam"] as? JsonPrimitive)?.content ?: "answer"
        val params = args.stringParams()
        val ttlSec = args.ttlSecArg()
        val result =
            dispatchGroupService.create(
                originProcessId = processId,
                questions = questions,
                agent = agent,
                answerParam = answerParam,
                params = params,
                ttlSec = ttlSec,
            )
        CallToolResult(
            content =
                listOf(
                    TextContent(
                        "Registered dispatch group ${result.dispatchId}: agent '$agent' starts once all " +
                            "${result.questionIds.size} questions are answered. Question ids: " +
                            result.questionIds.joinToString(", "),
                    ),
                ),
            structuredContent =
                buildJsonObject {
                    put("dispatchId", result.dispatchId)
                    put("questionIds", buildJsonArray { result.questionIds.forEach { add(JsonPrimitive(it)) } })
                },
        )
    }

/** The `ask_and_dispatch_group` input: the questions, the agent to start and what to pass it. */
private fun dispatchGroupSchema(maxGroupSize: Int): ToolSchema =
    ToolSchema(
        properties =
            buildJsonObject {
                put("questions", questionsSchema(maxGroupSize))
                put(
                    "agent",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Agent to start once every question is answered.")
                    },
                )
                put(
                    "answerParam",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Input name the answers are passed under, as a JSON array (default 'answer').",
                        )
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
                        put("description", "Optional: cancel the whole group if unanswered after this many seconds.")
                    },
                )
            },
        required = listOf("questions", "agent"),
    )

/** The `params` argument as a string map: non-primitive values are dropped. */
internal fun JsonObject.stringParams(): Map<String, String> =
    (this["params"] as? JsonObject)
        ?.entries
        ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }
        ?.toMap()
        .orEmpty()

/** The optional `ttlSec` argument, truncated to whole seconds. */
internal fun JsonObject.ttlSecArg(): Int? = (this["ttlSec"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()

/** The `questions` array: each item a `text` and optional `options`, at most [maxGroupSize] of them. */
private fun questionsSchema(maxGroupSize: Int): JsonObject =
    buildJsonObject {
        put("type", "array")
        put(
            "description",
            "The questions for the operator, in the order their answers are passed to the " +
                "agent (at most $maxGroupSize).",
        )
        put(
            "items",
            buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "text",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "The question for the operator (Markdown).")
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
                    },
                )
                put("required", buildJsonArray { add(JsonPrimitive("text")) })
            },
        )
    }
