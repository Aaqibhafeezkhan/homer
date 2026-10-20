package homer.app.routes

import homer.app.apiJson
import homer.app.fixtures.mountApiV1Routes
import homer.app.services.ProcessManager
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Characterization tests for the query-parameter validation of
 * GET /api/v1/status/all. Unlike the inline-reimplemented cases in
 * ApplicationTest.kt, these mount the *real* [apiV1Routes] handler so a
 * regression in the production validation is actually caught. They pin the
 * exact 400 status, the exact ErrorResponse body, and the early-return order
 * (which error wins when two params are invalid).
 */
class StatusAllValidationTest {
    private lateinit var processManager: ProcessManager

    @BeforeEach
    fun setup() {
        processManager = mockk(relaxed = true)
        // Default happy-path result for the cases that reach the handler body.
        coEvery {
            processManager.getAllProcesses(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns ProcessManager.ProcessListResult(processes = emptyList(), total = 0)
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    private fun ApplicationTestBuilder.mountStatusAllRoutes() {
        val pm = processManager
        mountApiV1Routes { every { processManager } returns pm }
    }

    private fun expectedBody(msg: String): String = apiJson.encodeToString(homer.app.models.ErrorResponse(msg = msg))

    @Test
    fun `invalid order parameter returns 400 with exact body`() =
        testApplication {
            mountStatusAllRoutes()

            val response = client.get("/api/v1/status/all?order=invalid")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                expectedBody("Invalid order parameter. Must be 'asc' or 'desc'"),
                response.bodyAsText(),
            )
        }

    @Test
    fun `limit below range returns 400 with exact body`() =
        testApplication {
            mountStatusAllRoutes()

            val response = client.get("/api/v1/status/all?limit=0")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                expectedBody("Invalid limit parameter. Must be between 1 and 1000"),
                response.bodyAsText(),
            )
        }

    @Test
    fun `limit above range returns 400 with exact body`() =
        testApplication {
            mountStatusAllRoutes()

            val response = client.get("/api/v1/status/all?limit=1001")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                expectedBody("Invalid limit parameter. Must be between 1 and 1000"),
                response.bodyAsText(),
            )
        }

