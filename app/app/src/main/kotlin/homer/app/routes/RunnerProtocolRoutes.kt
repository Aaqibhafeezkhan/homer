package homer.app.routes

import homer.app.exceptions.HomerException
import homer.app.exceptions.QueryParamValidationException
import homer.app.exceptions.RunnerTransferTimeoutException
import homer.app.exceptions.RunnerTransferTooLargeException
import homer.app.exceptions.TooManyRunnerPollsException
import homer.app.exceptions.TooManyRunnerTransfersException
import homer.app.exceptions.TooManySessionPollsException
import homer.app.exceptions.ValidationException
import homer.app.infra.matchesTokenConstantTime
import homer.app.models.ErrorResponse
import homer.app.models.RunnerFilter
import homer.app.runners.BoundedInputStream
import homer.app.runners.RunnerSession
import homer.app.runners.TarStreams
import homer.app.runners.external.ClaimResponse
import homer.app.runners.external.ExternalRunnerDomain
import homer.app.runners.external.ExternalRunnerException
import homer.app.runners.external.ExternalRunnerInstance
import homer.app.runners.external.ExternalRunnerSession
import homer.app.runners.external.NextRequest
import homer.app.runners.external.RegisterInstanceRequest
import homer.app.runners.external.RegisterInstanceResponse
import homer.app.runners.external.RunnerOp
import homer.app.runners.external.RunnerProtocol
import homer.app.runners.external.runnerProtocolJson
import homer.app.runners.queue.ClaimRequest
import homer.app.runners.queue.RunnerClaimQueue
import homer.app.runners.queue.TagRoutedSession
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.jvm.javaio.toOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.isRegularFile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("homer.app.routes.RunnerProtocolRoutes")

/**
 * The runner-facing surface of pull-mode runners (ADR-0066). Authenticated by a
 * domain token, a principal class of its own: mounted OUTSIDE `authenticate(...)`
 * next to `callbackRoutes`, so operator credentials can never satisfy these
 * routes and a domain token can never satisfy operator routes. Instance and
 * session ids are resolved inside the caller's domain only; anything else is
 * `404`, hiding existence across domains.
 */
internal fun Route.runnerProtocolRoutes(
    domains: List<ExternalRunnerDomain>,
    queue: RunnerClaimQueue,
    pollTimeout: Duration = RunnerProtocol.POLL_SEC.seconds,
) {
    route("/api/v1/runner") {
        runnerInstanceRoutes(domains, queue, pollTimeout)
        runnerNextRoute(domains, pollTimeout)
        runnerUploadTarRoute(domains)
        runnerDownloadTarRoute(domains)
        runnerOutputRoute(domains)
    }
}

/** `POST /register`, `DELETE /instances/{id}` and `POST /instances/{id}/claim`: an instance's lifecycle and its claim poll. */
private fun Route.runnerInstanceRoutes(
    domains: List<ExternalRunnerDomain>,
    queue: RunnerClaimQueue,
    pollTimeout: Duration,
) {
    post("/register") {
        val domain = call.resolveRunnerDomain(domains) ?: return@post
        val request =
            call.receiveJsonOr400(RegisterInstanceRequest.serializer(), "{ name?, tags?, concurrency?, version? }")
        val instance = domain.register(request)
        call.respond(
            HttpStatusCode.Created,
            RegisterInstanceResponse(instance.id, RunnerProtocol.POLL_SEC, domain.config.heartbeatTimeoutSec),
        )
    }

    delete("/instances/{id}") {
        val domain = call.resolveRunnerDomain(domains) ?: return@delete
        if (!domain.deregister(call.pathParameter("id"))) return@delete call.respond(HttpStatusCode.NotFound)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/instances/{id}/claim") {
        val domain = call.resolveRunnerDomain(domains) ?: return@post
        val instance = domain.instance(call.pathParameter("id")) ?: return@post call.respond(HttpStatusCode.NotFound)
        domain.touch(instance)
        if (instance.draining) return@post call.respond(HttpStatusCode.NoContent)
        if (!instance.tryAcquirePoll()) throw TooManyRunnerPollsException(instance.name, instance.declaredConcurrency)
        try {
            // The CAS claim inside take() has no suspension point after it, so a request it
            // took is always recorded here — even when the timeout fires at the same instant
            // and withTimeoutOrNull drops the block's return value.
            val taken = AtomicReference<ClaimRequest?>()
            withTimeoutOrNull(pollTimeout) { queue.take(instance).also { taken.set(it) } }
            val request = taken.get() ?: return@post call.respond(HttpStatusCode.NoContent)
            deliverClaim(call, domain, instance, request)
        } finally {
            instance.releasePoll()
        }
    }
}

