package homer.app.services

import homer.app.EnvConstants
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.SqlStatementRecorder
import homer.app.infra.FileOperations
import homer.app.infra.GraphStateResult
import homer.app.infra.GraphStateRunner
import homer.app.infra.LocalFileOperations
import homer.app.models.Agent
import homer.app.models.AppConfig
import homer.app.models.Command
import homer.app.models.ExecutionInfo
import homer.app.models.LangGraphEdgeRef
import homer.app.models.LangGraphRunStatus
import homer.app.models.LangGraphSubCommand
import homer.app.models.LangGraphSubProgress
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.models.SubRunProgress
import homer.app.services.persistence.ContinuationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The one owner of every run and continuation in the real-database `subs` fixture. */
private const val SUBS_OWNER = "alice"

class LangGraphRunStatusServiceTest {
    private val langGraphCmd = Command.LangGraph(module = "pkg.mod:graph", label = "Graph Flow")
    private val agent = Agent(name = "lg_agent", commands = listOf(langGraphCmd), env = mapOf("PYTHONPATH" to "/x"))
    private val appConfig =
        AppConfig(
            agentsSearchPath = "/agents",
            workspaceRootPath = "/workspaces",
            databasePath = "/var/lib/homer/homer.db",
        )

    // Fictional path, deliberately never created on disk: identity()'s NOFOLLOW
    // pre-check consults the real filesystem (not the mocked FileOperations below),
    // and treats a nonexistent path as "not a symlink" — so it must stay nonexistent
    // for these mock-based fileExists/readText stubs to be reached. A change that
    // points this at a real on-disk path (or a CI sandbox that pre-creates it) would
    // silently break that assumption.
    private val artifactsDir = Path.of("/tmp/proc1/artifacts")
    private val artifactFile = artifactsDir.resolve(LangGraphRunStatusService.ARTIFACT_FILENAME)

    private fun processInfo(
        dir: Path? = artifactsDir,
        owner: String? = null,
    ) = ProcessInfo(
        id = 1,
        status = ProcessStatus.WORKING,
        agentName = "lg_agent",
        artifactsDir = dir,
        owner = owner,
    )

    private fun subProgress(
        agentName: String,
        status: ProcessStatus,
        executions: List<ExecutionInfo> = emptyList(),
        currentCommand: String? = null,
    ) = SubRunProgress(agentName, status, executions, currentCommand)

    private fun service(
        fileOperations: FileOperations = mockk(),
        stateRunner: GraphStateRunner = GraphStateRunner { _, _, _, _, _ -> GraphStateResult.Err("unused") },
        continuationRepository: ContinuationRepository = mockk(),
        processManager: ProcessManager = mockk(),
        maxCachedStates: Int = 64,
        maxConcurrentStateRuns: Int = 4,
        maxQueueWait: Duration = 5.seconds,
        nowMillis: () -> Long = System::currentTimeMillis,
    ) = LangGraphRunStatusService(
        fileOperations = fileOperations,
        stateRunner = stateRunner,
        appConfig = appConfig,
        continuationRepository = continuationRepository,
        processManager = processManager,
        stateCacheTtl = 4.seconds,
        maxCachedStates = maxCachedStates,
        maxConcurrentStateRuns = maxConcurrentStateRuns,
        maxQueueWait = maxQueueWait,
        nowMillis = nowMillis,
    )

    /** Artifacts naming a fresh thread on every read, so no two polls share a cache key. */
    private fun fileOperationsWithUniqueThreads(): FileOperations {
        val next = AtomicInteger()
        return mockk {
            every { fileExists(artifactFile) } returns true
            every { readText(artifactFile) } answers {
                """{"threadId":"t${next.incrementAndGet()}","module":"pkg.mod:graph","segment":"fresh"}"""
            }
        }
    }

    /** Artifacts naming a different thread on each read, so every poll is a distinct cache key. */
    private fun fileOperationsForThreads(vararg threadIds: String) =
        mockk<FileOperations> {
            every { fileExists(artifactFile) } returns true
            every { readText(artifactFile) } returnsMany
                threadIds.map { """{"threadId":"$it","module":"pkg.mod:graph","segment":"fresh"}""" }
        }

    private fun fileOperationsFor(threadId: String = "t1") =
        mockk<FileOperations> {
            every { fileExists(artifactFile) } returns true
            every { readText(artifactFile) } returns """{"threadId":"$threadId","module":"pkg.mod:graph","segment":"fresh"}"""
        }

    @Test
    fun `identity is null when process has no artifacts dir`() {
        val result = service().identity(processInfo(dir = null))

        assertNull(result)
    }

    @Test
    fun `identity is null when artifact file is missing`() {
        val fileOperations = mockk<FileOperations> { every { fileExists(artifactFile) } returns false }

        val result = service(fileOperations = fileOperations).identity(processInfo())

        assertNull(result)
    }

    @Test
    fun `identity is null when artifact file is malformed`() {
        val fileOperations =
            mockk<FileOperations> {
                every { fileExists(artifactFile) } returns true
                every { readText(artifactFile) } returns "not json"
            }

        val result = service(fileOperations = fileOperations).identity(processInfo())

        assertNull(result)
    }

    @Test
    fun `identity parses a valid artifact file`() {
        val fileOperations =
            mockk<FileOperations> {
                every { fileExists(artifactFile) } returns true
                every { readText(artifactFile) } returns """{"threadId":"t1","module":"pkg.mod:graph","segment":"fresh"}"""
            }

        val result = service(fileOperations = fileOperations).identity(processInfo())

        assertEquals("t1", result?.threadId)
        assertEquals("pkg.mod:graph", result?.module)
        assertEquals("fresh", result?.segment)
    }

