package homer.agentdebug.commands

import com.github.ajalt.clikt.core.ProgramResult
import homer.app.models.AppConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ValidateCommandTest {
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

    /** Output of [block] together with the [ProgramResult] it throws. Restores stdout either way. */
    private fun captureFailure(block: () -> Unit): Pair<ProgramResult, String> {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer))
        val failure =
            try {
                assertThrows<ProgramResult> { block() }
            } finally {
                System.setOut(original)
            }
        return failure to buffer.toString()
    }

    @Test
    fun `agent defined with inputs has its params listed`(
        @TempDir tempDir: Path,
    ) {
        val agentDir = tempDir.resolve("with-inputs").createDirectories()
        agentDir.resolve("agent.yaml").writeText(
            """
            name: with-inputs
            commands:
              - !<cmd>
                cmd: "echo hi"
            inputs:
              - paramName: message
                envName: MESSAGE
                src: query
                type: ANY
                required: true
            """.trimIndent(),
        )
        val appConfig =
            AppConfig(
                agentsSearchPath = tempDir.toString(),
                workspaceRootPath = tempDir.resolve("workspace").toString(),
                databasePath = tempDir.resolve("db.sqlite").toString(),
            )

        val out =
            capture {
                ValidateCommand(appConfig, "with-inputs", "config.yaml").run()
            }

        assertContains(out, "Inputs: 1 params")
        assertContains(out, "message")
    }

    @Test
    fun `a nested agent definition validates`(
        @TempDir tempDir: Path,
    ) {
        val agentDir = tempDir.resolve("pipeline/nodes/analysis").createDirectories()
        agentDir.resolve("agent.yaml").writeText(
            """
            name: analysis
            description: nested fixture
            commands:
              - !<cmd>
                cmd: "echo hi"
            """.trimIndent(),
        )
        val appConfig =
            AppConfig(
                agentsSearchPath = tempDir.toString(),
                workspaceRootPath = tempDir.resolve("workspace").toString(),
                databasePath = tempDir.resolve("db.sqlite").toString(),
            )

        val out =
            capture {
                assertDoesNotThrow { ValidateCommand(appConfig, "analysis", "config.yaml").run() }
            }

        assertContains(out, "Agent: analysis")
        assertContains(out, "Loaded successfully")
        assertContains(out, "Validation passed.")
    }

    /** One agent using every command kind, plus a `when` and a result capture on the first. */
    private fun writeEveryKindAgent(tempDir: Path): AppConfig {
        val agentDir = tempDir.resolve("every-kind").createDirectories()
        agentDir.resolve("agent.yaml").writeText(
            """
            name: every-kind
            description: one of each
            dispatch:
              enabled: true
            commands:
              - !<cmd>
                cmd: "echo hi"
                label: "say"
                when: '[ -n "x" ]'
                resultEnvParam:
                  - src: STD_OUT
                    envName: GREETING
              - !<script>
                content: |
                  echo one
                  echo two
              - !<claude>
                prompt: !<langfuse>
                  promptName: "claude-langfuse"
                options:
                  model: sonnet
              - !<claude>
                prompt: !<prompt>
                  prompt: "plain claude"
                options:
                  model: sonnet
              - !<codex>
                prompt: !<langfuse>
                  promptName: "codex-langfuse"
                options:
                  model: gpt-5.2-codex
              - !<codex>
                prompt: !<prompt>
                  prompt: "plain codex"
                options:
                  model: gpt-5.2-codex
              - !<agent>
                agentName: other-agent
              - !<langgraph>
                module: graphs.flow
              - !<runnerCmd>
                runner: local
                execute: "echo remote"
              - !<opencode>
                prompt: !<prompt>
                  prompt: "plain opencode"
                options:
                  model: anthropic/claude-sonnet-4-5
            """.trimIndent(),
        )
        return AppConfig(
            agentsSearchPath = tempDir.toString(),
            workspaceRootPath = tempDir.resolve("workspace").toString(),
            databasePath = tempDir.resolve("db.sqlite").toString(),
        )
    }

    @Test
    fun `every command kind is reported, with the debug-mode notes`(
        @TempDir tempDir: Path,
    ) {
        val appConfig = writeEveryKindAgent(tempDir)

        val out = capture { ValidateCommand(appConfig, "every-kind", "config.yaml").run() }

        assertContains(out, "✓ Command[0] cmd: valid")
        assertContains(out, "✓ Command[1] script: valid")
        assertContains(out, "✓ Command[2] claude: valid (note: Langfuse prompt 'claude-langfuse' not available in debug mode)")
        assertContains(out, "✓ Command[3] claude: valid\n")
        assertContains(out, "✓ Command[4] codex: valid (note: Langfuse prompt 'codex-langfuse' not available in debug mode)")
        assertContains(out, "✓ Command[5] codex: valid\n")
        assertContains(out, "✓ Command[6] agent: valid (note: will be SKIPPED in debug run)")
        assertContains(out, "✓ Command[7] langgraph: valid (note: will be SKIPPED in debug run)")
        assertContains(out, "✓ Command[8] runnerCmd: valid (note: will be SKIPPED in debug run)")
        assertContains(out, "✓ Command[9] opencode: valid\n")
        assertContains(out, "✓ Inputs: none")
    }

    @Test
    fun `an unknown agent is reported with the available names`(
        @TempDir tempDir: Path,
    ) {
        val appConfig = writeEveryKindAgent(tempDir)

        val (failure, out) = captureFailure { ValidateCommand(appConfig, "missing", "config.yaml").run() }

        assertEquals(1, failure.statusCode)
        assertContains(out, "✗ Agent 'missing' not found in $tempDir")
        assertContains(out, "  Available: every-kind")
    }

    @Test
    fun `schema validation failure exits 1`(
        @TempDir tempDir: Path,
    ) {
        val agentDir = tempDir.resolve("bad-timeout").createDirectories()
        agentDir.resolve("agent.yaml").writeText(
            """
            name: bad-timeout
            commands:
              - !<cmd>
                cmd: "echo hi"
                timeout: -1
            """.trimIndent(),
        )
        val appConfig =
            AppConfig(
                agentsSearchPath = tempDir.toString(),
                workspaceRootPath = tempDir.resolve("workspace").toString(),
                databasePath = tempDir.resolve("db.sqlite").toString(),
            )

        val (failure, out) = captureFailure { ValidateCommand(appConfig, "bad-timeout", "config.yaml").run() }

        assertEquals(1, failure.statusCode)
        assertContains(out, "✗ Schema validation failed:")
        assertContains(out, "Validation FAILED.")
    }

    @Test
    fun `param types print as written in agent files`(
        @TempDir tempDir: Path,
    ) {
        val agentDir = tempDir.resolve("typed").createDirectories()
        agentDir.resolve("agent.yaml").writeText(
            """
            name: typed
            commands:
              - !<cmd>
                cmd: "echo hi"
            inputs:
              - paramName: count
                envName: COUNT
                src: query
                type: integer
              - paramName: legacy
                envName: LEGACY
                src: query
                type: ANY
            """.trimIndent(),
        )
        val appConfig =
            AppConfig(
                agentsSearchPath = tempDir.toString(),
                workspaceRootPath = tempDir.resolve("workspace").toString(),
                databasePath = tempDir.resolve("db.sqlite").toString(),
            )

        val out = capture { ValidateCommand(appConfig, "typed", "config.yaml").run() }

        assertContains(out, "count (integer, required)")
        assertContains(out, "legacy (ANY, required)")
    }
}
