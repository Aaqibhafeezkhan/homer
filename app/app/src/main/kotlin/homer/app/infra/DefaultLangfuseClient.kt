package homer.app.infra

import com.langfuse.client.core.LangfuseClientApiException
import com.langfuse.client.resources.prompts.requests.GetPromptRequest
import com.langfuse.client.resources.prompts.types.ChatMessage
import com.langfuse.client.resources.prompts.types.ChatMessageWithPlaceholders
import com.langfuse.client.resources.prompts.types.PlaceholderMessage
import homer.app.models.LangfuseConfig
import com.langfuse.client.LangfuseClient as LangfuseSdkClient

class DefaultLangfuseClient(
    config: LangfuseConfig,
    private val textCompiler: TextCompiler,
) : LangfuseClient {
    private val client: LangfuseSdkClient =
        LangfuseSdkClient
            .builder()
            .url(config.serverUrl)
            .credentials(config.publicKey, config.secretKey)
            .build()

    override suspend fun getPrompt(
        promptName: String,
        promptArguments: Map<String, String>,
        version: Int?,
        label: String?,
    ): String =
        try {
            val promptObj =
                if (version != null || label != null) {
                    val request =
                        GetPromptRequest
                            .builder()
                            .also { b -> version?.let { b.version(it) } }
                            .also { b -> label?.let { b.label(it) } }
                            .build()
                    client.prompts().get(promptName, request)
                } else {
                    client.prompts().get(promptName)
                }

            // The Prompt class is a discriminated union that can be ChatPrompt or TextPrompt
            val rawPrompt =
                when {
                    promptObj.isText -> {
                        // For text prompts, get the text content directly
                        promptObj.text.get().prompt
                    }

                    promptObj.isChat -> {
                        // For chat prompts, convert messages to string format
                        val chatPrompt = promptObj.chat.get()
                        chatPrompt.prompt.joinToString("\n") { it.visit(ChatLineRenderer) }
                    }

                    else -> {
                        error("Unknown prompt type")
                    }
                }

            // Replace all {{variable}} placeholders with values from promptArguments
            compilePrompt(rawPrompt, promptArguments)
        } catch (e: LangfuseClientApiException) {
            throw IllegalStateException("Failed to fetch prompt '$promptName': ${e.message}", e)
        }

    override fun compilePrompt(
        template: String,
        arguments: Map<String, String>,
    ): String = textCompiler.replaceArguments(template, arguments)
}

/** One chat message as a prompt line. */
private object ChatLineRenderer : ChatMessageWithPlaceholders.Visitor<String> {
    override fun visit(message: ChatMessage): String = "${message.role}: ${message.content}"

    // Render as a {{placeholder}} so compilePrompt can substitute it from promptArguments like any variable.
    override fun visit(message: PlaceholderMessage): String = "{{${message.name}}}"
}
