package homer.app

import homer.app.fixtures.AgentFixtures
import homer.app.models.Param
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Redaction of the parameters stored on a dispatch or continuation row.
 *
 * Those rows hold their parameters from registration until an operator answers or a
 * watched process ends, and the list/detail endpoints return them to anyone who can
 * see the row — so they need the same treatment a process's inputs get. See ADR-0052.
 *
 * `params` is stored keyed by **paramName** (DispatchService.create /
 * ContinuationService.create persist the caller-supplied request map verbatim;
 * AgentInputResolver.resolveFromParams converts to envName only at fire time), so
 * every case below uses `paramName` as the map key — not `envName` — to match what is
 * actually on the row. A `Param` whose `paramName` differs from its `envName` is the
 * case a security scan found unredacted.
 *
 * `storedSecretParamNames` is the schema v17 paramName-keyed snapshot
 * (`secret_param_names`), taken alongside the pre-existing envName-keyed one at
 * registration; `null` throughout most cases below models a pre-v17 row (or one where
 * the two snapshots agree with the current definition anyway) — see the dedicated
 * v17 cases for what it closes.
 */
class RedactStoredParamsTest {
    private fun agentWith(vararg inputs: Param) = AgentFixtures.simpleAgent(name = "a").copy(inputs = inputs.toList())

    @Test
    fun `a secret input is masked in the stored params`() {
        val agent = agentWith(Param(paramName = "token", envName = "TOKEN", secret = true))

        assertEquals(
            mapOf("token" to "***", "MR_IID" to "4211"),
            redactStoredParams(mapOf("token" to "tok-secret", "MR_IID" to "4211"), emptySet(), agent, storedSecretParamNames = null),
        )
    }

    @Test
    fun `a secret input is masked even when its paramName differs from its envName`() {
        // The stored key is "token" (paramName), but the secret set is
        // envName-keyed ("AUTH_TOKEN"). Comparing them directly leaves this unredacted.
        val agent = agentWith(Param(paramName = "token", envName = "AUTH_TOKEN", secret = true))

        assertEquals(
            mapOf("token" to "***"),
            redactStoredParams(mapOf("token" to "tok-secret"), emptySet(), agent, storedSecretParamNames = null),
        )
    }

    @Test
    fun `an input flipped to non-secret since registration stays masked`() {
        val agent = agentWith(Param(paramName = "token", envName = "TOKEN", secret = false))

        assertEquals(
            mapOf("token" to "***"),
            redactStoredParams(mapOf("token" to "tok-secret"), setOf("TOKEN"), agent, storedSecretParamNames = null),
        )
    }

    @Test
    fun `marking an input secret after registration masks rows already stored`() {
        val agent = agentWith(Param(paramName = "token", envName = "TOKEN", secret = true))

        assertEquals(
            mapOf("token" to "***"),
            redactStoredParams(mapOf("token" to "tok-secret"), emptySet(), agent, storedSecretParamNames = null),
        )
    }

    @Test
    fun `a row registered before the snapshot column falls back to the current flags`() {
        val agent = agentWith(Param(paramName = "token", envName = "TOKEN", secret = true))

        assertEquals(
            mapOf("token" to "***"),
            redactStoredParams(mapOf("token" to "tok-secret"), storedSecretKeys = null, agent = agent, storedSecretParamNames = null),
        )
    }

    @Test
    fun `without a snapshot an unknown key fails closed`() {
        // The case that breaks the "leave control params readable" shortcut: a pre-v16
        // row whose formerly-secret input has since been renamed matches neither the
        // current flags nor a snapshot, so it cannot be proven non-secret.
        val agent = agentWith(Param(paramName = "mr", envName = "MR_IID"))

        assertEquals(
            mapOf("mr" to "4211", "OLD_TOKEN" to "***"),
            redactStoredParams(
                mapOf("mr" to "4211", "OLD_TOKEN" to "tok-secret"),
                storedSecretKeys = null,
                agent = agent,
                storedSecretParamNames = null,
            ),
        )
    }

    @Test
    fun `without a snapshot a declared param stays readable even when its paramName differs from its envName`() {
        // The no-snapshot fail-closed check must be paramName-aware too — matching
        // against envName here would wrongly redact a legitimate, declared value.
        val agent = agentWith(Param(paramName = "mr", envName = "MR_IID"))

        assertEquals(
            mapOf("mr" to "4211"),
            redactStoredParams(mapOf("mr" to "4211"), storedSecretKeys = null, agent = agent, storedSecretParamNames = null),
        )
    }

