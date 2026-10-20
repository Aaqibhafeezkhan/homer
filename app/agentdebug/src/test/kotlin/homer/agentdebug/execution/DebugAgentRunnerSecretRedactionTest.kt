package homer.agentdebug.execution

import homer.agentdebug.ConfigLoader
import homer.app.models.Agent
import homer.app.models.ClaudeCmdOptions
import homer.app.models.Command
import homer.app.models.LlmEscalation
import homer.app.models.LlmPrompt
import homer.app.models.When
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * CWE-532 parity with the server: a `globalEnv` value sourced from `@SYSENV:` is a
 * credential the operator pulled out of the host env, so the debug-run stdout sinks
 * this suite captures — `[DRY ]` argv and the `[WHEN]`/`[ESCALATE]` lines — must mask
 * it. The span side of the same redactor is pinned by `WorkspaceConfigFactoryTest`.
 * Regression coverage for the missing `sysEnvBackedGlobalEnvNames` wiring (no
 * `SYSENV` test existed in agentdebug).
 */
class DebugAgentRunnerSecretRedactionTest {
    private val secret = "tok-s3cr3t-token-value"

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

    /**
     * A one-command Claude agent whose argv and `when` predicate carry the global.
     * The `when` predicate is printed verbatim by [DryRunPrinter], so the resolved
     * credential is placed in it directly to exercise that sink's redaction too.
     */
    private fun dryRunOutput(
        tempDir: Path,
        globalEnvYaml: String,
        optionValue: String,
    ): String {
        val configPath = tempDir.resolve("app.yaml")
        configPath.writeText(
            """
            agentsSearchPath: "${tempDir.resolve("agents")}"
            workspaceRootPath: "${tempDir.resolve("ws")}"
            databasePath: "${tempDir.resolve("db.sqlite")}"
            globalEnv:
            $globalEnvYaml
            """.trimIndent(),
        )
        val appConfig = ConfigLoader.loadAppConfig(configPath.toString(), mapOf("REPO_TOKEN" to secret))
        val agent =
            Agent(
                name = "redaction-agent",
                commands =
                    listOf(
                        Command.Claude(
                            prompt = LlmPrompt.Prompt("hi"),
                            options = ClaudeCmdOptions(options = listOf("--token", optionValue)),
                            whenClause =
                                When(
                                    When.Operator.ALL,
                                    listOf("curl -sf -H \"Authorization: Bearer $secret\" https://example.test/health"),
                                ),
                            escalate =
                                listOf(
                                    LlmEscalation(
                                        model = "claude-opus-5-5[1m]",
                                        options = listOf("--token", optionValue),
                                        whenClause = When(When.Operator.ANY, listOf("true")),
                                    ),
                                ),
                        ),
                    ),
            )
        return capture {
            runBlocking {
                DebugAgentRunner(
                    appConfig = appConfig,
                    agent = agent,
                    envOverrides = mapOf("REPO_TOKEN" to secret),
                    inputMap = emptyMap(),
                    dryRun = true,
                    keepWorkspace = false,
                    cmdIndex = null,
                    logger = LoggerFactory.getLogger("agentdebug-test"),
                ).run()
            }
        }
    }

    @Test
    fun `dry-run masks a whole-value @SYSENV globalEnv in argv, when and escalate lines`(
        @TempDir tempDir: Path,
    ) {
        val out = dryRunOutput(tempDir, "  TOKEN: \"@SYSENV:REPO_TOKEN\"", "{{TOKEN}}")

        assertFalse(secret in out, out)
        assertContains(out.lines().first { it.startsWith("[DRY ] cmd:") }, "--token ***")
        assertContains(out.lines().first { it.startsWith("[WHEN ") }, "***")
        assertContains(out.lines().first { it.startsWith("[ESCALATE 1]") }, "--token ***")
    }

    @Test
    fun `dry-run masks an embedded @SYSENV globalEnv in argv, when and escalate lines`(
        @TempDir tempDir: Path,
    ) {
        val out = dryRunOutput(tempDir, "  HEADER: \"Bearer @SYSENV:REPO_TOKEN\"", "{{HEADER}}")

        assertFalse(secret in out, out)
        assertContains(out.lines().first { it.startsWith("[DRY ] cmd:") }, "--token ***")
        assertContains(out.lines().first { it.startsWith("[WHEN ") }, "***")
        assertContains(out.lines().first { it.startsWith("[ESCALATE 1]") }, "--token ***")
    }
}
