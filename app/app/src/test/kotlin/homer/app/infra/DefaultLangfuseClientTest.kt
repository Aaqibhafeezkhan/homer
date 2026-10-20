package homer.app.infra

import com.sun.net.httpserver.HttpServer
import homer.app.models.LangfuseConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for DefaultLangfuseClient: template compilation, and prompt fetching against a
 * local stand-in for the Langfuse public API.
 */
class DefaultLangfuseClientTest {
    private lateinit var langfuseClient: DefaultLangfuseClient

    @BeforeEach
    fun setup() {
        // Use dummy config - we're only testing compilePrompt which doesn't need real API
        val config =
            LangfuseConfig(
                serverUrl = "https://dummy.langfuse.com",
                publicKey = "pk-test",
                secretKey = "sk-test",
            )
        val textCompiler = DefaultTextCompiler()
        langfuseClient = DefaultLangfuseClient(config, textCompiler)
    }

    // ========== Template Compilation Tests ==========

    @Test
    fun `compilePrompt should replace single variable`() {
        val template = "Hello, {{name}}!"
        val arguments = mapOf("name" to "World")

        val result = langfuseClient.compilePrompt(template, arguments)

        assertEquals("Hello, World!", result)
    }

    @Test
    fun `compilePrompt should replace multiple variables`() {
        val template = "{{greeting}}, {{name}}! Today is {{day}}."
        val arguments =
            mapOf(
                "greeting" to "Hello",
                "name" to "Alice",
                "day" to "Monday",
            )

        val result = langfuseClient.compilePrompt(template, arguments)

        assertEquals("Hello, Alice! Today is Monday.", result)
    }

    @Test
    fun `compilePrompt should leave template unchanged when no arguments`() {
        val template = "Hello, {{name}}!"
        val arguments = emptyMap<String, String>()

        val result = langfuseClient.compilePrompt(template, arguments)

        assertEquals("Hello, {{name}}!", result)
    }

    @Test
    fun `compilePrompt should handle template without variables`() {
        val template = "Hello, World!"
        val arguments = mapOf("name" to "Alice")

        val result = langfuseClient.compilePrompt(template, arguments)

        assertEquals("Hello, World!", result)
    }

    @Test
    fun `compilePrompt should replace same variable multiple times`() {
        val template = "{{name}} is great. I love {{name}}!"
        val arguments = mapOf("name" to "Kotlin")

        val result = langfuseClient.compilePrompt(template, arguments)

        assertEquals("Kotlin is great. I love Kotlin!", result)
    }

    @Test
    fun `compilePrompt should handle empty template`() {
        val template = ""
        val arguments = mapOf("name" to "World")

        val result = langfuseClient.compilePrompt(template, arguments)

        assertEquals("", result)
    }

    @Test
    fun `compilePrompt should handle multiline template`() {
        val template =
            """
            Line 1: {{var1}}
            Line 2: {{var2}}
            Line 3: {{var3}}
            """.trimIndent()
        val arguments =
            mapOf(
                "var1" to "first",
                "var2" to "second",
                "var3" to "third",
            )

        val result = langfuseClient.compilePrompt(template, arguments)

        assertTrue(result.contains("Line 1: first"))
        assertTrue(result.contains("Line 2: second"))
        assertTrue(result.contains("Line 3: third"))
    }

    @Test
    fun `compilePrompt should handle variable with special characters in value`() {
        val template = "Message: {{content}}"
        val arguments = mapOf("content" to "Hello! @#\$%^&*() 'quotes' \"double\"")

        val result = langfuseClient.compilePrompt(template, arguments)

        assertEquals("Message: Hello! @#\$%^&*() 'quotes' \"double\"", result)
    }

    @Test
    fun `compilePrompt should handle variable names with underscores`() {
        val template = "User: {{user_name}}, ID: {{user_id}}"
        val arguments =
            mapOf(
                "user_name" to "Alice",
                "user_id" to "12345",
            )

        val result = langfuseClient.compilePrompt(template, arguments)

        assertEquals("User: Alice, ID: 12345", result)
    }

    @Test
    fun `compilePrompt should handle variable names with numbers`() {
        val template = "{{var1}} and {{var2}}"
        val arguments =
            mapOf(
                "var1" to "first",
                "var2" to "second",
            )

        val result = langfuseClient.compilePrompt(template, arguments)

        assertEquals("first and second", result)
    }

    @Test
    fun `compilePrompt should not replace partial variable syntax`() {
        val template = "This is {name} not {{name}}"
        val arguments = mapOf("name" to "replaced")

        val result = langfuseClient.compilePrompt(template, arguments)

        assertEquals("This is {name} not replaced", result)
    }

    @Test
    fun `compilePrompt should handle JSON in template`() {
        val template = """{"user": "{{username}}", "message": "{{message}}"}"""
        val arguments =
            mapOf(
                "username" to "alice",
                "message" to "hello",
            )

        val result = langfuseClient.compilePrompt(template, arguments)

        assertEquals("""{"user": "alice", "message": "hello"}""", result)
    }

