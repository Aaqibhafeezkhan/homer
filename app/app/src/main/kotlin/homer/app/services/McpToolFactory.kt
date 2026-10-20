package homer.app.services

import homer.app.infra.AgentInputResolver
import homer.app.infra.runTool
import homer.app.infra.toolResult
import homer.app.models.Action
import homer.app.models.Agent
import homer.app.models.McpServerConfig
import homer.app.models.Param
import homer.app.models.ParamSource
import homer.app.models.ParamType
import homer.app.models.ProcessStatus
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.Logger

/**
 * Builds the per-request MCP [Server] for the `/mcp` endpoint: one tool per
 * opted-in agent (`agent.mcp.enabled`) plus the four run-management tools.
 * A fresh Server is built per POST so the tool list always reflects agent
 * reloads. See ADR-0018.
 */
internal class McpToolFactory(
    private val agentsService: AgentsService,
    private val authorizer: Authorizer,
    private val agentInputResolver: AgentInputResolver,
    private val agentRunStarter: AgentRunStarter,
    private val processManager: ProcessManager,
    private val artifactService: ArtifactService,
    private val processEventBroadcaster: ProcessEventBroadcaster,
    private val mcpConfig: McpServerConfig,
    private val logger: Logger,
) {
    /** The four run-management tools, each gating on the target run's visibility. */
    internal val runTools = McpRunTools(processManager, artifactService, authorizer, mcpConfig)

    /**
     * Builds a fresh MCP Server for one POST. Agent tools are filtered to
     * those [principal] holds a RUN grant for (ADR-0037); the management
     * tools (get_run_info/list_artifacts/get_artifact/cancel_run) are always
     * registered, but each gates on the target run's visibility internally.
     */
    fun buildServer(principal: String?): Server {
        val server =
            Server(
                serverInfo =
                    Implementation(
                        name = "homer",
                        version = System.getenv("HOMER_VERSION") ?: "dev",
                    ),
                options =
                    ServerOptions(
                        capabilities = ServerCapabilities(tools = ServerCapabilities.Tools()),
                    ),
            )
        server.addTools(
            mcpAgents()
                .filter { authorizer.hasAction(principal, Action.RUN, it.name) }
                .map { agentTool(it, principal) },
        )
        server.addTools(runTools.tools(principal))
        return server
    }

    /**
     * Agents opted into MCP exposure. Agents whose name fails the MCP
     * tool-name charset or whose params collide with the reserved `_wait`
     * argument are skipped with a WARN.
     */
    internal fun mcpAgents(): List<Agent> =
        agentsService.agents.values
            .filter { it.mcp?.enabled == true }
            .filter { agent ->
                when {
                    !TOOL_NAME_REGEX.matches(agent.name) -> {
                        logger.warn(
                            "Agent '{}' is not exposed over MCP: name does not match {}",
                            agent.name,
                            TOOL_NAME_REGEX.pattern,
                        )
                        false
                    }

                    agent.effectiveInputs.orEmpty().any { it.paramName == WAIT_ARG } -> {
                        logger.warn(
                            "Agent '{}' is not exposed over MCP: param '{}' collides with the reserved MCP argument",
                            agent.name,
                            WAIT_ARG,
                        )
                        false
                    }

                    agent.effectiveInputs.orEmpty().any { it.src == ParamSource.HEADER && it.required } -> {
                        logger.warn(
                            "Agent '{}' is not exposed over MCP: it has a required header input, " +
                                "which an MCP client can never supply",
                            agent.name,
                        )
                        false
                    }

                    else -> {
                        true
                    }
                }
            }

    /**
     * Tool input schema for an agent: QUERY + BODY params become top-level
     * arguments, `inputSchema` properties/required are merged in, and the
     * reserved `_wait` argument is injected. HEADER params are excluded —
     * headers carry auth material an LLM client must not be asked to supply.
     */
    internal fun toolSchemaFor(agent: Agent): ToolSchema {
        val params = agent.effectiveInputs.orEmpty().filter { it.src != ParamSource.HEADER }
        val schemaProperties = agent.inputSchema?.get("properties") as? JsonObject
        val schemaRequired = agentInputResolver.schemaRequiredFields(agent)
        val properties =
            buildJsonObject {
                params.forEach { param -> put(param.paramName, paramSchema(param)) }
                schemaProperties?.forEach { (name, prop) -> put(name, prop) }
                put(
                    WAIT_ARG,
                    buildJsonObject {
                        put("type", "number")
                        put(
                            "description",
                            "Optional: seconds to wait for the run to finish (0..${mcpConfig.maxWaitSec}). " +
                                "Omit to return immediately with {runId, status}.",
                        )
                    },
                )
            }
        val required =
            (params.filter { it.required }.map { it.paramName } + schemaRequired).distinct()
        return ToolSchema(
            properties = properties,
            required = required.takeIf { it.isNotEmpty() },
        )
    }

    private fun paramSchema(param: Param): JsonObject =
        buildJsonObject {
            when (param.type) {
                ParamType.NUMBER -> {
                    put("type", "number")
                }

                ParamType.STRING, ParamType.ANY -> {
                    put("type", "string")
                }

                ParamType.STR -> {
                    put("type", "string")
                    param.pattern?.let { put("pattern", it) }
                    param.minLength?.let { put("minLength", it) }
                    param.maxLength?.let { put("maxLength", it) }
                    secretGatedValues(param)?.let { vs -> put("enum", JsonArray(vs.map(::JsonPrimitive))) }
                }

                ParamType.INT -> {
                    put("type", "integer")
                    param.min?.let { put("minimum", it.toLong()) }
                    param.max?.let { put("maximum", it.toLong()) }
                    secretGatedValues(param)?.let { vs -> put("enum", JsonArray(vs.map { JsonPrimitive(it.toLong()) })) }
                }

                ParamType.NUM -> {
                    put("type", "number")
                    param.min?.let { put("minimum", it) }
                    param.max?.let { put("maximum", it) }
                    secretGatedValues(param)?.let { vs -> put("enum", JsonArray(vs.map { JsonPrimitive(it.toDouble()) })) }
                }

                ParamType.BOOL -> {
                    put("type", "boolean")
                }
            }
            // A secret input's default and values are dropped: any run-grant holder reads this. See ADR-0071.
            if (!param.secret) param.default?.let { put("default", typedDefault(it, param.type)) }
            put("description", "Agent input (${param.src.name.lowercase()} param)")
        }

    /** The [Param] values served as the tool schema's `enum`: a secret input carries none (ADR-0071). */
    private fun secretGatedValues(param: Param): List<String>? = if (param.secret) null else param.values

    private fun typedDefault(
        default: String,
        type: ParamType,
    ): JsonPrimitive =
        when (type) {
            ParamType.INT -> JsonPrimitive(default.toLong())
            ParamType.NUM -> JsonPrimitive(default.toDouble())
            ParamType.BOOL -> JsonPrimitive(default.lowercase().toBooleanStrict())
            else -> JsonPrimitive(default)
        }

    private fun agentTool(
        agent: Agent,
        principal: String?,
    ): RegisteredTool =
        RegisteredTool(
            Tool(
                name = agent.name,
                description = agent.description ?: "Homer agent '${agent.name}'",
                inputSchema = toolSchemaFor(agent),
            ),
        ) { request -> callAgentTool(agent, request, principal) }

    internal suspend fun callAgentTool(
        agent: Agent,
        request: CallToolRequest,
        principal: String?,
    ): CallToolResult =
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val waitSec =
                ((args[WAIT_ARG] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0)
                    .toLong()
                    .coerceIn(0L, mcpConfig.maxWaitSec.toLong())
            val classified = classifyToolArgs(agent, args)

            val processId =
                agentRunStarter.start(
                    agent = agent,
                    invocationChain = emptyList(),
                    source = "mcp",
                    owner = principal,
                ) {
                    agentInputResolver.resolveDebug(
                        queryParams = classified.queryParams,
                        bodyJson = classified.bodyJson,
                        headers = emptyMap(),
                        agent = agent,
                        enforceRequired = true,
                    )
                }

            if (waitSec == 0L) return@runTool startedResult(agent, processId)
            finishedResult(agent, processId, waitSec)
        }

    /** The `_wait`-less result: the run's id and current status, with a hint to poll. */
    private suspend fun startedResult(
        agent: Agent,
        processId: Int,
    ): CallToolResult {
        val status = processManager.getProcessStatus(processId)
        return toolResult(
            "Started agent '${agent.name}': run $processId is ${status.name}. " +
                "Poll with get_run_info, or pass _wait to block until it finishes.",
            buildJsonObject {
                put("runId", processId)
                put("status", status.name)
            },
        )
    }

    /** Waits up to [waitSec] for the run, then reports its status, cost and artifacts. */
    private suspend fun finishedResult(
        agent: Agent,
        processId: Int,
        waitSec: Long,
    ): CallToolResult {
        val finished = awaitTerminalStatus(processId, waitSec)
        val info =
            processManager.getProcessInfo(processId)
                ?: error("Process $processId disappeared while waiting")
        val artifacts =
            artifactService
                .listArtifacts(processId)
                .getOrNull()
                ?.files
                .orEmpty()
        val structured =
            buildJsonObject {
                put("runId", processId)
                put("status", info.status.name)
                put("costUsd", info.costUsd)
                put(
                    "artifacts",
                    buildJsonArray {
                        artifacts.forEach { entry ->
                            add(
                                buildJsonObject {
                                    put("path", entry.path)
                                    put("size", entry.size)
                                },
                            )
                        }
                    },
                )
            }
        val text =
            if (finished) {
                "Agent '${agent.name}' run $processId finished with status ${info.status.name} " +
                    "(cost $${info.costUsd}, ${artifacts.size} artifact(s))."
            } else {
                "Agent '${agent.name}' run $processId is still ${info.status.name} after ${waitSec}s. " +
                    "Poll with get_run_info(runId=$processId)."
            }
        return toolResult(text, structured)
    }

    /**
     * The agent inputs carried by an MCP tool call, split into the shapes
     * [AgentInputResolver] expects: HTTP-style query [Parameters] and an
     * optional JSON request body.
     */
    internal class ClassifiedToolArgs(
        val queryParams: Parameters,
        val bodyJson: JsonObject?,
    )

    /**
     * Routes each tool-call argument to where the agent declared it: QUERY
     * params become query [Parameters], everything else becomes body fields.
     * The reserved [WAIT_ARG] is dropped — it controls the tool, not the run.
     * The body is null unless there are body fields or the agent has an
     * `inputSchema` (which expects a body object even when empty).
     */
    internal fun classifyToolArgs(
        agent: Agent,
        args: JsonObject,
    ): ClassifiedToolArgs {
        val queryParamNames =
            agent.effectiveInputs
                .orEmpty()
                .filter { it.src == ParamSource.QUERY }
                .map(Param::paramName)
                .toSet()
        val queryBuilder = ParametersBuilder()
        val bodyFields = mutableMapOf<String, JsonElement>()
        args.forEach { (name, value) ->
            when {
                name == WAIT_ARG -> {}

                name in queryParamNames -> {
                    queryBuilder.append(name, stringify(value))
                }

                else -> {
                    bodyFields[name] = value
                }
            }
        }
        val bodyJson =
            if (bodyFields.isNotEmpty() || agent.inputSchema != null) JsonObject(bodyFields) else null
        return ClassifiedToolArgs(queryBuilder.build(), bodyJson)
    }

    /**
     * Waits up to [waitSec] seconds for the run to reach a terminal status.
     * Listens for terminal StatusChanged events on the broadcaster, merged
     * with a 2 s poll fallback that covers both the subscribe race (the run
     * may finish before the collector attaches) and DROP_OLDEST event loss.
     * Returns true when terminal, false on timeout.
     */
    private suspend fun awaitTerminalStatus(
        processId: Int,
        waitSec: Long,
    ): Boolean =
        withTimeoutOrNull(waitSec * 1000) {
            merge(
                processEventBroadcaster
                    .subscribe()
                    .filterIsInstance<ProcessEvent.StatusChanged>()
                    .filter { it.processId == processId }
                    .map { },
                flow {
                    while (true) {
                        delay(POLL_FALLBACK_MS)
                        emit(Unit)
                    }
                },
            )
                // The initial check must run INSIDE this collection, after the
                // broadcaster subscription above is live — checking before
                // subscribing would miss an event landing in between.
                .onStart { emit(Unit) }
                .map { processManager.getProcessStatus(processId) }
                .first { it in TERMINAL_STATUSES }
        } != null

    private fun stringify(value: JsonElement): String =
        when (value) {
            is JsonNull -> ""
            is JsonPrimitive -> value.content
            else -> value.toString()
        }

    companion object {
        const val WAIT_ARG = "_wait"
        private const val POLL_FALLBACK_MS = 2_000L
        private val TERMINAL_STATUSES =
            setOf(
                ProcessStatus.FINISHED,
                ProcessStatus.FAILED,
                ProcessStatus.KILLED,
            )
        private val TOOL_NAME_REGEX = Regex("^[a-zA-Z0-9_-]{1,128}$")
    }
}