/** `POST /sessions/{sid}/next`: a session's op poll, carrying the result of the op it completed. */
private fun Route.runnerNextRoute(
    domains: List<ExternalRunnerDomain>,
    pollTimeout: Duration,
) {
    post("/sessions/{sid}/next") {
        val domain = call.resolveRunnerDomain(domains) ?: return@post
        val session = call.resolveSession(domain) ?: return@post
        domain.touch(session.instance)
        val request = nextRequest(call.receiveText())
        // The result first, the bound second: a refused poll must never cost the run an
        // op result the instance already carried here (it would wait out its deadline).
        request.completed?.let { session.mailbox.complete(it) }
        if (!session.tryAcquireNextPoll()) {
            throw TooManySessionPollsException(session.instance.name, ExternalRunnerSession.MAX_NEXT_POLLS)
        }
        val op: RunnerOp? =
            try {
                session.mailbox.next(pollTimeout)
            } finally {
                session.releaseNextPoll()
            }
        if (op == null) return@post call.respond(HttpStatusCode.NoContent)
        call.respond(HttpStatusCode.OK, op)
    }
}

/** `GET /sessions/{sid}/ops/{opId}/tar`: the tar an upload op sends to the instance. */
@Suppress("SuspendFunSwallowedCancellation") // ends the transfer and rethrows
private fun Route.runnerUploadTarRoute(domains: List<ExternalRunnerDomain>) {
    get("/sessions/{sid}/ops/{opId}/tar") {
        val domain = call.resolveRunnerDomain(domains) ?: return@get
        val session = call.resolveSession(domain) ?: return@get
        domain.touch(session.instance)
        val opId = call.pathParameter("opId")
        val source = session.uploadSource(opId) ?: return@get call.respond(HttpStatusCode.NotFound)
        // A single file is packed under the remote name so the instance's extract matches it (TarStreams single-file check).
        val entryName = if (source.local.isRegularFile()) source.remoteRelative.substringAfterLast('/') else null
        // Taken before respond so a duplicate can still be answered 429, but released by the
        // producer: whether respond waits for the stream to end is up to the engine.
        val slot = session.tryBeginTransfer(opId) ?: throw TooManyRunnerTransfersException(session.instance.name)
        try {
            call.respondBytesWriter(ContentType("application", "gzip")) {
                val channel = this
                try {
                    domain.withTransferTimeout(release = { channel.cancel(it) }) {
                        withContext(Dispatchers.IO) {
                            TarStreams.pack(
                                channel.toOutputStream(),
                                source.local,
                                excludes = source.excludes,
                                singleFileEntryName = entryName,
                            )
                        }
                    }
                } catch (e: RunnerTransferTimeoutException) {
                    // The 200 is already sent, so nothing can answer this; the stream just ends
                    // short. Its gzip trailer is missing, so the instance's extract refuses it, and
                    // the run's await of this upload, started earlier under the same deadline, has
                    // already failed.
                    logger.warn("Runner '{}': upload tar to instance '{}' cut off: {}", domain.alias, session.instance.name, e.message)
                } finally {
                    session.endTransfer(opId, slot)
                }
            }
        } catch (e: Throwable) {
            // respond failed, possibly before the producer ever ran.
            session.endTransfer(opId, slot)
            throw e
        }
    }
}

