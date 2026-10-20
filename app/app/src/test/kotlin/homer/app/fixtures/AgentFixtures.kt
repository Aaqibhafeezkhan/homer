package homer.app.fixtures

import homer.app.models.Agent
import homer.app.models.ClaudeCmdOptions
import homer.app.models.CmdArgsTokenizer
import homer.app.models.Command
import homer.app.models.LlmPrompt
import homer.app.models.OnMaxParallelRunsStrategy

/**
 * Test fixtures for Agent models
 */
object AgentFixtures {
    /**
     * Creates a simple agent with a single Cmd command
     */
    fun simpleAgent(
        name: String = "test_agent",
        command: String = "echo hello",
        description: String? = null,
    ) = Agent(
        name = name,
        description = description,
        commands =
            listOf(
                Command.Cmd(cmd = CmdArgsTokenizer.tokenize(command), timeout = 60),
            ),
        env = mapOf("TEST_VAR" to "value"),
    )

    /**
     * Creates an agent with multiple commands of different types
     */
    fun multiCommandAgent() =
        Agent(
            name = "multi_agent",
            commands =
                listOf(
                    Command.Cmd(cmd = listOf("echo", "1"), timeout = 60),
                    Command.Script(content = "echo 2", shell = "sh", timeout = 60),
                    Command.Claude(
                        prompt = LlmPrompt.Prompt("test prompt"),
                        options = ClaudeCmdOptions(maxTurns = 40, model = null),
                    ),
                ),
            env = emptyMap(),
        )

    /**
     * Creates an agent with Script command
     */
    fun scriptAgent(
        name: String = "script_agent",
        content: String = "#!/bin/bash\necho 'test'",
    ) = Agent(
        name = name,
        commands =
            listOf(
                Command.Script(content = content, shell = "sh", timeout = 60),
            ),
        env = emptyMap(),
    )

    /**
     * Creates an agent with Claude command
     */
    fun claudeAgent(
        name: String = "claude_agent",
        prompt: String = "Hello Claude",
    ) = Agent(
        name = name,
        commands =
            listOf(
                Command.Claude(
                    prompt = LlmPrompt.Prompt(prompt),
                    options = ClaudeCmdOptions(maxTurns = 40, model = null),
                ),
            ),
        env = emptyMap(),
    )

    /**
     * Creates an agent with a Codex command — the other `Command.LlmCommand`,
     * used to prove the cost-cap preflight gates on any LLM harness command,
     * not only Claude.
     */
    fun codexAgent(
        name: String = "codex_agent",
        prompt: String = "Hello Codex",
    ) = Agent(
        name = name,
        commands =
            listOf(
                Command.Codex(prompt = LlmPrompt.Prompt(prompt)),
            ),
        env = emptyMap(),
    )

    /**
     * Sample agent JSON with valid structure
     */
    fun validAgentJson() =
        """
        {
          "name": "test_agent",
          "description": "A test agent for unit testing",
          "commands": [
            {"type": "cmd", "cmd": "echo hello", "timeout": 60}
          ],
          "env": {"KEY": "value"}
        }
        """.trimIndent()

    /**
     * Sample agent JSON with invalid name (special characters)
     */
    fun invalidNameAgentJson() =
        """
        {
          "name": "test@agent#",
          "commands": [
            {"type": "cmd", "cmd": "echo hello", "timeout": 60}
          ]
        }
        """.trimIndent()

    /**
     * Sample agent JSON with empty commands
     */
    fun emptyCommandsAgentJson() =
        """
        {
          "name": "test_agent",
          "commands": []
        }
        """.trimIndent()

    /**
     * Malformed JSON
     */
    fun malformedAgentJson() =
        """
        {
          "name": "test_agent",
          "commands": [
            {"type": "cmd", "cmd": "echo"
        }
        """.trimIndent()

    /**
     * Sample agent YAML with valid structure
     */
    fun validAgentYaml() =
        """
name: test_agent_yaml
description: A test agent for unit testing in YAML format
commands:
  - !<cmd>
    cmd: echo hello
    timeout: 60
env:
  KEY: value
        """.trimIndent()

    /**
     * Malformed YAML
     */
    fun malformedAgentYaml() =
        """
        name: test_agent
        commands:
          - type: cmd
            cmd: echo
          invalid: [
        """.trimIndent()

    /**
     * Creates an agent with a specific maxParallelRuns limit
     */
    fun agentWithMaxParallelRuns(
        maxParallelRuns: Int = 2,
        strategy: OnMaxParallelRunsStrategy = OnMaxParallelRunsStrategy.WAIT,
    ) = simpleAgent().copy(maxParallelRuns = maxParallelRuns, onMaxParallelRuns = strategy)

    /**
     * Creates an agent with commands that have continueIfFail enabled
     */
    fun agentWithContinueIfFail() =
        Agent(
            name = "continue_if_fail_agent",
            commands =
                listOf(
                    Command.Cmd(cmd = listOf("echo", "1"), timeout = 60, continueIfFail = true),
                    Command.Cmd(cmd = listOf("echo", "2"), timeout = 60, continueIfFail = true),
                    Command.Cmd(cmd = listOf("echo", "3"), timeout = 60, continueIfFail = true),
                ),
            env = emptyMap(),
        )

    /**
     * Creates an agent with a finishExitCode on the first command, followed by a second command.
     * Used to verify early-finish behavior.
     */
    fun agentWithFinishExitCode(finishExitCode: Int = 128) =
        Agent(
            name = "finish_exit_code_agent",
            commands =
                listOf(
                    Command.Cmd(cmd = listOf("echo", "1"), timeout = 60, finishExitCode = finishExitCode),
                    Command.Cmd(cmd = listOf("echo", "2"), timeout = 60),
                ),
            env = emptyMap(),
        )

    /**
     * Creates an agent with finishExitCode and continueIfFail=true on the first command.
     */
    fun agentWithFinishExitCodeAndContinueIfFail(finishExitCode: Int = 128) =
        Agent(
            name = "finish_exit_code_continue_agent",
            commands =
                listOf(
                    Command.Cmd(cmd = listOf("echo", "1"), timeout = 60, finishExitCode = finishExitCode, continueIfFail = true),
                    Command.Cmd(cmd = listOf("echo", "2"), timeout = 60),
                ),
            env = emptyMap(),
        )
}
