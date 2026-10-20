package homer.app.infra

import homer.app.models.Agent
import homer.app.models.TagValidation
import homer.app.models.TelemetryExport
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanId
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceId
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.api.trace.Tracer
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * Root-span construction and trace tagging for an agent run, shared by every entry
 * point that starts one: `AgentRunStarter` and the retry / resume / debug handlers,
 * which build their own root spans. Keeping both here is deliberate — each of those
 * handlers previously re-implemented span setup and quietly missed whatever the
 * others gained. See ADR-0050.
 */
private val logger = LoggerFactory.getLogger("homer.app.infra.AgentTracing")

/**
 * Starts this run's root span, honouring the agent's `telemetry.export`.
 *
 * A muted run gets a **valid but unsampled** span context rather than an invalid one,
 * and that distinction is the whole mechanism: every span Homer creates for a run
 * parents off this one, so the SDK's default `ParentBased` sampler drops the entire
 * subtree. An invalid parent would make each child a new root instead — exporting
 * more, not less. The wrapped span is non-recording, so a caller's `setAttribute` /
 * `setStatus` / `end` calls are no-ops and no call site has to branch.
 */
internal fun Tracer.startAgentRootSpan(
    agent: Agent,
    spanName: String,
    random: Random = Random.Default,
): Span =
    if (agent.telemetry.export == TelemetryExport.OFF) {
        Span.wrap(
            SpanContext.create(
                // `or 1` keeps the low word non-zero: an all-zero id is the invalid id.
                TraceId.fromLongs(random.nextLong(), random.nextLong() or 1L),
                SpanId.fromLong(random.nextLong() or 1L),
                TraceFlags.getDefault(),
                TraceState.getDefault(),
            ),
        )
    } else {
        spanBuilder(spanName).startSpan()
    }

/**
 * `paramName:value` tags for the inputs named in the run's effective `fromInputs`
 * (`tags.fromInputs` unioned with the deprecated `telemetry.traceTags`; see
 * [Agent.effectiveFromInputTags]), so a run is findable by its subject (ticket, MR,
 * thread) — both as a Langfuse trace tag (the traces API filters on tags, and cannot
 * see span attributes) and as a queryable process tag (`?tag=`). One derivation feeds
 * both, so the exported tag and the stored tag never diverge. See ADR-0060 and ADR-0050.
 *
 * Names are resolved against the agent's declared inputs, which agent load has already
 * verified exist and are not `secret`; an unresolvable name here means the agent was
 * edited underneath us, so it is skipped rather than tagged with a guess.
 *
 * [secretEnvNames] is **required, with no default, on purpose.** Load validation only
 * rejects tagging an input that is secret *now*, which is not the same question for a
 * path replaying stored inputs: an input that was secret when the run happened, later
 * flipped to `secret: false` and added to `fromInputs`, would otherwise be exported as a
 * cleartext tag. Retry and resume must pass the union of the source run's snapshot and
 * the current definition. A default here is precisely how a future call site would
 * silently reintroduce that leak, so omitting it is a compile error. See ADR-0052.
 */
internal fun langfuseTraceTags(
    agent: Agent,
    resolvedInputs: Map<String, String>,
    secretEnvNames: Set<String>,
): List<String> {
    val fromInputs = agent.effectiveFromInputTags
    if (fromInputs.isEmpty()) return emptyList()
    val byParamName = agent.effectiveInputs.orEmpty().associateBy { it.paramName }
    return fromInputs.mapNotNull { paramName ->
        val param = byParamName[paramName] ?: return@mapNotNull null
        if (param.envName in secretEnvNames) return@mapNotNull null
        val value = resolvedInputs[param.envName]?.trim()
        when {
            // An unset/empty input just means "no tag for this run", not a defect.
            value.isNullOrEmpty() -> {
                null
            }

            // Out-of-charset or oversized values are dropped, not rewritten: a
            // truncated/mangled value hits no exact-match filter and misrepresents
            // the run. Name the offending value class so the author can fix it.
            !TagValidation.VALUE_PATTERN.matches(value) -> {
                val reason =
                    if (value.length > TagValidation.MAX_TAG_VALUE_LENGTH) {
                        "is ${value.length} chars, over the ${TagValidation.MAX_TAG_VALUE_LENGTH}-char tag-value limit"
                    } else {
                        "has characters outside [A-Za-z0-9._-]"
                    }
                logger.warn("Agent '{}': input '{}' {}; not tagging the run", agent.name, paramName, reason)
                null
            }

            else -> {
                "$paramName:$value"
            }
        }
    }
}
