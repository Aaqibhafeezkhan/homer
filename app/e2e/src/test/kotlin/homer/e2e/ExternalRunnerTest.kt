package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import homer.e2e.support.RunnerQueueView
import homer.e2e.support.RunnersView
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * External pull-mode runners (ADR-0066) against real homer-runner containers that share the
 * backend's network namespace (docker-compose.e2e.yml): `e2e-runner` serves `e2e-external`,
 * `e2e-crash-runner` serves `e2e-crash` and is killed by the crash test.
 */
class ExternalRunnerTest : E2eTestBase() {
    private suspend fun instanceNames(): Set<String> =
        HomerApi
            .decode<RunnersView>(E2e.client.get("/api/v1/runners"))
            .runners
            .filter { it.kind == "external" }
            .flatMap { it.instances.orEmpty() }
            .map { it.name }
            .toSet()

    private suspend fun awaitInstance(name: String) =
        Awaits.awaitNotNull("instance '$name' registered", timeoutMs = 60_000, pollMs = 500) {
            name.takeIf { it in instanceNames() }
        }

    @Test
    fun `a tagged agent runs on the external instance with the domain's extraUploads and its output and artifacts come back`() =
        runBlocking {
            awaitInstance("e2e-runner")
            val processId = HomerApi.startRun("external-echo")
            val info = Awaits.awaitTerminal(processId, timeoutMs = 60_000)

            assertEquals("FINISHED", info.status)
            val stdout = HomerApi.artifactText(processId, info.executions[0].stdOut, "cmd_0.out")
            assertTrue(stdout.contains("via homer-runner"), "stdout was: $stdout")
            assertTrue(stdout.contains("extra: e2e extra upload"), "the domain's extraUploads reach the instance: $stdout")
            assertTrue(
                stdout.contains("helper: executable"),
                "the .homer/bin helper must arrive executable so a run can call it by name: $stdout",
            )
            val artifact = HomerApi.artifact(processId, "nested/remote.txt")
            assertEquals(HttpStatusCode.OK, artifact.status)
            assertEquals("remote artifact", artifact.bodyAsText().trim())
        }

    @Test
    fun `a runnerCmd on the external domain gets its extraUploads before the checks run`() =
        runBlocking {
            awaitInstance("e2e-runner")
            val processId = HomerApi.startRun("runnercmd-external")
            val info = Awaits.awaitTerminal(processId, timeoutMs = 60_000)

            assertEquals("FINISHED", info.status, "the check needs the uploaded marker: $info")
            val stdout = HomerApi.artifactText(processId, info.executions[0].stdOut, "cmd_0.out")
            assertTrue(stdout.contains("runnercmd extra: e2e extra upload"), "stdout was: $stdout")
        }

    @Test
    fun `the admin runner endpoints list the instance and refuse scoped and domain tokens`() =
        runBlocking {
            awaitInstance("e2e-runner")
            val queue = HomerApi.decode<RunnerQueueView>(E2e.client.get("/api/v1/runners/queue"))
            assertTrue(queue.pending >= 0)

            for (path in listOf("/api/v1/runners", "/api/v1/runners/queue")) {
                val scoped = E2e.unauthClient.get(path) { header(HttpHeaders.Authorization, "Bearer echo-scoped-token") }
                assertEquals(HttpStatusCode.Forbidden, scoped.status, path)
                val domain =
                    E2e.unauthClient.get(path) {
                        header(HttpHeaders.Authorization, "Bearer e2e-external-domain-token-0123456789")
                    }
                assertEquals(HttpStatusCode.Unauthorized, domain.status, path)
            }
        }

    @Test
    fun `killing a run cancels the remote process`() =
        // <Unit>: the block ends in awaitNotNull's String, and JUnit skips non-void @Test methods.
        runBlocking<Unit> {
            awaitInstance("e2e-runner")
            val processId = HomerApi.startRun("external-sleeper")
            Awaits.awaitStatus(processId, setOf("WORKING"), timeoutMs = 60_000)
            // The claim is WORKING before the op reaches the instance; wait until the sleep runs there.
            Awaits.awaitNotNull("sleeper running on the runner", timeoutMs = 30_000, pollMs = 1_000) {
                probe().takeIf { it == "sleeper=alive" }
            }

            assertEquals(HttpStatusCode.OK, HomerApi.kill(processId).status)
            assertEquals("KILLED", Awaits.awaitStatus(processId, setOf("KILLED"), timeoutMs = 15_000).status)
            Awaits.awaitNotNull<String>("remote sleeper gone after kill", timeoutMs = 30_000, pollMs = 1_000) {
                probe().takeIf { it == "sleeper=gone" }
            }
        }

    @Test
    fun `a runner that dies mid-run fails the run within the heartbeat TTL and drops the instance`() =
        runBlocking {
            awaitInstance("e2e-crash-runner")
            val processId = HomerApi.startRun("external-crash")
            // heartbeatTimeoutSec 35 + 5 s sweep + slack.
            val info = Awaits.awaitTerminal(processId, timeoutMs = 75_000)

            assertEquals("FAILED", info.status)
            assertTrue("e2e-crash-runner" !in instanceNames(), "the dead instance must be dropped")
        }

    private suspend fun probe(): String {
        val id = HomerApi.startRun("external-probe")
        val info = Awaits.awaitTerminal(id, timeoutMs = 60_000)
        return HomerApi.artifactText(id, info.executions[0].stdOut, "cmd_0.out").trim()
    }
}
