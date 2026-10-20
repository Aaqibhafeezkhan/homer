package homer.app

import homer.app.fixtures.AgentFixtures
import homer.app.models.Param
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * API-response redaction of a run's stored inputs.
 *
 * The property that matters: what was secret *when the run happened* stays
 * redacted, whatever the agent definition says now. Records outlive their
 * workspace (ADR-0048), so the definition has plenty of time to change under
 * them. See ADR-0052.
 */
class RedactInputParamsTest {
    private fun agentWith(vararg inputs: Param) = AgentFixtures.simpleAgent(name = "a").copy(inputs = inputs.toList())

    @Test
    fun `an input flipped to non-secret since the run stays redacted`() {
        // The exposure this closes: the value was collected under `secret: true`,
        // the agent was later edited, and the retained record would have started
        // serving the historical credential in the clear.
        val agent = agentWith(Param(paramName = "token", envName = "TOKEN", secret = false))

        val redacted =
            redactInputParams(
                inputParams = mapOf("TOKEN" to "tok-secret"),
                agent = agent,
                storedSecretKeys = setOf("TOKEN"),
            )

        assertEquals(mapOf("TOKEN" to "***"), redacted)
    }

    @Test
    fun `marking an input secret now also protects records already retained`() {
        // The other direction, and the one an operator reaches for on DISCOVERING that
        // a value was sensitive. Preferring the snapshot alone would make `secret: true`
        // useless as a remediation: the records that already hold the value are exactly
        // the ones that need covering.
        val agent = agentWith(Param(paramName = "mr", envName = "MR_IID", secret = true))

        val redacted =
            redactInputParams(
                inputParams = mapOf("MR_IID" to "4211"),
                agent = agent,
                storedSecretKeys = emptySet(),
            )

        assertEquals(mapOf("MR_IID" to "***"), redacted)
    }

    @Test
    fun `a row without a stored snapshot falls back to the current definition`() {
        // Rows predating the column keep exactly the behaviour they had, rather than
        // being blanket-redacted on upgrade.
        val agent =
            agentWith(
                Param(paramName = "token", envName = "TOKEN", secret = true),
                Param(paramName = "mr", envName = "MR_IID"),
            )

        val redacted =
            redactInputParams(
                inputParams = mapOf("TOKEN" to "tok-secret", "MR_IID" to "4211"),
                agent = agent,
                storedSecretKeys = null,
            )

        assertEquals(mapOf("TOKEN" to "***", "MR_IID" to "4211"), redacted)
    }

    @Test
    fun `a key unknown to the current definition is redacted even when the snapshot omits it`() {
        // An unknown key cannot be proven non-secret, so it fails closed regardless.
        val agent = agentWith(Param(paramName = "mr", envName = "MR_IID"))

        val redacted =
            redactInputParams(
                inputParams = mapOf("MR_IID" to "4211", "GONE" to "value"),
                agent = agent,
                storedSecretKeys = emptySet(),
            )

        assertEquals(mapOf("MR_IID" to "4211", "GONE" to "***"), redacted)
    }

    @Test
    fun `everything is redacted when the agent is gone`() {
        val redacted =
            redactInputParams(
                inputParams = mapOf("MR_IID" to "4211"),
                agent = null,
                storedSecretKeys = emptySet(),
            )

        assertEquals(mapOf("MR_IID" to "***"), redacted)
    }
}
