package homer.app.infra

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue

class InputSchemaValidatorTest {
    private val validator = InputSchemaValidator()

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject

    private val schema =
        obj(
            """
            {
              "type": "object",
              "required": ["user", "count"],
              "properties": {
                "user": { "type": "string" },
                "count": { "type": "integer", "minimum": 1 }
              },
              "additionalProperties": false
            }
            """.trimIndent(),
        )

    @Test
    fun `conforming body produces no errors`() {
        val errors = validator.validate(schema, obj("""{"user":"alice","count":3}"""))
        assertTrue(errors.isEmpty(), "expected no errors, got: $errors")
    }

    @Test
    fun `violating body produces error messages`() {
        val errors = validator.validate(schema, obj("""{"count":0}"""))
        assertTrue(errors.isNotEmpty(), "expected violations for missing 'user' and count < 1")
    }

    @Test
    fun `same schema is compiled once and cached`() {
        val first = validator.precompile(schema)
        val second = validator.precompile(schema)
        assertTrue(first === second, "expected the cached JsonSchema instance to be reused")
    }

    @Test
    fun `structurally malformed schema throws on precompile`() {
        // 'properties' must be an object of subschemas; a string is meta-invalid.
        val bad = obj("""{"type":"object","properties":"oops"}""")
        assertThrows<Exception> { validator.precompile(bad) }
    }

    @Test
    fun `safe pattern in a property compiles`() {
        val schema =
            obj(
                """
                {
                  "type": "object",
                  "properties": { "ticket": { "type": "string", "pattern": "^[A-Z]+-[0-9]+$" } }
                }
                """.trimIndent(),
            )
        validator.precompile(schema) // does not throw
    }

    @Test
    fun `ReDoS-prone pattern in a property is rejected at precompile`() {
        val schema =
            obj(
                """
                {
                  "type": "object",
                  "properties": { "s": { "type": "string", "pattern": "^(a+)+$" } }
                }
                """.trimIndent(),
            )
        val ex = assertThrows<IllegalArgumentException> { validator.precompile(schema) }
        assertTrue(ex.message!!.contains("ReDoS"), "expected a ReDoS rejection, got: ${ex.message}")
    }

    @Test
    fun `ReDoS-prone patternProperties key is rejected at precompile`() {
        val schema =
            obj(
                """
                {
                  "type": "object",
                  "patternProperties": { "(a|ab)*": { "type": "string" } }
                }
                """.trimIndent(),
            )
        assertThrows<IllegalArgumentException> { validator.precompile(schema) }
    }

    @Test
    fun `catastrophic pattern never reaches the unbounded matcher`() {
        // Regression: the classic evil regex + a crafted value used to pin a CPU
        // inside networknt's matcher. It must now be rejected before any match runs,
        // so validate() (which precompiles first) never evaluates the body.
        val schema = obj("""{"type":"object","properties":{"s":{"type":"string","pattern":"^(a+)+$"}}}""")
        val crafted = obj("""{"s":"${"a".repeat(40)}!"}""")
        assertThrows<IllegalArgumentException> { validator.validate(schema, crafted) }
    }
}
