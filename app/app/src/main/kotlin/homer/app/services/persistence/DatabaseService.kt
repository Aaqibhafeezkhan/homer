package homer.app.services.persistence

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.Logger
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.io.PrintWriter
import java.sql.Connection
import javax.sql.DataSource
import java.util.logging.Logger as JulLogger

/**
 * Opens (creating if absent) the SQLite database backing the orchestrator's
 * durable state and owns the Exposed [Database] handle. SQLite is the single
 * source of truth — there is no in-memory cache of durable state. See ADR-0020.
 *
 * Pragmas are set on the [SQLiteConfig] so the driver applies them to every
 * connection Exposed opens:
 * - `journal_mode=WAL` — readers don't block the single writer.
 * - `busy_timeout=5000` — a writer waiting on the write lock retries for 5 s
 *   instead of failing immediately with `SQLITE_BUSY`.
 * - `transaction_mode=IMMEDIATE` — every transaction begins with
 *   `BEGIN IMMEDIATE`, taking the write lock up front. This is load-bearing:
 *   under WAL a DEFERRED read-then-write (the JSON-column read-modify-write in
 *   the repositories) that races another writer fails with the non-retryable
 *   `SQLITE_BUSY_SNAPSHOT`; IMMEDIATE serializes such transactions through the
 *   write lock instead, which is what replaces the old coroutine `Mutex`.
 *
 * No `foreign_keys` pragma is set: no table declares a foreign key, and
 * referential integrity is maintained in code instead — co-transactional
 * deletes, existence-guarded inserts, and plain-reference ids that may outlive
 * their parent (ADR-0048, ADR-0058, ADR-0060).
 *
 * The writer handle runs single-attempt: its [DatabaseConfig] sets
 * [DatabaseConfig.defaultMaxAttempts] to `1`. Exposed re-runs a transaction up
 * to `maxAttempts` times whatever throws `SQLException` — including a
 * `commit()` that failed *after* the data landed, which sqlite-jdbc can raise
 * from the re-`BEGIN IMMEDIATE` it issues inside `commit()`. Every write block
 * on this handle is a read-modify-write append that a replay would duplicate,
 * so the retry is disabled rather than made safe per block. See ADR-0020.
 *
 * A transaction that writes goes through [query], which ends the driver's
 * post-commit re-`BEGIN` so the write lock ends with the transaction, not with
 * Exposed's connection teardown ([BounceWriterToAutoCommit]). A transaction that only
 * reads goes through [read]: a second handle whose transactions begin DEFERRED
 * under `query_only`, so under WAL they neither take nor wait for the write
 * lock, and which keeps Exposed's default retries — a read block is safely
 * re-runnable. Both run the blocking JDBC transaction on [Dispatchers.IO]. See
 * the 2026-09-29 amendments to ADR-0020.
 */
