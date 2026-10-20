package homer.app.executors.llm

import homer.app.config.ExecutionConfig
import homer.app.executors.ExecutionContext
import homer.app.fixtures.TestFixtures
import homer.app.infra.SecretRedactor
import homer.app.models.ClaudeCmdOptions
import homer.app.models.Command
import homer.app.models.LlmEscalation
import homer.app.models.LlmPrompt
import homer.app.models.When
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TierEscalationTest {
    private val escalation = TierEscalation(FakeHarness(), LoggerFactory.getLogger(TierEscalationTest::class.java))
    private val command = Command.Claude(prompt = LlmPrompt.Prompt("hi"), options = ClaudeCmdOptions(model = "small"))

    @Test
    fun `tier 0 reuses the step's argv and a later tier builds its own on its own attribute`() {
        val span = TestFixtures.mockSpan()
        val argv0 = listOf("claude", "--fake", "small")

        assertEquals(argv0, escalation.tierArgv(0, command, argv0, emptyMap(), span, SecretRedactor.NONE))
        verify { span.setAttribute("input.argv", argv0.toString()) }

        val big = command.copy(options = command.options.copy(model = "big"))
        assertEquals(listOf("claude", "--fake", "big"), escalation.tierArgv(1, big, argv0, emptyMap(), span, SecretRedactor.NONE))
        verify { span.setAttribute("llm.escalation.tier", 1L) }
    }

    @Test
    fun `the last tier never escalates and evaluates no predicate`(
        @TempDir dir: Path,
    ) = runTest {
        val tiered = command.copy(escalate = listOf(LlmEscalation(model = "big", whenClause = When(When.Operator.ALL, listOf("true")))))
        val context =
            ExecutionContext(
                workspace = TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig(env = emptyMap())),
                config = ExecutionConfig(),
                shell = TestFixtures.mockShellExecutor(),
                stdOut = dir.resolve("out"),
                stdErr = dir.resolve("err"),
            )

        assertFalse(escalation.escalates(tiered, 1, "failed", 1, context, TestFixtures.mockSpan(), SecretRedactor.NONE))
        assertFalse(escalation.escalates(command, 0, "failed", 1, context, TestFixtures.mockSpan(), SecretRedactor.NONE))
    }
}
