package homer.app.routes

import homer.app.callerKey
import homer.app.exceptions.BodylessBadRequestException
import homer.app.infra.SseConnectionLimiter
import homer.app.models.LlmEvent
import homer.app.models.LlmEventJson
import homer.app.services.Authorizer
import homer.app.services.LlmEventStreamService
import homer.app.services.LlmStreamEvent
import homer.app.services.LogTailService
import homer.app.services.ProcessEvent
import homer.app.services.ProcessEventBroadcaster
import homer.app.services.ProcessManager
import homer.app.services.StreamCursor
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

private const val HEARTBEAT_MS = 15_000L

fun Route.streamingRoute(
    broadcaster: ProcessEventBroadcaster,
    logTailService: LogTailService,
    authorizer: Authorizer,
    processManager: ProcessManager,
    llmEventStreamService: LlmEventStreamService,
) {
    // One limiter for the app lifetime (streamingRoute is registered once). Bounds
    // concurrent streams per caller so one principal can't open them without bound.
    val sseLimiter = SseConnectionLimiter()
    route("/stream") {
        processEventsStreamRoute(sseLimiter, broadcaster, authorizer, processManager)
        agentEventsStreamRoute(sseLimiter, broadcaster, authorizer)
        processLogsStreamRoute(sseLimiter, logTailService, authorizer, processManager)
        llmEventsStreamRoute(sseLimiter, llmEventStreamService, authorizer, processManager)
    }
}

/** `GET /stream/processes`: status, command, question and message events of the processes the caller can see. */
private fun Route.processEventsStreamRoute(
    sseLimiter: SseConnectionLimiter,
    broadcaster: ProcessEventBroadcaster,
    authorizer: Authorizer,
    processManager: ProcessManager,
) {
    get("/processes") {
        val principal = call.principal<UserIdPrincipal>()?.name
        // Per-connection memo (processId -> owner, agentName) so repeated events
        // for the same process don't each re-query ProcessManager.
        val visibilityMemo = mutableMapOf<Int, Pair<String?, String>>()
        call.withSseSlot(sseLimiter) {
            runSseSession(this) {
                broadcaster
                    .subscribe()
                    .collect { event ->
                        val pid = streamedProcessId(event) ?: return@collect
                        val (owner, agentName) =
                            visibilityMemo.getOrPut(pid) {
                                val info = processManager.getProcessInfo(pid)
                                info?.owner to (info?.agentName ?: "")
                            }
                        if (!authorizer.canSee(principal, owner, agentName)) return@collect
                        sendFrame(this, processEventFrame(event) ?: return@collect)
                    }
            }
        }
    }
}

/** `GET /stream/agents`: the `agent.reload` diff, narrowed to the agents the caller holds a grant on. */
private fun Route.agentEventsStreamRoute(
    sseLimiter: SseConnectionLimiter,
    broadcaster: ProcessEventBroadcaster,
    authorizer: Authorizer,
) {
    get("/agents") {
        val principal = call.principal<UserIdPrincipal>()?.name
        call.withSseSlot(sseLimiter) {
            runSseSession(this) {
                broadcaster
                    .subscribe()
                    .filter { it is ProcessEvent.AgentsReloaded }
                    .collect { event ->
                        val reloaded = event as ProcessEvent.AgentsReloaded
                        // Mirror GET /api/v1/agents: a principal only learns about
                        // agent names it holds a grant on (admins see all via anyGrant).
                        // The frame itself is ALWAYS sent, even with all lists empty:
                        // consumers use it as the "reload completed" signal (a no-op
                        // reload has a genuinely empty diff — file edits reload
                        // inline, so a follow-up POST /reload often changes nothing),
                        // and suppressing filtered-empty frames would let a no-grant
                        // principal infer that some hidden agent changed.
                        val added = reloaded.added.filter { authorizer.anyGrant(principal, it) }
                        val updated = reloaded.updated.filter { authorizer.anyGrant(principal, it) }
                        val removed = reloaded.removed.filter { authorizer.anyGrant(principal, it) }
                        sendEvent(
                            this,
                            "agent.reload",
                            buildJsonObject {
                                put("added", buildJsonArray { added.forEach { add(JsonPrimitive(it)) } })
                                put("updated", buildJsonArray { updated.forEach { add(JsonPrimitive(it)) } })
                                put("removed", buildJsonArray { removed.forEach { add(JsonPrimitive(it)) } })
                            }.toString(),
                        )
                    }
            }
        }
    }
}

