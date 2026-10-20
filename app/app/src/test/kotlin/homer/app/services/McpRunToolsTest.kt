package homer.app.services

import homer.app.models.Action
import homer.app.models.Grant
import homer.app.models.McpServerConfig
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

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
                assertEquals("Run $RUN_ID not found", (it.content.first() as TextContent).text)
            }
            coVerify(exactly = 0) { artifactService.listArtifacts(any()) }
            coVerify(exactly = 0) { processManager.killProcess(any(), any()) }
        }

    private companion object {
        const val RUN_ID = 42
    }
}