    @Test
    fun `identity is null when langgraph json is a symlink, even to a valid identity file`() {
        // Real FS + real LocalFileOperations here (not mockk): the NOFOLLOW pre-check
        // in identity() calls java.nio.file.Files directly against the actual path,
        // so it needs a real symlink on disk to exercise, not a stubbed method.
        val outsideDir =
            java.nio.file.Files
                .createTempDirectory("lgrs-outside")
        val artifactsTempDir =
            java.nio.file.Files
                .createTempDirectory("lgrs-artifacts")
        try {
            val target = outsideDir.resolve("real-identity.json")
            java.nio.file.Files.writeString(
                target,
                """{"threadId":"t1","module":"pkg.mod:graph","segment":"fresh"}""",
            )
            val link = artifactsTempDir.resolve(LangGraphRunStatusService.ARTIFACT_FILENAME)
            java.nio.file.Files
                .createSymbolicLink(link, target)

            val result =
                service(fileOperations = LocalFileOperations())
                    .identity(processInfo(dir = artifactsTempDir))

            assertNull(result)
        } finally {
            outsideDir.toFile().deleteRecursively()
            artifactsTempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `status is null when there is no identity`() =
        runBlocking {
            val result = service().status(processInfo(dir = null), agent, includeGraph = false)

            assertNull(result)
        }

    @Test
    fun `status without includeGraph skips the state runner`() =
        runBlocking {
            val fileOperations =
                mockk<FileOperations> {
                    every { fileExists(artifactFile) } returns true
                    every { readText(artifactFile) } returns """{"threadId":"t1","module":"pkg.mod:graph","segment":"fresh"}"""
                }
            var called = false
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    called = true
                    GraphStateResult.Err("should not run")
                }

            val result =
                service(
                    fileOperations = fileOperations,
                    stateRunner = stateRunner,
                ).status(processInfo(), agent, includeGraph = false)

            assertEquals(
                LangGraphRunStatus(label = "Graph Flow", module = "pkg.mod:graph", threadId = "t1", segment = "fresh"),
                result,
            )
            assertFalse(called)
        }

    @Test
    fun `status with includeGraph maps node status and mermaid on success`() =
        runBlocking {
            val fileOperations =
                mockk<FileOperations> {
                    every { fileExists(artifactFile) } returns true
                    every { readText(artifactFile) } returns """{"threadId":"t1","module":"pkg.mod:graph","segment":"fresh"}"""
                }
            var capturedEnv: Map<String, String>? = null
            val stateRunner =
                GraphStateRunner { _, _, _, env, _ ->
                    capturedEnv = env
                    GraphStateResult.Ok(
                        """{"threadId":"t1","nodes":{"a":"done"},"errors":{},"mermaid":"graph TD; a-->b",""" +
                            """"topology":{"nodes":[{"id":"a"}],"edges":[]}}""",
                    )
                }

            val result =
                service(fileOperations = fileOperations, stateRunner = stateRunner)
                    .status(processInfo(), agent, includeGraph = true)

            assertEquals(
                "a",
                result
                    ?.topology
                    ?.nodes
                    ?.single()
                    ?.id,
            )
            assertEquals("graph TD; a-->b", result?.mermaid)
            assertEquals(mapOf("a" to "done"), result?.nodes)
            assertNull(result?.error)
            assertEquals(appConfig.resolveLangGraphStateDir(null), capturedEnv?.get(EnvConstants.LG_STATE_DIR))
        }

    @Test
    fun `status drops an over-bound topology but keeps node statuses`() =
        runBlocking {
            val fileOperations =
                mockk<FileOperations> {
                    every { fileExists(artifactFile) } returns true
                    every { readText(artifactFile) } returns """{"threadId":"t1","module":"pkg.mod:graph","segment":"fresh"}"""
                }
            var capturedEnv: Map<String, String>? = null
            val stateRunner =
                GraphStateRunner { _, _, _, env, _ ->
                    capturedEnv = env
                    GraphStateResult.Ok(
                        """{"threadId":"t1","nodes":{"a":"done"},"errors":{},"mermaid":"graph TD; a-->b",""" +
                            """"topology":{"nodes":[{"id":"a"}],"edges":[{"source":"a","target":"ghost"}]}}""",
                    )
                }

            val result =
                service(fileOperations = fileOperations, stateRunner = stateRunner)
                    .status(processInfo(), agent, includeGraph = true)

            assertNull(result?.topology)
            assertEquals(mapOf("a" to "done"), result?.nodes)
            assertEquals(appConfig.resolveLangGraphStateDir(null), capturedEnv?.get(EnvConstants.LG_STATE_DIR))
        }

    @Test
    fun `status includes traversed edges that are real edges of the topology`() =
        runBlocking {
            val fileOperations = fileOperationsFor()
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    GraphStateResult.Ok(
                        """{"threadId":"t1","nodes":{"a":"done","b":"done"},"errors":{},""" +
                            """"topology":{"nodes":[{"id":"a"},{"id":"b"}],"edges":[{"source":"a","target":"b"}]},""" +
                            """"traversed":[{"source":"a","target":"b"}]}""",
                    )
                }

            val result =
                service(fileOperations = fileOperations, stateRunner = stateRunner)
                    .status(processInfo(), agent, includeGraph = true)

            assertEquals(listOf(LangGraphEdgeRef("a", "b")), result?.traversed)
        }

    @Test
    fun `status drops a traversed pair that is not an edge of the topology`() =
        runBlocking {
            val fileOperations = fileOperationsFor()
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    GraphStateResult.Ok(
                        """{"threadId":"t1","nodes":{"a":"done","b":"done"},"errors":{},""" +
                            """"topology":{"nodes":[{"id":"a"},{"id":"b"}],"edges":[{"source":"a","target":"b"}]},""" +
                            """"traversed":[{"source":"a","target":"b"},{"source":"a","target":"ghost"},""" +
                            """{"source":"b","target":"a"}]}""",
                    )
                }

            val result =
                service(fileOperations = fileOperations, stateRunner = stateRunner)
                    .status(processInfo(), agent, includeGraph = true)

