package homer.app.executors

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import homer.app.EnvConstants
import homer.app.config.ExecutionConfig
import homer.app.exceptions.InvocationCycleDetectedException
import homer.app.fixtures.TestFixtures
import homer.app.infra.AgentInputResolver
import homer.app.infra.TextCompiler
import homer.app.models.Agent
import homer.app.models.AgentCommandParam
import homer.app.models.Command
import homer.app.models.Param
import homer.app.models.ParamSource
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.models.ResultTransformer
import homer.app.services.AgentRunStarter
import homer.app.services.AgentSubRunStarter
import homer.app.services.AgentsService
import homer.app.services.Authorizer
import homer.app.services.ProcessManager
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.time.Duration
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ch.qos.logback.classic.Logger as LogbackLogger

class RunAgentExecutorTest {
    private lateinit var executionContext: ExecutionContext
    private lateinit var textCompiler: TextCompiler
    private lateinit var agentRunStarter: AgentRunStarter
    private lateinit var agentsService: AgentsService
    private lateinit var agentInputResolver: AgentInputResolver
    private lateinit var authorizer: Authorizer
    private lateinit var processManager: ProcessManager
    private lateinit var executor: RunAgentExecutor

    private lateinit var logger: LogbackLogger
    private lateinit var appender: ListAppender<ILoggingEvent>

