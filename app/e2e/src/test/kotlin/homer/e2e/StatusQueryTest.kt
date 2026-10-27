package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import homer.e2e.support.ProcessInfo
import homer.e2e.support.ProcessList
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusQueryTest : E2eTestBase() {
    /** Guarantee at least three terminal processes regardless of class execution order. */
    private suspend fun seedRuns() {
        repeat(3) { i ->
            Awaits.awaitTerminal(HomerApi.startRun("echo", query = mapOf("message" to "seed-$i")))
        }
    }

    @Test
    fun `status all pages consistently with descending ids`() =
        runBlocking {
            seedRuns()

            val firstPage: ProcessList = HomerApi.decode(HomerApi.statusAll(mapOf("limit" to "2")))
            assertEquals(2, firstPage.limit)
            assertEquals(0, firstPage.offset)
            assertEquals(2, firstPage.processes.size)
            assertTrue(firstPage.total >= 3)
            val ids = firstPage.processes.map { it.id }
            assertTrue(ids[0] > ids[1], "default order must be descending by id: $ids")

            // No runs start between the two calls (the suite is sequential), so
            // the offset page must be the same sequence shifted by one.
            val shifted: ProcessList = HomerApi.decode(HomerApi.statusAll(mapOf("limit" to "2", "offset" to "1")))
            assertEquals(ids[1], shifted.processes[0].id, "offset=1 must shift the window by exactly one row")
        }

    @Test
    fun `status responses carry the run agent's group`() =
        runBlocking {
            val id = HomerApi.startRun("echo", query = mapOf("message" to "grouped"))
            Awaits.awaitTerminal(id)

            // Resolved from the current agent definition, so both the single read
            // and the list carry it — and an ungrouped agent's run carries null.
            assertEquals("core", HomerApi.status(id).group)

            val listed: ProcessList = HomerApi.decode(HomerApi.statusAll(mapOf("agentName" to "echo")))
            assertEquals("core", listed.processes.first { it.id == id }.group)

            val ungrouped = HomerApi.startRun("fail")
            Awaits.awaitTerminal(ungrouped)
            assertEquals(null, HomerApi.status(ungrouped).group)
        }

    @Test
    fun `status all filters by types and agentName`() =
        runBlocking {
            seedRuns()

            val finished: ProcessList = HomerApi.decode(HomerApi.statusAll(mapOf("types" to "FINISHED")))
            assertTrue(finished.processes.isNotEmpty())
            assertTrue(finished.processes.all { it.status == "FINISHED" })

            val mixedCase: ProcessList = HomerApi.decode(HomerApi.statusAll(mapOf("types" to "working,finished")))
            assertTrue(mixedCase.processes.all { it.status in setOf("WORKING", "FINISHED") })

            val byAgent: ProcessList = HomerApi.decode(HomerApi.statusAll(mapOf("agentName" to "echo")))
            assertTrue(byAgent.processes.isNotEmpty())
            assertTrue(byAgent.processes.all { it.agentName == "echo" })
        }

    @Test
    fun `status all tag filter does not expose a stored tag embedding a secret input value`() =
        runBlocking {
            // CWE-203: `tagged-secret` tags from the public `mr` input
            // only, but the run's secret `token` input is among its input params, so a
            // stored tag whose value embeds that secret is exactly what the read-time
            // screen must hide from `?tag=` — matched nowhere, `total` unchanged — while
            // a sibling run on the same agent (the screen is per row, so the sibling's
            // own params carry no secret needle) still filters through. Seen through a
            // reader-scoped caller per ADR-0038. See ADR-0060.
            val scopedAuth = "Bearer tagged-scoped-token"
            val nano = System.nanoTime().toString()
            val secretValue = "tokensecret$nano"
            val embeddedTag = "mr:embedded$secretValue"

            val probed =
                HomerApi.startRun("tagged-secret", query = mapOf("mr" to "embedded$secretValue", "token" to secretValue))
            Awaits.awaitTerminal(probed)
            val siblingId = HomerApi.startRun("tagged-secret", query = mapOf("mr" to "plain$nano"))
            Awaits.awaitTerminal(siblingId)

            suspend fun filteredList(tag: String): ProcessList =
                HomerApi.decode(
                    E2e.client.get("/api/v1/status/all") {
                        header(HttpHeaders.Authorization, scopedAuth)
                        parameter("tag", tag)
                    },
                )

            val probedMatches: ProcessList = filteredList(embeddedTag)
            assertEquals(0, probedMatches.total, "a tag embedding the run's secret must not be matchable: $probedMatches")
            assertTrue(probedMatches.processes.none { it.id == probed }, "no process may leak through: $probedMatches")

            val siblingMatches: ProcessList = filteredList("mr:plain$nano")
            assertTrue(
                siblingMatches.processes.any { it.id == siblingId },
                "the un-screened sibling must still match: $siblingMatches",
            )
            assertEquals(1, siblingMatches.total, "the sibling filter must count exactly its own run: $siblingMatches")

            // The display path uses the same per-row screen, so the run's own status
            // must hide the tag too.
            val status =
                E2e.client.get("/api/v1/status/$probed") {
                    header(HttpHeaders.Authorization, scopedAuth)
                }
            assertEquals(HttpStatusCode.OK, status.status)
            val probedInfo: ProcessInfo = HomerApi.decode(status)
            assertTrue(
                !probedInfo.tags.contains(embeddedTag),
                "the run's own tag list must hide the secret-embedding tag: ${probedInfo.tags}",
            )
        }

    @Test
    fun `status all filters by subject tag resolved from inputs`() =
        runBlocking {
            // The `tagged` fixture derives a `mr:<value>` subject tag from its `mr`
            // input, so this run carries `mr:e2e-<id>` once it starts.
            val marker = "e2e-${System.nanoTime()}"
            val processId = HomerApi.startRun("tagged", query = mapOf("mr" to marker))
            Awaits.awaitTerminal(processId)

            // The run reports its own tag on the single-process endpoint.
            assertTrue(HomerApi.status(processId).tags.contains("mr:$marker"), "run should carry its fromInputs tag")

            // An exact tag filter finds the run and only runs that carry it.
            val matched: ProcessList = HomerApi.decode(HomerApi.statusAll(mapOf("tag" to "mr:$marker")))
            assertTrue(matched.processes.any { it.id == processId }, "tag filter must return the tagged run")
            assertTrue(matched.processes.all { it.tags.contains("mr:$marker") }, "every returned run must carry the filtered tag")

            // A tag no run declared matches nothing.
            val unmatched: ProcessList = HomerApi.decode(HomerApi.statusAll(mapOf("tag" to "mr:no-such-$marker")))
            assertTrue(unmatched.processes.none { it.id == processId }, "a non-matching tag must exclude the run")
        }

    @Test
    fun `status all rejects more than ten tag filters with 400`() =
        runBlocking {
            val response =
                E2e.client.get("/api/v1/status/all") {
                    repeat(11) { i -> parameter("tag", "k:v$i") }
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `status all rejects invalid query parameters with 400`() =
        runBlocking {
            assertEquals(HttpStatusCode.BadRequest, HomerApi.statusAll(mapOf("types" to "BOGUS")).status)
            assertEquals(HttpStatusCode.BadRequest, HomerApi.statusAll(mapOf("order" to "sideways")).status)
            assertEquals(HttpStatusCode.BadRequest, HomerApi.statusAll(mapOf("limit" to "0")).status)
            assertEquals(HttpStatusCode.BadRequest, HomerApi.statusAll(mapOf("limit" to "1001")).status)
            assertEquals(HttpStatusCode.BadRequest, HomerApi.statusAll(mapOf("offset" to "-1")).status)
        }

    @Test
    fun `costs endpoint reports the spend snapshot`() =
        runBlocking {
            val response = E2e.client.get("/api/v1/costs")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = E2e.json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(body.containsKey("snapshotAtSec"), "costs body should carry the snapshot timestamp: $body")
        }

    @Test
    fun `costs monthly defaults to the current month and reports shape`() =
        runBlocking {
            val response = E2e.client.get("/api/v1/costs/monthly")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = E2e.json.parseToJsonElement(response.bodyAsText()).jsonObject
            val month = body["month"]!!.jsonPrimitive.content
            assertTrue(Regex("""^\d{4}-\d{2}$""").matches(month), "month must be YYYY-MM: $month")
            body["perAgentUsd"]!!.jsonObject // shape check: must decode as an object
            assertTrue(body["totalUsd"]!!.jsonPrimitive.doubleOrNull != null, "totalUsd must be numeric: $body")
            assertTrue(body["availableMonths"]!!.jsonArray.all { it.jsonPrimitive.content.isNotBlank() })
        }

    @Test
    fun `costs monthly rejects a malformed month with 400`() =
        runBlocking {
            val response =
                E2e.client.get("/api/v1/costs/monthly") {
                    parameter("month", "garbage")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `costs totals reports all-time per-agent spend shape`() =
        runBlocking {
            val response = E2e.client.get("/api/v1/costs/totals")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = E2e.json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(body.containsKey("perAgentUsd"), "totals body should carry perAgentUsd: $body")
            assertTrue(body["totalUsd"]!!.jsonPrimitive.doubleOrNull != null, "totalUsd must be numeric: $body")
        }

    @Test
    fun `costs by-tag reports the breakdown for a tracked key`() =
        runBlocking {
            // "mr" is listed in costTrackedTagKeys (fixtures/config/config.json).
            // The `tagged` fixture is a plain cmd run (e2e excludes Claude commands, see
            // app/e2e/README.md), so its cost is 0 — this proves the HTTP-boundary wiring
            // (route, config gate, tag join, response shape), not a real dollar figure;
            // the sum arithmetic itself is covered at the unit level in
            // ProcessRepositoryTest.
            val marker = "e2e-${System.nanoTime()}"
            Awaits.awaitTerminal(HomerApi.startRun("tagged", query = mapOf("mr" to marker)))

            val response = E2e.client.get("/api/v1/costs/by-tag/mr")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = E2e.json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("mr", body["key"]!!.jsonPrimitive.content)
            val valuesUsd = body["valuesUsd"]!!.jsonObject
            assertTrue(valuesUsd.containsKey(marker), "breakdown must include the run's tag value: $valuesUsd")
            assertTrue(body["totalUsd"]!!.jsonPrimitive.doubleOrNull != null, "totalUsd must be numeric: $body")
            assertEquals(false, body["truncated"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `costs by-tag rejects a key not listed in costTrackedTagKeys with 400`() =
        runBlocking {
            val response = E2e.client.get("/api/v1/costs/by-tag/not-a-tracked-key")
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `status reports the command kind of each execution`() =
        runBlocking {
            val id = HomerApi.startRun("echo", query = mapOf("message" to "kind"))
            Awaits.awaitTerminal(id)
            assertEquals(
                "cmd",
                HomerApi
                    .status(id)
                    .executions
                    .single()
                    .kind,
            )
        }

    @Test
    fun `status reports the serving backend alias for a local run`() =
        runBlocking {
            // Every run that got a session records the backend that served it; the
            // default local runner's alias is "local", with no pod fields. The e2e
            // client sends the admin token, so the raw alias is still returned.
            val id = HomerApi.startRun("echo", query = mapOf("message" to "runner-alias"))
            Awaits.awaitTerminal(id)

            val runner = HomerApi.status(id).runner
            assertEquals("local", runner?.alias, "a local run must report the local backend")
            assertEquals("local", runner?.type)
            assertEquals(null, runner?.podName)
        }

    @Test
    fun `status withholds the runner alias from a scoped non-admin caller`() =
        runBlocking {
            // CWE-200: the alias is operator-chosen backend metadata
            // that can name a host, tenant or environment. A non-admin principal who
            // can see the run must get the generic backend type, never the raw alias —
            // on the single-process and the list path alike.
            val scopedAuth = "Bearer echo-scoped-token"
            val id =
                HomerApi.startRun(
                    "echo",
                    query = mapOf("message" to "scoped-runner-alias"),
                    headers = mapOf(HttpHeaders.Authorization to scopedAuth),
                )
            Awaits.awaitTerminal(id)

            val single =
                E2e.client.get("/api/v1/status/$id") { header(HttpHeaders.Authorization, scopedAuth) }
            assertEquals(HttpStatusCode.OK, single.status)
            val singleRunner = E2e.json.decodeFromString<ProcessInfo>(single.bodyAsText()).runner
            assertEquals(null, singleRunner?.alias, "a non-admin caller must not receive the raw alias")
            assertEquals("local", singleRunner?.type, "a non-admin caller keeps the generic backend type")

            val list =
                E2e.client.get("/api/v1/status/all") {
                    header(HttpHeaders.Authorization, scopedAuth)
                    parameter("agentName", "echo")
                }
            assertEquals(HttpStatusCode.OK, list.status)
            val row =
                E2e.json
                    .decodeFromString<ProcessList>(list.bodyAsText())
                    .processes
                    .first { it.id == id }
            assertEquals(null, row.runner?.alias, "the list path must withhold the alias too")
            assertEquals("local", row.runner?.type)

            // Sibling: the admin caller still receives the raw alias.
            assertEquals("local", HomerApi.status(id).runner?.alias, "an admin must still receive the raw alias")
        }

    @Test
    fun `health withholds the runner alias from a scoped non-admin caller`() =
        runBlocking {
            // CWE-200: the configured runners' aliases and the sweep
            // snapshot's `runnerAlias` are operator-chosen backend metadata, so a
            // non-admin caller of GET /api/v1/health gets them nulled while the
            // built-in `local` row keeps its fixed, non-sensitive alias. The admin
            // caller still sees the raw values.
            val scopedAuth = "Bearer echo-scoped-token"

            val nonAdmin =
                E2e.client.get("/api/v1/health") { header(HttpHeaders.Authorization, scopedAuth) }
            assertEquals(HttpStatusCode.OK, nonAdmin.status)
            val nonAdminBody = E2e.json.parseToJsonElement(nonAdmin.bodyAsText()).jsonObject

            val nonAdminRunners = nonAdminBody["runners"]!!.jsonArray
            assertTrue(nonAdminRunners.isNotEmpty(), "health must list the runner rows: $nonAdminBody")
            val localRow =
                nonAdminRunners.first { it.jsonObject["type"]!!.jsonPrimitive.content == "local" }.jsonObject
            assertEquals("local", localRow["alias"]!!.jsonPrimitive.content, "the built-in local row keeps its fixed alias")

            // The fixture configures one non-local runner (`e2e-ext`), so the gate is
            // exercised: a non-admin must not receive its operator-chosen alias.
            val configuredRows =
                nonAdminRunners.filter { it.jsonObject["type"]!!.jsonPrimitive.content != "local" }
            assertTrue(
                configuredRows.isNotEmpty(),
                "the e2e fixture configures at least one non-local runner so the gate is exercised: $nonAdminBody",
            )
            // The API writes explicit nulls: a withheld alias is JsonNull, never a Kotlin null.
            assertTrue(
                configuredRows.all { it.jsonObject["alias"] == JsonNull },
                "a non-admin caller must not receive a configured runner alias: $nonAdminBody",
            )
            sweepRunnerAliases(nonAdminBody).forEach { alias ->
                assertEquals(JsonNull, alias, "a non-admin caller must not receive a sweep runnerAlias: $nonAdminBody")
            }

            // Sibling: the admin caller still receives the raw aliases.
            val admin = E2e.client.get("/api/v1/health")
            assertEquals(HttpStatusCode.OK, admin.status)
            val adminBody = E2e.json.parseToJsonElement(admin.bodyAsText()).jsonObject
            val adminAliases = adminBody["runners"]!!.jsonArray.map { it.jsonObject["alias"] }
            assertEquals(nonAdminRunners.size, adminAliases.size, "both callers must see the same runner rows")
            assertTrue(
                adminAliases.any { it != null && it != JsonNull && it.jsonPrimitive.content != "local" },
                "an admin caller must still receive the configured runner alias: $adminBody",
            )
        }

    /** The sweep block is present only after a startup sweep ran; a null sweep contributes nothing. */
    private fun sweepRunnerAliases(body: JsonObject): List<JsonElement?> =
        body["sweep"]
            ?.jsonObject
            ?.get("deleted")
            ?.jsonArray
            ?.map { it.jsonObject["runnerAlias"] }
            ?: emptyList()
}
