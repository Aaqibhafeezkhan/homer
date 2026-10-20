package homer.agentdebug.commands

import homer.app.models.AppConfig
import homer.app.models.RunnerConfig
import homer.app.models.SshAuth
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class AgentLoadingTest {
    private fun agent(
        root: Path,
        name: String,
        runner: String,
    ) {
        root.resolve(name).createDirectories().resolve("agent.yaml").writeText(
            """
            name: $name
            runner: $runner
            commands:
              - !<cmd>
                cmd: "echo hi"
            """.trimIndent(),
        )
    }

    @Test
    fun `agents on local or a configured runner load and one on an unknown alias is skipped`(
        @TempDir tempDir: Path,
    ) {
        val agents = tempDir.resolve("agents")
        agent(agents, "on-local", "local")
        agent(agents, "on-box", "box")
        agent(agents, "on-nowhere", "nowhere")
        val appConfig =
            AppConfig(
                agentsSearchPath = agents.toString(),
                workspaceRootPath = tempDir.resolve("workspace").toString(),
                databasePath = tempDir.resolve("db.sqlite").toString(),
                runners =
                    mapOf(
                        "box" to RunnerConfig.Ssh(host = "h", user = "u", auth = SshAuth.Agent, remoteWorkspaceRoot = "/ws"),
                    ),
            )

        val loaded = appConfig.loadAgents(LoggerFactory.getLogger("test")).agents

        assertEquals(setOf("on-local", "on-box"), loaded.keys)
    }
}
