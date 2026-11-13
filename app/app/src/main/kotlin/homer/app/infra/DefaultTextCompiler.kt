package homer.app.infra

import homer.app.models.templatePlaceholderPattern

/**
 * The distinct `{{NAME}}` names [template] refers to. Rendering leaves an unknown name as its
 * literal text, so a caller that must resolve every name — a lock key, whose literal would be
 * shared by every run — checks these against its env instead of inspecting the rendered output.
 */
internal fun placeholderNames(template: String): Set<String> =
    templatePlaceholderPattern.findAll(template).mapTo(linkedSetOf()) { it.groupValues[1] }

class DefaultTextCompiler : TextCompiler {
    override fun replaceArguments(
        template: String,
        arguments: Map<String, String>,
    ): String = templatePlaceholderPattern.replace(template) { match -> arguments[match.groupValues[1]] ?: match.value }
}