/** `POST /sessions/{sid}/ops/{opId}/tar`: the tar a download op receives from the instance. */
private fun Route.runnerDownloadTarRoute(domains: List<ExternalRunnerDomain>) {
    post("/sessions/{sid}/ops/{opId}/tar") {
        val domain = call.resolveRunnerDomain(domains) ?: return@post
        val session = call.resolveSession(domain) ?: return@post
        domain.touch(session.instance)
        val opId = call.pathParameter("opId")
        val target = session.downloadTarget(opId) ?: return@post call.respond(HttpStatusCode.NotFound)
        val isDir = isDirTarKind(call.request.queryParameters["kind"])
        val limit = domain.config.maxTransferBytes
        try {
            target.materialized =
                session.oneTransfer(opId) {
                    val channel = call.receiveChannel()
                    val input = BoundedInputStream(channel.toInputStream(), limit)
                    domain.withTransferTimeout(release = { channel.cancel(it) }) {
                        withContext(Dispatchers.IO) {
                            TarStreams.extract(
                                input = input,
                                local = target.local,
                                mode = target.mode,
                                remoteIsDir = isDir,
                                fileName = target.remoteRelative.substringAfterLast('/'),
                                sourceLabel = "runner instance '${session.instance.name}'",
                                // The same ceiling on both sides of the decompressor: the compressed
                                // body is bounded above, the bytes it expands to are bounded here.
                                maxDecompressedBytes = limit,
                            )
                        }
                    }
                }
        } catch (e: SecurityException) {
            logger.warn(
                "Runner '{}': instance '{}' posted a tar that failed containment: {}",
                domain.alias,
                session.instance.name,
                e.message,
            )
            return@post call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(msg = "Tar refused: ${e.message}"))
        }
        call.respond(HttpStatusCode.Accepted)
    }
}

/** `POST /sessions/{sid}/ops/{opId}/output`: one chunk of a command's stdout or stderr. */
private fun Route.runnerOutputRoute(domains: List<ExternalRunnerDomain>) {
    post("/sessions/{sid}/ops/{opId}/output") {
        val domain = call.resolveRunnerDomain(domains) ?: return@post
        val session = call.resolveSession(domain) ?: return@post
        domain.touch(session.instance)
        val (stream, seq) = outputChunkPosition(call.request.queryParameters["stream"], call.request.queryParameters["seq"])
        val opId = call.pathParameter("opId")
        val sink = session.sink(opId) ?: return@post call.respond(HttpStatusCode.NotFound)
        val declared = call.request.contentLength()
        if (declared != null && declared > RunnerProtocol.MAX_OUTPUT_CHUNK_BYTES) {
            throw RunnerTransferTooLargeException(RunnerProtocol.MAX_OUTPUT_CHUNK_BYTES.toLong())
        }
        // Per stream, not per op: the reference runner pumps stdout and stderr concurrently.
        session.oneTransfer("$opId/$stream") {
            val channel = call.receiveChannel()
            domain.withTransferTimeout(release = { channel.cancel(it) }) {
                withContext(Dispatchers.IO) {
                    val bytes =
                        BoundedInputStream(
                            channel.toInputStream(),
                            RunnerProtocol.MAX_OUTPUT_CHUNK_BYTES.toLong(),
                        ).use { it.readBytes() }
                    sink.append(stream, seq, bytes)
                }
            }
        }
        call.respond(HttpStatusCode.Accepted)
    }
}

/**
 * Answers the claim poll with the claimed [request]: completes it with a session opened on [instance],
 * or, when the run stopped waiting, as if nothing matched. A failure before it is answered fails the
 * request instead of leaving the run parked for its full claim TTL.
 */
