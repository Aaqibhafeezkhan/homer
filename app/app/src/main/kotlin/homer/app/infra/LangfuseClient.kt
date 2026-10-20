package homer.app.infra

/**
 * Interface for Langfuse client providing prompt management capabilities
 */
interface LangfuseClient {
    /**
     * Fetch a prompt from Langfuse by name
     * @param promptName Name of the prompt in Langfuse
     * @param promptArguments Variables to substitute in the prompt template
     * @return Compiled prompt text
     */
    suspend fun getPrompt(
        promptName: String,
        promptArguments: Map<String, String> = emptyMap(),
        version: Int? = null,
        label: String? = null,
    ): String

    /**
     * Compile a prompt template by substituting variables
     * @param template Prompt template with {{variable}} placeholders
     * @param arguments Variables to substitute
     * @return Compiled prompt text
     */
    fun compilePrompt(
        template: String,
        arguments: Map<String, String>,
    ): String
}
