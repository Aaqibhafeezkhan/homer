package homer.app.models

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class RunnerCmdSerializationTest {
    private val strictJson = Json { ignoreUnknownKeys = false }

    @Test
    fun `yaml kaml tag form deserializes`() {
        val yaml =
            """
            name: demo
            commands:
              - !<runnerCmd>
                runner: build-box
                check:
                  - which cargo
                syncToRemote:
                  - src/
                execute: |
                  cargo build --release
                syncFromRemote:
                  - target/release/app
                timeout: 600
            """.trimIndent()
        val agent = Yaml.default.decodeFromString(Agent.serializer(), yaml)
        val cmd = agent.commands.single() as Command.RunnerCmd
        assertEquals("build-box", cmd.runner)
        assertEquals(listOf("which cargo"), cmd.check)
        assertEquals(listOf("src/"), cmd.syncToRemote)
        assertEquals(listOf("target/release/app"), cmd.syncFromRemote)
        assertEquals("sh", cmd.shell)
        assertEquals(600, cmd.timeout)
    }

    @Test
    fun `json discriminator form deserializes`() {
        val json =
            """
            {"name":"demo","commands":[{"type":"runnerCmd","runner":"build-box","execute":"echo hi"}]}
            """.trimIndent()
        val agent = strictJson.decodeFromString(Agent.serializer(), json)
        val cmd = agent.commands.single() as Command.RunnerCmd
        assertEquals("build-box", cmd.runner)
        assertEquals("runnerCmd", cmd.label)
    }

    @Test
    fun `blank runner or execute is rejected`() {
        assertThrows<IllegalArgumentException> { Command.RunnerCmd(runner = " ", execute = "echo") }
        assertThrows<IllegalArgumentException> { Command.RunnerCmd(runner = "x", execute = "") }
        assertThrows<IllegalArgumentException> { Command.RunnerCmd(runner = "x", execute = "echo", check = listOf(" ")) }
    }
}
