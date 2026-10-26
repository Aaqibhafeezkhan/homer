package homer.app.routes

import homer.app.EnvConstants
import homer.app.apiJson
import homer.app.configureStatusPages
import homer.app.exceptions.RunnerTransferTimeoutException
import homer.app.exceptions.RunnerTransferTooLargeException
import homer.app.infra.WorkspaceConfig
import homer.app.models.RunnerConfig
import homer.app.models.RunnerFilter
import homer.app.runners.DownloadMode
import homer.app.runners.RunnerSessionParams
import homer.app.runners.external.ClaimResponse
import homer.app.runners.external.ExternalRunnerDomain
import homer.app.runners.external.ExternalRunnerException
import homer.app.runners.external.ExternalRunnerSession
import homer.app.runners.external.NextRequest
import homer.app.runners.external.OpResult
import homer.app.runners.external.RegisterInstanceResponse
import homer.app.runners.external.RunnerOp
import homer.app.runners.external.RunnerProtocol
import homer.app.runners.queue.RunnerClaimQueue
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.writeByteArray
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.opentelemetry.context.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RunnerProtocolRoutesTest {
    @TempDir
    lateinit var tmp: Path

    private val queue = RunnerClaimQueue(maxPending = 10)
    private val workers =
        ExternalRunnerDomain(
            "workers",
            RunnerConfig.External(token = "workers-token", allowedTags = listOf("linux", "gpu"), maxInstances = 2),
            queue,
        )
    private val other = ExternalRunnerDomain("other", RunnerConfig.External(token = "other-token"), queue)
    private val greeter =
        ExternalRunnerDomain(
            "greeter",
            RunnerConfig.External(token = "greeter-token", env = mapOf("GREETING" to "from-the-domain")),
            queue,
        )

    private fun ApplicationTestBuilder.mount() {
        application {
            configureStatusPages()
            routing {
                install(ContentNegotiation) { json(apiJson) }
                runnerProtocolRoutes(listOf(workers, other, greeter), queue, pollTimeout = 200.milliseconds)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.register(
        token: String = "workers-token",
        body: String = """{"name":"host-1","tags":["linux","gpu"],"concurrency":1}""",
    ): HttpResponse =
        client.post("/api/v1/runner/register") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.registered(): RegisterInstanceResponse =
        apiJson.decodeFromString(
            RegisterInstanceResponse.serializer(),
            register()
                .also {
                    assertEquals(HttpStatusCode.Created, it.status)
                }.bodyAsText(),
        )

    private fun params(processId: Int = 7): RunnerSessionParams {
        val root = tmp.resolve("runs/$processId").createDirectories()
        val ws =
            mockk<WorkspaceConfig> {
                every { projectRoot } returns root
                every { maxOutputBytes } returns 1024
            }
        return RunnerSessionParams(processId, "gpu-job", ws, mapOf(EnvConstants.WORK_DIR to "$root/workdir"), Context.root())
    }

    @Test
    fun `register answers 201 with the poll and heartbeat facts`() =
        testApplication {
            mount()
            val r = registered()
            assertEquals(32, r.instanceId.length)
            assertEquals(30, r.pollSec)
            assertEquals(60, r.heartbeatTimeoutSec)
            assertEquals(setOf("linux", "gpu"), workers.instance(r.instanceId)!!.tags)
        }

    @Test
    fun `a missing or unknown bearer is 401 on every runner route`() =
        testApplication {
            mount()
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/runner/register").status)
            assertEquals(HttpStatusCode.Unauthorized, register(token = "not-a-domain-token").status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                client
                    .post("/api/v1/runner/instances/abc/claim") {
                        header(HttpHeaders.Authorization, "Bearer ops-admin-token")
                    }.status,
            )
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/runner/sessions/abc/next").status)
        }

    @Test
    fun `a register body from a newer instance is accepted, not a 400`() =
        testApplication {
            mount()
            // The instance and the server are upgraded independently: a key this binary does not
            // know must not refuse the registration the instance carried here (ADR-0066).
            val r = register(body = """{"name":"host-1","futureField":true}""")
            assertEquals(HttpStatusCode.Created, r.status)
        }

    @Test
    fun `register validates tags and concurrency with 422 and the instance bound with 429`() =
        testApplication {
            mount()
            assertEquals(HttpStatusCode.UnprocessableEntity, register(body = """{"tags":["windows"]}""").status)
            assertEquals(HttpStatusCode.UnprocessableEntity, register(body = """{"tags":["bad tag"]}""").status)
            assertEquals(HttpStatusCode.UnprocessableEntity, register(body = """{"concurrency":17}""").status)
            assertEquals(HttpStatusCode.UnprocessableEntity, register(body = """{"name":"bad name"}""").status)
            assertEquals(HttpStatusCode.BadRequest, register(body = """{"tags": "not-a-list"}""").status)
            registered()
            registered()
            assertEquals(HttpStatusCode.TooManyRequests, register().status)
        }

    @Test
    fun `deregister answers 204 once and 404 afterwards, a foreign domain never sees the id`() =
        testApplication {
            mount()
            val id = registered().instanceId
            assertEquals(
                HttpStatusCode.NotFound,
                client
                    .delete("/api/v1/runner/instances/$id") {
                        header(HttpHeaders.Authorization, "Bearer other-token")
                    }.status,
            )
            assertEquals(
                HttpStatusCode.NoContent,
                client
                    .delete("/api/v1/runner/instances/$id") {
                        header(HttpHeaders.Authorization, "Bearer workers-token")
                    }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client
                    .delete("/api/v1/runner/instances/$id") {
                        header(HttpHeaders.Authorization, "Bearer workers-token")
                    }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client
                    .post("/api/v1/runner/instances/$id/claim") {
                        header(HttpHeaders.Authorization, "Bearer workers-token")
                    }.status,
            )
        }

    @Test
    fun `claim answers 204 when nothing matches within the poll and 200 with a session when a run waits`() =
        testApplication {
            mount()
            val id = registered().instanceId
            val idle = client.post("/api/v1/runner/instances/$id/claim") { header(HttpHeaders.Authorization, "Bearer workers-token") }
            assertEquals(HttpStatusCode.NoContent, idle.status)

            val p = params()
            coroutineScope {
                val run = async(Dispatchers.Default) { queue.awaitClaim(p, RunnerFilter.Tags(setOf("gpu")), 30.seconds) }
                delay(100)
                val claimed =
                    client.post(
                        "/api/v1/runner/instances/$id/claim",
                    ) { header(HttpHeaders.Authorization, "Bearer workers-token") }
                assertEquals(HttpStatusCode.OK, claimed.status)
                val body = apiJson.decodeFromString(ClaimResponse.serializer(), claimed.bodyAsText())
                assertEquals(7, body.processId)
                assertEquals("gpu-job", body.agentName)
                assertEquals(listOf("gpu"), body.tags)
                val session = run.await() as ExternalRunnerSession
                assertEquals(body.sessionId, session.id)
                assertEquals(1, workers.instance(id)!!.inUse)
                session.close()
            }
        }

    @Test
    fun `a claimed session carries the domain env like a tag-routed static runner`() =
        testApplication {
            mount()
            val registration = register(token = "greeter-token")
            assertEquals(HttpStatusCode.Created, registration.status)
            val id = apiJson.decodeFromString(RegisterInstanceResponse.serializer(), registration.bodyAsText()).instanceId
            val p = params()
            coroutineScope {
                val run = async(Dispatchers.Default) { queue.awaitClaim(p, RunnerFilter.Tags(setOf("gpu")), 30.seconds) }
                delay(100)
                val claimed =
                    client.post(
                        "/api/v1/runner/instances/$id/claim",
                    ) { header(HttpHeaders.Authorization, "Bearer greeter-token") }
                assertEquals(HttpStatusCode.OK, claimed.status)
                val session = run.await()
                assertEquals("from-the-domain", session.env["GREETING"], "the domain env is layered into the delivered session")
                assertTrue(session !is ExternalRunnerSession, "a domain with env hands the run the layered wrapper, not the raw session")
                session.close()
            }
        }

    @Test
    fun `an alias-filtered claim is handed the raw session, so the domain env is not layered twice`() =
        testApplication {
            mount()
            val registration = register(token = "greeter-token")
            assertEquals(HttpStatusCode.Created, registration.status)
            val id = apiJson.decodeFromString(RegisterInstanceResponse.serializer(), registration.bodyAsText()).instanceId
            val p = params()
            coroutineScope {
                val run = async(Dispatchers.Default) { queue.awaitClaim(p, RunnerFilter.Alias("greeter"), 30.seconds) }
                delay(100)
                val claimed =
                    client.post(
                        "/api/v1/runner/instances/$id/claim",
                    ) { header(HttpHeaders.Authorization, "Bearer greeter-token") }
                assertEquals(HttpStatusCode.OK, claimed.status)
                val session = run.await()
                // WorkspaceConfigFactory already layered the domain env below the agent's own
                // env for an alias-filtered run; a wrapper here would re-apply it above the
                // agent env and drop the agent's override.
                assertTrue(session is ExternalRunnerSession, "an alias-filtered claim is delivered unwrapped")
                assertNull(session.env["GREETING"], "the domain env must not be layered a second time")
                session.close()
            }
        }

    @Test
    fun `an alias-filtered run for the domain is claimable and an unrelated alias is not`() =
        testApplication {
            mount()
            val id = registered().instanceId
            val p8 = params(8)
            val p9 = params(9)
            coroutineScope {
                val unrelated =
                    async(Dispatchers.Default) { runCatching { queue.awaitClaim(p8, RunnerFilter.Alias("build-host"), 1.seconds) } }
                val mine = async(Dispatchers.Default) { queue.awaitClaim(p9, RunnerFilter.Alias("workers"), 30.seconds) }
                delay(100)
                val claimed =
                    client.post(
                        "/api/v1/runner/instances/$id/claim",
                    ) { header(HttpHeaders.Authorization, "Bearer workers-token") }
                assertEquals(HttpStatusCode.OK, claimed.status)
                assertEquals(9, apiJson.decodeFromString(ClaimResponse.serializer(), claimed.bodyAsText()).processId)
                (mine.await() as ExternalRunnerSession).close()
                assertTrue(unrelated.await().isFailure, "no consumer serves 'build-host'")
            }
        }

    @Test
    fun `more concurrent claim polls than the declared concurrency is 429`() =
        testApplication {
            application {
                configureStatusPages()
                routing {
                    install(ContentNegotiation) { json(apiJson) }
                    runnerProtocolRoutes(listOf(workers), queue, pollTimeout = 2.seconds)
                }
            }
            val id = registered().instanceId
            coroutineScope {
                val first =
                    launch {
                        client.post(
                            "/api/v1/runner/instances/$id/claim",
                        ) { header(HttpHeaders.Authorization, "Bearer workers-token") }
                    }
                delay(200)
                val second = client.post("/api/v1/runner/instances/$id/claim") { header(HttpHeaders.Authorization, "Bearer workers-token") }
                assertEquals(HttpStatusCode.TooManyRequests, second.status)
                first.join()
            }
        }

    @Test
    fun `a further claim poll while the sole permit is already held by a live session is 429`() =
        testApplication {
            mount()
            val id = registered().instanceId
            val p = params()
            coroutineScope {
                val run = async(Dispatchers.Default) { queue.awaitClaim(p, RunnerFilter.Tags(setOf("gpu")), 30.seconds) }
                delay(100)
                val claimed =
                    client.post(
                        "/api/v1/runner/instances/$id/claim",
                    ) { header(HttpHeaders.Authorization, "Bearer workers-token") }
                assertEquals(HttpStatusCode.OK, claimed.status)
                val session = run.await() as ExternalRunnerSession
                assertEquals(1, workers.instance(id)!!.inUse)

                val again = client.post("/api/v1/runner/instances/$id/claim") { header(HttpHeaders.Authorization, "Bearer workers-token") }
                assertEquals(HttpStatusCode.TooManyRequests, again.status)

                session.close()
            }
        }

    @Test
    fun `a claim that fails to open a session fails the parked run instead of leaving it claimed`() =
        testApplication {
            val spiedWorkers = spyk(workers)
            every { spiedWorkers.openSession(any(), any()) } throws RuntimeException("boom")
            application {
                configureStatusPages()
                routing {
                    install(ContentNegotiation) { json(apiJson) }
                    runnerProtocolRoutes(listOf(spiedWorkers, other), queue, pollTimeout = 200.milliseconds)
                }
            }
            val id = registered().instanceId
            val p = params()
            coroutineScope {
                val run =
                    async(Dispatchers.Default) { runCatching { queue.awaitClaim(p, RunnerFilter.Tags(setOf("gpu")), 30.seconds) } }
                delay(100)
                val claimed =
                    client.post(
                        "/api/v1/runner/instances/$id/claim",
                    ) { header(HttpHeaders.Authorization, "Bearer workers-token") }
                assertEquals(HttpStatusCode.InternalServerError, claimed.status)

                val outcome = withTimeoutOrNull(1.seconds) { run.await() }
                assertNotNull(outcome, "the parked run must fail promptly instead of waiting out its 30s claim TTL")
                assertTrue(outcome.exceptionOrNull() is ExternalRunnerException, "$outcome")
            }
        }

    @Test
    fun `a claim after the run was cancelled answers 204`() =
        testApplication {
            mount()
            val id = registered().instanceId
            val p = params()
            coroutineScope {
                val run =
                    async(Dispatchers.Default) { runCatching { queue.awaitClaim(p, RunnerFilter.Tags(setOf("gpu")), 30.seconds) } }
                delay(100)
                run.cancel()
                run.join()
                val claimed =
                    client.post(
                        "/api/v1/runner/instances/$id/claim",
                    ) { header(HttpHeaders.Authorization, "Bearer workers-token") }
                assertEquals(HttpStatusCode.NoContent, claimed.status)
                assertEquals(0, workers.instance(id)!!.inUse)
            }
        }

    @Test
    fun `a request claimed just before its run gives up completes false, so the caller closes what it opened`() =
        testApplication {
            mount()
            val id = registered().instanceId
            val instance = workers.instance(id)!!
            val p = params()
            coroutineScope {
                val run =
                    async(Dispatchers.Default) { runCatching { queue.awaitClaim(p, RunnerFilter.Tags(setOf("gpu")), 30.seconds) } }
                delay(100)
                val req = queue.take(instance)
                run.cancel()
                run.join()

                val session = workers.openSession(instance, req.params)
                assertFalse(req.complete(session))
                session.close()
                assertEquals(0, instance.inUse)
                assertNull(workers.session(session.id)?.takeIf { it.closedAt == null })
            }
        }

    @Test
    fun `next delivers ops in order, accepts results and heartbeats the instance`() =
        testApplication {
            mount()
            val id = registered().instanceId
            val p = params()
            coroutineScope {
                val run = async(Dispatchers.Default) { queue.awaitClaim(p, RunnerFilter.Tags(setOf("gpu")), 30.seconds) }
                delay(100)
                val claimed =
                    apiJson.decodeFromString(
                        ClaimResponse.serializer(),
                        client
                            .post("/api/v1/runner/instances/$id/claim") {
                                header(HttpHeaders.Authorization, "Bearer workers-token")
                            }.bodyAsText(),
                    )
                val session = run.await() as ExternalRunnerSession
                val overrides = async(Dispatchers.Default) { session.createWorkspace() }
                delay(100)

                val next =
                    client.post(
                        "/api/v1/runner/sessions/${claimed.sessionId}/next",
                    ) { header(HttpHeaders.Authorization, "Bearer workers-token") }
                assertEquals(HttpStatusCode.OK, next.status)
                val op = apiJson.decodeFromString(RunnerOp.serializer(), next.bodyAsText())
                assertTrue(op is RunnerOp.CreateWorkspace, "$op")

                val before = workers.instance(id)!!.lastSeenAt
                delay(20)
                val afterResult =
                    client.post("/api/v1/runner/sessions/${claimed.sessionId}/next") {
                        header(HttpHeaders.Authorization, "Bearer workers-token")
                        contentType(ContentType.Application.Json)
                        // `future` stands for a field an instance newer than this server sends:
                        // the result it carried here must still reach the run (ADR-0066).
                        setBody("""{"completed":{"opId":"${op.opId}","root":"/r/7","future":1}}""")
                    }
                assertEquals(HttpStatusCode.NoContent, afterResult.status, "nothing else queued yet")
                assertEquals("/r/7/workdir", overrides.await()[EnvConstants.WORK_DIR])
                assertTrue(workers.instance(id)!!.lastSeenAt >= before)

                assertEquals(
                    HttpStatusCode.NotFound,
                    client
                        .post("/api/v1/runner/sessions/${claimed.sessionId}/next") {
                            header(HttpHeaders.Authorization, "Bearer other-token")
                        }.status,
                )
                assertEquals(
                    HttpStatusCode.NotFound,
                    client
                        .post("/api/v1/runner/sessions/nope/next") {
                            header(HttpHeaders.Authorization, "Bearer workers-token")
                        }.status,
                )
                session.close()
                val close =
                    client.post(
                        "/api/v1/runner/sessions/${claimed.sessionId}/next",
                    ) { header(HttpHeaders.Authorization, "Bearer workers-token") }
                assertTrue(apiJson.decodeFromString(RunnerOp.serializer(), close.bodyAsText()) is RunnerOp.Close)
                assertFalse(workers.instance(id)!!.draining)
                assertNotNull(workers.instance(id))
            }
        }

    @Test
    fun `more concurrent next polls than the session allows is 429 while the others stay served`() =
        testApplication {
            application {
                configureStatusPages()
                routing {
                    install(ContentNegotiation) { json(apiJson) }
                    runnerProtocolRoutes(listOf(workers, other, greeter), queue, pollTimeout = 2.seconds)
                }
            }
            val (sid, session) = claimedSession()
            coroutineScope {
                val parked =
                    (1..ExternalRunnerSession.MAX_NEXT_POLLS).map {
                        async(Dispatchers.Default) {
                            client.post("/api/v1/runner/sessions/$sid/next") {
                                header(HttpHeaders.Authorization, "Bearer workers-token")
                            }
                        }
                    }
                delay(300)
                val refused =
                    client.post("/api/v1/runner/sessions/$sid/next") { header(HttpHeaders.Authorization, "Bearer workers-token") }
                assertEquals(HttpStatusCode.TooManyRequests, refused.status)
                // The bound refuses the surplus poll only: the ones already parked still get
                // served, and the refused call never dropped a result (it carried none).
                session.mailbox.post(RunnerOp.Cancel("op-1"))
                assertTrue(parked.map { it.await() }.all { it.status == HttpStatusCode.OK || it.status == HttpStatusCode.NoContent })

                // Released in `finally`: once the parked polls answered, a poll is accepted again.
                assertEquals(
                    HttpStatusCode.NoContent,
                    client
                        .post("/api/v1/runner/sessions/$sid/next") {
                            header(HttpHeaders.Authorization, "Bearer workers-token")
                        }.status,
                )
            }
            session.close()
        }

    /** Registers, parks a run, claims it and hands back the session plus its id — the preamble every transfer test needs. */
    private suspend fun ApplicationTestBuilder.claimedSession(): Pair<String, ExternalRunnerSession> {
        val id = registered().instanceId
        // params() builds a mockk<WorkspaceConfig>; evaluated here on the test thread so
        // MockK's first-use ByteBuddy bootstrap never lands inside the async producer's
        // timed rendezvous with the claim call below (fix round 1, item 3).
        val p = params()
        return coroutineScope {
            val run = async(Dispatchers.Default) { queue.awaitClaim(p, RunnerFilter.Tags(setOf("gpu")), 30.seconds) }
            delay(100)
            val claimed =
                apiJson.decodeFromString(
                    ClaimResponse.serializer(),
                    client
                        .post("/api/v1/runner/instances/$id/claim") {
                            header(HttpHeaders.Authorization, "Bearer workers-token")
                        }.bodyAsText(),
                )
            claimed.sessionId to (run.await() as ExternalRunnerSession)
        }
    }

    private suspend fun ApplicationTestBuilder.nextOp(sid: String): RunnerOp =
        apiJson.decodeFromString(
            RunnerOp.serializer(),
            client
                .post("/api/v1/runner/sessions/$sid/next") {
                    header(HttpHeaders.Authorization, "Bearer workers-token")
                }.bodyAsText(),
        )

    private suspend fun ApplicationTestBuilder.complete(
        sid: String,
        result: OpResult,
    ): HttpResponse =
        client.post("/api/v1/runner/sessions/$sid/next") {
            header(HttpHeaders.Authorization, "Bearer workers-token")
            contentType(ContentType.Application.Json)
            setBody(apiJson.encodeToString(NextRequest.serializer(), NextRequest(result)))
        }

    private fun tarOf(dir: Path): ByteArray =
        java.io
            .ByteArrayOutputStream()
            .also {
                homer.app.runners.TarStreams
                    .pack(it, dir)
            }.toByteArray()

    @Test
    fun `an upload op streams the workspace tar to the instance`() =
        testApplication {
            mount()
            val (sid, session) = claimedSession()
            val src = tmp.resolve("src").createDirectories()
            src.resolve("hello.txt").writeText("hi")
            coroutineScope {
                val upload = async(Dispatchers.Default) { session.upload(src, ".") }
                delay(100)
                val op = nextOp(sid) as RunnerOp.Upload
                val tar =
                    client.get(
                        "/api/v1/runner/sessions/$sid/ops/${op.opId}/tar",
                    ) { header(HttpHeaders.Authorization, "Bearer workers-token") }
                assertEquals(HttpStatusCode.OK, tar.status)
                val extracted = tmp.resolve("extracted").createDirectories()
                homer.app.runners.TarStreams.extract(
                    tar.bodyAsChannel().toInputStream(),
                    extracted,
                    DownloadMode.OVERWRITE,
                    remoteIsDir = true,
                    fileName = ".",
                    sourceLabel = "test",
                    maxDecompressedBytes = homer.app.runners.TarStreams.DEFAULT_MAX_EXTRACTED_BYTES,
                )
                assertEquals("hi", extracted.resolve("hello.txt").readText())
                complete(sid, OpResult(op.opId))
                upload.await()
                assertEquals(
                    HttpStatusCode.NotFound,
                    client
                        .get("/api/v1/runner/sessions/$sid/ops/${op.opId}/tar") {
                            header(HttpHeaders.Authorization, "Bearer workers-token")
                        }.status,
                )
            }
            session.close()
        }

    @Test
    fun `a download op accepts the posted tar, extracts it under the target and prunes on MIRROR after the ok result`() =
        testApplication {
            mount()
            val (sid, session) = claimedSession()
            val local = tmp.resolve("workdir").createDirectories()
            local.resolve("stale").writeText("old")
            val remote = tmp.resolve("remote").createDirectories()
            remote.resolve("fresh").writeText("new")
            coroutineScope {
                val download = async(Dispatchers.Default) { session.download("workdir", local, DownloadMode.MIRROR) }
                delay(100)
                val op = nextOp(sid) as RunnerOp.Download
                val posted =
                    client.post("/api/v1/runner/sessions/$sid/ops/${op.opId}/tar?kind=dir") {
                        header(HttpHeaders.Authorization, "Bearer workers-token")
                        setBody(tarOf(remote))
                    }
                assertEquals(HttpStatusCode.Accepted, posted.status)
                assertEquals("new", local.resolve("fresh").readText())
                assertTrue(local.resolve("stale").exists(), "no prune before the instance confirms the download")
                complete(sid, OpResult(op.opId))
                download.await()
                assertFalse(local.resolve("stale").exists())
            }
            session.close()
        }

    @Test
    fun `a tar with an escaping entry is refused and the target is untouched`() =
        testApplication {
            mount()
            val (sid, session) = claimedSession()
            val local = tmp.resolve("artifacts").createDirectories()
            coroutineScope {
                val download = async(Dispatchers.Default) { runCatching { session.download("artifacts", local, DownloadMode.OVERLAY) } }
                delay(100)
                val op = nextOp(sid) as RunnerOp.Download
                val evil =
                    java.io
                        .ByteArrayOutputStream()
                        .also { bos ->
                            org.apache.commons.compress.archivers.tar
                                .TarArchiveOutputStream(
                                    org.apache.commons.compress.compressors.gzip
                                        .GzipCompressorOutputStream(bos),
                                ).use { tar ->
                                    val e =
                                        org.apache.commons.compress.archivers.tar
                                            .TarArchiveEntry("../escape.txt")
                                    e.size = 1
                                    tar.putArchiveEntry(e)
                                    tar.write("x".toByteArray())
                                    tar.closeArchiveEntry()
                                }
                        }.toByteArray()
                val posted =
                    client.post("/api/v1/runner/sessions/$sid/ops/${op.opId}/tar") {
                        header(HttpHeaders.Authorization, "Bearer workers-token")
                        setBody(evil)
                    }
                assertEquals(HttpStatusCode.UnprocessableEntity, posted.status)
                assertFalse(tmp.resolve("escape.txt").exists())
                complete(sid, OpResult(op.opId, ok = false, error = "refused"))
                assertTrue(download.await().isFailure)
            }
            session.close()
        }

    @Test
    fun `a tar past maxTransferBytes is 413`() =
        testApplication {
            // 16, not 64: the fixture tar compresses to ~109 bytes, so a bound anywhere near
            // that is one fixture or compression-library change away from a false green.
            val small = ExternalRunnerDomain("small", RunnerConfig.External(token = "small-token", maxTransferBytes = 16), queue)
            application {
                configureStatusPages()
                routing {
                    install(ContentNegotiation) { json(apiJson) }
                    runnerProtocolRoutes(listOf(small), queue, pollTimeout = 200.milliseconds)
                }
            }
            val id =
                apiJson
                    .decodeFromString(
                        RegisterInstanceResponse.serializer(),
                        register(token = "small-token", body = """{"tags":["gpu"]}""").bodyAsText(),
                    ).instanceId
            val p = params()
            coroutineScope {
                val run = async(Dispatchers.Default) { queue.awaitClaim(p, RunnerFilter.Alias("small"), 30.seconds) }
                delay(100)
                val sid =
                    apiJson
                        .decodeFromString(
                            ClaimResponse.serializer(),
                            client
                                .post("/api/v1/runner/instances/$id/claim") {
                                    header(HttpHeaders.Authorization, "Bearer small-token")
                                }.bodyAsText(),
                        ).sessionId
                val session = run.await() as ExternalRunnerSession
                val local = tmp.resolve("big").createDirectories()
                val download = async(Dispatchers.Default) { runCatching { session.download("artifacts", local, DownloadMode.OVERLAY) } }
                delay(100)
                val op =
                    apiJson.decodeFromString(
                        RunnerOp.serializer(),
                        client
                            .post("/api/v1/runner/sessions/$sid/next") {
                                header(HttpHeaders.Authorization, "Bearer small-token")
                            }.bodyAsText(),
                    )
                val big = tmp.resolve("bigsrc").createDirectories().also { it.resolve("blob").writeText("x".repeat(10_000)) }
                val posted =
                    client.post("/api/v1/runner/sessions/$sid/ops/${op.opId}/tar") {
                        header(HttpHeaders.Authorization, "Bearer small-token")
                        setBody(tarOf(big))
                    }
                assertEquals(HttpStatusCode.PayloadTooLarge, posted.status)
                session.close()
                download.cancel()
            }
        }

    @Test
    fun `a tar whose compressed body fits but decompresses past the bound is 413`() =
        testApplication {
            // 64 KiB: the posted body is ~2 KiB of gzip, well under the bound, and expands to
            // 2 MiB — the compressed bound alone would let the whole bomb land on disk.
            val bomb = ExternalRunnerDomain("bomb", RunnerConfig.External(token = "bomb-token", maxTransferBytes = 65_536), queue)
            application {
                configureStatusPages()
                routing {
                    install(ContentNegotiation) { json(apiJson) }
                    runnerProtocolRoutes(listOf(bomb), queue, pollTimeout = 200.milliseconds)
                }
            }
            val id =
                apiJson
                    .decodeFromString(
                        RegisterInstanceResponse.serializer(),
                        register(token = "bomb-token", body = """{"tags":["gpu"]}""").bodyAsText(),
                    ).instanceId
            val p = params()
            coroutineScope {
                val run = async(Dispatchers.Default) { queue.awaitClaim(p, RunnerFilter.Alias("bomb"), 30.seconds) }
                delay(100)
                val sid =
                    apiJson
                        .decodeFromString(
                            ClaimResponse.serializer(),
                            client
                                .post("/api/v1/runner/instances/$id/claim") {
                                    header(HttpHeaders.Authorization, "Bearer bomb-token")
                                }.bodyAsText(),
                        ).sessionId
                val session = run.await() as ExternalRunnerSession
                val local = tmp.resolve("bombed").createDirectories()
                val download = async(Dispatchers.Default) { runCatching { session.download("artifacts", local, DownloadMode.OVERLAY) } }
                delay(100)
                val op =
                    apiJson.decodeFromString(
                        RunnerOp.serializer(),
                        client
                            .post("/api/v1/runner/sessions/$sid/next") {
                                header(HttpHeaders.Authorization, "Bearer bomb-token")
                            }.bodyAsText(),
                    )
                val src = tmp.resolve("bombsrc").createDirectories().also { it.resolve("blob").writeText("0".repeat(2 * 1024 * 1024)) }
                val body = tarOf(src)
                assertTrue(body.size < 65_536, "the posted body must fit under maxTransferBytes, got ${body.size}")
                val posted =
                    client.post("/api/v1/runner/sessions/$sid/ops/${op.opId}/tar") {
                        header(HttpHeaders.Authorization, "Bearer bomb-token")
                        setBody(body)
                    }
                assertEquals(HttpStatusCode.PayloadTooLarge, posted.status)
                session.close()
                download.cancel()
            }
        }

    @Test
    fun `a tar the instance never finishes sending is refused at transferTimeoutSec`() =
        testApplication {
            val slow = ExternalRunnerDomain("slow", RunnerConfig.External(token = "slow-token", transferTimeoutSec = 1), queue)
            application {
                configureStatusPages()
                routing {
                    install(ContentNegotiation) { json(apiJson) }
                    runnerProtocolRoutes(listOf(slow), queue, pollTimeout = 200.milliseconds)
                }
            }
            val id =
                apiJson
                    .decodeFromString(
                        RegisterInstanceResponse.serializer(),
                        register(token = "slow-token", body = """{"tags":["gpu"]}""").bodyAsText(),
                    ).instanceId
            val p = params()
            coroutineScope {
                val run = async(Dispatchers.Default) { queue.awaitClaim(p, RunnerFilter.Alias("slow"), 30.seconds) }
                delay(100)
                val sid =
                    apiJson
                        .decodeFromString(
                            ClaimResponse.serializer(),
                            client
                                .post("/api/v1/runner/instances/$id/claim") {
                                    header(HttpHeaders.Authorization, "Bearer slow-token")
                                }.bodyAsText(),
                        ).sessionId
                val session = run.await() as ExternalRunnerSession
                val local = tmp.resolve("stalled").createDirectories()
                val download = async(Dispatchers.Default) { runCatching { session.download("artifacts", local, DownloadMode.OVERLAY) } }
                delay(100)
                val op =
                    apiJson.decodeFromString(
                        RunnerOp.serializer(),
                        client
                            .post("/api/v1/runner/sessions/$sid/next") {
                                header(HttpHeaders.Authorization, "Bearer slow-token")
                            }.bodyAsText(),
                    )
                // A perfectly valid tar, dribbled: it stays far under maxTransferBytes and the
                // rest arrives only after the answer, so the 408 cannot wait for the peer — a
                // deadline that only fires once the blocked read returns would hang here.
                val body = tarOf(tmp.resolve("stallsrc").createDirectories().also { it.resolve("f").writeText("x") })
                val answered = CompletableDeferred<Unit>()
                val stalling =
                    object : OutgoingContent.WriteChannelContent() {
                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            channel.writeByteArray(body.copyOfRange(0, 1))
                            channel.flush()
                            answered.await()
                            runCatching {
                                channel.writeByteArray(body.copyOfRange(1, body.size))
                                channel.flush()
                            }
                        }
                    }
                val posted =
                    withTimeoutOrNull(10.seconds) {
                        client.post("/api/v1/runner/sessions/$sid/ops/${op.opId}/tar") {
                            header(HttpHeaders.Authorization, "Bearer slow-token")
                            setBody(stalling)
                        }
                    }
                answered.complete(Unit)
                assertEquals(HttpStatusCode.RequestTimeout, posted?.status, "the deadline must not wait for the stalled peer")
                session.close()
                download.cancel()
            }
        }

    /** Mounts only [domain], registers one `gpu` instance with [token] and claims an alias-filtered run on it. */
    private suspend fun ApplicationTestBuilder.claimedSessionIn(
        domain: ExternalRunnerDomain,
        token: String,
    ): Pair<String, ExternalRunnerSession> {
        application {
            configureStatusPages()
            routing {
                install(ContentNegotiation) { json(apiJson) }
                runnerProtocolRoutes(listOf(domain), queue, pollTimeout = 200.milliseconds)
            }
        }
        val id =
            apiJson
                .decodeFromString(
                    RegisterInstanceResponse.serializer(),
                    register(token = token, body = """{"tags":["gpu"]}""").bodyAsText(),
                ).instanceId
        val p = params()
        return coroutineScope {
            val run = async(Dispatchers.Default) { queue.awaitClaim(p, RunnerFilter.Alias(domain.alias), 30.seconds) }
            delay(100)
            val sid =
                apiJson
                    .decodeFromString(
                        ClaimResponse.serializer(),
                        client
                            .post("/api/v1/runner/instances/$id/claim") {
                                header(HttpHeaders.Authorization, "Bearer $token")
                            }.bodyAsText(),
                    ).sessionId
            sid to (run.await() as ExternalRunnerSession)
        }
    }

    /** A directory holding one incompressible file far larger than any in-memory response buffer, so a fetch of it blocks on the reader. */
    private fun bigUploadSource(): Path =
        tmp.resolve("bigupload").createDirectories().also {
            it.resolve("blob").toFile().writeBytes(ByteArray(16 * 1024 * 1024).also { b -> java.util.Random(1).nextBytes(b) })
        }

    @Test
    fun `an upload fetch past transferTimeoutSec is cut off after its 200`() =
        testApplication {
            val slow = ExternalRunnerDomain("slow", RunnerConfig.External(token = "slow-token", transferTimeoutSec = 1), queue)
            val (sid, session) = claimedSessionIn(slow, "slow-token")
            val src = bigUploadSource()
            coroutineScope {
                val upload = async(Dispatchers.Default) { runCatching { session.upload(src, ".") } }
                delay(100)
                val op =
                    apiJson.decodeFromString(
                        RunnerOp.serializer(),
                        client
                            .post("/api/v1/runner/sessions/$sid/next") {
                                header(HttpHeaders.Authorization, "Bearer slow-token")
                            }.bodyAsText(),
                    ) as RunnerOp.Upload
                client
                    .prepareGet("/api/v1/runner/sessions/$sid/ops/${op.opId}/tar") {
                        header(HttpHeaders.Authorization, "Bearer slow-token")
                    }.execute { response ->
                        // The status is committed before the first byte of the stream, so the
                        // deadline cannot turn it into a 408: it can only cut the body short.
                        assertEquals(HttpStatusCode.OK, response.status)
                        delay(2.seconds)
                        val extracted = tmp.resolve("cut").createDirectories()
                        val read =
                            runCatching {
                                homer.app.runners.TarStreams.extract(
                                    response.bodyAsChannel().toInputStream(),
                                    extracted,
                                    DownloadMode.OVERWRITE,
                                    remoteIsDir = true,
                                    fileName = ".",
                                    sourceLabel = "test",
                                    maxDecompressedBytes = homer.app.runners.TarStreams.DEFAULT_MAX_EXTRACTED_BYTES,
                                )
                            }
                        assertTrue(read.isFailure, "a fetch past the deadline must not deliver the whole tar")
                    }
                session.close()
                upload.await()
            }
        }

    @Test
    fun `an output chunk the instance never finishes sending is refused at transferTimeoutSec`() =
        testApplication {
            val slow = ExternalRunnerDomain("slow", RunnerConfig.External(token = "slow-token", transferTimeoutSec = 1), queue)
            val (sid, session) = claimedSessionIn(slow, "slow-token")
            val out = tmp.resolve("cmd_0.out")
            val err = tmp.resolve("cmd_0.err")
            coroutineScope {
                val cmd = async(Dispatchers.Default) { runCatching { session.shell.cmd(listOf("echo"), out, err, timeout = 30.seconds) } }
                delay(100)
                val op =
                    apiJson.decodeFromString(
                        RunnerOp.serializer(),
                        client
                            .post("/api/v1/runner/sessions/$sid/next") {
                                header(HttpHeaders.Authorization, "Bearer slow-token")
                            }.bodyAsText(),
                    ) as RunnerOp.Cmd
                val answered = CompletableDeferred<Unit>()
                val stalling =
                    object : OutgoingContent.WriteChannelContent() {
                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            channel.writeByteArray("x".toByteArray())
                            channel.flush()
                            answered.await()
                        }
                    }
                val posted =
                    withTimeoutOrNull(10.seconds) {
                        client.post("/api/v1/runner/sessions/$sid/ops/${op.opId}/output?stream=stdout&seq=0") {
                            header(HttpHeaders.Authorization, "Bearer slow-token")
                            setBody(stalling)
                        }
                    }
                answered.complete(Unit)
                assertEquals(HttpStatusCode.RequestTimeout, posted?.status, "the deadline must not wait for the stalled peer")
                session.close()
                cmd.await()
            }
        }

    @Test
    fun `a concurrent second fetch of the same upload tar is 429 until the first one finishes`() =
        testApplication {
            mount()
            val (sid, session) = claimedSession()
            val src = bigUploadSource()
            coroutineScope {
                val upload = async(Dispatchers.Default) { runCatching { session.upload(src, ".") } }
                delay(100)
                val op = nextOp(sid) as RunnerOp.Upload
                val url = "/api/v1/runner/sessions/$sid/ops/${op.opId}/tar"
                client
                    .prepareGet(url) { header(HttpHeaders.Authorization, "Bearer workers-token") }
                    .execute { first ->
                        // Unread, the first fetch stays blocked mid-stream and holds the op's slot.
                        assertEquals(HttpStatusCode.OK, first.status)
                        delay(300)
                        assertEquals(
                            HttpStatusCode.TooManyRequests,
                            client.get(url) { header(HttpHeaders.Authorization, "Bearer workers-token") }.status,
                        )
                        first.bodyAsChannel().toInputStream().use { it.readBytes() }
                    }
                // Released by the producer's `finally`, a moment after the stream's EOF reached us.
                val again =
                    withTimeoutOrNull(5.seconds) {
                        var status: HttpStatusCode
                        do {
                            status = client.get(url) { header(HttpHeaders.Authorization, "Bearer workers-token") }.status
                            if (status == HttpStatusCode.TooManyRequests) delay(50)
                        } while (status == HttpStatusCode.TooManyRequests)
                        status
                    }
                assertEquals(HttpStatusCode.OK, again)
                session.close()
                upload.await()
            }
        }

    @Test
    fun `a concurrent second post of the same download tar is 429 and the first one still lands`() =
        testApplication {
            mount()
            val (sid, session) = claimedSession()
            val local = tmp.resolve("landed").createDirectories()
            val remote = tmp.resolve("remote-src").createDirectories().also { it.resolve("fresh").writeText("new") }
            val body = tarOf(remote)
            coroutineScope {
                val download = async(Dispatchers.Default) { session.download("workdir", local, DownloadMode.OVERWRITE) }
                delay(100)
                val op = nextOp(sid) as RunnerOp.Download
                val url = "/api/v1/runner/sessions/$sid/ops/${op.opId}/tar?kind=dir"
                val resume = CompletableDeferred<Unit>()
                val first =
                    async(Dispatchers.Default) {
                        client.post(url) {
                            header(HttpHeaders.Authorization, "Bearer workers-token")
                            setBody(
                                object : OutgoingContent.WriteChannelContent() {
                                    override suspend fun writeTo(channel: ByteWriteChannel) {
                                        channel.writeByteArray(body.copyOfRange(0, 1))
                                        channel.flush()
                                        resume.await()
                                        channel.writeByteArray(body.copyOfRange(1, body.size))
                                        channel.flush()
                                    }
                                },
                            )
                        }
                    }
                delay(300)
                val duplicate =
                    client.post(url) {
                        header(HttpHeaders.Authorization, "Bearer workers-token")
                        setBody(body)
                    }
                assertEquals(HttpStatusCode.TooManyRequests, duplicate.status)
                resume.complete(Unit)
                assertEquals(HttpStatusCode.Accepted, first.await().status)
                assertEquals("new", local.resolve("fresh").readText())
                // Released in `finally`: the op accepts a post again once the first one ended.
                assertEquals(
                    HttpStatusCode.Accepted,
                    client
                        .post(url) {
                            header(HttpHeaders.Authorization, "Bearer workers-token")
                            setBody(body)
                        }.status,
                )
                complete(sid, OpResult(op.opId))
                download.await()
            }
            session.close()
        }

    @Test
    fun `a concurrent duplicate output chunk is 429 while the other stream and the next chunk are served`() =
        testApplication {
            mount()
            val (sid, session) = claimedSession()
            val out = tmp.resolve("cmd_0.out")
            val err = tmp.resolve("cmd_0.err")
            coroutineScope {
                val cmd = async(Dispatchers.Default) { session.shell.cmd(listOf("echo"), out, err, timeout = 30.seconds) }
                delay(100)
                val op = nextOp(sid) as RunnerOp.Cmd

                suspend fun chunk(
                    stream: String,
                    seq: Long,
                    body: ByteArray,
                ) = client.post("/api/v1/runner/sessions/$sid/ops/${op.opId}/output?stream=$stream&seq=$seq") {
                    header(HttpHeaders.Authorization, "Bearer workers-token")
                    setBody(body)
                }

                val resume = CompletableDeferred<Unit>()
                val first =
                    async(Dispatchers.Default) {
                        client.post("/api/v1/runner/sessions/$sid/ops/${op.opId}/output?stream=stdout&seq=0") {
                            header(HttpHeaders.Authorization, "Bearer workers-token")
                            setBody(
                                object : OutgoingContent.WriteChannelContent() {
                                    override suspend fun writeTo(channel: ByteWriteChannel) {
                                        channel.writeByteArray("o".toByteArray())
                                        channel.flush()
                                        resume.await()
                                        channel.writeByteArray("ne\n".toByteArray())
                                        channel.flush()
                                    }
                                },
                            )
                        }
                    }
                delay(300)
                assertEquals(HttpStatusCode.TooManyRequests, chunk("stdout", 0, "one\n".toByteArray()).status)
                // The bound is per stream: stdout's slot being held does not block stderr.
                assertEquals(HttpStatusCode.Accepted, chunk("stderr", 0, "warn\n".toByteArray()).status)
                resume.complete(Unit)
                assertEquals(HttpStatusCode.Accepted, first.await().status)
                assertEquals(HttpStatusCode.Accepted, chunk("stdout", 1, "two\n".toByteArray()).status)

                complete(sid, OpResult(op.opId, exitCode = 0))
                assertEquals(0, cmd.await().exitCode)
                assertEquals("one\ntwo\n", out.readText())
                assertEquals("warn\n", err.readText())
            }
            session.close()
        }

    @Test
    fun `output chunks are appended in order, a gap is 409, a bad stream is 400 and an oversized chunk is 413`() =
        testApplication {
            mount()
            val (sid, session) = claimedSession()
            val out = tmp.resolve("cmd_0.out")
            val err = tmp.resolve("cmd_0.err")
            coroutineScope {
                val cmd = async(Dispatchers.Default) { session.shell.cmd(listOf("echo"), out, err, timeout = 30.seconds) }
                delay(100)
                val op = nextOp(sid) as RunnerOp.Cmd

                suspend fun chunk(
                    stream: String,
                    seq: Long,
                    body: ByteArray,
                ) = client.post("/api/v1/runner/sessions/$sid/ops/${op.opId}/output?stream=$stream&seq=$seq") {
                    header(HttpHeaders.Authorization, "Bearer workers-token")
                    setBody(body)
                }

                assertEquals(HttpStatusCode.Accepted, chunk("stdout", 0, "one\n".toByteArray()).status)
                assertEquals(HttpStatusCode.Conflict, chunk("stdout", 2, "three\n".toByteArray()).status)
                assertEquals(HttpStatusCode.Accepted, chunk("stdout", 1, "two\n".toByteArray()).status)
                assertEquals(HttpStatusCode.Accepted, chunk("stderr", 0, "warn\n".toByteArray()).status)
                assertEquals(HttpStatusCode.BadRequest, chunk("stdlog", 0, "x".toByteArray()).status)
                assertEquals(
                    HttpStatusCode.BadRequest,
                    client
                        .post("/api/v1/runner/sessions/$sid/ops/${op.opId}/output?stream=stdout") {
                            header(HttpHeaders.Authorization, "Bearer workers-token")
                            setBody("x".toByteArray())
                        }.status,
                )
                assertEquals(
                    HttpStatusCode.PayloadTooLarge,
                    chunk("stdout", 2, ByteArray(RunnerProtocol.MAX_OUTPUT_CHUNK_BYTES + 1)).status,
                )
                assertEquals(
                    HttpStatusCode.NotFound,
                    client
                        .post("/api/v1/runner/sessions/$sid/ops/unknown/output?stream=stdout&seq=0") {
                            header(HttpHeaders.Authorization, "Bearer workers-token")
                            setBody("x".toByteArray())
                        }.status,
                )

                complete(sid, OpResult(op.opId, exitCode = 0))
                assertEquals(0, cmd.await().exitCode)
                assertEquals("one\ntwo\n", out.readText())
                assertEquals("warn\n", err.readText())
            }
            session.close()
        }

    @Test
    fun `past the deadline only the cancelled channel's failure becomes a timeout, a transfer's own refusal keeps its status`() =
        runBlocking<Unit> {
            val slow = ExternalRunnerDomain("slow", RunnerConfig.External(token = "slow-token", transferTimeoutSec = 1), queue)
            // Each block outlives the deadline (the watchdog has fired) before it fails.
            assertThrows<SecurityException> {
                slow.withTransferTimeout(release = {}) {
                    Thread.sleep(1_500)
                    throw SecurityException("entry escapes the target")
                }
            }
            assertThrows<RunnerTransferTooLargeException> {
                slow.withTransferTimeout(release = {}) {
                    Thread.sleep(1_500)
                    throw RunnerTransferTooLargeException(16)
                }
            }
            assertThrows<RunnerTransferTimeoutException> {
                slow.withTransferTimeout(release = {}) {
                    Thread.sleep(1_500)
                    throw java.io.IOException("channel cancelled")
                }
            }
        }

    @Test
    fun `a caller cancelled past the deadline ends cancelled, not as a transfer timeout`() =
        runBlocking<Unit> {
            val slow = ExternalRunnerDomain("slow", RunnerConfig.External(token = "slow-token", transferTimeoutSec = 1), queue)
            // What withTransferTimeout itself throws, captured inside the cancelled caller.
            val thrown = CompletableDeferred<Throwable?>()
            launch {
                val caller = coroutineContext.job
                val result =
                    runCatching {
                        slow.withTransferTimeout(release = {}) {
                            Thread.sleep(1_500) // the watchdog has fired
                            caller.cancel() // e.g. the runner disconnects
                            throw CancellationException("caller cancelled")
                        }
                    }
                thrown.complete(result.exceptionOrNull())
            }.join()

            assertIs<CancellationException>(thrown.await())
        }
}
