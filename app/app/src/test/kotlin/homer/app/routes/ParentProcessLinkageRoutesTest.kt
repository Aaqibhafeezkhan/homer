package homer.app.routes

import homer.app.EnvConstants
import homer.app.apiJson
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.mountApiV1Routes
import homer.app.models.Action
import homer.app.models.ErrorResponse
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.services.AgentRunStarter
import homer.app.services.AgentsService
import homer.app.services.Authorizer
import homer.app.services.ProcessManager
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Parent/child run linkage at the HTTP boundary (ADR-0058): the
 * `X-Homer-Parent-Process-Id` request header on a run request, the
 * `parentProcessId` / `rootProcessId` filters and the roots-only filter on
 * `GET /status/all`, and the two fields on the status responses. The denial
 * cases pin that an unknown parent and a parent the caller cannot see are
 * indistinguishable (§12: hide existence).
 */
class ParentProcessLinkageRoutesTest {
    private lateinit var processManager: ProcessManager
    private lateinit var agentRunStarter: AgentRunStarter
    private lateinit var agentsService: AgentsService

    private val agent = AgentFixtures.simpleAgent(name = "echo")

    /** A flow root: no parent, and so no root of its own. */
    private val root =
        ProcessInfo(
            id = 7,
            status = ProcessStatus.FINISHED,
            agentName = "root",
            owner = "alice",
        )

    /** A segment of root 7's flow: parented to 3, whose flow root is still 7. */
    private val segment = root.copy(id = 9, parentProcessId = 3, rootProcessId = 7)

