package homer.app.routes

import homer.app.EnvConstants
import homer.app.exceptions.QueryParamValidationException
import homer.app.exceptions.QuestionNotFoundException
import homer.app.exceptions.RunNotAcceptingMessagesException
import homer.app.exceptions.ValidationException
import homer.app.infra.ResultToEnvMapper
import homer.app.models.Agent
import homer.app.models.AskQuestionRequest
import homer.app.models.AskQuestionResponse
import homer.app.models.CommandResult
import homer.app.models.CreateDispatchRequest
import homer.app.models.CreateDispatchResponse
import homer.app.models.CreateJoinRequest
import homer.app.models.CreateJoinResponse
import homer.app.models.CreateRunRequest
import homer.app.models.CreateRunResponse
import homer.app.models.DeclareTagRequest
import homer.app.models.DeclareTagResponse
import homer.app.models.ErrorResponse
import homer.app.models.ResultEnvParam
import homer.app.models.ResultTransformer
import homer.app.models.SrcType
import homer.app.models.TagValidation
import homer.app.models.TakeRunMessagesResponse
import homer.app.models.TakenRunMessage
import homer.app.services.ArtifactTooLargeException
import homer.app.services.HookEvent
import homer.app.services.JoinWatch
import homer.app.services.ProcessManager
import homer.app.services.QuestionsService
import homer.app.services.RESULT_TRANSFORM_MAX_BYTES
import homer.app.services.RunMessageHookOutput
import homer.app.toResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("homer.app.routes.CallbackRoutes")

/**
 * Question callback routes for running commands, authenticated by the
 * per-process `HMR_CALLBACK_TOKEN` — never by operator credentials.
 *
 * Mounted OUTSIDE `authenticate(...)` on purpose, next to the other
 * unsecured routes: this is a structural trust boundary (ADR-0019).
 * Operator auth schemes can never satisfy these routes and the callback
 * token can never satisfy operator routes, regardless of how either is
 * configured. Token extraction + constant-time resolution happen here at
 * the route level.
 */
internal fun Route.callbackRoutes(deps: ApiDeps) {
    callbackQuestionRoutes(deps)
    callbackTagRoutes(deps)
    callbackDispatchRoutes(deps)
    callbackRunRoutes(deps)
    callbackJoinRoutes(deps)
    callbackResultRoutes(deps)
    callbackMessageRoutes(deps)
}

/** `POST /api/v1/callback/questions` and `GET …/{id}`: asking a question and polling its answer. */
private fun Route.callbackQuestionRoutes(deps: ApiDeps) {
    with(deps) {
        route("/api/v1/callback/questions") {
            // Ask a question: { text, options? } → { id }. 409 when the
            // process is terminal, 429 at the open-question cap.
            post {
                val processId = call.resolveCallbackProcess(questionsService) ?: return@post
                val request = call.receiveOr400<AskQuestionRequest>("Invalid JSON body: expected { text, options? }")
                val question = questionsService.ask(processId, request.text, request.options)
                call.respond(AskQuestionResponse(id = question.id))
            }

            // Check / long-poll an answer. waitSec=0 (default) returns the
            // current snapshot immediately; otherwise the request is held
            // until the question leaves OPEN or the (clamped) wait elapses —
            // the response can still be OPEN, and the caller just polls again
            // with the same id (waits are resumable).
            get("/{id}") {
                val processId = call.resolveCallbackProcess(questionsService) ?: return@get
                val id = call.pathParameter("id")
                val waitSecParam = call.request.queryParameters["waitSec"]
                val waitSec =
                    if (waitSecParam == null) {
                        0
                    } else {
                        waitSecParam.toIntOrNull()
                            ?: throw ValidationException("waitSec must be an integer", "waitSec")
                    }

                // Ownership check BEFORE waiting: a foreign question id must
                // 404 immediately, not after holding the poll.
                val question = questionsService.get(id)
                if (question == null || question.processId != processId) {
                    throw QuestionNotFoundException(id)
                }

                val result =
                    questionsService.awaitAnswer(id, waitSec)
                        ?: throw QuestionNotFoundException(id)
                call.respond(result.toResponse())
            }
        }
    }
}