    @Test
    fun `a non-secret param with paramName differing from envName stays readable when a snapshot exists`() {
        val agent = agentWith(Param(paramName = "mr", envName = "MR_IID", secret = false))

        assertEquals(
            mapOf("mr" to "4211"),
            redactStoredParams(mapOf("mr" to "4211"), emptySet(), agent, storedSecretParamNames = null),
        )
    }

    @Test
    fun `control parameters that were never declared inputs are left readable`() {
        // Unlike a process's inputs, these maps legitimately carry control params. The
        // snapshot covers a renamed secret input, so blanking unknown keys would cost
        // the console its view for no gain.
        val agent = agentWith(Param(paramName = "mr", envName = "MR_IID"))

        assertEquals(
            mapOf("mr" to "4211", "HMR_LG_THREAD" to "t-1"),
            redactStoredParams(mapOf("mr" to "4211", "HMR_LG_THREAD" to "t-1"), emptySet(), agent, storedSecretParamNames = null),
        )
    }

    @Test
    fun `everything is masked when the target agent is gone`() {
        assertEquals(
            mapOf("mr" to "***"),
            redactStoredParams(mapOf("mr" to "4211"), emptySet(), agent = null, storedSecretParamNames = null),
        )
    }

    @Test
    fun `a secret input removed since registration stays masked via the snapshot`() {
        // The paramName translation alone finds nothing here (no current input has
        // this envName any more) — only the raw envName check catches it.
        val agent = agentWith()

        assertEquals(
            mapOf("token" to "***"),
            redactStoredParams(
                mapOf("token" to "tok-secret"),
                storedSecretKeys = setOf("token"),
                agent = agent,
                storedSecretParamNames = null,
            ),
        )
    }

    @Test
    fun `a secret input re-pointed to a new envName with secret flipped off stays masked via the snapshot`() {
        // Same gap, different shape: the input still exists, but its *current* envName
        // no longer matches the snapshot's, so the paramName translation misses it too.
        val agent = agentWith(Param(paramName = "token", envName = "TOKEN_V2", secret = false))

        assertEquals(
            mapOf("token" to "***"),
            redactStoredParams(
                mapOf("token" to "tok-secret"),
                storedSecretKeys = setOf("token"),
                agent = agent,
                storedSecretParamNames = null,
            ),
        )
    }

    @Test
    fun `without a paramName snapshot the residual persists for a deleted input with an explicit paramName split`() {
        // Documents the accepted residual this commit does NOT retroactively fix: a
        // pre-v17 row (storedSecretParamNames = null) with an explicit paramName/envName
        // split whose input was later deleted has no way to recover the historical
        // paramName — the value comes back in cleartext until the row ages out (ADR-0052).
        val agent = agentWith()

        assertEquals(
            mapOf("token" to "tok-secret"),
            redactStoredParams(
                mapOf("token" to "tok-secret"),
                storedSecretKeys = setOf("AUTH_TOKEN"),
                agent = agent,
                storedSecretParamNames = null,
            ),
        )
    }

    @Test
    fun `a paramName snapshot closes the gap for a deleted input with an explicit paramName split`() {
        // The recommended fix: a v17+ row's paramName-keyed snapshot is checked
        // directly, so it needs no current input to translate through — unlike the
        // sibling test above, this one is masked.
        val agent = agentWith()

        assertEquals(
            mapOf("token" to "***"),
            redactStoredParams(
                mapOf("token" to "tok-secret"),
                storedSecretKeys = setOf("AUTH_TOKEN"),
                agent = agent,
                storedSecretParamNames = setOf("token"),
            ),
        )
    }

    @Test
    fun `a paramName snapshot closes the gap for an input re-pointed to a different envName`() {
        val agent = agentWith(Param(paramName = "token", envName = "TOKEN_V2", secret = false))

        assertEquals(
            mapOf("token" to "***"),
            redactStoredParams(
                mapOf("token" to "tok-secret"),
                storedSecretKeys = setOf("AUTH_TOKEN"),
                agent = agent,
                storedSecretParamNames = setOf("token"),
            ),
        )
    }
}
