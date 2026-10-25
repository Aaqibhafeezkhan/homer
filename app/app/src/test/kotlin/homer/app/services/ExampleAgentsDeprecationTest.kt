package homer.app.services

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import homer.app.fixtures.repoRoot
import homer.app.infra.AgentDirs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps the bundled `example/agents/` on the current format: loading them through the real
 * [AgentsService] path must log no deprecation WARN (`queryParams`, STRING/NUMBER/ANY, `traceTags`).
 */
class ExampleAgentsDeprecationTest {
    private val logger = LoggerFactory.getLogger("ExampleAgentsDeprecationTest") as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attach() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private fun deprecationWarnings() =
        appender.list
            .filter { it.level == Level.WARN && it.formattedMessage.contains("deprecated", ignoreCase = true) }
            .map { it.formattedMessage }

    @Test
    fun `bundled example agents log no deprecation warning`() {
        val dir = File(repoRoot, "example/agents")
        val expected =
            dir
                .listFiles { f -> f.isDirectory && AgentDirs.DEFINITION_FILE_NAMES.any { File(f, it).isFile } }!!
                .map { it.name }
                .toSet()

        val service = AgentsService(dir.path, logger)

        val loaded =
            service.agents.keys
                .mapNotNull { service.getAgentDir(it)?.fileName?.toString() }
                .toSet()
        assertEquals(expected, loaded, "every example agent must load; missing dirs: ${expected - loaded}")
        val warnings = deprecationWarnings()
        assertTrue(warnings.isEmpty(), "example agents use deprecated syntax:\n${warnings.joinToString("\n")}")
    }

    @Test
    fun `a deliberately deprecated agent does log the warning`() {
        val root = Files.createTempDirectory("deprecated-agents")
        try {
            val agentDir = Files.createDirectories(root.resolve("legacy"))
            Files.writeString(
                agentDir.resolve("agent.yaml"),
                """
                name: legacy
                commands:
                  - !<cmd>
                    cmd: "echo hi"
                queryParams:
                  - paramName: n
                    type: STRING
                    required: false
                """.trimIndent(),
            )

            AgentsService(root.toString(), logger)

            assertTrue(
                deprecationWarnings().isNotEmpty(),
                "capture must see the deprecation WARN; saw ${appender.list.map { it.formattedMessage }}",
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
