package homer.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class DeprecatedSyntaxScan(
    val offenders: List<String>,
    val blocks: Int,
    val markedBlocks: Int,
)

private const val LEGACY_MARKER = "<!-- legacy-syntax-example -->"
private val LEGACY_TYPES = setOf("STRING", "NUMBER", "ANY")
private val legacyType = Regex("""\btype"?\s*:\s*"?(STRING|NUMBER|ANY)\b""")
private val queryParamsKey = Regex("""^(\s*)\{?\s*"?queryParams"?\s*:""")
private val yamlCommand = Regex("""^(\s*)-\s*!<(\w+)>""")
private val jsonCommand = Regex("""^(\s*)\{?\s*"type"\s*:\s*"(agent|cmd|script|claude|codex|langgraph|runnerCmd)"""")

/**
 * Scans the yaml/json fenced blocks of [markdown] for the deprecated param types and the
 * agent-level `queryParams` alias. Blocks preceded by the legacy marker line are counted
 * but not checked. JSON blocks that parse are checked structurally (key order does not
 * matter); unparseable JSON and YAML fall back to a line scan.
 */
internal fun scanDeprecatedSyntax(markdown: String): DeprecatedSyntaxScan {
    val lines = markdown.lines()
    val offenders = mutableListOf<String>()
    var blocks = 0
    var markedBlocks = 0
    var i = 0
    while (i < lines.size) {
        val lang = lines[i].trimEnd().removePrefix("```").takeIf { lines[i].startsWith("```") }
        if (lang != "yaml" && lang != "json") {
            i++
            continue
        }
        val marked = i > 0 && lines[i - 1].trim() == LEGACY_MARKER
        blocks++
        if (marked) markedBlocks++
        val fenceLine = i + 1
        val start = ++i
        while (i < lines.size && lines[i].trimEnd() != "```") i++
        val body = lines.subList(start, i)
        i++
        if (marked) continue
        val parsed = if (lang == "json") runCatching { Json.parseToJsonElement(body.joinToString("\n")) }.getOrNull() else null
        if (parsed != null) {
            walk(parsed, "$") { offenders += "AGENT.md:$fenceLine (block) at $it" }
        } else {
            scanLines(body, start + 1) { offenders += "AGENT.md:$fenceLine (block) line $it" }
        }
    }
    return DeprecatedSyntaxScan(offenders, blocks, markedBlocks)
}

internal fun deprecatedSyntaxOffenders(markdown: String): List<String> = scanDeprecatedSyntax(markdown).offenders

private fun walk(
    element: JsonElement,
    path: String,
    report: (String) -> Unit,
) {
    when (element) {
        is JsonObject -> {
            val type = (element["type"] as? JsonPrimitive)?.content
            if ("queryParams" in element && type != "agent") report("$path.queryParams: agent-level alias")
            if ("paramName" in element && type in LEGACY_TYPES) report("$path.type: legacy param type $type")
            element.forEach { (k, v) -> walk(v, "$path.$k", report) }
        }

        is JsonArray -> {
            element.forEachIndexed { n, v -> walk(v, "$path[$n]", report) }
        }

        else -> {}
    }
}

private fun scanLines(
    body: List<String>,
    firstLineNo: Int,
    report: (String) -> Unit,
) {
    var inAgentCommand = false
    var commandIndent = 0
    body.forEachIndexed { n, line ->
        val yaml = yamlCommand.find(line)
        val json = jsonCommand.find(line)
        val qp = queryParamsKey.find(line)
        val runAgentField =
            qp != null && (
                (inAgentCommand && qp.groupValues[1].length > commandIndent) ||
                    json?.groupValues?.get(2) == "agent"
            )
        val flagged = (qp != null && !runAgentField) || legacyType.containsMatchIn(line)
        if (flagged) report("${firstLineNo + n}: ${line.trim()}")
        if (yaml != null) {
            inAgentCommand = yaml.groupValues[2] == "agent"
            commandIndent = yaml.groupValues[1].length
        } else if (json != null) {
            inAgentCommand = json.groupValues[2] == "agent"
            commandIndent = json.groupValues[1].length - 1
        }
    }
}
