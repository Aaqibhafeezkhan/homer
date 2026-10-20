package homer.agentdebug.stubs

import homer.app.infra.DefaultTextCompiler
import homer.app.infra.LangfuseClient

class DebugLangfuseClient : LangfuseClient {
    private val textCompiler = DefaultTextCompiler()

    override fun compilePrompt(
        template: String,
        arguments: Map<String, String>,
    ): String = textCompiler.replaceArguments(template, arguments)

    override suspend fun getPrompt(
        promptName: String,
        promptArguments: Map<String, String>,
        version: Int?,
        label: String?,
    ): String {
        println("[WARN] Langfuse prompt '$promptName' not available in debug mode — using placeholder")
        return "<langfuse prompt: $promptName>"
    }
}
