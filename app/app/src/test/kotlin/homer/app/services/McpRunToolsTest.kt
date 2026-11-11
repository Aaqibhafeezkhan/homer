package homer.app.services

import homer.app.fixtures.TestFixtures
import homer.app.fixtures.TestFixtures.firstText
import homer.app.infra.LocalFileOperations
import homer.app.models.Action
import homer.app.models.ArtifactEntry
import homer.app.models.ArtifactListingResponse
import homer.app.models.ExecutionInfo
import homer.app.models.Grant
import homer.app.models.McpServerConfig
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpRunToolsTest {
    private val processManager: ProcessManager = mockk(relaxed = true)
    private val artifactService: ArtifactService = mockk()

    /** "bob" holds READ on "other" only — no grant on "deploy", and isn't the run's owner. */
    private val authorizer =
        Authorizer(
            mapOf("bob" to Authorizer.PrincipalGrants(admin = false, grants = listOf(Grant(listOf("other"), listOf(Action.READ))))),
            emptyMap(),
            enabled = true,
        )
    private val tools = McpRunTools(processManager, artifactService, authorizer, McpServerConfig(enabled = true))

    private fun runRequest() = CallToolRequest(CallToolRequestParams(name = "t", arguments = buildJsonObject { put("runId", RUN_ID) }))

    private fun hiddenRun() =
        coEvery { processManager.getProcessInfo(RUN_ID) } returns
            ProcessInfo(id = RUN_ID, status = ProcessStatus.WORKING, agentName = "deploy", owner = "alice")

    /** A run "bob" can see (his READ grant covers "other") but, with [authorizer], may not control. */
    private fun visibleRun(
        currentCommand: String? = null,
        executions: List<ExecutionInfo> = emptyList(),
    ) = coEvery { processManager.getProcessInfo(RUN_ID) } returns
        ProcessInfo(
            id = RUN_ID,
            status = ProcessStatus.WORKING,
            agentName = "other",
            owner = "alice",
            currentCommand = currentCommand,
            executions = executions,
        )

    private fun execution(
        label: String,
        resultCode: Int,
        errMsg: String? = null,
    ) = ExecutionInfo(
        label = label,
        start = 0,
        end = 1,
        resultCode = resultCode,
        stdOut = null,
        stdErr = null,
        errMsg = errMsg,
    )

    @Test
    fun `registers the four run-management tools`() {
        assertEquals(listOf("get_run_info", "list_artifacts", "get_artifact", "cancel_run"), tools.tools("bob").map { it.tool.name })
    }

    @Test
    fun `a hidden run reads as not found on every tool and is never touched`() =
        runTest {
            hiddenRun()
            val results =
                listOf(
                    tools.getRunInfo(runRequest(), "bob"),
                    tools.listArtifacts(runRequest(), "bob"),
                    tools.cancelRun(runRequest(), "bob"),
                )
            results.forEach {
                assertEquals(true, it.isError)
                assertEquals("Run $RUN_ID not found", firstText(it))
            }
            coVerify(exactly = 0) { artifactService.listArtifacts(any()) }
            coVerify(exactly = 0) { processManager.killProcess(any(), any()) }
        }

    // ========== get_run_info ==========

    @Test
    fun `get_run_info carries currentCommand and a command error only when the run has them`() =
        runTest {
            visibleRun(
                currentCommand = "claude",
                executions = listOf(execution("build", resultCode = 1, errMsg = "exit 1"), execution("test", resultCode = 0)),
            )

            val structured = assertNotNull(tools.getRunInfo(runRequest(), "bob").structuredContent)

            assertEquals("claude", structured["currentCommand"]!!.jsonPrimitive.content)
            val commands = structured["commands"]!!.jsonArray.map { it.jsonObject }
            assertEquals("exit 1", commands[0]["error"]!!.jsonPrimitive.content)
            assertFalse(commands[1].containsKey("error"), "a command without errMsg carries no error key")
        }

    @Test
    fun `get_run_info omits currentCommand and error when the run has neither`() =
        runTest {
            visibleRun()

            val structured = assertNotNull(tools.getRunInfo(runRequest(), "bob").structuredContent)

            assertFalse(structured.containsKey("currentCommand"))
            assertTrue(structured["commands"]!!.jsonArray.isEmpty())
        }

    @Test
    fun `get_run_info reports a missing or non-numeric runId as an error`() =
        runTest {
            val missing = tools.getRunInfo(CallToolRequest(CallToolRequestParams(name = "t")), "bob")
            val nonNumeric =
                tools.getRunInfo(
                    CallToolRequest(CallToolRequestParams(name = "t", arguments = buildJsonObject { put("runId", "abc") })),
                    "bob",
                )

            assertEquals(true, missing.isError)
            assertEquals(true, nonNumeric.isError)
            assertTrue(firstText(missing).contains("runId"))
        }

    // ========== list_artifacts ==========

    @Test
    fun `list_artifacts reports the run's artifacts`() =
        runTest {
            visibleRun()
            coEvery { artifactService.listArtifacts(RUN_ID) } returns
                Result.success(
                    ArtifactListingResponse(
                        processId = RUN_ID,
                        artifactsDir = "/tmp/a",
                        files = listOf(ArtifactEntry(path = "out.txt", size = 7, modified = 0)),
                    ),
                )

            val result = tools.listArtifacts(runRequest(), "bob")

            assertNull(result.isError)
            assertTrue(firstText(result).contains("out.txt"))
            val structured = assertNotNull(result.structuredContent)
            assertEquals(
                "out.txt",
                structured["artifacts"]!!
                    .jsonArray
                    .single()
                    .jsonObject["path"]!!
                    .jsonPrimitive.content,
            )
        }

    // ========== get_artifact ==========

    /** Tools over a real [ArtifactService] for a run whose artifact `out.txt` holds [content]. */
    private fun artifactTools(
        content: ByteArray,
        cap: Long = DEFAULT_CAP,
    ): Pair<McpRunTools, Path> {
        val dir = Files.createTempDirectory("mcp-run-tools-test")
        Files.write(dir.resolve("out.txt"), content)
        coEvery { processManager.getProcessInfo(RUN_ID) } returns
            ProcessInfo(id = RUN_ID, status = ProcessStatus.FINISHED, agentName = "other", owner = "alice", artifactsDir = dir)
        val service =
            ArtifactService(
                processManager = processManager,
                fileOperations = LocalFileOperations(),
                logger = TestFixtures.mockLogger(),
            )
        return McpRunTools(processManager, service, authorizer, McpServerConfig(enabled = true, maxInlineArtifactBytes = cap)) to dir
    }

    private fun artifactRequest(path: String? = "out.txt") =
        CallToolRequest(
            CallToolRequestParams(
                name = "t",
                arguments =
                    buildJsonObject {
                        put("runId", RUN_ID)
                        path?.let { put("path", it) }
                    },
            ),
        )

    private suspend fun readArtifact(
        content: String,
        cap: Long,
    ): CallToolResult {
        val (tools, dir) = artifactTools(content.toByteArray(), cap)
        return try {
            tools.getArtifact(artifactRequest(), "bob")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `get_artifact inlines an artifact exactly at the cap without truncating it`() =
        runTest {
            val result = readArtifact("A".repeat(8), cap = 8)
            val structured = assertNotNull(result.structuredContent)

            assertNull(result.isError)
            assertFalse((structured["truncated"] as JsonPrimitive).content.toBoolean())
            assertEquals("A".repeat(8), (structured["content"] as JsonPrimitive).content)
            assertFalse(firstText(result).contains("showing first"), "an untruncated artifact says so in no header")
        }

    @Test
    fun `get_artifact truncates an artifact one byte over the cap`() =
        runTest {
            val result = readArtifact("A".repeat(9), cap = 8)
            val structured = assertNotNull(result.structuredContent)

            assertNull(result.isError)
            assertTrue((structured["truncated"] as JsonPrimitive).content.toBoolean())
            assertEquals(9, (structured["totalSize"] as JsonPrimitive).content.toInt())
            assertEquals("A".repeat(8), (structured["content"] as JsonPrimitive).content)
            assertTrue(firstText(result).contains("showing first 8"), "a truncated artifact says so: ${firstText(result)}")
        }

    @Test
    fun `get_artifact refuses a binary artifact and points at the GET API`() =
        runTest {
            val result = readArtifact("hi\u0000!", cap = DEFAULT_CAP)

            assertEquals(true, result.isError)
            assertTrue(firstText(result).contains("binary"))
            assertTrue(firstText(result).contains("/api/v1/artifacts"))
        }

    @Test
    fun `get_artifact reports a missing path and a missing file`() =
        runTest {
            val (tools, dir) = artifactTools("safe".toByteArray())
            try {
                val noPath = tools.getArtifact(artifactRequest(path = null), "bob")
                val noFile = tools.getArtifact(artifactRequest(path = "gone.txt"), "bob")

                assertEquals(true, noPath.isError)
                assertTrue(firstText(noPath).contains("path"))
                assertEquals(true, noFile.isError)
                assertTrue(firstText(noFile).contains("not found"))
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test
    fun `get_artifact hides a run the principal cannot see before it validates the path`() =
        runTest {
            hiddenRun()

            val result = tools.getArtifact(artifactRequest(path = "../escape.txt"), "bob")

            assertEquals(true, result.isError)
            assertEquals("Run $RUN_ID not found", firstText(result))
        }

    // ========== cancel_run ==========

    @Test
    fun `cancel_run kills a run the principal controls`() =
        runTest {
            visibleRun()
            val controlling =
                McpRunTools(
                    processManager,
                    artifactService,
                    Authorizer(
                        mapOf(
                            "bob" to
                                Authorizer.PrincipalGrants(
                                    admin = false,
                                    grants = listOf(Grant(listOf("other"), listOf(Action.READ, Action.CONTROL))),
                                ),
                        ),
                        emptyMap(),
                        enabled = true,
                    ),
                    McpServerConfig(enabled = true),
                )
            coEvery { processManager.getProcessStatus(RUN_ID) } returns ProcessStatus.KILLED

            val result = controlling.cancelRun(runRequest(), "bob")

            assertNull(result.isError)
            coVerify(exactly = 1) { processManager.killProcess(RUN_ID, any()) }
            assertEquals("KILLED", (result.structuredContent!!["status"])!!.jsonPrimitive.content)
        }

    private companion object {
        const val RUN_ID = 42
        const val DEFAULT_CAP = 65_536L
    }
}