/** `POST /api/v1/callback/tags`: a run declaring one runtime subject tag. */
private fun Route.callbackTagRoutes(deps: ApiDeps) {
    with(deps) {
        route("/api/v1/callback/tags") {
            // Declare one `key:value` subject tag against the calling run. The run is
            // identified by the callback token alone — a process id in the body is
            // ignored (ADR-0019). 409 when the run is terminal/closed; 400 for an
            // undeclared key, a malformed value, or the per-run tag ceiling. See ADR-0060.
            post {
                val processId = call.resolveCallbackProcess(questionsService) ?: return@post
                // A terminal/closed run can no longer describe its subject; mirror the
                // 409 the questions route gives a closed process.
                val registration = questionsService.registrationFor(processId)
                if (registration == null || registration.closed) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse(msg = "Process $processId is no longer accepting tags"),
                    )
                    return@post
                }
                val request = call.receiveOr400<DeclareTagRequest>("Invalid JSON body: expected { tag }")
                // Fail closed if the run's agent definition was unloaded underneath us:
                // without it there is no runtime-key allowlist to authorize against.
                val agent = agentsService.agents[registration.agentName]
                if (agent == null) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse(msg = "Agent '${registration.agentName}' is no longer loaded"),
                    )
                    return@post
                }
                val (key, value) = runtimeTag(request.tag, agent)
                // [sensitive_data_exposure / CWE-200] A run tag is readable by every principal
                // with a read grant, so a value carrying any of this run's secrets (secret
                // inputs, @SYSENV credentials, its own callback token) is refused — the same
                // substring screen the artifact-stem check applies. Never log the value.
                // See ADR-0060.
                refuseSecretTagValue(registration, processId, key, value)
                val result =
                    processManager.addRuntimeTag(
                        processId,
                        "$key:$value",
                        TagValidation.MAX_RUNTIME_TAGS_PER_RUN,
                        agent.effectiveFromInputTags.toSet(),
                    )
                if (!result.written) {
                    throw QueryParamValidationException(
                        "Run already has the maximum of ${TagValidation.MAX_RUNTIME_TAGS_PER_RUN} runtime tags",
                    )
                }
                call.respond(DeclareTagResponse(tag = "$key:$value", count = result.count))
            }
        }
    }
}

/** `POST /api/v1/callback/dispatches`: a question that dispatches an agent once answered. */
private fun Route.callbackDispatchRoutes(deps: ApiDeps) {
    with(deps) {
        route("/api/v1/callback/dispatches") {
            post {
                val processId = call.resolveCallbackProcess(questionsService) ?: return@post
                val request =
                    call.receiveOr400<CreateDispatchRequest>(
                        "Invalid JSON body: expected { text, agent, options?, answerParam?, params?, ttlSec? }",
                    )
                val result =
                    dispatchService.create(
                        originProcessId = processId,
                        text = request.text,
                        options = request.options,
                        agent = request.agent,
                        answerParam = request.answerParam,
                        params = request.params,
                        ttlSec = request.ttlSec,
                    )
                call.respond(CreateDispatchResponse(result.dispatchId, result.questionId))
            }
        }
    }
}

