package homer.app.infra

import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.PathNotFoundException
import homer.app.exceptions.PayloadSchemaValidationException
import homer.app.exceptions.QueryParamValidationException
import homer.app.exceptions.ValidationException
import homer.app.models.Agent
import homer.app.models.Param
import homer.app.models.ParamSource
import homer.app.models.Transformer
import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Resolves agent inputs from query parameters, JSON request body, and HTTP headers
 * based on the agent's `inputs` (or legacy `queryParams`) configuration.
 */
class AgentInputResolver(
    private val inputSchemaValidator: InputSchemaValidator = InputSchemaValidator(),
    private val paramValidator: ParamValidator = ParamValidator(),
) {
    /**
     * Resolves agent inputs from one HTTP request, passed as the values the route
     * extracted from it: [method], [queryParameters], [headers] and [readBody].
     * Dispatches each [Param] to its configured source: query string, JSON request
     * body, or HTTP header. [readBody] is called only when at least one input has
     * `src = BODY` or the agent has an `inputSchema`, so no other request has its
     * body read.
     *
     * When any input has `src = BODY`, the request body is parsed as a top-level JSON
     * object. The method enforces:
     *  - POST method (GET with body inputs is rejected),
     *  - `application/json` or `application/<subtype>+json` (e.g. `application/vnd.api+json`) Content-Type,
     *  - well-formed JSON object body.
     *
     * The Content-Type and JSON-object rules apply only to a **non-blank** body. For an
     * agent without an `inputSchema`, a request that carries no bytes (or only whitespace)
     * is treated as `{}` (checked from the bytes read, not from `Content-Length`, so a
     * chunked body is still parsed), which lets a caller omit the body entirely when no
     * body input is required.
     * An agent with an `inputSchema` keeps requiring a non-empty body.
     */
    suspend fun resolve(
        method: HttpMethod,
        queryParameters: Parameters,
        headers: Headers,
        readBody: suspend () -> String,
        agent: Agent,
        enforceRequired: Boolean = true,
    ): Map<String, String> {
        val inputs = agent.effectiveInputs ?: emptyList()
        val needsBody = inputs.any { it.src == ParamSource.BODY } || agent.inputSchema != null
        val bodyJson: JsonObject? = if (needsBody) readJsonBody(method, headers, readBody, agent) else null

        applyInputSchema(agent, bodyJson)

        if (inputs.isEmpty()) return emptyMap()

        return resolveInternal(
            inputs = inputs,
            queryParameters = queryParameters,
            bodyJson = bodyJson,
            headerLookup = { name -> headers[name] },
            enforceRequired = enforceRequired,
        )
    }

    /**
     * Reads and parses the request body as a top-level JSON object, enforcing POST and a JSON
     * Content-Type on a non-blank body; a blank body is `{}` for an agent without an
     * `inputSchema`. See [resolve].
     */
    private suspend fun readJsonBody(
        method: HttpMethod,
        headers: Headers,
        readBody: suspend () -> String,
        agent: Agent,
    ): JsonObject {
        if (method != HttpMethod.Post) {
            throw QueryParamValidationException(
                "Agent '${agent.name}' requires request body; use POST",
            )
        }
        val text =
            try {
                readBody()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw QueryParamValidationException("Invalid JSON body")
            }
        // Blank counts as empty: callers piping a body (echo | curl --data-binary @-)
        // or serializers that append a newline carry no more intent than zero bytes.
        if (text.isBlank() && agent.inputSchema == null) return JsonObject(emptyMap())
        requireJsonContentType(headers)
        return parseJsonObject(text)
    }

    /**
     * Resolves agent inputs for the debug-execute endpoint, where the caller passes
     * everything in the JSON payload rather than as a live HTTP request. Bridges to
     * [resolveInternal] using the same code path as [resolve], so the debug endpoint
     * exercises identical extraction/validation semantics for QUERY/BODY/HEADER sources.
     *
     * Header lookup is case-insensitive (matches Ktor's header semantics) — the [headers]
     * map is indexed by lower-cased key before lookup.
     */
    fun resolveDebug(
        queryParams: Parameters,
        bodyJson: JsonObject?,
        headers: Map<String, String>,
        agent: Agent,
        enforceRequired: Boolean = false,
    ): Map<String, String> {
        val inputs = agent.effectiveInputs ?: emptyList()
        applyInputSchema(agent, bodyJson)
        if (inputs.isEmpty()) return emptyMap()
        val headerIndex = headers.mapKeys { it.key.lowercase() }
        return resolveInternal(
            inputs = inputs,
            queryParameters = queryParams,
            bodyJson = bodyJson,
            headerLookup = { name -> headerIndex[name.lowercase()] },
            enforceRequired = enforceRequired,
        )
    }

    /**
     * Validates [bodyJson] against the agent's `inputSchema` when both are
     * present. The live `resolve` path guarantees a non-null body when a schema
     * is set (it reads + parses the body first); `resolveDebug` may pass null,
     * in which case validation is skipped.
     */
    private fun applyInputSchema(
        agent: Agent,
        bodyJson: JsonObject?,
    ) {
        val schema = agent.inputSchema ?: return
        val body = bodyJson ?: return
        val errors = inputSchemaValidator.validate(schema, body)
        if (errors.isNotEmpty()) {
            throw PayloadSchemaValidationException(
                "Payload failed schema validation: ${errors.joinToString("; ")}",
                errors = errors,
            )
        }
    }

    private fun resolveInternal(
        inputs: List<Param>,
        queryParameters: Parameters,
        bodyJson: JsonObject?,
        headerLookup: (String) -> String?,
        enforceRequired: Boolean,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        inputs.forEach { param ->
            val raw: String? =
                when (param.src) {
                    ParamSource.QUERY -> queryParameters[param.paramName]
                    ParamSource.BODY -> bodyJson?.get(param.paramName)?.let(::stringifyJsonValue)
                    ParamSource.HEADER -> headerLookup(param.paramName)
                }
            val resolved = if (raw == null) resolveAbsent(param, enforceRequired) else resolvePresent(param, raw, enforceRequired)
            if (resolved != null) result[param.envName] = resolved
        }
        return result
    }

    /** An input the request did not carry: its validated default, else null — or a rejection when it is required. */
    private fun resolveAbsent(
        param: Param,
        enforceRequired: Boolean,
    ): String? {
        val default = param.default
        if (default != null) {
            // Load-time validation (validateDeclaration) guarantees the
            // default satisfies the param's own constraints.
            return when (val v = paramValidator.validate(default, param)) {
                is ParamValidation.Valid -> v.normalized
                is ParamValidation.Invalid -> throw QueryParamValidationException(v.message, paramName = param.paramName)
            }
        }
        if (param.required && enforceRequired) {
            throw QueryParamValidationException(
                "Parameter '${param.paramName}' is missing",
                paramName = param.paramName,
            )
        }
        return null
    }

    /** An input the request carried: transformed and validated, or null (lenient) / a rejection (strict) when unusable. */
    private fun resolvePresent(
        param: Param,
        raw: String,
        enforceRequired: Boolean,
    ): String? {
        val transformed = transform(param.transformer, raw)
        val invalidMessage: String =
            if (transformed == null) {
                "Parameter '${param.paramName}' is not valid ${param.type.wireName}"
            } else {
                when (val v = paramValidator.validate(transformed, param)) {
                    is ParamValidation.Valid -> return v.normalized
                    is ParamValidation.Invalid -> v.message
                }
            }
        // Present but unusable: strict callers reject instead of silently
        // dropping — a value the caller explicitly sent must not vanish.
        if (enforceRequired) {
            throw QueryParamValidationException(invalidMessage, paramName = param.paramName)
        }
        return null
    }

    /**
     * A String-valued param map routed into the shapes [resolveInternal] expects,
     * by each param's declared src on [agent]: QUERY params become query
     * [Parameters]; HEADER params are dropped (headers carry transport auth an
     * internal caller must not fabricate); reserved control keys
     * ([ReservedEnvNames.isReserved]) are dropped too — they reach the run env via
     * the separate `reservedControlParams` merge on the fire path, never via declared
     * input resolution, and no external caller can legitimately declare an input bound
     * to one (rejected at agent load); everything else — declared BODY params and
     * undeclared keys — becomes a JSON body field, so `inputSchema` sees the same body
     * an external POST would carry.
     *
     * The String-map analogue of `McpToolFactory.classifyToolArgs` — not a mirror.
     * It differs in three ways: it drops header-named keys (MCP routes them into
     * the body instead), it drops reserved control keys (MCP has no such concept),
     * and every value here is string-wrapped (MCP preserves JSON value types). Don't
     * "unify" the two without re-checking these deltas.
     */
    class ClassifiedParams(
        val queryParams: Parameters,
        val bodyJson: JsonObject?,
    )

    fun classifyParams(
        agent: Agent,
        params: Map<String, String>,
    ): ClassifiedParams {
        val inputs = agent.effectiveInputs.orEmpty()
        val queryNames = inputs.filter { it.src == ParamSource.QUERY }.map(Param::paramName).toSet()
        val headerNames = inputs.filter { it.src == ParamSource.HEADER }.map(Param::paramName).toSet()
        val queryBuilder = ParametersBuilder()
        val bodyFields = mutableMapOf<String, JsonElement>()
        params.forEach { (name, value) ->
            when {
                name in queryNames -> {
                    queryBuilder.append(name, value)
                }

                name in headerNames -> {}

                ReservedEnvNames.isReserved(name) -> {}

                else -> {
                    bodyFields[name] = JsonPrimitive(value)
                }
            }
        }
        val bodyJson =
            if (bodyFields.isNotEmpty() || agent.inputSchema != null) JsonObject(bodyFields) else null
        return ClassifiedParams(queryBuilder.build(), bodyJson)
    }

    /**
     * Resolves agent inputs from a plain param map (internal callers: RunAgent,
     * dispatch, continuation). Same extraction/validation path as the HTTP route.
     */
    fun resolveFromParams(
        params: Map<String, String>,
        agent: Agent,
        enforceRequired: Boolean = true,
    ): Map<String, String> {
        val classified = classifyParams(agent, params)
        return resolveDebug(
            queryParams = classified.queryParams,
            bodyJson = classified.bodyJson,
            headers = emptyMap(),
            agent = agent,
            enforceRequired = enforceRequired,
        )
    }

    /**
     * Display-ready names of the agent's inputs a scheduled (cron) run can never
     * satisfy: every required declared input — a cron fire passes no caller params
     * and no headers, and the model refuses `required` with a `default`
     * (ParamValidator) — plus every `inputSchema.required` field (JSON Schema
     * `default` is annotation-only). Schema-derived names carry the same
     * ` (inputSchema)` marker [validateSatisfiable] reports them under.
     */
    fun unsatisfiableWithoutDefaults(agent: Agent): List<String> =
        agent.effectiveInputs
            .orEmpty()
            .filter { it.required }
            .map { "'${it.paramName}'" } +
            schemaRequiredFields(agent).map { "'$it' (inputSchema)" }

    /**
     * Presence-only registration check for internal dispatch: every required
     * input (and every `inputSchema.required` field) must be coverable by
     * [providedParamNames]. Required HEADER inputs are never satisfiable
     * internally. Value validation still happens at fire time.
     */
    fun validateSatisfiable(
        agent: Agent,
        providedParamNames: Set<String>,
    ) {
        val unsatisfiable =
            agent.effectiveInputs.orEmpty().filter { param ->
                param.required &&
                    (param.src == ParamSource.HEADER || param.paramName !in providedParamNames)
            }
        val schemaMissing =
            schemaRequiredFields(agent).filter { it !in providedParamNames }
        if (unsatisfiable.isNotEmpty() || schemaMissing.isNotEmpty()) {
            val details =
                unsatisfiable.map { "${it.paramName} (${it.src.name.lowercase()})" } +
                    schemaMissing.map { "$it (inputSchema)" }
            throw ValidationException(
                "target agent '${agent.name}' has required inputs not satisfiable by this call: " +
                    details.joinToString(", "),
                "params",
            )
        }
    }

    /**
     * Re-validates a finished run's stored (envName-keyed) inputs for retry against
     * the CURRENT agent definition and returns the cleaned env map to replay. Retry
     * must not bypass the input policy a fresh request enforces, so this:
     *  - rejects when a currently-required input has no stored value,
     *  - re-runs the current [ParamValidator] on each stored value (type/pattern/enum),
     *    so a since-tightened constraint rejects the retry instead of replaying a
     *    now-invalid value, and
     *  - drops stored values for inputs removed since the original run, so stale
     *    undeclared env can no longer ride through into command execution.
     *
     * Stored values are already resolved (post-transformer), so transformers are NOT
     * re-applied. Internal control markers ([ReservedEnvNames.isReserved], e.g.
     * `HMR_LG_*`) are preserved verbatim — they are not caller inputs. `inputSchema`
     * is intentionally not re-checked here: the stored map is envName-keyed,
     * post-transform, and string-erased, so it cannot be faithfully validated against
     * a paramName-keyed body schema; the ParamValidator re-check and stale-key drop
     * are what close the retry input-policy bypass.
     */
    fun resolveRetryParams(
        agent: Agent,
        storedParams: Map<String, String>,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        storedParams.forEach { (name, value) ->
            if (ReservedEnvNames.isReserved(name)) result[name] = value
        }
        agent.effectiveInputs.orEmpty().forEach { param ->
            val stored = storedParams[param.envName]
            if (stored == null) {
                if (param.required) {
                    throw QueryParamValidationException(
                        "Cannot retry: agent '${agent.name}' now requires input '${param.paramName}' " +
                            "which is absent from the original run",
                        paramName = param.paramName,
                    )
                }
                return@forEach
            }
            when (val v = paramValidator.validate(stored, param)) {
                is ParamValidation.Valid -> {
                    result[param.envName] = v.normalized
                }

                is ParamValidation.Invalid -> {
                    throw QueryParamValidationException(
                        "Cannot retry: stored value for '${param.paramName}' is no longer valid: ${v.message}",
                        paramName = param.paramName,
                    )
                }
            }
        }
        return result
    }

    /** The `inputSchema.required` field names, tolerating a non-string entry. */
    internal fun schemaRequiredFields(agent: Agent): List<String> =
        (agent.inputSchema?.get("required") as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content }
}

