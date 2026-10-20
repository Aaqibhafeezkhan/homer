package homer.app.fixtures

import homer.app.infra.AgentInputResolver
import homer.app.infra.ProcessJobRegistry
import homer.app.models.DispatchConfig
import homer.app.models.QuestionsConfig
import homer.app.services.AgentRunStarter
import homer.app.services.AgentsService
import homer.app.services.Authorizer
import homer.app.services.ContinuationService
import homer.app.services.DispatchService
import homer.app.services.ProcessEventBroadcaster
import homer.app.services.ProcessManager
import homer.app.services.QuestionsService
import homer.app.services.ResultTokenService
import homer.app.services.persistence.ContinuationRepository
import homer.app.services.persistence.DatabaseService
import homer.app.services.persistence.DispatchRepository
import homer.app.services.persistence.ProcessRepository
import homer.app.services.persistence.QuestionsRepository
import homer.app.services.persistence.ResultTokenRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.slf4j.Logger
import java.nio.file.Files

/**
 * Shared helpers for tests that need a real SQLite-backed persistence layer.
 * Each [tempDatabase] call creates an isolated on-disk database (deleted on JVM
 * exit) so durability round-trips exercise the same engine production uses. A
 * temp file — not `:memory:` — because Exposed opens a connection per
 * transaction and a plain in-memory URL gives each connection its own empty db.
 *
 * The default dispatcher is [Dispatchers.Unconfined] so DB calls run inline on
 * the caller's thread: this keeps `runTest` virtual-time and `yield()`-ordering
 * tests deterministic (no real thread hop). Concurrency tests that need genuine
 * parallel writers pass [Dispatchers.IO] explicitly.
 */
object PersistenceTestSupport {
    fun tempDatabasePath(): String =
        Files
            .createTempFile("homer-test-", ".db")
            .also { it.toFile().deleteOnExit() }
            .toString()

    fun tempDatabase(
        path: String = tempDatabasePath(),
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): DatabaseService = DatabaseService(path, TestFixtures.mockLogger(), dispatcher).also { it.migrate() }

    /** A [ProcessManager] backed by a fresh isolated SQLite database. */
    fun processManager(
        logger: Logger = TestFixtures.mockLogger(),
        broadcaster: ProcessEventBroadcaster = ProcessEventBroadcaster(),
        maxProcesses: Int = ProcessManager.DEFAULT_MAX_PROCESSES,
        onProcessEvicted: suspend (Int) -> Unit = {},
        db: DatabaseService = tempDatabase(),
    ): ProcessManager {
        val registry = ProcessJobRegistry()
        return ProcessManager(
            ProcessRepository(db, registry),
            registry,
            logger,
            broadcaster,
            maxProcesses,
            onProcessEvicted = onProcessEvicted,
        )
    }

    /** A [QuestionsService] backed by a fresh isolated SQLite database. */
    fun questionsService(
        logger: Logger = TestFixtures.mockLogger(),
        broadcaster: ProcessEventBroadcaster = ProcessEventBroadcaster(),
        config: QuestionsConfig = QuestionsConfig(),
        db: DatabaseService = tempDatabase(),
    ): QuestionsService = QuestionsService(QuestionsRepository(db), logger, broadcaster, config)

    /**
     * A [DispatchService] sharing [db] with [questionsService] so the question +
     * dispatch rows persist in the same database. [agentsService], [runStarter]
     * and [inputResolver] are caller-supplied mocks (the run start is the heavy
     * dependency a route test stubs out).
     */
    internal fun dispatchService(
        db: DatabaseService,
        questionsService: QuestionsService,
        agentsService: AgentsService,
        runStarter: AgentRunStarter,
        inputResolver: AgentInputResolver,
        processManager: ProcessManager = processManager(db = db),
        broadcaster: ProcessEventBroadcaster = ProcessEventBroadcaster(),
        config: DispatchConfig = DispatchConfig(),
        logger: Logger = TestFixtures.mockLogger(),
        authorizer: Authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
    ): DispatchService =
        DispatchService(
            DispatchRepository(db),
            questionsService,
            agentsService,
            authorizer,
            runStarter,
            inputResolver,
            processManager,
            broadcaster,
            logger,
            config,
        )

    /**
     * A [ContinuationService] sharing [db] with [questionsService]. Like
     * [dispatchService], the heavy run-start collaborators are caller-supplied
     * mocks; [processManager] backs the already-terminal race re-check.
     */
    internal fun continuationService(
        db: DatabaseService,
        questionsService: QuestionsService,
        agentsService: AgentsService,
        runStarter: AgentRunStarter,
        inputResolver: AgentInputResolver,
        processManager: ProcessManager,
        config: DispatchConfig = DispatchConfig(),
        logger: Logger = TestFixtures.mockLogger(),
        authorizer: Authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
    ): ContinuationService =
        ContinuationService(
            ContinuationRepository(db),
            questionsService,
            agentsService,
            authorizer,
            runStarter,
            inputResolver,
            processManager,
            ResultTokenService(ResultTokenRepository(db), logger, config),
            logger,
            config,
        )
}

/** Every question matching [status] and [processId], unpaged: a test helper over [QuestionsService.page]. */
suspend fun QuestionsService.all(
    status: QuestionsService.QuestionStatus? = null,
    processId: Int? = null,
): List<QuestionsService.Question> = page(status, processId, visibleOwner = null, visibleAgents = null, offset = 0, limit = 1000).items
