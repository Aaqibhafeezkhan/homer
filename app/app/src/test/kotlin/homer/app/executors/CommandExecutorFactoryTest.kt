package homer.app.executors

import homer.app.executors.llm.LlmHarnessExecutor
import homer.app.fixtures.TestFixtures
import homer.app.infra.FileOperations
import homer.app.infra.LangfuseClient
import homer.app.models.ClaudeCmdOptions
import homer.app.models.Command
import homer.app.models.LlmPrompt
import homer.app.services.CostMeter
import homer.app.services.LlmTelemetryService
import homer.app.services.ProcessManager
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Unit tests for CommandExecutorFactory
 * Tests executor routing and instance management
 */
class CommandExecutorFactoryTest {
    private lateinit var factory: CommandExecutorFactory
    private lateinit var mockLangfuseClient: LangfuseClient
    private lateinit var mockClaudeTelemetryService: LlmTelemetryService
    private lateinit var mockFileOperations: FileOperations
    private lateinit var mockProcessManager: ProcessManager
    private lateinit var mockCostMeter: CostMeter
    private lateinit var mockRunAgentExecutor: CommandExecutor<Command.RunAgent>
    private lateinit var mockLangGraphExecutor: CommandExecutor<Command.LangGraph>
    private lateinit var mockRunnerCmdExecutor: CommandExecutor<Command.RunnerCmd>

    @BeforeEach
    fun setup() {
        mockLangfuseClient = mockk(relaxed = true)
        mockClaudeTelemetryService = mockk(relaxed = true)
        mockFileOperations = TestFixtures.mockFileOperations()
        mockProcessManager = mockk(relaxed = true)
        mockCostMeter = mockk(relaxed = true)
        mockRunAgentExecutor = mockk(relaxed = true)
        mockLangGraphExecutor = mockk(relaxed = true)
        mockRunnerCmdExecutor = mockk(relaxed = true)
        factory =
            CommandExecutorFactory(
                mockLangfuseClient,
                mockClaudeTelemetryService,
                mockFileOperations,
                mockProcessManager,
                mockCostMeter,
                settlementScope = CoroutineScope(SupervisorJob()),
                runAgentExecutor = mockRunAgentExecutor,
                langGraphExecutor = mockLangGraphExecutor,
                runnerCmdExecutor = mockRunnerCmdExecutor,
            )
    }

    @Test
    fun `getExecutor should return CmdExecutor for Command_Cmd`() {
        val command = Command.Cmd(cmd = listOf("echo", "test"), timeout = 60)

        val executor = factory.getExecutor(command)

        assertIs<CmdExecutor>(executor, "Should return CmdExecutor for Cmd command")
    }

    @Test
    fun `getExecutor should return ScriptExecutor for Command_Script`() {
        val command = Command.Script(content = "echo test", shell = "bash", timeout = 60)

        val executor = factory.getExecutor(command)

        assertIs<ScriptExecutor>(executor, "Should return ScriptExecutor for Script command")
    }

    @Test
    fun `getExecutor should return LlmHarnessExecutor for Command_Claude`() {
        val command =
            Command.Claude(
                prompt = LlmPrompt.Prompt("test prompt"),
                options = ClaudeCmdOptions(maxTurns = 40, model = null),
            )

        val executor = factory.getExecutor(command)

        assertIs<LlmHarnessExecutor<Command.Claude>>(executor, "Should return LlmHarnessExecutor for Claude command")
    }

    @Test
    fun `getExecutor should reuse CmdExecutor instance`() {
        val command1 = Command.Cmd(cmd = listOf("echo", "1"), timeout = 60)
        val command2 = Command.Cmd(cmd = listOf("echo", "2"), timeout = 60)

        val executor1 = factory.getExecutor(command1)
        val executor2 = factory.getExecutor(command2)

        assertSame(executor1, executor2, "Should reuse the same CmdExecutor instance")
    }

    @Test
    fun `getExecutor should reuse ScriptExecutor instance`() {
        val command1 = Command.Script(content = "echo 1", shell = "bash", timeout = 60)
        val command2 = Command.Script(content = "echo 2", shell = "sh", timeout = 60)

        val executor1 = factory.getExecutor(command1)
        val executor2 = factory.getExecutor(command2)

        assertSame(executor1, executor2, "Should reuse the same ScriptExecutor instance")
    }

