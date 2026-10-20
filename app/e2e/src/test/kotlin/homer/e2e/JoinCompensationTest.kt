package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import homer.e2e.support.ProcessList
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A refused join leaves no sub running (ADR-0026, 2026-10-01 amendment): the
 * second `join-cooldown-sub` start hits that agent's cooldown after the first
 * one started, so `/callback/joins` answers `429` and must kill the first sub
 * rather than leave it running untracked.
 */
class JoinCompensationTest : E2eTestBase() {
    @Test
    fun `a join refused after its first sub started kills that sub`() =
        runBlocking {
            val originId = HomerApi.startRun("join-refused")
            val origin = Awaits.awaitTerminal(originId)
            assertEquals("FINISHED", origin.status)
            val stdout = HomerApi.artifact(originId, "cmd_0.out").bodyAsText()
            assertTrue(stdout.contains("join=refused"), "the join must be refused: $stdout")

            val subs: ProcessList = HomerApi.decode(HomerApi.statusAll(mapOf("agentName" to "join-cooldown-sub")))
            val sub = subs.processes.single { it.parentProcessId == originId }
            assertEquals("KILLED", Awaits.awaitTerminal(sub.id).status, "the started sub must not keep running")
            assertTrue(
                HomerApi.continuations().continuations.none { it.originProcessId == originId },
                "a refused join registers no continuation",
            )
        }
}
