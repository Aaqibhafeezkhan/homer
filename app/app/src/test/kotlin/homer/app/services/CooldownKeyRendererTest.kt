package homer.app.services

import homer.app.exceptions.CooldownKeyInvalidException
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.Cooldown
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CooldownKeyRendererTest {
    private fun agent(
        key: String?,
        env: Map<String, String> = emptyMap(),
    ) = Agent(
        name = "reviewer",
        env = env,
        commands = listOf(Command.Cmd(cmd = listOf("true"))),
        cooldown = Cooldown(minInterval = "1m", key = key),
    )

    @Test
    fun `an agent without a key renders nothing`() {
        assertEquals(null, agent(null).renderCooldownKey(mapOf("MR" to "1")))
    }

    @Test
    fun `a placeholder resolves from the run's resolved inputs`() {
        assertEquals("mr-1", agent("mr-{{MR}}").renderCooldownKey(mapOf("MR" to "1")))
    }

    @Test
    fun `a placeholder resolves from the agent's own env`() {
        // HMR_AGENT_DIR rides agent.env, so a template may name it; HMR_PROCESS_ID does not
        // exist yet at render time and is covered by the unresolved case below.
        assertEquals(
            "team-core",
            agent("team-{{TEAM}}", env = mapOf("TEAM" to "core")).renderCooldownKey(emptyMap()),
        )
    }

    @Test
    fun `a resolved input overrides an env entry of the same name`() {
        // The same layer order the workspace env uses, so the key names the subject the run
        // actually runs over rather than the agent's static default.
        assertEquals("mr-2", agent("mr-{{MR}}", env = mapOf("MR" to "1")).renderCooldownKey(mapOf("MR" to "2")))
    }

    @Test
    fun `a key with no placeholder renders as itself`() {
        assertEquals("nightly", agent("nightly").renderCooldownKey(emptyMap()))
    }

    @Test
    fun `an unresolved placeholder fails closed and names only the placeholder`() {
        val e =
            assertFailsWith<CooldownKeyInvalidException> {
                agent("mr-{{MR}}").renderCooldownKey(mapOf("TOKEN" to "s3cr3t-value"))
            }

        assertEquals("reviewer", e.agentName)
        assertTrue(e.message!!.contains("{{MR}}"), e.message!!)
        // §12 rule 7: the refusal carries the placeholder name, never an env value — and the
        // template is never handed on as a literal every subject would then share.
        assertTrue(!e.message!!.contains("s3cr3t-value"), e.message!!)
    }

    @Test
    fun `a placeholder naming a replayed stored-secret input is refused`() {
        // Retry, resume and the dispatch/continuation fires replay stored inputs: what was
        // secret for the source run stays secret for the replay, so the key must not render
        // from it — the value would land in the plain-text `cooldown_key` column and its index.
        val e =
            assertFailsWith<CooldownKeyInvalidException> {
                agent("mr-{{MR}}").renderCooldownKey(mapOf("MR" to "s3cr3t-value"), extraSecretInputKeys = setOf("MR"))
            }

        assertEquals("reviewer", e.agentName)
        assertTrue(e.message!!.contains("{{MR}}"), e.message!!)
        assertTrue(!e.message!!.contains("s3cr3t-value"), e.message!!)
        // The same key over a non-secret input renders as usual.
        assertEquals("mr-1", agent("mr-{{MR}}").renderCooldownKey(mapOf("MR" to "1"), extraSecretInputKeys = setOf("OTHER")))
    }

    @Test
    fun `an empty rendered value is rejected`() {
        assertFailsWith<CooldownKeyInvalidException> { agent("{{MR}}").renderCooldownKey(mapOf("MR" to "")) }
        assertFailsWith<CooldownKeyInvalidException> { agent("{{MR}}").renderCooldownKey(mapOf("MR" to "   ")) }
        // The check is on the rendered value, not on each substitution: a template that
        // still spells out a subject keeps a distinct key even when its variable is empty.
        assertEquals("mr-", agent("mr-{{MR}}").renderCooldownKey(mapOf("MR" to "")))
    }

    @Test
    fun `a rendered value over the length cap is rejected and one at the cap is not`() {
        val atCap = "x".repeat(CooldownKeyRenderer.MAX_KEY_LENGTH)
        assertEquals(atCap, agent("{{MR}}").renderCooldownKey(mapOf("MR" to atCap)))

        val overCap = "x".repeat(CooldownKeyRenderer.MAX_KEY_LENGTH + 1)
        val e = assertFailsWith<CooldownKeyInvalidException> { agent("{{MR}}").renderCooldownKey(mapOf("MR" to overCap)) }

        assertTrue(e.message!!.contains("129 characters"), e.message!!)
        assertTrue(!e.message!!.contains(overCap), e.message!!)
    }

    @Test
    fun `a rendered value outside the allowed charset is rejected`() {
        for (value in listOf("mr 1", "mr%1", "mr'1", "mr*1", "mr\n1", "mr;1", "mr{{1}}", "mr\u0000", "mr+1")) {
            assertFailsWith<CooldownKeyInvalidException>("value '$value' must be refused") {
                agent("{{MR}}").renderCooldownKey(mapOf("MR" to value))
            }
        }
    }

    @Test
    fun `every character the charset allows renders`() {
        val allowed = "aZ0._:/-"

        assertEquals(allowed, agent("{{MR}}").renderCooldownKey(mapOf("MR" to allowed)))
    }

    @Test
    fun `a returned key is always a non-empty token of the allowed charset within the cap`() =
        runTest {
            val allowed = allowedKeyChars()
            val codepoints = Arb.element(allowed.map { Codepoint(it.code) })
            checkAll(300, Arb.string(0..(CooldownKeyRenderer.MAX_KEY_LENGTH + 8), codepoints)) { value ->
                val key = runCatching { agent("{{MR}}").renderCooldownKey(mapOf("MR" to value)) }.getOrNull()
                when {
                    value.isEmpty() -> {
                        assertEquals(null, key, "an empty value must be refused, not rendered")
                    }

                    value.length > CooldownKeyRenderer.MAX_KEY_LENGTH -> {
                        assertEquals(null, key, "an over-long value must be refused, not truncated")
                    }

                    else -> {
                        assertEquals(value, key)
                        val rendered = assertNotNull(key)
                        assertTrue(rendered.isNotEmpty() && rendered.length <= CooldownKeyRenderer.MAX_KEY_LENGTH, rendered)
                        assertTrue(rendered.all { it in allowed }, rendered)
                    }
                }
            }
        }

    @Test
    fun `any value carrying a character outside the charset is refused`() =
        runTest {
            val allowed = allowedKeyChars()
            checkAll(300, Arb.string(1..40)) { value ->
                if (value.any { it !in allowed }) {
                    assertFailsWith<CooldownKeyInvalidException>("value '$value' must be refused") {
                        agent("{{MR}}").renderCooldownKey(mapOf("MR" to value))
                    }
                }
            }
        }

    private fun allowedKeyChars(): Set<Char> = ('a'..'z').toSet() + ('A'..'Z') + ('0'..'9') + "._:/-".toSet()
}
