package homer.app.infra

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Compiles and caches per-agent JSON Schemas (Draft 2020-12) and validates
 * incoming webhook payloads against them.
 *
 * Distinct from [homer.app.config.AgentSchemaValidator], which validates agent
 * *definitions* against the bundled `homer-agent.schema.json`. This validates
 * *runtime request bodies* against an operator-supplied `inputSchema`.
 */
class InputSchemaValidator {
    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
    private val metaSchema: Schema =
        registry.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"))
    private val cache = ConcurrentHashMap<String, Schema>()

    /**
     * Compiles [inputSchema] (cached by its canonical string). A structurally
     * malformed schema (fails the Draft 2020-12 meta-schema), one with an
     * unresolvable `$ref`, or one carrying a ReDoS-prone `pattern` throws here,
     * so the failure surfaces at agent load rather than at request time.
     */
    fun precompile(inputSchema: JsonObject): Schema =
        cache.computeIfAbsent(inputSchema.toString()) { schemaText ->
            val metaErrors = metaSchema.validate(schemaText, InputFormat.JSON)
            require(metaErrors.isEmpty()) {
                "inputSchema is not a valid JSON Schema: ${metaErrors.joinToString("; ") { it.message }}"
            }
            lintPatterns(inputSchema)
            registry.getSchema(schemaText).also { it.initializeValidators() }
        }

    /**
     * Rejects any `pattern` (or `patternProperties` key) whose shape is prone to
     * catastrophic backtracking. networknt matches these author regexes against
     * caller-controlled bodies with an unbounded `java.util.regex` engine, so a
     * vulnerable pattern is a ReDoS vector — fail closed at load. See ADR-0040.
     */
    private fun lintPatterns(schema: JsonElement) {
        when (schema) {
            is JsonObject -> {
                (schema["pattern"] as? JsonPrimitive)?.takeIf { it.isString }?.let { p ->
                    RedosPatternDetector.vulnerabilityReason(p.content)?.let {
                        throw IllegalArgumentException("inputSchema has a ReDoS-prone 'pattern': $it")
                    }
                }
                (schema["patternProperties"] as? JsonObject)?.keys?.forEach { key ->
                    RedosPatternDetector.vulnerabilityReason(key)?.let {
                        throw IllegalArgumentException("inputSchema has a ReDoS-prone 'patternProperties' key: $it")
                    }
                }
                schema.values.forEach { lintPatterns(it) }
            }

            is JsonArray -> {
                schema.forEach { lintPatterns(it) }
            }

            else -> {}
        }
    }

    /** Returns human-readable violation messages; an empty list means valid. */
    fun validate(
        inputSchema: JsonObject,
        body: JsonObject,
    ): List<String> = precompile(inputSchema).validate(body.toString(), InputFormat.JSON).map { it.message }
}