@Suppress("SuspendFunSwallowedCancellation") // fails the claim under NonCancellable and rethrows
private suspend fun deliverClaim(
    call: ApplicationCall,
    domain: ExternalRunnerDomain,
    instance: ExternalRunnerInstance,
    request: ClaimRequest,
) {
    // Once take() hands us `request` it is CLAIMED and off the deque: nobody else
    // will ever complete or fail it. `delivered` tracks whether we reached a point
    // where the request was answered one way or another, so a failure before that
    // (openSession throwing, or this coroutine dying) fails the request instead of
    // leaving the waiting run parked for its full claim TTL.
    var delivered = false
    var session: ExternalRunnerSession? = null
    try {
        session = domain.openSession(instance, request.params)
        // A tag-filtered run learns its runner only now, so the domain env has to
        // reach it through the session, exactly as StaticRunnerConsumer does. An
        // alias-filtered run was already composed against this domain's env by
        // WorkspaceConfigFactory; layering it again here would put it ABOVE the
        // agent's own env and silently drop the agent's override. See ADR-0066.
        val deliveredSession: RunnerSession =
            if (request.filter is RunnerFilter.Tags && domain.runnerEnv.isNotEmpty()) {
                TagRoutedSession(session, domain.runnerEnv)
            } else {
                session
            }
        if (!request.complete(deliveredSession)) {
            delivered = true
            // The run stopped waiting between our claim and now: as if nothing matched.
            session.close()
            return call.respond(HttpStatusCode.NoContent)
        }
        delivered = true
        domain.touch(instance)
        logger.info("Runner '{}': instance '{}' claimed process {}", domain.alias, instance.name, request.params.processId)
        call.respond(
            HttpStatusCode.OK,
            ClaimResponse(
                sessionId = session.id,
                processId = request.params.processId,
                agentName = request.params.agentName,
                tags = (request.filter as? RunnerFilter.Tags)?.tags?.sorted() ?: emptyList(),
            ),
        )
    } catch (e: Throwable) {
        withContext(NonCancellable) {
            if (!delivered) {
                session?.close()
                request.fail(
                    ExternalRunnerException("Runner instance '${instance.name}' disconnected before the claim completed"),
                )
            }
        }
        throw e
    }
}

/** The `stream` and `seq` of an output chunk: a 400 unless `stdout` or `stderr` and an integer. */
internal fun outputChunkPosition(
    stream: String?,
    seq: String?,
): Pair<String, Long> {
    if (stream != RunnerProtocol.STREAM_STDOUT && stream != RunnerProtocol.STREAM_STDERR) {
        throw ValidationException("stream must be stdout or stderr", "stream")
    }
    val parsedSeq = seq?.toLongOrNull() ?: throw ValidationException("seq must be an integer", "seq")
    return stream to parsedSeq
}

/**
 * The body of a `next` poll: a blank one carries no result; a 400 unless `{ completed? }`.
 * Decoded with [runnerProtocolJson], so a field an instance newer than this server sends is
 * ignored rather than costing the run the op result it carried here.
 */
internal fun nextRequest(body: String): NextRequest {
    if (body.isBlank()) return NextRequest()
    return try {
        runnerProtocolJson.decodeFromString(NextRequest.serializer(), body)
    } catch (_: SerializationException) {
        throw ValidationException("Invalid JSON body: expected { completed? }")
    } catch (_: IllegalArgumentException) {
        throw ValidationException("Invalid JSON body: expected { completed? }")
    }
}

/** Whether a posted tar's `kind` names a directory: `dir` (the default) or `file`, else a 400. */
internal fun isDirTarKind(kind: String?): Boolean =
    when (kind ?: "dir") {
        "dir" -> true
        "file" -> false
        else -> throw ValidationException("kind must be dir or file", "kind")
    }

/**
 * Bounds one transfer handler in time. `transferTimeoutSec` is otherwise enforced
 * only in `ExternalRunnerSession.await`, and a bound living in another component
 * is not a bound (§12 rule 4): without this an instance that stops sending or
 * reading pins a `Dispatchers.IO` thread — the pool every SQLite transaction
 * uses — and its connection for as long as it likes.
 *
 * `withTimeout` alone cannot enforce it: the copy blocks that thread on the peer's
 * socket and never observes cancellation. At the deadline a watchdog calls
 * [release], which cancels the call's byte channel and fails the blocked read or
 * write — the ADR-0064 shape. CWE-400.
 */
