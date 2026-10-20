package homer.app.services

import homer.app.infra.runTool
import homer.app.infra.toolError
import homer.app.infra.toolResult
import homer.app.models.McpServerConfig
import homer.app.models.ProcessInfo
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files

/**
 * The run-management tools of the `/mcp` endpoint [McpToolFactory] builds:
 * `get_run_info`, `list_artifacts`, `get_artifact` and `cancel_run`. They are
 * registered for every principal, so each one gates on the target run itself —
 * a run the principal cannot see (or, for `cancel_run`, control) reads exactly
 * like a missing one (ADR-0039).
 */
internal class McpRunTools(
    private val processManager: ProcessManager,
    private val artifactService: ArtifactService,
    private val authorizer: Authorizer,
    private val mcpConfig: McpServerConfig,
) {
    /** The tools registered for [principal]; each handler gates on the target run itself. */
    fun tools(principal: String?): List<RegisteredTool> =
        listOf(
            RegisteredTool(
                Tool(
                    name = "get_run_info",
                    description =
                        "Get the status, cost, and per-command results of a Homer agent run started earlier " +
                            "(by an agent tool, the HTTP API, or cron).",
                    inputSchema = runIdSchema(),
                ),
            ) { request -> getRunInfo(request, principal) },
            RegisteredTool(
                Tool(
                    name = "list_artifacts",
                    description = "List the artifact files produced by a Homer agent run.",
                    inputSchema = runIdSchema(),
                ),
            ) { request -> listArtifacts(request, principal) },
            RegisteredTool(
                Tool(
                    name = "get_artifact",
                    description =
                        "Fetch a text artifact produced by a Homer agent run. Inlines up to " +
                            "${mcpConfig.maxInlineArtifactBytes} bytes; larger files are truncated " +
                            "(fetch the rest over GET /api/v1/artifacts). Binary files are rejected.",
                    inputSchema =
                        ToolSchema(
                            properties =
                                buildJsonObject {
                                    put("runId", buildJsonObject { put("type", "number") })
                                    put(
                                        "path",
                                        buildJsonObject {
                                            put("type", "string")
                                            put("description", "Artifact path relative to the run's artifacts dir (from list_artifacts)")
                                        },
                                    )
                                },
                            required = listOf("runId", "path"),
                        ),
                ),
            ) { request -> getArtifact(request, principal) },
            RegisteredTool(
                Tool(
                    name = "cancel_run",
                    description = "Cancel (kill) a running Homer agent run.",
                    inputSchema = runIdSchema(),
                ),
            ) { request -> cancelRun(request, principal) },
        )

    internal suspend fun getRunInfo(
        request: CallToolRequest,
        principal: String?,
    ): CallToolResult =
        runTool {
            val runId = requireRunId(request)
            val info = visibleRun(runId, principal) ?: return@runTool toolError("Run $runId not found")
            val structured =
                buildJsonObject {
                    put("runId", info.id)
                    put("agentName", info.agentName)
                    put("status", info.status.name)
                    put("costUsd", info.costUsd)
                    info.currentCommand?.let { put("currentCommand", it) }
                    put(
                        "commands",
                        buildJsonArray {
                            info.executions.forEach { exec ->
                                add(
                                    buildJsonObject {
                                        put("label", exec.label)
                                        put("resultCode", exec.resultCode)
                                        put("skipped", exec.skipped)
                                        exec.errMsg?.let { put("error", it) }
                                    },
                                )
                            }
                        },
                    )
                }
            toolResult(
                "Run $runId (agent '${info.agentName}') is ${info.status.name}; " +
                    "cost $${info.costUsd}, ${info.executions.size} command(s) executed.",
                structured,
            )
        }

    internal suspend fun listArtifacts(
        request: CallToolRequest,
        principal: String?,
    ): CallToolResult =
        runTool {
            val runId = requireRunId(request)
            if (visibleRun(runId, principal) == null) return@runTool toolError("Run $runId not found")
            val listing =
                artifactService.listArtifacts(runId).getOrElse {
                    return@runTool toolError("Run $runId: ${it.message}")
                }
            val structured =
                buildJsonObject {
                    put("runId", runId)
                    put(
                        "artifacts",
                        buildJsonArray {
                            listing.files.forEach { entry ->
                                add(
                                    buildJsonObject {
                                        put("path", entry.path)
                                        put("size", entry.size)
                                        put("modified", entry.modified)
                                    },
                                )
                            }
                        },
                    )
                }
            toolResult(
                "Run $runId has ${listing.files.size} artifact(s): " +
                    listing.files.joinToString(", ") { it.path }.ifEmpty { "none" },
                structured,
            )
        }

    internal suspend fun getArtifact(
        request: CallToolRequest,
        principal: String?,
    ): CallToolResult =
        runTool {
            val runId = requireRunId(request)
            val relativePath =
                (request.arguments?.get("path") as? JsonPrimitive)?.content
                    ?: return@runTool toolError("Missing required argument 'path'")
            if (visibleRun(runId, principal) == null) return@runTool toolError("Run $runId not found")
            val validated =
                artifactService.validateRelativePath(relativePath).getOrElse {
                    return@runTool toolError("Invalid path: ${it.message}")
                }
            val artifactPath =
                artifactService.resolveArtifactPath(runId, validated).getOrElse {
                    return@runTool toolError("Artifact not found: ${it.message}")
                }
            if (!Files.exists(artifactPath)) {
                return@runTool toolError("Artifact file not found: $relativePath")
            }
            val totalSize = Files.size(artifactPath)
            val cap = mcpConfig.maxInlineArtifactBytes
            val bytes =
                Files.newInputStream(artifactPath).use { stream ->
                    stream.readNBytes(cap.toInt())
                }
            if (bytes.any { it == 0.toByte() }) {
                return@runTool toolError(
                    "Artifact '$relativePath' looks binary; fetch it over " +
                        "GET /api/v1/artifacts?processId=$runId&path=$relativePath instead",
                )
            }
            val truncated = totalSize > cap
            val content = String(bytes, Charsets.UTF_8)
            val structured =
                buildJsonObject {
                    put("runId", runId)
                    put("path", relativePath)
                    put("totalSize", totalSize)
                    put("truncated", truncated)
                    // Clients that prefer structuredContent over the text block
                    // (per MCP spec, structured results are the data) must get
                    // the artifact body here too, not just metadata.
                    put("content", content)
                }
            val header =
                if (truncated) {
                    "Artifact '$relativePath' ($totalSize bytes; showing first $cap):\n"
                } else {
                    "Artifact '$relativePath' ($totalSize bytes):\n"
                }
            toolResult(header + content, structured)
        }

    internal suspend fun cancelRun(
        request: CallToolRequest,
        principal: String?,
    ): CallToolResult =
        runTool {
            val runId = requireRunId(request)
            val info =
                processManager.getProcessInfo(runId)
                    ?: return@runTool toolError("Run $runId not found")
            if (!authorizer.canControl(principal, info.owner, info.agentName)) {
                return@runTool toolError("Run $runId not found")
            }
            processManager.killProcess(runId, "Cancelled over MCP")
            val status = processManager.getProcessStatus(runId)
            toolResult(
                "Run $runId is now ${status.name}.",
                buildJsonObject {
                    put("runId", runId)
                    put("status", status.name)
                },
            )
        }

    /** Run [runId] when [principal] can see it; null when it is missing or hidden, which read the same (ADR-0039). */
    private suspend fun visibleRun(
        runId: Int,
        principal: String?,
    ): ProcessInfo? = processManager.getProcessInfo(runId)?.takeIf { authorizer.canSee(principal, it.owner, it.agentName) }

    private fun requireRunId(request: CallToolRequest): Int =
        (request.arguments?.get("runId") as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
            ?: throw IllegalArgumentException("Missing or non-numeric required argument 'runId'")

    private fun runIdSchema(): ToolSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    put(
                        "runId",
                        buildJsonObject {
                            put("type", "number")
                            put("description", "Run id returned when the run was started")
                        },
                    )
                },
            required = listOf("runId"),
        )
}