    @Test
    fun `negative offset returns 400 with exact body`() =
        testApplication {
            mountStatusAllRoutes()

            val response = client.get("/api/v1/status/all?offset=-1")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                expectedBody("Invalid offset parameter. Must be >= 0"),
                response.bodyAsText(),
            )
        }

    @Test
    fun `invalid status types returns 400 with exact body listing offenders`() =
        testApplication {
            mountStatusAllRoutes()

            val response = client.get("/api/v1/status/all?types=INVALID,ALSO_BAD")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                expectedBody("Invalid status types: INVALID, ALSO_BAD"),
                response.bodyAsText(),
            )
        }

    @Test
    fun `valid parameters reach the handler body and return 200`() =
        testApplication {
            mountStatusAllRoutes()

            val response = client.get("/api/v1/status/all?types=working&order=asc&offset=0&limit=10")

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `order is validated before limit when both are invalid`() =
        testApplication {
            mountStatusAllRoutes()

            // order=invalid AND limit=0 are both invalid; the order check runs
            // first, so its message must win (pins early-return ordering).
            val response = client.get("/api/v1/status/all?order=invalid&limit=0")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                expectedBody("Invalid order parameter. Must be 'asc' or 'desc'"),
                response.bodyAsText(),
            )
        }

    @Test
    fun `limit is validated before offset when both are invalid`() =
        testApplication {
            mountStatusAllRoutes()

            // limit=0 AND offset=-1 are both invalid; limit check runs first.
            val response = client.get("/api/v1/status/all?limit=0&offset=-1")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                expectedBody("Invalid limit parameter. Must be between 1 and 1000"),
                response.bodyAsText(),
            )
        }

    @Test
    fun `offset is validated before status types when both are invalid`() =
        testApplication {
            mountStatusAllRoutes()

            // offset=-1 AND types=INVALID are both invalid; offset check runs first.
            val response = client.get("/api/v1/status/all?offset=-1&types=INVALID")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                expectedBody("Invalid offset parameter. Must be >= 0"),
                response.bodyAsText(),
            )
        }

    @Test
    fun `more than ten distinct tag filters returns 400 with exact body`() =
        testApplication {
            mountStatusAllRoutes()

            // 11 distinct tags is one over the MAX_TAG_QUERY_PARAMS cap (§12 rule 4).
            val query = (1..11).joinToString("&") { "tag=k:v$it" }
            val response = client.get("/api/v1/status/all?$query")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                expectedBody("At most 10 tag filters are allowed"),
                response.bodyAsText(),
            )
        }

    @Test
    fun `denial_of_service - a tag filter longer than the max length returns 400 with exact body`() =
        testApplication {
            mountStatusAllRoutes()

            // One character past MAX_TAG_QUERY_LENGTH (193): longer than any storable
            // key:value tag, so it can match nothing and is refused before the DB bind (§12 rule 4).
            val overLong = "k:" + "v".repeat(homer.app.models.TagValidation.MAX_TAG_QUERY_LENGTH)
            val response = client.get("/api/v1/status/all?tag=$overLong")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                expectedBody(
                    "A tag filter may be at most ${homer.app.models.TagValidation.MAX_TAG_QUERY_LENGTH} characters",
                ),
                response.bodyAsText(),
            )
        }

    @Test
    fun `a tag filter exactly at the max length reaches the handler body and returns 200`() =
        testApplication {
            mountStatusAllRoutes()

            // MAX_TAG_QUERY_LENGTH (193) chars exactly: the longest storable tag, must pass.
            val maxLen = "k:" + "v".repeat(homer.app.models.TagValidation.MAX_TAG_QUERY_LENGTH - 2)
            val response = client.get("/api/v1/status/all?tag=$maxLen")

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `exactly ten tag filters reach the handler body and return 200`() =
        testApplication {
            mountStatusAllRoutes()

            val query = (1..10).joinToString("&") { "tag=k:v$it" }
            val response = client.get("/api/v1/status/all?$query")

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `duplicate tag filters collapse below the cap and return 200`() =
        testApplication {
            mountStatusAllRoutes()

            // 11 params but only one distinct value: de-dup drops it to a single
            // predicate, so the cap is not tripped (mirrors the query builder).
            val query = (1..11).joinToString("&") { "tag=mr:15" }
            val response = client.get("/api/v1/status/all?$query")

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `non-positive or non-numeric rootProcessId returns 400 with exact body`() =
        testApplication {
            mountStatusAllRoutes()

            for (value in listOf("abc", "0", "-1", "1.5")) {
                val response = client.get("/api/v1/status/all?rootProcessId=$value")
                assertEquals(HttpStatusCode.BadRequest, response.status, "value '$value'")
                assertEquals(
                    expectedBody("Invalid rootProcessId parameter. Must be a positive integer"),
                    response.bodyAsText(),
                )
            }
        }

    @Test
    fun `a blank rootProcessId is treated as absent`() =
        testApplication {
            mountStatusAllRoutes()

            // An empty value is what a console filter clearing its input sends; it must
            // read as "no filter", not as a validation error.
            val response = client.get("/api/v1/status/all?rootProcessId=")

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `roots accepts only the literal true and false`() =
        testApplication {
            mountStatusAllRoutes()

            for (value in listOf("TRUE", "True", " true ")) {
                assertEquals(HttpStatusCode.OK, client.get("/api/v1/status/all?roots=$value").status, "value '$value'")
            }
            for (value in listOf("yes", "1", "on", "tru")) {
                val response = client.get("/api/v1/status/all?roots=$value")
                assertEquals(HttpStatusCode.BadRequest, response.status, "value '$value'")
                assertEquals(
                    expectedBody("Invalid roots parameter. Must be 'true' or 'false'"),
                    response.bodyAsText(),
                )
            }
        }

    @Test
    fun `rootProcessId is validated before the tag cap`() =
        testApplication {
            mountStatusAllRoutes()

            // Both are invalid; rootProcessId's check sits before the tag cap, so a
            // typo'd id is reported rather than the tag count it happened to ride in with.
            val query = (1..11).joinToString("&") { "tag=k:v$it" }
            val response = client.get("/api/v1/status/all?rootProcessId=abc&$query")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                expectedBody("Invalid rootProcessId parameter. Must be a positive integer"),
                response.bodyAsText(),
            )
        }
}
