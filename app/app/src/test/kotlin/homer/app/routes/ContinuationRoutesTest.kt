package homer.app.routes

import homer.app.apiJson
import homer.app.exceptions.CooldownActiveException
import homer.app.exceptions.ValidationException
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.mountApiV1Routes
import homer.app.fixtures.mountCallbackRoutes
import homer.app.infra.AgentInputResolver
import homer.app.models.Agent
import homer.app.models.AgentDispatchConfig
import homer.app.models.ContinuationListResponse
import homer.app.models.ContinuationResponse
import homer.app.models.CreateJoinResponse
import homer.app.models.CreateRunResponse
import homer.app.models.DispatchConfig
import homer.app.models.ProcessStatus
import homer.app.services.AgentRunStarter
import homer.app.services.AgentsService
import homer.app.services.ContinuationKind
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Mirrors [DispatchRoutesTest]: callback-side (`/callback/runs`) behavior runs
 * through [mountCallbackRoutes], operator-side (`/continuations`) through
 * [mountApiV1Routes], seeding via the shared service when only one side is
 * exercised. See that file's note on the dual mount points.
 */
class ContinuationRoutesTest {
    /** A QuestionsService + ContinuationService + DispatchService sharing one database. */
    private class Harness(
        config: DispatchConfig = DispatchConfig(),
    ) {
        val db = PersistenceTestSupport.tempDatabase()
        val questions = PersistenceTestSupport.questionsService(db = db)
        val agents = mockk<AgentsService>()
        val runStarter = mockk<AgentRunStarter>()
        val inputResolver = mockk<AgentInputResolver>(relaxed = true)
        val processManager = PersistenceTestSupport.processManager(db = db)
        val dispatches =
            PersistenceTestSupport.dispatchService(
                db = db,
                questionsService = questions,
                agentsService = agents,
                runStarter = runStarter,
                inputResolver = inputResolver,
                processManager = processManager,
                config = config,
            )
        val continuations =
            PersistenceTestSupport.continuationService(
                db = db,
                questionsService = questions,
                agentsService = agents,
                runStarter = runStarter,
                inputResolver = inputResolver,
                processManager = processManager,
                config = config,
            )

        init {
            val handler = mockk<Agent>(relaxed = true)
            every { handler.dispatch } returns AgentDispatchConfig(enabled = true)
            val blocked = mockk<Agent>(relaxed = true)
            every { blocked.dispatch } returns null
            every { agents.agents } returns mapOf("handler" to handler, "blocked" to blocked)
        }

        /** A real, still-running watched process so a seeded continuation stays PENDING. */
        fun workingProcess(): Int =
            runBlocking {
                processManager.obtainProcessId("handler").also {
                    processManager.setProcessStatus(it, ProcessStatus.WORKING)
                }
            }
    }

