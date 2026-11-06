package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.ErrorBody
import homer.e2e.support.Health
import homer.e2e.support.HomerApi
import homer.e2e.support.MaintenanceState
import homer.e2e.support.ProcessList
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Black-box coverage of the admin maintenance mode against the production server: the toggle
 * round-trip, a denial indistinguishable from not-found, the flag on `/api/v1/health`, and a
 * start that arrives during the window, waits out `queueWaitSec` and answers `503` without ever
 * creating a run. See ADR-0092.
 */
class MaintenanceTest : E2eTestBase() {
    private suspend fun setMode(enabled: Boolean): HttpResponse =
        E2e.client.post("/api/v1/maintenance") {
            setBody(TextContent("""{"enabled":$enabled}""", ContentType.Application.Json))
        }

    private suspend fun maintenance(): MaintenanceState = HomerApi.decode(E2e.client.get("/api/v1/maintenance"))

    @Test
    fun `the toggle round-trips the state for an admin`() =
        runBlocking {
            try {
                // Off is the shipped default: nothing queued, never toggled in this process.
                assertFalse(maintenance().enabled)

                assertEquals(HttpStatusCode.OK, setMode(true).status)
                val on = maintenance()
                assertTrue(on.enabled)
                assertTrue((on.since ?: 0) > 0, "a live toggle stamps the moment it was turned on")
            } finally {
                // Every other test in the suite must find the gate open again.
                setMode(false)
            }
            val after = maintenance()
            assertFalse(after.enabled)
            assertNull(after.since)
        }

    @Test
    fun `a non-admin gets the same 404 an unknown resource answers`() =
        runBlocking {
            val scoped = "Bearer echo-scoped-token"
            val denied = E2e.client.get("/api/v1/maintenance") { header(HttpHeaders.Authorization, scoped) }
            val unknown = E2e.client.get("/api/v1/does-not-exist") { header(HttpHeaders.Authorization, scoped) }

            assertEquals(HttpStatusCode.NotFound, denied.status)
            assertEquals(
                unknown.bodyAsText(),
                denied.bodyAsText(),
                "the denial must be byte-identical to a path that was never mounted",
            )
        }

    @Test
    fun `health exposes the maintenance flag`() =
        runBlocking {
            try {
                setMode(true)
                assertTrue(HomerApi.decode<Health>(E2e.client.get("/api/v1/health")).maintenance)
            } finally {
                setMode(false)
            }
            assertFalse(HomerApi.decode<Health>(E2e.client.get("/api/v1/health")).maintenance)
        }

    @Test
    fun `a start arriving during the window waits out the bound and answers 503 with no run`() =
        runBlocking {
            val seen: ProcessList =
                HomerApi.decode(
                    E2e.client.get("/api/v1/status/all") { parameter("agentName", "echo") },
                )
            val before = seen.processes.maxOfOrNull { it.id } ?: 0

            try {
                setMode(true)
                val start = HomerApi.run("echo", query = mapOf("message" to "during-maintenance"))
                assertEquals(HttpStatusCode.ServiceUnavailable, start.status)
                val body = E2e.json.decodeFromString<ErrorBody>(start.bodyAsText())
                assertTrue(
                    body.msg.contains("Maintenance mode is on"),
                    "the shed must name the mode, got: ${body.msg}",
                )
            } finally {
                setMode(false)
            }

            // A shed start is never replayed: no echo run shows up once the mode is off again.
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val now: ProcessList =
                    HomerApi.decode(
                        E2e.client.get("/api/v1/status/all") { parameter("agentName", "echo") },
                    )
                assertTrue(
                    now.processes.none { it.id > before },
                    "a start the window shed must create no process row",
                )
                delay(250)
            }
        }

    @Test
    fun `a run that starts another run finishes through the window instead of parking`() =
        runBlocking {
            // `maintenance-parent` sleeps, then hops to `echo`. The window is turned on while it
            // is still WORKING, so the hop is a start of already-admitted work: it must not park,
            // the parent must reach FINISHED, and nothing may be left in the queue. See ADR-0092.
            val parentId = HomerApi.startRun("maintenance-parent")
            Awaits.awaitStatus(parentId, setOf("WORKING"))

            try {
                setMode(true)

                val finished = Awaits.awaitTerminal(parentId, timeoutMs = 20_000)
                assertEquals(
                    "FINISHED",
                    finished.status,
                    "a run whose hop parked would fail on the wait bound instead of finishing",
                )
                assertEquals(
                    0,
                    maintenance().queued,
                    "a start that is part of admitted work must never be queued",
                )

                val children =
                    HomerApi.decode<ProcessList>(
                        E2e.client.get("/api/v1/status/all") { parameter("parentProcessId", parentId) },
                    )
                val child = children.processes.firstOrNull { it.agentName == "echo" }
                assertTrue(child != null, "the parent's hop must still start its child run")
                assertEquals("FINISHED", Awaits.awaitTerminal(child.id).status)
            } finally {
                setMode(false)
            }
        }
}
