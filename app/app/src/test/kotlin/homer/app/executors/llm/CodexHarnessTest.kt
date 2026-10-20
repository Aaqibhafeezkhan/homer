package homer.app.executors.llm

import homer.app.infra.LlmOutputJson
import homer.app.infra.scanLlmAccounting
import homer.app.models.CodexCmdOptions
import homer.app.models.Command
import homer.app.models.LlmEscalation
import homer.app.models.LlmEvent
import homer.app.models.LlmPrompt
import homer.app.models.LlmUsage
import homer.app.models.Translation
import homer.app.models.TurnStatus
import homer.app.models.When
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodexHarnessTest {
    private val harness = CodexHarness()
    private val command = Command.Codex(prompt = LlmPrompt.Prompt("hi"))

    private fun line(json: String) = LlmOutputJson.parseToJsonElement(json).jsonObject

    private fun fixture(name: String): List<LlmEvent> =
        requireNotNull(javaClass.getResourceAsStream("/llm/codex/$name"))
            .use { it.readBytes().decodeToString() }
            .lineSequence()
            .filter { it.isNotBlank() }
            .flatMap { (harness.translate(line(it)) as? Translation.Events)?.events ?: emptyList() }
            .toList()

    @Test
    fun `escalated changes the model and replaces raw options only when the tier sets them`() {
        val base =
            Command.Codex(
                prompt = LlmPrompt.Prompt("hi"),
                options = CodexCmdOptions(model = "cheap", sandbox = "read-only", options = listOf("--x")),
            )
        val always = When(When.Operator.ALL, listOf("true"))

        val kept = harness.argv(harness.escalated(base, LlmEscalation(model = "strong", whenClause = always)), emptyMap())
        assertEquals("strong", kept[kept.indexOf("--model") + 1])
        assertTrue(kept.contains("read-only"))
        assertTrue(kept.contains("--x"))

        val replaced =
            harness.argv(
                harness.escalated(base, LlmEscalation(model = "strong", options = emptyList(), whenClause = always)),
                emptyMap(),
            )
        assertFalse(replaced.contains("--x"))
    }

    @Test
    fun `item ids are not model calls, so the spend lookup gets no call count`() {
        // One Codex response can carry a message and a tool call item; counting items would
        // over-state the calls and wait out the whole re-poll schedule (ADR-0070).
        assertEquals(false, harness.countsModelCalls)
        val text =
            requireNotNull(javaClass.getResourceAsStream("/llm/codex/success.jsonl"))
                .use { it.readBytes().decodeToString() }
        val scan = scanLlmAccounting(text.lineSequence(), harness::translate)
        assertEquals("0199a213-81c0-7800-8aa1-bbab2a035a53", scan.sessionId)
    }

    // ========== argv ==========

    private fun argv(
        options: CodexCmdOptions,
        env: Map<String, String> = emptyMap(),
    ): List<String> = harness.argv(Command.Codex(prompt = LlmPrompt.Prompt("hi"), options = options), env)

    private fun List<String>.argFor(flag: String): String {
        val i = indexOf(flag)
        assertTrue(i >= 0, "flag $flag not present; argv=$this")
        assertTrue(i + 1 < size, "flag $flag has no value; argv=$this")
        return this[i + 1]
    }

    @Test
    fun `minimal options produce only the hardcoded exec tail`() {
        assertEquals(listOf("codex", "exec", "--json", "--color", "never", "-"), argv(CodexCmdOptions()))
    }

    @Test
    fun `model option maps to --model`() {
        assertEquals("gpt-5.2-codex", argv(CodexCmdOptions(model = "gpt-5.2-codex")).argFor("--model"))
    }

    @Test
    fun `profile option maps to --profile`() {
        assertEquals("ci", argv(CodexCmdOptions(profile = "ci")).argFor("--profile"))
    }

    @Test
    fun `sandbox option maps to --sandbox`() {
        assertEquals("workspace-write", argv(CodexCmdOptions(sandbox = "workspace-write")).argFor("--sandbox"))
    }

    @Test
    fun `dangerouslyBypassApprovalsAndSandbox emits the flag when sandbox is unset`() {
        assertTrue(
            argv(CodexCmdOptions(dangerouslyBypassApprovalsAndSandbox = true)).contains("--dangerously-bypass-approvals-and-sandbox"),
        )
    }

    @Test
    fun `sandbox takes precedence over dangerouslyBypassApprovalsAndSandbox`() {
        val a = argv(CodexCmdOptions(sandbox = "read-only", dangerouslyBypassApprovalsAndSandbox = true))
        assertEquals("read-only", a.argFor("--sandbox"))
        assertFalse(a.contains("--dangerously-bypass-approvals-and-sandbox"))
    }

    @Test
    fun `path and boolean options map to their flags`() {
        val a =
            argv(
                CodexCmdOptions(
                    cd = "repo",
                    addDir = "/extra",
                    skipGitRepoCheck = true,
                    ephemeral = true,
                    ignoreUserConfig = true,
                    ignoreRules = true,
                    outputSchema = "/tmp/schema.json",
                    outputLastMessage = "/tmp/last.txt",
                ),
            )
        assertEquals("repo", a.argFor("--cd"))
        assertEquals("/extra", a.argFor("--add-dir"))
        assertTrue(a.contains("--skip-git-repo-check"))
        assertTrue(a.contains("--ephemeral"))
        assertTrue(a.contains("--ignore-user-config"))
        assertTrue(a.contains("--ignore-rules"))
        assertEquals("/tmp/schema.json", a.argFor("--output-schema"))
        assertEquals("/tmp/last.txt", a.argFor("--output-last-message"))
    }

    @Test
    fun `config entries emit repeated --config flags`() {
        val a = argv(CodexCmdOptions(config = listOf("model_reasoning_effort=\"high\"", "hide_agent_reasoning=true")))
        val first = a.indexOf("--config")
        assertEquals("model_reasoning_effort=\"high\"", a[first + 1])
        assertEquals("--config", a[first + 2])
        assertEquals("hide_agent_reasoning=true", a[first + 3])
    }

    @Test
    fun `raw options are passed through before the hardcoded tail`() {
        val a = argv(CodexCmdOptions(options = listOf("--oss")))
        assertTrue(a.contains("--oss"))
        assertTrue(a.indexOf("--oss") < a.indexOf("--json"))
    }

    @Test
    fun `stdin sentinel is the last argument`() {
        assertEquals("-", argv(CodexCmdOptions(model = "gpt-5.2-codex")).last())
    }

    @Test
    fun `option values resolve VAR placeholders against the workspace env`() {
        val a = argv(CodexCmdOptions(cd = "{{HMR_WORK_DIR}}/repo"), env = mapOf("HMR_WORK_DIR" to "/srv/work"))
        assertEquals("/srv/work/repo", a.argFor("--cd"))
    }

    @Test
    fun `reports cost is false`() {
        assertEquals(false, harness.reportsCost)
    }

    @Test
    fun `success fixture yields session start, tool pairs, message and a successful turn end with all five usage fields`() {
        val es = fixture("success.jsonl")
        assertEquals("0199a213-81c0-7800-8aa1-bbab2a035a53", es.filterIsInstance<LlmEvent.SessionStart>().single().sessionId)
        val calls = es.filterIsInstance<LlmEvent.ToolCall>()
        assertEquals(listOf("command_execution", "mcp_tool_call", "file_change"), calls.map { it.name })
        assertEquals(calls.map { it.id }, es.filterIsInstance<LlmEvent.ToolResult>().map { it.id })
        assertEquals("All done.", es.filterIsInstance<LlmEvent.AssistantMessage>().single().text)
        val end = es.filterIsInstance<LlmEvent.TurnEnd>().single()
        assertEquals(TurnStatus.Success, end.status)
        assertEquals(24763L, end.usage.input)
        assertEquals(24448L, end.usage.cacheRead)
        assertEquals(10L, end.usage.cacheWrite)
        assertEquals(122L, end.usage.output)
        assertEquals(40L, end.usage.reasoning)
        assertEquals(null, end.costUsd)
    }

    @Test
    fun `reasoning items item_started and turn_started are ignored`() {
        assertEquals(Translation.Ignored, harness.translate(line("""{"type":"turn.started"}""")))
        assertEquals(
            Translation.Ignored,
            harness.translate(line("""{"type":"item.started","item":{"id":"i","type":"command_execution"}}""")),
        )
        assertEquals(
            Translation.Ignored,
            harness.translate(line("""{"type":"item.completed","item":{"id":"i","type":"reasoning","text":"x"}}""")),
        )
        assertEquals(
            Translation.Ignored,
            harness.translate(line("""{"type":"item.completed","item":{"id":"i","type":"web_search","query":"x"}}""")),
        )
    }

    @Test
    fun `an item_completed without an item object is Malformed`() {
        assertIs<Translation.Malformed>(harness.translate(line("""{"type":"item.completed","item":"nope"}""")))
    }

    @Test
    fun `a command_execution with a non-zero exit is an error result`() {
        val es =
            (
                harness.translate(
                    line(
                        """{"type":"item.completed","item":{"id":"i","type":"command_execution","command":"false","aggregated_output":"","exit_code":1,"status":"failed"}}""",
                    ),
                ) as Translation.Events
            ).events
        assertTrue((es[1] as LlmEvent.ToolResult).isError)
    }

    private fun itemEvents(item: String): List<LlmEvent> =
        assertIs<Translation.Events>(harness.translate(line("""{"type":"item.completed","item":$item}"""))).events

    private fun itemResult(item: String): LlmEvent.ToolResult = itemEvents(item)[1] as LlmEvent.ToolResult

    @Test
    fun `an item without an id or a type is Malformed`() {
        val withoutId = harness.translate(line("""{"type":"item.completed","item":{"type":"agent_message"}}"""))
        val withoutType = harness.translate(line("""{"type":"item.completed","item":{"id":"i"}}"""))
        assertEquals(Translation.Malformed("item without id"), withoutId)
        assertEquals(Translation.Malformed("item without type"), withoutType)
    }

    @Test
    fun `an agent_message without text is an empty assistant message`() {
        val events = itemEvents("""{"id":"m","type":"agent_message"}""")
        assertEquals(listOf(LlmEvent.AssistantMessage(messageId = "m", model = null, text = "")), events)
    }

    @Test
    fun `a command_execution without an exit code or output is a successful empty result`() {
        val result = itemResult("""{"id":"c","type":"command_execution","command":"true"}""")
        assertEquals(JsonPrimitive(""), result.output)
        assertFalse(result.isError)
        assertFalse(itemResult("""{"id":"c","type":"command_execution","aggregated_output":"ok","exit_code":0}""").isError)
    }

    @Test
    fun `an mcp_tool_call reports its result, or its error as an error result`() {
        val ok = itemResult("""{"id":"t","type":"mcp_tool_call","tool":"x","result":{"content":"done"},"status":"completed"}""")
        assertEquals(JsonObject(mapOf("content" to JsonPrimitive("done"))), ok.output)
        assertFalse(ok.isError)

        val failed = itemResult("""{"id":"t","type":"mcp_tool_call","tool":"x","error":{"message":"boom"},"status":"failed"}""")
        assertEquals(JsonObject(mapOf("message" to JsonPrimitive("boom"))), failed.output)
        assertTrue(failed.isError)

        assertEquals(JsonPrimitive(""), itemResult("""{"id":"t","type":"mcp_tool_call","server":"s","tool":"x"}""").output)
    }

    @Test
    fun `a file_change reports its status, empty when absent`() {
        val withStatus = itemResult("""{"id":"f","type":"file_change","changes":[],"status":"completed"}""")
        assertEquals(JsonPrimitive("completed"), withStatus.output)
        assertEquals(JsonPrimitive(""), itemResult("""{"id":"f","type":"file_change","changes":[]}""").output)
    }

    @Test
    fun `turn_failed becomes a Failed turn end and inspect rejects it`() {
        val es = fixture("turn-failed.jsonl")
        assertEquals(TurnStatus.Failed("stream disconnected before completion"), es.filterIsInstance<LlmEvent.TurnEnd>().single().status)
        assertIs<Inspection.Malformed>(harness.inspect(es, emptyList(), command))
    }

    @Test
    fun `turn and error events without their optional fields translate to zero usage and a placeholder message`() {
        assertEquals(
            Translation.Events(listOf(LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage()))),
            harness.translate(line("""{"type":"turn.completed"}""")),
        )
        assertEquals(
            Translation.Events(listOf(LlmEvent.TurnEnd(TurnStatus.Failed("no message")))),
            harness.translate(line("""{"type":"turn.failed","error":{}}""")),
        )
        assertEquals(
            Translation.Events(listOf(LlmEvent.TransientError("no message"))),
            harness.translate(line("""{"type":"error"}""")),
        )
    }

    @Test
    fun `transient error events do not fail inspection`() {
        val es = fixture("transient-error.jsonl")
        assertEquals(1, es.count { it is LlmEvent.TransientError })
        assertEquals(Inspection.Success, harness.inspect(es, emptyList(), command))
    }

    @Test
    fun `inspect rejects a stream without turn_completed`() {
        assertIs<Inspection.Malformed>(harness.inspect(listOf(LlmEvent.SessionStart("s", null)), emptyList(), command))
    }

    @Test
    fun `a duplicated turn_completed still inspects as one success`() {
        val es = fixture("success.jsonl") + fixture("success.jsonl").filterIsInstance<LlmEvent.TurnEnd>()
        assertEquals(Inspection.Success, harness.inspect(es, emptyList(), command))
    }

    @Test
    fun `argv keeps the exec tail and resolves placeholders`() {
        val cmd =
            Command.Codex(
                prompt = LlmPrompt.Prompt("hi"),
                options = CodexCmdOptions(model = "{{M}}", sandbox = "read-only", dangerouslyBypassApprovalsAndSandbox = true),
            )
        val argv = harness.argv(cmd, mapOf("M" to "gpt-5.2-codex"))
        assertEquals(listOf("codex", "exec"), argv.take(2))
        assertEquals("gpt-5.2-codex", argv[argv.indexOf("--model") + 1])
        assertTrue("--sandbox" in argv && "--dangerously-bypass-approvals-and-sandbox" !in argv)
        assertEquals(listOf("--json", "--color", "never", "-"), argv.takeLast(4))
    }
}