    @Test
    fun `callback runs with continuation fields starts the sub and registers a continuation`() =
        testApplication {
            val h = Harness()
            // A real WORKING sub so the continuation stays PENDING (the race re-check is exercised separately).
            val subId = h.workingProcess()
            coEvery { h.runStarter.start(any(), any(), eq("dispatch"), any(), any(), any(), any(), any(), any(), any()) } returns subId
            val token = runBlocking { h.questions.registerProcess(7, "asker") }
            mountCallbackRoutes {
                every { questionsService } returns h.questions
                every { dispatchService } returns h.dispatches
                every { continuationService } returns h.continuations
            }

            val resp =
                client.post("/api/v1/callback/runs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"agent":"handler","continueAgent":"handler","continueThread":"thread-1",
                           |"resultJsonPath":"$.answer","ttlSec":60}
                        """.trimMargin(),
                    )
                }

            assertEquals(HttpStatusCode.OK, resp.status)
            val created = apiJson.decodeFromString<CreateRunResponse>(resp.bodyAsText())
            assertEquals(subId, created.processId)
            val continuationId = assertNotNull(created.continuationId)
            val continuation = runBlocking { h.continuations.get(continuationId) }
            assertNotNull(continuation)
            assertEquals(subId, continuation.watchedProcessId)
            assertEquals("handler", continuation.targetAgentName)
            assertEquals("thread-1", continuation.params["HMR_LG_THREAD"])
            assertEquals("HMR_LG_RESUME", continuation.resumeParam)
            assertEquals("PENDING", continuation.status.name)
        }

    @Test
    fun `callback runs routes continueParams to the continuation and params to the sub`() =
        testApplication {
            val h = Harness()
            val subId = h.workingProcess()
            coEvery { h.runStarter.start(any(), any(), eq("dispatch"), any(), any(), any(), any(), any(), any(), any()) } returns subId
            val token = runBlocking { h.questions.registerProcess(7, "asker") }
            mountCallbackRoutes {
                every { questionsService } returns h.questions
                every { dispatchService } returns h.dispatches
                every { continuationService } returns h.continuations
            }

            // The sub gets `params`; the continuation gets `continueParams` (e.g. the LangGraph
            // interrupt id, which must reach the RESUMED parent, not the sub). The two must not bleed.
            val resp =
                client.post("/api/v1/callback/runs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"agent":"handler","params":{"SUB_ONLY":"x"},
                           |"continueAgent":"handler","continueThread":"thread-1",
                           |"continueParams":{"HMR_LG_INTERRUPT":"int-9"}}
                        """.trimMargin(),
                    )
                }

            assertEquals(HttpStatusCode.OK, resp.status)
            val created = apiJson.decodeFromString<CreateRunResponse>(resp.bodyAsText())
            val continuation = runBlocking { h.continuations.get(assertNotNull(created.continuationId)) }
            assertNotNull(continuation)
            // The continuation carries the thread id AND the continuation-targeted interrupt id…
            assertEquals("int-9", continuation.params["HMR_LG_INTERRUPT"])
            assertEquals("thread-1", continuation.params["HMR_LG_THREAD"])
            // …but NOT the sub-agent-only param.
            assertNull(continuation.params["SUB_ONLY"])
        }

    @Test
    fun `callback runs without continuation fields is a plain dispatch`() =
        testApplication {
            val h = Harness()
            coEvery { h.runStarter.start(any(), any(), eq("dispatch"), any(), any(), any(), any(), any(), any(), any()) } returns 42
            val token = runBlocking { h.questions.registerProcess(7, "asker") }
            mountCallbackRoutes {
                every { questionsService } returns h.questions
                every { dispatchService } returns h.dispatches
                every { continuationService } returns h.continuations
            }

            val resp =
                client.post("/api/v1/callback/runs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"agent":"handler"}""")
                }

            assertEquals(HttpStatusCode.OK, resp.status)
            val created = apiJson.decodeFromString<CreateRunResponse>(resp.bodyAsText())
            assertEquals(42, created.processId)
            assertNull(created.continuationId)
            assertEquals(emptyList(), runBlocking { h.continuations.page(null, null, null, null, 0, 100).items })
        }

    @Test
    fun `callback runs with a non-dispatchable continueAgent is rejected without starting the sub`() =
        testApplication {
            val h = Harness()
            coEvery { h.runStarter.start(any(), any(), eq("dispatch"), any(), any(), any(), any(), any(), any(), any()) } returns 42
            val token = runBlocking { h.questions.registerProcess(7, "asker") }
            mountCallbackRoutes {
                every { questionsService } returns h.questions
                every { dispatchService } returns h.dispatches
                every { continuationService } returns h.continuations
            }

            val resp =
                client.post("/api/v1/callback/runs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"agent":"handler","continueAgent":"blocked"}""")
                }

            assertEquals(HttpStatusCode.Forbidden, resp.status)
            // Continuation validation runs BEFORE dispatchNow, so the sub never started.
            coVerify(exactly = 0) { h.runStarter.start(any(), any(), eq("dispatch"), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `callback runs with a blank resumeParam is rejected without starting the sub`() =
        testApplication {
            val h = Harness()
            coEvery { h.runStarter.start(any(), any(), eq("dispatch"), any(), any(), any(), any(), any(), any(), any()) } returns 42
            val token = runBlocking { h.questions.registerProcess(7, "asker") }
            mountCallbackRoutes {
                every { questionsService } returns h.questions
                every { dispatchService } returns h.dispatches
                every { continuationService } returns h.continuations
            }

            val resp =
                client.post("/api/v1/callback/runs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"agent":"handler","continueAgent":"handler","resumeParam":""}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            coVerify(exactly = 0) { h.runStarter.start(any(), any(), eq("dispatch"), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `callback runs fires the continuation when the sub is already terminal`() =
        testApplication {
            val h = Harness()
            // The dispatched sub is already FINISHED before the continuation row exists.
            val subId =
                runBlocking {
                    val pid = h.processManager.obtainProcessId("handler")
                    h.processManager.setProcessStatus(pid, ProcessStatus.FINISHED)
                    pid
                }
            coEvery { h.runStarter.start(any(), any(), eq("dispatch"), any(), any(), any(), any(), any(), any(), any()) } returns subId
            coEvery { h.runStarter.start(any(), any(), eq("continuation"), any(), any(), any(), any(), any(), any(), any()) } returns 99
            val token = runBlocking { h.questions.registerProcess(7, "asker") }
            mountCallbackRoutes {
                every { questionsService } returns h.questions
                every { dispatchService } returns h.dispatches
                every { continuationService } returns h.continuations
            }

            val resp =
                client.post("/api/v1/callback/runs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"agent":"handler","continueAgent":"handler"}""")
                }

            assertEquals(HttpStatusCode.OK, resp.status)
            val created = apiJson.decodeFromString<CreateRunResponse>(resp.bodyAsText())
            val continuation = runBlocking { h.continuations.get(assertNotNull(created.continuationId)) }
            assertNotNull(continuation)
            // The race close fired it immediately rather than leaving it PENDING.
            assertEquals("DISPATCHED", continuation.status.name)
            assertEquals(99, continuation.firedProcessId)
        }

    @Test
    fun `callback joins starts all subs and registers a join continuation`() =
        testApplication {
            val h = Harness()
            val sub1 = h.workingProcess()
            val sub2 = h.workingProcess()
            coEvery { h.runStarter.start(any(), any(), eq("dispatch"), any(), any(), any(), any(), any(), any(), any()) } returnsMany
                listOf(sub1, sub2)
            val token = runBlocking { h.questions.registerProcess(7, "asker") }
            mountCallbackRoutes {
                every { questionsService } returns h.questions
                every { dispatchService } returns h.dispatches
                every { continuationService } returns h.continuations
            }

            val resp =
                client.post("/api/v1/callback/joins") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"subs":[{"agent":"handler","interruptId":"int-a"},
                           |{"agent":"handler","params":{"SUB_ONLY":"x"},"interruptId":"int-b"}],
                           |"continueAgent":"handler","continueThread":"thread-1",
                           |"continueParams":{"HMR_LG_INTERRUPT":"int-a,int-b"}}
                        """.trimMargin(),
                    )
                }

            assertEquals(HttpStatusCode.OK, resp.status)
            val created = apiJson.decodeFromString<CreateJoinResponse>(resp.bodyAsText())
            assertEquals(listOf(sub1, sub2), created.processIds)
            val continuation = runBlocking { h.continuations.get(created.continuationId) }
            assertNotNull(continuation)
            assertEquals(ContinuationKind.JOIN, continuation.kind)
            assertEquals("PENDING", continuation.status.name)
            assertEquals(setOf(sub1, sub2), continuation.watches.map { it.watchedProcessId }.toSet())
            assertEquals(setOf("int-a", "int-b"), continuation.watches.map { it.interruptId }.toSet())
            // The continuation carries the thread id AND the comma-joined interrupt ids (the join signal).
            assertEquals("thread-1", continuation.params["HMR_LG_THREAD"])
            assertEquals("int-a,int-b", continuation.params["HMR_LG_INTERRUPT"])
        }

    @Test
    fun `callback joins with a non-dispatchable sub starts no sub and is rejected`() =
        testApplication {
            val h = Harness()
            coEvery { h.runStarter.start(any(), any(), eq("dispatch"), any(), any(), any(), any(), any(), any(), any()) } returns 42
            val token = runBlocking { h.questions.registerProcess(7, "asker") }
            mountCallbackRoutes {
                every { questionsService } returns h.questions
                every { dispatchService } returns h.dispatches
                every { continuationService } returns h.continuations
            }

            val resp =
                client.post("/api/v1/callback/joins") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"subs":[{"agent":"handler","interruptId":"int-a"},{"agent":"blocked","interruptId":"int-b"}],
                           |"continueAgent":"handler"}
                        """.trimMargin(),
                    )
                }

            assertEquals(HttpStatusCode.Forbidden, resp.status)
            // Validate-before-start: a non-dispatchable sub must reject before any sub is started.
            coVerify(exactly = 0) { h.runStarter.start(any(), any(), eq("dispatch"), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `callback joins fires the join when all subs are already terminal`() =
        testApplication {
            val h = Harness()
            val sub1 =
                runBlocking {
                    val p = h.processManager.obtainProcessId("handler")
                    h.processManager.setProcessStatus(p, ProcessStatus.FINISHED)
                    p
                }
            val sub2 =
                runBlocking {
                    val p = h.processManager.obtainProcessId("handler")
                    h.processManager.setProcessStatus(p, ProcessStatus.FINISHED)
                    p
                }
            coEvery { h.runStarter.start(any(), any(), eq("dispatch"), any(), any(), any(), any(), any(), any(), any()) } returnsMany
                listOf(sub1, sub2)
            coEvery { h.runStarter.start(any(), any(), eq("continuation"), any(), any(), any(), any(), any(), any(), any()) } returns 99
            val token = runBlocking { h.questions.registerProcess(7, "asker") }
            mountCallbackRoutes {
                every { questionsService } returns h.questions
                every { dispatchService } returns h.dispatches
                every { continuationService } returns h.continuations
            }

            val resp =
                client.post("/api/v1/callback/joins") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"subs":[{"agent":"handler","interruptId":"int-a"},{"agent":"handler","interruptId":"int-b"}],
                           |"continueAgent":"handler"}
                        """.trimMargin(),
                    )
                }

            assertEquals(HttpStatusCode.OK, resp.status)
            val created = apiJson.decodeFromString<CreateJoinResponse>(resp.bodyAsText())
            val continuation = runBlocking { h.continuations.get(created.continuationId) }
            assertNotNull(continuation)
            // The race close fired it immediately rather than leaving it PENDING.
            assertEquals("DISPATCHED", continuation.status.name)
            assertEquals(99, continuation.firedProcessId)
        }

    // ===== No untracked sub on a 4xx (ADR-0026, 2026-10-01 amendment) =====

    /** Fills the single PENDING slot of a `maxPending = 1` harness. */
    private fun Harness.fillPendingCap() =
        runBlocking { continuations.create(7, workingProcess(), "handler", emptyMap(), "HMR_LG_RESUME", null, null) }

    @Test
    fun `callback runs whose continuation hits the pending cap kills the started sub`() =
        testApplication {
            val h = Harness(DispatchConfig(maxPending = 1))
            val subId = h.workingProcess()
            coEvery { h.runStarter.start(any(), any(), eq("dispatch"), any(), any(), any(), any(), any(), any(), any()) } returns subId
            val token = runBlocking { h.questions.registerProcess(7, "asker") }
            h.fillPendingCap()
            mountCallbackRoutes {
                every { questionsService } returns h.questions
                every { dispatchService } returns h.dispatches
                every { continuationService } returns h.continuations
                every { processManager } returns h.processManager
            }

            val resp =
                client.post("/api/v1/callback/runs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"agent":"handler","continueAgent":"handler"}""")
                }

            assertEquals(HttpStatusCode.TooManyRequests, resp.status)
            assertEquals(ProcessStatus.KILLED, runBlocking { h.processManager.getProcessStatus(subId) })
        }

    @Test
    fun `callback joins whose second sub fails to start kills the first and is rejected`() =
        testApplication {
            val h = Harness()
            val sub1 = h.workingProcess()
            coEvery { h.runStarter.start(any(), any(), eq("dispatch"), any(), any(), any(), any(), any(), any(), any()) } returns
                sub1 andThenThrows CooldownActiveException("handler", 30, "cooldown.min")
            val token = runBlocking { h.questions.registerProcess(7, "asker") }
            mountCallbackRoutes {
                every { questionsService } returns h.questions
                every { dispatchService } returns h.dispatches
                every { continuationService } returns h.continuations
                every { processManager } returns h.processManager
            }

            val resp =
                client.post("/api/v1/callback/joins") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"subs":[{"agent":"handler","interruptId":"int-a"},{"agent":"handler","interruptId":"int-b"}],
                           |"continueAgent":"handler"}
                        """.trimMargin(),
                    )
                }

            assertEquals(HttpStatusCode.TooManyRequests, resp.status)
            assertEquals(ProcessStatus.KILLED, runBlocking { h.processManager.getProcessStatus(sub1) })
            assertEquals(emptyList(), runBlocking { h.continuations.page(null, null, null, null, 0, 100).items })
        }

    @Test
    fun `callback joins whose continuation hits the pending cap kills every started sub`() =
        testApplication {
            val h = Harness(DispatchConfig(maxPending = 1))
            val sub1 = h.workingProcess()
            val sub2 = h.workingProcess()
            coEvery { h.runStarter.start(any(), any(), eq("dispatch"), any(), any(), any(), any(), any(), any(), any()) } returnsMany
                listOf(sub1, sub2)
            val token = runBlocking { h.questions.registerProcess(7, "asker") }
            h.fillPendingCap()
            mountCallbackRoutes {
                every { questionsService } returns h.questions
                every { dispatchService } returns h.dispatches
                every { continuationService } returns h.continuations
                every { processManager } returns h.processManager
            }

            val resp =
                client.post("/api/v1/callback/joins") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"subs":[{"agent":"handler","interruptId":"int-a"},{"agent":"handler","interruptId":"int-b"}],
                           |"continueAgent":"handler"}
                        """.trimMargin(),
                    )
                }

            assertEquals(HttpStatusCode.TooManyRequests, resp.status)
            assertEquals(ProcessStatus.KILLED, runBlocking { h.processManager.getProcessStatus(sub1) })
            assertEquals(ProcessStatus.KILLED, runBlocking { h.processManager.getProcessStatus(sub2) })
        }

    @Test
    fun `callback joins with invalid params on a later sub starts no sub`() =
        testApplication {
            val h = Harness()
            coEvery { h.runStarter.start(any(), any(), eq("dispatch"), any(), any(), any(), any(), any(), any(), any()) } returns 42
            every { h.inputResolver.resolveFromParams(eq(mapOf("BAD" to "x")), any(), any()) } throws
                ValidationException("unknown input 'BAD'", "BAD")
            val token = runBlocking { h.questions.registerProcess(7, "asker") }
            mountCallbackRoutes {
                every { questionsService } returns h.questions
                every { dispatchService } returns h.dispatches
                every { continuationService } returns h.continuations
                every { processManager } returns h.processManager
            }

            val resp =
                client.post("/api/v1/callback/joins") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"subs":[{"agent":"handler","interruptId":"int-a"},
                           |{"agent":"handler","params":{"BAD":"x"},"interruptId":"int-b"}],
                           |"continueAgent":"handler"}
                        """.trimMargin(),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            coVerify(exactly = 0) { h.runStarter.start(any(), any(), eq("dispatch"), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `GET continuations lists and fetches by id`() =
        testApplication {
            val h = Harness()
            val watched = h.workingProcess()
            val created =
                runBlocking {
                    h.questions.registerProcess(7, "asker")
                    h.continuations.create(7, watched, "handler", emptyMap(), "HMR_LG_RESUME", null, null)
                }
            mountApiV1Routes {
                every { questionsService } returns h.questions
                every { continuationService } returns h.continuations
            }

            val list =
                apiJson.decodeFromString<ContinuationListResponse>(
                    client.get("/api/v1/continuations").bodyAsText(),
                )
            assertEquals(1, list.continuations.size)
            assertEquals(created, list.continuations.first().id)

            val getResp = client.get("/api/v1/continuations/$created")
            assertEquals(HttpStatusCode.OK, getResp.status)
            val one = apiJson.decodeFromString<ContinuationResponse>(getResp.bodyAsText())
            assertEquals("handler", one.agentName)
            assertEquals("PENDING", one.status)
        }

    @Test
    fun `GET continuations filters by status`() =
        testApplication {
            val h = Harness()
            val watched = h.workingProcess()
            runBlocking {
                h.questions.registerProcess(7, "asker")
                h.continuations.create(7, watched, "handler", emptyMap(), "HMR_LG_RESUME", null, null)
            }
            mountApiV1Routes {
                every { questionsService } returns h.questions
                every { continuationService } returns h.continuations
            }

            val pending =
                apiJson.decodeFromString<ContinuationListResponse>(
                    client.get("/api/v1/continuations?status=PENDING").bodyAsText(),
                )
            assertEquals(1, pending.continuations.size)
            val dispatched =
                apiJson.decodeFromString<ContinuationListResponse>(
                    client.get("/api/v1/continuations?status=DISPATCHED").bodyAsText(),
                )
            assertEquals(0, dispatched.continuations.size)
        }

    @Test
    fun `GET unknown continuation responds 404`() =
        testApplication {
            val h = Harness()
            mountApiV1Routes {
                every { questionsService } returns h.questions
                every { continuationService } returns h.continuations
            }
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/continuations/999").status)
        }

    @Test
    fun `DELETE continuation cancels a PENDING continuation`() =
        testApplication {
            val h = Harness()
            val watched = h.workingProcess()
            val created =
                runBlocking {
                    h.questions.registerProcess(7, "asker")
                    h.continuations.create(7, watched, "handler", emptyMap(), "HMR_LG_RESUME", null, null)
                }
            mountApiV1Routes {
                every { questionsService } returns h.questions
                every { continuationService } returns h.continuations
            }

            val cancelResp = client.delete("/api/v1/continuations/$created")
            assertEquals(HttpStatusCode.NoContent, cancelResp.status)
            assertEquals("CANCELLED", runBlocking { h.continuations.get(created) }?.status?.name)
        }

    @Test
    fun `GET continuations pages newest first and reports total, offset and limit`() =
        testApplication {
            val h = Harness()
            val watched = h.workingProcess()
            val ids =
                runBlocking {
                    h.questions.registerProcess(7, "asker")
                    (1..3).map { h.continuations.create(7, watched, "handler", emptyMap(), "HMR_LG_RESUME", null, null) }
                }
            mountApiV1Routes {
                every { questionsService } returns h.questions
                every { continuationService } returns h.continuations
            }

            val first = apiJson.decodeFromString<ContinuationListResponse>(client.get("/api/v1/continuations?limit=2").bodyAsText())
            assertEquals(ids.reversed().take(2), first.continuations.map { it.id })
            assertEquals(3, first.total)
            assertEquals(0, first.offset)
            assertEquals(2, first.limit)
            val rest =
                apiJson.decodeFromString<ContinuationListResponse>(client.get("/api/v1/continuations?limit=2&offset=2").bodyAsText())
            assertEquals(listOf(ids.first()), rest.continuations.map { it.id })
            assertEquals(3, rest.total)
            val defaults = apiJson.decodeFromString<ContinuationListResponse>(client.get("/api/v1/continuations").bodyAsText())
            assertEquals(100, defaults.limit)
            assertEquals(3, defaults.continuations.size)
        }

    @Test
    fun `GET continuations rejects a limit outside 1 to 1000 and a negative offset`() =
        testApplication {
            val h = Harness()
            mountApiV1Routes {
                every { questionsService } returns h.questions
                every { continuationService } returns h.continuations
            }

            for (query in listOf("limit=0", "limit=1001", "offset=-1")) {
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/continuations?$query").status, query)
            }
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/continuations?limit=1000").status)
        }
}
