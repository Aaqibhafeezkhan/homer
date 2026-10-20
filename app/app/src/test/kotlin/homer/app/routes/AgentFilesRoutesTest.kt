package homer.app.routes

import homer.app.configureStatusPages
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.fixtures.mountApiV1Routes
import homer.app.models.CreateAgentResponse
import homer.app.services.AgentFilesService
import homer.app.services.AgentsService
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.every
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for the agent-files routes. These mount the *real*
 * [apiV1Routes] handler over a real [AgentFilesService] backed by a temp
 * directory, so production routing and the [respondFileOpResult] mapping are
 * what's under test — a regression in either is actually caught. The separate
 * `respondFileOpResult …` cases below probe the helper's mapping in isolation
 * and are complementary, not redundant.
 */
class AgentFilesRoutesTest {
    private lateinit var root: Path
    private lateinit var agentsService: AgentsService
    private lateinit var service: AgentFilesService

    @BeforeEach
    fun setup() {
        root = Files.createTempDirectory("agent-files-routes-test")
        val agentDir = root.resolve("my_agent")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.json"), AgentFixtures.validAgentJson().toByteArray())
        val logger = TestFixtures.mockLogger()
        agentsService = AgentsService(root.toString(), logger)
        service = AgentFilesService(agentsService, logger)
    }

    @AfterEach
    fun teardown() {
        if (::root.isInitialized && Files.exists(root)) {
            root.toFile().walkTopDown().forEach { it.setWritable(true, false) }
            root.toFile().deleteRecursively()
        }
    }

    private fun ApplicationTestBuilder.wireRoutes() {
        val svc = service
        mountApiV1Routes { every { agentFilesService } returns svc }
    }

    @Test
    fun `GET files lists agent directory`() =
        testApplication {
            wireRoutes()
            val res = client.get("/api/v1/agents/test_agent/files")
            assertEquals(HttpStatusCode.OK, res.status)
            assertContains(res.bodyAsText(), "agent.json")
        }

    @Test
    fun `GET files returns 404 for unknown agent`() =
        testApplication {
            wireRoutes()
            val res = client.get("/api/v1/agents/nope/files")
            assertEquals(HttpStatusCode.NotFound, res.status)
        }

    @Test
    fun `GET file returns content`() =
        testApplication {
            wireRoutes()
            val res = client.get("/api/v1/agents/test_agent/files/agent.json")
            assertEquals(HttpStatusCode.OK, res.status)
            assertContains(res.bodyAsText(), "\"content\"")
        }

    @Test
    fun `GET file rejects traversal`() =
        testApplication {
            wireRoutes()
            val res = client.get("/api/v1/agents/test_agent/files/..%2Fsecret.txt")
            assertEquals(HttpStatusCode.BadRequest, res.status)
        }