/** `POST /api/v1/callback/runs`: starting an agent at once, optionally with a completion continuation. */
private fun Route.callbackRunRoutes(deps: ApiDeps) {
    with(deps) {
        route("/api/v1/callback/runs") {
            // Fire-and-forget: start an opt-in target agent immediately, no
            // operator question. Fresh chain; the asking process is recorded
            // only as the referer (ADR-0022). When `continueAgent` is present,
            // also register a completion continuation that resumes the parent
            // once the started sub reaches a terminal state (ADR-0023).
            post {
                val processId = call.resolveCallbackProcess(questionsService) ?: return@post
                val request =
                    call.receiveOr400<CreateRunRequest>(
                        "Invalid JSON body: expected { agent, params?, continueAgent?, continueThread?, " +
                            "continueParams?, resumeParam?, resultJsonPath?, ttlSec? }",
                    )
                val continueAgent = request.continueAgent
                // `params` go to the sub (dispatchNow); `continueParams` (+ the thread id) go to the
                // continuation run — the resumed parent. The LangGraph interrupt id rides here so it
                // reaches the parent, not the useless sub. See ADR-0023.
                val continuationParams = continuationRunParams(request.continueThread, request.continueParams)
                // Validate ALL continuation inputs BEFORE starting the sub: a 4xx here must not leave
                // an untracked sub-process running (no processId in the error body). See ADR-0023.
                if (continueAgent != null) {
                    continuationService.validate(
                        originProcessId = processId,
                        target = continueAgent,
                        resumeParam = request.resumeParam,
                        params = continuationParams,
                        ttlSec = request.ttlSec,
                    )
                }
                if (continueAgent == null) {
                    call.respond(CreateRunResponse(dispatchService.dispatchNow(processId, request.agent, request.params)))
                    return@post
                }
                val resultTransformer = request.resultJsonPath?.let { path -> jsonPathResult(path, request.resumeParam) }
                // What only surfaces after the start (the pending-continuation cap) kills the sub.
                val (dispatchedProcessId, continuationId) =
                    processManager.killStartedOnFailure { started ->
                        val subId = dispatchService.dispatchNow(processId, request.agent, request.params).also { started += it }
                        subId to
                            continuationService.create(
                                originProcessId = processId,
                                watchedProcessId = subId,
                                target = continueAgent,
                                params = continuationParams,
                                resumeParam = request.resumeParam,
                                resultTransformer = resultTransformer,
                                ttlSec = request.ttlSec,
                            )
                    }
                call.respond(CreateRunResponse(dispatchedProcessId, continuationId))
            }
        }
    }
}

/** `POST /api/v1/callback/joins`: a parallel fan-out resumed by one join continuation. */
private fun Route.callbackJoinRoutes(deps: ApiDeps) {
    with(deps) {
        route("/api/v1/callback/joins") {
            // Parallel fan-out: start N subs at once and register ONE join continuation that resumes
            // the parent a single time after the last sub ends, carrying all results keyed by
            // interrupt id. Validate-before-start (origin live, continueAgent + every sub
            // dispatchable with valid params, resumeParam, ttl), then kill-on-failure for what
            // only surfaces after a start, so a 4xx can't orphan started subs. See ADR-0026.
            post {
                val processId = call.resolveCallbackProcess(questionsService) ?: return@post
                val request =
                    call.receiveOr400<CreateJoinRequest>(
                        "Invalid JSON body: expected { subs:[{ agent, params?, resultJsonPath?, interruptId }], " +
                            "continueAgent, continueThread?, continueParams?, resumeParam?, ttlSec? }",
                    )
                // `subs[*].params` go to each sub (dispatchNow); `continueParams` (+ the thread id, +
                // the comma-joined interrupt ids the runtime supplies as HMR_LG_INTERRUPT) go to the
                // resumed parent. See ADR-0026.
                val continuationParams = continuationRunParams(request.continueThread, request.continueParams)
                continuationService.validateJoin(
                    originProcessId = processId,
                    target = request.continueAgent,
                    subAgents = request.subs.map { it.agent },
                    resumeParam = request.resumeParam,
                    params = continuationParams,
                    ttlSec = request.ttlSec,
                )
                request.subs.forEach { dispatchService.validateDispatchNow(processId, it.agent, it.params) }
                // What only surfaces once subs start (cost cap, cooldown, capacity, the pending cap)
                // kills every sub this request already started.
                val (watches, continuationId) =
                    processManager.killStartedOnFailure { started ->
                        val watches =
                            request.subs.map { sub ->
                                val subProcessId = dispatchService.dispatchNow(processId, sub.agent, sub.params).also { started += it }
                                val transformer = sub.resultJsonPath?.let { path -> jsonPathResult(path, request.resumeParam) }
                                JoinWatch(watchedProcessId = subProcessId, interruptId = sub.interruptId, resultTransformer = transformer)
                            }
                        watches to
                            continuationService.createJoin(
                                originProcessId = processId,
                                watches = watches,
                                target = request.continueAgent,
                                params = continuationParams,
                                resumeParam = request.resumeParam,
                                ttlSec = request.ttlSec,
                            )
                    }
                call.respond(CreateJoinResponse(watches.map { it.watchedProcessId }, continuationId))
            }
        }
    }
}