            // "ghost" is not a node of the topology, and b->a is not a real edge
            // (only a->b is) -- both are agent-writable checkpoint data, so
            // Homer trusts neither without re-checking against its own topology.
            assertEquals(listOf(LangGraphEdgeRef("a", "b")), result?.traversed)
        }

    @Test
    fun `status collapses duplicate traversed pairs to one`() =
        runBlocking {
            val fileOperations = fileOperationsFor()
            val duplicates = List(50) { """{"source":"a","target":"b"}""" }.joinToString(",")
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    GraphStateResult.Ok(
                        """{"threadId":"t1","nodes":{"a":"done","b":"done"},"errors":{},""" +
                            """"topology":{"nodes":[{"id":"a"},{"id":"b"}],"edges":[{"source":"a","target":"b"}]},""" +
                            """"traversed":[$duplicates]}""",
                    )
                }

            val result =
                service(fileOperations = fileOperations, stateRunner = stateRunner)
                    .status(processInfo(), agent, includeGraph = true)

            // The local bound: at most one pair per topology edge, whatever the runtime sends.
            assertEquals(listOf(LangGraphEdgeRef("a", "b")), result?.traversed)
        }

    @Test
    fun `status without traversed in the state output keeps it null (old runtime)`() =
        runBlocking {
            val fileOperations = fileOperationsFor()
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    GraphStateResult.Ok(
                        """{"threadId":"t1","nodes":{"a":"done"},"errors":{},""" +
                            """"topology":{"nodes":[{"id":"a"}],"edges":[]}}""",
                    )
                }

            val result =
                service(fileOperations = fileOperations, stateRunner = stateRunner)
                    .status(processInfo(), agent, includeGraph = true)

            assertNull(result?.traversed)
        }

    @Test
    fun `status drops traversed when the topology itself is rejected`() =
        runBlocking {
            val fileOperations = fileOperationsFor()
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    GraphStateResult.Ok(
                        """{"threadId":"t1","nodes":{"a":"done"},"errors":{},""" +
                            """"topology":{"nodes":[{"id":"a"}],"edges":[{"source":"a","target":"ghost"}]},""" +
                            """"traversed":[{"source":"a","target":"ghost"}]}""",
                    )
                }

            val result =
                service(fileOperations = fileOperations, stateRunner = stateRunner)
                    .status(processInfo(), agent, includeGraph = true)

            assertNull(result?.topology)
            assertNull(result?.traversed)
        }

    @Test
    fun `status without topology in the state output keeps it null (old runtime)`() =
        runBlocking {
            val fileOperations =
                mockk<FileOperations> {
                    every { fileExists(artifactFile) } returns true
                    every { readText(artifactFile) } returns """{"threadId":"t1","module":"pkg.mod:graph","segment":"fresh"}"""
                }
            var capturedEnv: Map<String, String>? = null
            val stateRunner =
                GraphStateRunner { _, _, _, env, _ ->
                    capturedEnv = env
                    GraphStateResult.Ok("""{"threadId":"t1","nodes":{"a":"done"},"errors":{},"mermaid":"graph TD; a-->b"}""")
                }

            val result =
                service(fileOperations = fileOperations, stateRunner = stateRunner)
                    .status(processInfo(), agent, includeGraph = true)

            assertNull(result?.topology)
            assertEquals(mapOf("a" to "done"), result?.nodes)
            assertEquals(appConfig.resolveLangGraphStateDir(null), capturedEnv?.get(EnvConstants.LG_STATE_DIR))
        }

    @Test
    fun `status scopes the checkpoint store to the process owner`() =
        runBlocking {
            val fileOperations =
                mockk<FileOperations> {
                    every { fileExists(artifactFile) } returns true
                    // A forged artifact naming another principal's thread still resolves
                    // against this process's own owner-scoped store.
                    every { readText(artifactFile) } returns """{"threadId":"victim-thread","module":"pkg.mod:graph","segment":"fresh"}"""
                }
            var capturedEnv: Map<String, String>? = null
            val stateRunner =
                GraphStateRunner { _, _, _, env, _ ->
                    capturedEnv = env
                    GraphStateResult.Err("no checkpoint")
                }

            service(fileOperations = fileOperations, stateRunner = stateRunner)
                .status(processInfo(owner = "alice"), agent, includeGraph = true)

            assertEquals("/var/lib/homer/langgraph/alice", capturedEnv?.get(EnvConstants.LG_STATE_DIR))
            assertNotEquals(
                appConfig.resolveLangGraphStateDir("bob"),
                capturedEnv?.get(EnvConstants.LG_STATE_DIR),
            )
        }

    @Test
    fun `status with includeGraph surfaces runner error`() =
        runBlocking {
            val fileOperations =
                mockk<FileOperations> {
                    every { fileExists(artifactFile) } returns true
                    every { readText(artifactFile) } returns """{"threadId":"t1","module":"pkg.mod:graph","segment":"fresh"}"""
                }
            val stateRunner = GraphStateRunner { _, _, _, _, _ -> GraphStateResult.Err("boom") }

            val result =
                service(fileOperations = fileOperations, stateRunner = stateRunner)
                    .status(processInfo(), agent, includeGraph = true)

            assertEquals("boom", result?.error)
            assertNull(result?.nodes)
            // The mermaid rides the state output, so a failed state run has none.
            assertNull(result?.mermaid)
        }

    @Test
    fun `status with includeGraph surfaces unparseable state output as error`() =
        runBlocking {
            val fileOperations =
                mockk<FileOperations> {
                    every { fileExists(artifactFile) } returns true
                    every { readText(artifactFile) } returns """{"threadId":"t1","module":"pkg.mod:graph","segment":"fresh"}"""
                }
            val stateRunner = GraphStateRunner { _, _, _, _, _ -> GraphStateResult.Ok("not json") }

            val result =
                service(fileOperations = fileOperations, stateRunner = stateRunner)
                    .status(processInfo(), agent, includeGraph = true)

            assertTrue(result?.error != null)
            assertNull(result.nodes)
        }

    @Test
    fun `status reports an error when the agent no longer defines a LangGraph command`() =
        runBlocking {
            val fileOperations =
                mockk<FileOperations> {
                    every { fileExists(artifactFile) } returns true
                    every { readText(artifactFile) } returns """{"threadId":"t1","module":"pkg.mod:graph","segment":"fresh"}"""
                }
            val agentWithoutLangGraph = Agent(name = "lg_agent", commands = listOf(Command.Cmd(cmd = listOf("echo", "hi"))))

            val result = service(fileOperations = fileOperations).status(processInfo(), agentWithoutLangGraph, includeGraph = true)

            assertEquals("t1", result?.threadId)
            assertTrue(result?.error?.contains("LangGraph command") == true)
        }

    @Test
    fun `subs keeps a resolved node's history from interruptsSeen and a DISPATCHED continuation`() =
        runBlocking {
            val fileOperations = fileOperationsFor()
            // fan_a already completed: not parked, so `interrupts` is empty and only
            // `interruptsSeen` still attributes i1/i2 to it.
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    GraphStateResult.Ok(
                        """{"threadId":"t1","nodes":{"fan_a":"done"},"errors":{},"interrupts":{},""" +
                            """"interruptsSeen":{"fan_a":["i1","i2"]}}""",
                    )
                }
            val fired =
                Continuation(
                    id = 1,
                    watchedProcessId = 0,
                    status = ContinuationStatus.DISPATCHED,
                    targetAgentName = "parent",
                    params = mapOf(EnvConstants.LG_THREAD to "t1"),
                    originProcessId = 1,
                    originAgentName = "lg_agent",
                    expiresAt = null,
                    createdAt = 0L,
                    kind = ContinuationKind.JOIN,
                    watches = listOf(JoinWatch(7, "i1"), JoinWatch(8, "i2")),
                )
            val continuationRepository =
                mockk<ContinuationRepository> {
                    coEvery { byThread("t1", null) } returns listOf(fired)
                }
            val processManager =
                mockk<ProcessManager> {
                    // Sub 8 was evicted from the process store; it still appears, unresolved.
                    coEvery { getSubRunProgress(listOf(7, 8)) } returns
                        mapOf(7 to subProgress("worker-a", ProcessStatus.FINISHED))
                }

            val result =
                service(
                    fileOperations = fileOperations,
                    stateRunner = stateRunner,
                    continuationRepository = continuationRepository,
                    processManager = processManager,
                ).status(processInfo(), agent, includeGraph = true)

            assertEquals(
                listOf(
                    LangGraphSubProgress("i1", 7, "worker-a", "FINISHED", commands = emptyList()),
                    LangGraphSubProgress("i2", 8, null, null, commands = null),
                ),
                result?.subs?.get("fan_a"),
            )
        }

    @Test
    fun `subs resolves a JOIN continuation's watched sub by interrupt id`() =
        runBlocking {
            val fileOperations = fileOperationsFor()
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    GraphStateResult.Ok(
                        """{"threadId":"t1","nodes":{"fan_a":"parked"},"errors":{},"interrupts":{"fan_a":["i1"]}}""",
                    )
                }
            val join =
                Continuation(
                    id = 1,
                    watchedProcessId = 0,
                    status = ContinuationStatus.PENDING,
                    targetAgentName = "parent",
                    params = mapOf(EnvConstants.LG_THREAD to "t1"),
                    originProcessId = 1,
                    originAgentName = "lg_agent",
                    expiresAt = null,
                    createdAt = 0L,
                    kind = ContinuationKind.JOIN,
                    watches = listOf(JoinWatch(7, "i1"), JoinWatch(8, "i2")),
                )
            val continuationRepository =
                mockk<ContinuationRepository> {
                    coEvery { byThread("t1", null) } returns listOf(join)
                }
            val processManager =
                mockk<ProcessManager> {
                    // Only the parked interrupt's sub is read: i2 is not in the state.
                    coEvery { getSubRunProgress(listOf(7)) } returns
                        mapOf(7 to subProgress("worker-a", ProcessStatus.FINISHED))
                }

            val result =
                service(
                    fileOperations = fileOperations,
                    stateRunner = stateRunner,
                    continuationRepository = continuationRepository,
                    processManager = processManager,
                ).status(processInfo(), agent, includeGraph = true)

            assertEquals(
                mapOf("fan_a" to listOf(LangGraphSubProgress("i1", 7, "worker-a", "FINISHED", commands = emptyList()))),
                result?.subs,
            )
        }

    @Test
    fun `subs carries the sub's per-command progress`() =
        runBlocking {
            val fileOperations = fileOperationsFor()
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    GraphStateResult.Ok(
                        """{"threadId":"t1","nodes":{"fan_a":"parked"},"errors":{},"interrupts":{"fan_a":["i1"]}}""",
                    )
                }
            val single =
                Continuation(
                    id = 4,
                    watchedProcessId = 9,
                    status = ContinuationStatus.PENDING,
                    targetAgentName = "parent",
                    params = mapOf(EnvConstants.LG_THREAD to "t1", EnvConstants.LG_INTERRUPT to "i1"),
                    originProcessId = 1,
                    originAgentName = "lg_agent",
                    expiresAt = null,
                    createdAt = 0L,
                )
            val execution = { label: String, code: Int, errMsg: String?, skipped: Boolean ->
                ExecutionInfo(
                    label = label,
                    start = 0L,
                    end = 1L,
                    resultCode = code,
                    stdOut = null,
                    stdErr = null,
                    errMsg = errMsg,
                    skipped = skipped,
                )
            }
            val continuationRepository =
                mockk<ContinuationRepository> {
                    coEvery { byThread("t1", null) } returns listOf(single)
                }
            val processManager =
                mockk<ProcessManager> {
                    coEvery { getSubRunProgress(listOf(9)) } returns
                        mapOf(
                            9 to
                                subProgress(
                                    "worker-a",
                                    ProcessStatus.WORKING,
                                    executions =
                                        listOf(
                                            execution("setup", 0, null, false),
                                            execution("lint", 1, "Execution failed", false),
                                            execution("opt", 0, null, true),
                                            // finishExitCode/continueIfFail success: non-zero code, no errMsg
                                            execution("probe", 42, null, false),
                                        ),
                                    currentCommand = "claude",
                                ),
                        )
                }

            val result =
                service(
                    fileOperations = fileOperations,
                    stateRunner = stateRunner,
                    continuationRepository = continuationRepository,
                    processManager = processManager,
                ).status(processInfo(), agent, includeGraph = true)

            assertEquals(
                listOf(
                    LangGraphSubCommand("setup", "ok"),
                    LangGraphSubCommand("lint", "failed"),
                    LangGraphSubCommand("opt", "skipped"),
                    LangGraphSubCommand("probe", "ok"),
                    LangGraphSubCommand("claude", "running"),
                ),
                result
                    ?.subs
                    ?.get("fan_a")
                    ?.first()
                    ?.commands,
            )
        }

    @Test
    fun `subs shows a running step that shares the previous step's label`() =
        runBlocking {
            val fileOperations = fileOperationsFor()
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    GraphStateResult.Ok(
                        """{"threadId":"t1","nodes":{"fan_a":"parked"},"errors":{},"interrupts":{"fan_a":["i1"]}}""",
                    )
                }
            val single =
                Continuation(
                    id = 4,
                    watchedProcessId = 9,
                    status = ContinuationStatus.PENDING,
                    targetAgentName = "parent",
                    params = mapOf(EnvConstants.LG_THREAD to "t1", EnvConstants.LG_INTERRUPT to "i1"),
                    originProcessId = 1,
                    originAgentName = "lg_agent",
                    expiresAt = null,
                    createdAt = 0L,
                )
            val continuationRepository =
                mockk<ContinuationRepository> {
                    coEvery { byThread("t1", null) } returns listOf(single)
                }
            val processManager =
                mockk<ProcessManager> {
                    coEvery { getSubRunProgress(listOf(9)) } returns
                        mapOf(
                            9 to
                                subProgress(
                                    "worker-a",
                                    ProcessStatus.WORKING,
                                    executions =
                                        listOf(
                                            ExecutionInfo(
                                                label = "lint",
                                                start = 0L,
                                                end = 1L,
                                                resultCode = 0,
                                                stdOut = null,
                                                stdErr = null,
                                                errMsg = null,
                                            ),
                                        ),
                                    // The boundary commits the append and the set in one transaction,
                                    // so a running "lint" here is the NEXT step: a step may share its
                                    // predecessor's label, and it must not be hidden as a duplicate.
                                    currentCommand = "lint",
                                ),
                        )
                }

            val result =
                service(
                    fileOperations = fileOperations,
                    stateRunner = stateRunner,
                    continuationRepository = continuationRepository,
                    processManager = processManager,
                ).status(processInfo(), agent, includeGraph = true)

            assertEquals(
                listOf(LangGraphSubCommand("lint", "ok"), LangGraphSubCommand("lint", "running")),
                result
                    ?.subs
                    ?.get("fan_a")
                    ?.first()
                    ?.commands,
            )
        }

    @Test
    fun `subs resolves a SINGLE continuation's watched sub by interrupt id`() =
        runBlocking {
            val fileOperations = fileOperationsFor()
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    GraphStateResult.Ok(
                        """{"threadId":"t1","nodes":{"fan_a":"parked"},"errors":{},"interrupts":{"fan_a":["i1"]}}""",
                    )
                }
            val single =
                Continuation(
                    id = 2,
                    watchedProcessId = 9,
                    status = ContinuationStatus.PENDING,
                    targetAgentName = "parent",
                    params = mapOf(EnvConstants.LG_THREAD to "t1", EnvConstants.LG_INTERRUPT to "i1"),
                    originProcessId = 1,
                    originAgentName = "lg_agent",
                    expiresAt = null,
                    createdAt = 0L,
                )
            val continuationRepository =
                mockk<ContinuationRepository> {
                    coEvery { byThread("t1", null) } returns listOf(single)
                }
            val processManager =
                mockk<ProcessManager> {
                    coEvery { getSubRunProgress(listOf(9)) } returns
                        mapOf(9 to subProgress("worker-a", ProcessStatus.FINISHED))
                }

            val result =
                service(
                    fileOperations = fileOperations,
                    stateRunner = stateRunner,
                    continuationRepository = continuationRepository,
                    processManager = processManager,
                ).status(processInfo(), agent, includeGraph = true)

            assertEquals(
                mapOf("fan_a" to listOf(LangGraphSubProgress("i1", 9, "worker-a", "FINISHED", commands = emptyList()))),
                result?.subs,
            )
        }

    @Test
    fun `subs ignores a PENDING continuation registered for a different thread`() =
        runBlocking {
            val fileOperations = fileOperationsFor()
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    GraphStateResult.Ok(
                        """{"threadId":"t1","nodes":{"fan_a":"parked"},"errors":{},"interrupts":{"fan_a":["i1"]}}""",
                    )
                }
            val otherThread =
                Continuation(
                    id = 3,
                    watchedProcessId = 9,
                    status = ContinuationStatus.PENDING,
                    targetAgentName = "parent",
                    params = mapOf(EnvConstants.LG_THREAD to "other-thread", EnvConstants.LG_INTERRUPT to "i1"),
                    originProcessId = 1,
                    originAgentName = "lg_agent",
                    expiresAt = null,
                    createdAt = 0L,
                )
            val continuationRepository =
                mockk<ContinuationRepository> {
                    // The service asks for its own thread only; the other thread's row is never read.
                    coEvery { byThread("t1", null) } returns emptyList()
                    coEvery { byThread("other-thread", null) } returns listOf(otherThread)
                }
            val processManager = mockk<ProcessManager> { coEvery { getSubRunProgress(any()) } returns emptyMap() }

            val result =
                service(
                    fileOperations = fileOperations,
                    stateRunner = stateRunner,
                    continuationRepository = continuationRepository,
                    processManager = processManager,
                ).status(processInfo(), agent, includeGraph = true)

            // Non-null and empty: the lookup ran and found nothing, rather than failing into null.
            assertEquals(emptyMap(), result?.subs)
            coVerify(exactly = 0) { continuationRepository.byThread("other-thread", any()) }
            coVerify(exactly = 1) { processManager.getSubRunProgress(emptyList()) }
        }

    @Test
    fun `subs omits an interrupt id with no matching continuation (e_g_ a human gate)`() =
        runBlocking {
            val fileOperations = fileOperationsFor()
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    GraphStateResult.Ok(
                        """{"threadId":"t1","nodes":{"gate_a":"parked"},"errors":{},"interrupts":{"gate_a":["ghost"]}}""",
                    )
                }
            val continuationRepository =
                mockk<ContinuationRepository> {
                    coEvery { byThread("t1", null) } returns emptyList()
                }
            val processManager = mockk<ProcessManager> { coEvery { getSubRunProgress(any()) } returns emptyMap() }

            val result =
                service(
                    fileOperations = fileOperations,
                    stateRunner = stateRunner,
                    continuationRepository = continuationRepository,
                    processManager = processManager,
                ).status(processInfo(), agent, includeGraph = true)

            // Non-null and empty: the lookup ran and found nothing, rather than failing into null.
            assertEquals(emptyMap(), result?.subs)
            coVerify(exactly = 1) { processManager.getSubRunProgress(emptyList()) }
        }

    @Test
    fun `subs is null when the state output has no interrupts key (old runtime)`() =
        runBlocking {
            val fileOperations = fileOperationsFor()
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    GraphStateResult.Ok("""{"threadId":"t1","nodes":{"a":"done"},"errors":{}}""")
                }
            val continuationRepository = mockk<ContinuationRepository>()

            val result =
                service(
                    fileOperations = fileOperations,
                    stateRunner = stateRunner,
                    continuationRepository = continuationRepository,
                    processManager = mockk(),
                ).status(processInfo(), agent, includeGraph = true)

            assertNull(result?.subs)
            assertEquals(mapOf("a" to "done"), result?.nodes)
            coVerify(exactly = 0) { continuationRepository.byThread(any(), any()) }
        }

    @Test
    fun `includeGraph false never touches the continuation lookup`() =
        runBlocking {
            val fileOperations = fileOperationsFor()
            val continuationRepository = mockk<ContinuationRepository>()

            val result =
                service(
                    fileOperations = fileOperations,
                    continuationRepository = continuationRepository,
                    processManager = mockk(),
                ).status(processInfo(), agent, includeGraph = false)

            assertNull(result?.subs)
            coVerify(exactly = 0) { continuationRepository.byThread(any(), any()) }
        }

    @Test
    fun `subs is null but the rest of the response is intact when the continuation lookup throws`() =
        runBlocking {
            val fileOperations = fileOperationsFor()
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    GraphStateResult.Ok(
                        """{"threadId":"t1","nodes":{"fan_a":"parked"},"errors":{},""" +
                            """"interrupts":{"fan_a":["i1"]},"mermaid":"graph TD; a-->b"}""",
                    )
                }
            val continuationRepository =
                mockk<ContinuationRepository> {
                    coEvery { byThread("t1", null) } throws RuntimeException("db down")
                }

            val result =
                service(
                    fileOperations = fileOperations,
                    stateRunner = stateRunner,
                    continuationRepository = continuationRepository,
                    processManager = mockk(),
                ).status(processInfo(), agent, includeGraph = true)

            assertEquals("graph TD; a-->b", result?.mermaid)
            assertEquals(mapOf("fan_a" to "parked"), result?.nodes)
            assertNull(result?.subs)
        }

    @Test
    fun `a cancellation during the continuation lookup propagates instead of answering without subs`() =
        runBlocking {
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    GraphStateResult.Ok("""{"threadId":"t1","nodes":{"fan_a":"parked"},"errors":{},"interrupts":{"fan_a":["i1"]}}""")
                }
            val continuationRepository =
                mockk<ContinuationRepository> {
                    coEvery { byThread("t1", null) } throws CancellationException("client went away")
                }
            val service =
                service(
                    fileOperations = fileOperationsFor(),
                    stateRunner = stateRunner,
                    continuationRepository = continuationRepository,
                    processManager = mockk(),
                )

            assertFailsWith<CancellationException> { service.status(processInfo(), agent, includeGraph = true) }
            Unit
        }

    @Test
    fun `subsTruncated carries the runtime's clipped nodes`() =
        runBlocking {
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    GraphStateResult.Ok(
                        """{"threadId":"t1","nodes":{"loop":"parked"},"errors":{},""" +
                            """"interrupts":{"loop":["i9"]},"interruptsSeenTruncated":["loop"]}""",
                    )
                }
            val continuationRepository = mockk<ContinuationRepository> { coEvery { byThread("t1", null) } returns emptyList() }

            val result =
                service(
                    fileOperations = fileOperationsFor(),
                    stateRunner = stateRunner,
                    continuationRepository = continuationRepository,
                ).status(processInfo(), agent, includeGraph = true)

            assertEquals(listOf("loop"), result?.subsTruncated)
        }

    @Test
    fun `subsTruncated is null when the runtime clipped nothing`() =
        runBlocking {
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    GraphStateResult.Ok("""{"threadId":"t1","nodes":{"a":"done"},"errors":{},"interruptsSeenTruncated":[]}""")
                }

            val result =
                service(fileOperations = fileOperationsFor(), stateRunner = stateRunner)
                    .status(processInfo(), agent, includeGraph = true)

            assertNull(result?.subsTruncated)
        }

    @Test
    fun `a second poll within the TTL reuses the state reading`() =
        runBlocking {
            var runs = 0
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    runs++
                    GraphStateResult.Ok("""{"threadId":"t1","nodes":{"a":"done"},"errors":{}}""")
                }
            var clock = 1_000L
            val service = service(fileOperations = fileOperationsFor(), stateRunner = stateRunner, nowMillis = { clock })

            val first = service.status(processInfo(), agent, includeGraph = true)
            clock += 3_000
            val second = service.status(processInfo(), agent, includeGraph = true)

            assertEquals(1, runs)
            assertEquals(first, second)
        }

    @Test
    fun `a poll after the TTL re-reads the state`() =
        runBlocking {
            var runs = 0
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    runs++
                    GraphStateResult.Ok("""{"threadId":"t1","nodes":{"a":"${if (runs == 1) "pending" else "done"}"},"errors":{}}""")
                }
            var clock = 1_000L
            val service = service(fileOperations = fileOperationsFor(), stateRunner = stateRunner, nowMillis = { clock })

            assertEquals(mapOf("a" to "pending"), service.status(processInfo(), agent, includeGraph = true)?.nodes)
            clock += 4_000
            assertEquals(mapOf("a" to "done"), service.status(processInfo(), agent, includeGraph = true)?.nodes)
            assertEquals(2, runs)
        }

    @Test
    fun `never more state subprocesses run at once than the configured limit`() =
        runBlocking {
            // Distinct threads on purpose: nothing here is a cache hit, so only the
            // permit limits how many interpreters exist at the same moment.
            val inFlight = AtomicInteger()
            val peak = AtomicInteger()
            val stateRunner =
                GraphStateRunner { _, _, threadId, _, _ ->
                    val now = inFlight.incrementAndGet()
                    peak.getAndUpdate { max(it, now) }
                    delay(30)
                    inFlight.decrementAndGet()
                    GraphStateResult.Ok("""{"threadId":"$threadId","nodes":{"a":"done"},"errors":{}}""")
                }
            val service =
                service(
                    fileOperations = fileOperationsWithUniqueThreads(),
                    stateRunner = stateRunner,
                    maxConcurrentStateRuns = 3,
                )

            coroutineScope {
                repeat(12) { launch(Dispatchers.Default) { service.status(processInfo(), agent, includeGraph = true) } }
            }

            assertTrue(peak.get() in 1..3, "peak concurrency was ${peak.get()}, limit is 3")
        }

    @Test
    fun `a burst of concurrent polls for one thread collapses onto the runs already in flight`() =
        runBlocking {
            // The cache alone does not do this: every one of these callers misses on
            // arrival. Re-checking after taking a permit is what stops each queued
            // caller from spawning its own interpreter.
            val runs = AtomicInteger()
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    runs.incrementAndGet()
                    delay(30)
                    GraphStateResult.Ok("""{"threadId":"t1","nodes":{"a":"done"},"errors":{}}""")
                }
            val service =
                service(
                    fileOperations = fileOperationsFor("t1"),
                    stateRunner = stateRunner,
                    maxConcurrentStateRuns = 2,
                    // Frozen: the entry cannot expire mid-burst.
                    nowMillis = { 1_000L },
                )

            coroutineScope {
                repeat(20) { launch(Dispatchers.Default) { service.status(processInfo(), agent, includeGraph = true) } }
            }

            assertTrue(runs.get() in 1..2, "20 concurrent polls ran ${runs.get()} subprocesses, expected at most 2")
        }

    @Test
    fun `a caller that waits out the queue limit is shed, and the refusal is not cached`() =
        runBlocking {
            val runs = AtomicInteger()
            val stateRunner =
                GraphStateRunner { _, _, threadId, _, _ ->
                    runs.incrementAndGet()
                    delay(300)
                    GraphStateResult.Ok("""{"threadId":"$threadId","nodes":{"a":"done"},"errors":{}}""")
                }
            val service =
                service(
                    // Distinct threads: the second caller cannot be served by the cache,
                    // so it has to queue behind the only slot.
                    fileOperations = fileOperationsWithUniqueThreads(),
                    stateRunner = stateRunner,
                    maxConcurrentStateRuns = 1,
                    maxQueueWait = 40.milliseconds,
                )

            val occupier = launch(Dispatchers.Default) { service.status(processInfo(), agent, includeGraph = true) }
            delay(60) // let it take the only permit
            val shed = service.status(processInfo(), agent, includeGraph = true)
            occupier.join()

            assertEquals(LangGraphRunStatusService.BUSY_MESSAGE, shed?.error)
            assertNull(shed?.nodes, "a shed request reports no state")
            // The permit came back and nothing cached the refusal, so the next caller works.
            val after = service.status(processInfo(), agent, includeGraph = true)
            assertEquals(mapOf("a" to "done"), after?.nodes)
            assertNull(after?.error)
        }

    @Test
    fun `the cache never holds more than its maximum, evicting the least recently read`() =
        runBlocking {
            // Distinct thread ids come from an agent-writable artifact, so cardinality
            // is attacker-influenced: the bound must hold regardless of the TTL.
            val threads = mutableListOf<String>()
            val stateRunner =
                GraphStateRunner { _, _, threadId, _, _ ->
                    threads += threadId
                    GraphStateResult.Ok("""{"threadId":"$threadId","nodes":{"a":"done"},"errors":{}}""")
                }
            val service =
                service(
                    fileOperations = fileOperationsForThreads("t1", "t2", "t3", "t1", "t3"),
                    stateRunner = stateRunner,
                    maxCachedStates = 2,
                    // Frozen clock: nothing can expire, so only the size bound can evict.
                    nowMillis = { 1_000L },
                )

            repeat(5) { service.status(processInfo(), agent, includeGraph = true) }

            // t1 fell out when t3 arrived (2 entries max) and had to be re-read; the
            // second t3 poll was still a hit.
            assertEquals(listOf("t1", "t2", "t3", "t1"), threads)
        }

    @Test
    fun `a runner failure is cached too`() =
        runBlocking {
            var runs = 0
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    runs++
                    GraphStateResult.Err("timed out")
                }
            var clock = 1_000L
            val service = service(fileOperations = fileOperationsFor(), stateRunner = stateRunner, nowMillis = { clock })

            assertEquals("timed out", service.status(processInfo(), agent, includeGraph = true)?.error)
            clock += 1_000
            assertEquals("timed out", service.status(processInfo(), agent, includeGraph = true)?.error)
            assertEquals(1, runs)
        }

    @Test
    fun `two owners of the same thread id never share a cached reading`() =
        runBlocking {
            val stateDirs = mutableListOf<String>()
            val stateRunner =
                GraphStateRunner { _, _, _, env, _ ->
                    stateDirs += env.getValue(EnvConstants.LG_STATE_DIR)
                    GraphStateResult.Ok("""{"threadId":"t1","nodes":{"a":"done"},"errors":{}}""")
                }
            // Same instant for both polls: only the differing state dir can force the second run.
            val service = service(fileOperations = fileOperationsFor(), stateRunner = stateRunner, nowMillis = { 1_000L })

            service.status(processInfo(owner = "alice"), agent, includeGraph = true)
            service.status(processInfo(owner = "bob"), agent, includeGraph = true)

            assertEquals(
                listOf(appConfig.resolveLangGraphStateDir("alice"), appConfig.resolveLangGraphStateDir("bob")),
                stateDirs,
            )
        }

    @Test
    fun `a cached state reading still gets live sub progress`() =
        runBlocking {
            val stateRunner =
                GraphStateRunner { _, _, _, _, _ ->
                    GraphStateResult.Ok(
                        """{"threadId":"t1","nodes":{"fan_a":"parked"},"errors":{},"interrupts":{"fan_a":["i1"]}}""",
                    )
                }
            val single =
                Continuation(
                    id = 1,
                    watchedProcessId = 7,
                    status = ContinuationStatus.PENDING,
                    targetAgentName = "parent",
                    params = mapOf(EnvConstants.LG_THREAD to "t1", EnvConstants.LG_INTERRUPT to "i1"),
                    originProcessId = 1,
                    originAgentName = "lg_agent",
                    expiresAt = null,
                    createdAt = 0L,
                    kind = ContinuationKind.SINGLE,
                )
            val continuationRepository = mockk<ContinuationRepository> { coEvery { byThread("t1", null) } returns listOf(single) }
            val processManager =
                mockk<ProcessManager> {
                    coEvery { getSubRunProgress(listOf(7)) } returnsMany
                        listOf(
                            mapOf(7 to subProgress("worker", ProcessStatus.WORKING)),
                            mapOf(7 to subProgress("worker", ProcessStatus.FINISHED)),
                        )
                }
            var clock = 1_000L
            val service =
                service(
                    fileOperations = fileOperationsFor(),
                    stateRunner = stateRunner,
                    continuationRepository = continuationRepository,
                    processManager = processManager,
                    nowMillis = { clock },
                )

            val first = service.status(processInfo(), agent, includeGraph = true)
            clock += 1_000
            val second = service.status(processInfo(), agent, includeGraph = true)

            assertEquals(
                "WORKING",
                first
                    ?.subs
                    ?.get("fan_a")
                    ?.single()
                    ?.status,
            )
            assertEquals(
                "FINISHED",
                second
                    ?.subs
                    ?.get("fan_a")
                    ?.single()
                    ?.status,
            )
        }

    // --- subs against a real database ---

    private class SubsDb {
        val db = PersistenceTestSupport.tempDatabase()
        val continuations = ContinuationRepository(db)
        val processManager = PersistenceTestSupport.processManager(db = db)
    }

    private fun step(
        label: String,
        errMsg: String? = null,
    ) = ExecutionInfo(
        label = label,
        start = 0L,
        end = 1L,
        resultCode = if (errMsg == null) 0 else 1,
        stdOut = null,
        stdErr = null,
        errMsg = errMsg,
    )

    private suspend fun ProcessManager.subRun(
        agentName: String,
        status: ProcessStatus,
        steps: List<ExecutionInfo> = emptyList(),
        running: String? = null,
    ): Int {
        val id = obtainProcessId(agentName)
        setProcessStatus(id, ProcessStatus.WORKING)
        steps.forEach { completeStep(id, it, null, null) }
        running?.let { setCurrentCommand(id, it, 5L) }
        if (status != ProcessStatus.WORKING) setProcessStatus(id, status)
        return id
    }

    private fun lgContinuation(
        params: Map<String, String>,
        watched: Int = 0,
        kind: ContinuationKind = ContinuationKind.SINGLE,
        owner: String? = SUBS_OWNER,
    ) = Continuation(
        id = 0,
        watchedProcessId = watched,
        status = ContinuationStatus.PENDING,
        targetAgentName = "parent",
        params = params,
        originProcessId = 1,
        originAgentName = "lg_agent",
        expiresAt = null,
        createdAt = 0L,
        kind = kind,
        owner = owner,
    )

    private val mixedThreadState =
        """{"threadId":"t1","nodes":{"fan_a":"parked","fan_b":"done"},"errors":{},""" +
            """"interrupts":{"fan_a":["i1","i2","i3"]},"interruptsSeen":{"fan_b":["ghost","i4","i5","i6","i7"]}}"""

    /**
     * Thread t1 with a SINGLE, a PENDING JOIN (watches registered out of pid order, one on an
     * evicted pid) and a DISPATCHED JOIN; thread t2 reusing t1's interrupt id `i1`, plus its own
     * join; and a continuation with no thread at all. Returns the t1 sub pids.
     */
    private suspend fun SubsDb.seedMixedThreads(): List<Int> {
        val s1 =
            processManager.subRun(
                "worker-a",
                ProcessStatus.WORKING,
                listOf(step("setup"), step("lint", "boom")),
                "claude",
            )
        val s2 = processManager.subRun("worker-b", ProcessStatus.FINISHED, listOf(step("setup")))
        val s3 = processManager.subRun("worker-c", ProcessStatus.FAILED)
        val s4 = processManager.subRun("worker-d", ProcessStatus.FINISHED)
        val other1 = processManager.subRun("other-a", ProcessStatus.FINISHED)
        val other2 = processManager.subRun("other-b", ProcessStatus.FINISHED)
        val noThread = processManager.subRun("no-thread", ProcessStatus.FINISHED)
        val t1 = mapOf(EnvConstants.LG_THREAD to "t1")
        continuations.create(lgContinuation(t1 + (EnvConstants.LG_INTERRUPT to "i1"), watched = s1), 100)
        continuations.createJoin(
            lgContinuation(t1 + (EnvConstants.LG_INTERRUPT to "i3,i2,i7"), kind = ContinuationKind.JOIN),
            listOf(JoinWatch(s3, "i3"), JoinWatch(s2, "i2"), JoinWatch(9_999, "i7")),
            100,
        )
        val fired =
            continuations.createJoin(lgContinuation(t1, kind = ContinuationKind.JOIN), listOf(JoinWatch(s4, "i4")), 100)!!
        continuations.claim(fired)
        continuations.markDispatched(fired, processId = 1, at = 1L)
        val t2 = mapOf(EnvConstants.LG_THREAD to "t2")
        continuations.create(lgContinuation(t2 + (EnvConstants.LG_INTERRUPT to "i1"), watched = other1), 100)
        continuations.createJoin(lgContinuation(t2, kind = ContinuationKind.JOIN), listOf(JoinWatch(other2, "i5")), 100)
        continuations.create(lgContinuation(mapOf(EnvConstants.LG_INTERRUPT to "i6"), watched = noThread), 100)
        return listOf(s1, s2, s3, s4)
    }

    private suspend fun SubsDb.status(
        state: String,
        owner: String? = SUBS_OWNER,
    ) = service(
        fileOperations = fileOperationsFor("t1"),
        stateRunner = GraphStateRunner { _, _, _, _, _ -> GraphStateResult.Ok(state) },
        continuationRepository = continuations,
        processManager = processManager,
    ).status(processInfo(owner = owner), agent, includeGraph = true)

    @Test
    fun `subs never includes another thread's continuations, even one reusing an interrupt id`() =
        runBlocking {
            val fixture = SubsDb()
            val (s1, s2, s3, s4) = fixture.seedMixedThreads()

            val result = fixture.status(mixedThreadState)

            assertEquals(
                mapOf(
                    "fan_a" to
                        listOf(
                            LangGraphSubProgress(
                                "i1",
                                s1,
                                "worker-a",
                                "WORKING",
                                commands =
                                    listOf(
                                        LangGraphSubCommand("setup", "ok"),
                                        LangGraphSubCommand("lint", "failed"),
                                        LangGraphSubCommand("claude", "running"),
                                    ),
                            ),
                            LangGraphSubProgress("i2", s2, "worker-b", "FINISHED", commands = listOf(LangGraphSubCommand("setup", "ok"))),
                            LangGraphSubProgress("i3", s3, "worker-c", "FAILED", commands = emptyList()),
                        ),
                    // i5 (t2's join) and i6 (no thread) are not t1's; ghost has no continuation;
                    // i7's sub was evicted and stays listed, unresolved.
                    "fan_b" to
                        listOf(
                            LangGraphSubProgress("i4", s4, "worker-d", "FINISHED", commands = emptyList()),
                            LangGraphSubProgress("i7", 9_999, null, null, commands = null),
                        ),
                ),
                result?.subs,
            )
        }

    @Test
    fun `subs never lists another owner's sub runs, even when the run names that owner's thread and interrupt ids`() =
        runBlocking {
            val fixture = SubsDb()
            // Every continuation on t1 here belongs to SUBS_OWNER.
            fixture.seedMixedThreads()
            val own = fixture.processManager.subRun("mallory-worker", ProcessStatus.FINISHED)
            val mine = mapOf(EnvConstants.LG_THREAD to "t1", EnvConstants.LG_INTERRUPT to "m1")
            fixture.continuations.create(lgContinuation(mine, watched = own, owner = "mallory"), 100)
            // Mallory's run names SUBS_OWNER's thread in its identity artifact, and its checkpoint
            // carries SUBS_OWNER's interrupt ids next to its own.
            val forged = mixedThreadState.replace(""""interruptsSeen":{""", """"interruptsSeen":{"fan_m":["m1"],""")

            val result = fixture.status(forged, owner = "mallory")

            assertEquals(
                mapOf("fan_m" to listOf(LangGraphSubProgress("m1", own, "mallory-worker", "FINISHED", commands = emptyList()))),
                result?.subs,
            )
            // A run with no owner matches only unowned continuations, never everyone's.
            assertEquals(emptyMap(), fixture.status(forged, owner = null)?.subs)
        }

    /** The `subs` before its thread index: every continuation, filtered in memory, and one process read per interrupt. */
    private suspend fun SubsDb.subsTheOldWay(interrupts: Map<String, List<String>>): Map<String, List<LangGraphSubProgress>> {
        val byInterrupt =
            continuations
                .page(null, null, null, null, 0, 1000)
                .items
                .sortedBy { it.id } // the old full list read in id order; a page is newest first
                .filter { it.params[EnvConstants.LG_THREAD] == "t1" }
                .flatMap { c ->
                    when (c.kind) {
                        ContinuationKind.JOIN -> {
                            c.watches.map { it.interruptId to it.watchedProcessId }
                        }

                        ContinuationKind.SINGLE -> {
                            c.params[EnvConstants.LG_INTERRUPT]?.let { listOf(it to c.watchedProcessId) }
                                ?: emptyList()
                        }
                    }
                }.toMap()
        return buildMap {
            for ((node, ids) in interrupts) {
                val nodeSubs =
                    ids.mapNotNull { interruptId ->
                        val pid = byInterrupt[interruptId] ?: return@mapNotNull null
                        val info = processManager.getProcessInfo(pid)
                        val commands =
                            info?.let {
                                it.executions.map { e ->
                                    LangGraphSubCommand(
                                        e.label,
                                        if (e.skipped) {
                                            "skipped"
                                        } else if (e.errMsg == null) {
                                            "ok"
                                        } else {
                                            "failed"
                                        },
                                    )
                                } + listOfNotNull(it.currentCommand?.let { c -> LangGraphSubCommand(c, "running") })
                            }
                        LangGraphSubProgress(interruptId, pid, info?.agentName, info?.status?.name, commands = commands)
                    }
                if (nodeSubs.isNotEmpty()) put(node, nodeSubs)
            }
        }
    }

    @Test
    fun `subs returns exactly what the pre-index lookup returned for a thread with SINGLE and JOIN continuations`() =
        runBlocking {
            val fixture = SubsDb()
            fixture.seedMixedThreads()
            // Two t1 continuations registered under one interrupt id: the old full read met them in
            // rowid order, so the later registration won.
            val first = fixture.processManager.subRun("dup-first", ProcessStatus.FINISHED)
            val later = fixture.processManager.subRun("dup-later", ProcessStatus.FINISHED)
            val i8 = mapOf(EnvConstants.LG_THREAD to "t1", EnvConstants.LG_INTERRUPT to "i8")
            fixture.continuations.create(lgContinuation(i8, watched = first), 100)
            fixture.continuations.create(lgContinuation(i8, watched = later), 100)

            val result = fixture.status(mixedThreadState.replace(""""interruptsSeen":{""", """"interruptsSeen":{"fan_c":["i8"],"""))

            val expected =
                fixture.subsTheOldWay(
                    mapOf(
                        "fan_a" to listOf("i1", "i2", "i3"),
                        "fan_b" to listOf("ghost", "i4", "i5", "i6", "i7"),
                        "fan_c" to listOf("i8"),
                    ),
                )
            assertEquals(expected, result?.subs)
            assertEquals(listOf(3, 2, 1), expected.values.map { it.size })
            assertEquals(later, expected["fan_c"]?.single()?.processId, "the old lookup's last-wins winner")
        }

    /** SELECTs one `subs` call issues for a t1 run fanned out to [subRuns] subs, half SINGLE, half in one JOIN. */
    private suspend fun subsSelects(subRuns: Int): List<String> {
        val fixture = SubsDb()
        val t1 = mapOf(EnvConstants.LG_THREAD to "t1")
        val watches = mutableListOf<JoinWatch>()
        repeat(subRuns) { i ->
            val pid = fixture.processManager.subRun("worker-$i", ProcessStatus.FINISHED, listOf(step("s")))
            if (i % 2 == 0) {
                fixture.continuations.create(lgContinuation(t1 + (EnvConstants.LG_INTERRUPT to "i$i"), watched = pid), 1_000)
            } else {
                watches += JoinWatch(pid, "i$i")
            }
            // Another thread's traffic must not cost this thread anything either.
            fixture.continuations.create(lgContinuation(mapOf(EnvConstants.LG_THREAD to "t-other-$i"), watched = pid), 1_000)
        }
        fixture.continuations.createJoin(lgContinuation(t1, kind = ContinuationKind.JOIN), watches, 1_000)
        val ids = (0 until subRuns).joinToString(",") { "\"i$it\"" }
        val state = """{"threadId":"t1","nodes":{"fan":"parked"},"errors":{},"interrupts":{"fan":[$ids]}}"""
        SqlStatementRecorder.reset()
        val result = fixture.status(state)
        val selects = SqlStatementRecorder.statements().filter { it.startsWith("SELECT") }
        assertEquals(subRuns, result?.subs?.get("fan")?.size)
        return selects
    }

    @Test
    fun `one subs call issues the same number of statements however many sub runs it resolves, below the chunk size`() =
        runBlocking {
            val few = subsSelects(2)
            val many = subsSelects(40)
            assertEquals(few.size, many.size, "few: $few\nmany: $many")
            // The thread's continuations, their join watches, and one batched sub-run read.
            assertEquals(3, many.size, "$many")
        }
}
