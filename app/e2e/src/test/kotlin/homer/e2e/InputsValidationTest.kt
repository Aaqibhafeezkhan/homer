package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.ErrorBody
import homer.e2e.support.HomerApi
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputsValidationTest : E2eTestBase() {
    @Test
    fun `header body jsonPath and number inputs all reach the command env`() =
        runBlocking {
            val processId =
                HomerApi.startRun(
                    "inputs",
                    query = mapOf("n" to "42", "token" to "shh-e2e"),
                    body = """{"service":{"id":"svc-1"}}""",
                    headers = mapOf("X-Trace-Id" to "trace-e2e"),
                )
            val info = Awaits.awaitTerminal(processId)

            assertEquals("FINISHED", info.status)
            val stdout = HomerApi.artifact(processId, "cmd_0.out").bodyAsText()
            assertTrue(stdout.contains("trace=trace-e2e"), "header input missing from env: $stdout")
            assertTrue(stdout.contains("svc=svc-1"), "jsonPath body input missing from env: $stdout")
            assertTrue(stdout.contains("n=42"), "NUMBER query input missing from env: $stdout")
            assertTrue(stdout.contains("token=shh-e2e"), "secret input must still reach the run env: $stdout")
        }

    @Test
    fun `bodyless POST runs an agent whose only body input is optional`() =
        runBlocking {
            // No body and no Content-Type (the Export Curl shape): the optional `service`
            // body input stays unset, so the command sees an empty SVC_ID.
            val processId = HomerApi.startRun("inputs", query = mapOf("n" to "1"))
            val info = Awaits.awaitTerminal(processId)

            assertEquals("FINISHED", info.status)
            val stdout = HomerApi.artifact(processId, "cmd_0.out").bodyAsText()
            assertTrue(stdout.contains("svc= n=1"), "bodyless run should leave the optional body input unset: $stdout")
        }

    @Test
    fun `missing required input is rejected with 400`() =
        runBlocking {
            val response = HomerApi.run("inputs", body = """{"service":{"id":"svc-1"}}""")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val error = E2e.json.decodeFromString<ErrorBody>(response.bodyAsText())
            assertTrue(error.msg.contains("n"), "error should name the offending parameter: ${error.msg}")
        }

    @Test
    fun `non-numeric value for a NUMBER input is rejected with 400`() =
        runBlocking {
            val response = HomerApi.run("inputs", query = mapOf("n" to "abc"), body = """{"service":{"id":"x"}}""")
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `secret input values are redacted in status responses`() =
        runBlocking {
            val processId =
                HomerApi.startRun(
                    "inputs",
                    query = mapOf("n" to "7", "token" to "super-secret-value"),
                    body = """{"service":{"id":"x"}}""",
                )
            val info = Awaits.awaitTerminal(processId)

            assertFalse(
                info.inputParams.values.any { it.contains("super-secret-value") },
                "secret value leaked into inputParams: ${info.inputParams}",
            )
            assertTrue(
                info.inputParams.values.any { it.contains("***") },
                "secret value should be redacted with ***: ${info.inputParams}",
            )
        }

    @Test
    fun `inputSchema accepts a conforming payload`() =
        runBlocking {
            val processId = HomerApi.startRun("schema-guard", body = """{"service":"checkout"}""")
            assertEquals("FINISHED", Awaits.awaitTerminal(processId).status)
        }

    @Test
    fun `inputSchema rejects a non-conforming payload with 422 naming the violations`() =
        runBlocking {
            val response = HomerApi.run("schema-guard", body = """{"bogus":1}""")
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            val error = E2e.json.decodeFromString<ErrorBody>(response.bodyAsText())
            assertTrue(
                error.msg.contains("schema", ignoreCase = true) && error.msg.contains("service"),
                "422 msg must name the schema violations: ${error.msg}",
            )
        }

    @Test
    fun `inputSchema agent rejects GET and missing body with 400`() =
        runBlocking {
            val get = E2e.client.get("/api/v1/agent/schema-guard")
            assertEquals(HttpStatusCode.BadRequest, get.status)

            val noBody = HomerApi.run("schema-guard")
            assertEquals(HttpStatusCode.BadRequest, noBody.status)
        }

    @Test
    fun `typed inputs reach the env with defaults applied and booleans normalized`() =
        runBlocking {
            val processId =
                HomerApi.startRun(
                    "inputs",
                    query = mapOf("n" to "1", "environment" to "staging", "flag" to "TRUE"),
                    body = """{"service":{"id":"x"}}""",
                )
            val info = Awaits.awaitTerminal(processId)

            assertEquals("FINISHED", info.status)
            val stdout = HomerApi.artifact(processId, "cmd_0.out").bodyAsText()
            assertTrue(stdout.contains("env=staging"), "enum string input missing from env: $stdout")
            assertTrue(stdout.contains("count=10 "), "default for the absent integer input not applied: $stdout")
            assertTrue(stdout.contains("flag=true"), "boolean input should be lowercase-normalized: $stdout")
        }

    @Test
    fun `explicit typed value overrides the default`() =
        runBlocking {
            val processId =
                HomerApi.startRun(
                    "inputs",
                    query = mapOf("n" to "1", "count" to "42"),
                    body = """{"service":{"id":"x"}}""",
                )
            val info = Awaits.awaitTerminal(processId)
            assertEquals("FINISHED", info.status)
            val stdout = HomerApi.artifact(processId, "cmd_0.out").bodyAsText()
            assertTrue(stdout.contains("count=42 "), "explicit value should win over the default: $stdout")
        }

    @Test
    fun `out-of-range integer input is rejected with 400 naming the bound`() =
        runBlocking {
            val response =
                HomerApi.run("inputs", query = mapOf("n" to "1", "count" to "250"), body = """{"service":{"id":"x"}}""")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val error = E2e.json.decodeFromString<ErrorBody>(response.bodyAsText())
            assertTrue(error.msg.contains("<= 100"), "400 msg should name the violated bound: ${error.msg}")
        }

    @Test
    fun `non-enum value is rejected with 400 listing the allowed values`() =
        runBlocking {
            val response =
                HomerApi.run("inputs", query = mapOf("n" to "1", "environment" to "qa"), body = """{"service":{"id":"x"}}""")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val error = E2e.json.decodeFromString<ErrorBody>(response.bodyAsText())
            assertTrue(
                error.msg.contains("one of [dev, staging, prod]"),
                "400 msg should list allowed values: ${error.msg}",
            )
        }

    @Test
    fun `a secret input outside its declared values is rejected with 400 without echoing the set`() =
        runBlocking {
            // ADR-0071: the allow-list still binds, but the 400 never lists it.
            val response =
                HomerApi.run(
                    "inputs",
                    query = mapOf("n" to "1", "apiKey" to "not-in-declared-set"),
                    body = """{"service":{"id":"x"}}""",
                )
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val error = E2e.json.decodeFromString<ErrorBody>(response.bodyAsText())
            assertTrue(error.msg.contains("not one of the allowed values"), "400 msg should deny generically: ${error.msg}")
            assertFalse(error.msg.contains("abc") || error.msg.contains("def"), "400 msg echoed the declared set: ${error.msg}")
        }

    @Test
    fun `a secret integer above its max is rejected with 400 without echoing the value`() =
        runBlocking {
            val response =
                HomerApi.run("inputs", query = mapOf("n" to "1", "pin" to "123456789"), body = """{"service":{"id":"x"}}""")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val error = E2e.json.decodeFromString<ErrorBody>(response.bodyAsText())
            assertTrue(error.msg.contains("must be <= 9999"), "400 msg should keep the bound: ${error.msg}")
            assertFalse(error.msg.contains("123456789"), "400 msg echoed the secret value: ${error.msg}")
        }

    @Test
    fun `non-integer typed value is rejected with 400`() =
        runBlocking {
            val response = HomerApi.run("inputs", query = mapOf("n" to "1", "count" to "4.5"), body = """{"service":{"id":"x"}}""")
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
}
