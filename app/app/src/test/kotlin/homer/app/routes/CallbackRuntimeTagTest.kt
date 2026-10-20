package homer.app.routes

import homer.app.exceptions.QueryParamValidationException
import homer.app.fixtures.AgentFixtures
import homer.app.models.TagValidation
import homer.app.models.Tags
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** [runtimeTag], the `key:value` parse behind `POST /api/v1/callback/tags` (ADR-0060). */
class CallbackRuntimeTagTest {
    private val agent =
        AgentFixtures.simpleAgent(name = "agent-a").copy(
            tags = Tags(fromInputs = listOf("mr"), runtime = listOf("decision")),
        )

    @Test
    fun `a declared runtime key with a valid value splits at the first colon`() {
        assertEquals("decision" to "approve", runtimeTag("decision:approve", agent))
        assertEquals("decision" to "a.b-c_1", runtimeTag("decision:a.b-c_1", agent))
    }

    @Test
    fun `a tag without a key or a value is malformed`() {
        for (tag in listOf("decision", ":approve", "decision:", "")) {
            val e = assertFailsWith<QueryParamValidationException>(tag) { runtimeTag(tag, agent) }
            assertEquals("Tag must be 'key:value'", e.message, tag)
        }
    }

    @Test
    fun `a fromInputs key or an undeclared key is refused with its reason`() {
        assertEquals(
            "key 'mr' is a tags.fromInputs key; runtime tags cannot override it",
            assertFailsWith<QueryParamValidationException> { runtimeTag("mr:1", agent) }.message,
        )
        assertEquals(
            "key 'other' is not a declared tags.runtime key",
            assertFailsWith<QueryParamValidationException> { runtimeTag("other:1", agent) }.message,
        )
    }

    @Test
    fun `a value outside the tag pattern is refused`() {
        val e = assertFailsWith<QueryParamValidationException> { runtimeTag("decision:has space", agent) }
        assertEquals("Tag value must match ^[A-Za-z0-9._-]{1,${TagValidation.MAX_TAG_VALUE_LENGTH}}\$", e.message)
    }

    @Test
    fun `any accepted tag is the declared key and a pattern-matching value`() =
        runTest {
            checkAll(Arb.string(0..12)) { value ->
                val tag = "decision:$value"
                if (TagValidation.VALUE_PATTERN.matches(value)) {
                    assertEquals("decision" to value, runtimeTag(tag, agent), tag)
                } else {
                    assertFailsWith<QueryParamValidationException>(tag) { runtimeTag(tag, agent) }
                }
            }
        }
}