    @Test
    fun `compilePrompt should handle code snippets in template`() {
        val template =
            """
            function {{functionName}}() {
                console.log("{{message}}");
            }
            """.trimIndent()
        val arguments =
            mapOf(
                "functionName" to "greet",
                "message" to "Hello World",
            )

        val result = langfuseClient.compilePrompt(template, arguments)

        assertTrue(result.contains("function greet()"))
        assertTrue(result.contains("console.log(\"Hello World\")"))
    }

    @Test
    fun `compilePrompt should ignore extra arguments not in template`() {
        val template = "Hello, {{name}}!"
        val arguments =
            mapOf(
                "name" to "World",
                "unused1" to "ignored",
                "unused2" to "also ignored",
            )

        val result = langfuseClient.compilePrompt(template, arguments)

        assertEquals("Hello, World!", result)
    }

    @Test
    fun `compilePrompt should handle empty value`() {
        val template = "Value: {{value}}"
        val arguments = mapOf("value" to "")

        val result = langfuseClient.compilePrompt(template, arguments)

        assertEquals("Value: ", result)
    }

    @Test
    fun `compilePrompt should handle newlines in value`() {
        val template = "Content: {{content}}"
        val arguments = mapOf("content" to "line1\nline2\nline3")

        val result = langfuseClient.compilePrompt(template, arguments)

        assertEquals("Content: line1\nline2\nline3", result)
    }

    @Test
    fun `compilePrompt should handle prompt with markdown`() {
        val template =
            """
            # {{title}}

            **Bold text**: {{bold_content}}

            - Item 1: {{item1}}
            - Item 2: {{item2}}
            """.trimIndent()
        val arguments =
            mapOf(
                "title" to "My Document",
                "bold_content" to "Important",
                "item1" to "First",
                "item2" to "Second",
            )

        val result = langfuseClient.compilePrompt(template, arguments)

        assertTrue(result.contains("# My Document"))
        assertTrue(result.contains("**Bold text**: Important"))
        assertTrue(result.contains("- Item 1: First"))
        assertTrue(result.contains("- Item 2: Second"))
    }

    // ========== Prompt fetching (local stand-in for the Langfuse API) ==========

    /** Serves [body] with [status] for every request, recording each request's path and query. */
    private fun <T> withLangfuseStub(
        body: String,
        status: Int = 200,
        block: suspend (client: DefaultLangfuseClient, requests: List<String>) -> T,
    ): T {
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.toString()
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val config =
                LangfuseConfig(
                    serverUrl = "http://127.0.0.1:${server.address.port}",
                    publicKey = "pk-test",
                    secretKey = "sk-test",
                )
            return runBlocking { block(DefaultLangfuseClient(config, DefaultTextCompiler()), requests) }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `getPrompt fetches the latest text prompt and compiles its variables`() =
        withLangfuseStub(
            """{"type":"text","name":"greet","version":1,"config":{},"labels":[],"tags":[],"prompt":"Hello, {{name}}!"}""",
        ) { client, requests ->
            assertEquals("Hello, World!", client.getPrompt("greet", mapOf("name" to "World"), null, null))
            assertEquals(listOf("/api/public/v2/prompts/greet"), requests)
        }

    @Test
    fun `getPrompt passes version and label and renders a chat prompt with placeholders`() =
        withLangfuseStub(
            """
            {"type":"chat","name":"chat","version":3,"config":{},"labels":["prod"],"tags":[],
             "prompt":[{"role":"system","content":"You are {{who}}"},{"type":"placeholder","name":"history"}]}
            """.trimIndent(),
        ) { client, requests ->
            val rendered = client.getPrompt("chat", mapOf("who" to "Homer", "history" to "user: hi"), 3, "prod")
            assertEquals("system: You are Homer\nuser: hi", rendered)
            val request = requests.single()
            assertTrue(request.startsWith("/api/public/v2/prompts/chat?"), request)
            assertTrue("version=3" in request && "label=prod" in request, request)
        }

    @Test
    fun `getPrompt with only a label sends no version`() =
        withLangfuseStub(
            """{"type":"text","name":"p","version":2,"config":{},"labels":["staging"],"tags":[],"prompt":"x"}""",
        ) { client, requests ->
            assertEquals("x", client.getPrompt("p", emptyMap(), null, "staging"))
            assertEquals("/api/public/v2/prompts/p?label=staging", requests.single())
        }

    @Test
    fun `getPrompt refuses a prompt type it cannot render`() {
        val error =
            assertFailsWith<IllegalStateException> {
                withLangfuseStub(
                    """{"type":"audio","name":"p","version":1,"config":{},"labels":[],"tags":[],"prompt":"x"}""",
                ) { client, _ ->
                    client.getPrompt("p", emptyMap(), null, null)
                }
            }
        assertEquals("Unknown prompt type", error.message)
    }

    @Test
    fun `getPrompt wraps an API error with the prompt name`() {
        val error =
            assertFailsWith<RuntimeException> {
                withLangfuseStub("""{"message":"not found"}""", status = 404) { client, _ ->
                    client.getPrompt("missing", emptyMap(), null, null)
                }
            }
        assertTrue(error.message!!.startsWith("Failed to fetch prompt 'missing': "), error.message)
    }
}