@Suppress("SuspendFunSwallowedCancellation") // rethrows the caller's cancellation; maps only the cancelled channel
internal suspend fun <T> ExternalRunnerDomain.withTransferTimeout(
    release: (Throwable) -> Unit,
    block: suspend () -> T,
): T {
    val timedOut = AtomicBoolean(false)
    try {
        return coroutineScope {
            // Dispatchers.Default: the IO pool is exactly what a stalled peer saturates.
            val watchdog =
                launch(Dispatchers.Default) {
                    delay(config.transferTimeoutSec.seconds)
                    timedOut.set(true)
                    release(RunnerTransferTimeoutException(alias, config.transferTimeoutSec))
                }
            try {
                block()
            } finally {
                watchdog.cancel()
            }
        }
    } catch (e: SecurityException) {
        // Only the cancelled channel's failure becomes the timeout: a refusal the transfer made
        // itself (422 containment, 413 size) keeps its own status even past the deadline.
        throw e
    } catch (e: HomerException) {
        throw e
    } catch (e: Throwable) {
        // The caller's own cancellation (disconnect, shutdown) stays a cancellation past the deadline.
        if (e is CancellationException) currentCoroutineContext().ensureActive()
        if (timedOut.get()) throw RunnerTransferTimeoutException(alias, config.transferTimeoutSec)
        throw e
    }
}

/**
 * Serves [block] as the one in-flight request for [key], shedding a concurrent duplicate
 * with `429` before its body is read (see [ExternalRunnerSession.tryBeginTransfer]).
 * CWE-400.
 */
private suspend fun <T> ExternalRunnerSession.oneTransfer(
    key: String,
    block: suspend () -> T,
): T {
    val slot = tryBeginTransfer(key) ?: throw TooManyRunnerTransfersException(instance.name)
    try {
        return block()
    } finally {
        endTransfer(key, slot)
    }
}

/** The bearer credential a runner request presents, or null when absent or blank. */
internal fun ApplicationRequest.runnerBearerToken(): String? =
    headers[HttpHeaders.Authorization]
        ?.let { if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7).trim() else null }
        ?.takeIf { it.isNotEmpty() }

/**
 * Constant-time match of [token] against every configured domain token. The only
 * such matcher: the request-body limit is resolved before this route authenticates
 * and has to reach the same verdict, and a second copy of a credential comparison
 * is new surface of its own (§12).
 */
internal fun <T> matchRunnerDomain(
    domains: List<T>,
    token: String?,
    tokenOf: (T) -> String,
): T? {
    if (token.isNullOrEmpty()) return null
    return domains.firstOrNull { matchesTokenConstantTime(tokenOf(it), token) }
}

/**
 * Domain-token authentication: constant-time match against every configured
 * domain token; anything else is `401`. Never logs the credential.
 */
internal suspend fun ApplicationCall.resolveRunnerDomain(domains: List<ExternalRunnerDomain>): ExternalRunnerDomain? {
    val token = request.runnerBearerToken()
    if (token == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse(msg = "Missing runner token"))
        return null
    }
    val domain = matchRunnerDomain(domains, token) { it.config.token }
    if (domain == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse(msg = "Invalid runner token"))
        return null
    }
    return domain
}

internal suspend fun ApplicationCall.resolveSession(domain: ExternalRunnerDomain): ExternalRunnerSession? {
    val session = domain.session(pathParameter("sid"))
    if (session == null) respond(HttpStatusCode.NotFound)
    return session
}

// SerializationException is an IllegalArgumentException, so one catch covers both.
private suspend fun <T> ApplicationCall.receiveJsonOr400(
    serializer: kotlinx.serialization.KSerializer<T>,
    shape: String,
): T =
    try {
        runnerProtocolJson.decodeFromString(serializer, receiveText())
    } catch (_: IllegalArgumentException) {
        throw QueryParamValidationException("Invalid JSON body: expected $shape")
    }
