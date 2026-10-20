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

/**
 * `agentdebug` builds its own [homer.app.services.AgentsService], so it inherits
 * nested discovery — this is the coverage that says so (ADR-0068).
 */
class ListCommandTest {
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

    private fun appConfig(tempDir: Path) =
        AppConfig(
            agentsSearchPath = tempDir.toString(),
            workspaceRootPath = tempDir.resolve("workspace").toString(),
            databasePath = tempDir.resolve("db.sqlite").toString(),
        )

    private fun writeAgent(
        dir: Path,
        name: String,
    ) {
        dir.createDirectories()
        dir.resolve("agent.yaml").writeText(
            """
            name: $name
            description: fixture
            commands:
              - !<cmd>
                cmd: "echo hi"
            """.trimIndent(),
        )
    }

    @Test
    fun `list prints a nested agent and its parent`(
        @TempDir tempDir: Path,
    ) {
        writeAgent(tempDir.resolve("web-pipeline"), "web_pipeline")
        writeAgent(tempDir.resolve("web-pipeline/nodes/analysis"), "analysis")

        val out = capture { ListCommand(appConfig(tempDir)).run() }

        assertContains(out, "Available agents (2)")
        assertContains(out, "web_pipeline")
        assertContains(out, "analysis")
    }

    @Test
    fun `list stays empty when only a hidden directory holds a definition`(
        @TempDir tempDir: Path,
    ) {
        writeAgent(tempDir.resolve(".git/hooks"), "hidden_agent")

        val out = capture { ListCommand(appConfig(tempDir)).run() }

        assertContains(out, "Available agents (0)")
    }
}
