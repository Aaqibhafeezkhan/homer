package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.CreateAgentResponse
import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.FileContent
import homer.e2e.support.FileList
import homer.e2e.support.HomerApi
import homer.e2e.support.ReloadResult
import homer.e2e.support.SchemaErrorBody
import homer.e2e.support.SseCollector
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentCatalogTest : E2eTestBase() {
    @Test
    fun `agents list contains every fixture agent`() =
        runBlocking {
            val names =
                HomerApi
                    .agents()
                    .agents
                    .map { it.name }
                    .toSet()
            val fixtures =
                setOf(
                    "echo",
                    "fail",
                    "sleeper",
                    "multi-step",
                    "lock-wait",
                    "lock-skip",
                    "lock-kill",
                    "parallel-reject",
                    "inputs",
                    "schema-guard",
                    "artifact-writer",
                    "artifact-name-template",
                    "workspace-clean",
                    "workspace-keep",
                    "asker",
                    "dispatcher",
                    "conditional",
                    "timeout-cmd",
                    "parent",
                    "orphan-parent",
                    "continuer",
                    "continued",
                    "json-emitter",
                    "join-refused",
                    "join-cooldown-sub",
                    "runnercmd-local",
                    "runnercmd-check-fail",
                    "runnercmd-external",
                    "nested-parent",
                    // Nested inside nested-parent's own directory: nesting is layout only.
                    "nested-analysis",
                    "file-links",
                    "langgraph-inspect",
                    "langgraph-too-big",
                )
            assertTrue(names.containsAll(fixtures), "missing fixture agents: ${fixtures - names}")
            val echo = HomerApi.agents().agents.first { it.name == "echo" }
            assertTrue(!echo.description.isNullOrBlank())
        }

    @Test
    fun `agents list serves the group of a grouped fixture and null for an ungrouped one`() =
        runBlocking {
            val agents = HomerApi.agents().agents.associateBy { it.name }

            assertEquals("core", agents.getValue("echo").group, "the echo fixture declares `group: core`")
            assertEquals(null, agents.getValue("fail").group, "a fixture without a group must report null")
        }

    @Test
    fun `agents list omits a secret input's default and values but keeps a non-secret default`() =
        runBlocking {
            val inputs =
                HomerApi
                    .agents()
                    .agents
                    .first { it.name == "inputs" }
                    .inputs
                    .orEmpty()
                    .associateBy { it.paramName }

            val apiKey = assertNotNull(inputs["apiKey"], "the inputs fixture must declare an apiKey param")
            assertTrue(apiKey.secret, "the fixture apiKey param must be secret")
            assertEquals(
                null,
                apiKey.default,
                "a secret input's default must not be served by GET /api/v1/agents",
            )
            assertEquals(
                null,
                apiKey.values,
                "a secret input's values must not be served by GET /api/v1/agents",
            )

            val count = assertNotNull(inputs["count"], "the inputs fixture must declare a count param")
            assertEquals("10", count.default, "a non-secret input's default must still be served")
        }

    @Test
    fun `a nested child agent loads and runs independently of its parent`() =
        runBlocking {
            val parent = HomerApi.startRun("nested-parent")
            assertEquals("FINISHED", Awaits.awaitTerminal(parent).status)
            assertTrue(HomerApi.artifact(parent, "cmd_0.out").bodyAsText().contains("nested-parent-ran"))

            val child = HomerApi.startRun("nested-analysis")
            assertEquals("FINISHED", Awaits.awaitTerminal(child).status)
            assertTrue(HomerApi.artifact(child, "cmd_0.out").bodyAsText().contains("nested-analysis-ran"))
        }

    @Test
    fun `files listing omits a symlink escaping the agent directory and keeps a contained one`() =
        runBlocking {
            // CWE-59: file-links ships inside-link -> notes.txt and outside-link -> ../echo/agent.yaml
            // (cp -a in the compose fixtures-init keeps both as links).
            val listing: FileList = HomerApi.decode(E2e.client.get("/api/v1/agents/file-links/files"))
            val paths = listing.entries.map { it.path }.toSet()

            assertTrue("notes.txt" in paths, "the plain file must be listed; got $paths")
            val notes = listing.entries.first { it.path == "notes.txt" }
            val inside =
                assertNotNull(
                    listing.entries.firstOrNull { it.path == "inside-link" },
                    "a symlink resolving inside the agent directory must stay listed; got $paths",
                )
            assertEquals(notes.size, inside.size, "a contained link reports its checked target's size")
            assertTrue("outside-link" !in paths, "a symlink escaping the agent directory must be omitted; got $paths")
        }

    @Test
    fun `agent JSON schema is served`() =
        runBlocking {
            val response = E2e.client.get("/api/v1/schemas/agent")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"properties\""), "response should be the agent JSON schema")
        }

    @Test
    fun `agent create duplicate and invalid names map to 201 409 400`() =
        runBlocking {
            val name = uniqueAgentName("created")
            val created = createAgent(name)
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals(name, HomerApi.decode<CreateAgentResponse>(created).name)
            // create() writes a template agent.yaml and reloads — visible immediately.
            assertTrue(HomerApi.agents().agents.any { it.name == name })

            assertEquals(HttpStatusCode.Conflict, createAgent("echo").status)
            assertEquals(HttpStatusCode.BadRequest, createAgent("has spaces").status)
        }

    @Test
    fun `files API writes validates deletes and refuses to drop the definition`() =
        runBlocking {
            val name = uniqueAgentName("files")
            assertEquals(HttpStatusCode.Created, createAgent(name).status)

            val listing: FileList = HomerApi.decode(E2e.client.get("/api/v1/agents/$name/files"))
            assertTrue(listing.entries.any { it.path == "agent.yaml" }, "created agent must have a template agent.yaml")

            val template: FileContent = HomerApi.decode(E2e.client.get("/api/v1/agents/$name/files/agent.yaml"))
            assertTrue(template.content.contains(name))

            val invalid = putFile(name, "agent.yaml", "name: $name\ncommands: not-a-list\n")
            assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
            val schemaError = E2e.json.decodeFromString<SchemaErrorBody>(invalid.bodyAsText())
            assertTrue(schemaError.errors.isNotEmpty())

            val valid =
                putFile(
                    name,
                    "agent.yaml",
                    """
                    name: $name
                    description: written by the E2E suite
                    commands:
                      - !<cmd>
                        cmd: [echo, created-agent-ran]
                    """.trimIndent() + "\n",
                )
            assertEquals(HttpStatusCode.OK, valid.status)

            assertEquals(HttpStatusCode.OK, putFile(name, "notes.txt", "scratch\n").status)
            assertEquals(HttpStatusCode.NoContent, E2e.client.delete("/api/v1/agents/$name/files/notes.txt").status)

            val refused = E2e.client.delete("/api/v1/agents/$name/files/agent.yaml")
            assertEquals(HttpStatusCode.Forbidden, refused.status)

            // A definition discovery could never load is refused as 400 before any
            // disk touch; a plain file under the same hidden directory still passes
            // (ADR-0068). The over-deep chain uses the 10-segment file-API cap
            // SERVER.md documents — the server's MAX_PATH_SEGMENTS.
            val overDeep = (1..10).joinToString("/") { "s$it" } + "/agent.yaml"
            val validDef =
                """
                name: $name
                description: written by the E2E suite
                commands:
                  - !<cmd>
                    cmd: [echo, created-agent-ran]
                """.trimIndent() + "\n"
            assertEquals(HttpStatusCode.BadRequest, putFile(name, overDeep, validDef).status)
            // 8 dirs + agent.yaml = 9 segments: under the 10-segment cap, past
            // MAX_DEPTH — refused only by the new definition-reach gate.
            val underCapOverDeep = (1..8).joinToString("/") { "s$it" } + "/agent.yaml"
            assertEquals(HttpStatusCode.BadRequest, putFile(name, underCapOverDeep, validDef).status)
            assertEquals(HttpStatusCode.BadRequest, putFile(name, ".hidden/tool/agent.yaml", validDef).status)
            assertEquals(
                HttpStatusCode.OK,
                putFile(name, ".hidden/tool/notes.txt", "scratch\n").status,
                "the refusal is definition-only: a plain file under a hidden directory is still writable",
            )
        }

    @Test
    fun `reload emits an SSE event and a rewritten agent is runnable`() =
        runBlocking {
            val name = uniqueAgentName("runnable")
            assertEquals(HttpStatusCode.Created, createAgent(name).status)
            val valid =
                putFile(
                    name,
                    "agent.yaml",
                    """
                    name: $name
                    commands:
                      - !<cmd>
                        cmd: [echo, created-agent-ran]
                    """.trimIndent() + "\n",
                )
            assertEquals(HttpStatusCode.OK, valid.status)

            SseCollector.open("/api/v1/stream/agents").use { sse ->
                val reload: ReloadResult = HomerApi.decode(E2e.client.post("/api/v1/agents/reload"))
                assertTrue(reload.errors.isEmpty(), "reload must not report agent load errors: ${reload.errors}")
                sse.awaitEvent("agent.reload after POST /agents/reload") { it.event == "agent.reload" }
            }

            val processId = HomerApi.startRun(name)
            val info = Awaits.awaitTerminal(processId)
            assertEquals("FINISHED", info.status)
            assertTrue(HomerApi.artifact(processId, "cmd_0.out").bodyAsText().contains("created-agent-ran"))
        }

    private var counter = 0

    /** Unique per run: agent dirs persist in the volume between suite runs without `down -v`. */
    private fun uniqueAgentName(prefix: String): String {
        counter += 1
        return "e2e-$prefix-${System.currentTimeMillis()}-$counter"
    }

    private suspend fun createAgent(name: String): HttpResponse =
        E2e.client.post("/api/v1/agents") {
            setBody(TextContent("""{"name":${E2e.json.encodeToString(name)}}""", ContentType.Application.Json))
        }

    private suspend fun putFile(
        agent: String,
        path: String,
        content: String,
    ): HttpResponse =
        E2e.client.put("/api/v1/agents/$agent/files/$path") {
            setBody(TextContent("""{"content":${E2e.json.encodeToString(content)}}""", ContentType.Application.Json))
        }
}
