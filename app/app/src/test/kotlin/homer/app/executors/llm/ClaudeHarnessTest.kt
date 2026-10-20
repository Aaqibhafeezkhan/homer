package homer.app.executors.llm

import homer.app.exceptions.ClaudeCliVersionMismatchException
import homer.app.exceptions.ValidationException
import homer.app.infra.LlmOutputJson
import homer.app.models.ClaudeCmdOptions
import homer.app.models.ClaudeSubagent
import homer.app.models.Command
import homer.app.models.LlmEscalation
import homer.app.models.LlmEvent
import homer.app.models.LlmPrompt
import homer.app.models.Translation
import homer.app.models.TurnStatus
import homer.app.models.When
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeHarnessTest {
    private val harness = ClaudeHarness()
    private val command = Command.Claude(prompt = LlmPrompt.Prompt("hi"), options = ClaudeCmdOptions())

    private fun line(json: String) = LlmOutputJson.parseToJsonElement(json).jsonObject

    private fun events(json: String): List<LlmEvent> = (harness.translate(line(json)) as Translation.Events).events

    // ========== argv ==========

    private fun argv(
        options: ClaudeCmdOptions,
        env: Map<String, String> = emptyMap(),
    ): List<String> = harness.argv(Command.Claude(prompt = LlmPrompt.Prompt("hi"), options = options), env)

    private fun List<String>.indexOfFlag(flag: String): Int = indexOf(flag)

    private fun List<String>.argFor(flag: String): String {
        val i = indexOf(flag)
        assertTrue(i >= 0, "flag $flag not present; argv=$this")
        assertTrue(i + 1 < size, "flag $flag has no value; argv=$this")
        return this[i + 1]
    }

    /** The value following each occurrence of a repeatable `flag`, in order. */
    private fun List<String>.valuesFor(flag: String): List<String> =
        mapIndexedNotNull { i, token -> if (token == flag && i + 1 < size) this[i + 1] else null }

    @Test
    fun `escalated changes the model and replaces raw options only when the tier sets them`() {
        val base =
            Command.Claude(
                prompt = LlmPrompt.Prompt("hi"),
                options = ClaudeCmdOptions(model = "cheap", maxTurns = 7, options = listOf("--continue")),
            )
        val always = When(When.Operator.ALL, listOf("true"))

        val kept = harness.argv(harness.escalated(base, LlmEscalation(model = "strong", whenClause = always)), emptyMap())
        assertEquals("strong", kept.argFor("--model"))
        assertEquals("7", kept.argFor("--max-turns"))
        assertTrue(kept.contains("--continue"))

        val fresh =
            harness.argv(
                harness.escalated(base, LlmEscalation(model = "strong", options = emptyList(), whenClause = always)),
                emptyMap(),
            )
        assertFalse(fresh.contains("--continue"))
    }

    @Test
    fun `minimal options produce only required flags plus hardcoded tail`() {
        val a = argv(ClaudeCmdOptions())
        assertEquals("claude", a.first())
        // Defaults: dangerouslySkipPermissions=false, maxTurns=40
        assertFalse(a.contains("--dangerously-skip-permissions"))
        assertEquals("40", a.argFor("--max-turns"))
        assertEquals("stream-json", a.argFor("--output-format"))
        assertTrue(a.contains("--print"))
        assertTrue(a.contains("--verbose"))
        // Nothing else should be present.
        assertFalse(a.contains("--agent"))
        assertFalse(a.contains("--model"))
        assertFalse(a.contains("--fallback-model"))
        assertFalse(a.contains("--system-prompt"))
        assertFalse(a.contains("--agents"))
        assertFalse(a.contains("--permission-mode"))
    }

    @Test
    fun `explicit dangerouslySkipPermissions=true emits the flag`() {
        assertTrue(argv(ClaudeCmdOptions(dangerouslySkipPermissions = true)).contains("--dangerously-skip-permissions"))
    }

    @Test
    fun `agent option maps to --agent`() {
        assertEquals("coder", argv(ClaudeCmdOptions(agent = "coder")).argFor("--agent"))
    }

    @Test
    fun `model option maps to --model`() {
        assertEquals("sonnet", argv(ClaudeCmdOptions(model = "sonnet")).argFor("--model"))
    }

    @Test
    fun `fallbackModel maps to --fallback-model`() {
        assertEquals("haiku", argv(ClaudeCmdOptions(fallbackModel = "haiku")).argFor("--fallback-model"))
    }

    @Test
    fun `systemPrompt maps to --system-prompt`() {
        assertEquals("be brief", argv(ClaudeCmdOptions(systemPrompt = "be brief")).argFor("--system-prompt"))
    }

    @Test
    fun `systemPromptFile maps to --system-prompt-file`() {
        assertEquals("/tmp/p.md", argv(ClaudeCmdOptions(systemPromptFile = "/tmp/p.md")).argFor("--system-prompt-file"))
    }

    @Test
    fun `systemPrompt and systemPromptFile simultaneously throws ValidationException`() {
        val command =
            Command.Claude(prompt = LlmPrompt.Prompt("hi"), options = ClaudeCmdOptions(systemPrompt = "a", systemPromptFile = "/tmp/b.md"))
        assertThrows<ValidationException> { harness.argv(command, emptyMap()) }
    }

    @Test
    fun `option values resolve VAR placeholders against the workspace env`() {
        val a =
            argv(
                ClaudeCmdOptions(systemPromptFile = "{{HMR_AGENT_DIR}}/system.md", addDir = listOf("{{HMR_AGENT_DIR}}")),
                env = mapOf("HMR_AGENT_DIR" to "/srv/agents/deploy"),
            )
        assertEquals("/srv/agents/deploy/system.md", a.argFor("--system-prompt-file"))
        assertEquals("/srv/agents/deploy", a.argFor("--add-dir"))
    }

    @Test
    fun `subagent fields resolve placeholders and stay valid JSON when the value has a quote`() {
        // The env value contains a double quote: templating the *encoded* --agents
        // JSON would corrupt it. Resolving fields before encoding lets the encoder
        // escape the substituted value, so the blob stays valid JSON.
        val a =
            argv(
                ClaudeCmdOptions(agents = mapOf("reviewer" to ClaudeSubagent(prompt = "see {{DIR}}"))),
                env = mapOf("DIR" to """a"b"""),
            )
        val parsed = Json.parseToJsonElement(a.argFor("--agents")).jsonObject
        assertEquals(
            "see a\"b",
            parsed["reviewer"]!!.jsonObject["prompt"]!!.jsonPrimitive.content,
            "subagent prompt must resolve {{DIR}} and the quote must be JSON-escaped, not corrupt the blob",
        )
    }

    @Test
    fun `subagent value resolving to another placeholder is not re-expanded into the agents JSON`() {
        // DIR resolves to a value that is itself a {{VAR}} template; EVIL's value
        // carries JSON metacharacters. A second template pass over the encoded
        // --agents blob would substitute EVIL unescaped and let it break out of the
        // prompt string and inject a sibling "tools" key. The encoded blob must be
        // excluded from that second pass (JSON injection via double evaluation).
        val a =
            argv(
                ClaudeCmdOptions(agents = mapOf("reviewer" to ClaudeSubagent(prompt = "see {{DIR}}"))),
                env = mapOf("DIR" to "{{EVIL}}", "EVIL" to """","tools":["Bash"],"x":"""),
            )
        val reviewer = Json.parseToJsonElement(a.argFor("--agents")).jsonObject["reviewer"]!!.jsonObject
        // {{DIR}} resolves once to the literal "{{EVIL}}"; the inner placeholder is NOT re-expanded.
        assertEquals("see {{EVIL}}", reviewer["prompt"]!!.jsonPrimitive.content)
        // The payload did not break out to inject a sibling field.
        assertFalse(reviewer.containsKey("tools"))
    }

    @Test
    fun `appendSystemPrompt maps to --append-system-prompt`() {
        assertEquals("also: be nice", argv(ClaudeCmdOptions(appendSystemPrompt = "also: be nice")).argFor("--append-system-prompt"))
    }

    @Test
    fun `appendSystemPromptFile maps to --append-system-prompt-file`() {
        assertEquals("/tmp/a.md", argv(ClaudeCmdOptions(appendSystemPromptFile = "/tmp/a.md")).argFor("--append-system-prompt-file"))
    }

    @Test
    fun `excludeDynamicSystemPromptSections emits bool flag`() {
        assertTrue(argv(ClaudeCmdOptions(excludeDynamicSystemPromptSections = true)).contains("--exclude-dynamic-system-prompt-sections"))
    }

    @Test
    fun `permissionMode maps to --permission-mode`() {
        assertEquals("plan", argv(ClaudeCmdOptions(permissionMode = "plan")).argFor("--permission-mode"))
    }

    @Test
    fun `permissionMode suppresses --dangerously-skip-permissions`() {
        val a = argv(ClaudeCmdOptions(permissionMode = "bypassPermissions", dangerouslySkipPermissions = true))
        assertEquals("bypassPermissions", a.argFor("--permission-mode"))
        assertFalse(a.contains("--dangerously-skip-permissions"))
    }

    @Test
    fun `permissionPromptTool maps to --permission-prompt-tool`() {
        assertEquals("mcp__ask", argv(ClaudeCmdOptions(permissionPromptTool = "mcp__ask")).argFor("--permission-prompt-tool"))
    }

    @Test
    fun `sessionId maps to --session-id`() {
        assertEquals("abc-123", argv(ClaudeCmdOptions(sessionId = "abc-123")).argFor("--session-id"))
    }

    @Test
    fun `noSessionPersistence emits bool flag`() {
        assertTrue(argv(ClaudeCmdOptions(noSessionPersistence = true)).contains("--no-session-persistence"))
    }

    @Test
    fun `agents map emits --agents with JSON-encoded value`() {
        val a =
            argv(
                ClaudeCmdOptions(
                    agents =
                        mapOf(
                            "reviewer" to
                                ClaudeSubagent(
                                    prompt = "review PRs",
                                    description = "code review",
                                    tools = listOf("Read", "Grep"),
                                    model = "sonnet",
                                ),
                        ),
                ),
            )
        val json = a.argFor("--agents")
        assertContains(json, "\"reviewer\"")
        assertContains(json, "\"prompt\":\"review PRs\"")
        assertContains(json, "\"description\":\"code review\"")
        assertContains(json, "\"tools\":[\"Read\",\"Grep\"]")
        assertContains(json, "\"model\":\"sonnet\"")
        // absent optional fields must not appear as nulls
        assertFalse(json.contains("null"))
    }

    @Test
    fun `agents map with only prompt omits absent optional fields`() {
        val json = argv(ClaudeCmdOptions(agents = mapOf("x" to ClaudeSubagent(prompt = "do x")))).argFor("--agents")
        assertContains(json, "\"prompt\":\"do x\"")
        assertFalse(json.contains("description"))
        assertFalse(json.contains("tools"))
        assertFalse(json.contains("model"))
    }

    @Test
    fun `allowedTools maps to comma-joined --allowedTools`() {
        assertEquals("Read,Grep", argv(ClaudeCmdOptions(allowedTools = listOf("Read", "Grep"))).argFor("--allowedTools"))
    }

    @Test
    fun `disallowedTools maps to comma-joined --disallowedTools`() {
        assertEquals("Write,Edit", argv(ClaudeCmdOptions(disallowedTools = listOf("Write", "Edit"))).argFor("--disallowedTools"))
    }

    @Test
    fun `tools maps to comma-joined --tools`() {
        assertEquals("Bash", argv(ClaudeCmdOptions(tools = listOf("Bash"))).argFor("--tools"))
    }

    @Test
    fun `disableSlashCommands emits bool flag`() {
        assertTrue(argv(ClaudeCmdOptions(disableSlashCommands = true)).contains("--disable-slash-commands"))
    }

    @Test
    fun `mcpConfig maps to --mcp-config`() {
        assertEquals("/tmp/mcp.json", argv(ClaudeCmdOptions(mcpConfig = listOf("/tmp/mcp.json"))).argFor("--mcp-config"))
    }

    @Test
    fun `mcpConfig with multiple values emits variadic --mcp-config`() {
        val a = argv(ClaudeCmdOptions(mcpConfig = listOf("/a.json", "/b.json")))
        val i = a.indexOfFlag("--mcp-config")
        assertTrue(i >= 0)
        assertEquals(listOf("--mcp-config", "/a.json", "/b.json"), a.subList(i, i + 3))
    }

    @Test
    fun `strictMcpConfig emits bool flag`() {
        assertTrue(argv(ClaudeCmdOptions(strictMcpConfig = true)).contains("--strict-mcp-config"))
    }

    @Test
    fun `includeHookEvents emits bool flag when set`() {
        assertTrue(argv(ClaudeCmdOptions(includeHookEvents = true)).contains("--include-hook-events"))
    }

    @Test
    fun `includeHookEvents absent by default`() {
        assertFalse(argv(ClaudeCmdOptions()).contains("--include-hook-events"))
    }

    @Test
    fun `includePartialMessages emits bool flag when set`() {
        assertTrue(argv(ClaudeCmdOptions(includePartialMessages = true)).contains("--include-partial-messages"))
    }

    @Test
    fun `includePartialMessages absent by default`() {
        assertFalse(argv(ClaudeCmdOptions()).contains("--include-partial-messages"))
    }

    @Test
    fun `inputFormat maps to --input-format`() {
        assertEquals("text", argv(ClaudeCmdOptions(inputFormat = "text")).argFor("--input-format"))
    }

    @Test
    fun `inputFormat absent by default`() {
        assertFalse(argv(ClaudeCmdOptions()).contains("--input-format"))
    }

    @Test
    fun `pluginDir maps to --plugin-dir`() {
        assertEquals("/tmp/plug", argv(ClaudeCmdOptions(pluginDir = listOf("/tmp/plug"))).argFor("--plugin-dir"))
    }

    @Test
    fun `pluginDir with multiple values repeats --plugin-dir`() {
        assertEquals(listOf("/a", "/b"), argv(ClaudeCmdOptions(pluginDir = listOf("/a", "/b"))).valuesFor("--plugin-dir"))
    }

    @Test
    fun `pluginUrl repeats --plugin-url`() {
        val a = argv(ClaudeCmdOptions(pluginUrl = listOf("https://x/m", "https://y/m")))
        assertEquals(listOf("https://x/m", "https://y/m"), a.valuesFor("--plugin-url"))
    }

    @Test
    fun `file maps to variadic --file`() {
        val a = argv(ClaudeCmdOptions(file = listOf("/a.txt", "/b.txt")))
        val i = a.indexOfFlag("--file")
        assertTrue(i >= 0)
        assertEquals(listOf("--file", "/a.txt", "/b.txt"), a.subList(i, i + 3))
    }

    @Test
    fun `settings maps to --settings`() {
        assertEquals("/tmp/s.json", argv(ClaudeCmdOptions(settings = "/tmp/s.json")).argFor("--settings"))
    }

    @Test
    fun `settingSources maps to comma-joined --setting-sources`() {
        assertEquals("user,project", argv(ClaudeCmdOptions(settingSources = listOf("user", "project"))).argFor("--setting-sources"))
    }

    @Test
    fun `addDir maps to --add-dir`() {
        assertEquals("/srv/code", argv(ClaudeCmdOptions(addDir = listOf("/srv/code"))).argFor("--add-dir"))
    }

    @Test
    fun `addDir with multiple values emits variadic --add-dir`() {
        val a = argv(ClaudeCmdOptions(addDir = listOf("/a", "/b", "/c")))
        val i = a.indexOfFlag("--add-dir")
        assertTrue(i >= 0)
        assertEquals(listOf("--add-dir", "/a", "/b", "/c"), a.subList(i, i + 4))
    }

    @Test
    fun `maxBudgetUsd maps to --max-budget-usd`() {
        assertEquals("1.25", argv(ClaudeCmdOptions(maxBudgetUsd = 1.25)).argFor("--max-budget-usd"))
    }

    @Test
    fun `effort maps to --effort`() {
        assertEquals("high", argv(ClaudeCmdOptions(effort = "high")).argFor("--effort"))
    }

    @Test
    fun `bare emits bool flag`() {
        assertTrue(argv(ClaudeCmdOptions(bare = true)).contains("--bare"))
    }

    @Test
    fun `jsonSchema maps to --json-schema`() {
        assertEquals("{\"type\":\"object\"}", argv(ClaudeCmdOptions(jsonSchema = "{\"type\":\"object\"}")).argFor("--json-schema"))
    }

    @Test
    fun `betas maps to --betas`() {
        assertEquals("context-1m-2024-08-07", argv(ClaudeCmdOptions(betas = "context-1m-2024-08-07")).argFor("--betas"))
    }

    @Test
    fun `debugFile maps to --debug-file`() {
        assertEquals("/tmp/debug.log", argv(ClaudeCmdOptions(debugFile = "/tmp/debug.log")).argFor("--debug-file"))
    }

    @Test
    fun `options passthrough is appended before hardcoded tail`() {
        val a = argv(ClaudeCmdOptions(options = listOf("--debug", "--foo")))
        assertTrue(a.contains("--debug"))
        assertTrue(a.contains("--foo"))
        assertTrue(a.indexOfFlag("--debug") < a.indexOfFlag("--output-format"))
    }

    @Test
    fun `argv requests stream-json output format`() {
        assertEquals("stream-json", argv(ClaudeCmdOptions()).argFor("--output-format"))
    }

    @Test
    fun `hardcoded tail is always present at end`() {
        assertEquals(listOf("--output-format", "stream-json", "--print", "--verbose"), argv(ClaudeCmdOptions(model = "sonnet")).takeLast(4))
    }

    @Test
    fun `reports cost`() {
        assertTrue(harness.reportsCost)
    }

    @Test
    fun `counts model calls by message id`() {
        assertTrue(harness.countsModelCalls)
    }

    @Test
    fun `init line becomes SessionStart with a gated session id`() {
        val e = events("""{"type":"system","subtype":"init","session_id":"s-1","model":"sonnet","tools":["Bash"]}""").single()
        assertEquals(LlmEvent.SessionStart(sessionId = "s-1", model = "sonnet", tools = listOf("Bash")), e)
        val bad = events("""{"type":"system","subtype":"init","session_id":"../x","model":"sonnet"}""").single()
        assertNull((bad as LlmEvent.SessionStart).sessionId)
    }

    @Test
    fun `non-init system lines and id-less thinking-only messages are ignored`() {
        assertEquals(Translation.Ignored, harness.translate(line("""{"type":"system","subtype":"hook_started"}""")))
        assertEquals(
            Translation.Ignored,
            harness.translate(line("""{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"..."}]}}""")),
        )
    }

    @Test
    fun `a system line with a session id but no model still yields a SessionStart`() {
        val e = events("""{"type":"system","subtype":"init","session_id":"sess-9","tools":[]}""").single()
        assertEquals(LlmEvent.SessionStart(sessionId = "sess-9", model = null, tools = emptyList()), e)
    }

    @Test
    fun `a thinking-only message with an id still counts as a model call`() {
        val e =
            events(
                """{"type":"assistant","message":{"id":"m1","model":"sonnet","content":[{"type":"thinking","thinking":"..."}]}}""",
            ).single() as LlmEvent.AssistantMessage
        assertEquals("m1", e.messageId)
        assertEquals("", e.text)
    }

    @Test
    fun `assistant text and tool_use blocks carry the message id`() {
        val es =
            events(
                """{"type":"assistant","message":{"id":"m1","model":"sonnet","content":[{"type":"text","text":"hi"},{"type":"tool_use","id":"t1","name":"Bash","input":{"cmd":"ls"}}]}}""",
            )
        assertEquals(LlmEvent.AssistantMessage(messageId = "m1", model = "sonnet", text = "hi"), es[0])
        val call = assertIs<LlmEvent.ToolCall>(es[1])
        assertEquals("t1", call.id)
        assertEquals("Bash", call.name)
        assertEquals("m1", call.messageId)
    }

    @Test
    fun `a subagent message is flagged`() {
        val e =
            events(
                """{"type":"assistant","parent_tool_use_id":"p","message":{"id":"m","model":"haiku","content":[{"type":"text","text":"x"}]}}""",
            ).single()
        assertTrue((e as LlmEvent.AssistantMessage).subagent)
    }

    @Test
    fun `tool_result becomes ToolResult`() {
        val e = events("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}""").single()
        assertEquals("t1", (e as LlmEvent.ToolResult).id)
    }

    @Test
    fun `result success becomes TurnEnd with usage cost and modelUsage model`() {
        val e =
            events(
                """{"type":"result","subtype":"success","duration_ms":10,"duration_api_ms":5,"num_turns":2,"result":"done","total_cost_usd":0.5,"usage":{"input_tokens":1,"output_tokens":2,"cache_read_input_tokens":3,"cache_creation_input_tokens":4},"modelUsage":{"b":{},"a":{}}}""",
            ).single() as LlmEvent.TurnEnd
        assertEquals(TurnStatus.Success, e.status)
        assertEquals(0.5, e.costUsd)
        assertEquals(1L, e.usage.input)
        assertEquals(3L, e.usage.cacheRead)
        assertEquals(4L, e.usage.cacheWrite)
        assertEquals("a+b", e.model)
        assertEquals("done", e.resultText)
    }

    @Test
    fun `result with no total_cost_usd yields a null costUsd`() {
        val e = events("""{"type":"result","subtype":"success"}""").single() as LlmEvent.TurnEnd
        assertNull(e.costUsd)
    }

    @Test
    fun `an unparseable total_cost_usd still yields a TurnEnd with a null cost, not Malformed`() {
        val e = events("""{"type":"result","subtype":"success","total_cost_usd":"not-a-number"}""").single() as LlmEvent.TurnEnd
        assertEquals(TurnStatus.Success, e.status)
        assertNull(e.costUsd)
    }

    @Test
    fun `a result line carrying a session id also yields a gated SessionStart`() {
        val es = events("""{"type":"result","subtype":"success","session_id":"sess-1"}""")
        val start = es[0] as LlmEvent.SessionStart
        assertEquals("sess-1", start.sessionId)
        assertNull(start.model)
        assertIs<LlmEvent.TurnEnd>(es[1])

        val bad = events("""{"type":"result","subtype":"success","session_id":"../x"}""")
        assertNull((bad[0] as LlmEvent.SessionStart).sessionId)
    }

    @Test
    fun `result subtypes map to Stopped and Failed`() {
        assertEquals(
            TurnStatus.Stopped("error_max_turns"),
            (events("""{"type":"result","subtype":"error_max_turns"}""").single() as LlmEvent.TurnEnd).status,
        )
        assertEquals(
            TurnStatus.Failed("error_during_execution"),
            (events("""{"type":"result","subtype":"error_during_execution"}""").single() as LlmEvent.TurnEnd).status,
        )
    }

    @Test
    fun `a retyped known field is Malformed not a throw`() {
        val t = harness.translate(line("""{"type":"assistant","message":{"content":"not-a-list"}}"""))
        assertIs<Translation.Malformed>(t)
    }

    @Test
    fun `unknown type is Ignored`() {
        assertEquals(Translation.Ignored, harness.translate(line("""{"type":"stream_event"}""")))
    }

    @Test
    fun `inspect accepts one successful turn end`() {
        assertEquals(Inspection.Success, harness.inspect(listOf(LlmEvent.TurnEnd(TurnStatus.Success)), emptyList(), command))
    }

    @Test
    fun `inspect rejects a missing or failed turn end`() {
        assertIs<Inspection.Malformed>(harness.inspect(emptyList(), emptyList(), command))
        assertIs<Inspection.Malformed>(harness.inspect(listOf(LlmEvent.TurnEnd(TurnStatus.Failed("x"))), emptyList(), command))
    }

    @Test
    fun `a Stopped turn end is Terminal only when maxTurnsExitCode is set`() {
        val stopped = listOf(LlmEvent.TurnEnd(TurnStatus.Stopped("error_max_turns")))
        assertIs<Inspection.Malformed>(harness.inspect(stopped, emptyList(), command))
        val opted = Command.Claude(prompt = LlmPrompt.Prompt("hi"), options = ClaudeCmdOptions(maxTurnsExitCode = 7))
        assertEquals(Inspection.Terminal(7), harness.inspect(stopped, emptyList(), opted))
    }

    @Test
    fun `a result with a missing subtype translates to a Failed turn end and is Malformed`() {
        val e = events("""{"type":"result"}""").single() as LlmEvent.TurnEnd
        assertEquals(TurnStatus.Failed("<missing>"), e.status)
        assertIs<Inspection.Malformed>(harness.inspect(listOf(e), emptyList(), command))
    }

    @Test
    fun `a Failed turn end is Malformed even when maxTurnsExitCode is set — only Stopped becomes Terminal`() {
        val failed = listOf(LlmEvent.TurnEnd(TurnStatus.Failed("error_during_execution")))
        val opted = Command.Claude(prompt = LlmPrompt.Prompt("hi"), options = ClaudeCmdOptions(maxTurnsExitCode = 7))
        assertIs<Inspection.Malformed>(harness.inspect(failed, emptyList(), opted))
    }

    @Test
    fun `version pin reads the raw init line`() {
        val pinned = ClaudeHarness(requireClaudeCodeVersion = "2.1.0")
        val ok = listOf(line("""{"type":"system","subtype":"init","claude_code_version":"2.1.0"}"""))
        assertEquals(Inspection.Success, pinned.inspect(listOf(LlmEvent.TurnEnd(TurnStatus.Success)), ok, command))
        val bad = listOf(line("""{"type":"system","subtype":"init","claude_code_version":"2.0.9"}"""))
        assertThrows<ClaudeCliVersionMismatchException> { pinned.inspect(listOf(LlmEvent.TurnEnd(TurnStatus.Success)), bad, command) }
        assertThrows<ClaudeCliVersionMismatchException> {
            pinned.inspect(
                listOf(LlmEvent.TurnEnd(TurnStatus.Success)),
                emptyList(),
                command,
            )
        }
    }

    @Test
    fun `argv delegates to ClaudeArgvBuilder and processEnv carries the MCP cap`() {
        val cmd = Command.Claude(prompt = LlmPrompt.Prompt("hi"), options = ClaudeCmdOptions(model = "opus", maxMcpOutputTokens = 5))
        val argv = harness.argv(cmd, emptyMap())
        assertEquals("opus", argv[argv.indexOf("--model") + 1])
        assertTrue(argv.contains("stream-json"))
        assertEquals(mapOf("MAX_MCP_OUTPUT_TOKENS" to "5"), harness.processEnv(cmd))
        assertEquals(emptyMap<String, String>(), harness.processEnv(command))
    }

    @Test
    fun `stdOutFilter is present only when subtypes are configured`() {
        assertNull(harness.stdOutFilter(command))
        assertTrue(ClaudeHarness(dropSystemSubtypes = setOf("hook_progress")).stdOutFilter(command) != null)
    }

    @Test
    fun `the recorded CLI stream yields a model and exactly one successful turn end`() {
        val text = requireNotNull(javaClass.getResourceAsStream("/claude/cli-2.1-stream.jsonl")).use { it.readBytes().decodeToString() }
        val all =
            text
                .lineSequence()
                .filter { it.isNotBlank() }
                .flatMap {
                    (harness.translate(line(it)) as? Translation.Events)?.events
                        ?: emptyList()
                }.toList()
        assertEquals(1, all.count { it is LlmEvent.TurnEnd && it.status == TurnStatus.Success })
        assertTrue(all.filterIsInstance<LlmEvent.SessionStart>().first().model != null)
        assertTrue(all.any { it is LlmEvent.ToolCall })
        assertTrue(text.lineSequence().filter { it.isNotBlank() }.none { harness.translate(line(it)) is Translation.Malformed })
    }

    // ========== §12 denial: maxTurnsExitCode bound (moved from ClaudeExecutorTest) ==========

    @Test
    fun `maxTurnsExitCode out of range is rejected at construction`() {
        for (invalid in listOf(0, -1, 256)) {
            assertThrows<IllegalArgumentException> {
                ClaudeCmdOptions(maxTurnsExitCode = invalid)
            }
        }
    }

    @Test
    fun `maxTurnsExitCode boundary values are accepted`() {
        for (valid in listOf(1, 127, 255)) {
            assertEquals(valid, ClaudeCmdOptions(maxTurnsExitCode = valid).maxTurnsExitCode)
        }
        assertEquals(null, ClaudeCmdOptions().maxTurnsExitCode)
    }
}
