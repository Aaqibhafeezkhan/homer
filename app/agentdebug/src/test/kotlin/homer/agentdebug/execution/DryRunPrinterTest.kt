package homer.agentdebug.execution

import homer.app.infra.SecretRedactor
import homer.app.models.ClaudeCmdOptions
import homer.app.models.CodexCmdOptions
import homer.app.models.Command
import homer.app.models.LlmEscalation
import homer.app.models.LlmPrompt
import homer.app.models.OpencodeCmdOptions
import homer.app.models.When
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DryRunPrinterTest {
    private fun capture(block: () -> Unit): String {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }

    @Test
    fun `claude dry-run renders the real argv builder output, not a stale reimplementation`() {
        val command =
            Command.Claude(
                prompt = LlmPrompt.Prompt("hi"),
                options =
                    ClaudeCmdOptions(
                        model = "sonnet",
                        addDir = listOf("/a", "/b"),
                        pluginDir = listOf("/p1", "/p2"),
                    ),
            )

        val out = capture { DryRunPrinter.print(command, redactor = SecretRedactor.NONE) }

        val cmdLine = out.lines().first { it.startsWith("[DRY ] cmd:") }
        // The executor's real encoding — the old printer emitted `json` and none of these flags.
        assertContains(cmdLine, "--output-format stream-json")
        assertContains(cmdLine, "--model sonnet")
        assertContains(cmdLine, "--add-dir /a /b")
        assertContains(cmdLine, "--plugin-dir /p1 --plugin-dir /p2")
        assertFalse(cmdLine.contains("--output-format json "), "must not emit the stale `json` output format")
    }

    @Test
    fun `claude dry-run resolves template placeholders against the provided env`() {
        val command =
            Command.Claude(
                prompt = LlmPrompt.Prompt("hi"),
                options = ClaudeCmdOptions(addDir = listOf("{{HMR_AGENT_DIR}}/shared")),
            )

        val out = capture { DryRunPrinter.print(command, mapOf("HMR_AGENT_DIR" to "/srv/agent"), SecretRedactor.NONE) }

        val cmdLine = out.lines().first { it.startsWith("[DRY ] cmd:") }
        assertContains(cmdLine, "--add-dir /srv/agent/shared")
    }

    // CWE-532: the argv builders resolve {{VAR}} against the env, so a secret option
    // value would otherwise be printed verbatim.
    private val secretEnv = mapOf("API_TOKEN" to "tok-s3cr3t-value")
    private val redactor = SecretRedactor.from(secretEnv, setOf("API_TOKEN"), hostEnv = { null })

    @Test
    fun `claude dry-run masks a declared secret resolved into an option`() {
        val command =
            Command.Claude(
                prompt = LlmPrompt.Prompt("use tok-s3cr3t-value"),
                options = ClaudeCmdOptions(options = listOf("--token", "{{API_TOKEN}}")),
            )

        val out = capture { DryRunPrinter.print(command, secretEnv, redactor) }

        assertContains(out.lines().first { it.startsWith("[DRY ] cmd:") }, "--token ***")
        assertFalse("tok-s3cr3t-value" in out, out)
    }

    @Test
    fun `codex dry-run masks a declared secret resolved into an option`() {
        val command =
            Command.Codex(
                prompt = LlmPrompt.Prompt("use tok-s3cr3t-value"),
                options = CodexCmdOptions(config = listOf("api_key={{API_TOKEN}}")),
            )

        val out = capture { DryRunPrinter.print(command, secretEnv, redactor) }

        assertContains(out.lines().first { it.startsWith("[DRY ] cmd:") }, "--config api_key=***")
        assertFalse("tok-s3cr3t-value" in out, out)
    }

    @Test
    fun `claude dry-run prints one line per escalate tier with its model and predicate`() {
        val command =
            Command.Claude(
                prompt = LlmPrompt.Prompt("hi"),
                options = ClaudeCmdOptions(model = "cheap"),
                escalate =
                    listOf(
                        LlmEscalation(
                            model = "claude-opus-5-5[1m]",
                            options = emptyList(),
                            whenClause = When(When.Operator.ANY, listOf("[ \"\$HMR_LLM_OUTCOME\" != success ]")),
                        ),
                    ),
            )

        val out = capture { DryRunPrinter.print(command, redactor = SecretRedactor.NONE) }

        val tierLine = out.lines().single { it.startsWith("[ESCALATE 1]") }
        // The tier's own argv, built through the harness — not the step's cheap model.
        assertContains(tierLine, "model=claude-opus-5-5[1m]")
        assertContains(tierLine, "--model claude-opus-5-5[1m]")
        assertContains(tierLine, "when=any(")
    }

    @Test
    fun `codex dry-run prints one line per escalate tier with the tier's own argv`() {
        val command =
            Command.Codex(
                prompt = LlmPrompt.Prompt("hi"),
                options = CodexCmdOptions(model = "cheap", options = listOf("--keep-me")),
                escalate =
                    listOf(
                        LlmEscalation(
                            model = "gpt-strong",
                            options = emptyList(),
                            whenClause = When(When.Operator.ALL, listOf("[ \"\$HMR_LLM_OUTCOME\" != success ]")),
                        ),
                    ),
            )

        val out = capture { DryRunPrinter.print(command, redactor = SecretRedactor.NONE) }

        val tierLine = out.lines().single { it.startsWith("[ESCALATE 1]") }
        assertContains(tierLine, "model=gpt-strong")
        assertContains(tierLine, "--model gpt-strong")
        assertContains(tierLine, "when=all(")
        // The tier replaces the step's raw options, so its argv no longer carries them.
        assertFalse("--keep-me" in tierLine, tierLine)
        assertContains(out.lines().first { it.startsWith("[DRY ] cmd:") }, "--keep-me")
    }

    @Test
    fun `opencode dry-run prints one line per escalate tier with the tier's own argv`() {
        val command =
            Command.Opencode(
                prompt = LlmPrompt.Prompt("hi"),
                options = OpencodeCmdOptions(model = "cheap", options = listOf("--keep-me")),
                escalate =
                    listOf(
                        LlmEscalation(
                            model = "openai/gpt-strong",
                            options = emptyList(),
                            whenClause = When(When.Operator.ALL, listOf("[ \"\$HMR_LLM_OUTCOME\" != success ]")),
                        ),
                    ),
            )

        val out = capture { DryRunPrinter.print(command, redactor = SecretRedactor.NONE) }

        val tierLine = out.lines().single { it.startsWith("[ESCALATE 1]") }
        assertContains(tierLine, "model=openai/gpt-strong")
        assertContains(tierLine, "--model openai/gpt-strong")
        assertContains(tierLine, "--format json")
        assertContains(tierLine, "when=all(")
        // The tier replaces the step's raw options, so its argv no longer carries them.
        assertFalse("--keep-me" in tierLine, tierLine)
        assertContains(out.lines().first { it.startsWith("[DRY ] cmd:") }, "--keep-me")
    }

    @Test
    fun `opencode dry-run masks a declared secret resolved into an option`() {
        val command =
            Command.Opencode(
                prompt = LlmPrompt.Prompt("use tok-s3cr3t-value"),
                options = OpencodeCmdOptions(options = listOf("--header", "Authorization: Bearer {{API_TOKEN}}")),
            )

        val out = capture { DryRunPrinter.print(command, secretEnv, redactor) }

        assertContains(out.lines().first { it.startsWith("[DRY ] cmd:") }, "--header Authorization: Bearer ***")
        assertFalse("tok-s3cr3t-value" in out, out)
    }

    @Test
    fun `claude dry-run prints no escalate line without tiers`() {
        val command = Command.Claude(prompt = LlmPrompt.Prompt("hi"), options = ClaudeCmdOptions(model = "cheap"))

        val out = capture { DryRunPrinter.print(command, redactor = SecretRedactor.NONE) }

        assertFalse("[ESCALATE" in out, out)
    }

    @Test
    fun `script dry-run prints the shell and every content line as stdin`() {
        val command = Command.Script(content = "echo one\necho two\n", shell = "bash")

        val out = capture { DryRunPrinter.print(command, redactor = SecretRedactor.NONE) }

        assertEquals(
            listOf("[DRY ] shell: bash", "[DRY ] stdin:", "[DRY ]   echo one", "[DRY ]   echo two"),
            out.lines().filter { it.isNotEmpty() },
        )
    }

    @Test
    fun `a when clause is printed before the command`() {
        val command = Command.Cmd(cmd = listOf("echo", "hi"), whenClause = When(When.Operator.ALL, listOf("true", "false")))

        val out = capture { DryRunPrinter.print(command, redactor = SecretRedactor.NONE) }

        assertEquals(listOf("[WHEN all] true ; false", "[DRY ] cmd: echo hi"), out.lines().filter { it.isNotEmpty() })
    }

    @Test
    fun `llm dry-runs name a Langfuse prompt instead of printing its text`() {
        val prompt = LlmPrompt.Langfuse(promptName = "triage")

        val claudeCommand = Command.Claude(prompt = prompt, options = ClaudeCmdOptions())
        val claude = capture { DryRunPrinter.print(claudeCommand, redactor = SecretRedactor.NONE) }
        val codex = capture { DryRunPrinter.print(Command.Codex(prompt = prompt), redactor = SecretRedactor.NONE) }

        assertContains(claude.lines(), "[DRY ]   <langfuse: triage>")
        assertContains(codex.lines(), "[DRY ]   <langfuse: triage>")
    }

    @Test
    fun `commands the dry run does not execute are printed as skipped`() {
        val out =
            capture {
                DryRunPrinter.print(Command.RunAgent(agentName = "child"), redactor = SecretRedactor.NONE)
                DryRunPrinter.print(Command.LangGraph(module = "graphs.flow"), redactor = SecretRedactor.NONE)
                DryRunPrinter.print(Command.RunnerCmd(runner = "gpu", execute = "make"), redactor = SecretRedactor.NONE)
            }

        assertEquals(
            listOf(
                "[DRY ] agentName: child  [SKIPPED]",
                "[DRY ] langgraph module: graphs.flow  (python: python3)  [SKIPPED]",
                "[DRY ] runner: gpu  [SKIPPED]",
            ),
            out.lines().filter { it.isNotEmpty() },
        )
    }
}