/** `GET /api/v1/callback/results/{token}`: a continuation result read by reference. */
private fun Route.callbackResultRoutes(deps: ApiDeps) {
    with(deps) {
        route("/api/v1/callback/results") {
            // Read-by-reference fetch of a large continuation result. Triple-auth:
            // a valid HMR_CALLBACK_TOKEN proves a live Homer process is calling,
            // the capability token in the path scopes the read to one (watched
            // process, artifact), and the caller's agent must match the token's
            // audience (the continuation's target) so a leaked token is useless to
            // any other agent. The transformer, if any, is applied server-side at
            // fetch. Reusable within the token's TTL by any process running the
            // target agent (each resume segment is a fresh such process). See ADR-0025.
            get("/{token}") {
                val callerProcessId = call.resolveCallbackProcess(questionsService) ?: return@get
                val token = call.pathParameter("token")
                val record = resultTokenService.resolve(token)
                if (record == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(msg = "Invalid or expired result token"))
                    return@get
                }
                // Audience check: the caller must run the agent the token was minted for.
                // Fail closed when the caller's registration is gone (e.g. after a restart).
                val callerAgent = questionsService.registrationFor(callerProcessId)?.agentName
                if (callerAgent != record.consumerAgent) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(msg = "Result token not valid for this agent"))
                    return@get
                }
                val artifactPath =
                    artifactService.resolveArtifactPath(record.watchedProcessId, record.path).getOrElse {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = it.message ?: "Artifact not found"))
                        return@get
                    }
                if (!fileOperations.fileExists(artifactPath)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Artifact file not found"))
                    return@get
                }
                val size =
                    artifactService.checkArtifactSize(artifactPath).getOrElse { failure ->
                        respondArtifactSizeFailure(call, failure)
                        return@get
                    }

                val transformer = record.resultTransformer
                if (transformer == null) {
                    call.respondOutputStream(ContentType.Text.Plain, HttpStatusCode.OK) {
                        artifactService.streamArtifact(artifactPath, this)
                    }
                    return@get
                }
                respondTransformedResult(call, artifactPath, size, transformer)
            }
        }
    }
}

/** `POST /api/v1/callback/messages/take`: a run taking the operator messages queued for it. */
private fun Route.callbackMessageRoutes(deps: ApiDeps) {
    with(deps) {
        // Takes every QUEUED message of the token's own run, never another's (a child run has its own
        // token), and marks them DELIVERED. `?hook=` answers as Claude Code hook JSON, or 204 when
        // nothing was queued. Never long-polls: hooks must stay fast. See ADR-0097.
        post("/api/v1/callback/messages/take") {
            val processId = call.resolveCallbackProcess(questionsService) ?: return@post
            val hook =
                call.request.queryParameters["hook"]?.let { param ->
                    HookEvent.fromParam(param)
                        ?: throw QueryParamValidationException("hook must be one of: ${HookEvent.entries.joinToString { it.param }}")
                }
            val registration = questionsService.registrationFor(processId)
            if (registration == null || registration.closed) throw RunNotAcceptingMessagesException(processId)
            val messages = runMessagesService.take(processId)
            when {
                hook == null -> call.respond(TakeRunMessagesResponse(messages.map { TakenRunMessage(it.id, it.text, it.createdAt) }))
                messages.isEmpty() -> call.respond(HttpStatusCode.NoContent)
                else -> call.respond(RunMessageHookOutput.render(hook, messages))
            }
        }
    }
}

/**
 * The params of a continuation run, the resumed parent: [continueParams] plus, when given, the
 * LangGraph [continueThread] the parent resumes. See ADR-0023.
 */
private fun continuationRunParams(
    continueThread: String?,
    continueParams: Map<String, String>,
): Map<String, String> =
    buildMap {
        continueThread?.let { put(EnvConstants.LG_THREAD, it) }
        putAll(continueParams)
    }

/** The transformer reading a sub's result into [envName]: the JsonPath [path] applied to its stdout. */
private fun jsonPathResult(
    path: String,
    envName: String,
): ResultEnvParam =
    ResultEnvParam(
        src = SrcType.STD_OUT,
        envName = envName,
        transformer = ResultTransformer.JsonPath(path),
    )

