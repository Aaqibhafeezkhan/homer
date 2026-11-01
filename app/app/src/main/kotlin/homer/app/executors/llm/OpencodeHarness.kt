package homer.app.executors.llm

import homer.app.infra.DefaultTextCompiler
import homer.app.infra.TextCompiler
import homer.app.models.Command
import homer.app.models.LlmEscalation
import homer.app.models.LlmEvent
import homer.app.models.LlmUsage
import homer.app.models.Translation
import homer.app.models.TurnStatus
import homer.app.models.gateSessionId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** opencode CLI (`opencode run --format json`). Every accessor is untyped and total. */
class OpencodeHarness(
    textCompiler: TextCompiler = DefaultTextCompiler(),
) : LlmHarness<Command.Opencode> {
    override val id: String = "opencode"

    // `step_finish` carries the step's own USD cost.
    override val reportsCost: Boolean = true

    // One step can carry text and a tool call, so a message id is not one model request.
    override val countsModelCalls: Boolean = false
    private val argvBuilder = OpencodeArgvBuilder(textCompiler)
    private val stepCosts = StepCostTotals()

    override fun argv(
        command: Command.Opencode,
        env: Map<String, String>,
    ): List<String> = argvBuilder.build(command.cmd, command.options, env)

    override fun escalated(
        command: Command.Opencode,
        tier: LlmEscalation,
    ): Command.Opencode =
        command.copy(options = command.options.copy(model = tier.model, options = tier.options ?: command.options.options))

    override fun translate(line: JsonObject): Translation =
        when (line.nonBlankString("type")) {
            "step_start" -> {
                Translation.Events(listOf(LlmEvent.SessionStart(gateSessionId(line.nonBlankString("sessionID")), model = null)))
            }

            "text" -> {
                text(line)
            }

            "tool_use" -> {
                toolUse(line)
            }

            "step_finish" -> {
                stepFinish(line)
            }

            "error" -> {
                transientError(line)
            }

            else -> {
                Translation.Ignored
            }
        }

    override fun inspect(
        events: List<LlmEvent>,
        raw: List<JsonObject>,
        command: Command.Opencode,
    ): Inspection =
        inspectTurns(
            events,
            failedReason = "stream contains a failed 'step_finish' event",
            noSuccessReason = "stdout contains no successful 'step_finish' event",
        )

    private fun text(line: JsonObject): Translation {
        val part = line["part"] as? JsonObject ?: return Translation.Malformed("text without a part object")
        val body = part.nonBlankString("text") ?: return Translation.Malformed("text part without text")
        return Translation.Events(listOf(LlmEvent.AssistantMessage(part.nonBlankString("messageID"), model = null, text = body)))
    }

    private fun toolUse(line: JsonObject): Translation {
        val part = line["part"] as? JsonObject ?: return Translation.Malformed("tool_use without a part object")
        // The tool part's own id is `partID`; `id` is the tool call's.
        val callId = part.nonBlankString("id") ?: return Translation.Malformed("tool part without a call id")
        val name = part.nonBlankString("tool") ?: return Translation.Malformed("tool part without a tool name")
        val state = part["state"] as? JsonObject ?: return Translation.Malformed("tool part without a state object")
        val call = LlmEvent.ToolCall(callId, name, state["input"] ?: JsonPrimitive(""), part.nonBlankString("messageID"))
        return toolResult(state, call)
    }

    /** The tool part's state decides whether the call already has a result to pair it with. */
    private fun toolResult(
        state: JsonObject,
        call: LlmEvent.ToolCall,
    ): Translation =
        when (val status = state.nonBlankString("status")) {
            "completed" -> {
                Translation.Events(listOf(call, LlmEvent.ToolResult(call.id, state["output"] ?: JsonPrimitive(""), isError = false)))
            }

            "error" -> {
                Translation.Events(listOf(call, LlmEvent.ToolResult(call.id, state["error"] ?: JsonPrimitive(""), isError = true)))
            }

            // The call is known, its result is not yet.
            "pending", "running", "streaming" -> {
                Translation.Events(listOf(call))
            }

            else -> {
                Translation.Malformed("tool part with an unknown state '${status ?: ""}'")
            }
        }

    private fun stepFinish(line: JsonObject): Translation {
        val part = line["part"] as? JsonObject ?: return Translation.Malformed("step_finish without a part object")
        val reason = part.nonBlankString("reason") ?: return Translation.Malformed("step_finish without a reason")
        val stepId = part.nonBlankString("id") ?: return Translation.Malformed("step_finish without a part id")
        // ADR-0074: `cost` is this step's own, while TurnEnd.costUsd must be cumulative for the
        // process, so the step's cost is accumulated by session. A stream whose session id does
        // not gate has no bucket to accumulate in, and reports the step alone.
        // CWE-682: a negative or non-finite `cost` in agent-written stdout is no cost at all — an
        // over-large literal parses to Infinity and would poison the cumulative figure for good.
        val stepCost = (part["cost"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val cost = gateSessionId(line.nonBlankString("sessionID"))?.let { stepCosts.add(it, stepId, stepCost) } ?: stepCost
        return Translation.Events(listOf(LlmEvent.TurnEnd(status(reason), usage(part["tokens"] as? JsonObject), costUsd = cost)))
    }

    private fun status(reason: String): TurnStatus =
        when (reason) {
            // opencode's own FinishReason set: the run ended normally, or on a budget the agent asked for.
            "stop", "length", "tool-calls", "unknown" -> TurnStatus.Success

            // A provider refusal, and any reason a future CLI adds: fail closed (ADR-0047 rule 3).
            else -> TurnStatus.Failed(reason)
        }

    private fun usage(tokens: JsonObject?): LlmUsage {
        val cache = tokens?.get("cache") as? JsonObject
        return LlmUsage(
            input = tokens.count("input"),
            output = tokens.count("output"),
            cacheRead = cache.count("read"),
            cacheWrite = cache.count("write"),
            reasoning = tokens.count("reasoning"),
        )
    }

    // CWE-345: a negative or unparseable token count in agent-written stdout counts as none.
    private fun JsonObject?.count(key: String): Long = ((this?.get(key) as? JsonPrimitive)?.longOrNull ?: 0L).coerceAtLeast(0L)
}

/**
 * Running per-step cost totals, one bucket per opencode session. ADR-0074 requires
 * `TurnEnd.costUsd` to be cumulative for the harness process, while opencode reports one
 * step's own cost on each `step_finish`, so [OpencodeHarness] accumulates.
 *
 * Each adapter instance carries its own [StepCostTotals] — the executor factory, the
 * `/llm-events` harness map and agentdebug each build their own — and one instance reads
 * a stdout file more than once (the attempt's accounting scan and the telemetry hand-off;
 * two live subscribers on the stream service's), so a step counts once, by the id
 * opencode puts on it, per instance. The retained state is bounded (§12 rule 4: the
 * session id is agent-written stdout, so its cardinality is not ours): the bound sheds
 * one eldest session's bucket, a live run's once 64 newer sessions arrived while it
 * ran — that run's cumulative then restarts, and the accounting scan's full-file
 * re-read rebuilds it, so the charge stays ADR-0074-correct; only an incremental
 * stream read of the shed run can see the restart.
 */
internal class StepCostTotals {
    private val sessions = HashMap<String, Session>()

    /** Sessions in insertion order, so the bound knows which bucket is eldest. */
    private val order = ArrayDeque<String>()

    /** The session's running total once [stepId]'s [cost] is counted; a step already counted leaves it unchanged. */
    @Synchronized
    fun add(
        sessionId: String,
        stepId: String,
        cost: Double,
    ): Double {
        // Checked before the insert, and only for a session with no bucket yet: eviction makes
        // room for exactly one new session by dropping one eldest bucket. Clearing every
        // session instead would restart a mid-stream run's cumulative `TurnEnd.costUsd`, making
        // the charge depend on when the scan reads the file — the order-dependent under-charge
        // ADR-0074 forbids.
        if (!sessions.containsKey(sessionId) && sessions.size >= MAX_TRACKED_SESSIONS) {
            sessions.remove(order.removeFirstOrNull())
        }
        val existing = sessions[sessionId]
        if (existing != null) return existing.add(stepId, cost)
        val created = Session()
        sessions[sessionId] = created
        order.addLast(sessionId)
        return created.add(stepId, cost)
    }

    private class Session {
        private val counted = HashSet<String>()
        private var total = 0.0

        fun add(
            stepId: String,
            cost: Double,
        ): Double {
            if (counted.size < MAX_COUNTED_STEPS && counted.add(stepId)) total += cost
            return total
        }
    }

    private companion object {
        const val MAX_TRACKED_SESSIONS = 64
        const val MAX_COUNTED_STEPS = 4_096
    }
}
