package homer.agentdebug.commands

import com.github.ajalt.clikt.core.ProgramResult
import homer.app.models.AppConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals

class RunCommandTest {
    @TempDir
    lateinit var tempDir: Path

    private fun appConfig(cmd: String = "echo hi"): AppConfig {
        tempDir.resolve("agents/greeter").createDirectories().resolve("agent.yaml").writeText(
            """
            name: greeter
            commands:
              - !<cmd>
                cmd: "$cmd"
                label: "say"
            """.trimIndent(),
        )
        return AppConfig(
            agentsSearchPath = tempDir.resolve("agents").toString(),
            workspaceRootPath = tempDir.resolve("workspace").toString(),
            databasePath = tempDir.resolve("db.sqlite").toString(),
        )
    }

    private fun capture(block: () -> Unit): String {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer, true))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }

    private fun command(
        agentName: String,
        envFile: String? = null,
        inputFile: String? = null,
        dryRun: Boolean = true,
        keepWorkspace: Boolean = false,
        cmd: String = "echo hi",
    ) = RunCommand(appConfig(cmd), agentName, envFile, inputFile, dryRun, keepWorkspace, cmdIndex = null)

    @Test
    fun `an unknown agent fails naming the available ones`() {
        val e = assertThrows<IllegalStateException> { command("nope").run() }
        assertContains(e.message.orEmpty(), "Agent 'nope' not found. Available: greeter")
    }

    @Test
    fun `a dry run loads the env and input files and prints the commands`() {
        val envFile = tempDir.resolve("debug.env").also { it.writeText("EXTRA=1\n") }
        val inputFile = tempDir.resolve("inputs.env").also { it.writeText("first=a\nsecond=b\n") }

        val out = capture { command("greeter", envFile.toString(), inputFile.toString()).run() }

        assertContains(out, "[INFO] Agent: greeter (1 command)")
        assertContains(out, "+ 2 input =")
        assertContains(out, "\"say\"")
    }

    @Test
    fun `a real run without env or input files executes the agent and keeps the workspace on request`() {
        val out = capture { command("greeter", dryRun = false, keepWorkspace = true).run() }

        assertContains(out, "[INFO] Done: 1 executed, 0 skipped")
        assertContains(out, "[INFO] Workspace kept at:")
    }

    @Test
    fun `a failed command ends the CLI with status 1`() {
        val failure = assertThrows<ProgramResult> { capture { command("greeter", dryRun = false, cmd = "false").run() } }

        assertEquals(1, failure.statusCode)
    }
}