    @Test
    fun `PUT writes file content`() =
        testApplication {
            wireRoutes()
            val res =
                client.put("/api/v1/agents/test_agent/files/notes.md") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody("""{"content":"hi there"}""")
                }
            assertEquals(HttpStatusCode.OK, res.status)
            assertEquals("hi there", Files.readString(root.resolve("my_agent/notes.md")))
        }

    @Test
    fun `PUT rejects invalid agent yaml with 422`() =
        testApplication {
            wireRoutes()
            val res =
                client.put("/api/v1/agents/test_agent/files/agent.yaml") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody("""{"content":"not: valid"}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, res.status)
            assertContains(res.bodyAsText(), "Agent definition invalid")
        }

    /**
     * ADR-0004: a write racing a permission change is 403 through the real route
     * and [respondFileOpResult] mapping, not an unhandled 500, and the body never
     * carries the filesystem path (§12 rule 7).
     */
    @Test
    fun `PUT to a target made read-only returns 403 without leaking the path (ADR-0004)`() {
        assumeTrue(
            TestFixtures.posixPermissionsEnforced(),
            "POSIX read-only bits not enforced (root or non-POSIX FS); the race cannot be exercised",
        )
        val target = root.resolve("my_agent/notes.txt")
        Files.write(target, "original".toByteArray())
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r--r--r--"))
        try {
            testApplication {
                wireRoutes()
                val res =
                    client.put("/api/v1/agents/test_agent/files/notes.txt") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"content":"nope"}""")
                    }
                assertEquals(HttpStatusCode.Forbidden, res.status)
                val body = res.bodyAsText()
                assertFalse(body.contains(root.toString()), "the 403 body must not leak the filesystem path: $body")
                assertFalse(body.contains("my_agent"), "the 403 body must not leak the agent directory: $body")
            }
        } finally {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"))
        }
    }

    @Test
    fun `DELETE removes file`() =
        testApplication {
            wireRoutes()
            Files.write(root.resolve("my_agent/extra.txt"), "bye".toByteArray())
            val res = client.delete("/api/v1/agents/test_agent/files/extra.txt")
            assertEquals(HttpStatusCode.NoContent, res.status)
            assertTrue(!Files.exists(root.resolve("my_agent/extra.txt")))
        }

    @Test
    fun `DELETE refuses agent-json`() =
        testApplication {
            wireRoutes()
            val res = client.delete("/api/v1/agents/test_agent/files/agent.json")
            assertEquals(HttpStatusCode.Forbidden, res.status)
        }

    @Test
    fun `POST agents creates new agent`() =
        testApplication {
            wireRoutes()
            val res =
                client.post("/api/v1/agents") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody("""{"name":"my_new_one"}""")
                }
            assertEquals(HttpStatusCode.Created, res.status)
            assertTrue(Files.exists(root.resolve("my_new_one/agent.yaml")))
        }

    @Test
    fun `POST agents rejects duplicates`() =
        testApplication {
            wireRoutes()
            val res =
                client.post("/api/v1/agents") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody("""{"name":"test_agent"}""")
                }
            assertEquals(HttpStatusCode.Conflict, res.status)
        }

    // ========== Nested agent boundary through the API (ADR-0047, ADR-0068) ==========

    private fun nestedChild(
        relative: String,
        name: String,
    ) {
        val dir = root.resolve("my_agent/$relative")
        Files.createDirectories(dir)
        val yaml =
            """
            name: $name
            description: nested child
            commands:
              - !<cmd>
                cmd: echo hello
                timeout: 60
            """.trimIndent()
        Files.write(dir.resolve("agent.yaml"), yaml.toByteArray())
        agentsService.reload()
    }

    @Test
    fun `GET of a nested agent definition through its parent is 404`() =
        testApplication {
            wireRoutes()
            nestedChild("nodes/analysis", "analysis")
            val res = client.get("/api/v1/agents/test_agent/files/nodes/analysis/agent.yaml")
            assertEquals(HttpStatusCode.NotFound, res.status)
        }

    @Test
    fun `PUT of a nested agent definition through its parent is 404 and changes nothing`() =
        testApplication {
            wireRoutes()
            nestedChild("nodes/analysis", "analysis")
            val target = root.resolve("my_agent/nodes/analysis/agent.yaml")
            val before = Files.readString(target)

            val res =
                client.put("/api/v1/agents/test_agent/files/nodes/analysis/agent.yaml") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody("""{"content":"name: hijacked\ncommands: []\n"}""")
                }

            assertEquals(HttpStatusCode.NotFound, res.status)
            assertEquals(before, Files.readString(target), "a denied write must not reach the child's definition")
        }

    @Test
    fun `DELETE of a nested agent definition through its parent is 404 and leaves the file`() =
        testApplication {
            wireRoutes()
            nestedChild("nodes/analysis", "analysis")
            val target = root.resolve("my_agent/nodes/analysis/agent.yaml")

            val res = client.delete("/api/v1/agents/test_agent/files/nodes/analysis/agent.yaml")

            assertEquals(HttpStatusCode.NotFound, res.status)
            assertTrue(Files.exists(target), "a denied delete must leave the child's definition in place")
        }

    @Test
    fun `a parent's file listing does not include a nested agent subtree`() =
        testApplication {
            wireRoutes()
            nestedChild("nodes/analysis", "analysis")

            val res = client.get("/api/v1/agents/test_agent/files")

            assertEquals(HttpStatusCode.OK, res.status)
            assertTrue(
                !res.bodyAsText().contains("nodes/analysis"),
                "a parent's listing must not expose a nested agent's subtree: ${res.bodyAsText()}",
            )
        }

    private fun io.ktor.server.application.Application.wireFileOpResultRoute(result: AgentFilesService.FileOpResult<String>) {
        configureStatusPages()
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        routing {
            get("/probe") {
                respondFileOpResult(call, result, schemaInvalidMsg = "schema-invalid-message") { r ->
                    call.respond(HttpStatusCode.Created, CreateAgentResponse(name = r.value))
                }
            }
        }
    }

    @Test
    fun `respondFileOpResult maps Ok via onOk`() =
        testApplication {
            application { wireFileOpResultRoute(AgentFilesService.FileOpResult.Ok("made_it")) }
            val res = client.get("/probe")
            assertEquals(HttpStatusCode.Created, res.status)
            assertContains(res.bodyAsText(), "made_it")
        }

    @Test
    fun `respondFileOpResult maps NotFound to 404 with message`() =
        testApplication {
            application { wireFileOpResultRoute(AgentFilesService.FileOpResult.NotFound("no such thing")) }
            val res = client.get("/probe")
            assertEquals(HttpStatusCode.NotFound, res.status)
            assertContains(res.bodyAsText(), "no such thing")
        }

    @Test
    fun `respondFileOpResult maps BadRequest to 400 with message`() =
        testApplication {
            application { wireFileOpResultRoute(AgentFilesService.FileOpResult.BadRequest("bad input")) }
            val res = client.get("/probe")
            assertEquals(HttpStatusCode.BadRequest, res.status)
            assertContains(res.bodyAsText(), "bad input")
        }

    @Test
    fun `respondFileOpResult maps Forbidden to 403 with message`() =
        testApplication {
            application { wireFileOpResultRoute(AgentFilesService.FileOpResult.Forbidden("read-only")) }
            val res = client.get("/probe")
            assertEquals(HttpStatusCode.Forbidden, res.status)
            assertContains(res.bodyAsText(), "read-only")
        }

    @Test
    fun `respondFileOpResult maps Conflict to 409 with message`() =
        testApplication {
            application { wireFileOpResultRoute(AgentFilesService.FileOpResult.Conflict("already exists")) }
            val res = client.get("/probe")
            assertEquals(HttpStatusCode.Conflict, res.status)
            assertContains(res.bodyAsText(), "already exists")
        }

    @Test
    fun `respondFileOpResult maps SchemaInvalid to 422 with given message and errors`() =
        testApplication {
            application { wireFileOpResultRoute(AgentFilesService.FileOpResult.SchemaInvalid(listOf("err-one", "err-two"))) }
            val res = client.get("/probe")
            assertEquals(HttpStatusCode.UnprocessableEntity, res.status)
            val body = res.bodyAsText()
            assertContains(body, "schema-invalid-message")
            assertContains(body, "err-one")
            assertContains(body, "err-two")
        }
}