    @Test
    fun `getExecutor should reuse the Claude LlmHarnessExecutor instance`() {
        val command1 =
            Command.Claude(
                prompt = LlmPrompt.Prompt("prompt 1"),
                options = ClaudeCmdOptions(maxTurns = 40, model = null),
            )
        val command2 =
            Command.Claude(
                prompt = LlmPrompt.Prompt("prompt 2"),
                options = ClaudeCmdOptions(maxTurns = 40, model = null),
            )

        val executor1 = factory.getExecutor(command1)
        val executor2 = factory.getExecutor(command2)

        assertSame(executor1, executor2, "Should reuse the same LlmHarnessExecutor instance")
    }

    @Test
    fun `getExecutor should return LlmHarnessExecutor for Command_Codex`() {
        val command = Command.Codex(prompt = LlmPrompt.Prompt("test prompt"))

        val executor = factory.getExecutor(command)

        assertIs<LlmHarnessExecutor<Command.Codex>>(executor, "Should return LlmHarnessExecutor for Codex command")
    }

    @Test
    fun `getExecutor should reuse the Codex LlmHarnessExecutor instance`() {
        val executor1 = factory.getExecutor(Command.Codex(prompt = LlmPrompt.Prompt("prompt 1")))
        val executor2 = factory.getExecutor(Command.Codex(prompt = LlmPrompt.Prompt("prompt 2")))

        assertSame(executor1, executor2, "Should reuse the same LlmHarnessExecutor instance")
    }

    @Test
    fun `getExecutor should return LlmHarnessExecutor for Command_Opencode`() {
        val command = Command.Opencode(prompt = LlmPrompt.Prompt("test prompt"))

        val executor = factory.getExecutor(command)

        assertIs<LlmHarnessExecutor<Command.Opencode>>(executor, "Should return LlmHarnessExecutor for Opencode command")
    }

    @Test
    fun `getExecutor should reuse the Opencode LlmHarnessExecutor instance`() {
        val executor1 = factory.getExecutor(Command.Opencode(prompt = LlmPrompt.Prompt("prompt 1")))
        val executor2 = factory.getExecutor(Command.Opencode(prompt = LlmPrompt.Prompt("prompt 2")))

        assertSame(executor1, executor2, "Should reuse the same LlmHarnessExecutor instance")
    }

    @Test
    fun `getExecutor should return RunAgentExecutor for Command_RunAgent`() {
        val command = Command.RunAgent(agentName = "some_agent")

        val executor = factory.getExecutor(command)

        assertSame(mockRunAgentExecutor, executor, "Should return the RunAgentExecutor for RunAgent command")
    }

    @Test
    fun `getExecutor should return LangGraphExecutor for Command_LangGraph`() {
        val command = Command.LangGraph(module = "my_graphs.flow")

        val executor = factory.getExecutor(command)

        assertSame(mockLangGraphExecutor, executor, "Should return the LangGraphExecutor for LangGraph command")
    }

    @Test
    fun `getExecutor should return RunnerCmdExecutor for Command_RunnerCmd`() {
        val command = Command.RunnerCmd(runner = "build-box", execute = "echo hi")

        val executor = factory.getExecutor(command)

        assertSame(mockRunnerCmdExecutor, executor, "Should return the RunnerCmdExecutor for RunnerCmd command")
    }

    @Test
    fun `getExecutor should return different instances for different command types`() {
        val cmdCommand = Command.Cmd(cmd = listOf("echo", "test"), timeout = 60)
        val scriptCommand = Command.Script(content = "echo test", shell = "bash", timeout = 60)
        val claudeCommand =
            Command.Claude(
                prompt = LlmPrompt.Prompt("test"),
                options = ClaudeCmdOptions(maxTurns = 40, model = null),
            )

        val cmdExecutor = factory.getExecutor(cmdCommand)
        val scriptExecutor = factory.getExecutor(scriptCommand)
        val claudeExecutor = factory.getExecutor(claudeCommand)

        // Three distinct executor classes, so necessarily three distinct instances.
        assertIs<CmdExecutor>(cmdExecutor)
        assertIs<ScriptExecutor>(scriptExecutor)
        assertIs<LlmHarnessExecutor<Command.Claude>>(claudeExecutor)
    }
}
