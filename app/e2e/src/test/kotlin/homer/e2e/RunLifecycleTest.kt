package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.ErrorBody
import homer.e2e.support.HomerApi
import homer.e2e.support.ProcessInfo
import homer.e2e.support.ProcessList
import homer.e2e.support.RunResponse
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RunLifecycleTest : E2eTestBase() {
    @Test
    fun `echo run finishes with full history and captured stdout`() =
        runBlocking {
            val processId = HomerApi.startRun("echo", query = mapOf("message" to "hello-e2e"))
            val info = Awaits.awaitTerminal(processId)

            assertEquals("FINISHED", info.status)
            assertEquals("echo", info.agentName)
            assertEquals(listOf("CREATED", "WORKING", "FINISHED"), info.history.map { it.status })
            assertEquals(1, info.executions.size)
            assertEquals(0, info.executions[0].resultCode)
            assertTrue(stdout(info, 0).contains("got: hello-e2e"), "stdout should contain the echoed input")
        }

    @Test
    fun `failing command yields FAILED with its exit code and stderr`() =
        runBlocking {
            val processId = HomerApi.startRun("fail")
            val info = Awaits.awaitTerminal(processId)

            assertEquals("FAILED", info.status)
            assertEquals(7, info.executions[0].resultCode)
            val stderr = HomerApi.artifactText(processId, info.executions[0].stdErr, "cmd_0.err")
            assertTrue(stderr.contains("deliberate failure on stderr"), "stderr artifact should carry the message, got: $stderr")
        }

    @Test
    fun `multi-step run chains captured results into later commands`() =
        runBlocking {
            val processId = HomerApi.startRun("multi-step")
            val info = Awaits.awaitTerminal(processId)

            assertEquals("FINISHED", info.status, "continueIfFail must carry the run over the failing first command")
            assertEquals(2, info.executions.size)
            assertEquals(1, info.executions[0].resultCode, "first command exits 1 via `false`")
            assertTrue(stdout(info, 1).contains("value=4 code=1"), "jsonPath + RESULT_CODE captures must reach command 2's env")
        }

    @Test
    fun `command timeout aborts the run quickly`() =
        runBlocking {
            val processId = HomerApi.startRun("timeout-cmd")
            // Standard budget: "quickly" is asserted below from the server's own history
            // timestamps, so a tighter client poll window only adds flakiness under load.
            val info = Awaits.awaitTerminal(processId)

            assertEquals("FAILED", info.status)
            val working = info.history.first { it.status == "WORKING" }.timestamp
            val failed = info.history.first { it.status == "FAILED" }.timestamp
            assertTrue(failed - working <= 6, "2s timeout must abort the 10s sleep, took ${failed - working}s")
        }

    @Test
    fun `when-predicate skips the command unless its input is present`() =
        runBlocking {
            val skippedRun = Awaits.awaitTerminal(HomerApi.startRun("conditional"))
            assertEquals("FINISHED", skippedRun.status)
            assertEquals(false, skippedRun.executions[0].skipped)
            assertEquals(true, skippedRun.executions[1].skipped, "needs-region must be skipped without `region`")

            val fullRun = Awaits.awaitTerminal(HomerApi.startRun("conditional", query = mapOf("region" to "eu-1")))
            assertEquals("FINISHED", fullRun.status)
            assertEquals(false, fullRun.executions[1].skipped)
            assertTrue(stdout(fullRun, 1).contains("region=eu-1"))
        }

    @Test
    fun `timed-out when predicate fails the run with a timeout execution entry`() =
        runBlocking {
            val processId = HomerApi.startRun("when-timeout")
            val info = Awaits.awaitTerminal(processId)

            assertEquals("FAILED", info.status)
            assertEquals(1, info.executions.size, "the timed-out step must be recorded, not omitted")
            assertEquals(false, info.executions[0].skipped)
            assertEquals(-1, info.executions[0].resultCode)
            val errMsg = info.executions[0].errMsg ?: ""
            assertTrue(errMsg.contains("timed out"), "errMsg should carry the timeout message, got: $errMsg")
        }

    @Test
    fun `parent agent starts a child echo run with forwarded input`() =
        runBlocking {
            val parentId = HomerApi.startRun("parent")
            val parent = Awaits.awaitTerminal(parentId)
            assertEquals("FINISHED", parent.status)

            val child =
                Awaits.awaitNotNull("child echo run with the forwarded input") {
                    val page: ProcessList = HomerApi.decode(HomerApi.statusAll(mapOf("agentName" to "echo")))
                    page.processes.firstOrNull { it.id > parentId && it.inputParams.containsValue("from-parent") }
                }
            val childDone = Awaits.awaitTerminal(child.id)
            assertEquals("FINISHED", childDone.status)
            assertTrue(stdout(childDone, 0).contains("got: from-parent"))

            // Parent/child linkage (ADR-0058): the child records the run that started it,
            // the root does not, and the list endpoint can be narrowed to one run's children.
            assertEquals(parentId, childDone.parentProcessId)
            assertEquals(null, parent.parentProcessId)
            val children: ProcessList = HomerApi.decode(HomerApi.statusAll(mapOf("parentProcessId" to "$parentId")))
            assertEquals(listOf(child.id), children.processes.map { it.id })

            // Flow linkage: the parent is the flow's first run, so the child's root is the
            // parent and the parent is rootless; one query returns the whole flow below it.
            assertEquals(parentId, childDone.rootProcessId)
            assertEquals(null, parent.rootProcessId)
            val flow: ProcessList = HomerApi.decode(HomerApi.statusAll(mapOf("rootProcessId" to "$parentId")))
            assertEquals(listOf(child.id), flow.processes.map { it.id })
            val roots: ProcessList = HomerApi.decode(HomerApi.statusAll(mapOf("roots" to "true")))
            assertTrue(roots.processes.any { it.id == parentId }, "a flow root must appear in the roots view")
            assertTrue(roots.processes.none { it.id == child.id }, "a flow segment must not appear in the roots view")
        }

    @Test
    fun `external caller links a run to a visible parent via header, and the run sees it in env`() =
        runBlocking {
            val parentId = HomerApi.startRun("echo", query = mapOf("message" to "external-parent"))

            val linkedId = HomerApi.startRun("parent-echo", headers = mapOf("X-Homer-Parent-Process-Id" to "$parentId"))
            val linked = Awaits.awaitTerminal(linkedId)
            assertEquals("FINISHED", linked.status)
            assertEquals(parentId, linked.parentProcessId)
            assertTrue(stdout(linked, 0).contains("parent=$parentId"), "HMR_PARENT_PROCESS_ID must reach the run")
            // The parent is a flow root, so the linked run inherits it as its own root.
            assertEquals(parentId, linked.rootProcessId)
            assertTrue(stdout(linked, 0).contains("root=$parentId"), "HMR_ROOT_PROCESS_ID must reach the run")

            val unlinked = Awaits.awaitTerminal(HomerApi.startRun("parent-echo"))
            assertEquals(null, unlinked.parentProcessId)
            assertTrue(stdout(unlinked, 0).contains("parent=none"), "HMR_PARENT_PROCESS_ID must be unset at the root")
            assertEquals(null, unlinked.rootProcessId)
            assertTrue(stdout(unlinked, 0).contains("root=none"), "HMR_ROOT_PROCESS_ID must be unset at the root")
        }

    @Test
    fun `unknown or malformed parent header refuses the run before it starts`() =
        runBlocking {
            val unknown = HomerApi.run("parent-echo", headers = mapOf("X-Homer-Parent-Process-Id" to "999999999"))
            assertEquals(HttpStatusCode.UnprocessableEntity, unknown.status)
            assertEquals("Parent process 999999999 not found", E2e.json.decodeFromString<ErrorBody>(unknown.bodyAsText()).msg)

            val malformed = HomerApi.run("parent-echo", headers = mapOf("X-Homer-Parent-Process-Id" to "abc"))
            assertEquals(HttpStatusCode.BadRequest, malformed.status)

            val junkFilter = HomerApi.statusAll(mapOf("parentProcessId" to "0"))
            assertEquals(HttpStatusCode.BadRequest, junkFilter.status)
        }

    @Test
    fun `killed run stays KILLED after the command would have finished`() =
        runBlocking {
            val processId = HomerApi.startRun("sleeper")
            Awaits.awaitStatus(processId, setOf("WORKING"))

            val killResponse = HomerApi.kill(processId)
            assertEquals(HttpStatusCode.OK, killResponse.status)
            val killed = Awaits.awaitStatus(processId, setOf("KILLED"), timeoutMs = 10_000)
            assertEquals("KILLED", killed.status)

            // Status-precedence invariant: the sleep would end at ~4s; nothing
            // may overwrite KILLED afterwards.
            delay(4_500)
            assertEquals("KILLED", HomerApi.status(processId).status)
        }

    @Test
    fun `retry replays a terminal run and rejects a running one`() =
        runBlocking {
            val originalId = HomerApi.startRun("echo", query = mapOf("message" to "retry-me"))
            Awaits.awaitTerminal(originalId)

            val retryResponse = HomerApi.retry(originalId)
            assertEquals(HttpStatusCode.OK, retryResponse.status)
            val retriedId = HomerApi.decode<RunResponse>(retryResponse).processId
            assertNotEquals(originalId, retriedId)
            val retried = Awaits.awaitTerminal(retriedId)
            assertEquals("FINISHED", retried.status)
            assertTrue(stdout(retried, 0).contains("got: retry-me"), "retry must replay the original inputs")

            val workingId = HomerApi.startRun("sleeper")
            Awaits.awaitStatus(workingId, setOf("WORKING"))
            val conflict = HomerApi.retry(workingId)
            assertEquals(HttpStatusCode.Conflict, conflict.status)
            HomerApi.kill(workingId)
        }

    // Cooldown on the manual-recovery endpoints (ADR-0051 amendment). The fixture
    // agents are plain cmd/script agents; resume needs a LangGraph thread id, which
    // only the Python runtime writes (`langgraph.json`), and this suite excludes
    // LangGraph commands on purpose (README § Design) — resume's cooldown wiring
    // shares retry's `withoutFailureBackoff()` call site, so the Ktor test-host
    // (`CooldownEndpointTest`) pins it directly while retry pins it here over real
    // HTTP.

    @Test
    fun `minInterval refuses an immediate retry with 429 and launches nothing`() =
        runBlocking {
            val firstId = HomerApi.startRun("cooldown-interval", query = mapOf("message" to "cooldown-e2e"))
            Awaits.awaitTerminal(firstId)

            // The first start opened the minInterval window; the immediate retry sits
            // inside it and must be refused before any process row is created.
            val refused = HomerApi.retry(firstId)
            assertEquals(HttpStatusCode.TooManyRequests, refused.status, "retry inside minInterval must be refused")
            val error = E2e.json.decodeFromString<ErrorBody>(refused.bodyAsText())
            assertTrue(error.msg.contains("cooldown"), "the 429 message should name the cooldown, got: ${error.msg}")

            // A refused start launches nothing: no new run of the agent ever appears.
            val list: ProcessList = HomerApi.decode(HomerApi.statusAll(mapOf("agentName" to "cooldown-interval")))
            assertEquals(1, list.total, "the refused retry must not create a run")
        }

    @Test
    fun `afterFailure does not gate a retry of a failed run`() =
        runBlocking {
            val failedId = HomerApi.startRun("cooldown-fail-retry")
            assertEquals("FAILED", Awaits.awaitTerminal(failedId).status)

            // The failure just ended, so the afterFailure window (1h) is wide open —
            // a manual retry is the way out of that backoff and must be admitted.
            val retryResponse = HomerApi.retry(failedId)
            assertEquals(HttpStatusCode.OK, retryResponse.status, "a manual retry must not be held back by afterFailure")
            val retriedId = HomerApi.decode<RunResponse>(retryResponse).processId
            assertNotEquals(failedId, retriedId)
            assertEquals("FAILED", Awaits.awaitTerminal(retriedId).status, "the fixture fails again, proving the retry really ran")
        }

    @Test
    fun `status of an unknown or invalid process id maps to 404 and 400`() =
        runBlocking {
            val unknown = HomerApi.statusResponse(99_999_999)
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            val error = E2e.json.decodeFromString<ErrorBody>(unknown.bodyAsText())
            assertEquals(1, error.err)

            val invalid = E2e.client.get("/api/v1/status/not-a-number")
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
        }

    private suspend fun stdout(
        info: ProcessInfo,
        cmdIndex: Int,
    ): String = HomerApi.artifactText(info.id, info.executions[cmdIndex].stdOut, "cmd_$cmdIndex.out")
}
