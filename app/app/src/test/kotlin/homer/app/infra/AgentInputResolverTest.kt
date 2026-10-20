package homer.app.infra

import homer.app.exceptions.PayloadSchemaValidationException
import homer.app.exceptions.QueryParamValidationException
import homer.app.exceptions.ValidationException
import homer.app.fixtures.AgentFixtures
import homer.app.models.Agent
import homer.app.models.Param
import homer.app.models.ParamSource
import homer.app.models.ParamType
import homer.app.models.Transformer
import homer.app.routes.resolve
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import io.ktor.http.contentType
import io.ktor.http.parametersOf
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for AgentInputResolver
 * Tests query parameter extraction and validation logic
 */
class AgentInputResolverTest {
    private lateinit var resolver: AgentInputResolver

    @BeforeEach
    fun setup() {
        resolver = AgentInputResolver()
    }

    // Helper function to create Parameters from a map
    private fun createParameters(params: Map<String, String>): Parameters {
        val builder = ParametersBuilder()
        params.forEach { (key, value) -> builder.append(key, value) }
        return builder.build()
    }

    // Resolves a GET carrying only [query] — no headers, and a body that must not be read.
    private fun resolveQuery(
        query: Parameters,
        agent: Agent,
        enforceRequired: Boolean = true,
    ): Map<String, String> =
        runBlocking {
            resolver.resolve(HttpMethod.Get, query, Headers.Empty, { error("body read for a query-only agent") }, agent, enforceRequired)
        }

    // Helper function to create an agent with query params configuration
    private fun createAgent(params: List<Param>? = null): Agent =
        AgentFixtures.simpleAgent().copy(
            queryParams = params,
        )

    // Helper to create agent with single parameter
    private fun createAgentWithParam(
        paramName: String,
        type: ParamType,
        envName: String = paramName,
        required: Boolean = true,
        transformer: homer.app.models.Transformer = homer.app.models.Transformer.Pass,
    ): Agent =
        AgentFixtures.simpleAgent().copy(
            queryParams =
                listOf(
                    Param(
                        paramName = paramName,
                        envName = envName,
                        type = type,
                        required = required,
                        transformer = transformer,
                    ),
                ),
        )

    @Test
    fun `resolveDebug returns empty map when features is null`() {
        val agent = AgentFixtures.simpleAgent().copy(queryParams = null)
        val params = createParameters(mapOf("foo" to "bar"))

        val result =
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `resolveDebug returns empty map when queryParamsToEnv is null`() {
        val agent = createAgent(params = null)
        val params = createParameters(mapOf("foo" to "bar"))

        val result =
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `resolveDebug returns empty map when queryParamsToEnv is empty`() {
        val agent = createAgent(params = emptyList())
        val params = createParameters(mapOf("foo" to "bar"))

        val result =
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `resolveDebug validates and returns single STRING parameter`() {
        val agent = createAgentWithParam("name", ParamType.STRING)
        val params = createParameters(mapOf("name" to "value"))

        val result =
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )

        assertEquals(mapOf("name" to "value"), result)
    }

    @Test
    fun `resolveDebug validates and returns single NUMBER parameter`() {
        val agent = createAgentWithParam("count", ParamType.NUMBER)
        val params = createParameters(mapOf("count" to "42"))

        val result =
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )

        assertEquals(mapOf("count" to "42"), result)
    }

    @Test
    fun `resolveDebug validates and returns single TEXT parameter`() {
        val agent = createAgentWithParam("description", ParamType.ANY)
        val params = createParameters(mapOf("description" to "some text with spaces"))

        val result =
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )

        assertEquals(mapOf("description" to "some text with spaces"), result)
    }

    @Test
    fun `resolveDebug validates and returns multiple mixed parameters`() {
        val agent =
            createAgent(
                params =
                    listOf(
                        Param("name", type = ParamType.STRING),
                        Param("count", type = ParamType.NUMBER),
                        Param("description", type = ParamType.ANY),
                    ),
            )
        val params =
            createParameters(
                mapOf(
                    "name" to "test",
                    "count" to "123",
                    "description" to "a longer description",
                ),
            )

        val result =
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )

        assertEquals(
            mapOf(
                "name" to "test",
                "count" to "123",
                "description" to "a longer description",
            ),
            result,
        )
    }

    @Test
    fun `resolveDebug throws QueryParamValidationException when parameter is missing`() {
        val agent = createAgentWithParam("required", ParamType.STRING, required = true)
        val params = createParameters(emptyMap())

        val exception =
            assertThrows<QueryParamValidationException> {
                resolver.resolveDebug(queryParams = params, bodyJson = null, headers = emptyMap(), agent = agent, enforceRequired = true)
            }

        assertEquals("Parameter 'required' is missing", exception.message)
        assertEquals(HttpStatusCode.BadRequest, exception.httpStatusCode)
        assertEquals("required", exception.paramName)
    }

    @Test
    fun `resolveDebug throws QueryParamValidationException when NUMBER parameter is invalid`() {
        val agent = createAgentWithParam("count", ParamType.NUMBER)
        val params = createParameters(mapOf("count" to "not-a-number"))

        val exception =
            assertThrows<QueryParamValidationException> {
                resolver.resolveDebug(queryParams = params, bodyJson = null, headers = emptyMap(), agent = agent, enforceRequired = true)
            }

        assertEquals("Parameter 'count' is not valid NUMBER", exception.message)
        assertEquals(HttpStatusCode.BadRequest, exception.httpStatusCode)
        assertEquals("count", exception.paramName)
    }

    @Test
    fun `resolveDebug throws QueryParamValidationException when STRING parameter is invalid`() {
        val agent = createAgentWithParam("name", ParamType.STRING)
        val params = createParameters(mapOf("name" to "has spaces"))

        val exception =
            assertThrows<QueryParamValidationException> {
                resolver.resolveDebug(queryParams = params, bodyJson = null, headers = emptyMap(), agent = agent, enforceRequired = true)
            }

        assertEquals("Parameter 'name' is not valid STRING", exception.message)
        assertEquals(HttpStatusCode.BadRequest, exception.httpStatusCode)
        assertEquals("name", exception.paramName)
    }

    @Test
    fun `resolveDebug handles empty string parameter value`() {
        val agent = createAgentWithParam("name", ParamType.STRING)
        val params = createParameters(mapOf("name" to ""))

        val result =
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )

        assertEquals(mapOf("name" to ""), result)
    }

    @Test
    fun `resolveDebug handles zero as valid NUMBER`() {
        val agent = createAgentWithParam("count", ParamType.NUMBER)
        val params = createParameters(mapOf("count" to "0"))

        val result =
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )

        assertEquals(mapOf("count" to "0"), result)
    }

    @Test
    fun `resolveDebug handles negative numbers`() {
        val agent = createAgentWithParam("offset", ParamType.NUMBER)
        val params = createParameters(mapOf("offset" to "-42"))

        val result =
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )

        assertEquals(mapOf("offset" to "-42"), result)
    }

    @Test
    fun `resolveDebug handles multiline text`() {
        val agent = createAgentWithParam("description", ParamType.ANY)
        val multilineText = "line1\nline2\nline3"
        val params = createParameters(mapOf("description" to multilineText))

        val result =
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )

        assertEquals(mapOf("description" to multilineText), result)
    }

    @Test
    fun `resolveDebug rejects decimal numbers for NUMBER type`() {
        val agent = createAgentWithParam("count", ParamType.NUMBER)
        val params = createParameters(mapOf("count" to "3.14"))

        val exception =
            assertThrows<QueryParamValidationException> {
                resolver.resolveDebug(queryParams = params, bodyJson = null, headers = emptyMap(), agent = agent, enforceRequired = true)
            }

        assertEquals("Parameter 'count' is not valid NUMBER", exception.message)
    }

    @Test
    fun `resolveDebug throws exception with correct paramName for first invalid param in multiple`() {
        val agent =
            createAgent(
                params =
                    listOf(
                        Param("param1", type = ParamType.STRING),
                        Param("param2", type = ParamType.STRING),
                    ),
            )
        val params = createParameters(mapOf("param1" to "valid"))
        // param2 is missing

        val exception =
            assertThrows<QueryParamValidationException> {
                resolver.resolveDebug(queryParams = params, bodyJson = null, headers = emptyMap(), agent = agent, enforceRequired = true)
            }

        assertEquals("Parameter 'param2' is missing", exception.message)
        assertEquals("param2", exception.paramName)
    }

    @Test
    fun `present but invalid optional param fails with 400 instead of silent drop`() {
        val agent =
            bodyAgent(
                Param(paramName = "limit", envName = "LIMIT", type = ParamType.NUMBER, required = false),
            )
        val e =
            assertThrows<QueryParamValidationException> {
                resolver.resolveDebug(
                    queryParams = parametersOf("limit", "not-a-number"),
                    bodyJson = null,
                    headers = emptyMap(),
                    agent = agent,
                    enforceRequired = true,
                )
            }
        assertEquals("limit", e.paramName)
    }

    @Test
    fun `absent optional param is omitted`() {
        val agent =
            bodyAgent(
                Param(paramName = "limit", envName = "LIMIT", type = ParamType.NUMBER, required = false),
            )
        val result = resolver.resolveDebug(Parameters.Empty, null, emptyMap(), agent, enforceRequired = true)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `jsonPath miss on a present value fails with 400 when strict`() {
        val agent =
            bodyAgent(
                Param(
                    paramName = "config",
                    envName = "API_KEY",
                    type = ParamType.ANY,
                    required = false,
                    transformer = Transformer.JsonPath("$.apiKey"),
                ),
            )
        assertThrows<QueryParamValidationException> {
            resolver.resolveDebug(parametersOf("config", """{"other":1}"""), null, emptyMap(), agent, enforceRequired = true)
        }
    }

    @Test
    fun `enforceRequired=false keeps dropping invalid values silently`() {
        val agent =
            bodyAgent(
                Param(paramName = "limit", envName = "LIMIT", type = ParamType.NUMBER, required = false),
            )
        val result = resolver.resolveDebug(parametersOf("limit", "nope"), null, emptyMap(), agent, enforceRequired = false)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `exception properties are accessible and correct`() {
        val exception =
            QueryParamValidationException(
                message = "Test error",
                httpStatusCode = HttpStatusCode.BadRequest,
                paramName = "testParam",
            )

        assertEquals("Test error", exception.message)
        assertEquals(HttpStatusCode.BadRequest, exception.httpStatusCode)
        assertEquals("testParam", exception.paramName)
    }

    @Test
    fun `exception defaults httpStatusCode to BadRequest when not specified`() {
        val exception =
            QueryParamValidationException(
                message = "Test error",
                paramName = "testParam",
            )

        assertEquals(HttpStatusCode.BadRequest, exception.httpStatusCode)
    }

    @Test
    fun `exception allows null paramName`() {
        val exception =
            QueryParamValidationException(
                message = "Test error",
            )

        assertEquals("Test error", exception.message)
        assertEquals(HttpStatusCode.BadRequest, exception.httpStatusCode)
        assertEquals(null, exception.paramName)
    }

    @Test
    fun `resolveDebug skips missing required params when enforceRequired is false`() {
        // Regression: debug runs allow the user to omit required params to
        // exercise the env-only path. Missing required params must not throw.
        val agent = createAgentWithParam("required", ParamType.STRING, required = true)
        val params = createParameters(emptyMap())

        val result =
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = false,
            )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `resolveDebug still returns supplied params when enforceRequired is false`() {
        val agent =
            createAgent(
                params =
                    listOf(
                        Param("a", "A", type = ParamType.STRING, required = true, transformer = homer.app.models.Transformer.Pass),
                        Param("b", "B", type = ParamType.STRING, required = true, transformer = homer.app.models.Transformer.Pass),
                    ),
            )
        val params = createParameters(mapOf("a" to "hello"))

        val result =
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = false,
            )

        assertEquals(mapOf("A" to "hello"), result)
    }

    @Test
    fun `resolve reads QUERY src and maps to envName`() {
        val agent =
            AgentFixtures.simpleAgent().copy(
                inputs =
                    listOf(
                        Param(
                            paramName = "name",
                            envName = "NAME",
                            src = ParamSource.QUERY,
                            type = ParamType.STRING,
                        ),
                    ),
            )
        val query = createParameters(mapOf("name" to "alice"))

        val result = resolveQuery(query, agent)

        assertEquals(mapOf("NAME" to "alice"), result)
    }

    @Test
    fun `resolve returns empty map when effectiveInputs is null`() {
        val agent = AgentFixtures.simpleAgent().copy(inputs = null, queryParams = null)
        val query = createParameters(mapOf("foo" to "bar"))

        val result = resolveQuery(query, agent)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `resolve falls back to legacy queryParams via effectiveInputs`() {
        val agent =
            AgentFixtures.simpleAgent().copy(
                queryParams =
                    listOf(
                        Param(paramName = "legacy", envName = "LEGACY", type = ParamType.STRING),
                    ),
            )
        val query = createParameters(mapOf("legacy" to "ok"))

        val result = resolveQuery(query, agent)

        assertEquals(mapOf("LEGACY" to "ok"), result)
    }

    @Test
    fun `resolve throws QueryParamValidationException for missing required QUERY param`() {
        val agent =
            AgentFixtures.simpleAgent().copy(
                inputs =
                    listOf(
                        Param(
                            paramName = "required",
                            envName = "REQUIRED",
                            src = ParamSource.QUERY,
                            type = ParamType.STRING,
                            required = true,
                        ),
                    ),
            )
        val query = Parameters.Empty

        val exception =
            assertThrows<QueryParamValidationException> {
                resolveQuery(query, agent)
            }

        assertEquals("Parameter 'required' is missing", exception.message)
        assertEquals("required", exception.paramName)
    }

    @Test
    fun `resolve - two JsonPath params with different raw values - each uses its own raw`() {
        // Regression: prior implementation cached the first param's parsed JsonPath
        // DocumentContext across the entire loop, so a second JsonPath param would
        // read from the first param's raw value instead of its own. This will matter
        // once BODY params land — each param must parse its own raw exactly once.
        val agent =
            AgentFixtures.simpleAgent().copy(
                inputs =
                    listOf(
                        Param(
                            paramName = "a",
                            envName = "A",
                            src = ParamSource.QUERY,
                            type = ParamType.ANY,
                            transformer = Transformer.JsonPath(path = "$.id"),
                        ),
                        Param(
                            paramName = "b",
                            envName = "B",
                            src = ParamSource.QUERY,
                            type = ParamType.ANY,
                            transformer = Transformer.JsonPath(path = "$.id"),
                        ),
                    ),
            )
        val query =
            createParameters(
                mapOf(
                    "a" to """{"id":"alpha"}""",
                    "b" to """{"id":"beta"}""",
                ),
            )

        val result = resolveQuery(query, agent)

        assertEquals(mapOf("A" to "alpha", "B" to "beta"), result)
    }

    private fun bodyAgent(vararg params: Param) = AgentFixtures.simpleAgent().copy(inputs = params.toList())

    @Test
    fun `resolve - body top-level scalar`() =
        testApplication {
            application {
                routing {
                    post("/probe") {
                        val agent = bodyAgent(Param("user", "USER", ParamSource.BODY, ParamType.STRING))
                        val out = resolver.resolve(call, agent)
                        call.respondText(out["USER"] ?: "NONE")
                    }
                }
            }
            val resp =
                client.post("/probe") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"user":"alice"}""")
                }
            assertEquals("alice", resp.bodyAsText())
        }

    @Test
    fun `resolve - body nested via jsonPath transformer`() =
        testApplication {
            application {
                routing {
                    post("/probe") {
                        val agent =
                            bodyAgent(
                                Param(
                                    "payload",
                                    "ID",
                                    ParamSource.BODY,
                                    ParamType.ANY,
                                    transformer = Transformer.JsonPath("$.service.id"),
                                ),
                            )
                        val out = resolver.resolve(call, agent)
                        call.respondText(out["ID"] ?: "NONE")
                    }
                }
            }
            val resp =
                client.post("/probe") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"payload":{"service":{"id":"42"}}}""")
                }
            assertEquals("42", resp.bodyAsText())
        }

    @Test
    fun `resolve - GET with body input throws 400`() =
        testApplication {
            application {
                install(io.ktor.server.plugins.statuspages.StatusPages) {
                    exception<QueryParamValidationException> { call, ex ->
                        call.respond(HttpStatusCode.BadRequest, ex.message ?: "")
                    }
                }
                routing {
                    get("/probe") {
                        val agent = bodyAgent(Param("user", "USER", ParamSource.BODY))
                        resolver.resolve(call, agent)
                        call.respondText("UNEXPECTED")
                    }
                }
            }
            val resp = client.get("/probe")
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertContains(resp.bodyAsText(), "requires request body")
        }

    @Test
    fun `resolve - GET on an optional-only body input still requires POST`() =
        testApplication {
            // ADR-0072: the bodyless relaxation is POST-only. Every body input optional
            // must not let a bodyless GET through — the 400 must come from the method
            // check, not from requiredness.
            application {
                install(io.ktor.server.plugins.statuspages.StatusPages) {
                    exception<QueryParamValidationException> { call, ex ->
                        call.respond(HttpStatusCode.BadRequest, ex.message ?: "")
                    }
                }
                routing {
                    get("/probe") {
                        val agent =
                            bodyAgent(
                                Param("user", "USER", ParamSource.BODY, ParamType.STR, required = false, default = "anon"),
                            )
                        resolver.resolve(call, agent)
                        call.respondText("UNEXPECTED")
                    }
                }
            }
            val resp = client.get("/probe")
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertContains(resp.bodyAsText(), "requires request body; use POST")
        }

    @Test
    fun `CWE-248 - a malformed Content-Type is a 400 validation failure, not an unhandled parse error`() {
        val agent = bodyAgent(Param("user", "USER", ParamSource.BODY, ParamType.STRING))
        val headers = Headers.build { append(HttpHeaders.ContentType, "application") }

        val thrown =
            assertThrows<QueryParamValidationException> {
                runBlocking { resolver.resolve(HttpMethod.Post, Parameters.Empty, headers, { """{"user":"alice"}""" }, agent) }
            }

        assertEquals("Invalid Content-Type header: 'application'", thrown.message)
        assertEquals(HttpStatusCode.BadRequest, thrown.httpStatusCode)
    }

    @Test
    fun `resolve - wrong Content-Type throws 400`() =
        testApplication {
            application {
                install(io.ktor.server.plugins.statuspages.StatusPages) {
                    exception<QueryParamValidationException> { call, ex ->
                        call.respond(HttpStatusCode.BadRequest, ex.message ?: "")
                    }
                }
                routing {
                    post("/probe") {
                        val agent = bodyAgent(Param("user", "USER", ParamSource.BODY))
                        resolver.resolve(call, agent)
                        call.respondText("UNEXPECTED")
                    }
                }
            }
            val resp =
                client.post("/probe") {
                    contentType(ContentType.Text.Plain)
                    setBody("user=alice")
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertContains(resp.bodyAsText(), "Content-Type")
        }

    @Test
    fun `resolve - malformed JSON throws 400`() =
        testApplication {
            application {
                install(io.ktor.server.plugins.statuspages.StatusPages) {
                    exception<QueryParamValidationException> { call, ex ->
                        call.respond(HttpStatusCode.BadRequest, ex.message ?: "")
                    }
                }
                routing {
                    post("/probe") {
                        val agent = bodyAgent(Param("user", "USER", ParamSource.BODY))
                        resolver.resolve(call, agent)
                        call.respondText("UNEXPECTED")
                    }
                }
            }
            val resp =
                client.post("/probe") {
                    contentType(ContentType.Application.Json)
                    setBody("not-json")
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertContains(resp.bodyAsText(), "Invalid JSON body")
        }

    @Test
    fun `resolve - JSON array body throws 400 with 'expected an object' message`() =
        testApplication {
            application {
                install(io.ktor.server.plugins.statuspages.StatusPages) {
                    exception<QueryParamValidationException> { call, ex ->
                        call.respond(HttpStatusCode.BadRequest, ex.message ?: "")
                    }
                }
                routing {
                    post("/probe") {
                        val agent = bodyAgent(Param("user", "USER", ParamSource.BODY))
                        resolver.resolve(call, agent)
                        call.respondText("UNEXPECTED")
                    }
                }
            }
            val resp =
                client.post("/probe") {
                    contentType(ContentType.Application.Json)
                    setBody("[1,2,3]")
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertContains(resp.bodyAsText(), "expected an object")
        }

    @Test
    fun `resolve - empty body with JSON Content-Type applies the optional body input default`() =
        testApplication {
            application {
                routing {
                    post("/probe") {
                        val agent =
                            bodyAgent(
                                Param("user", "USER", ParamSource.BODY, ParamType.STR, required = false, default = "anon"),
                            )
                        val out = resolver.resolve(call, agent)
                        call.respondText(out["USER"] ?: "NONE")
                    }
                }
            }
            val resp =
                client.post("/probe") {
                    contentType(ContentType.Application.Json)
                    setBody("")
                }
            assertEquals("anon", resp.bodyAsText())
        }

    @Test
    fun `resolve - empty body without Content-Type applies the optional body input default`() =
        testApplication {
            application {
                routing {
                    post("/probe") {
                        val agent =
                            bodyAgent(
                                Param("user", "USER", ParamSource.BODY, ParamType.STR, required = false, default = "anon"),
                            )
                        val out = resolver.resolve(call, agent)
                        call.respondText(out["USER"] ?: "NONE")
                    }
                }
            }
            // No Content-Type at all — the empty-body check runs before the Content-Type gate.
            val resp = client.post("/probe") { setBody("") }
            assertEquals("anon", resp.bodyAsText())
        }

    @Test
    fun `resolve - whitespace-only body without Content-Type resolves to an empty object`() =
        testApplication {
            application {
                routing {
                    post("/probe") {
                        val agent =
                            bodyAgent(
                                Param("user", "USER", ParamSource.BODY, ParamType.STR, required = false, default = "anon"),
                            )
                        val out = resolver.resolve(call, agent)
                        call.respondText(out["USER"] ?: "NONE")
                    }
                }
            }
            // A trailing newline (echo | curl --data-binary @-) must not fall through to
            // the Content-Type gate — a blank body counts as empty, not just zero bytes.
            val resp = client.post("/probe") { setBody("\n") }
            assertEquals("anon", resp.bodyAsText())
        }

    @Test
    fun `resolve - empty body with a required body input names the parameter`() =
        testApplication {
            application {
                install(io.ktor.server.plugins.statuspages.StatusPages) {
                    exception<QueryParamValidationException> { call, ex ->
                        call.respond(HttpStatusCode.BadRequest, ex.message ?: "")
                    }
                }
                routing {
                    post("/probe") {
                        val agent = bodyAgent(Param("user", "USER", ParamSource.BODY))
                        resolver.resolve(call, agent)
                        call.respondText("UNEXPECTED")
                    }
                }
            }
            val resp =
                client.post("/probe") {
                    contentType(ContentType.Application.Json)
                    setBody("")
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertContains(resp.bodyAsText(), "Parameter 'user' is missing")
        }

    @Test
    fun `resolve - non-empty chunked body is parsed, not treated as empty`() =
        testApplication {
            application {
                routing {
                    post("/probe") {
                        val agent = bodyAgent(Param("user", "USER", ParamSource.BODY))
                        val out = resolver.resolve(call, agent)
                        call.respondText(out["USER"] ?: "NONE")
                    }
                }
            }
            // ReadChannelContent carries no Content-Length → the body is sent chunked.
            val resp =
                client.post("/probe") {
                    setBody(
                        object : io.ktor.http.content.OutgoingContent.ReadChannelContent() {
                            override val contentType = ContentType.Application.Json

                            override fun readFrom() =
                                io.ktor.utils.io
                                    .ByteReadChannel("""{"user":"alice"}""".toByteArray())
                        },
                    )
                }
            assertEquals("alice", resp.bodyAsText())
        }

    @Test
    fun `resolve - schema agent with empty body still throws 400`() =
        testApplication {
            application {
                install(io.ktor.server.plugins.statuspages.StatusPages) {
                    exception<QueryParamValidationException> { call, ex ->
                        call.respond(HttpStatusCode.BadRequest, ex.message ?: "")
                    }
                }
                routing {
                    post("/probe") {
                        val agent =
                            bodyAgent(Param("user", "USER", ParamSource.BODY, required = false))
                                .copy(inputSchema = buildJsonObject { put("type", "object") })
                        resolver.resolve(call, agent)
                        call.respondText("UNEXPECTED")
                    }
                }
            }
            val resp =
                client.post("/probe") {
                    contentType(ContentType.Application.Json)
                    setBody("")
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }

    @Test
    fun `resolve - JsonNull body value treated as missing`() =
        testApplication {
            application {
                routing {
                    post("/probe") {
                        val agent = bodyAgent(Param("user", "USER", ParamSource.BODY, required = false))
                        val out = resolver.resolve(call, agent)
                        call.respondText(if (out.isEmpty()) "EMPTY" else out.toString())
                    }
                }
            }
            val resp =
                client.post("/probe") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"user":null}""")
                }
            assertEquals("EMPTY", resp.bodyAsText())
        }

    @Test
    fun `resolve - application_vnd_api+json accepted`() =
        testApplication {
            application {
                routing {
                    post("/probe") {
                        val agent = bodyAgent(Param("user", "USER", ParamSource.BODY))
                        val out = resolver.resolve(call, agent)
                        call.respondText(out["USER"] ?: "NONE")
                    }
                }
            }
            val resp =
                client.post("/probe") {
                    contentType(ContentType.parse("application/vnd.api+json"))
                    setBody("""{"user":"alice"}""")
                }
            assertEquals("alice", resp.bodyAsText())
        }

    @Test
    fun `resolve - header case-insensitive lookup`() =
        testApplication {
            application {
                routing {
                    post("/probe") {
                        val agent =
                            AgentFixtures.simpleAgent().copy(
                                inputs = listOf(Param("X-Trace-Id", "TRACE", ParamSource.HEADER, ParamType.ANY)),
                            )
                        val out = resolver.resolve(call, agent)
                        call.respondText(out["TRACE"] ?: "NONE")
                    }
                }
            }
            // Lowercase request header, agent declared title-case → still matches.
            val resp = client.post("/probe") { header("x-trace-id", "abc") }
            assertEquals("abc", resp.bodyAsText())
        }

    @Test
    fun `resolve - missing optional header is skipped`() =
        testApplication {
            application {
                routing {
                    post("/probe") {
                        val agent =
                            AgentFixtures.simpleAgent().copy(
                                inputs =
                                    listOf(
                                        Param(
                                            paramName = "X-Trace-Id",
                                            envName = "TRACE",
                                            src = ParamSource.HEADER,
                                            required = false,
                                        ),
                                    ),
                            )
                        val out = resolver.resolve(call, agent)
                        call.respondText(if (out.isEmpty()) "EMPTY" else out.toString())
                    }
                }
            }
            val resp = client.post("/probe")
            assertEquals("EMPTY", resp.bodyAsText())
        }

    @Test
    fun `resolve - missing required header throws 400`() =
        testApplication {
            application {
                install(io.ktor.server.plugins.statuspages.StatusPages) {
                    exception<QueryParamValidationException> { call, ex ->
                        call.respond(HttpStatusCode.BadRequest, ex.message ?: "")
                    }
                }
                routing {
                    post("/probe") {
                        val agent =
                            AgentFixtures.simpleAgent().copy(
                                inputs =
                                    listOf(
                                        Param(
                                            paramName = "X-Trace-Id",
                                            envName = "TRACE",
                                            src = ParamSource.HEADER,
                                            required = true,
                                        ),
                                    ),
                            )
                        resolver.resolve(call, agent)
                        call.respondText("UNEXPECTED")
                    }
                }
            }
            val resp = client.post("/probe")
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertContains(resp.bodyAsText(), "X-Trace-Id")
        }

    @Test
    fun `resolve skips missing required params when enforceRequired is false`() {
        val agent =
            AgentFixtures.simpleAgent().copy(
                inputs =
                    listOf(
                        Param(
                            paramName = "required",
                            envName = "REQUIRED",
                            src = ParamSource.QUERY,
                            type = ParamType.STRING,
                            required = true,
                        ),
                    ),
            )
        val query = Parameters.Empty

        val result = resolveQuery(query, agent, enforceRequired = false)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `resolveDebug returns empty map when agent has no inputs`() {
        val agent = AgentFixtures.simpleAgent().copy(inputs = null, queryParams = null)

        val result =
            resolver.resolveDebug(
                queryParams = Parameters.Empty,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
            )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `resolveDebug extracts query, body and header sources via JSON payload`() {
        val agent =
            AgentFixtures.simpleAgent().copy(
                inputs =
                    listOf(
                        Param("q", "Q", ParamSource.QUERY, ParamType.STRING),
                        Param("user", "USER", ParamSource.BODY, ParamType.STRING),
                        Param("X-Trace-Id", "TRACE", ParamSource.HEADER, ParamType.STRING),
                    ),
            )

        val result =
            resolver.resolveDebug(
                queryParams = createParameters(mapOf("q" to "hi")),
                bodyJson = buildJsonObject { put("user", "alice") },
                headers = mapOf("X-Trace-Id" to "abc"),
                agent = agent,
            )

        assertEquals(mapOf("Q" to "hi", "USER" to "alice", "TRACE" to "abc"), result)
    }

    @Test
    fun `resolveDebug header lookup is case-insensitive`() {
        val agent =
            AgentFixtures.simpleAgent().copy(
                inputs =
                    listOf(
                        Param("X-Trace-Id", "TRACE", ParamSource.HEADER, ParamType.STRING),
                    ),
            )

        // Caller sends the header lower-cased; the param spec is in PascalCase.
        val result =
            resolver.resolveDebug(
                queryParams = Parameters.Empty,
                bodyJson = null,
                headers = mapOf("x-trace-id" to "abc"),
                agent = agent,
            )

        assertEquals(mapOf("TRACE" to "abc"), result)
    }

    @Test
    fun `resolveDebug defaults enforceRequired to false (skips missing required params)`() {
        val agent =
            AgentFixtures.simpleAgent().copy(
                inputs =
                    listOf(
                        Param("user", "USER", ParamSource.BODY, ParamType.STRING, required = true),
                    ),
            )

        val result =
            resolver.resolveDebug(
                queryParams = Parameters.Empty,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
            )

        assertTrue(result.isEmpty())
    }

    private fun schemaObj(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject

    private val deploySchema =
        schemaObj(
            """
            {
              "type": "object",
              "required": ["service"],
              "properties": { "service": { "type": "string" } },
              "additionalProperties": false
            }
            """.trimIndent(),
        )

    @Test
    fun `resolve - conforming body passes schema and extracts inputs`() =
        testApplication {
            application {
                routing {
                    post("/probe") {
                        val agent =
                            bodyAgent(Param("service", "SERVICE", ParamSource.BODY, ParamType.STRING))
                                .copy(inputSchema = deploySchema)
                        val out = resolver.resolve(call, agent)
                        call.respondText(out["SERVICE"] ?: "NONE")
                    }
                }
            }
            val resp =
                client.post("/probe") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"service":"api"}""")
                }
            assertEquals("api", resp.bodyAsText())
        }

    @Test
    fun `resolve - schema violation throws PayloadSchemaValidationException`() =
        testApplication {
            application {
                install(io.ktor.server.plugins.statuspages.StatusPages) {
                    exception<PayloadSchemaValidationException> { call, ex ->
                        call.respond(HttpStatusCode.UnprocessableEntity, ex.message ?: "")
                    }
                }
                routing {
                    post("/probe") {
                        val agent =
                            bodyAgent(Param("service", "SERVICE", ParamSource.BODY, ParamType.STRING))
                                .copy(inputSchema = deploySchema)
                        resolver.resolve(call, agent)
                        call.respondText("UNEXPECTED")
                    }
                }
            }
            val resp =
                client.post("/probe") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"wrong":"field"}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
            assertContains(resp.bodyAsText(), "Payload failed schema validation")
        }

    @Test
    fun `resolve - schema with no body inputs still reads and validates body`() =
        testApplication {
            application {
                install(io.ktor.server.plugins.statuspages.StatusPages) {
                    exception<PayloadSchemaValidationException> { call, ex ->
                        call.respond(HttpStatusCode.UnprocessableEntity, ex.message ?: "")
                    }
                }
                routing {
                    post("/probe") {
                        // No inputs at all — schema is the only reason to read the body.
                        val agent = AgentFixtures.simpleAgent().copy(inputs = null, inputSchema = deploySchema)
                        resolver.resolve(call, agent)
                        call.respondText("OK")
                    }
                }
            }
            val resp =
                client.post("/probe") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"nope":1}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
        }

    @Test
    fun `resolveDebug - schema violation throws PayloadSchemaValidationException`() {
        val agent = AgentFixtures.simpleAgent().copy(inputs = null, inputSchema = deploySchema)
        assertThrows<PayloadSchemaValidationException> {
            resolver.resolveDebug(
                queryParams = Parameters.Empty,
                bodyJson = schemaObj("""{"wrong":"x"}"""),
                headers = emptyMap(),
                agent = agent,
            )
        }
    }

    // --- resolveFromParams / validateSatisfiable (internal callers: RunAgent, dispatch, continuation) ---

    @Test
    fun `resolveFromParams satisfies a declared BODY input`() {
        val agent = bodyAgent(Param(paramName = "user", envName = "USER_NAME", src = ParamSource.BODY, type = ParamType.ANY))

        val result = resolver.resolveFromParams(mapOf("user" to "alice"), agent)

        assertEquals(mapOf("USER_NAME" to "alice"), result)
    }

    @Test
    fun `resolveFromParams never satisfies a HEADER input`() {
        val agent =
            bodyAgent(
                Param(paramName = "X-Token", envName = "TOKEN", src = ParamSource.HEADER, type = ParamType.ANY, required = true),
            )

        assertThrows<QueryParamValidationException> {
            resolver.resolveFromParams(mapOf("X-Token" to "sekrit"), agent)
        }
    }

    @Test
    fun `resolveFromParams validates inputSchema against the routed body`() {
        val schema =
            Json.parseToJsonElement(
                """{"type":"object","required":["user"],"properties":{"user":{"type":"string"}}}""",
            ) as JsonObject
        val agent =
            bodyAgent(
                Param(paramName = "user", envName = "USER_NAME", src = ParamSource.BODY, type = ParamType.ANY),
            ).copy(inputSchema = schema)

        assertThrows<PayloadSchemaValidationException> {
            resolver.resolveFromParams(emptyMap(), agent)
        }
    }

    @Test
    fun `resolveFromParams keeps reserved control params out of the schema-validated body`() {
        val schema =
            Json.parseToJsonElement(
                """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "properties": { "user": { "type": "string" } },
                  "required": ["user"]
                }
                """.trimIndent(),
            ) as JsonObject
        val agent =
            bodyAgent(
                Param(paramName = "user", envName = "USER_NAME", src = ParamSource.BODY, type = ParamType.ANY),
            ).copy(inputSchema = schema)

        val result = resolver.resolveFromParams(mapOf("user" to "alice", "HMR_LG_RESUME" to "x"), agent)

        assertEquals(mapOf("USER_NAME" to "alice"), result)
    }

    @Test
    fun `validateSatisfiable rejects targets with required HEADER or unprovided inputs`() {
        val agent =
            bodyAgent(
                Param(paramName = "a", envName = "A", required = true),
                Param(paramName = "h", envName = "H", src = ParamSource.HEADER, required = true),
            )

        assertThrows<ValidationException> { resolver.validateSatisfiable(agent, setOf("a")) } // header
        assertThrows<ValidationException> {
            resolver.validateSatisfiable(bodyAgent(Param(paramName = "a", envName = "A", required = true)), emptySet())
        } // missing
        resolver.validateSatisfiable(bodyAgent(Param(paramName = "a", envName = "A", required = true)), setOf("a")) // ok
    }

    @Test
    fun `unsatisfiableWithoutDefaults lists required declared inputs and ignores optional ones`() {
        val agent =
            bodyAgent(
                Param(paramName = "a", envName = "A", required = true),
                Param(paramName = "b", envName = "B", required = false),
            )

        assertEquals(listOf("'a'"), resolver.unsatisfiableWithoutDefaults(agent))
    }

    @Test
    fun `unsatisfiableWithoutDefaults includes a required HEADER input`() {
        // A cron fire passes no headers either, so a required HEADER input is unsatisfiable too.
        val agent =
            bodyAgent(
                Param(paramName = "h", envName = "H", src = ParamSource.HEADER, required = true),
            )

        assertEquals(listOf("'h'"), resolver.unsatisfiableWithoutDefaults(agent))
    }

    @Test
    fun `unsatisfiableWithoutDefaults marks inputSchema required fields`() {
        val schema =
            Json.parseToJsonElement(
                """{"type":"object","required":["user"],"properties":{"user":{"type":"string"}}}""",
            ) as JsonObject
        val agent =
            bodyAgent(Param(paramName = "a", envName = "A", required = true)).copy(inputSchema = schema)

        assertEquals(listOf("'a'", "'user' (inputSchema)"), resolver.unsatisfiableWithoutDefaults(agent))
    }

    @Test
    fun `unsatisfiableWithoutDefaults tolerates a non-string inputSchema required entry`() {
        // A JSON number is a JsonPrimitive, so it is tolerated via its content; a
        // non-primitive entry (object/array) has no content and is dropped.
        val schema =
            Json.parseToJsonElement(
                """{"type":"object","required":["user",42,{}],"properties":{"user":{"type":"string"}}}""",
            ) as JsonObject
        val agent = bodyAgent().copy(inputSchema = schema)

        assertEquals(listOf("'user' (inputSchema)", "'42' (inputSchema)"), resolver.unsatisfiableWithoutDefaults(agent))
    }

    @Test
    fun `resolveRetryParams rejects when a now-required input is missing from stored params`() {
        val agent = bodyAgent(Param(paramName = "id", envName = "ID", required = true))

        assertThrows<QueryParamValidationException> {
            resolver.resolveRetryParams(agent, emptyMap())
        }
        // Present and valid → returned re-validated.
        assertEquals(mapOf("ID" to "7"), resolver.resolveRetryParams(agent, mapOf("ID" to "7")))
    }

    @Test
    fun `resolveRetryParams rejects a stored value the current validator no longer accepts`() {
        // The agent was reloaded so "count" is now an integer; the original run stored
        // a non-integer value that a fresh request would now reject.
        val agent = bodyAgent(Param(paramName = "count", envName = "COUNT", type = ParamType.INT))
        assertThrows<QueryParamValidationException> {
            resolver.resolveRetryParams(agent, mapOf("COUNT" to "not-a-number"))
        }
    }

    @Test
    fun `resolveRetryParams drops a stored value for an input removed since the original run`() {
        // "OLD" is no longer a declared input; its stale stored value must not ride through.
        val agent = bodyAgent(Param(paramName = "id", envName = "ID", type = ParamType.STR))
        val out = resolver.resolveRetryParams(agent, mapOf("ID" to "7", "OLD" to "stale"))
        assertEquals(mapOf("ID" to "7"), out)
    }

    @Test
    fun `resolveRetryParams preserves reserved control markers verbatim`() {
        val agent = bodyAgent(Param(paramName = "id", envName = "ID", type = ParamType.STR))
        val out = resolver.resolveRetryParams(agent, mapOf("ID" to "7", "HMR_LG_THREAD" to "t-1"))
        assertEquals(mapOf("ID" to "7", "HMR_LG_THREAD" to "t-1"), out)
    }

    // --- typed generation (string/integer/number/boolean) via ParamValidator ---

    @Test
    fun `typed string input accepts values with spaces`() {
        val agent = bodyAgent(Param(paramName = "msg", type = ParamType.STR))
        val result =
            resolver.resolveDebug(
                queryParams = parametersOf("msg", "hello world"),
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )
        assertEquals("hello world", result["msg"])
    }

    @Test
    fun `default is applied when an optional param is absent`() {
        val agent =
            bodyAgent(
                Param(paramName = "count", type = ParamType.INT, required = false, default = "10"),
            )
        val result =
            resolver.resolveDebug(
                queryParams = parametersOf(),
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )
        assertEquals("10", result["count"])
    }

    @Test
    fun `explicit value wins over the default`() {
        val agent =
            bodyAgent(
                Param(paramName = "count", type = ParamType.INT, required = false, default = "10"),
            )
        val result =
            resolver.resolveDebug(
                queryParams = parametersOf("count", "3"),
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )
        assertEquals("3", result["count"])
    }

    @Test
    fun `default is applied on non-strict paths too`() {
        val agent =
            bodyAgent(
                Param(paramName = "count", type = ParamType.INT, required = false, default = "10"),
            )
        val result = resolver.resolveFromParams(emptyMap(), agent, enforceRequired = false)
        assertEquals("10", result["count"])
    }

    @Test
    fun `boolean input is normalized to lowercase in the resolved env`() {
        val agent = bodyAgent(Param(paramName = "flag", type = ParamType.BOOL, required = false))
        val result =
            resolver.resolveDebug(
                queryParams = parametersOf("flag", "TRUE"),
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )
        assertEquals("true", result["flag"])
    }

    @Test
    fun `constraint violation surfaces the precise message`() {
        val agent =
            bodyAgent(
                Param(paramName = "environment", type = ParamType.STR, values = listOf("dev", "staging", "prod")),
            )
        val e =
            assertFailsWith<QueryParamValidationException> {
                resolver.resolveDebug(
                    queryParams = parametersOf("environment", "qa"),
                    bodyJson = null,
                    headers = emptyMap(),
                    agent = agent,
                    enforceRequired = true,
                )
            }
        assertTrue(e.message!!.contains("must be one of [dev, staging, prod]"))
    }

    @Test
    fun `out-of-range typed value is silently dropped on non-strict paths`() {
        val agent = bodyAgent(Param(paramName = "count", type = ParamType.INT, required = false, min = 1.0))
        val result = resolver.resolveFromParams(mapOf("count" to "0"), agent, enforceRequired = false)
        assertFalse(result.containsKey("count"))
    }
}