class DatabaseService(
    private val databasePath: String,
    private val logger: Logger,
    // Blocking JDBC runs here, off the main dispatcher. Overridable in tests
    // (e.g. an unconfined/inline dispatcher) so virtual-time and ordering-based
    // tests stay deterministic.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // Wraps the writer handle's JDBC connections. Identity in production; a test
    // substitutes a proxy that throws from `commit()` after it has landed, to
    // drive the post-commit failure through the real [query] path. Wrapping has
    // to happen at the DataSource: Exposed discards the `setupConnection` hook's
    // return value, so that hook cannot make its wrapper the transaction's
    // connection.
    private val wrapWriterConnection: (Connection) -> Connection = IDENTITY_CONNECTION_WRAPPER,
    // The writer handle's retry policy. Production always uses the companion's
    // single-attempt default; a test substitutes Exposed's multi-attempt default
    // to show the replay this ticket is about.
    private val writerDatabaseConfig: DatabaseConfig = WRITER_DATABASE_CONFIG,
) {
    val database: Database by lazy { open() }

    @Volatile
    private var opened = false

    private fun open(): Database {
        val dataSource = writerDataSource()
        val db =
            Database.connect(
                if (wrapWriterConnection === IDENTITY_CONNECTION_WRAPPER) {
                    dataSource
                } else {
                    ConnectionWrappingDataSource(dataSource, wrapWriterConnection)
                },
                databaseConfig = writerDatabaseConfig,
            )
        opened = true
        logger.info("Opened SQLite database at {}", databasePath)
        return db
    }

    /** The writer handle's [DataSource]: the connection pragmas above, over the database file. */
    private fun writerDataSource(): DataSource =
        SQLiteDataSource(writerConfig()).apply {
            url = "jdbc:sqlite:$databasePath"
        }

    /** The write handle's pragmas, shared by [open] and [close]'s off-path checkpoint connection. */
    private fun writerConfig(): SQLiteConfig =
        SQLiteConfig().apply {
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setBusyTimeout(BUSY_TIMEOUT_MS)
            setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE)
        }

    private val readerConnectionsLazy = lazy { openReaderConnections() }

    /** Read connections kept open between transactions. Internal for tests. */
    internal val readerConnections: IdleConnectionCache by readerConnectionsLazy

    private val readDatabase: Database by lazy { Database.connect({ readerConnections.borrow() }) }

    private fun openReaderConnections(): IdleConnectionCache {
        // The writer handle creates the file and switches it to WAL; the reader relies on both.
        database
        val config =
            SQLiteConfig().apply {
                setBusyTimeout(BUSY_TIMEOUT_MS)
                setTransactionMode(SQLiteConfig.TransactionMode.DEFERRED)
            }
        val dataSource =
            SQLiteDataSource(config).apply {
                url = "jdbc:sqlite:$databasePath"
            }
        return IdleConnectionCache(READER_IDLE_CONNECTIONS) {
            dataSource.connection.also { c -> c.createStatement().use { it.execute("PRAGMA query_only = ON") } }
        }
    }

    /** Creates tables if missing and stamps the schema version. Idempotent. */
    fun migrate() {
        transaction(database) {
            SchemaUtils.create(
                Processes,
                ProcessInputs,
                ProcessTags,
                Questions,
                Dispatches,
                Continuations,
                ContinuationWatches,
                ResultTokens,
                CostEntries,
                ServerMeta,
            )
            rawStatement { st ->
                val current = st.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                if (current < SCHEMA_VERSION) {
                    upgradeSchema(st, current)
                    st.execute("PRAGMA user_version = $SCHEMA_VERSION")
                    logger.info("Database schema version set to {} (was {})", SCHEMA_VERSION, current)
                }
            }
        }
    }

    @Volatile
    private var maintenance: Job? = null

    /**
     * Refreshes SQLite's planner statistics (`sqlite_stat1`). Without them the planner
     * guesses selectivity and can pick an index that matches almost every row. Analyzes
     * every table that has no statistics or has changed size a lot since its last
     * analysis, and is a cheap no-op otherwise. `0x10000` checks all tables: a plain
     * `PRAGMA optimize` only considers tables this connection has queried, and each
     * transaction here opens a new connection. Never fails the caller.
     */
    fun optimize() {
        runCatching {
            transaction(database) { rawStatement { it.execute("PRAGMA optimize=0x10002") } }
        }.onFailure { logger.warn("PRAGMA optimize failed: {}", it.message) }
    }

    /** Runs [optimize] every [intervalMs] until [close]. */
    fun startMaintenance(
        scope: CoroutineScope,
        intervalMs: Long = OPTIMIZE_INTERVAL_MS,
    ) {
        maintenance =
            scope.launch(ioDispatcher) {
                while (isActive) {
                    delay(intervalMs)
                    optimize()
                }
            }
    }

    fun close() {
        maintenance?.cancel()
        // Never force-open during shutdown: if startup aborted before migrate()
        // touched the database, there is nothing to checkpoint.
        if (!opened) return
        // Before the checkpoint: an open reader connection would keep the WAL alive.
        if (readerConnectionsLazy.isInitialized()) readerConnections.close()
        // Flush the WAL into the main db on shutdown so the file is
        // self-contained for inspection/backup. This runs on its own autocommit
        // connection: the old form ran it inside its own write transaction
        // (`transaction(database) { … }`), where the transaction's own write lock
        // blocks a TRUNCATE checkpoint with `SQLITE_LOCKED` — which runCatching
        // swallowed, so the WAL was never truncated on shutdown.
        runCatching {
            checkpoint()
        }.onFailure { logger.warn("WAL checkpoint on shutdown failed: {}", it.message) }
        logger.info("Closed SQLite database at {}", databasePath)
    }

    /**
     * `PRAGMA wal_checkpoint(TRUNCATE)` on a short-lived autocommit connection — never
     * inside a transaction: `transaction_mode=IMMEDIATE` makes every
     * `transaction(database)` a `BEGIN IMMEDIATE`, where SQLite refuses a checkpoint
     * with `SQLITE_LOCKED`. See ADR-0020.
     */
    private fun checkpoint() {
        val dataSource = writerDataSource()
        dataSource.connection.use { c ->
            c.createStatement().use { st ->
                // A checkpoint blocked by another connection's lock reports busy != 0 in
                // the result row instead of throwing, so the failure would be silent.
                st.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").use { rs ->
                    if (rs.next() && rs.getInt(1) != 0) {
                        logger.warn("WAL checkpoint on shutdown blocked (busy) — the -wal file was not truncated")
                    }
                }
            }
        }
    }

    /** Runs [block] inside one write-locked transaction on [ioDispatcher] (blocking JDBC). */
    suspend fun <T> query(block: JdbcTransaction.() -> T): T =
        withContext(ioDispatcher) {
            transaction(database) {
                registerInterceptor(BounceWriterToAutoCommit)
                block()
            }
        }

    /**
     * Runs a [block] that only reads inside one DEFERRED, `query_only` transaction on
     * [ioDispatcher]. It sees the last committed state and never contends for the write
     * lock; a write attempted inside it fails with `SQLITE_READONLY`.
     */
    suspend fun <T> read(block: JdbcTransaction.() -> T): T =
        withContext(ioDispatcher) {
            transaction(readDatabase) { block() }
        }

    private fun <T> JdbcTransaction.rawStatement(block: (java.sql.Statement) -> T): T {
        val raw = connection.connection as java.sql.Connection
        return raw.createStatement().use(block)
    }

    /** Delegates every [DataSource] method to the real one and wraps the connection. */
    private class ConnectionWrappingDataSource(
        private val delegate: DataSource,
        private val wrap: (Connection) -> Connection,
    ) : DataSource {
        override fun getConnection(): Connection = wrap(delegate.connection)

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = wrap(delegate.getConnection(username, password))

        override fun getLogWriter(): PrintWriter = delegate.logWriter

        override fun setLogWriter(out: PrintWriter?) = delegate.setLogWriter(out)

        override fun setLoginTimeout(seconds: Int) = delegate.setLoginTimeout(seconds)

        override fun getLoginTimeout(): Int = delegate.loginTimeout

        override fun getParentLogger(): JulLogger = delegate.parentLogger

        override fun <T : Any?> unwrap(iface: Class<T>?): T = delegate.unwrap(iface)

        override fun isWrapperFor(iface: Class<*>?): Boolean = delegate.isWrapperFor(iface)
    }

    companion object {
        const val SCHEMA_VERSION = 29
        const val OPTIMIZE_INTERVAL_MS = 60 * 60 * 1000L
        private const val BUSY_TIMEOUT_MS = 5000

        private val IDENTITY_CONNECTION_WRAPPER: (Connection) -> Connection = { it }

        /**
         * The writer handle's config: one attempt, never a retry. A block here
         * that throws may already have landed its data (a commit can throw after
         * it succeeded), and the blocks on this handle are not idempotent, so
         * re-running one duplicates durable state. The reader keeps Exposed's
         * default. See ADR-0020 and SQLite rule 5 in `app/CLAUDE.md`.
         */
        val WRITER_DATABASE_CONFIG: DatabaseConfig = DatabaseConfig { defaultMaxAttempts = 1 }

        /**
         * Ends the driver's post-commit re-`BEGIN IMMEDIATE` (see the 2026-09-29 amendment to
         * ADR-0020 for why `commit()` leaves one): the write lock must end with the
         * transaction, not with Exposed's connection teardown. Stateless, so one instance
         * serves every transaction. Runs after the commit has landed, hence `runCatching` —
         * a failure must not turn a landed write into an error, only widen the lock window
         * back to the connection. Single-attempt + bounce are the same ticket's two halves:
         * a write block here neither replays nor holds the lock past its commit.
         */
        private object BounceWriterToAutoCommit : StatementInterceptor {
            override fun afterCommit(transaction: Transaction) {
                val raw = (transaction as JdbcTransaction).connection.connection as java.sql.Connection
                runCatching { raw.autoCommit = true }
            }
        }

        /** Read connections kept open between transactions; more open on demand, never waited for. */
        private const val READER_IDLE_CONNECTIONS = 4
    }
}
