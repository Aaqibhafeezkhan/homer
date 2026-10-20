package homer.app.routes

import homer.app.EnvConstants
import homer.app.apiJson
import homer.app.exceptions.AgentNotDispatchableException
import homer.app.exceptions.DispatchAgentNotFoundException
import homer.app.exceptions.QuestionsClosedForProcessException
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.mountCallbackRoutes
import homer.app.infra.SecretRedactor
import homer.app.models.AskQuestionResponse
import homer.app.models.CreateRunResponse
import homer.app.models.QuestionResponse
import homer.app.models.QuestionsConfig
import homer.app.models.ResultEnvParam
import homer.app.models.ResultTransformer
import homer.app.models.SrcType
import homer.app.models.TagValidation
import homer.app.services.ArtifactTooLargeException
import homer.app.services.RESULT_TRANSFORM_MAX_BYTES
import homer.app.services.persistence.ResultTokenRecord
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallbackRoutesTest {
    private fun newService(config: QuestionsConfig = QuestionsConfig()) = PersistenceTestSupport.questionsService(config = config)

    @Test
    fun `ask without a token responds 401`() =
        testApplication {
            mountCallbackRoutes { every { questionsService } returns newService() }

            val response =
                client.post("/api/v1/callback/questions") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"anyone?"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `ask with an unknown token responds 401`() =
        testApplication {
            mountCallbackRoutes { every { questionsService } returns newService() }

            val response =
                client.post("/api/v1/callback/questions") {
                    header(HttpHeaders.Authorization, "Bearer not-a-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"anyone?"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `ask returns the question id`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes { every { questionsService } returns service }

            val response =
                client.post("/api/v1/callback/questions") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"Deploy to prod?","options":["yes","no"]}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = apiJson.decodeFromString<AskQuestionResponse>(response.bodyAsText())
            assertEquals(body.id, service.get(body.id)?.id)
            assertEquals(listOf("yes", "no"), service.get(body.id)?.options)
        }

    @Test
    fun `check without waitSec returns the current OPEN snapshot immediately`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            val question = runBlocking { service.ask(1, "Proceed?") }
            mountCallbackRoutes { every { questionsService } returns service }

            val response =
                client.get("/api/v1/callback/questions/${question.id}") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = apiJson.decodeFromString<QuestionResponse>(response.bodyAsText())
            assertEquals("OPEN", body.status)
            assertEquals(null, body.answer)
        }

    @Test
    fun `long-poll resolves when the answer lands mid-poll`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            val question = runBlocking { service.ask(1, "Proceed?") }
            mountCallbackRoutes { every { questionsService } returns service }

            coroutineScope {
                val answerJob =
                    launch {
                        delay(200)
                        service.answer(question.id, "yes, go")
                    }

                val response =
                    client.get("/api/v1/callback/questions/${question.id}?waitSec=30") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }

                assertEquals(HttpStatusCode.OK, response.status)
                val body = apiJson.decodeFromString<QuestionResponse>(response.bodyAsText())
                assertEquals("ANSWERED", body.status)
                assertEquals("yes, go", body.answer)
                answerJob.join()
            }
        }

    @Test
    fun `waitSec is clamped to maxWaitSec so the poll returns OPEN`() =
        testApplication {
            val service = newService(QuestionsConfig(maxWaitSec = 1))
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            val question = runBlocking { service.ask(1, "Proceed?") }
            mountCallbackRoutes { every { questionsService } returns service }

            // Unclamped, this request would hold for ~28 hours and time the
            // test out; returning OPEN promptly proves the clamp applied.
            val response =
                client.get("/api/v1/callback/questions/${question.id}?waitSec=99999") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = apiJson.decodeFromString<QuestionResponse>(response.bodyAsText())
            assertEquals("OPEN", body.status)
        }

    @Test
    fun `non-integer waitSec responds 400`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            val question = runBlocking { service.ask(1, "Proceed?") }
            mountCallbackRoutes { every { questionsService } returns service }

            val response =
                client.get("/api/v1/callback/questions/${question.id}?waitSec=soon") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `a foreign process question id responds 404`() =
        testApplication {
            val service = newService()
            val tokenA = runBlocking { service.registerProcess(1, "agent-a") }
            runBlocking { service.registerProcess(2, "agent-b") }
            val foreignQuestion = runBlocking { service.ask(2, "B's question") }
            mountCallbackRoutes { every { questionsService } returns service }

            val response =
                client.get("/api/v1/callback/questions/${foreignQuestion.id}") {
                    header(HttpHeaders.Authorization, "Bearer $tokenA")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `ask on a terminal process responds 409`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            runBlocking { service.expireForProcess(1) }
            mountCallbackRoutes { every { questionsService } returns service }

            val response =
                client.post("/api/v1/callback/questions") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"too late?"}""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }

    // --- runtime subject tags (POST /api/v1/callback/tags), ADR-0060 ---

    private fun agentWithRuntimeTag(name: String = "agent-a") =
        homer.app.fixtures.AgentFixtures.simpleAgent(name = name).copy(
            tags = homer.app.models.Tags(fromInputs = listOf("mr"), runtime = listOf("decision")),
        )

    @Test
    fun `declare tag without a token responds 401`() =
        testApplication {
            mountCallbackRoutes { every { questionsService } returns newService() }

            val response =
                client.post("/api/v1/callback/tags") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"tag":"decision:approved"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `declare tag stores it against the token's own run and returns the count`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            val deps =
                mountCallbackRoutes {
                    every { questionsService } returns service
                    every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
                    coEvery { processManager.addRuntimeTag(1, "decision:approved", 20, setOf("mr")) } returns
                        homer.app.services.persistence.ProcessRepository
                            .RuntimeTagResult(written = true, count = 1)
                }

            val response =
                client.post("/api/v1/callback/tags") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"tag":"decision:approved"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = apiJson.decodeFromString<homer.app.models.DeclareTagResponse>(response.bodyAsText())
            assertEquals("decision:approved", body.tag)
            assertEquals(1, body.count)
            // The run id comes from the token, not the body — the process id is never in the request.
            // The agent's fromInputs keys are passed through so they are excluded from the ceiling.
            // Resolve the child mock outside the verify lambda: the handler also reads
            // `processManager` for the secret-value check, so a chained verify would count
            // that accessor and over-count.
            val pm = deps.processManager
            coVerify(exactly = 1) { pm.addRuntimeTag(1, "decision:approved", 20, setOf("mr")) }
        }

    @Test
    fun `re-declaring the same tag collapses to one and keeps the count idempotent`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
                // The repository collapses a repeat to the same set: written=true, count unchanged.
                coEvery { processManager.addRuntimeTag(1, "decision:approved", 20, setOf("mr")) } returns
                    homer.app.services.persistence.ProcessRepository
                        .RuntimeTagResult(written = true, count = 1)
            }

            repeat(2) {
                val response =
                    client.post("/api/v1/callback/tags") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"tag":"decision:approved"}""")
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(1, apiJson.decodeFromString<homer.app.models.DeclareTagResponse>(response.bodyAsText()).count)
            }
        }

    @Test
    fun `declare tag on a terminal run responds 409`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            runBlocking { service.expireForProcess(1) }
            mountCallbackRoutes {
                every { questionsService } returns service
                every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
            }

            val response =
                client.post("/api/v1/callback/tags") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"tag":"decision:approved"}""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }

    @Test
    fun `declare tag with an undeclared runtime key responds 400`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
            }

            val response =
                client.post("/api/v1/callback/tags") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"tag":"undeclared:x"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("not a declared tags.runtime key"))
        }

    @Test
    fun `declare tag whose key is a fromInputs name is refused as an override`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
            }

            // `mr` is a fromInputs key, not a runtime key: runtime tags cannot override it.
            val response =
                client.post("/api/v1/callback/tags") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"tag":"mr:15"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("cannot override"))
        }

    @Test
    fun `declare tag with a malformed value responds 400`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
            }

            // A colon in the value breaks the single-colon `key:value` split; charset rejects it.
            val response =
                client.post("/api/v1/callback/tags") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"tag":"decision:has:colon"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    /**
     * The redactor a run registers: built the way `Process` builds it, from the workspace
     * env restricted to the secret names plus the callback token the service minted.
     */
    private fun redactorFor(
        env: Map<String, String>,
        secretEnvNames: Set<String>,
    ): (String) -> SecretRedactor =
        { token ->
            SecretRedactor.from(
                env + (EnvConstants.CALLBACK_TOKEN to token),
                secretEnvNames + EnvConstants.CALLBACK_TOKEN,
                minNeedleLength = TagValidation.SECRET_NEEDLE_MIN_LENGTH,
            )
        }

    private val secretEnv =
        mapOf(
            // A `secret: true` input.
            "TOKEN" to "s3cr3tvalue1",
            // An operator credential forwarded via `@SYSENV:` — never an input, so
            // `inputParams` does not carry it; only the workspace redactor does.
            "API_KEY" to "hostcred-abcdef",
            // A secret below the needle floor: unprotectable by matching.
            "PIN" to "1234",
            "MR_IID" to "15",
        )
    private val secretNames = setOf("TOKEN", "API_KEY", "PIN")

    private suspend fun io.ktor.client.HttpClient.declareTag(
        token: String,
        tag: String,
    ) = post("/api/v1/callback/tags") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody("""{"tag":"$tag"}""")
    }

    private fun assertRefusedAsSecret(
        response: io.ktor.client.statement.HttpResponse,
        deps: homer.app.routes.ApiDeps,
    ) = runBlocking {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("secret"))
        // The write never reached the repository: a refused value must not be persisted.
        // Resolve the child mock outside the verify lambda so the chained accessor is
        // not what gets counted.
        val pm = deps.processManager
        coVerify(exactly = 0) { pm.addRuntimeTag(any(), any(), any(), any()) }
    }

    @Test
    fun `sensitive_data_exposure - declare tag whose value equals a secret input is refused 400`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a", redactorFor(secretEnv, secretNames)) }
            val deps =
                mountCallbackRoutes {
                    every { questionsService } returns service
                    every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
                }

            assertRefusedAsSecret(client.declareTag(token, "decision:s3cr3tvalue1"), deps)
        }

    @Test
    fun `sensitive_data_exposure - declare tag embedding a secret input as a substring is refused 400`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a", redactorFor(secretEnv, secretNames)) }
            val deps =
                mountCallbackRoutes {
                    every { questionsService } returns service
                    every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
                }

            assertRefusedAsSecret(client.declareTag(token, "decision:pre-s3cr3tvalue1-post"), deps)
        }

    @Test
    fun `sensitive_data_exposure - declare tag carrying a SYSENV-sourced credential is refused 400`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a", redactorFor(secretEnv, secretNames)) }
            val deps =
                mountCallbackRoutes {
                    every { questionsService } returns service
                    every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
                }

            assertRefusedAsSecret(client.declareTag(token, "decision:hostcred-abcdef"), deps)
        }

    @Test
    fun `sensitive_data_exposure - declare tag carrying the run's own callback token is refused 400`() =
        testApplication {
            // A published callback token would let any reader of /status/all act as this run.
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a", redactorFor(secretEnv, secretNames)) }
            val deps =
                mountCallbackRoutes {
                    every { questionsService } returns service
                    every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
                }

            assertRefusedAsSecret(client.declareTag(token, "decision:$token"), deps)
        }

    @Test
    fun `a secret shorter than the needle floor does not refuse a tag that contains it`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a", redactorFor(secretEnv, secretNames)) }
            mountCallbackRoutes {
                every { questionsService } returns service
                every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
                coEvery { processManager.addRuntimeTag(1, "decision:1234x", 20, setOf("mr")) } returns
                    homer.app.services.persistence.ProcessRepository
                        .RuntimeTagResult(written = true, count = 1)
            }

            val response = client.declareTag(token, "decision:1234x")

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `declare tag past the per-run ceiling responds 400`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
                coEvery { processManager.addRuntimeTag(1, "decision:approved", 20, setOf("mr")) } returns
                    homer.app.services.persistence.ProcessRepository
                        .RuntimeTagResult(written = false, count = 20)
            }

            val response =
                client.post("/api/v1/callback/tags") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"tag":"decision:approved"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("maximum"))
        }

    @Test
    fun `declare tag with a malformed body responds 400`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
            }

            val response =
                client.post("/api/v1/callback/tags") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"nope":"decision:approved"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `dispatch run without a token responds 401`() =
        testApplication {
            mountCallbackRoutes { every { questionsService } returns newService() }

            val response =
                client.post("/api/v1/callback/runs") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"agent":"target"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `dispatch run returns the new process id`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { dispatchService.dispatchNow(1, "target", mapOf("foo" to "bar")) } returns 99
            }

            val response =
                client.post("/api/v1/callback/runs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"agent":"target","params":{"foo":"bar"}}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(99, apiJson.decodeFromString<CreateRunResponse>(response.bodyAsText()).processId)
        }

    @Test
    fun `dispatch run to a non-dispatchable agent responds 403`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { dispatchService.dispatchNow(any(), any(), any()) } throws AgentNotDispatchableException("target")
            }

            val response =
                client.post("/api/v1/callback/runs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"agent":"target"}""")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `dispatch run to an unknown agent responds 404`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { dispatchService.dispatchNow(any(), any(), any()) } throws DispatchAgentNotFoundException("nope")
            }

            val response =
                client.post("/api/v1/callback/runs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"agent":"nope"}""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `callback runs responds 404 when the nested dispatch hop is unauthorized`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { dispatchService.dispatchNow(any(), any(), any()) } throws DispatchAgentNotFoundException("target")
            }

            val response =
                client.post("/api/v1/callback/runs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"agent":"target"}""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("Dispatch target agent 'target' not found"))
        }

    @Test
    fun `callback joins responds 404 when a sub hop is unauthorized`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { dispatchService.dispatchNow(any(), any(), any()) } throws DispatchAgentNotFoundException("b")
            }

            val response =
                client.post("/api/v1/callback/joins") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"subs":[{"agent":"b","interruptId":"i1"}],"continueAgent":"parent"}""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `callback runs responds 404 for an unauthorized continue agent WITHOUT starting the sub`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            val deps =
                mountCallbackRoutes {
                    every { questionsService } returns service
                    // The pre-start gate (validate) throws BEFORE dispatchNow ever runs — this proves
                    // the check fires early enough that the sub is never started.
                    coEvery {
                        continuationService.validate(any(), any(), any(), any(), any())
                    } throws DispatchAgentNotFoundException("continueTarget")
                }

            val response =
                client.post("/api/v1/callback/runs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"agent":"target","continueAgent":"continueTarget"}""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            coVerify(exactly = 0) { deps.dispatchService.dispatchNow(any(), any(), any()) }
        }

    @Test
    fun `callback joins responds 404 for an unauthorized sub WITHOUT starting any sub`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            val deps =
                mountCallbackRoutes {
                    every { questionsService } returns service
                    // The pre-start gate (validateJoin) throws BEFORE the sub-start loop runs — this
                    // proves the check fires early enough that no sub is ever started.
                    coEvery {
                        continuationService.validateJoin(any(), any(), any(), any(), any(), any())
                    } throws DispatchAgentNotFoundException("b")
                }

            val response =
                client.post("/api/v1/callback/joins") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"subs":[{"agent":"b","interruptId":"i1"}],"continueAgent":"parent"}""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            coVerify(exactly = 0) { deps.dispatchService.dispatchNow(any(), any(), any()) }
        }

    @Test
    fun `dispatch run from a dead origin process responds 409`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { dispatchService.dispatchNow(any(), any(), any()) } throws QuestionsClosedForProcessException(1)
            }

            val response =
                client.post("/api/v1/callback/runs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"agent":"target"}""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }

    @Test
    fun `dispatch run with a malformed body responds 400`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes { every { questionsService } returns service }

            val response =
                client.post("/api/v1/callback/runs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"missing":"agent"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // --- read-by-reference result fetch (GET /api/v1/callback/results/{token}) ---

    private fun record(
        transformer: ResultEnvParam? = null,
        consumerAgent: String = "agent-a",
    ) = ResultTokenRecord(
        token = "tok-1",
        watchedProcessId = 42,
        path = "cmd_0.out",
        resultTransformer = transformer,
        sizeBytes = 3,
        createdAt = 1,
        expiresAt = null,
        consumerAgent = consumerAgent,
    )

    @Test
    fun `result fetch without a callback token responds 401`() =
        testApplication {
            mountCallbackRoutes { every { questionsService } returns newService() }

            val response = client.get("/api/v1/callback/results/tok-1")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `result fetch with a bad or expired result token responds 401`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns null
            }

            val response =
                client.get("/api/v1/callback/results/tok-1") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `result fetch streams the raw artifact when there is no transformer`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns record()
                coEvery { artifactService.resolveArtifactPath(42, "cmd_0.out") } returns Result.success(Path.of("/x/cmd_0.out"))
                every { fileOperations.fileExists(any()) } returns true
                every { artifactService.checkArtifactSize(any()) } returns Result.success(3L)
                coEvery { artifactService.streamArtifact(any(), any()) } answers {
                    secondArg<OutputStream>().write("RAW".toByteArray())
                }
            }

            val response =
                client.get("/api/v1/callback/results/tok-1") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("RAW", response.bodyAsText())
        }

    @Test
    fun `result fetch is reusable within the TTL`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns record()
                coEvery { artifactService.resolveArtifactPath(42, "cmd_0.out") } returns Result.success(Path.of("/x/cmd_0.out"))
                every { fileOperations.fileExists(any()) } returns true
                every { artifactService.checkArtifactSize(any()) } returns Result.success(3L)
                coEvery { artifactService.streamArtifact(any(), any()) } answers {
                    secondArg<OutputStream>().write("RAW".toByteArray())
                }
            }

            repeat(2) {
                val response =
                    client.get("/api/v1/callback/results/tok-1") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("RAW", response.bodyAsText())
            }
        }

    @Test
    fun `result fetch applies the transformer server-side`() =
        testApplication {
            val file = Files.createTempFile("result", ".out").also { it.writeText("""{"summary":"DONE"}""") }
            val transformer = ResultEnvParam(SrcType.STD_OUT, "OUT", ResultTransformer.JsonPath("$.summary"))
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns record(transformer = transformer)
                coEvery { artifactService.resolveArtifactPath(42, "cmd_0.out") } returns Result.success(file)
                every { fileOperations.fileExists(any()) } returns true
                every { artifactService.checkArtifactSize(any()) } returns Result.success(20L)
            }

            val response =
                client.get("/api/v1/callback/results/tok-1") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("DONE", response.bodyAsText())
        }

    @Test
    fun `result fetch with a transformer over the transform cap responds 413 without reading the file`() =
        testApplication {
            val transformer = ResultEnvParam(SrcType.STD_OUT, "OUT", ResultTransformer.JsonPath("$.summary"))
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns record(transformer = transformer)
                coEvery { artifactService.resolveArtifactPath(42, "cmd_0.out") } returns Result.success(Path.of("/x/cmd_0.out"))
                every { fileOperations.fileExists(any()) } returns true
                every { artifactService.checkArtifactSize(any()) } returns Result.success(RESULT_TRANSFORM_MAX_BYTES + 1)
            }

            val response =
                client.get("/api/v1/callback/results/tok-1") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertContains(response.bodyAsText(), "fetch is raw-only")
        }

    @Test
    fun `result fetch whose transformer matches nothing responds an empty body`() =
        testApplication {
            val file = Files.createTempFile("result", ".out").also { it.writeText("""{"other":"x"}""") }
            val transformer = ResultEnvParam(SrcType.STD_OUT, "OUT", ResultTransformer.JsonPath("$.summary"))
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns record(transformer = transformer)
                coEvery { artifactService.resolveArtifactPath(42, "cmd_0.out") } returns Result.success(file)
                every { fileOperations.fileExists(any()) } returns true
                every { artifactService.checkArtifactSize(any()) } returns Result.success(13L)
            }

            val response =
                client.get("/api/v1/callback/results/tok-1") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("", response.bodyAsText())
        }

    @Test
    fun `result fetch by an agent other than the token's audience responds 403`() =
        testApplication {
            // A leaked token presented by a process running a DIFFERENT agent must not
            // redeem it, even though that process holds a valid callback token.
            val service = newService()
            val foreignToken = runBlocking { service.registerProcess(2, "agent-b") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns record(consumerAgent = "agent-a")
            }

            val response =
                client.get("/api/v1/callback/results/tok-1") {
                    header(HttpHeaders.Authorization, "Bearer $foreignToken")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `result fetch by a second process of the same agent still succeeds`() =
        testApplication {
            // A resumed LangGraph segment re-runs the target agent as a NEW process and
            // re-fetches the same token. Audience binding is by agent, not process, so
            // that cross-segment re-fetch must keep working.
            val service = newService()
            val secondSegmentToken = runBlocking { service.registerProcess(2, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns record(consumerAgent = "agent-a")
                coEvery { artifactService.resolveArtifactPath(42, "cmd_0.out") } returns Result.success(Path.of("/x/cmd_0.out"))
                every { fileOperations.fileExists(any()) } returns true
                every { artifactService.checkArtifactSize(any()) } returns Result.success(3L)
                coEvery { artifactService.streamArtifact(any(), any()) } answers {
                    secondArg<OutputStream>().write("RAW".toByteArray())
                }
            }

            val response =
                client.get("/api/v1/callback/results/tok-1") {
                    header(HttpHeaders.Authorization, "Bearer $secondSegmentToken")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("RAW", response.bodyAsText())
        }

    @Test
    fun `result fetch responds 404 when the artifact is gone`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns record()
                coEvery { artifactService.resolveArtifactPath(42, "cmd_0.out") } returns Result.success(Path.of("/x/cmd_0.out"))
                every { fileOperations.fileExists(any()) } returns false
            }

            val response =
                client.get("/api/v1/callback/results/tok-1") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `result fetch responds 413 when the artifact is oversized`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns record()
                coEvery { artifactService.resolveArtifactPath(42, "cmd_0.out") } returns Result.success(Path.of("/x/cmd_0.out"))
                every { fileOperations.fileExists(any()) } returns true
                every { artifactService.checkArtifactSize(any()) } returns
                    Result.failure(ArtifactTooLargeException(size = 999, maxBytes = 10))
            }

            val response =
                client.get("/api/v1/callback/results/tok-1") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }
}