    @BeforeEach
    fun setup() {
        processManager = mockk(relaxed = true)
        coEvery { processManager.getProcessInfo(7) } returns root
        coEvery { processManager.getProcessInfo(9) } returns segment
        coEvery { processManager.getProcessInfo(404) } returns null
        coEvery {
            processManager.getAllProcesses(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns
            ProcessManager.ProcessListResult(processes = emptyList(), total = 0)
        agentRunStarter = mockk()
        coEvery { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 42
        agentsService = mockk(relaxed = true)
        every { agentsService.agents } returns mapOf("echo" to agent)
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    private fun ApplicationTestBuilder.mount(authorizer: Authorizer? = null) {
        val pm = processManager
        val starter = agentRunStarter
        val agents = agentsService
        mountApiV1Routes {
            every { processManager } returns pm
            every { agentRunStarter } returns starter
            every { agentsService } returns agents
            if (authorizer != null) every { this@mountApiV1Routes.authorizer } returns authorizer
        }
    }

    private fun expectedBody(msg: String): String = apiJson.encodeToString(ErrorResponse(msg = msg))

    // --- POST /api/v1/agent/{name} + X-Homer-Parent-Process-Id ---

    @Test
    fun `run request without the header starts an unlinked run`() =
        testApplication {
            mount()

            val response = client.post("/api/v1/agent/echo")

            assertEquals(HttpStatusCode.OK, response.status)
            coVerify { agentRunStarter.start(any(), any(), eq("http"), any(), any(), any(), any(), isNull(), any(), any()) }
        }

    @Test
    fun `run request with a visible parent links the run to it`() =
        testApplication {
            mount()

            val response = client.post("/api/v1/agent/echo") { header(EnvConstants.PARENT_PROCESS_HEADER, " 7 ") }

            assertEquals(HttpStatusCode.OK, response.status)
            coVerify { agentRunStarter.start(any(), any(), eq("http"), any(), any(), any(), any(), eq(7), any(), any()) }
        }

    @Test
    fun `run request with an unknown parent is refused with 422 and starts nothing`() =
        testApplication {
            mount()

            val response = client.post("/api/v1/agent/echo") { header(EnvConstants.PARENT_PROCESS_HEADER, "404") }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertEquals(expectedBody("Parent process 404 not found"), response.bodyAsText())
            coVerify(exactly = 0) { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `run request with a parent the caller cannot see gets the same 422 as an unknown one`() =
        testApplication {
            val authorizer =
                mockk<Authorizer> {
                    every { hasAction(any(), Action.RUN, "echo") } returns true
                    every { anyGrant(any(), "echo") } returns true
                    every { canSee(any(), any(), any()) } returns false
                }
            mount(authorizer)

            val response = client.post("/api/v1/agent/echo") { header(EnvConstants.PARENT_PROCESS_HEADER, "7") }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertEquals(expectedBody("Parent process 7 not found"), response.bodyAsText())
            coVerify(exactly = 0) { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `run request with a malformed parent header is a 400`() =
        testApplication {
            mount()

            for (value in listOf("abc", "0", "-3", "1.5")) {
                val response = client.post("/api/v1/agent/echo") { header(EnvConstants.PARENT_PROCESS_HEADER, value) }
                assertEquals(HttpStatusCode.BadRequest, response.status, "value '$value'")
                assertEquals(
                    expectedBody("Invalid ${EnvConstants.PARENT_PROCESS_HEADER} header. Must be a positive integer"),
                    response.bodyAsText(),
                )
            }
            coVerify(exactly = 0) { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    // --- GET /api/v1/status/all?parentProcessId= ---

    @Test
    fun `status all forwards a valid parentProcessId filter`() =
        testApplication {
            mount()

            val response = client.get("/api/v1/status/all?parentProcessId=7")

            assertEquals(HttpStatusCode.OK, response.status)
            coVerify {
                processManager.getAllProcesses(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    parentProcessId = 7,
                    tags = any(),
                    screenTags = any(),
                    canSeeRoot = any(),
                )
            }
        }

    @Test
    fun `status all rejects a non-positive or non-numeric parentProcessId with 400`() =
        testApplication {
            mount()

            for (value in listOf("abc", "0", "-1")) {
                val response = client.get("/api/v1/status/all?parentProcessId=$value")
                assertEquals(HttpStatusCode.BadRequest, response.status, "value '$value'")
                assertEquals(
                    expectedBody("Invalid parentProcessId parameter. Must be a positive integer"),
                    response.bodyAsText(),
                )
            }
            coVerify(exactly = 0) {
                processManager.getAllProcesses(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
        }

    // --- GET /api/v1/status/{id} ---

    @Test
    fun `status response carries the parent id, null at the root`() =
        testApplication {
            coEvery { processManager.getProcessInfo(8) } returns root.copy(id = 8, agentName = "echo", parentProcessId = 7)
            mount()

            val child = Json.parseToJsonElement(client.get("/api/v1/status/8").bodyAsText()).jsonObject
            assertEquals("7", child["parentProcessId"]?.jsonPrimitive?.content)

            val root = Json.parseToJsonElement(client.get("/api/v1/status/7").bodyAsText()).jsonObject
            assertEquals("null", root["parentProcessId"].toString())
        }

    // --- root propagation (ADR-0058) ---

    @Test
    fun `run request with a visible parent inherits that parent's root`() =
        testApplication {
            mount()

            val response = client.post("/api/v1/agent/echo") { header(EnvConstants.PARENT_PROCESS_HEADER, "9") }

            assertEquals(HttpStatusCode.OK, response.status)
            coVerify {
                agentRunStarter.start(
                    any(),
                    any(),
                    eq("http"),
                    any(),
                    any(),
                    any(),
                    any(),
                    eq(9),
                    eq(7),
                    any(),
                )
            }
        }

    @Test
    fun `run request with a parent that is itself a segment reuses the flow root`() =
        testApplication {
            // The parent is not the root row — its own root is. Deriving the root from the
            // parent's `rootProcessId` (not from the parent's id) is what keeps a chain of
            // external hops in one flow.
            coEvery { processManager.getProcessInfo(404) } returns
                root.copy(id = 404, parentProcessId = 9, rootProcessId = 7)
            mount()

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/agent/echo") { header(EnvConstants.PARENT_PROCESS_HEADER, "404") }.status)

            coVerify { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), eq(7), any()) }
        }

    @Test
    fun `run request with a parent that is itself the root links to it as the root`() =
        testApplication {
            mount()

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/agent/echo") { header(EnvConstants.PARENT_PROCESS_HEADER, "7") }.status)

            // A parent with no root of its own IS the root, so the child's root is the parent.
            coVerify { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), eq(7), any()) }
        }

    @Test
    fun `run request whose parent's flow root the caller cannot see is stamped rootless`() =
        testApplication {
            // [information_disclosure / CWE-200] The parent row is visible (the 422 gate
            // passed), but the flow root above it belongs to another principal on an agent
            // this caller holds no grant on. Deriving the root from the parent row would
            // publish that foreign id through the new run, its HMR_ROOT_PROCESS_ID env and
            // its span — the id the status responses withhold. The child is stored rootless
            // instead of mis-rooted.
            val authorizer =
                mockk<Authorizer> {
                    every { hasAction(any(), Action.RUN, "echo") } returns true
                    every { anyGrant(any(), "echo") } returns true
                    // The parent row runs "echo" (visible to the unsecured caller); its
                    // flow root runs "root" (not visible) — otherwise the header would be
                    // refused outright.
                    every { canSee(any(), "carol", "echo") } returns true
                    every { canSee(any(), "alice", "root") } returns false
                }
            // The parent is carol's echo run, itself a segment of alice's flow rooted at 7.
            coEvery { processManager.getProcessInfo(9) } returns
                root.copy(id = 9, agentName = "echo", owner = "carol", parentProcessId = 3, rootProcessId = 7)
            mount(authorizer)

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/agent/echo") { header(EnvConstants.PARENT_PROCESS_HEADER, "9") }.status)

            coVerify {
                agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), isNull(), any())
            }
        }

    // --- GET /api/v1/status/all?rootProcessId= / ?roots= ---

    @Test
    fun `status all forwards a valid rootProcessId filter`() =
        testApplication {
            mount()

            val response = client.get("/api/v1/status/all?rootProcessId=7")

            assertEquals(HttpStatusCode.OK, response.status)
            coVerify {
                processManager.getAllProcesses(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    rootProcessId = 7,
                    rootsOnly = false,
                    tags = any(),
                    screenTags = any(),
                    canSeeRoot = any(),
                )
            }
        }

    @Test
    fun `status all rejects a non-positive or non-numeric rootProcessId with 400`() =
        testApplication {
            mount()

            for (value in listOf("abc", "0", "-1")) {
                val response = client.get("/api/v1/status/all?rootProcessId=$value")
                assertEquals(HttpStatusCode.BadRequest, response.status, "value '$value'")
                assertEquals(
                    expectedBody("Invalid rootProcessId parameter. Must be a positive integer"),
                    response.bodyAsText(),
                )
            }
            coVerify(exactly = 0) {
                processManager.getAllProcesses(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `status all forwards the roots-only filter`() =
        testApplication {
            mount()

            val response = client.get("/api/v1/status/all?roots=true")

            assertEquals(HttpStatusCode.OK, response.status)
            coVerify {
                processManager.getAllProcesses(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    rootProcessId = null,
                    rootsOnly = true,
                    tags = any(),
                    screenTags = any(),
                    canSeeRoot = any(),
                )
            }
        }

    @Test
    fun `status response carries the root id, null at the root`() =
        testApplication {
            coEvery { processManager.getProcessInfo(8) } returns
                root.copy(id = 8, agentName = "echo", parentProcessId = 3, rootProcessId = 7)
            mount()

            val descendant = Json.parseToJsonElement(client.get("/api/v1/status/8").bodyAsText()).jsonObject
            assertEquals("7", descendant["rootProcessId"]?.jsonPrimitive?.content)

            val rootBody = Json.parseToJsonElement(client.get("/api/v1/status/7").bodyAsText()).jsonObject
            assertEquals("null", rootBody["rootProcessId"].toString())
        }
}
