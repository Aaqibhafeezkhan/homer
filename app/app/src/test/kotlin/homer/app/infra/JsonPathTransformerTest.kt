package homer.app.infra

import homer.app.exceptions.QueryParamValidationException
import homer.app.fixtures.AgentFixtures
import homer.app.models.Param
import homer.app.models.ParamType
import homer.app.models.Transformer
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 * Comprehensive tests for JSONPath transformer functionality
 * Tests both the underlying jsonpathkt library and integration with AgentInputResolver
 */
class JsonPathTransformerTest {
    private lateinit var resolver: AgentInputResolver

    @BeforeEach
    fun setup() {
        resolver = AgentInputResolver()
    }

    private fun createParameters(params: Map<String, String>): Parameters {
        val builder = ParametersBuilder()
        params.forEach { (key, value) -> builder.append(key, value) }
        return builder.build()
    }

    @Test
    fun `resolveDebug extracts value using JsonPath transformer from array`() {
        val agent =
            AgentFixtures.simpleAgent().copy(
                queryParams =
                    listOf(
                        Param(
                            paramName = "data",
                            envName = "EXTRACTED_VALUE",
                            type = ParamType.ANY,
                            transformer = Transformer.JsonPath("$.x[2]"),
                        ),
                    ),
            )
        val params = createParameters(mapOf("data" to """{"x": [1, 2, 3]}"""))

        val result =
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )

        assertEquals(mapOf("EXTRACTED_VALUE" to "3"), result)
    }

    @Test
    fun `resolveDebug extracts nested object property using JsonPath`() {
        val agent =
            AgentFixtures.simpleAgent().copy(
                queryParams =
                    listOf(
                        Param(
                            paramName = "user",
                            envName = "USER_NAME",
                            type = ParamType.STRING,
                            transformer = Transformer.JsonPath("$.name"),
                        ),
                    ),
            )
        val params = createParameters(mapOf("user" to """{"name": "Alice", "age": 30}"""))

        val result =
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )

        assertEquals(mapOf("USER_NAME" to "Alice"), result)
    }

    @Test
    fun `resolveDebug uses Pass transformer when not specified`() {
        val agent =
            AgentFixtures.simpleAgent().copy(
                queryParams =
                    listOf(
                        Param(
                            paramName = "simple",
                            envName = "SIMPLE_VALUE",
                            type = ParamType.STRING,
                            // transformer defaults to Transformer.Pass
                        ),
                    ),
            )
        val params = createParameters(mapOf("simple" to "value"))

        val result =
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )

        assertEquals(mapOf("SIMPLE_VALUE" to "value"), result)
    }

    @Test
    fun `resolveDebug throws exception when JSONPath returns null for required param`() {
        val agent =
            AgentFixtures.simpleAgent().copy(
                queryParams =
                    listOf(
                        Param(
                            paramName = "data",
                            envName = "VALUE",
                            type = ParamType.ANY,
                            required = true,
                            transformer = Transformer.JsonPath("$.missing"),
                        ),
                    ),
            )
        val params = createParameters(mapOf("data" to """{"x": [1, 2, 3]}"""))

        val exception =
            assertThrows<QueryParamValidationException> {
                resolver.resolveDebug(
                    queryParams = params,
                    bodyJson = null,
                    headers = emptyMap(),
                    agent = agent,
                    enforceRequired = true,
                )
            }

        assertEquals("Parameter 'data' is not valid ANY", exception.message)
        assertEquals("data", exception.paramName)
    }

    @Test
    fun `resolveDebug throws when JSONPath returns null for optional param and strict`() {
        // Present-but-invalid now rejects regardless of `required` when enforceRequired
        // is true — the caller explicitly sent a value that couldn't be extracted.
        val agent =
            AgentFixtures.simpleAgent().copy(
                queryParams =
                    listOf(
                        Param(
                            paramName = "data",
                            envName = "VALUE",
                            type = ParamType.ANY,
                            required = false,
                            transformer = Transformer.JsonPath("$.missing"),
                        ),
                    ),
            )
        val params = createParameters(mapOf("data" to """{"x": [1, 2, 3]}"""))

        val exception =
            assertThrows<QueryParamValidationException> {
                resolver.resolveDebug(
                    queryParams = params,
                    bodyJson = null,
                    headers = emptyMap(),
                    agent = agent,
                    enforceRequired = true,
                )
            }

        assertEquals("data", exception.paramName)
    }

    @Test
    fun `resolveDebug validates extracted value against ParamType NUMBER`() {
        val agent =
            AgentFixtures.simpleAgent().copy(
                queryParams =
                    listOf(
                        Param(
                            paramName = "data",
                            envName = "COUNT",
                            type = ParamType.NUMBER,
                            transformer = Transformer.JsonPath("$.count"),
                        ),
                    ),
            )
        val params = createParameters(mapOf("data" to """{"count": 42}"""))

        val result =
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )

        assertEquals(mapOf("COUNT" to "42"), result)
    }

    @Test
    fun `resolveDebug rejects extracted value that fails validation`() {
        val agent =
            AgentFixtures.simpleAgent().copy(
                queryParams =
                    listOf(
                        Param(
                            paramName = "data",
                            envName = "NAME",
                            type = ParamType.STRING,
                            transformer = Transformer.JsonPath("$.name"),
                        ),
                    ),
            )
        val params = createParameters(mapOf("data" to """{"name": "has spaces"}"""))

        val exception =
            assertThrows<QueryParamValidationException> {
                resolver.resolveDebug(
                    queryParams = params,
                    bodyJson = null,
                    headers = emptyMap(),
                    agent = agent,
                    enforceRequired = true,
                )
            }

        assertEquals("Parameter 'data' is not valid STRING", exception.message)
    }

    @Test
    fun `resolveDebug extracts string from nested array`() {
        val agent =
            AgentFixtures.simpleAgent().copy(
                queryParams =
                    listOf(
                        Param(
                            paramName = "data",
                            envName = "ITEM",
                            type = ParamType.ANY,
                            transformer = Transformer.JsonPath("$.items[1]"),
                        ),
                    ),
            )
        val params = createParameters(mapOf("data" to """{"items": ["first", "second", "third"]}"""))

        val result =
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )

        assertEquals(mapOf("ITEM" to "second"), result)
    }

    @Test
    fun `resolveDebug handles multiple params with different transformers`() {
        val agent =
            AgentFixtures.simpleAgent().copy(
                queryParams =
                    listOf(
                        Param(
                            paramName = "json_data",
                            envName = "EXTRACTED",
                            type = ParamType.ANY,
                            transformer = Transformer.JsonPath("$.value"),
                        ),
                        Param(
                            paramName = "simple_data",
                            envName = "SIMPLE",
                            type = ParamType.STRING,
                            transformer = Transformer.Pass,
                        ),
                    ),
            )
        val params =
            createParameters(
                mapOf(
                    "json_data" to """{"value": "extracted"}""",
                    "simple_data" to "plain",
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
                "EXTRACTED" to "extracted",
                "SIMPLE" to "plain",
            ),
            result,
        )
    }

    // ========================================
    // Edge Cases
    // ========================================

    @Test
    fun `resolveDebug throws on empty JSON object with jsonPath miss when strict`() {
        // Present-but-invalid now rejects regardless of `required` when enforceRequired
        // is true — the caller explicitly sent a value that couldn't be extracted.
        val agent =
            AgentFixtures.simpleAgent().copy(
                queryParams =
                    listOf(
                        Param(
                            paramName = "data",
                            envName = "VALUE",
                            type = ParamType.ANY,
                            required = false,
                            transformer = Transformer.JsonPath("$.field"),
                        ),
                    ),
            )
        val params = createParameters(mapOf("data" to "{}"))

        assertThrows<QueryParamValidationException> {
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )
        }
    }

    @Test
    fun `resolveDebug throws on empty JSON array with jsonPath miss when strict`() {
        // Present-but-invalid now rejects regardless of `required` when enforceRequired
        // is true — the caller explicitly sent a value that couldn't be extracted.
        val agent =
            AgentFixtures.simpleAgent().copy(
                queryParams =
                    listOf(
                        Param(
                            paramName = "data",
                            envName = "VALUE",
                            type = ParamType.ANY,
                            required = false,
                            transformer = Transformer.JsonPath("$[0]"),
                        ),
                    ),
            )
        val params = createParameters(mapOf("data" to "[]"))

        assertThrows<QueryParamValidationException> {
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )
        }
    }

    @Test
    fun `resolveDebug extracts from deeply nested structure`() {
        val agent =
            AgentFixtures.simpleAgent().copy(
                queryParams =
                    listOf(
                        Param(
                            paramName = "data",
                            envName = "CITY",
                            type = ParamType.ANY,
                            transformer = Transformer.JsonPath("$.user.address.city"),
                        ),
                    ),
            )
        val params =
            createParameters(
                mapOf("data" to """{"user": {"address": {"city": "NYC", "zip": "10001"}}}"""),
            )

        val result =
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )

        assertEquals(mapOf("CITY" to "NYC"), result)
    }

    @Test
    fun `resolveDebug handles invalid JSON gracefully for required param`() {
        val agent =
            AgentFixtures.simpleAgent().copy(
                queryParams =
                    listOf(
                        Param(
                            paramName = "data",
                            envName = "VALUE",
                            type = ParamType.ANY,
                            required = true,
                            transformer = Transformer.JsonPath("$.field"),
                        ),
                    ),
            )
        val params = createParameters(mapOf("data" to "not valid json"))

        val exception =
            assertThrows<QueryParamValidationException> {
                resolver.resolveDebug(
                    queryParams = params,
                    bodyJson = null,
                    headers = emptyMap(),
                    agent = agent,
                    enforceRequired = true,
                )
            }

        assertEquals("Parameter 'data' is not valid ANY", exception.message)
    }

    @Test
    fun `resolveDebug throws on invalid JSON for optional param when strict`() {
        // Present-but-invalid now rejects regardless of `required` when enforceRequired
        // is true — the caller explicitly sent a value that couldn't be parsed.
        val agent =
            AgentFixtures.simpleAgent().copy(
                queryParams =
                    listOf(
                        Param(
                            paramName = "data",
                            envName = "VALUE",
                            type = ParamType.ANY,
                            required = false,
                            transformer = Transformer.JsonPath("$.field"),
                        ),
                    ),
            )
        val params = createParameters(mapOf("data" to "not valid json"))

        assertThrows<QueryParamValidationException> {
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )
        }
    }

    @Test
    fun `resolveDebug extracts number and validates as NUMBER type`() {
        val agent =
            AgentFixtures.simpleAgent().copy(
                queryParams =
                    listOf(
                        Param(
                            paramName = "data",
                            envName = "AGE",
                            type = ParamType.NUMBER,
                            transformer = Transformer.JsonPath("$.age"),
                        ),
                    ),
            )
        val params = createParameters(mapOf("data" to """{"age": 25}"""))

        val result =
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )

        assertEquals(mapOf("AGE" to "25"), result)
    }

    @Test
    fun `resolveDebug extracts boolean as string`() {
        val agent =
            AgentFixtures.simpleAgent().copy(
                queryParams =
                    listOf(
                        Param(
                            paramName = "data",
                            envName = "ACTIVE",
                            type = ParamType.ANY,
                            transformer = Transformer.JsonPath("$.active"),
                        ),
                    ),
            )
        val params = createParameters(mapOf("data" to """{"active": true}"""))

        val result =
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )

        assertEquals(mapOf("ACTIVE" to "true"), result)
    }

    @Test
    fun `resolveDebug extracts last element using standard array syntax`() {
        val agent =
            AgentFixtures.simpleAgent().copy(
                queryParams =
                    listOf(
                        Param(
                            paramName = "data",
                            envName = "VALUE",
                            type = ParamType.ANY,
                            required = false,
                            transformer = Transformer.JsonPath("$.items[2]"),
                        ),
                    ),
            )
        val params = createParameters(mapOf("data" to """{"items": [1, 2, 3]}"""))

        val result =
            resolver.resolveDebug(
                queryParams = params,
                bodyJson = null,
                headers = emptyMap(),
                agent = agent,
                enforceRequired = true,
            )

        assertEquals(mapOf("VALUE" to "3"), result)
    }
}