/** Refuses with a `400` a runtime tag [value] carrying any secret of the run's [registration]; logs the key, never the value. */
private fun refuseSecretTagValue(
    registration: QuestionsService.Registration,
    processId: Int,
    key: String,
    value: String,
) {
    if (registration.secretRedactor.redact(value) != value) {
        logger.warn(
            "Process {} (agent '{}'): runtime tag with key '{}' refused — value contains a secret",
            processId,
            registration.agentName,
            key,
        )
        throw QueryParamValidationException("Tag value must not contain a secret value")
    }
}

/**
 * Answers a result fetch with [transformer] applied to the artifact at [artifactPath], [size]
 * bytes long; a transform that matches nothing yields an empty body.
 */
private suspend fun respondTransformedResult(
    call: ApplicationCall,
    artifactPath: Path,
    size: Long,
    transformer: ResultEnvParam,
) {
    // A JsonPath/regex transform parses the whole file in memory; cap it well below
    // the raw-stream cap so a large-but-under-100-MiB artifact can't OOM the server.
    if (size > RESULT_TRANSFORM_MAX_BYTES) {
        call.respond(
            HttpStatusCode.PayloadTooLarge,
            ErrorResponse(msg = "Result too large to transform ($size > $RESULT_TRANSFORM_MAX_BYTES); fetch is raw-only"),
        )
        return
    }
    val value =
        runCatching {
            ResultToEnvMapper(CommandResult(0, artifactPath, artifactPath))
                .mapToEnv(listOf(transformer))[transformer.envName]
        }.getOrNull()
    call.respondText(value ?: "", ContentType.Text.Plain)
}

/**
 * The key and value of the runtime tag [tag] a run of [agent] declares: a 400 for anything but
 * `key:value` with a declared `tags.runtime` key and a value matching [TagValidation.VALUE_PATTERN].
 */
internal fun runtimeTag(
    tag: String,
    agent: Agent,
): Pair<String, String> {
    val separator = tag.indexOf(':')
    if (separator <= 0 || separator == tag.length - 1) {
        throw QueryParamValidationException("Tag must be 'key:value'")
    }
    val key = tag.substring(0, separator)
    val value = tag.substring(separator + 1)
    if (key !in agent.tags.runtime) {
        val why =
            if (key in agent.effectiveFromInputTags) {
                "key '$key' is a tags.fromInputs key; runtime tags cannot override it"
            } else {
                "key '$key' is not a declared tags.runtime key"
            }
        throw QueryParamValidationException(why)
    }
    if (!TagValidation.VALUE_PATTERN.matches(value)) {
        throw QueryParamValidationException("Tag value must match ^[A-Za-z0-9._-]{1,${TagValidation.MAX_TAG_VALUE_LENGTH}}\$")
    }
    return key to value
}

/**
 * Runs [block], which records every sub it starts in the list it is given. If [block] throws
 * (or the request is cancelled), each recorded sub is killed before the exception propagates,
 * so a failed request never leaves a sub running that no continuation tracks. See ADR-0026.
 */
@Suppress("SuspendFunSwallowedCancellation") // kills the started subs under NonCancellable and rethrows
private suspend fun <T> ProcessManager.killStartedOnFailure(block: suspend (started: MutableList<Int>) -> T): T {
    val started = mutableListOf<Int>()
    try {
        return block(started)
    } catch (e: Throwable) {
        withContext(NonCancellable) {
            started.forEach { subId ->
                try {
                    killProcess(subId, "Never registered: the callback request that started it failed")
                } catch (killError: Exception) {
                    logger.warn("Failed to kill unregistered sub-process {}: {}", subId, killError.message)
                }
            }
        }
        throw e
    }
}

/**
 * Extracts the Bearer callback token and resolves it to a process id.
 * Responds 401 and returns null when the token is missing or unknown
 * (a restart wipes the registry, so helpers blocked across one land here).
 */
private suspend fun ApplicationCall.resolveCallbackProcess(questionsService: QuestionsService): Int? {
    val token =
        request.headers[HttpHeaders.Authorization]
            ?.let { if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7).trim() else null }
    if (token.isNullOrEmpty()) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse(msg = "Missing callback token"))
        return null
    }
    val processId = questionsService.resolveToken(token)
    if (processId == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse(msg = "Invalid callback token"))
        return null
    }
    return processId
}
