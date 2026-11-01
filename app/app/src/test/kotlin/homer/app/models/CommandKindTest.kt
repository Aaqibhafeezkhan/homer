package homer.app.models

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CommandKindTest {
    @Test
    fun `kind is the serial name of each command type`() {
        assertEquals("cmd", Command.Cmd(cmd = listOf("true")).kind)
        assertEquals("script", Command.Script(content = "true").kind)
        assertEquals("claude", Command.Claude(prompt = LlmPrompt.Prompt("hi"), options = ClaudeCmdOptions()).kind)
        assertEquals("codex", Command.Codex(prompt = LlmPrompt.Prompt("hi")).kind)
        assertEquals("opencode", Command.Opencode(prompt = LlmPrompt.Prompt("hi")).kind)
        assertEquals("agent", Command.RunAgent(agentName = "x").kind)
        assertEquals("langgraph", Command.LangGraph(module = "m").kind)
        assertEquals("runnerCmd", Command.RunnerCmd(runner = "r", execute = "true").kind)
    }

    @Test
    fun `claude, codex and opencode are LlmCommands sharing prompt and retry`() {
        val claude: Command =
            Command.Claude(
                prompt = LlmPrompt.Prompt("hi"),
                options = ClaudeCmdOptions(),
                retry = LlmRetry(attempts = 2),
            )
        assertIs<Command.LlmCommand>(claude)
        assertEquals(2, (claude as Command.LlmCommand).retry?.attempts)
        assertIs<Command.LlmCommand>(Command.Codex(prompt = LlmPrompt.Prompt("hi")))
        val opencode = Command.Opencode(prompt = LlmPrompt.Prompt("hi"), options = OpencodeCmdOptions(model = "m"))
        assertIs<Command.LlmCommand>(opencode)
        assertEquals("m", opencode.model)
    }

    @Test
    fun `an escalation list longer than the bound is rejected at construction`() {
        // CWE-400: each tier multiplies the step's spend and runtime (ADR-0047 rule 4, ADR-0079).
        val tiers =
            List(MAX_LLM_ESCALATION_TIERS + 1) {
                LlmEscalation(model = "m$it", whenClause = When(When.Operator.ALL, listOf("true")))
            }
        val opencode = Command.Opencode(prompt = LlmPrompt.Prompt("hi"))
        val codex = Command.Codex(prompt = LlmPrompt.Prompt("hi"))

        assertIs<IllegalArgumentException>(runCatching { opencode.copy(escalate = tiers) }.exceptionOrNull())
        assertIs<IllegalArgumentException>(runCatching { codex.copy(escalate = tiers) }.exceptionOrNull())
        assertEquals(MAX_LLM_ESCALATION_TIERS, opencode.copy(escalate = tiers.dropLast(1)).escalate.size)
    }
}
