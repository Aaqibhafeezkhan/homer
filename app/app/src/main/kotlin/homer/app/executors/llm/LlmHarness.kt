package homer.app.executors.llm

import homer.app.infra.OutputLineFilter
import homer.app.models.Command
import homer.app.models.LlmEscalation
import homer.app.models.LlmEvent
import homer.app.models.Translation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One LLM coding-harness CLI, as seen by [LlmHarnessExecutor]: how to launch it and
 * how to read what it wrote. Stateless; constructed with its own config only, so it is
 * tested as plain functions against recorded stdout lines. See ADR-0070.
 */
interface LlmHarness<C> where C : Command, C : Command.LlmCommand {
    /** Equals the command's [homer.app.models.kind] (`claude`, `codex`). */
    val id: String

    /** Whether the CLI reports a USD cost per run. False makes an unpriced attempt record CostSource.NONE. */
    val reportsCost: Boolean

    /**
     * Whether each distinct message id in the stream is one model request. False makes the
     * session-spend lookup settle on a stable figure instead of a call count. See ADR-0070.
     */
    val countsModelCalls: Boolean

    fun argv(
        command: C,
        env: Map<String, String>,
    ): List<String>

    /** Extra process env beyond the workspace env and the trace parent the executor adds. */
    fun processEnv(command: C): Map<String, String> = emptyMap()

    fun stdOutFilter(command: C): LlmStdOutFilter? = null

    /** [command] as one escalation tier runs it: its model, and its raw CLI options when the tier sets them. See ADR-0079. */
    fun escalated(
        command: C,
        tier: LlmEscalation,
    ): C

    /** Total: never throws. One raw line may yield several events (Codex items). */
    fun translate(line: JsonObject): Translation

    /** Judges a zero-exit attempt. [raw] is the strict-parsed line list, for checks the canonical model does not carry (Claude's version pin). */
    fun inspect(
        events: List<LlmEvent>,
        raw: List<JsonObject>,
        command: C,
    ): Inspection
}

sealed interface Inspection {
    data object Success : Inspection

    data class Malformed(
        val reason: String,
    ) : Inspection

    /** A terminal, recoverable stop the agent opted into; returned with this exit code, never retried. */
    data class Terminal(
        val exitCode: Int,
    ) : Inspection
}

/** A stdout filter that can report what it removed, for the attempt's stderr summary. */
interface LlmStdOutFilter : OutputLineFilter {
    fun dropped(): List<DroppedSubtype>
}

/** The string at [key], or null when it is absent, not a primitive, or blank. */
internal fun JsonObject.nonBlankString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