    private val targetAgent = Agent(name = "target_agent", commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"))))

    /** Captures the inputs the resolveInputs lambda passes to AgentInputResolver. */
    private lateinit var resolvedParams: CapturingSlot<Map<String, String>>

    @BeforeEach
    fun setup() {
        textCompiler =
            mockk {
                every { replaceArguments(any(), any()) } answers { firstArg() }
            }
        agentsService =
            mockk {
                every { agents } returns mapOf("target_agent" to targetAgent)
            }
        resolvedParams = slot()
        agentInputResolver =
            mockk {
                every { resolveFromParams(capture(resolvedParams), any(), any()) } returns emptyMap()
            }
        agentRunStarter = mockk()
        // Invoke the resolveInputs lambda (the declared arg before the suspend
        // continuation, which occupies the true last slot) like the real start()
        // does, so tests that assert on the forwarded params exercise the
        // resolution path.
        coEvery {
            agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            arg<suspend () -> Map<String, String>>(9).invoke()
            42
        }
        authorizer =
            mockk {
                every { canDispatchHop(any(), any(), any()) } returns true
                // The dispatch gates the derived flow root on the run owner's
                // visibility; the denial case has its own test below.
                every { canSee(any(), any(), any()) } returns true
            }
        // Default: the origin row is unreadable, so a dispatched child derives no
        // root. Tests that assert the derivation stub getProcessInfo themselves.
        processManager = mockk(relaxed = true)
        coEvery { processManager.getProcessInfo(any()) } returns null

        executor = newExecutor(authorizer)

        val stdOut = Files.createTempFile("run_agent_test_stdout", ".txt")
        val stdErr = Files.createTempFile("run_agent_test_stderr", ".txt")
        val workspaceConfig = TestFixtures.mockWorkspaceConfig(env = mapOf("FOO" to "bar"))
        val workspace = TestFixtures.mockWorkspace(config = workspaceConfig)

        executionContext =
            ExecutionContext(
                workspace = workspace,
                config = ExecutionConfig(),
                shell = mockk(relaxed = true),
                stdOut = stdOut,
                stdErr = stdErr,
            )

        logger = LoggerFactory.getLogger(RunAgentExecutor::class.java) as LogbackLogger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
    }

    @AfterEach
    fun teardown() {
        logger.detachAppender(appender)
        Files.deleteIfExists(executionContext.stdOut)
        Files.deleteIfExists(executionContext.stdErr)
    }

    private fun warnings(): List<String> = appender.list.filter { it.level.levelStr == "WARN" }.map { it.formattedMessage }

    private fun contextWithEnv(env: Map<String, String>): ExecutionContext =
        executionContext.copy(workspace = TestFixtures.mockWorkspace(config = TestFixtures.mockWorkspaceConfig(env = env)))

    private fun newExecutor(
        authorizer: Authorizer,
        agentRunStarter: () -> AgentRunStarter = { this.agentRunStarter },
    ) = RunAgentExecutor(
        subRuns = AgentSubRunStarter(agentRunStarter, agentsService, authorizer, processManager),
        agentInputResolver = agentInputResolver,
        textCompiler = textCompiler,
    )

    @Test
    fun `happy path - writes processId to stdout and returns exit code 0`() =
        runTest {
            val result = executor.execute(Command.RunAgent(agentName = "target_agent"), executionContext, TestFixtures.mockSpan())

            assertEquals(0, result.exitCode)
            assertEquals("processId: 42", executionContext.stdOut.readText())
        }

    @Test
    fun `agent not found - returns exit code 255 and writes diagnostic to stderr`() =
        runTest {
            every { agentsService.agents } returns emptyMap()

            val result = executor.execute(Command.RunAgent(agentName = "missing_agent"), executionContext, TestFixtures.mockSpan())

            assertEquals(255, result.exitCode)
            assertEquals("agent 'missing_agent' not found", executionContext.stdErr.readText())
            coVerify(exactly = 0) { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `HomerException from start - returns exit code 255 and writes message to stderr`() =
        runTest {
            coEvery {
                agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } throws InvocationCycleDetectedException("target_agent", listOf("root", "target_agent"))

            val result = executor.execute(Command.RunAgent(agentName = "target_agent"), executionContext, TestFixtures.mockSpan())

            assertEquals(255, result.exitCode)
            val stderr = executionContext.stdErr.readText()
            assert(stderr.contains("cycle detected")) { "stderr missing cycle message: $stderr" }
        }

    @Test
    fun `forwards invocation chain parsed from env`() =
        runTest {
            val workspaceConfig = TestFixtures.mockWorkspaceConfig(env = mapOf(EnvConstants.INVOCATION_CHAIN to "root, child"))
            val context = executionContext.copy(workspace = TestFixtures.mockWorkspace(config = workspaceConfig))

            executor.execute(Command.RunAgent(agentName = "target_agent"), context, TestFixtures.mockSpan())

            coVerify { agentRunStarter.start(any(), listOf("root", "child"), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `empty invocation chain env yields empty chain`() =
        runTest {
            executor.execute(Command.RunAgent(agentName = "target_agent"), executionContext, TestFixtures.mockSpan())

            coVerify { agentRunStarter.start(any(), emptyList(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `links the child to this run via the ExecutionContext process id`() =
        runTest {
            // The env copy of HMR_PROCESS_ID is not consulted: the context id is authoritative.
            val workspaceConfig = TestFixtures.mockWorkspaceConfig(env = mapOf(EnvConstants.PROCESS_ID to "999"))
            val context =
                executionContext.copy(processId = 17, workspace = TestFixtures.mockWorkspace(config = workspaceConfig))

            executor.execute(Command.RunAgent(agentName = "target_agent"), context, TestFixtures.mockSpan())

            coVerify { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), eq(17), any(), any()) }
        }

    @Test
    fun `no parent link when the context carries no process id`() =
        runTest {
            executor.execute(Command.RunAgent(agentName = "target_agent"), executionContext, TestFixtures.mockSpan())
            coVerify { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), isNull(), any(), any()) }
        }

    @Test
    fun `dispatched child inherits the origin run's flow root`() =
        runTest {
            // The origin is itself a segment: its stored root (3) is the child's root, not
            // the origin's own id. Derived from the origin row the executor reads back; the
            // gate reads the root row to check the owner's visibility, so it must resolve.
            coEvery { processManager.getProcessInfo(3) } returns
                ProcessInfo(id = 3, status = ProcessStatus.WORKING, agentName = "asker")
            coEvery { processManager.getProcessInfo(7) } returns
                ProcessInfo(
                    id = 7,
                    status = ProcessStatus.WORKING,
                    agentName = "asker",
                    parentProcessId = 1,
                    rootProcessId = 3,
                )
            val context = executionContext.copy(processId = 7)

            executor.execute(Command.RunAgent(agentName = "target_agent"), context, TestFixtures.mockSpan())

            coVerify { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), eq(7), eq(3), any()) }
        }

    @Test
    fun `dispatched child drops the derived root when the run owner cannot see the root row`() =
        runTest {
            // [information_disclosure / CWE-200] The child is attributed to this run's
            // owner, so a flow root that owner cannot see must not reach the new row, its
            // HMR_ROOT_PROCESS_ID env or its span. The child is stored rootless instead of
            // mis-rooted.
            coEvery { processManager.getProcessInfo(3) } returns
                ProcessInfo(id = 3, status = ProcessStatus.WORKING, agentName = "asker", owner = "alice")
            coEvery { processManager.getProcessInfo(7) } returns
                ProcessInfo(
                    id = 7,
                    status = ProcessStatus.WORKING,
                    agentName = "asker",
                    parentProcessId = 1,
                    rootProcessId = 3,
                )
            every { authorizer.canSee("alice", "alice", "asker") } returns false
            val context = executionContext.copy(processId = 7, owner = "alice")

            executor.execute(Command.RunAgent(agentName = "target_agent"), context, TestFixtures.mockSpan())

            coVerify { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), eq(7), isNull(), any()) }
        }

    @Test
    fun `dispatched child roots its flow at an origin that has no root of its own`() =
        runTest {
            // A rootless origin IS the flow's root, so the child's root is the origin.
            coEvery { processManager.getProcessInfo(7) } returns
                ProcessInfo(id = 7, status = ProcessStatus.WORKING, agentName = "asker")
            val context = executionContext.copy(processId = 7)

            executor.execute(Command.RunAgent(agentName = "target_agent"), context, TestFixtures.mockSpan())

            coVerify { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), eq(7), eq(7), any()) }
        }

