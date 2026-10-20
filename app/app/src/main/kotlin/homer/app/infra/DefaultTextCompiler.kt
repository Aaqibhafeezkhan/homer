package homer.app.infra

class DefaultTextCompiler : TextCompiler {
    private val templatePattern = Regex("""\{\{(\w+)\}\}""")

    override fun replaceArguments(
        template: String,
        arguments: Map<String, String>,
    ): String = templatePattern.replace(template) { match -> arguments[match.groupValues[1]] ?: match.value }
}
