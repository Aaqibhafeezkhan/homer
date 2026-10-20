package homer.app.infra

interface TextCompiler {
    fun replaceArguments(
        template: String,
        arguments: Map<String, String>,
    ): String
}
