package homer.app.executors.llm

import homer.app.models.Command
import homer.app.models.LlmEscalation
import homer.app.models.LlmEvent
import homer.app.models.LlmUsage
import homer.app.models.Translation
import homer.app.models.TurnStatus
import homer.app.models.gateSessionId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A minimal dialect for the generic-executor tests: `{"t":"start","sid":..}`,
 * `{"t":"msg","id":..}`, `{"t":"end","ok":true|false,"cost":..,"in":..}`, `{"t":"stop"}`,
 * `{"t":"junk"}`. Codex is the real "no-cost" harness; Claude is the real "cost" harness;
 * this one exercises the executor without either adapter's own rules.
 */
open class FakeHarness(
    private val terminalExit: Int? = null,
    override val reportsCost: Boolean = true,
    override val countsModelCalls: Boolean = true,
) : LlmHarness<Command.Claude> {
    override val id = "claude"

    override fun argv(
        command: Command.Claude,
        env: Map<String, String>,
    ) = listOfNotNull(command.cmd, "--fake", command.options.model)

    override fun escalated(
        command: Command.Claude,
        tier: LlmEscalation,
    ) = command.copy(options = command.options.copy(model = tier.model, options = tier.options ?: command.options.options))

    override fun translate(line: JsonObject): Translation =
        when ((line["t"] as? JsonPrimitive)?.contentOrNull) {
            "start" -> {
                Translation.Events(
                    listOf(LlmEvent.SessionStart(gateSessionId((line["sid"] as? JsonPrimitive)?.contentOrNull), "fake-model")),
                )
            }

            "msg" -> {
                Translation.Events(listOf(LlmEvent.AssistantMessage((line["id"] as? JsonPrimitive)?.contentOrNull, null, "x")))
            }

            "end" -> {
                Translation.Events(
                    listOf(
                        LlmEvent.TurnEnd(
                            if ((line["ok"] as? JsonPrimitive)?.contentOrNull == "false") TurnStatus.Failed("bad") else TurnStatus.Success,
                            usage = LlmUsage(input = (line["in"] as? JsonPrimitive)?.longOrNull ?: 0L),
                            costUsd = (line["cost"] as? JsonPrimitive)?.doubleOrNull,
                        ),
                    ),
                )
            }

            "stop" -> {
                Translation.Events(listOf(LlmEvent.TurnEnd(TurnStatus.Stopped("cap"))))
            }

            "junk" -> {
                Translation.Malformed("junk")
            }

            else -> {
                Translation.Ignored
            }
        }

    override fun inspect(
        events: List<LlmEvent>,
        raw: List<JsonObject>,
        command: Command.Claude,
    ): Inspection {
        val end = events.filterIsInstance<LlmEvent.TurnEnd>().firstOrNull() ?: return Inspection.Malformed("no end")
        return when (end.status) {
            TurnStatus.Success -> Inspection.Success
            is TurnStatus.Stopped -> terminalExit?.let { Inspection.Terminal(it) } ?: Inspection.Malformed("stopped")
            is TurnStatus.Failed -> Inspection.Malformed("failed")
        }
    }
}
