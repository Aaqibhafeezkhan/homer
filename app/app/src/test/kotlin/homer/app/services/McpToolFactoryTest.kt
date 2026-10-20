package homer.app.services

import homer.app.exceptions.QueryParamValidationException
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.infra.AgentInputResolver
import homer.app.infra.LocalFileOperations
import homer.app.models.Action
import homer.app.models.AgentMcpConfig
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.ExecutionInfo
import homer.app.models.Grant
import homer.app.models.McpServerConfig
import homer.app.models.Param
import homer.app.models.ParamSource
import homer.app.models.ParamType
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.models.Server
import homer.app.models.TokenConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpToolFactoryTest {
    private lateinit var agentsService: AgentsService
    private lateinit var agentRunStarter: AgentRunStarter
    private lateinit var processManager: ProcessManager
    private lateinit var artifactService: ArtifactService
    private lateinit var broadcaster: ProcessEventBroadcaster
    private var resolvedInputs: Map<String, String>? = null

    @BeforeEach
    fun setup() {
        agentsService = mockk(relaxed = true)
        every { agentsService.agents } returns emptyMap()
        agentRunStarter = mockk()
        coEvery { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            resolvedInputs = arg<suspend () -> Map<String, String>>(9).invoke()
            RUN_ID
        }
        processManager = mockk(relaxed = true)
        artifactService = mockk()
        broadcaster = ProcessEventBroadcaster()
        resolvedInputs = null
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    private fun factory(
        config: McpServerConfig = McpServerConfig(enabled = true),
        authorizer: Authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
    ) = McpToolFactory(
        agentsService = agentsService,
        authorizer = authorizer,
        agentInputResolver = AgentInputResolver(),
        agentRunStarter = agentRunStarter,
        processManager = processManager,
        artifactService = artifactService,
        processEventBroadcaster = broadcaster,
        mcpConfig = config,
        logger = TestFixtures.mockLogger(),
    )

    private fun request(arguments: JsonObject? = null) = CallToolRequest(CallToolRequestParams(name = "test", arguments = arguments))

    private fun firstText(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): String =
        (result.content.first() as TextContent).text

    // ========== toolSchemaFor ==========

    @Test
    fun `toolSchemaFor maps query and body params, excludes headers, injects _wait`() {
        val agent =
            AgentFixtures.simpleAgent(name = "schema_agent").copy(
                inputs =
                    listOf(
                        Param(paramName = "q", type = ParamType.STRING, src = ParamSource.QUERY),
                        Param(paramName = "n", type = ParamType.NUMBER, src = ParamSource.BODY, required = false),
                        Param(paramName = "secret", src = ParamSource.HEADER),
                    ),
            )

        val schema = factory().toolSchemaFor(agent)

        val properties = assertNotNull(schema.properties)
        assertEquals(setOf("q", "n", "_wait"), properties.keys)
        assertEquals("string", ((properties["q"] as JsonObject)["type"] as JsonPrimitive).content)
        assertEquals("number", ((properties["n"] as JsonObject)["type"] as JsonPrimitive).content)
        assertEquals(listOf("q"), schema.required)
    }

    @Test
    fun `toolSchemaFor merges inputSchema properties and required`() {
        val inputSchema =
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("service") { put("type", "string") }
                }
                putJsonArray("required") { add(JsonPrimitive("service")) }
            }
        val agent = AgentFixtures.simpleAgent(name = "schema_merge").copy(inputSchema = inputSchema)

        val schema = factory().toolSchemaFor(agent)

        val properties = assertNotNull(schema.properties)
        assertEquals(setOf("service", "_wait"), properties.keys)
        assertEquals(listOf("service"), schema.required)
    }

    @Test
    fun `typed params map to real JSON schema types and constraints`() {
        val agent =
            AgentFixtures.simpleAgent(name = "typed_schema").copy(
                inputs =
                    listOf(
                        Param(
                            paramName = "environment",
                            type = ParamType.STR,
                            values = listOf("dev", "staging", "prod"),
                        ),
                        Param(
                            paramName = "count",
                            type = ParamType.INT,
                            min = 1.0,
                            max = 100.0,
                            required = false,
                            default = "10",
                        ),
                        Param(paramName = "ratio", type = ParamType.NUM, required = false),
                        Param(paramName = "dryRun", type = ParamType.BOOL, required = false),
                        Param(paramName = "ticket", type = ParamType.STR, pattern = "^[A-Z]+-[0-9]+$", required = false),
                    ),
            )
        val schema = factory().toolSchemaFor(agent)
        val props = assertNotNull(schema.properties)

        val env = props["environment"]!!.jsonObject
        assertEquals("string", env["type"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("dev", "staging", "prod"),
            env["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )

        val count = props["count"]!!.jsonObject
        assertEquals("integer", count["type"]!!.jsonPrimitive.content)
        assertEquals(1L, count["minimum"]!!.jsonPrimitive.long)
        assertEquals(100L, count["maximum"]!!.jsonPrimitive.long)
        assertEquals(10L, count["default"]!!.jsonPrimitive.long)

        assertEquals("number", props["ratio"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("boolean", props["dryRun"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "^[A-Z]+-[0-9]+$",
            props["ticket"]!!.jsonObject["pattern"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `tool schema drops a secret input's default and values on every typed arm`() {
        // Any run-grant holder reads this schema. See ADR-0071.
        val agent =
            AgentFixtures.simpleAgent(name = "secret_schema").copy(
                inputs =
                    listOf(
                        Param(
                            paramName = "token",
                            type = ParamType.STR,
                            required = false,
                            secret = true,
                            values = listOf("abc", "def"),
                            default = "super-secret-default",
                        ),
                        Param(
                            paramName = "pin",
                            type = ParamType.INT,
                            required = false,
                            secret = true,
                            values = listOf("1234", "5678"),
                            default = "1234",
                        ),
                        Param(
                            paramName = "ratio",
                            type = ParamType.NUM,
                            required = false,
                            secret = true,
                            values = listOf("0.25", "0.5"),
                            default = "0.25",
                        ),
                        Param(
                            paramName = "count",
                            type = ParamType.INT,
                            required = false,
                            default = "10",
                        ),
                    ),
            )
        val props = assertNotNull(factory().toolSchemaFor(agent).properties)

        for (name in listOf("token", "pin", "ratio")) {
            val schema = props[name]!!.jsonObject
            assertFalse(schema.containsKey("default"), "secret $name default must not appear in the tool schema")
            assertFalse(schema.containsKey("enum"), "secret $name values must not appear as the tool schema's enum")
        }
        assertEquals(10L, props["count"]!!.jsonObject["default"]!!.jsonPrimitive.long)
    }

    @Test
    fun `tool schema still serves a non-secret integer input's values as enum`() {
        // The string arm's enum is covered by the typed-schema case above.
        val agent =
            AgentFixtures.simpleAgent(name = "enum_schema").copy(
                inputs =
                    listOf(
                        Param(
                            paramName = "level",
                            type = ParamType.INT,
                            required = false,
                            values = listOf("1", "2"),
                        ),
                    ),
            )
        val props = assertNotNull(factory().toolSchemaFor(agent).properties)

        assertEquals(
            listOf(1L, 2L),
            props["level"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.long },
        )
    }

    @Test
    fun `legacy param types keep the current schema mapping`() {
        val agent =
            AgentFixtures.simpleAgent(name = "legacy_schema").copy(
                inputs =
                    listOf(
                        Param(paramName = "n", type = ParamType.NUMBER),
                        Param(paramName = "s", type = ParamType.STRING, required = false),
                    ),
            )
        val props = assertNotNull(factory().toolSchemaFor(agent).properties)
        assertEquals("number", props["n"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("string", props["s"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `boolean default with non-lowercase casing maps to a schema default without throwing`() {
        val agent =
            AgentFixtures.simpleAgent(name = "bool_casing").copy(
                inputs =
                    listOf(
                        Param(paramName = "dryRun", type = ParamType.BOOL, required = false, default = "True"),
                    ),
            )
        val props = assertNotNull(factory().toolSchemaFor(agent).properties)
        val dryRun = props["dryRun"]!!.jsonObject
        assertEquals("boolean", dryRun["type"]!!.jsonPrimitive.content)
        assertEquals(true, dryRun["default"]!!.jsonPrimitive.boolean)
    }

    // ========== mcpAgents ==========

    @Test
    fun `mcpAgents exposes only opted-in agents and skips _wait collisions and bad names`() {
        val optedIn = AgentFixtures.simpleAgent(name = "good").copy(mcp = AgentMcpConfig(enabled = true))
        val optedOut = AgentFixtures.simpleAgent(name = "not_exposed")
        val collision =
            AgentFixtures.simpleAgent(name = "collides").copy(
                mcp = AgentMcpConfig(enabled = true),
                inputs = listOf(Param(paramName = "_wait")),
            )
        val badName =
            AgentFixtures.simpleAgent(name = "x".repeat(129)).copy(mcp = AgentMcpConfig(enabled = true))
        every { agentsService.agents } returns
            listOf(optedIn, optedOut, collision, badName).associateBy { it.name }

        val exposed = factory().mcpAgents()

        assertEquals(listOf("good"), exposed.map { it.name })
    }

    @Test
    fun `agent with required header input is not exposed over MCP`() {
        val agent =
            AgentFixtures.simpleAgent(name = "req_header").copy(
                inputs =
                    listOf(
                        Param(paramName = "X-Signature", envName = "SIGNATURE", src = ParamSource.HEADER, required = true),
                    ),
                mcp = AgentMcpConfig(enabled = true),
            )
        every { agentsService.agents } returns mapOf(agent.name to agent)

        val exposed = factory().mcpAgents()

        assertTrue(exposed.isEmpty())
    }

    @Test
    fun `agent with optional header input is still exposed`() {
        val agent =
            AgentFixtures.simpleAgent(name = "opt_header").copy(
                inputs =
                    listOf(
                        Param(paramName = "X-Trace", envName = "TRACE", src = ParamSource.HEADER, required = false),
                    ),
                mcp = AgentMcpConfig(enabled = true),
            )
        every { agentsService.agents } returns mapOf(agent.name to agent)

        val exposed = factory().mcpAgents()

        assertEquals(1, exposed.size)
    }

    // ========== classifyToolArgs ==========

    @Test
    fun `classifyToolArgs routes query params to query, others to body, and drops _wait`() {
        val agent =
            AgentFixtures.simpleAgent(name = "classify").copy(
                inputs =
                    listOf(
                        Param(paramName = "q", src = ParamSource.QUERY),
                        Param(paramName = "b", src = ParamSource.BODY),
                    ),
            )

        val classified =
            factory().classifyToolArgs(
                agent,
                buildJsonObject {
                    put("q", "query-value")
                    put("b", "body-value")
                    put("extra", "extra-value")
                    put("_wait", 60)
                },
            )

        assertEquals("query-value", classified.queryParams["q"])
        assertNull(classified.queryParams["b"])
        assertNull(classified.queryParams["_wait"])
        val body = assertNotNull(classified.bodyJson)
        assertEquals(setOf("b", "extra"), body.keys)
        assertEquals("body-value", (body["b"] as JsonPrimitive).content)
        assertEquals("extra-value", (body["extra"] as JsonPrimitive).content)
    }

    @Test
    fun `classifyToolArgs returns null body when no body fields and no inputSchema`() {
        val agent =
            AgentFixtures.simpleAgent(name = "query_only").copy(
                inputs = listOf(Param(paramName = "q", src = ParamSource.QUERY)),
            )

        val classified =
            factory().classifyToolArgs(agent, buildJsonObject { put("q", "v") })

        assertNull(classified.bodyJson)
        assertEquals("v", classified.queryParams["q"])
    }

    @Test
    fun `classifyToolArgs returns empty body object when agent has inputSchema but no body fields`() {
        val inputSchema =
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("service") { put("type", "string") }
                }
            }
        val agent =
            AgentFixtures.simpleAgent(name = "schema_only").copy(
                inputs = listOf(Param(paramName = "q", src = ParamSource.QUERY)),
                inputSchema = inputSchema,
            )

        val classified =
            factory().classifyToolArgs(agent, buildJsonObject { put("q", "v") })

        val body = assertNotNull(classified.bodyJson)
        assertTrue(body.isEmpty())
    }

    // ========== callAgentTool ==========

    @Test
    fun `callAgentTool routes args to query vs body and returns runId immediately`() =
        runTest {
            val agent =
                AgentFixtures.simpleAgent(name = "router").copy(
                    inputs =
                        listOf(
                            Param(paramName = "q", envName = "Q_ENV", src = ParamSource.QUERY),
                            Param(paramName = "b", envName = "B_ENV", src = ParamSource.BODY),
                        ),
                )
            coEvery { processManager.getProcessStatus(RUN_ID) } returns ProcessStatus.WORKING

            val result =
                factory().callAgentTool(
                    agent,
                    request(
                        buildJsonObject {
                            put("q", "query-value")
                            put("b", "body-value")
                        },
                    ),
                    null,
                )

            assertNull(result.isError)
            assertEquals(mapOf("Q_ENV" to "query-value", "B_ENV" to "body-value"), resolvedInputs)
            val structured = assertNotNull(result.structuredContent)
            assertEquals(RUN_ID, (structured["runId"] as JsonPrimitive).content.toInt())
            assertEquals("WORKING", (structured["status"] as JsonPrimitive).content)
            coVerify {
                agentRunStarter.start(agent, emptyList(), "mcp", any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `callAgentTool stamps the MCP caller principal as the run owner`() =
        runTest {
            val agent = AgentFixtures.simpleAgent(name = "owned")
            coEvery { processManager.getProcessStatus(RUN_ID) } returns ProcessStatus.WORKING

            val result = factory().callAgentTool(agent, request(), "runner")

            assertNull(result.isError)
            coVerify {
                agentRunStarter.start(agent, emptyList(), "mcp", any(), any(), "runner", any(), any(), any(), any())
            }
        }

    @Test
    fun `callAgentTool returns isError when a required input is missing`() =
        runTest {
            val agent =
                AgentFixtures.simpleAgent(name = "strict").copy(
                    inputs = listOf(Param(paramName = "must", src = ParamSource.QUERY, required = true)),
                )

            val result = factory().callAgentTool(agent, request(), null)

            assertEquals(true, result.isError)
            assertTrue(firstText(result).contains("must"), "error text should name the missing param")
            assertTrue(
                firstText(result).contains(QueryParamValidationException::class.simpleName!!),
                "error text should carry the exception name",
            )
        }

    @Test
    fun `callAgentTool with _wait returns final status after terminal event`() =
        runTest {
            val agent = AgentFixtures.simpleAgent(name = "waiter")
            var status = ProcessStatus.WORKING
            coEvery { processManager.getProcessStatus(RUN_ID) } answers { status }
            coEvery { processManager.getProcessInfo(RUN_ID) } answers {
                ProcessInfo(
                    id = RUN_ID,
                    status = status,
                    agentName = agent.name,
                    costUsd = 1.25,
                )
            }
            coEvery { artifactService.listArtifacts(RUN_ID) } returns
                Result.success(
                    homer.app.models.ArtifactListingResponse(
                        processId = RUN_ID,
                        artifactsDir = "/tmp/a",
                        files = listOf(homer.app.models.ArtifactEntry(path = "out.txt", size = 7, modified = 0)),
                    ),
                )

            val deferred =
                async {
                    factory().callAgentTool(
                        agent,
                        request(buildJsonObject { put("_wait", 60) }),
                        null,
                    )
                }
            testScheduler.runCurrent()
            status = ProcessStatus.FINISHED
            broadcaster.emit(
                ProcessEvent.StatusChanged(RUN_ID, ProcessStatus.FINISHED, agent.name),
            )
            val result = deferred.await()

            assertNull(result.isError)
            val structured = assertNotNull(result.structuredContent)
            assertEquals("FINISHED", (structured["status"] as JsonPrimitive).content)
            assertEquals(1.25, (structured["costUsd"] as JsonPrimitive).content.toDouble())
            assertEquals(1, (structured["artifacts"] as JsonArray).size)
        }

    @Test
    fun `callAgentTool with _wait falls back to polling when the event is missed`() =
        runTest {
            val agent = AgentFixtures.simpleAgent(name = "poller")
            var status = ProcessStatus.WORKING
            coEvery { processManager.getProcessStatus(RUN_ID) } answers { status }
            coEvery { processManager.getProcessInfo(RUN_ID) } answers {
                ProcessInfo(id = RUN_ID, status = status, agentName = agent.name)
            }
            coEvery { artifactService.listArtifacts(RUN_ID) } returns Result.failure(IllegalArgumentException("none"))

            val deferred =
                async {
                    factory().callAgentTool(
                        agent,
                        request(buildJsonObject { put("_wait", 60) }),
                        null,
                    )
                }
            testScheduler.runCurrent()
            // No broadcaster event — only the 2 s poll fallback can observe this.
            status = ProcessStatus.FINISHED
            val result = deferred.await()

            assertNull(result.isError)
            val structured = assertNotNull(result.structuredContent)
            assertEquals("FINISHED", (structured["status"] as JsonPrimitive).content)
        }

    @Test
    fun `callAgentTool with _wait times out and reports the run still working`() =
        runTest {
            val agent = AgentFixtures.simpleAgent(name = "slow")
            coEvery { processManager.getProcessStatus(RUN_ID) } returns ProcessStatus.WORKING
            coEvery { processManager.getProcessInfo(RUN_ID) } returns
                ProcessInfo(
                    id = RUN_ID,
                    status = ProcessStatus.WORKING,
                    agentName = agent.name,
                )
            coEvery { artifactService.listArtifacts(RUN_ID) } returns Result.failure(IllegalArgumentException("none"))

            val result =
                factory().callAgentTool(
                    agent,
                    request(buildJsonObject { put("_wait", 5) }),
                    null,
                )

            assertNull(result.isError)
            val structured = assertNotNull(result.structuredContent)
            assertEquals("WORKING", (structured["status"] as JsonPrimitive).content)
            assertTrue(firstText(result).contains("still"), "text should say the run is still running")
        }

    // ========== management tools ==========

    @Test
    fun `get_run_info returns isError for unknown runId`() =
        runTest {
            coEvery { processManager.getProcessInfo(999) } returns null

            val result = factory().runTools.getRunInfo(request(buildJsonObject { put("runId", 999) }), null)

            assertEquals(true, result.isError)
            assertTrue(firstText(result).contains("999"))
        }

    @Test
    fun `get_run_info returns isError when runId is missing`() =
        runTest {
            val result = factory().runTools.getRunInfo(request(), null)

            assertEquals(true, result.isError)
            assertTrue(firstText(result).contains("runId"))
        }

    @Test
    fun `get_run_info returns status and cost`() =
        runTest {
            coEvery { processManager.getProcessInfo(RUN_ID) } returns
                ProcessInfo(
                    id = RUN_ID,
                    status = ProcessStatus.FINISHED,
                    agentName = "an_agent",
                    costUsd = 0.5,
                    executions =
                        listOf(
                            ExecutionInfo(
                                label = "cmd",
                                start = 0,
                                end = 1,
                                resultCode = 0,
                                stdOut = null,
                                stdErr = null,
                                errMsg = null,
                            ),
                        ),
                )

            val result = factory().runTools.getRunInfo(request(buildJsonObject { put("runId", RUN_ID) }), null)

            assertNull(result.isError)
            val structured = assertNotNull(result.structuredContent)
            assertEquals("FINISHED", (structured["status"] as JsonPrimitive).content)
            assertEquals(1, (structured["commands"] as JsonArray).size)
        }

    @Test
    fun `cancel_run kills a known run`() =
        runTest {
            coEvery { processManager.getProcessInfo(RUN_ID) } returns
                ProcessInfo(
                    id = RUN_ID,
                    status = ProcessStatus.WORKING,
                    agentName = "an_agent",
                )
            coEvery { processManager.getProcessStatus(RUN_ID) } returns ProcessStatus.KILLED

            val result = factory().runTools.cancelRun(request(buildJsonObject { put("runId", RUN_ID) }), null)

            assertNull(result.isError)
            coVerify { processManager.killProcess(RUN_ID, any()) }
            assertEquals(
                "KILLED",
                ((result.structuredContent!!["status"]) as JsonPrimitive).content,
            )
        }

    // ========== management tools: visibility gate ==========

    /** "bob" holds READ on "other" only — no grant on "deploy", and isn't the run's owner. */
    private fun deniedReadAuthorizer(): Authorizer =
        Authorizer(
            mapOf("bob" to Authorizer.PrincipalGrants(admin = false, grants = listOf(Grant(listOf("other"), listOf(Action.READ))))),
            emptyMap(),
            enabled = true,
        )

    /** "bob" holds READ (but not CONTROL) on "deploy" — visible, but not controllable. */
    private fun readOnlyAuthorizer(): Authorizer =
        Authorizer(
            mapOf("bob" to Authorizer.PrincipalGrants(admin = false, grants = listOf(Grant(listOf("deploy"), listOf(Action.READ))))),
            emptyMap(),
            enabled = true,
        )

    @Test
    fun `get_run_info returns not-found for a run the principal cannot see`() =
        runTest {
            coEvery { processManager.getProcessInfo(RUN_ID) } returns
                ProcessInfo(
                    id = RUN_ID,
                    status = ProcessStatus.WORKING,
                    agentName = "deploy",
                    owner = "alice",
                )

            val result =
                factory(
                    authorizer = deniedReadAuthorizer(),
                ).runTools.getRunInfo(request(buildJsonObject { put("runId", RUN_ID) }), "bob")

            assertEquals(true, result.isError)
            assertTrue(firstText(result).contains("not found"))
        }

    @Test
    fun `get_run_info succeeds for the run owner`() =
        runTest {
            coEvery { processManager.getProcessInfo(RUN_ID) } returns
                ProcessInfo(
                    id = RUN_ID,
                    status = ProcessStatus.WORKING,
                    agentName = "deploy",
                    owner = "alice",
                )

            val result =
                factory(
                    authorizer = deniedReadAuthorizer(),
                ).runTools.getRunInfo(request(buildJsonObject { put("runId", RUN_ID) }), "alice")

            assertNull(result.isError)
        }

    @Test
    fun `list_artifacts returns not-found for a run the principal cannot see`() =
        runTest {
            coEvery { processManager.getProcessInfo(RUN_ID) } returns
                ProcessInfo(
                    id = RUN_ID,
                    status = ProcessStatus.WORKING,
                    agentName = "deploy",
                    owner = "alice",
                )

            val result =
                factory(
                    authorizer = deniedReadAuthorizer(),
                ).runTools.listArtifacts(request(buildJsonObject { put("runId", RUN_ID) }), "bob")

            assertEquals(true, result.isError)
            assertTrue(firstText(result).contains("not found"))
            coVerify(exactly = 0) { artifactService.listArtifacts(any()) }
        }

    @Test
    fun `get_artifact returns not-found for a run the principal cannot see`() =
        runTest {
            coEvery { processManager.getProcessInfo(RUN_ID) } returns
                ProcessInfo(
                    id = RUN_ID,
                    status = ProcessStatus.WORKING,
                    agentName = "deploy",
                    owner = "alice",
                )

            val result =
                factory(authorizer = deniedReadAuthorizer()).runTools.getArtifact(
                    request(
                        buildJsonObject {
                            put("runId", RUN_ID)
                            put("path", "out.txt")
                        },
                    ),
                    "bob",
                )

            assertEquals(true, result.isError)
            assertTrue(firstText(result).contains("not found"))
        }

    @Test
    fun `cancel_run is denied without control on the run`() =
        runTest {
            coEvery { processManager.getProcessInfo(RUN_ID) } returns
                ProcessInfo(
                    id = RUN_ID,
                    status = ProcessStatus.WORKING,
                    agentName = "deploy",
                    owner = "alice",
                )

            val result =
                factory(
                    authorizer = readOnlyAuthorizer(),
                ).runTools.cancelRun(request(buildJsonObject { put("runId", RUN_ID) }), "bob")

            assertEquals(true, result.isError)
            assertTrue(firstText(result).contains("not found"))
            coVerify(exactly = 0) { processManager.killProcess(any(), any()) }
        }

    // ========== get_artifact ==========

    private fun artifactSetup(content: ByteArray): Path {
        val dir = Files.createTempDirectory("mcp-artifact-test")
        Files.write(dir.resolve("out.txt"), content)
        coEvery { processManager.getProcessInfo(RUN_ID) } returns
            ProcessInfo(
                id = RUN_ID,
                status = ProcessStatus.FINISHED,
                agentName = "an_agent",
                artifactsDir = dir,
            )
        artifactService =
            ArtifactService(
                processManager = processManager,
                fileOperations = LocalFileOperations(),
                logger = TestFixtures.mockLogger(),
            )
        return dir
    }

    @Test
    fun `get_artifact inlines small text artifacts completely`() =
        runTest {
            val dir = artifactSetup("hello artifact".toByteArray())
            try {
                val result =
                    factory().runTools.getArtifact(
                        request(
                            buildJsonObject {
                                put("runId", RUN_ID)
                                put("path", "out.txt")
                            },
                        ),
                        null,
                    )

                assertNull(result.isError)
                assertTrue(firstText(result).contains("hello artifact"))
                val structured = assertNotNull(result.structuredContent)
                assertFalse((structured["truncated"] as JsonPrimitive).content.toBoolean())
                // Clients that prefer structuredContent over the text block
                // must see the artifact body too, not just metadata.
                assertEquals("hello artifact", (structured["content"] as JsonPrimitive).content)
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test
    fun `get_artifact truncates artifacts above the inline cap`() =
        runTest {
            val dir = artifactSetup("A".repeat(100).toByteArray())
            try {
                val result =
                    factory(McpServerConfig(enabled = true, maxInlineArtifactBytes = 16)).runTools.getArtifact(
                        request(
                            buildJsonObject {
                                put("runId", RUN_ID)
                                put("path", "out.txt")
                            },
                        ),
                        null,
                    )

                assertNull(result.isError)
                val structured = assertNotNull(result.structuredContent)
                assertTrue((structured["truncated"] as JsonPrimitive).content.toBoolean())
                assertEquals(100, (structured["totalSize"] as JsonPrimitive).content.toInt())
                assertTrue(firstText(result).endsWith("A".repeat(16)))
                assertEquals("A".repeat(16), (structured["content"] as JsonPrimitive).content)
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test
    fun `get_artifact rejects binary artifacts`() =
        runTest {
            val dir = artifactSetup(byteArrayOf(0x68, 0x69, 0x00, 0x21))
            try {
                val result =
                    factory().runTools.getArtifact(
                        request(
                            buildJsonObject {
                                put("runId", RUN_ID)
                                put("path", "out.txt")
                            },
                        ),
                        null,
                    )

                assertEquals(true, result.isError)
                assertTrue(firstText(result).contains("binary"))
                assertTrue(firstText(result).contains("/api/v1/artifacts"))
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test
    fun `get_artifact rejects path traversal`() =
        runTest {
            val dir = artifactSetup("safe".toByteArray())
            try {
                val result =
                    factory().runTools.getArtifact(
                        request(
                            buildJsonObject {
                                put("runId", RUN_ID)
                                put("path", "../escape.txt")
                            },
                        ),
                        null,
                    )

                assertEquals(true, result.isError)
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    // ========== buildServer ==========

    private val managementToolNames = setOf("get_run_info", "list_artifacts", "get_artifact", "cancel_run")

    @Test
    fun `buildServer registers agent tools and management tools`() {
        val optedIn =
            AgentFixtures
                .simpleAgent(name = "exposed", description = "An exposed agent")
                .copy(mcp = AgentMcpConfig(enabled = true))
        every { agentsService.agents } returns mapOf("exposed" to optedIn)

        // addTools throws if the tools capability is missing; reaching here
        // with both tool sets registered is the assertion.
        factory().buildServer(null)
    }

    @Test
    fun `buildServer filters agent tools to those the principal holds a RUN grant for`() {
        val allowed = AgentFixtures.simpleAgent(name = "allowed").copy(mcp = AgentMcpConfig(enabled = true))
        val denied = AgentFixtures.simpleAgent(name = "denied").copy(mcp = AgentMcpConfig(enabled = true))
        every { agentsService.agents } returns mapOf("allowed" to allowed, "denied" to denied)
        val authorizer =
            Authorizer.from(
                AppConfig(
                    server =
                        Server(
                            apiAuth =
                                AuthConfig.Bearer(
                                    tokens =
                                        mapOf(
                                            "runner" to
                                                TokenConfig(
                                                    token = "t",
                                                    grants = listOf(Grant(listOf("allowed"), listOf(Action.RUN))),
                                                ),
                                        ),
                                ),
                        ),
                    agentsSearchPath = "unused",
                    workspaceRootPath = "unused",
                    databasePath = "unused",
                ),
            )

        val server = factory(authorizer = authorizer).buildServer("runner")

        assertEquals(setOf("allowed") + managementToolNames, server.tools.keys)
    }

    @Test
    fun `buildServer keeps all opted-in agent tools when the authorizer is disabled`() {
        val allowed = AgentFixtures.simpleAgent(name = "allowed").copy(mcp = AgentMcpConfig(enabled = true))
        val denied = AgentFixtures.simpleAgent(name = "denied").copy(mcp = AgentMcpConfig(enabled = true))
        every { agentsService.agents } returns mapOf("allowed" to allowed, "denied" to denied)

        val server = factory().buildServer(null)

        assertEquals(setOf("allowed", "denied") + managementToolNames, server.tools.keys)
    }

    companion object {
        private const val RUN_ID = 42
    }
}
