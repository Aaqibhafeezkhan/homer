package homer.agentdebug

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The root command's `--config` / `--env` loading, through the `list` subcommand. */
class MainTest {
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

    private fun configFile(tempDir: Path): Path {
        tempDir.resolve("agents/hello").createDirectories().resolve("agent.yaml").writeText(
            """
            name: hello
            description: fixture
            commands:
              - !<cmd>
                cmd: "echo hi"
            """.trimIndent(),
        )
        return tempDir.resolve("app.yaml").also {
            it.writeText(
                """
                agentsSearchPath: "${tempDir.resolve("agents")}"
                workspaceRootPath: "${tempDir.resolve("ws")}"
                databasePath: "${tempDir.resolve("db.sqlite")}"
                globalEnv:
                  TOKEN: "@SYSENV:AGENTDEBUG_MAIN_TEST_TOKEN"
                """.trimIndent(),
            )
        }
    }

    private fun cli() = AgentdebugCli().subcommands(ListSubcommand())

    @Test
    fun `list loads the config with the --env file resolving its @SYSENV references`(
        @TempDir tempDir: Path,
    ) {
        val config = configFile(tempDir)
        val env = tempDir.resolve("vars.env").also { it.writeText("AGENTDEBUG_MAIN_TEST_TOKEN=s3cr3t\n") }

        val output = capture { cli().parse(listOf("--config", config.toString(), "--env", env.toString(), "list")) }

        assertContains(output, "hello")
    }

    @Test
    fun `list without --env resolves @SYSENV from the host environment`(
        @TempDir tempDir: Path,
    ) {
        val config = configFile(tempDir)
        config.writeText(config.toFile().readText().replace("AGENTDEBUG_MAIN_TEST_TOKEN", "PATH"))

        val output = capture { cli().parse(listOf("--config", config.toString(), "list")) }

        assertContains(output, "hello")
    }

    @Test
    fun `a subcommand without --config fails`() {
        val e = assertFailsWith<IllegalStateException> { cli().parse(listOf("list")) }

        assertEquals("--config is required for this subcommand", e.message)
    }
}
