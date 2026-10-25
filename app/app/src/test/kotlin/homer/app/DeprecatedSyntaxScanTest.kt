package homer.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeprecatedSyntaxScanTest {
    private fun yaml(vararg body: String) = "```yaml\n${body.joinToString("\n")}\n```"

    private fun json(vararg body: String) = "```json\n${body.joinToString("\n")}\n```"

    private fun flagged(md: String) = deprecatedSyntaxOffenders(md).isNotEmpty()

    @Test
    fun `flags YAML legacy type`() = assertTrue(flagged(yaml("inputs:", "  - paramName: a", "    type: NUMBER")))

    @Test
    fun `flags compact JSON legacy type`() = assertTrue(flagged(json("""{ "inputs": [ { "paramName": "a", "type": "STRING" } ] }""")))

    @Test
    fun `flags YAML flow-style legacy type`() = assertTrue(flagged(yaml("inputs:", "  - {paramName: x, type: ANY}")))

    @Test
    fun `flags agent-level queryParams in YAML`() = assertTrue(flagged(yaml("name: a", "queryParams:", "  - paramName: a")))

    @Test
    fun `flags agent-level queryParams in JSON`() = assertTrue(flagged(json("{", "  \"name\": \"a\",", "  \"queryParams\": []", "}")))

    @Test
    fun `flags queryParams under a cmd command`() =
        assertTrue(flagged(yaml("commands:", "  - !<cmd>", "    cmd: [x]", "    queryParams: []")))

    @Test
    fun `spares queryParams under an agent command in YAML`() =
        assertEquals(
            emptyList(),
            deprecatedSyntaxOffenders(yaml("commands:", "  - !<agent>", "    agentName: x", "    queryParams:", "      - envName: A")),
        )

    @Test
    fun `spares queryParams in a JSON agent command line scan`() =
        assertEquals(emptyList(), deprecatedSyntaxOffenders(json("""{ "type": "agent", "queryParams": [ ] }, oops""")))

    @Test
    fun `flags a legacy type on a RunAgent queryParams line`() =
        assertTrue(flagged(yaml("commands:", "  - !<agent>", "    agentName: x", "    queryParams: [{paramName: a, type: ANY}]")))

    @Test
    fun `spares marked blocks`() =
        assertEquals(emptyList(), deprecatedSyntaxOffenders("<!-- legacy-syntax-example -->\n" + yaml("queryParams:", "  - type: NUMBER")))

    @Test
    fun `spares look-alike words`() = assertEquals(emptyList(), deprecatedSyntaxOffenders(yaml("type: STRINGS", "type: ANYTHING")))

    @Test
    fun `JSON agent command with queryParams before type is spared`() =
        assertEquals(
            emptyList(),
            deprecatedSyntaxOffenders(
                json("""{ "commands": [ { "queryParams": [ { "envName": "A", "paramName": "p" } ], "type": "agent" } ] }"""),
            ),
        )

    @Test
    fun `JSON cmd command with queryParams before type is flagged`() =
        assertTrue(flagged(json("""{ "commands": [ { "queryParams": [], "type": "cmd" } ] }""")))

    @Test
    fun `JSON legacy param type with type before paramName is flagged`() =
        assertTrue(flagged(json("""{ "inputs": [ { "type": "NUMBER", "paramName": "a" } ] }""")))

    @Test
    fun `reports block start line`() {
        val out = deprecatedSyntaxOffenders("text\n\n" + yaml("type: ANY"))
        assertTrue(out.single().startsWith("AGENT.md:3 "), out.toString())
    }
}
