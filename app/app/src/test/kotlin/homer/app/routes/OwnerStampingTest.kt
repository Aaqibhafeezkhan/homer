package homer.app.routes

import homer.app.apiJson
import homer.app.buildBearerProvider
import homer.app.configureStatusPages
import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.infra.AgentInputResolver
import homer.app.models.Agent
import homer.app.models.AuthConfig
import homer.app.models.ProcessCreationResult
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.models.TokenConfig
import homer.app.services.AgentRunStarter
import homer.app.services.Authorizer
import homer.app.services.ProcessManager
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Task 3 (Plan 2, process ownership): the direct process-creation entry
 * points must stamp the real caller onto the new process -- HTTP execute
 * and debug from the authenticated principal, retry from the RETRYING
 * caller (deliberately NOT the source process's original owner). Cron's
 * `owner = "system:cron"` stamping is covered in CronSchedulerServiceTest.
 *
 * Mounts the production bearer provider so `call.principalName()` is
 * exercised through the real Authentication pipeline rather than asserted
 * against a bare ApplicationCall.
 */
class OwnerStampingTest {
    private fun Application.installBearerAuth(tokenToPrincipal: Map<String, String>) {
        val tokens = tokenToPrincipal.entries.associate { (token, principal) -> principal to TokenConfig(token = token) }
        install(Authentication) { buildBearerProvider(AuthConfig.Bearer(tokens = tokens)) }
        configureStatusPages()
    }

    @Test
    fun `POST agent stamps owner from the authenticated principal`() =
        testApplication {
            val agent = AgentFixtures.simpleAgent(name = "echo")
            val agentInputResolver = AgentInputResolver()
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 1)
            val agentRunStarter =
                AgentRunStarter(
                    serverId = "test-server",
                    processExecutor = mockProcessExecutor,
                    processFactory = mockProcessFactory,
                    telemetryService = TestFixtures.mockTelemetryService(),
                    costMeter = mockk(relaxed = true),
                    maxInvocationDepth = 5,
                    cooldownGate = mockk(relaxed = true),
                    maintenanceGate = mockk(relaxed = true),
                )

            application {
                install(ContentNegotiation) { json(apiJson) }
                installBearerAuth(mapOf("run-token" to "ci"))
                routing {
                    authenticate("auth-bearer") {
                        post("/api/v1/agent/{agentName}") {
                            handleAgentExecution(call, agent, agentInputResolver, agentRunStarter)
                        }
                    }
                }
            }

            val response =
                client.post("/api/v1/agent/echo") {
                    header(HttpHeaders.Authorization, "Bearer run-token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            verify(exactly = 1) {
                mockProcessFactory.create(
                    agent = agent,
                    queryParams = any(),
                    parentContext = any(),
                    invocationChain = listOf("echo"),
                    owner = "ci",
                )
            }
        }

    @Test
    fun `POST agents debug stamps owner from the authenticated principal`() =
        testApplication {
            val agent = AgentFixtures.simpleAgent(name = "debug-agent")
            val agentInputResolver = AgentInputResolver()
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 7)

            application {
                install(ContentNegotiation) { json(apiJson) }
                installBearerAuth(mapOf("debug-token" to "dana"))
                routing {
                    authenticate("auth-bearer") {
                        post("/api/v1/agents/{agentName}/debug") {
                            handleAgentDebug(
                                serverId = "test-server",
                                call = call,
                                agent = agent,
                                agentInputResolver = agentInputResolver,
                                processExecutor = mockProcessExecutor,
                                processFactory = mockProcessFactory,
                                telemetryService = TestFixtures.mockTelemetryService(),
                                maintenanceGate = mockk(relaxed = true),
                            )
                        }
                    }
                }
            }

            val response =
                client.post("/api/v1/agents/debug-agent/debug") {
                    header(HttpHeaders.Authorization, "Bearer debug-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"commandIndex":0,"env":{},"queryParams":{},"headers":{}}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            verify(exactly = 1) {
                mockProcessFactory.create(
                    match<Agent> { it.name == "debug-agent" },
                    emptyMap(),
                    any(),
                    commandIndices = listOf(0),
                    owner = "dana",
                )
            }
        }

    @Test
    fun `POST retry stamps owner from the retrying caller, not the source process owner`() =
        testApplication {
            val agent = AgentFixtures.simpleAgent(name = "deploy")
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FINISHED,
                    agentName = "deploy",
                    inputParams = emptyMap(),
                    owner = "alice",
                )
            val agentInputResolver = AgentInputResolver()
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)

            application {
                install(ContentNegotiation) { json(apiJson) }
                installBearerAuth(mapOf("bob-token" to "bob"))
                routing {
                    authenticate("auth-bearer") {
                        post("/api/v1/processes/{id}/retry") {
                            handleRetry(
                                serverId = "test-server",
                                call = call,
                                sourceProcess = sourceProcess,
                                agent = agent,
                                authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                                processManager = mockk(relaxed = true),
                                processExecutor = mockProcessExecutor,
                                processFactory = mockProcessFactory,
                                telemetryService = TestFixtures.mockTelemetryService(),
                                costMeter = mockk(relaxed = true),
                                agentInputResolver = agentInputResolver,
                                maintenanceGate = mockk(relaxed = true),
                            )
                        }
                    }
                }
            }

            val response =
                client.post("/api/v1/processes/42/retry") {
                    header(HttpHeaders.Authorization, "Bearer bob-token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            verify(exactly = 1) {
                mockProcessFactory.create(agent, emptyMap(), any(), owner = "bob")
            }
        }
}
