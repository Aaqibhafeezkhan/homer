package homer.app.services

import homer.app.infra.ReservedEnvNames
import homer.app.models.Agent
import homer.app.models.ProcessInfo
import org.slf4j.Logger

/**
 * What a continuation or dispatch fired to the same agent inherits from the run that
 * registered it: the origin run's stored inputs keyed by **paramName** (the keying the
 * continuation's own `params` use), plus the origin run's `secret: true` envName
 * snapshot, which the fire unions into `extraSecretInputKeys`. See ADR-0101.
 */
internal data class InheritedOriginInputs(
    val params: Map<String, String> = emptyMap(),
    val secretInputKeys: Set<String> = emptySet(),
) {
    companion object {
        val NONE = InheritedOriginInputs()
    }
}

/**
 * Why: a resumed segment would otherwise lose its subject inputs — see ADR-0101.
 *
 * The origin run's inputs a fire to [target] inherits, or [InheritedOriginInputs.NONE].
 *
 * Every gate below fails closed to [InheritedOriginInputs.NONE] — an ineligible
 * continuation fires exactly as it did before this existed:
 *
 *  - [target]'s **live** definition must opt in (`continuation.inheritInputs`), read at
 *    fire time as `dispatch.enabled` already is, so disabling it stops pending fires.
 *  - [originAgentName] must equal `target.name`: the LangGraph segment case. A
 *    continuation to a *different* agent inherits nothing, so a hop can never read
 *    another agent's inputs through this path.
 *  - [origin] (the origin run's row, supplied as a supplier so a fire that inherits
 *    nothing — the flag-off default — reads no row) must exist and its owner must equal
 *    [originOwner], the owner the continuation/dispatch row carries. A purged row or an
 *    owner mismatch inherits nothing and logs; it does not fail the fire. Both sides are
 *    server-derived columns, never agent-written data.
 *
 * The values returned are the origin run's **stored** inputs, re-validated by the
 * caller's existing `AgentInputResolver` path at fire time — never trusted as "already
 * validated". [target]'s declared inputs are the keying bridge (stored inputs are
 * envName-keyed, continuation params are paramName-keyed): a name the agent no longer
 * declares is dropped, which also drops every reserved `HMR_*` marker (`HMR_LG_THREAD`,
 * `HMR_LG_RESUME`) that a previous segment stored, and bounds the map by the declared
 * input set so inheritance cannot grow from segment to segment.
 */
internal suspend fun inheritedOriginInputs(
    origin: suspend () -> ProcessInfo?,
    originAgentName: String,
    originOwner: String?,
    target: Agent,
    label: String,
    logger: Logger,
): InheritedOriginInputs {
    if (target.continuation?.inheritInputs != true) return InheritedOriginInputs.NONE
    if (originAgentName != target.name) return InheritedOriginInputs.NONE
    val originInfo = origin()
    if (originInfo == null) {
        logger.warn("$label to agent '{}': origin run row is gone, inheriting no inputs", target.name)
        return InheritedOriginInputs.NONE
    }
    if (originInfo.owner != originOwner) {
        logger.warn(
            "$label to agent '{}': origin run {} belongs to another owner, inheriting no inputs",
            target.name,
            originInfo.id,
        )
        return InheritedOriginInputs.NONE
    }
    return InheritedOriginInputs(
        params = declaredParams(target, originInfo.inputParams),
        secretInputKeys = originInfo.secretInputKeys.orEmpty(),
    )
}

/**
 * The inherited map's paramNames whose value survives the fire's merge — those values
 * are the origin run's **stored**, already-transformed ones, so the fire's
 * `AgentInputResolver.resolveFromParams` must validate but never transform them a
 * second time. An own param or the resume/answer value of the same name wins and is
 * transformed as caller-supplied input. See [inheritedOriginInputs], ADR-0101.
 */
internal fun InheritedOriginInputs.storedParamNames(ownParamNames: Set<String>): Set<String> = params.keys - ownParamNames

/**
 * The origin run's envName-keyed stored inputs as paramName-keyed params on [target],
 * dropping every name the agent does not declare (and every reserved name, which no
 * agent may declare) — see [inheritedOriginInputs].
 */
private fun declaredParams(
    target: Agent,
    storedInputs: Map<String, String>,
): Map<String, String> {
    val byEnvName = target.effectiveInputs.orEmpty().associateBy { it.envName }
    return storedInputs
        .mapNotNull { (envName, value) ->
            val param = byEnvName[envName] ?: return@mapNotNull null
            if (ReservedEnvNames.isReserved(envName)) null else param.paramName to value
        }.toMap()
}