/** `GET /stream/processes/{id}/logs`: the tail of one command's stdout or stderr. */
private fun Route.processLogsStreamRoute(
    sseLimiter: SseConnectionLimiter,
    logTailService: LogTailService,
    authorizer: Authorizer,
    processManager: ProcessManager,
) {
    get("/processes/{id}/logs") {
        val processId = call.parameters["id"]?.toIntOrNull()
        val cmdIndex = call.request.queryParameters["cmd"]?.toIntOrNull() ?: 0
        val stream = logStream(call.request.queryParameters["stream"])

        if (processId == null || stream == null) throw BodylessBadRequestException()

        if (!call.canSeeStreamedProcess(processId, processManager, authorizer)) return@get

        val path = logTailService.resolve(processId, cmdIndex, stream)
        if (path == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        val resumeOffset = call.request.header("Last-Event-ID")?.toLongOrNull()

        call.withSseSlot(sseLimiter) {
            runSseSession(this) {
                logTailService
                    .tail(processId, path, resumeOffset)
                    .collect { event -> sendFrame(this, logEventFrame(event)) }
            }
        }
    }
}

/** `GET /stream/processes/{id}/llm-events`: the translated LLM events of one command or the whole run. */
private fun Route.llmEventsStreamRoute(
    sseLimiter: SseConnectionLimiter,
    llmEventStreamService: LlmEventStreamService,
    authorizer: Authorizer,
    processManager: ProcessManager,
) {
    get("/processes/{id}/llm-events") {
        val processId = call.parameters["id"]?.toIntOrNull()
        val cmdIndex = optionalCmdIndex(call.request.queryParameters["cmd"])
        if (processId == null) throw BodylessBadRequestException()

        if (!call.canSeeStreamedProcess(processId, processManager, authorizer)) return@get

        if (cmdIndex != null && llmEventStreamService.resolve(processId, cmdIndex) == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        val resume = StreamCursor.parse(call.request.header("Last-Event-ID"))
        val source =
            if (cmdIndex != null) {
                llmEventStreamService.streamCommand(processId, cmdIndex, resume)
            } else {
                llmEventStreamService.streamProcess(processId, resume)
            }

        call.withSseSlot(sseLimiter) {
            runSseSession(this) {
                source.collect { event -> sendFrame(this, llmEventFrame(processId, event)) }
            }
        }
    }
}

/** One SSE frame: its `event:` name, its `data:` JSON and an optional `id:`. */
internal data class SseFrame(
    val name: String,
    val data: String,
    val id: String? = null,
)

/** The process a `/stream/processes` [event] is about; null for an event that stream does not carry. */
internal fun streamedProcessId(event: ProcessEvent): Int? =
    when (event) {
        is ProcessEvent.StatusChanged -> event.processId
        is ProcessEvent.CurrentCommandChanged -> event.processId
        is ProcessEvent.QuestionAsked -> event.processId
        is ProcessEvent.QuestionAnswered -> event.processId
        is ProcessEvent.QuestionExpired -> event.processId
        else -> messageEvent(event)?.processId
    }

/** The `/stream/processes` frame of [event]; null for an event that stream does not carry. */
internal fun processEventFrame(event: ProcessEvent): SseFrame? =
    when (event) {
        is ProcessEvent.StatusChanged -> {
            SseFrame(
                "process.status",
                buildJsonObject {
                    put("processId", JsonPrimitive(event.processId))
                    put("status", JsonPrimitive(event.status.name))
                    put("agentName", JsonPrimitive(event.agentName))
                }.toString(),
            )
        }

        is ProcessEvent.CurrentCommandChanged -> {
            SseFrame(
                "process.cmd",
                buildJsonObject {
                    put("processId", JsonPrimitive(event.processId))
                    put("label", event.label?.let(::JsonPrimitive) ?: JsonNull)
                    put("startedAt", event.startedAt?.let(::JsonPrimitive) ?: JsonNull)
                }.toString(),
            )
        }

        is ProcessEvent.QuestionAsked -> {
            SseFrame(
                "question.asked",
                buildJsonObject {
                    put("processId", JsonPrimitive(event.processId))
                    put("questionId", JsonPrimitive(event.questionId))
                    put("agentName", JsonPrimitive(event.agentName))
                }.toString(),
            )
        }

        is ProcessEvent.QuestionAnswered -> {
            SseFrame(
                "question.answered",
                buildJsonObject {
                    put("processId", JsonPrimitive(event.processId))
                    put("questionId", JsonPrimitive(event.questionId))
                }.toString(),
            )
        }

        is ProcessEvent.QuestionExpired -> {
            SseFrame(
                "question.expired",
                buildJsonObject {
                    put("processId", JsonPrimitive(event.processId))
                    put("questionId", JsonPrimitive(event.questionId))
                }.toString(),
            )
        }

        else -> {
            messageEvent(event)?.let { SseFrame(it.name, it.payload()) }
        }
    }

/** An inbox event of a run, as `/stream/processes` carries it. */
private class MessageEvent(
    val name: String,
    val processId: Int,
    val messageId: String,
) {
    fun payload(): String =
        buildJsonObject {
            put("processId", JsonPrimitive(processId))
            put("messageId", JsonPrimitive(messageId))
        }.toString()
}

private fun messageEvent(event: ProcessEvent): MessageEvent? =
    when (event) {
        is ProcessEvent.MessageQueued -> MessageEvent("message.queued", event.processId, event.messageId)
        is ProcessEvent.MessageDelivered -> MessageEvent("message.delivered", event.processId, event.messageId)
        is ProcessEvent.MessageUndelivered -> MessageEvent("message.undelivered", event.processId, event.messageId)
        else -> null
    }

/** The log stream the `stream` query parameter names (default `out`, any case); null for any other value. */
internal fun logStream(streamParam: String?): LogTailService.Stream? =
    when (streamParam?.lowercase() ?: "out") {
        "out" -> LogTailService.Stream.OUT
        "err" -> LogTailService.Stream.ERR
        else -> null
    }

/** The `/logs` frame of [event]: a chunk carries its end offset as the resume id. */
internal fun logEventFrame(event: LogTailService.LogEvent): SseFrame =
    when (event) {
        is LogTailService.LogEvent.Chunk -> {
            SseFrame(
                name = "log.chunk",
                data =
                    buildJsonObject {
                        put("content", JsonPrimitive(event.content))
                    }.toString(),
                id = event.offset.toString(),
            )
        }

        LogTailService.LogEvent.End -> {
            SseFrame("log.end", "{}")
        }
    }

/**
 * The command index the optional `cmd` query parameter of `/llm-events` names: null when it is
 * absent; anything but a non-negative integer is a bodyless 400.
 */
internal fun optionalCmdIndex(cmdParam: String?): Int? {
    if (cmdParam == null) return null
    val cmdIndex = cmdParam.toIntOrNull()
    if (cmdIndex == null || cmdIndex < 0) throw BodylessBadRequestException()
    return cmdIndex
}

/** The `/llm-events` frame of [event] for process [processId]: an event carries its [StreamCursor] as the resume id. */
internal fun llmEventFrame(
    processId: Int,
    event: LlmStreamEvent,
): SseFrame =
    when (event) {
        is LlmStreamEvent.Event -> {
            SseFrame(
                "llm.event",
                buildJsonObject {
                    put("processId", JsonPrimitive(processId))
                    put("cmdIndex", JsonPrimitive(event.cmdIndex))
                    put("attemptIndex", JsonPrimitive(event.attemptIndex))
                    put("event", LlmEventJson.encodeToJsonElement(LlmEvent.serializer(), event.event))
                }.toString(),
                id = StreamCursor(event.cmdIndex, event.attemptIndex, event.lineOffset, event.eventIndex).id,
            )
        }

        LlmStreamEvent.End -> {
            SseFrame("llm.end", "{}")
        }
    }

/**
 * Whether the caller can see process [processId]; when not, responds a bodyless `404`, the same for
 * a missing and a hidden process (ADR-0039).
 */
private suspend fun ApplicationCall.canSeeStreamedProcess(
    processId: Int,
    processManager: ProcessManager,
    authorizer: Authorizer,
): Boolean {
    val info = processManager.getProcessInfo(processId)
    if (info == null || !authorizer.canSee(principal<UserIdPrincipal>()?.name, info.owner, info.agentName)) {
        respond(HttpStatusCode.NotFound)
        return false
    }
    return true
}

/**
 * Acquires a per-caller stream slot, opens the SSE response, and releases the slot
 * when the stream ends (client disconnect / cancellation). Responds 429 without
 * streaming when the caller is already at its concurrent-stream cap.
 */
private suspend fun ApplicationCall.withSseSlot(
    limiter: SseConnectionLimiter,
    body: suspend ByteWriteChannel.() -> Unit,
) {
    val key = callerKey()
    if (!limiter.tryAcquire(key)) {
        respond(HttpStatusCode.TooManyRequests)
        return
    }
    try {
        respondBytesWriter(contentType = ContentType.Text.EventStream) { body() }
    } finally {
        limiter.release(key)
    }
}

@Suppress("SuspendFunSwallowedCancellation") // a disconnect ends the SSE response, and nothing runs after it
private suspend fun runSseSession(
    channel: ByteWriteChannel,
    block: suspend SseSession.() -> Unit,
) {
    val session = SseSession(channel)
    try {
        coroutineScope {
            // Flush an immediate ping: Netty defers the response headers until the
            // first body byte, so without it an idle stream (no events yet) never
            // delivers headers and the client cannot tell it is subscribed.
            session.ping()
            val heartbeat =
                launch {
                    while (isActive) {
                        delay(HEARTBEAT_MS)
                        session.ping()
                    }
                }
            try {
                session.block()
            } finally {
                heartbeat.cancel()
            }
        }
    } catch (_: CancellationException) {
        // Client disconnected or route cancelled; nothing to clean up further.
    }
}

private class SseSession(
    val channel: ByteWriteChannel,
)

private suspend fun SseSession.ping() {
    channel.writeStringUtf8(": ping\n\n")
    channel.flush()
}

private suspend fun sendEvent(
    session: SseSession,
    name: String,
    data: String,
    id: String? = null,
) {
    val sb = StringBuilder()
    sb.append("event: ").append(name).append('\n')
    if (id != null) sb.append("id: ").append(id).append('\n')
    sb.append("data: ").append(data).append("\n\n")
    session.channel.writeStringUtf8(sb.toString())
    session.channel.flush()
}

private suspend fun sendFrame(
    session: SseSession,
    frame: SseFrame,
) = sendEvent(session, frame.name, frame.data, frame.id)
