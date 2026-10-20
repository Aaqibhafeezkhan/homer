package homer.app

import homer.app.models.Agent
import homer.app.models.TagValidation

/**
 * Secret-input redaction for API responses.
 *
 * The secret set is the **union** of [storedSecretKeys] — the snapshot taken when the
 * run started — and the agent's current flags. Both directions matter and neither
 * alone is enough: the snapshot means an input that was `secret: true` then stays
 * redacted after the agent is edited to `secret: false`, and the current flags mean
 * marking an input `secret: true` today also protects records already retained.
 * That second direction is the whole point of the flag when an operator *discovers*
 * a value was sensitive. Null snapshot (a row from before the column) reduces the
 * union to the current definition, exactly what those rows always used. See ADR-0052.
 *
 * [agent] is the *current* definition; when it is gone (renamed/removed since the
 * run) we cannot know which values were secret, so everything is redacted — fail
 * closed. A stored key that matches no current input's envName (e.g. a secret
 * input was renamed since the run) is redacted the same way: an unknown key can't
 * be proven non-secret.
 */
internal fun redactInputParams(
    inputParams: Map<String, String>,
    agent: Agent?,
    storedSecretKeys: Set<String>? = null,
): Map<String, String> {
    if (agent == null) return inputParams.mapValues { "***" }
    val unprovable = unprovableKeyTest(agent, storedSecretKeys)
    return inputParams.mapValues { (k, v) -> if (unprovable(k)) "***" else v }
}

/**
 * Tests whether a key cannot be proven non-secret under the ADR-0052 union policy:
 * null [agent] fails closed on every key; otherwise any key in the secret-name
 * union or absent from the current definition is considered unprovable.
 * Single source of truth shared by [redactInputParams] and [screenTags]. See ADR-0052.
 * Built once per ([agent], [storedSecretKeys]) pair, since callers test every input key of a row.
 */
internal fun unprovableKeyTest(
    agent: Agent?,
    storedSecretKeys: Set<String>?,
): (String) -> Boolean {
    if (agent == null) return { true }
    val secretEnvNames = storedSecretKeys.orEmpty() + agent.secretEnvNames
    val allCurrentEnvNames =
        agent.effectiveInputs
            .orEmpty()
            .map { it.envName }
            .toSet()
    return { k -> k in secretEnvNames || k !in allCurrentEnvNames }
}

/**
 * Read-time screen for a run's subject tags: drops any tag containing a value that
 * is secret under the same ADR-0052 union [redactInputParams] uses, so marking an
 * input `secret: true` today also hides tags already stored, a key no longer in the
 * definition is treated as secret like [redactInputParams] treats it, and a missing
 * definition fails closed on every input value. Floor per
 * [TagValidation.SECRET_NEEDLE_MIN_LENGTH]. See ADR-0060.
 */
internal fun screenTags(
    tags: List<String>,
    inputParams: Map<String, String>,
    agent: Agent?,
    storedSecretKeys: Set<String>?,
): List<String> {
    if (tags.isEmpty()) return tags
    val unprovable = unprovableKeyTest(agent, storedSecretKeys)
    val needles =
        inputParams
            .filter { (k, _) -> unprovable(k) }
            .values
            .filter { it.length >= TagValidation.SECRET_NEEDLE_MIN_LENGTH }
    if (needles.isEmpty()) return tags
    return tags.filter { tag -> needles.none { tag.contains(it) } }
}

/**
 * Secret-input redaction for the stored parameters of a dispatch or continuation row.
 *
 * These `params` are stored keyed by **paramName** — the caller-supplied request key
 * `DispatchService.create`/`ContinuationService.create` persist verbatim.
 * `AgentInputResolver.resolveFromParams` converts to envName only at fire time. Three
 * checks, **unioned** — none replaces another:
 *  - [secretEnvNames] is translated to paramNames via the *current* agent definition
 *    (an input whose envName is secret-classified marks its paramName too);
 *  - the raw stored key is also checked directly against [secretEnvNames] — a key that
 *    literally equals a registered secret envName has no legitimate readable case (the
 *    common shape, since envName defaults to paramName absent an explicit override); and
 *  - [storedSecretParamNames] — a **paramName-keyed** snapshot taken at registration
 *    (`dispatches`/`continuations.secret_param_names`, schema v17) — is checked directly,
 *    since it is already in the params map's own key space.
 *
 * The first two checks between them still miss one case: an explicit `paramName`/
 * `envName` split whose input is **deleted or re-pointed to a different envName** since
 * registration. The envName translation has no current input left to translate through,
 * and the raw envName check never matches because the stored key was never the envName
 * to begin with. [storedSecretParamNames] closes that gap for v17+ rows by recording the
 * paramName directly, so it needs no current definition to recover it (a follow-up
 * from the MR security review). Rows registered before v17 have a null snapshot and
 * keep exactly the coverage the first two checks provide, same framing as the pre-v16
 * rows below.
 *
 * Same union policy as [redactInputParams] — the snapshot captured at registration plus
 * the target agent's current flags — for the same two reasons: an edit must not reopen a
 * closed door, and marking an input secret must cover rows already registered. A missing
 * target agent redacts everything, since nothing can then be proven non-secret.
 *
 * Keys absent from the current definition are redacted **only when there is no
 * [storedSecretKeys] snapshot**. With one, unknown keys are left readable: these maps
 * legitimately carry control parameters that were never declared inputs, and blanking
 * them would cost the console its view for no gain. Without one at all — a row from
 * before v16 — that does not hold: a formerly-secret input renamed since matches neither
 * check, so an unknown key cannot be proven non-secret and fails closed, exactly as
 * [redactInputParams] treats it. Such rows age out with their TTL.
 * See ADR-0052.
 */
internal fun redactStoredParams(
    params: Map<String, String>,
    storedSecretKeys: Set<String>?,
    agent: Agent?,
    storedSecretParamNames: Set<String>?,
): Map<String, String> {
    if (agent == null) return params.mapValues { "***" }
    val secretEnvNames = storedSecretKeys.orEmpty() + agent.secretEnvNames
    val inputs = agent.effectiveInputs.orEmpty()
    val secretParamNames =
        inputs.filter { it.envName in secretEnvNames }.map { it.paramName }.toSet() + storedSecretParamNames.orEmpty()
    val declaredParamNames = inputs.map { it.paramName }.toSet()
    return params.mapValues { (k, v) ->
        when {
            k in secretParamNames || k in secretEnvNames -> "***"
            storedSecretKeys == null && k !in declaredParamNames -> "***"
            else -> v
        }
    }
}
