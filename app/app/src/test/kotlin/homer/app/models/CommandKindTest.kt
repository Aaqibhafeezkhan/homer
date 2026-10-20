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
        assertEquals("agent", Command.RunAgent(agentName = "x").kind)
        assertEquals("langgraph", Command.LangGraph(module = "m").kind)
        assertEquals("runnerCmd", Command.RunnerCmd(runner = "r", execute = "true").kind)
    }

    @Test
    fun `claude and codex are LlmCommands sharing prompt and retry`() {
        val claude: Command =
            Command.Claude(
                prompt = LlmPrompt.Prompt("hi"),
                options = ClaudeCmdOptions(),
                retry = LlmRetry(attempts = 2),
            )
        assertIs<Command.LlmCommand>(claude)
        assertEquals(2, (claude as Command.LlmCommand).retry?.attempts)
        assertIs<Command.LlmCommand>(Command.Codex(prompt = LlmPrompt.Prompt("hi")))
    }
}
