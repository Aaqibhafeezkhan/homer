package homer.agentdebug.commands

import homer.app.models.AppConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertContains

class ShowCommandsCommandTest {
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
    fun `agent defined with inputs has its params section printed`(
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
                ShowCommandsCommand(appConfig, "with-inputs").run()
            }

        assertContains(out, "Inputs:")
        assertContains(out, "message")
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
    fun `every command kind is listed with its details`(
        @TempDir tempDir: Path,
    ) {
        val appConfig = writeEveryKindAgent(tempDir)

        val out = capture { ShowCommandsCommand(appConfig, "every-kind").run() }

        assertContains(out, "Description: one of each")
        assertContains(out, "Commands (10):")
        assertContains(out, "  [0] cmd     \"say\"    timeout=60s continueIfFail=false")
        assertContains(out, "      when (all): [ -n \"x\" ]")
        assertContains(out, "      cmd: echo hi")
        assertContains(out, "      outputs: GREETING <- STD_OUT")
        assertContains(out, "      shell: ")
        assertContains(out, "stdin: 2 lines")
        assertContains(out, "  [2] claude ")
        assertContains(out, "--output-format json --print --verbose")
        assertContains(out, "  [4] codex ")
        assertContains(out, " exec --json --color never -")
        assertContains(out, "      agentName: other-agent  [SKIPPED in debug mode]")
        assertContains(out, "      module: graphs.flow  (python: python3)  [SKIPPED in debug mode]")
        assertContains(out, "      runner: local  [SKIPPED in debug mode]")
        assertContains(out, "  [9] opencode \"opencode\"")
        assertContains(out, "      cmd: opencode run --format json")
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

        val out = capture { ShowCommandsCommand(appConfig, "typed").run() }

        assertContains(out, "count          integer  required")
        assertContains(out, "legacy         ANY      required")
    }
}
