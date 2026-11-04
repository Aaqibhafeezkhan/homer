package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import homer.e2e.support.ProcessInfo
import homer.e2e.support.ProcessList
import homer.e2e.support.RunnersView
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `runnerAffinity` (ADR-0099) against the two named instances of the `e2e-affinity` domain
 * (docker-compose.e2e.yml, concurrency 1 each): a flow's later run must land on the instance
 * that served its first, and a run without `runnerAffinity` must never be held back.
 */
class RunnerAffinityTest : E2eTestBase() {
    private suspend fun instanceNames(): Set<String> =
        HomerApi
            .decode<RunnersView>(E2e.client.get("/api/v1/runners"))
            .runners
            .filter { it.alias == "e2e-affinity" }
            .flatMap { it.instances.orEmpty() }
            .map { it.name }
            .toSet()

    /** Both instances must be polling before the flow starts, or the preference proves nothing. */
    private suspend fun awaitInstances() =
        Awaits.awaitNotNull("both e2e-affinity instances registered", timeoutMs = 120_000, pollMs = 500) {
            instanceNames().takeIf { it.containsAll(setOf("affinity-a", "affinity-b")) }
        }

    /** The children of [parentProcessId], by agent name. */
    private suspend fun childrenOf(parentProcessId: Int): Map<String, ProcessInfo> =
        HomerApi
            .decode<ProcessList>(HomerApi.statusAll(mapOf("parentProcessId" to "$parentProcessId")))
            .processes
            .associateBy { it.agentName }

    /** The hostname a run reported, which is how two runs on one instance are told from two instances. */
    private suspend fun hostOf(info: ProcessInfo): String {
        val stdout = HomerApi.artifactText(info.id, info.executions.firstOrNull()?.stdOut, "cmd_0.out")
        return Regex("""ran on (\S+)""").find(stdout)?.groupValues?.get(1)
            ?: error("no hostname in ${info.agentName}'s output: $stdout")
    }

    @Test
    fun `the flow's second run lands on the instance that served its first`() =
        runBlocking {
            awaitInstances()
            // An `!<agent>` hop is fire-and-forget: the parent starts a child and returns, so the
            // ordering the case needs must come from the flow itself. The parent starts
            // `affinity-sub`, whose first command writes the marker on the instance serving it
            // and whose second starts `affinity-check` — only after the marker exists, and with
            // the same flow root, so both children carry one (flow root, group) affinity key.
            val parentId = HomerApi.startRun("affinity-parent")
            Awaits.awaitTerminal(parentId, timeoutMs = 180_000)

            val started = childrenOf(parentId)
            assertEquals(setOf("affinity-sub"), started.keys, "the flow's first child: $started")
            val sub = Awaits.awaitTerminal(started.getValue("affinity-sub").id, timeoutMs = 180_000)
            assertEquals("FINISHED", sub.status, "the flow's first run: $sub")

            val checkRow = childrenOf(sub.id)
            assertEquals(setOf("affinity-check"), checkRow.keys, "the flow's second child: $checkRow")
            val check = Awaits.awaitTerminal(checkRow.getValue("affinity-check").id, timeoutMs = 180_000)
            assertEquals(
                "FINISHED",
                check.status,
                "the second run must find the marker the first left on its instance: $check",
            )
            assertEquals(
                hostOf(sub),
                hostOf(check),
                "both runs of one flow must be served by one instance: $sub / $check",
            )
        }

    @Test
    fun `an agent without runnerAffinity still runs on the same tag`() =
        runBlocking {
            awaitInstances()
            val processId = HomerApi.startRun("affinity-plain")
            val info = Awaits.awaitTerminal(processId, timeoutMs = 120_000)

            assertEquals("FINISHED", info.status, "an ordinary run is never held back by a preference: $info")
            // No other runner serves `e2e-affinity`, so finishing proves one of the two claimed it.
            hostOf(info)
        }
}
