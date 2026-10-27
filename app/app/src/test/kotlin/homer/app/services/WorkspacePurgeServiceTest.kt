package homer.app.services

import homer.app.executors.RunMessageExpiry
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.TestFixtures
import homer.app.infra.ProcessJobRegistry
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.models.Retain
import homer.app.services.persistence.ProcessRepository
import homer.app.services.persistence.QuestionsRepository
import homer.app.services.persistence.setPurgeAt
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspacePurgeServiceTest {
    private lateinit var processManager: ProcessManager
    private lateinit var questionsService: QuestionsService
    private lateinit var service: WorkspacePurgeService
    private val now = System.currentTimeMillis() / 1000

    @BeforeEach
    fun setup() {
        processManager = mockk(relaxed = true)
        questionsService = mockk(relaxed = true)
        service =
            WorkspacePurgeService(
                processManager = processManager,
                questionsService = questionsService,
                // The existing tests create their temp workspaces under the JVM temp dir,
                // so root the purge there; the containment test uses its own narrow root.
                workspaceRoot = Path.of(System.getProperty("java.io.tmpdir")),
                maxRetainedArtifactBytes = 16L * 1024 * 1024,
                logger = TestFixtures.mockLogger(),
            )
        // Two sweeps now: one for the directory, one for the row. Default both to
        // empty so each test states exactly which phase it is exercising.
        coEvery { processManager.getWorkspacePurgeableProcesses(any(), any()) } returns emptyList()
        coEvery { processManager.getRecordPurgeableProcesses(any(), any()) } returns emptyList()
    }

    @Test
    fun `purgeExpired removes process and workspace when purgeAt has passed`() =
        runTest {
            val tmpDir = Files.createTempDirectory("homer-test")
            val artifactsDir = tmpDir.resolve("artifacts")
            artifactsDir.toFile().mkdirs()
            artifactsDir.resolve("cmd_0.out").toFile().createNewFile()

            val process =
                ProcessInfo(
                    id = 1,
                    status = ProcessStatus.FINISHED,
                    agentName = "test",
                    purgeAt = now - 1,
                    artifactsDir = artifactsDir,
                )

            // The status/purgeAt filter lives in the SQL queries (ProcessRepository,
            // covered by ProcessRepositoryTest); the service purges what they return.
            // Default retention: both windows fall due together.
            coEvery { processManager.getWorkspacePurgeableProcesses(any(), any()) } returns listOf(process)
            coEvery { processManager.getRecordPurgeableProcesses(any(), any()) } returns listOf(process)

            service.purgeExpired()

            coVerify { processManager.removeProcess(1) }
            coVerify { questionsService.removeForProcess(1) }
            assertFalse(tmpDir.toFile().exists(), "projectRoot should be deleted")
        }

    @Test
    fun `purgeExpired deletes a projectRoot whose workdir the bounded cleanup left behind`() =
        runTest {
            // The end-of-run cleanup stops at its budget and leaves a partial workdir; the
            // purgeAfter sweep is the safety net that removes the whole projectRoot off the
            // lock path. No WorkspacePurgeService change is needed for that.
            val tmpDir = Files.createTempDirectory("homer-test")
            val artifactsDir = tmpDir.resolve("artifacts")
            artifactsDir.toFile().mkdirs()
            tmpDir.resolve("workdir").resolve("leftover.txt").toFile().apply {
                parentFile.mkdirs()
                createNewFile()
            }

            val process =
                ProcessInfo(
                    id = 1,
                    status = ProcessStatus.FINISHED,
                    agentName = "test",
                    purgeAt = now - 1,
                    artifactsDir = artifactsDir,
                )
            coEvery { processManager.getWorkspacePurgeableProcesses(any(), any()) } returns listOf(process)
            coEvery { processManager.getRecordPurgeableProcesses(any(), any()) } returns listOf(process)

            service.purgeExpired()

            assertFalse(tmpDir.toFile().exists(), "projectRoot with a leftover workdir should be deleted")
        }

    @Test
    fun `purgeExpired does nothing when no processes are due`() =
        runTest {
            service.purgeExpired()

            coVerify(exactly = 0) { processManager.removeProcess(any()) }
            coVerify(exactly = 0) { questionsService.removeForProcess(any()) }
        }

    @Test
    fun `a workspace sweep cancelled mid-row stops instead of moving to the next row`() =
        runTest {
            val first =
                ProcessInfo(id = 1, status = ProcessStatus.FINISHED, agentName = "a", artifactsDir = Path.of("/nowhere/1/artifacts"))
            val second = first.copy(id = 2, artifactsDir = Path.of("/nowhere/2/artifacts"))
            coEvery { processManager.getWorkspacePurgeableProcesses(any(), any()) } returns listOf(first, second)
            coEvery { processManager.isArtifactsDirShared(any(), 1) } throws CancellationException("stopped")

            assertFailsWith<CancellationException> { service.purgeExpired() }

            coVerify(exactly = 0) { processManager.isArtifactsDirShared(any(), 2) }
            coVerify(exactly = 0) { processManager.getRecordPurgeableProcesses(any(), any()) }
        }

    @Test
    fun `a record sweep cancelled mid-row stops instead of moving to the next row`() =
        runTest {
            val first = ProcessInfo(id = 1, status = ProcessStatus.FINISHED, agentName = "a")
            coEvery { processManager.getRecordPurgeableProcesses(any(), any()) } returns listOf(first, first.copy(id = 2))
            coEvery { processManager.removeProcess(1) } throws CancellationException("stopped")

            assertFailsWith<CancellationException> { service.purgeExpired() }

            coVerify(exactly = 0) { processManager.removeProcess(2) }
        }

    @Test
    fun `purgeExpired keeps the workspace dir when another record references it`() =
        runTest {
            val tmpDir = Files.createTempDirectory("homer-test")
            val artifactsDir = tmpDir.resolve("artifacts")
            artifactsDir.toFile().mkdirs()

            val process =
                ProcessInfo(
                    id = 1,
                    status = ProcessStatus.FINISHED,
                    agentName = "improver",
                    purgeAt = now - 1,
                    artifactsDir = artifactsDir,
                )
            coEvery { processManager.getWorkspacePurgeableProcesses(any(), any()) } returns listOf(process)
            coEvery { processManager.getRecordPurgeableProcesses(any(), any()) } returns listOf(process)
            coEvery { processManager.isArtifactsDirShared(artifactsDir, 1) } returns true

            service.purgeExpired()

            coVerify { processManager.removeProcess(1) }
            coVerify { questionsService.removeForProcess(1) }
            assertTrue(tmpDir.toFile().exists(), "shared workspace dir must NOT be deleted")
            tmpDir.toFile().deleteRecursively()
        }

    @Test
    fun `purgeExpired keeps the workspace dir when an active process references it`() =
        runTest {
            // Same mechanism as above — an active (CREATED/WORKING) run is just another
            // row referencing the dir; this documents the running-process case (42260).
            val tmpDir = Files.createTempDirectory("homer-test")
            val artifactsDir = tmpDir.resolve("artifacts")
            artifactsDir.toFile().mkdirs()
            artifactsDir.resolve("state.json").toFile().createNewFile()

            val expired =
                ProcessInfo(
                    id = 33502,
                    status = ProcessStatus.FINISHED,
                    agentName = "mobile-improver",
                    purgeAt = now - 1,
                    artifactsDir = artifactsDir,
                )
            coEvery { processManager.getWorkspacePurgeableProcesses(any(), any()) } returns listOf(expired)
            coEvery { processManager.getRecordPurgeableProcesses(any(), any()) } returns listOf(expired)
            coEvery { processManager.isArtifactsDirShared(artifactsDir, 33502) } returns true

            service.purgeExpired()

            coVerify { processManager.removeProcess(33502) }
            assertTrue(artifactsDir.resolve("state.json").toFile().exists(), "live run state must survive purge")
            tmpDir.toFile().deleteRecursively()
        }

    @Test
    fun `purgeExpired deletes a dir shared by two expired records exactly when the last one is purged`() =
        runTest {
            // Pins the interleaving of the shared-dir check with the per-row clear: the
            // dir must survive the first record's sweep (the second still references it)
            // and be deleted exactly when the second one is swept. A future reorder that
            // batched the clears after all the checks would regress this silently, since
            // both checks would then see both ids still referencing the dir.
            val tmpDir = Files.createTempDirectory("homer-test")
            val artifactsDir = tmpDir.resolve("artifacts")
            artifactsDir.toFile().mkdirs()

            val liveIds = mutableSetOf(1, 2)
            val processA =
                ProcessInfo(
                    id = 1,
                    status = ProcessStatus.FINISHED,
                    agentName = "test",
                    purgeAt = now - 1,
                    artifactsDir = artifactsDir,
                )
            val processB =
                ProcessInfo(
                    id = 2,
                    status = ProcessStatus.FINISHED,
                    agentName = "test",
                    purgeAt = now - 1,
                    artifactsDir = artifactsDir,
                )
            coEvery { processManager.getWorkspacePurgeableProcesses(any(), any()) } returns listOf(processA, processB)
            coEvery { processManager.isArtifactsDirShared(artifactsDir, any()) } answers {
                val excludeId = secondArg<Int>()
                liveIds.any { it != excludeId }
            }
            coEvery { processManager.clearArtifactsDir(any()) } answers {
                liveIds.remove(firstArg<Int>())
            }

            service.purgeExpired()

            coVerify { processManager.clearArtifactsDir(1) }
            coVerify { processManager.clearArtifactsDir(2) }
            assertFalse(tmpDir.toFile().exists(), "shared dir must be deleted once the last referencing record is purged")
        }

    @Test
    fun `purgeExpired refuses to delete a workspace resolving outside the workspace root`() =
        runTest {
            val root = Files.createTempDirectory("homer-root")
            val outside = Files.createTempDirectory("homer-outside")
            val artifactsDir = outside.resolve("artifacts")
            artifactsDir.toFile().mkdirs()
            artifactsDir.resolve("keep.txt").toFile().createNewFile()

            // A purge service whose configured root does NOT contain the (poisoned) dir.
            val narrowService =
                WorkspacePurgeService(processManager, questionsService, root, 16L * 1024 * 1024, TestFixtures.mockLogger())
            val process =
                ProcessInfo(
                    id = 7,
                    status = ProcessStatus.FINISHED,
                    agentName = "test",
                    purgeAt = now - 1,
                    artifactsDir = artifactsDir,
                )
            coEvery { processManager.getWorkspacePurgeableProcesses(any(), any()) } returns listOf(process)
            coEvery { processManager.isArtifactsDirShared(artifactsDir, 7) } returns false

            narrowService.purgeExpired()

            // Containment guard blocks the delete; the dir survives, artifacts_dir stays
            // set (so the next sweep retries), and the record-purge query — which requires
            // artifacts_dir IS NULL — never returns the row, so it is not removed either.
            assertTrue(outside.toFile().exists(), "an out-of-root workspace must not be deleted")
            coVerify(exactly = 0) { processManager.clearArtifactsDir(7) }
            coVerify(exactly = 0) { processManager.removeProcess(7) }
            outside.toFile().deleteRecursively()
            root.toFile().deleteRecursively()
        }

    @Test
    fun `purgeExpired handles null artifactsDir without throwing`() =
        runTest {
            val process =
                ProcessInfo(
                    id = 5,
                    status = ProcessStatus.KILLED,
                    agentName = "test",
                    purgeAt = now - 1,
                    artifactsDir = null,
                )

            // A row with no directory is invisible to the workspace query (artifacts_dir
            // IS NOT NULL) and is picked up by the record query alone.
            coEvery { processManager.getRecordPurgeableProcesses(any(), any()) } returns listOf(process)

            service.purgeExpired()

            coVerify { processManager.removeProcess(5) }
        }

    @Test
    fun `purgeExpired deletes the workspace but keeps the record when its window is longer`() =
        runTest {
            // The reason the split exists: /processes/{id} is pasted into tickets and merge
            // requests, and those comments outlive any sensible artifact retention.
            val tmpDir = Files.createTempDirectory("homer-test")
            val artifactsDir = tmpDir.resolve("artifacts")
            artifactsDir.toFile().mkdirs()
            artifactsDir.resolve("cmd_0.out").toFile().createNewFile()

            val process =
                ProcessInfo(
                    id = 9,
                    status = ProcessStatus.FINISHED,
                    agentName = "test",
                    purgeAt = now - 1,
                    artifactsDir = artifactsDir,
                )
            // Directory due, record not: only the workspace query returns it.
            coEvery { processManager.getWorkspacePurgeableProcesses(any(), any()) } returns listOf(process)

            service.purgeExpired()

            assertFalse(tmpDir.toFile().exists(), "the workspace should be gone")
            coVerify { processManager.clearArtifactsDir(9) }
            coVerify(exactly = 0) { processManager.removeProcess(9) }
            coVerify(exactly = 0) { questionsService.removeForProcess(9) }
        }

    @Test
    fun `workspace sweep prunes to retained globs and marks the row instead of clearing artifactsDir`() =
        runTest {
            val projectRoot = Files.createTempDirectory("homer-test")
            val workDir = projectRoot.resolve("workdir")
            workDir.toFile().mkdirs()
            val artifactsDir = projectRoot.resolve("artifacts")
            artifactsDir.toFile().mkdirs()
            artifactsDir.resolve("decision.json").toFile().createNewFile()
            artifactsDir.resolve("cmd_0.out").toFile().createNewFile()

            val process =
                ProcessInfo(
                    id = 21,
                    status = ProcessStatus.FINISHED,
                    agentName = "test",
                    purgeAt = now - 1,
                    artifactsDir = artifactsDir,
                    retainArtifacts = listOf("decision.json"),
                )
            coEvery { processManager.getWorkspacePurgeableProcesses(any(), any()) } returns listOf(process)

            service.purgeExpired()

            assertTrue(artifactsDir.resolve("decision.json").toFile().exists(), "retained artifact should survive")
            assertFalse(artifactsDir.resolve("cmd_0.out").toFile().exists(), "non-retained file should be gone")
            assertFalse(workDir.toFile().exists(), "workdir should be gone")
            coVerify { processManager.markWorkspacePurged(21, false) }
            coVerify(exactly = 0) { processManager.clearArtifactsDir(21) }
        }

    @Test
    fun `workspace sweep falls back to full delete when no retained file matches`() =
        runTest {
            val projectRoot = Files.createTempDirectory("homer-test")
            val artifactsDir = projectRoot.resolve("artifacts")
            artifactsDir.toFile().mkdirs()
            artifactsDir.resolve("cmd_0.out").toFile().createNewFile()

            val process =
                ProcessInfo(
                    id = 22,
                    status = ProcessStatus.FINISHED,
                    agentName = "test",
                    purgeAt = now - 1,
                    artifactsDir = artifactsDir,
                    retainArtifacts = listOf("absent.json"),
                )
            coEvery { processManager.getWorkspacePurgeableProcesses(any(), any()) } returns listOf(process)

            service.purgeExpired()

            assertFalse(projectRoot.toFile().exists(), "projectRoot should be deleted when nothing matched")
            coVerify { processManager.clearArtifactsDir(22) }
            coVerify(exactly = 0) { processManager.markWorkspacePurged(any(), any()) }
        }

    @Test
    fun `retention is skipped with a warning for a shared workspace`() =
        runTest {
            val projectRoot = Files.createTempDirectory("homer-test")
            val artifactsDir = projectRoot.resolve("artifacts")
            artifactsDir.toFile().mkdirs()
            artifactsDir.resolve("decision.json").toFile().createNewFile()

            val process =
                ProcessInfo(
                    id = 23,
                    status = ProcessStatus.FINISHED,
                    agentName = "test",
                    purgeAt = now - 1,
                    artifactsDir = artifactsDir,
                    retainArtifacts = listOf("decision.json"),
                )
            coEvery { processManager.getWorkspacePurgeableProcesses(any(), any()) } returns listOf(process)
            coEvery { processManager.isArtifactsDirShared(artifactsDir, 23) } returns true

            service.purgeExpired()

            assertTrue(artifactsDir.resolve("decision.json").toFile().exists(), "shared workspace dir must NOT be touched")
            coVerify { processManager.clearArtifactsDir(23) }
            coVerify(exactly = 0) { processManager.markWorkspacePurged(any(), any()) }
            projectRoot.toFile().deleteRecursively()
        }

    @Test
    fun `cap overflow marks the row truncated`() =
        runTest {
            val projectRoot = Files.createTempDirectory("homer-test")
            val artifactsDir = projectRoot.resolve("artifacts")
            artifactsDir.toFile().mkdirs()
            Files.write(artifactsDir.resolve("a.json"), ByteArray(10))
            Files.write(artifactsDir.resolve("b.json"), ByteArray(10))

            val tinyService =
                WorkspacePurgeService(
                    processManager = processManager,
                    questionsService = questionsService,
                    workspaceRoot = Path.of(System.getProperty("java.io.tmpdir")),
                    maxRetainedArtifactBytes = 10L,
                    logger = TestFixtures.mockLogger(),
                )
            val process =
                ProcessInfo(
                    id = 24,
                    status = ProcessStatus.FINISHED,
                    agentName = "test",
                    purgeAt = now - 1,
                    artifactsDir = artifactsDir,
                    retainArtifacts = listOf("a.json", "b.json"),
                )
            coEvery { processManager.getWorkspacePurgeableProcesses(any(), any()) } returns listOf(process)

            tinyService.purgeExpired()

            coVerify { processManager.markWorkspacePurged(24, true) }
        }

    @Test
    fun `everything-truncated prune still records retainedTruncated`() =
        runTest {
            // Every matched file overflows the cap: keptFiles == 0, but the prune WAS
            // truncated. The sweep must not fall through to the "nothing matched"
            // clear-without-a-flag path — the flag would silently stay NULL forever.
            val projectRoot = Files.createTempDirectory("homer-test")
            val artifactsDir = projectRoot.resolve("artifacts")
            artifactsDir.toFile().mkdirs()
            Files.write(artifactsDir.resolve("a.json"), ByteArray(10))

            val tinyService =
                WorkspacePurgeService(
                    processManager = processManager,
                    questionsService = questionsService,
                    workspaceRoot = Path.of(System.getProperty("java.io.tmpdir")),
                    maxRetainedArtifactBytes = 1L,
                    logger = TestFixtures.mockLogger(),
                )
            val process =
                ProcessInfo(
                    id = 26,
                    status = ProcessStatus.FINISHED,
                    agentName = "test",
                    purgeAt = now - 1,
                    artifactsDir = artifactsDir,
                    retainArtifacts = listOf("a.json"),
                )
            coEvery { processManager.getWorkspacePurgeableProcesses(any(), any()) } returns listOf(process)

            tinyService.purgeExpired()

            assertFalse(projectRoot.toFile().exists(), "projectRoot should be deleted when nothing fit under the cap")
            coVerify { processManager.clearArtifactsDir(26, retainedTruncated = true) }
            coVerify(exactly = 0) { processManager.markWorkspacePurged(any(), any()) }
        }

    @Test
    fun `record sweep deletes retained leftovers before removing the row`() =
        runTest {
            val projectRoot = Files.createTempDirectory("homer-test")
            val artifactsDir = projectRoot.resolve("artifacts")
            artifactsDir.toFile().mkdirs()
            artifactsDir.resolve("decision.json").toFile().createNewFile()

            val process =
                ProcessInfo(
                    id = 25,
                    status = ProcessStatus.FINISHED,
                    agentName = "test",
                    purgeAt = now - 1,
                    artifactsDir = artifactsDir,
                    retainArtifacts = listOf("decision.json"),
                )
            coEvery { processManager.getRecordPurgeableProcesses(any(), any()) } returns listOf(process)

            service.purgeExpired()

            assertFalse(projectRoot.toFile().exists(), "leftover retained dir should be gone")
            coVerify { processManager.removeProcess(25) }
            coVerify { questionsService.removeForProcess(25) }
        }

    @Test
    fun `record sweep keeps the row when the leftover delete fails`() =
        runTest {
            val root = Files.createTempDirectory("homer-root")
            val outside = Files.createTempDirectory("homer-outside")
            val artifactsDir = outside.resolve("artifacts")
            artifactsDir.toFile().mkdirs()
            artifactsDir.resolve("decision.json").toFile().createNewFile()

            val narrowService =
                WorkspacePurgeService(
                    processManager,
                    questionsService,
                    root,
                    16L * 1024 * 1024,
                    TestFixtures.mockLogger(),
                )
            val process =
                ProcessInfo(
                    id = 26,
                    status = ProcessStatus.FINISHED,
                    agentName = "test",
                    purgeAt = now - 1,
                    artifactsDir = artifactsDir,
                    retainArtifacts = listOf("decision.json"),
                )
            coEvery { processManager.getRecordPurgeableProcesses(any(), any()) } returns listOf(process)

            narrowService.purgeExpired()

            coVerify(exactly = 0) { processManager.removeProcess(26) }
            outside.toFile().deleteRecursively()
            root.toFile().deleteRecursively()
        }

    @Test
    fun `a run left UNKNOWN by a restart is swept on its agent's schedule, counted from the restart`() =
        runTest {
            // Real SQLite end to end: the status and purge_at filters live in the repository
            // queries, so a mocked ProcessManager could not show an UNKNOWN row being picked up.
            val root = Files.createTempDirectory("homer-root")
            val db = PersistenceTestSupport.tempDatabase()
            val repository = ProcessRepository(db, ProcessJobRegistry())
            val realQuestions = QuestionsService(QuestionsRepository(db), TestFixtures.mockLogger(), ProcessEventBroadcaster())
            val realManager = ProcessManager(repository, ProcessJobRegistry(), TestFixtures.mockLogger(), workspaceRoot = root)
            val purge = WorkspacePurgeService(realManager, realQuestions, root, 16L * 1024 * 1024, TestFixtures.mockLogger())
            val base = AgentFixtures.simpleAgent(name = "keeper")
            val keeper =
                base.copy(
                    workspace =
                        base.workspace.copy(
                            purgeAfter = "1d",
                            retain = Retain(recordFor = "7d", artifacts = listOf("decision.json")),
                        ),
                )
            val agentsService = mockk<AgentsService> { every { agents } returns mapOf("keeper" to keeper) }
            val day = 86_400L

            fun interruptedRunDir(id: Int) =
                root.resolve("$id").resolve("artifacts").also {
                    it.toFile().mkdirs()
                    it.resolve("decision.json").toFile().createNewFile()
                    it.resolve("cmd_0.out").toFile().createNewFile()
                }

            // keeper: purgeAfter 1d; "default-agent" is not loaded, so Workspace() defaults (5d) apply.
            val due = repository.obtainProcessId("keeper", emptyMap(), 100).processId
            val dueDir = interruptedRunDir(due)
            realManager.setArtifactsDir(due, dueDir)
            repository.transitionStatus(due, ProcessStatus.WORKING, 1, null)
            val notDue = repository.obtainProcessId("default-agent", emptyMap(), 100).processId
            val notDueDir = interruptedRunDir(notDue)
            realManager.setArtifactsDir(notDue, notDueDir)
            repository.transitionStatus(notDue, ProcessStatus.WORKING, 1, null)

            // The restart happened two days ago: keeper's 1d workspace window has passed,
            // its 7d record window and the default 5d window have not.
            StartupReconciliation(
                repository,
                realQuestions,
                mockk<RunMessageExpiry>(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                agentsService,
                TestFixtures.mockLogger(),
            ) { now - 2 * day }.reconcile()

            purge.purgeExpired()

            assertTrue(dueDir.resolve("decision.json").toFile().exists(), "retained artifact must survive the workspace sweep")
            assertFalse(dueDir.resolve("cmd_0.out").toFile().exists(), "non-retained file must be swept")
            assertNotNull(realManager.getProcessInfo(due), "record window (7d) has not passed")
            assertTrue(notDueDir.resolve("cmd_0.out").toFile().exists(), "default 5d window has not passed")
            assertEquals(notDueDir, realManager.getProcessInfo(notDue)?.artifactsDir)

            // Time passes beyond keeper's record window.
            repository.setPurgeAt(due, purgeAt = now - day, recordPurgeAt = now - 1, retainArtifacts = listOf("decision.json"))
            purge.purgeExpired()

            assertNull(realManager.getProcessInfo(due), "record must be purged once its window passes")
            assertFalse(root.resolve("$due").toFile().exists(), "retained leftovers die with the record")
            assertNotNull(realManager.getProcessInfo(notDue))
            root.toFile().deleteRecursively()
        }
}