    @Test
    fun `dispatched child derives no root when the origin row is unreadable`() =
        runTest {
            // An evicted or purged origin leaves the child parented but rootless, so it
            // reads as a root rather than being linked to a guessed id.
            coEvery { processManager.getProcessInfo(7) } returns null
            val context = executionContext.copy(processId = 7)

            executor.execute(Command.RunAgent(agentName = "target_agent"), context, TestFixtures.mockSpan())

            coVerify { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), eq(7), isNull(), any()) }
        }

    @Test
    fun `forwards ancestor lock keys plus own lock key to start`() =
        runTest {
            val workspaceConfig =
                TestFixtures.mockWorkspaceConfig(
                    lockKey = "order-42",
                    ancestorLockKeys = listOf("root-key"),
                )
            val context = executionContext.copy(workspace = TestFixtures.mockWorkspace(config = workspaceConfig))

            executor.execute(Command.RunAgent(agentName = "target_agent"), context, TestFixtures.mockSpan())

            coVerify {
                agentRunStarter.start(any(), any(), any(), any(), listOf("root-key", "order-42"), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `forwards the parent process owner from context to start`() =
        runTest {
            val context = executionContext.copy(owner = "ci")

            executor.execute(Command.RunAgent(agentName = "target_agent"), context, TestFixtures.mockSpan())

            coVerify { agentRunStarter.start(any(), any(), any(), any(), any(), eq("ci"), any(), any(), any(), any()) }
        }

    @Test
    fun `null parent owner forwards as null`() =
        runTest {
            executor.execute(Command.RunAgent(agentName = "target_agent"), executionContext, TestFixtures.mockSpan())

            coVerify { agentRunStarter.start(any(), any(), any(), any(), any(), isNull(), any(), any(), any(), any()) }
        }

    @Test
    fun `dispatches with source agent`() =
        runTest {
            executor.execute(Command.RunAgent(agentName = "target_agent"), executionContext, TestFixtures.mockSpan())

            coVerify { agentRunStarter.start(any(), any(), "agent", any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `param mapping with Pass transformer forwards transformed value`() =
        runTest {
            val workspaceConfig = TestFixtures.mockWorkspaceConfig(env = mapOf("FOO" to "bar"))
            val context = executionContext.copy(workspace = TestFixtures.mockWorkspace(config = workspaceConfig))

            val command =
                Command.RunAgent(
                    agentName = "target_agent",
                    queryParams =
                        listOf(
                            AgentCommandParam(envName = "FOO", paramName = "target", transformer = ResultTransformer.Pass),
                        ),
                )

            executor.execute(command, context, TestFixtures.mockSpan())

            assertEquals("bar", resolvedParams.captured["target"])
        }

    @Test
    fun `param mapping with JsonPath transformer forwards extracted value`() =
        runTest {
            val workspaceConfig = TestFixtures.mockWorkspaceConfig(env = mapOf("DATA" to """{"key":"val"}"""))
            val context = executionContext.copy(workspace = TestFixtures.mockWorkspace(config = workspaceConfig))

            val command =
                Command.RunAgent(
                    agentName = "target_agent",
                    queryParams =
                        listOf(
                            AgentCommandParam(
                                envName = "DATA",
                                paramName = "version",
                                transformer = ResultTransformer.JsonPath("$.key"),
                            ),
                        ),
                )

            executor.execute(command, context, TestFixtures.mockSpan())

            assertEquals("val", resolvedParams.captured["version"])
        }

    @Test
    fun `param mapping with Regex transformer forwards captured group`() =
        runTest {
            val workspaceConfig = TestFixtures.mockWorkspaceConfig(env = mapOf("STR" to "abc-123"))
            val context = executionContext.copy(workspace = TestFixtures.mockWorkspace(config = workspaceConfig))

            val command =
                Command.RunAgent(
                    agentName = "target_agent",
                    queryParams =
                        listOf(
                            AgentCommandParam(
                                envName = "STR",
                                paramName = "num",
                                transformer = ResultTransformer.Regex("-(\\d+)", group = 1),
                            ),
                        ),
                )

            executor.execute(command, context, TestFixtures.mockSpan())

            assertEquals("123", resolvedParams.captured["num"])
        }

    @Test
    fun `param mapping with Regex transformer without a group forwards the full match`() =
        runTest {
            val context = contextWithEnv(mapOf("STR" to "abc-123"))

            val command =
                Command.RunAgent(
                    agentName = "target_agent",
                    queryParams =
                        listOf(
                            AgentCommandParam(envName = "STR", paramName = "suffix", transformer = ResultTransformer.Regex("-\\d+")),
                        ),
                )

            executor.execute(command, context, TestFixtures.mockSpan())

            assertEquals("-123", resolvedParams.captured["suffix"])
        }

    // ADR-0047 rule 8: the ReDoS denial path. The preemptive bound is what makes this
    // test fail on the unbounded `toRegex().find(value)` it replaces.
    @Test
    fun `catastrophic regex queryParam is shed within the bound and the dispatch proceeds`() {
        val subject = "a".repeat(30)
        val context = contextWithEnv(mapOf("EVIL_A" to subject))
        val command =
            Command.RunAgent(
                agentName = "target_agent",
                queryParams =
                    listOf(
                        AgentCommandParam(envName = "EVIL_A", paramName = "evil", transformer = ResultTransformer.Regex("(a+)+\\1b")),
                    ),
            )

        val result =
            assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                runBlocking { executor.execute(command, context, TestFixtures.mockSpan()) }
            }

        // The param is dropped like a non-match, and the child run is dispatched anyway.
        assertEquals(0, result.exitCode)
        assertNull(resolvedParams.captured["evil"])
        coVerify { agentRunStarter.start(any(), any(), "agent", any(), any(), any(), any(), any(), any(), any()) }
        val logged = warnings()
        assertTrue(logged.any { it.contains("EVIL_A") }, "expected a WARN naming the env var, got $logged")
        assertTrue(logged.none { it.contains(subject) }, "the WARN must not carry the env value: $logged")
    }

    @Test
    fun `a regex param after a shed one is dropped without a second bounded match`() =
        runTest {
            val subject = "a".repeat(30)
            val context = contextWithEnv(mapOf("EVIL_A" to subject, "EVIL_B" to subject))
            val command =
                Command.RunAgent(
                    agentName = "target_agent",
                    queryParams =
                        listOf(
                            AgentCommandParam(envName = "EVIL_A", paramName = "first", transformer = ResultTransformer.Regex("(a+)+\\1b")),
                            AgentCommandParam(envName = "EVIL_B", paramName = "second", transformer = ResultTransformer.Regex("(a+)+\\1b")),
                        ),
                )

            val result = executor.execute(command, context, TestFixtures.mockSpan())

            assertEquals(0, result.exitCode)
            assertNull(resolvedParams.captured["first"])
            assertNull(resolvedParams.captured["second"])
            val logged = warnings()
            assertTrue(logged.any { it.contains("EVIL_A") }, "expected a WARN for the shed param, got $logged")
            assertTrue(logged.none { it.contains("EVIL_B") }, "the second regex param should be skipped, not matched: $logged")
        }

    @Test
    fun `a non-regex param after a shed one is still resolved`() =
        runTest {
            // The shed-then-skip cap skips only later `regex` transformers; the other
            // transformer kinds are untouched, so the dispatch keeps every param the
            // cap does not have to re-bound.
            val subject = "a".repeat(30)
            val context = contextWithEnv(mapOf("EVIL_A" to subject, "PLAIN" to "bar"))
            val command =
                Command.RunAgent(
                    agentName = "target_agent",
                    queryParams =
                        listOf(
                            AgentCommandParam(envName = "EVIL_A", paramName = "first", transformer = ResultTransformer.Regex("(a+)+\\1b")),
                            AgentCommandParam(envName = "PLAIN", paramName = "second"),
                        ),
                )

            val result = executor.execute(command, context, TestFixtures.mockSpan())

            assertEquals(0, result.exitCode)
            assertNull(resolvedParams.captured["first"])
            assertEquals("bar", resolvedParams.captured["second"])
        }

    @Test
    fun `uncompilable regex pattern omits the param instead of failing the run`() =
        runTest {
            val context = contextWithEnv(mapOf("BAD_PATTERN" to "anything"))
            val command =
                Command.RunAgent(
                    agentName = "target_agent",
                    queryParams =
                        listOf(
                            AgentCommandParam(envName = "BAD_PATTERN", paramName = "broken", transformer = ResultTransformer.Regex("(")),
                        ),
                )

            val result = executor.execute(command, context, TestFixtures.mockSpan())

            assertEquals(0, result.exitCode)
            assertNull(resolvedParams.captured["broken"])
            assertTrue(warnings().any { it.contains("BAD_PATTERN") }, "expected a WARN naming the env var")
        }

    @Test
    fun `a regex param after an uncompilable one is still resolved`() =
        runTest {
            // An uncompilable pattern omits only its own param — it must not trip the
            // shed-then-skip cap, which fires only on a shed match, so a later valid
            // regex param still resolves. §12 rule 4.
            val context = contextWithEnv(mapOf("PATTERN_SOURCE" to "Version: 1.2.3"))
            val command =
                Command.RunAgent(
                    agentName = "target_agent",
                    queryParams =
                        listOf(
                            AgentCommandParam(
                                envName = "PATTERN_SOURCE",
                                paramName = "broken",
                                transformer = ResultTransformer.Regex("("),
                            ),
                            AgentCommandParam(
                                envName = "PATTERN_SOURCE",
                                paramName = "version",
                                transformer = ResultTransformer.Regex("\\d+\\.\\d+\\.\\d+"),
                            ),
                        ),
                )

            val result = executor.execute(command, context, TestFixtures.mockSpan())

            assertEquals(0, result.exitCode)
            assertNull(resolvedParams.captured["broken"])
            assertEquals("1.2.3", resolvedParams.captured["version"], "an uncompilable param must not skip a later regex param")
        }

    @Test
    fun `required BODY input on the target is satisfied and the child run starts`() =
        runTest {
            val bodyInput = Param(paramName = "payload", envName = "PAYLOAD", src = ParamSource.BODY, required = true)
            val targetWithBodyInput = targetAgent.copy(inputs = listOf(bodyInput))
            every { agentsService.agents } returns mapOf("target_agent" to targetWithBodyInput)
            val capturedParams = slot<Map<String, String>>()
            every {
                agentInputResolver.resolveFromParams(capture(capturedParams), eq(targetWithBodyInput), any())
            } returns mapOf("PAYLOAD" to "hello")

            val workspaceConfig = TestFixtures.mockWorkspaceConfig(env = mapOf("SRC_ENV" to "hello"))
            val context = executionContext.copy(workspace = TestFixtures.mockWorkspace(config = workspaceConfig))
            val command =
                Command.RunAgent(
                    agentName = "target_agent",
                    queryParams = listOf(AgentCommandParam(envName = "SRC_ENV", paramName = "payload")),
                )

            val result = executor.execute(command, context, TestFixtures.mockSpan())

            assertEquals(0, result.exitCode)
            assertEquals(mapOf("payload" to "hello"), capturedParams.captured)
        }

    @Test
    fun `missing env var - param is silently skipped, agent still launches`() =
        runTest {
            val workspaceConfig = TestFixtures.mockWorkspaceConfig(env = emptyMap())
            val context = executionContext.copy(workspace = TestFixtures.mockWorkspace(config = workspaceConfig))

            val command =
                Command.RunAgent(
                    agentName = "target_agent",
                    queryParams = listOf(AgentCommandParam(envName = "MISSING_VAR", paramName = "param")),
                )

            val result = executor.execute(command, context, TestFixtures.mockSpan())

            assertEquals(0, result.exitCode)
            assertNull(resolvedParams.captured["param"])
        }

    @Test
    fun `span attributes are set with agent name and processId`() =
        runTest {
            val span = TestFixtures.mockSpan()

            executor.execute(Command.RunAgent(agentName = "target_agent"), executionContext, span)

            io.mockk.verify { span.setAttribute("agent.name", "target_agent") }
            io.mockk.verify { span.setAttribute("agent.processId", "42") }
        }

    @Test
    fun `result returns stdOut and stdErr paths from context`() =
        runTest {
            val result = executor.execute(Command.RunAgent(agentName = "target_agent"), executionContext, TestFixtures.mockSpan())

            assertEquals(executionContext.stdOut, result.stdOut)
            assertEquals(executionContext.stdErr, result.stdErr)
        }

    // ADR-0047 rule 3: the stderr of a denied hop must read exactly like an unknown agent's.
    @Test
    fun `denied hop reports the target as not found and returns 255 without starting the sub-run`() =
        runTest {
            val deniedAuthorizer =
                mockk<Authorizer> {
                    every { canDispatchHop(any(), any(), any()) } returns false
                }
            val starter = mockk<AgentRunStarter>(relaxed = true)
            val deniedExecutor = newExecutor(deniedAuthorizer, agentRunStarter = { starter })
            val context = executionContext.copy(owner = "alice", agentName = "a")

            val result = deniedExecutor.execute(Command.RunAgent(agentName = "target_agent"), context, TestFixtures.mockSpan())

            assertEquals(255, result.exitCode)
            coVerify(exactly = 0) { starter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            assertEquals("agent 'target_agent' not found", context.stdErr.readText())
        }

    @Test
    fun `allowed hop starts the sub-run and returns 0`() =
        runTest {
            val allowedAuthorizer =
                mockk<Authorizer> {
                    every { canDispatchHop("alice", "a", "target_agent") } returns true
                }
            val starter = mockk<AgentRunStarter>()
            coEvery {
                starter.start(any(), any(), "agent", any(), any(), "alice", any(), any(), any(), any())
            } returns 42
            val allowedExecutor = newExecutor(allowedAuthorizer, agentRunStarter = { starter })
            val context = executionContext.copy(owner = "alice", agentName = "a")

            val result = allowedExecutor.execute(Command.RunAgent(agentName = "target_agent"), context, TestFixtures.mockSpan())

            assertEquals(0, result.exitCode)
            assertTrue(context.stdOut.readText().contains("processId: 42"))
        }
}
