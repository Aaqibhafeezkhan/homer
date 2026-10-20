package homer.app.services.persistence

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.io.path.exists
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ch.qos.logback.classic.Logger as LogbackLogger

class DatabaseServiceTest {
    private val logger = LoggerFactory.getLogger(DatabaseServiceTest::class.java)

    private companion object {
        /** The writer handle's own `busy_timeout`, so a lock left held fails rather than hangs. */
        const val BUSY_TIMEOUT_MS = 5_000
    }

    /** Attaches a started [ListAppender] to the test's logback logger; detach it in `finally`. */
    private fun attachListAppender(): Pair<LogbackLogger, ListAppender<ILoggingEvent>> {
        val logbackLogger = logger as LogbackLogger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logbackLogger.addAppender(appender)
        return logbackLogger to appender
    }

    @Test
    fun `a read does not wait for a writer holding the write lock`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("homer.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { writer ->
            writer.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            writer.createStatement().use {
                it.execute(
                    "INSERT INTO processes (status, agent_name, history, executions) " +
                        "VALUES ('WORKING', 'uncommitted', '[]', '[]')",
                )
            }
            var count = -1L
            val elapsedMs = measureTimeMillis { count = svc.read { Processes.selectAll().count() } }
            writer.createStatement().use { it.execute("ROLLBACK") }

            // A read that queued for the write lock would sit out the whole 5 s busy_timeout and then fail.
            assertTrue(elapsedMs < 2_000, "read waited $elapsedMs ms behind the writer")
            assertEquals(0L, count, "read must see the last committed state, not the writer's pending row")
        }
    }

    @Test
    fun `a read refuses to write`(
        @TempDir dir: Path,
    ) = runTest {
        val svc = DatabaseService(dir.resolve("homer.db").toString(), logger)
        svc.migrate()
        assertFailsWith<ExposedSQLException> { svc.read { Processes.deleteAll() } }
    }

    @Test
    fun `the migrated schema declares no foreign key`(
        @TempDir dir: Path,
    ) = runTest {
        // Pins ADR-0020's amendment: no `foreign_keys` pragma is set and no table
        // declares a foreign key, so declaring a `REFERENCES` later must fail here.
        val dbPath = dir.resolve("homer.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()

        val schemaSql =
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT name, sql FROM sqlite_master WHERE sql IS NOT NULL").use { rs ->
                        generateSequence { if (rs.next()) rs.getString("name") to rs.getString("sql") else null }
                            .toList()
                    }
                }
            }

        assertTrue(schemaSql.isNotEmpty(), "migrate() created no schema objects")
        val withForeignKey = schemaSql.filter { (_, sql) -> sql.contains("REFERENCES", ignoreCase = true) }
        assertTrue(withForeignKey.isEmpty(), "schema declares a foreign key: ${withForeignKey.map { it.first }}")
    }

    @Test
    fun `a second writer takes the lock immediately after a query transaction commits`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("homer.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()

        // The probe runs at Exposed's after-commit seam, which is the one moment the window is
        // observable: the commit has landed, and the writer's connection is still open — Exposed
        // only closes it when the transaction tears down, so a check after query { } returns
        // would always pass. sqlite-jdbc re-issues BEGIN IMMEDIATE inside commit() while the
        // connection is still in manual-commit mode, so without the fix the committed
        // transaction is still holding the write lock here and the probe sits out its
        // busy_timeout and fails SQLITE_BUSY.
        // Covers query { } only; migrate()/getOrCreateInstanceId keep the wider window by
        // design (ADR-0020), so a new raw transaction(database) call site on a live path
        // belongs on query { } instead of beside this probe.
        val probe =
            object : StatementInterceptor {
                var failure: String? = null

                override fun afterCommit(transaction: Transaction) {
                    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { second ->
                        second.createStatement().use { it.execute("PRAGMA busy_timeout = $BUSY_TIMEOUT_MS") }
                        try {
                            second.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                            second.createStatement().use {
                                it.execute(
                                    "INSERT INTO processes (status, agent_name, history, executions) " +
                                        "VALUES ('WORKING', 'second', '[]', '[]')",
                                )
                            }
                            second.createStatement().use { it.execute("COMMIT") }
                        } catch (e: Exception) {
                            failure = e.message
                        }
                    }
                }
            }

        repeat(3) { i ->
            svc.query {
                registerInterceptor(probe)
                exec(
                    "INSERT INTO processes (status, agent_name, history, executions) " +
                        "VALUES ('WORKING', 'committed-$i', '[]', '[]')",
                )
            }
        }

        assertNull(probe.failure, "second writer could not take the lock after commit: ${probe.failure}")
        assertEquals(6L, svc.read { Processes.selectAll().count() }, "both writers' rows must have landed")
    }

    @Test
    fun `concurrent writers still serialize on the write lock`(
        @TempDir dir: Path,
    ) = runTest {
        val svc = DatabaseService(dir.resolve("homer.db").toString(), logger)
        svc.migrate()

        // Every write transaction still begins with BEGIN IMMEDIATE up front (ADR-0020's
        // replacement for the Mutex): N concurrent writers all commit, serialized, and none
        // waits past busy_timeout.
        val elapsed =
            measureTimeMillis {
                withContext(Dispatchers.IO) {
                    (1..8)
                        .map {
                            async {
                                svc.query {
                                    exec(
                                        "INSERT INTO processes (status, agent_name, history, executions) " +
                                            "VALUES ('WORKING', 'writer-$it', '[]', '[]')",
                                    )
                                }
                            }
                        }.awaitAll()
                }
            }

        assertTrue(elapsed < BUSY_TIMEOUT_MS, "8 serialized writers took $elapsed ms, past busy_timeout")
        assertEquals(8L, svc.read { Processes.selectAll().count() })
    }

    @Test
    fun `close truncates the WAL`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("homer.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        val wal = dir.resolve("homer.db-wal")

        // Hold a second connection's read snapshot across the write: without it SQLite deletes
        // the -wal when the writer's connection closes, and the assert below would pass even
        // had close()'s checkpoint never run. The snapshot is released — but the connection
        // kept open — before close(), so the checkpoint is not pinned behind it.
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { keeper ->
            keeper.autoCommit = false
            keeper.createStatement().use { it.executeQuery("SELECT count(*) FROM processes").use { rs -> rs.next() } }
            svc.query {
                exec(
                    "INSERT INTO processes (status, agent_name, history, executions) " +
                        "VALUES ('WORKING', 'wal', '[]', '[]')",
                )
            }
            assertTrue(Files.size(wal) > 0, "the committed write should still be in the -wal before close()")
            keeper.commit()
            svc.close()
            assertEquals(0L, Files.size(wal), "close() must truncate the WAL, not leave it for teardown")
        }
    }

    /** One row appended per block execution, so a replay shows up as a longer [attempts] list. */
    private fun appendCost(attempts: MutableList<Int>): JdbcTransaction.() -> Unit =
        {
            attempts += attempts.size + 1
            CostEntries.insert {
                it[agentName] = "a"
                it[timestampSec] = 1L
                it[costUsd] = 1.0
            }
        }

    @Test
    fun `a commit that throws after it landed replays the write block under Exposed's default config`(
        @TempDir dir: Path,
    ) = runTest {
        val path = dir.resolve("homer.db").toString()
        DatabaseService(path, logger).migrate()
        val attempts = mutableListOf<Int>()
        // Reproduces the post-commit SQLITE_BUSY replay against Exposed's own default (maxAttempts = 3): the commit
        // lands and still throws, so the block runs three times and appends three rows.
        val db =
            DatabaseService(
                path,
                logger,
                wrapWriterConnection = PostCommitFailureConnection::wrap,
                writerDatabaseConfig = PostCommitFailureConnection.multiAttemptDatabaseConfig(),
            ).database

        assertFailsWith<SQLException> { transaction(db) { appendCost(attempts)() } }

        assertEquals(listOf(1, 2, 3), attempts, "the default config must re-run the block on a commit-phase failure")
        assertEquals(3L, countRows(path, "cost_entries"), "each attempt landed its own row")
    }

    @Test
    fun `a commit that throws after it landed runs the write block exactly once on the writer handle`(
        @TempDir dir: Path,
    ) = runTest {
        val path = dir.resolve("homer.db").toString()
        DatabaseService(path, logger).migrate()
        val failing = DatabaseService(path, logger, wrapWriterConnection = PostCommitFailureConnection::wrap)
        val attempts = mutableListOf<Int>()

        val thrown = assertFailsWith<SQLException> { failing.query(appendCost(attempts)) }

        assertEquals("database is locked", thrown.message, "the commit failure propagates unchanged")
        assertEquals(listOf(1), attempts, "the writer handle must run the block once")
        assertEquals(1L, countRows(path, "cost_entries"), "exactly one row may land")
    }

    @Test
    fun `the writer handle is built single-attempt`(
        @TempDir dir: Path,
    ) = runTest {
        val svc = DatabaseService(dir.resolve("homer.db").toString(), logger)
        assertEquals(1, svc.database.config.defaultMaxAttempts)
        assertEquals(1, DatabaseService.WRITER_DATABASE_CONFIG.defaultMaxAttempts)
    }

    @Test
    fun `a raw transaction on the writer handle runs the block exactly once too`(
        @TempDir dir: Path,
    ) = runTest {
        val path = dir.resolve("homer.db").toString()
        DatabaseService(path, logger).migrate()
        val failing = DatabaseService(path, logger, wrapWriterConnection = PostCommitFailureConnection::wrap)
        val attempts = mutableListOf<Int>()

        // ServerMetaRepository runs `transaction(db.database)` outside `query { }`;
        // SQLite rule 5 says that path is single-attempt too, because the config
        // rides the writer Database itself. This pins it.
        val thrown = assertFailsWith<SQLException> { transaction(failing.database) { appendCost(attempts)() } }

        assertEquals("database is locked", thrown.message, "the commit failure propagates unchanged")
        assertEquals(listOf(1), attempts, "the raw writer-handle transaction must run the block once")
        assertEquals(1L, countRows(path, "cost_entries"), "exactly one row may land")
    }

    private fun countRows(
        path: String,
        table: String,
    ): Long =
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }

    @Test
    fun `maintenance re-runs optimize until close`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("homer.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        svc.startMaintenance(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO), intervalMs = 20)
        // Rows written after startup are analyzed by a later maintenance tick, not by anything else.
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use {
                it.execute(
                    "INSERT INTO processes (status, agent_name, history, executions) " +
                        "VALUES ('FINISHED', 'a', '[]', '[]')",
                )
            }
        }
        val analyzed = {
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT name FROM sqlite_master WHERE name = 'sqlite_stat1'").use { it.next() } &&
                        st.executeQuery("SELECT COUNT(*) FROM sqlite_stat1 WHERE tbl = 'processes'").use { rs ->
                            rs.next() &&
                                rs.getInt(1) > 0
                        }
                }
            }
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (!analyzed() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(analyzed(), "maintenance never gathered statistics")
        svc.close()
    }

    @Test
    fun `reads reuse one connection and still see the latest commit`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("homer.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        assertEquals(0L, svc.read { Processes.selectAll().count() })
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use {
                it.execute(
                    "INSERT INTO processes (status, agent_name, history, executions) " +
                        "VALUES ('FINISHED', 'a', '[]', '[]')",
                )
            }
        }
        // A reused connection must not keep the previous read's snapshot.
        assertEquals(1L, svc.read { Processes.selectAll().count() })
        repeat(20) { svc.read { Processes.selectAll().count() } }
        assertEquals(1, svc.readerConnections.opened.get(), "sequential reads opened more than one connection")
        svc.close()
    }

    @Test
    fun `a reused read connection still refuses to write`(
        @TempDir dir: Path,
    ) = runTest {
        val svc = DatabaseService(dir.resolve("homer.db").toString(), logger)
        svc.migrate()
        svc.read { Processes.selectAll().count() }
        assertFailsWith<ExposedSQLException> { svc.read { Processes.deleteAll() } }
        assertEquals(1, svc.readerConnections.opened.get())
        svc.close()
    }

    @Test
    fun `close leaves no WAL behind once readers have been cached`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("homer.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        svc.read { Processes.selectAll().count() }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use {
                it.execute(
                    "INSERT INTO processes (status, agent_name, history, executions) " +
                        "VALUES ('FINISHED', 'a', '[]', '[]')",
                )
            }
        }
        // A second, externally opened connection stays open across close(): while it is
        // open SQLite's own last-connection shutdown checkpoint cannot run, so an empty
        // WAL here is the explicit TRUNCATE checkpoint's doing, not the teardown's.
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { held ->
            held.createStatement().use { it.execute("SELECT 1") }
            // The cached reader keeps the database open, so the WAL is still there.
            val wal = java.io.File("$dbPath-wal")
            assertTrue(wal.exists() && wal.length() > 0, "expected WAL frames while a reader is cached")
            svc.close()
            assertTrue(!wal.exists() || wal.length() == 0L, "WAL left behind after close: ${wal.length()} bytes")
        }
    }

    @Test
    fun `close does not warn that the WAL checkpoint failed`(
        @TempDir dir: Path,
    ) = runTest {
        val (logbackLogger, appender) = attachListAppender()
        try {
            val svc = DatabaseService(dir.resolve("homer.db").toString(), logbackLogger)
            svc.migrate()
            svc.close()
            val failures = appender.list.filter { it.formattedMessage.contains("WAL checkpoint on shutdown failed") }
            assertTrue(failures.isEmpty(), "close() reported a checkpoint failure: ${failures.map { it.formattedMessage }}")
        } finally {
            logbackLogger.detachAppender(appender)
        }
    }

    @Test
    fun `close warns that the WAL checkpoint was blocked by a held write lock`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("homer.db").toString()
        val (logbackLogger, appender) = attachListAppender()
        val svc = DatabaseService(dbPath, logbackLogger)
        svc.migrate()
        // A second connection holding a write transaction across close() blocks the
        // TRUNCATE checkpoint: it reports busy in its result row instead of throwing,
        // so without the branch the failure would be silent.
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { held ->
            held.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            held.createStatement().use {
                it.execute(
                    "INSERT INTO processes (status, agent_name, history, executions) " +
                        "VALUES ('WORKING', 'uncommitted', '[]', '[]')",
                )
            }
            svc.close()
            val busy = appender.list.filter { it.formattedMessage.contains("WAL checkpoint on shutdown blocked (busy)") }
            assertTrue(
                busy.isNotEmpty(),
                "close() did not report the blocked checkpoint: ${appender.list.map { it.formattedMessage }}",
            )
            val failures = appender.list.filter { it.formattedMessage.contains("WAL checkpoint on shutdown failed") }
            assertTrue(
                failures.isEmpty(),
                "a blocked checkpoint must report busy, not a failure: ${failures.map { it.formattedMessage }}",
            )
        }
    }

    @Test
    fun `close on a never-opened service touches nothing`(
        @TempDir dir: Path,
    ) = runTest {
        val dbFile = dir.resolve("homer.db")
        val (logbackLogger, appender) = attachListAppender()
        try {
            DatabaseService(dbFile.toString(), logbackLogger).close()
            assertTrue(!dbFile.exists(), "close() created the database file")
            assertTrue(
                appender.list.isEmpty(),
                "close() logged on a never-opened service: ${appender.list.map { it.formattedMessage }}",
            )
        } finally {
            logbackLogger.detachAppender(appender)
        }
    }

    @Test
    fun `close on a never-opened service leaves a pre-existing database untouched`(
        @TempDir dir: Path,
    ) = runTest {
        val dbFile = dir.resolve("homer.db")
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { c ->
            c.createStatement().use { it.execute("CREATE TABLE marker (id INTEGER)") }
        }
        val lengthBefore = dbFile.toFile().length()
        val (logbackLogger, appender) = attachListAppender()
        try {
            DatabaseService(dbFile.toString(), logbackLogger).close()
            assertEquals(lengthBefore, dbFile.toFile().length(), "close() rewrote a pre-existing database")
            assertEquals(
                emptyList(),
                appender.list.map { it.formattedMessage },
                "close() logged on a never-opened service",
            )
        } finally {
            logbackLogger.detachAppender(appender)
        }
    }
}