/** Accepts `application/json` or `application/<subtype>+json`, ignoring parameters. */
private fun requireJsonContentType(headers: Headers) {
    val rawCt = headers[HttpHeaders.ContentType]
    val ct =
        try {
            (rawCt?.let(ContentType::parse) ?: ContentType.Any).withoutParameters()
        } catch (_: BadContentTypeFormatException) {
            // CWE-248: a caller-controlled header must not escape as an unhandled 500.
            throw QueryParamValidationException("Invalid Content-Type header: '$rawCt'")
        }
    val ok =
        ct.match(ContentType.Application.Json) ||
            (ct.contentType == "application" && ct.contentSubtype.endsWith("+json"))
    if (!ok) {
        throw QueryParamValidationException(
            "Expected Content-Type: application/json (got '$ct')",
        )
    }
}

/** [text] as a top-level JSON object; anything else is a [QueryParamValidationException]. */
private fun parseJsonObject(text: String): JsonObject =
    try {
        Json.parseToJsonElement(text) as? JsonObject
            ?: throw QueryParamValidationException("Invalid JSON body: expected an object")
    } catch (e: CancellationException) {
        throw e
    } catch (e: QueryParamValidationException) {
        throw e
    } catch (_: Exception) {
        throw QueryParamValidationException("Invalid JSON body")
    }

private fun transform(
    transformer: Transformer,
    raw: String,
): String? =
    when (transformer) {
        is Transformer.JsonPath -> {
            val parsed = runCatching { JsonPath.parse(raw) }.getOrNull()
            try {
                parsed?.read<Any>(transformer.path)?.toString()
            } catch (_: PathNotFoundException) {
                null
            }
        }

        Transformer.Pass -> {
            raw
        }
    }

private fun stringifyJsonValue(value: JsonElement): String? =
    when (value) {
        is JsonNull -> null
        is JsonPrimitive -> value.content
        else -> value.toString()
    }
