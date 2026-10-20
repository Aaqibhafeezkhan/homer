package homer.app.services.persistence

import homer.app.fixtures.SqlStatementRecorder
import homer.app.infra.ProcessJobRegistry
import homer.app.models.ExecutionInfo
import homer.app.models.ProcessStatus
import homer.app.services.ProcessManager
import homer.app.services.QuestionsService
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DatabaseMigrationTest {
    private val logger = LoggerFactory.getLogger(DatabaseMigrationTest::class.java)

    private fun tableNames(st: java.sql.Statement): List<String> =
        st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rs ->
            generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
        }

    private val processListIndexes =
        setOf(
            "processes_agent_name",
            "processes_status",
            "processes_parent_process_id",
            "processes_root_process_id",
            "processes_owner",
        )

    private fun processIndexNames(st: java.sql.Statement): Set<String> =
        st.executeQuery("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='processes'").use { rs ->
            generateSequence { if (rs.next()) rs.getString(1) else null }.toSet()
        }

    private fun userVersion(st: java.sql.Statement): Int = st.executeQuery("PRAGMA user_version").use { if (it.next()) it.getInt(1) else 0 }

    private fun columnNames(
        st: java.sql.Statement,
        table: String,
    ): List<String> =
        st.executeQuery("PRAGMA table_info($table)").use { rs ->
            generateSequence { if (rs.next()) rs.getString("name") else null }.toList()
        }

    @Test
    fun `fresh migrate creates every table including process_inputs at v27`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("fresh.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                assertTrue("dispatch_bound" in columnNames(st, "questions"))
                assertTrue("kind" in columnNames(st, "continuations"))
                assertTrue("consumer_agent" in columnNames(st, "result_tokens"))
                assertTrue("owner" in columnNames(st, "processes"))
                assertTrue("parent_process_id" in columnNames(st, "processes"))
                assertTrue("root_process_id" in columnNames(st, "processes"))
                assertTrue("cost_source" in columnNames(st, "processes"))
                assertTrue("secret_param_names" in columnNames(st, "dispatches"))
                assertTrue("secret_param_names" in columnNames(st, "continuations"))
                assertTrue("thread_id" in columnNames(st, "continuations"))
                val tables = tableNames(st)
                assertTrue("dispatches" in tables)
                assertTrue("continuations" in tables)
                assertTrue("continuation_watches" in tables)
                assertTrue("result_tokens" in tables)
                assertTrue("cost_entries" in tables)
                assertTrue("server_meta" in tables)
                assertTrue("process_tags" in tables)
                assertTrue("tag" in columnNames(st, "process_tags"))
                // v25: write-once inputs live in their own table, not in the hot processes row.
                assertTrue("process_inputs" in tables)
                assertEquals(listOf("process_id", "input_params"), columnNames(st, "process_inputs"))
                assertTrue("input_params" !in columnNames(st, "processes"))
                assertTrue(processIndexNames(st).containsAll(processListIndexes))
            }
        }
        svc.close()
    }

    @Test
    fun `every process-list count shape is planned on an index`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("fresh.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        // The WHERE shapes ProcessRepository.getAll composes for `total`, alone and with the non-admin visibility OR.
        val visibility = "(owner = 'u' OR agent_name IN ('a', 'b'))"
        val shapes =
            listOf(
                "",
                "WHERE agent_name = 'a'",
                "WHERE status IN ('WORKING', 'CREATED')",
                "WHERE parent_process_id = 7",
                "WHERE root_process_id = 7",
                "WHERE parent_process_id IS NULL",
                "WHERE $visibility",
                "WHERE status IN ('FAILED') AND $visibility",
                "WHERE parent_process_id IS NULL AND $visibility",
            )
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                for (where in shapes) {
                    val plan =
                        st.executeQuery("EXPLAIN QUERY PLAN SELECT COUNT(*) FROM processes $where").use { rs ->
                            generateSequence { if (rs.next()) rs.getString("detail") else null }.toList()
                        }
                    assertTrue(plan.none { it == "SCAN processes" }, "full table scan for '$where': $plan")
                }
                // A scoped caller with no readable agents: the owner clause alone, no `OR 0` arm, so
                // the page and its count stay on an index (ProcessQueries.visibleTo).
                val ownerOnly =
                    listOf(
                        "SELECT COUNT(*) FROM processes WHERE owner = 'u'",
                        "SELECT status FROM processes WHERE owner = 'u' ORDER BY id DESC LIMIT 100",
                    )
                for (sql in ownerOnly) assertTrue(plan(st, sql).any { "processes_owner" in it }, "'$sql': ${plan(st, sql)}")
                val filtered = "SELECT status FROM processes WHERE status IN ('FAILED') AND owner = 'u' ORDER BY id DESC LIMIT 100"
                assertTrue(plan(st, filtered).none { it == "SCAN processes" }, "full table scan for '$filtered': ${plan(st, filtered)}")
            }
        }
        svc.close()
    }

    @Test
    fun `v22 database gains the process-list indexes`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v22.db").toString()
        // A v22 DB: processes with every column, but none of the list-filter indexes.
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE processes (id INTEGER PRIMARY KEY AUTOINCREMENT, status VARCHAR(16), " +
                        "agent_name VARCHAR(255), current_command TEXT, current_command_start INTEGER, " +
                        "purge_at INTEGER, record_purge_at INTEGER, cost_usd REAL, cost_source TEXT NOT NULL DEFAULT 'cli', " +
                        "artifacts_dir TEXT, input_params TEXT, history TEXT, executions TEXT, runner_state TEXT, " +
                        "owner TEXT, trace_id VARCHAR(32), created_at INTEGER, ended_at INTEGER, secret_input_keys TEXT, " +
                        "retain_artifacts TEXT, workspace_purged_at INTEGER, retained_truncated INTEGER, " +
                        "parent_process_id INTEGER, root_process_id INTEGER)",
                )
                st.execute("PRAGMA user_version = 22")
            }
        }
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                assertTrue(processIndexNames(st).containsAll(processListIndexes))
                val plan =
                    st.executeQuery("EXPLAIN QUERY PLAN SELECT COUNT(*) FROM processes WHERE root_process_id = 7").use { rs ->
                        rs.next()
                        rs.getString("detail")
                    }
                assertTrue("processes_root_process_id" in plan, plan)
            }
        }
        svc.close()
    }

    /** v24 indexes, by table, as Tables.kt declares them and the v23→v24 migration creates them. */
    private val v24Indexes =
        mapOf(
            "processes" to
                setOf(
                    "processes_artifacts_dir",
                    "processes_workspace_purge",
                    "processes_record_purge",
                    "processes_legacy_record_purge",
                    "processes_agent_status_ended",
                ),
            "questions" to setOf("questions_status_dispatch_bound", "questions_process_dispatch_bound"),
            "dispatches" to setOf("dispatches_status_expires"),
            "continuations" to setOf("continuations_watched_status", "continuations_status_expires"),
        )

    /**
     * The WHERE shapes the sweeps and lookups send, each with the index that must serve it. Planned
     * without sqlite_stat1, as on a fresh install. Each shape mirrors a repository query.
     */
    private val v24LookupShapes =
        listOf(
            // ProcessRepository.isArtifactsDirShared
            "SELECT id FROM processes WHERE artifacts_dir = '/w/1' AND id <> 1 LIMIT 1" to "processes_artifacts_dir",
            // ProcessRepository.isArtifactsDirSharedWithActiveRun: same
            // artifacts_dir equality, narrowed to live rows so a terminal sibling cannot
            // block cleanup. Still planned on the artifacts_dir index.
            "SELECT id FROM processes WHERE artifacts_dir = '/w/1' AND id <> 1 AND status IN ('CREATED', 'WORKING') LIMIT 1" to
                "processes_artifacts_dir",
            // ProcessRepository.getWorkspacePurgeable
            "SELECT * FROM processes WHERE status IN ('FINISHED', 'FAILED', 'KILLED', 'UNKNOWN') AND purge_at IS NOT NULL " +
                "AND purge_at <= 100 AND artifacts_dir IS NOT NULL AND workspace_purged_at IS NULL" to "processes_workspace_purge",
            // ProcessRepository.getRecordPurgeable, both arms of the OR
            "SELECT * FROM processes WHERE status IN ('FINISHED', 'FAILED', 'KILLED', 'UNKNOWN') AND purge_at IS NOT NULL " +
                "AND (artifacts_dir IS NULL OR workspace_purged_at IS NOT NULL) AND ((record_purge_at IS NOT NULL AND " +
                "record_purge_at <= 100) OR (record_purge_at IS NULL AND purge_at <= 100))" to "processes_record_purge",
            "SELECT * FROM processes WHERE status IN ('FINISHED', 'FAILED', 'KILLED', 'UNKNOWN') AND purge_at IS NOT NULL " +
                "AND (artifacts_dir IS NULL OR workspace_purged_at IS NOT NULL) AND ((record_purge_at IS NOT NULL AND " +
                "record_purge_at <= 100) OR (record_purge_at IS NULL AND purge_at <= 100))" to "processes_legacy_record_purge",
            // ProcessRepository.stampUnknownPurgeTimes, once at startup: the unstamped UNKNOWN rows (only
            // status = ? is indexable, so any (status, …) index), then one UPDATE per id chunk
            "SELECT id, agent_name FROM processes WHERE status = 'UNKNOWN' AND purge_at IS NULL" to
                "processes_status|processes_record_purge|processes_legacy_record_purge",
            "UPDATE processes SET purge_at = 1, record_purge_at = 1, retain_artifacts = NULL WHERE id IN (1, 2)" to
                "INTEGER PRIMARY KEY",
            // ProcessRepository.latestFailureEndedAtTx, inside obtainProcessId's write transaction
            "SELECT ended_at FROM processes WHERE agent_name = 'a' AND status = 'FAILED' ORDER BY ended_at DESC LIMIT 1" to
                "processes_agent_status_ended",
            // ProcessRepository.latestRunStartedAtTx keeps the agent_name index, which is already in id order
            "SELECT created_at FROM processes WHERE agent_name = 'a' ORDER BY id DESC LIMIT 1" to "processes_agent_name",
            // ProcessRepository.createdRunsOf, on /agents/reload
            "SELECT id, agent_name FROM processes WHERE status = 'CREATED' AND agent_name IN ('a', 'b')" to "processes_agent_status_ended",
            // QuestionsRepository.tryInsert / expireForProcess, under the write lock
            "SELECT COUNT(*) FROM questions WHERE process_id = 1 AND status = 'OPEN' AND dispatch_bound = 0" to
                "questions_status_dispatch_bound",
            // QuestionsRepository.removeForProcess
            "SELECT id FROM questions WHERE process_id = 1 AND dispatch_bound = 0" to "questions_process_dispatch_bound",
            // QuestionsRepository.openCountByProcess, on every process-list read
            "SELECT process_id FROM questions WHERE status = 'OPEN' AND dispatch_bound = 0" to "questions_status_dispatch_bound",
            // ProcessRepository.getAll's open-question counts over the page's ids, in the list's read
            "SELECT process_id FROM questions WHERE status = 'OPEN' AND dispatch_bound = 0 AND process_id IN (1, 2)" to
                "questions_status_dispatch_bound",
            // ProcessRepository.getAll's root-visibility read of the page's flow roots
            "SELECT id, owner, agent_name FROM processes WHERE id IN (1, 2)" to "INTEGER PRIMARY KEY",
            // DispatchRepository.create cap and the DISPATCHING recovery. Any (status, …)
            // index serves it; since v26 the planner may pick dispatches_status_created.
            "SELECT COUNT(*) FROM dispatches WHERE status = 'PENDING'" to "dispatches_status_expires|dispatches_status_created",
            // DispatchRepository.expirePending
            "SELECT * FROM dispatches WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at <= 100" to
                "dispatches_status_expires",
            // DispatchRepository.expirePending's due-row pre-check, in db.read
            "SELECT id FROM dispatches WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at <= 100 LIMIT 1" to
                "dispatches_status_expires",
            // DispatchRepository.expirePending's write, one statement for every lapsed row
            "UPDATE dispatches SET status = 'CANCELLED' WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at <= 100" to
                "dispatches_status_expires",
            // DispatchRepository.failOrphanedDispatching, once at boot
            "UPDATE dispatches SET status = 'FAILED' WHERE status = 'DISPATCHING'" to "dispatches_status_expires|dispatches_status_created",
            // DispatchRepository's batched dispatch-bound question delete (expiry and boot sweeps)
            "DELETE FROM questions WHERE id IN ('q1', 'q2') AND dispatch_bound = 1" to "sqlite_autoindex_questions_1",
            // ContinuationRepository.findByWatchedProcessId, on every terminal transition
            "SELECT * FROM continuations WHERE watched_process_id = 1 AND status = 'PENDING'" to "continuations_watched_status",
            // ContinuationRepository.create cap and expirePending; either (status, …) index, as above
            "SELECT COUNT(*) FROM continuations WHERE status = 'PENDING'" to "continuations_status_expires|continuations_status_created",
            "SELECT * FROM continuations WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at <= 100" to
                "continuations_status_expires",
            // ContinuationRepository.expirePending's due-row pre-check, in db.read
            "SELECT id FROM continuations WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at <= 100 LIMIT 1" to
                "continuations_status_expires",
            // ContinuationRepository.expirePending's write, one statement for every lapsed row
            "UPDATE continuations SET status = 'EXPIRED' WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at <= 100" to
                "continuations_status_expires",
            // ContinuationRepository.failOrphanedDispatching, once at boot
            "UPDATE continuations SET status = 'FAILED' WHERE status = 'DISPATCHING'" to
                "continuations_status_expires|continuations_status_created",
            // ContinuationRepository.findDueRetries: only status = ? is indexable, so either (status, …) index
            "SELECT * FROM continuations WHERE status = 'PENDING' AND next_attempt_at IS NOT NULL AND next_attempt_at <= 100 " +
                "AND (expires_at IS NULL OR expires_at > 100)" to "continuations_status_expires|continuations_status_created",
        )

    private fun indexNames(
        st: java.sql.Statement,
        table: String,
    ): Set<String> =
        st.executeQuery("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='$table'").use { rs ->
            generateSequence { if (rs.next()) rs.getString(1) else null }.toSet()
        }

    private fun plan(
        st: java.sql.Statement,
        sql: String,
    ): List<String> =
        st.executeQuery("EXPLAIN QUERY PLAN $sql").use { rs ->
            generateSequence { if (rs.next()) rs.getString("detail") else null }.toList()
        }

    private fun assertLookupPlans(st: java.sql.Statement) {
        for ((sql, index) in v24LookupShapes) {
            val plan = plan(st, sql)
            assertTrue(plan.none { it.startsWith("SCAN ") && !it.contains(" INDEX ") }, "table scan for '$sql': $plan")
            val accepted = index.split("|")
            assertTrue(plan.any { p -> accepted.any { p.contains(" $it ") || p.endsWith(" $it") } }, "'$sql' not planned on $index: $plan")
            assertTrue(plan.none { it.startsWith("USE TEMP B-TREE") }, "'$sql' sorts in a temp b-tree: $plan")
        }
    }

    @Test
    fun `fresh migrate plans every sweep and lookup on its v24 index`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("fresh.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                for ((table, names) in v24Indexes) assertTrue(indexNames(st, table).containsAll(names), "$table: ${indexNames(st, table)}")
                assertLookupPlans(st)
            }
        }
        svc.close()
    }

    @Test
    fun `optimize gathers planner statistics and no process query falls back to a table scan with them`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("stats.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                // A production-shaped mix: mostly finished runs across a few agents, some children,
                // most workspaces already swept, a few rows still due.
                st.execute(
                    "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 3000) " +
                        "INSERT INTO processes (status, agent_name, history, executions, owner, " +
                        "parent_process_id, root_process_id, created_at, ended_at, purge_at, record_purge_at, artifacts_dir) " +
                        "SELECT CASE WHEN i > 2990 THEN 'WORKING' WHEN i % 33 = 0 THEN 'FAILED' ELSE 'FINISHED' END, " +
                        "'agent-' || (i % 12), '[]', '[]', CASE i % 5 WHEN 0 THEN 'alice' ELSE NULL END, " +
                        "CASE WHEN i % 5 = 0 THEN i - 1 END, CASE WHEN i % 5 = 0 THEN i - 1 END, i, i + 1, i + 100, i + 1000, " +
                        "CASE WHEN i > 2900 THEN '/w/' || i END FROM n",
                )
            }
        }
        svc.optimize()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                val analyzed =
                    st.executeQuery("SELECT COUNT(*) FROM sqlite_stat1 WHERE tbl = 'processes'").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                assertTrue(analyzed > 0, "no planner statistics for processes")
                val processShapes =
                    v24LookupShapes.map { it.first }.filter { " FROM processes " in it } +
                        listOf(
                            "SELECT COUNT(*) FROM processes WHERE parent_process_id IS NULL AND (owner = 'u' OR agent_name IN ('a', 'b'))",
                            "SELECT COUNT(*) FROM processes WHERE status IN ('FAILED') AND (owner = 'u' OR agent_name IN ('a', 'b'))",
                        )
                for (sql in processShapes) {
                    val plan = plan(st, sql)
                    assertTrue(plan.none { it == "SCAN processes" }, "table scan with statistics for '$sql': $plan")
                }
            }
        }
        svc.close()
    }

    @Test
    fun `the tag filter is driven by the tag index, not a probe per process row`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("fresh.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        // ProcessRepository.getAll with ?tag=: carriesAllTags plus the non-admin visibility.
        val sql =
            "SELECT * FROM processes WHERE processes.id IN (SELECT process_tags.process_id FROM process_tags " +
                "WHERE process_tags.tag IN ('env:a', 'team:b') GROUP BY process_tags.process_id " +
                "HAVING COUNT(process_tags.process_id) = 2) AND (processes.owner = 'u' OR processes.agent_name IN ('a')) " +
                "ORDER BY processes.id DESC LIMIT 10000"
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                val plan = plan(st, sql)
                assertTrue(plan.any { "process_tags_tag_process_id" in it }, "tag index not used: $plan")
                assertTrue(plan.any { it.startsWith("SEARCH processes USING INTEGER PRIMARY KEY") }, "processes not fetched by id: $plan")
                assertTrue(plan.none { it == "SCAN processes" || "CORRELATED" in it }, "per-row probe: $plan")
            }
        }
        svc.close()
    }

    // ProcessRepository.costByTag's candidate query (index-driven form adopted when
    // ORDER BY id DESC was added for truncation reporting) — mirrors the tag-filter shape
    // above, not a per-row correlated probe of processes.
    private fun costByTagCandidateSql(
        prefix: String,
        exclusiveUpperBound: String,
    ) = "SELECT * FROM processes WHERE processes.id IN (SELECT process_tags.process_id FROM process_tags " +
        "WHERE process_tags.tag >= '$prefix' AND process_tags.tag < '$exclusiveUpperBound') " +
        "ORDER BY processes.id DESC LIMIT 10001"

    private fun assertCostByTagPlan(st: java.sql.Statement) {
        val plan = plan(st, costByTagCandidateSql("ticket_id:", "ticket_id;"))
        assertTrue(plan.any { "process_tags_tag_process_id" in it }, "tag index not used: $plan")
        assertTrue(plan.any { it.startsWith("SEARCH processes USING INTEGER PRIMARY KEY") }, "processes not fetched by id: $plan")
        assertTrue(plan.none { it == "SCAN processes" || "CORRELATED" in it }, "per-row probe: $plan")
        assertTrue(plan.none { it.startsWith("USE TEMP B-TREE") }, "'costByTag' sorts in a temp b-tree: $plan")
    }

    @Test
    fun `costByTag candidate query is driven by the tag index at 150k rows, with and without statistics`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("fresh.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                // 150k processes. Production-shaped tagging: the queried key (ticket_id) on only a
                // fifth of runs, plus two noise keys (env, team) on every run — so the range scan is
                // actually selective against the whole process_tags table, not most of it.
                st.execute(
                    "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 150000) " +
                        "INSERT INTO processes (status, agent_name, history, executions, cost_usd) " +
                        "SELECT 'FINISHED', 'agent-' || (i % 12), '[]', '[]', 0.1 FROM n",
                )
                st.execute(
                    "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 150000) " +
                        "INSERT INTO process_tags (process_id, tag) SELECT i, 'ticket_id:' || (i % 400) FROM n WHERE i % 5 = 0",
                )
                st.execute(
                    "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 150000) " +
                        "INSERT INTO process_tags (process_id, tag) SELECT i, 'env:' || (i % 4) FROM n",
                )
                st.execute(
                    "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 150000) " +
                        "INSERT INTO process_tags (process_id, tag) SELECT i, 'team:' || (i % 10) FROM n",
                )
                assertCostByTagPlan(st)
            }
        }
        svc.optimize()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st -> assertCostByTagPlan(st) }
        }
        svc.close()
    }

    // ProcessRepository.tagsForTx (single run) and tagsByProcessTx (one chunk of the
    // batched page read) both sort explicitly so tag order is a guarantee, not an accident of
    // scan order. Both must stay served by the process_tags primary key with no sort step.
    private val aif85TagOrderShapes =
        listOf(
            // ProcessRepository.tagsForTx / getTags
            "SELECT tag FROM process_tags WHERE process_id = 12345 ORDER BY tag",
            // ProcessRepository.tagsByProcessTx, one chunk
            "SELECT process_id, tag FROM process_tags WHERE process_id IN (12345, 12346, 12347) ORDER BY process_id, tag",
        )

    private fun assertAif85TagOrderPlans(st: java.sql.Statement) {
        for (sql in aif85TagOrderShapes) {
            val p = plan(st, sql)
            assertTrue(p.any { it.startsWith("SEARCH process_tags") && "INDEX" in it }, "'$sql' not index-driven: $p")
            assertTrue(p.none { it.startsWith("USE TEMP B-TREE") }, "'$sql' sorts in a temp b-tree: $p")
        }
    }

    @Test
    fun `getTags and the batched tag read stay on the process_tags primary key, no sort`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("fresh.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                // 150k rows over 50k process ids, matching the "judge at maxProcesses" rule.
                st.execute(
                    "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 50000) " +
                        "INSERT INTO process_tags (process_id, tag) " +
                        "SELECT i, 'k' || (i % 7) || ':v' || j FROM n, (SELECT 0 AS j UNION SELECT 1 UNION SELECT 2)",
                )
                assertAif85TagOrderPlans(st)
            }
        }
        svc.optimize()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st -> assertAif85TagOrderPlans(st) }
        }
        svc.close()
    }

    @Test
    fun `v23 database gains the v24 indexes`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v23.db").toString()
        // A v23 DB: today's tables without the v24 indexes.
        DatabaseService(dbPath, logger).also { it.migrate() }.close()
        val freshDefinitions =
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
                c.createStatement().use { indexDefinitions(it) }
            }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                for (name in v24Indexes.values.flatten()) st.execute("DROP INDEX $name")
                st.execute("PRAGMA user_version = 23")
            }
        }
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                for ((table, names) in v24Indexes) assertTrue(indexNames(st, table).containsAll(names), "$table: ${indexNames(st, table)}")
                // The migration's raw SQL and the Tables.kt declarations must define the same index.
                assertEquals(freshDefinitions, indexDefinitions(st))
                assertLookupPlans(st)
            }
        }
        svc.close()
    }

    /** Each v24 index's definition, normalized: case, quoting, table qualifiers and whitespace removed. */
    private fun indexDefinitions(
        st: java.sql.Statement,
        names: Collection<String> = v24Indexes.values.flatten(),
    ): Map<String, String> =
        names.associateWith { name ->
            st.executeQuery("SELECT sql FROM sqlite_master WHERE type='index' AND name='$name'").use { rs ->
                assertTrue(rs.next(), "index $name missing")
                rs
                    .getString(1)
                    .lowercase()
                    .replace("\"", "")
                    .replace(Regex("\\b[a-z_]+\\."), "")
                    .replace(" if not exists", "")
                    .replace(Regex("\\s+"), "")
            }
        }

    /** v26 indexes, by table, as Tables.kt declares them and the v25→v26 migration creates them. */
    private val v26Indexes =
        mapOf(
            "dispatches" to setOf("dispatches_status_created", "dispatches_agent_name", "dispatches_owner"),
            "continuations" to setOf("continuations_status_created", "continuations_agent_name", "continuations_owner"),
        )

    /** The settled-row prune's shapes, each with the index that must serve it, planned without sqlite_stat1. */
    private val v26PruneShapes =
        listOf(
            // DispatchRepository.pruneSettled: the due-row pre-check in db.read, then each batch
            "SELECT id FROM dispatches WHERE status IN ('DISPATCHED', 'FAILED', 'CANCELLED') AND created_at < 100 LIMIT 1" to
                "dispatches_status_created",
            "SELECT id, question_id FROM dispatches WHERE status IN ('DISPATCHED', 'FAILED', 'CANCELLED') AND created_at < 100 " +
                "LIMIT 500" to "dispatches_status_created",
            // ContinuationRepository.pruneSettled: the due-row pre-check in db.read, then each batch
            "SELECT id FROM continuations WHERE status IN ('DISPATCHED', 'FAILED', 'CANCELLED', 'EXPIRED') AND created_at < 100 " +
                "LIMIT 1" to "continuations_status_created",
            "SELECT id FROM continuations WHERE status IN ('DISPATCHED', 'FAILED', 'CANCELLED', 'EXPIRED') AND created_at < 100 " +
                "LIMIT 500" to "continuations_status_created",
            // The batch's child deletes: bound questions by primary key, join watch rows by continuation id
            "DELETE FROM questions WHERE id IN ('q1', 'q2') AND dispatch_bound = 1" to "sqlite_autoindex_questions_1",
            "DELETE FROM continuation_watches WHERE continuation_id IN (1, 2)" to "continuation_watches_continuation_id",
        )

    private fun assertV26Plans(st: java.sql.Statement) {
        for ((sql, index) in v26PruneShapes) {
            val plan = plan(st, sql)
            assertTrue(plan.none { it.startsWith("SCAN ") }, "scan for '$sql': $plan")
            assertTrue(
                plan.any { " $index " in it || it.endsWith(" $index") || it.contains("INDEX $index") },
                "'$sql' not planned on $index: $plan",
            )
        }
    }

    @Test
    fun `fresh migrate plans the settled prune on its v26 indexes`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("fresh.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                for ((table, names) in v26Indexes) assertTrue(indexNames(st, table).containsAll(names), "$table: ${indexNames(st, table)}")
                assertV26Plans(st)
            }
        }
        svc.close()
    }

    /** The (status, agentName, visibleOwner, visibleAgents) combinations the two list routes send. */
    private val v26PageCases: List<Triple<String, Pair<String?, String?>, Pair<String?, Set<String>?>>> =
        listOf(
            Triple("admin", null to null, null to null),
            Triple("admin ?status=", "FAILED" to null, null to null),
            Triple("admin ?agentName=", null to "agent-3", null to null),
            Triple("admin ?status=&agentName=", "DISPATCHED" to "agent-3", null to null),
            Triple("scoped", null to null, "alice" to setOf("agent-3", "agent-4")),
            Triple("scoped ?status=", "DISPATCHED" to null, "alice" to setOf("agent-3", "agent-4")),
            Triple("scoped ?agentName=", null to "agent-3", "alice" to setOf("agent-3", "agent-4")),
            Triple("scoped, owner arm only", null to null, "alice" to emptySet()),
            Triple("scoped, a principal that owns nothing", null to null, "nobody" to setOf("agent-3")),
        )

    /**
     * Per [v26PageCases] name, the indexes (without the `<table>_` prefix) each WHERE arm may be
     * planned on, in plan order. Where two filters meet in one arm, the planner may pick either.
     */
    private val v26PageArms: Map<String, List<Set<String>>> =
        mapOf(
            "admin" to emptyList(),
            "admin ?status=" to listOf(setOf("status_created")),
            "admin ?agentName=" to listOf(setOf("agent_name")),
            "admin ?status=&agentName=" to listOf(setOf("status_created", "agent_name")),
            "scoped" to listOf(setOf("owner"), setOf("agent_name")),
            "scoped ?status=" to listOf(setOf("owner"), setOf("agent_name", "status_created")),
            "scoped ?agentName=" to listOf(setOf("owner", "agent_name"), setOf("agent_name")),
            "scoped, owner arm only" to listOf(setOf("owner")),
            "scoped, a principal that owns nothing" to listOf(setOf("owner"), setOf("agent_name")),
        )

    /**
     * Runs every [v26PageCases] page through both repositories, records the SQL Exposed actually
     * sends, and plans it. Only the unfiltered admin page may walk the table: a backward rowid walk
     * that stops after offset + limit rows. Everything else is index-driven and never sorts.
     */
    private suspend fun assertV26PagePlans(
        svc: DatabaseService,
        st: java.sql.Statement,
    ) {
        val dispatches = DispatchRepository(svc)
        val continuations = ContinuationRepository(svc)
        for ((name, filters, visibility) in v26PageCases) {
            val (status, agentName) = filters
            val (owner, agents) = visibility
            SqlStatementRecorder.reset()
            dispatches.page(
                status?.let {
                    homer.app.services.DispatchStatus
                        .valueOf(it)
                },
                agentName,
                owner,
                agents,
                0,
                100,
            )
            continuations.page(
                status?.let {
                    homer.app.services.ContinuationStatus
                        .valueOf(it)
                },
                agentName,
                owner,
                agents,
                0,
                100,
            )
            val statements =
                SqlStatementRecorder.statements().filter {
                    it.startsWith("SELECT") && (" FROM dispatches" in it || " FROM continuations" in it)
                }
            // A count and a page from each repository.
            assertEquals(4, statements.size, "$name ran: ${SqlStatementRecorder.statements()}")
            val arms = v26PageArms.getValue(name)
            for (sql in statements) {
                val table = if (" FROM dispatches" in sql) "dispatches" else "continuations"
                // (parent, detail) rows: parent 0 is the outer read, anything else is inside the id subquery.
                val rows =
                    st.executeQuery("EXPLAIN QUERY PLAN $sql").use { rs ->
                        generateSequence { if (rs.next()) rs.getInt("parent") to rs.getString("detail") else null }.toList()
                    }
                val plan = rows.map { it.second }
                // The outer read never sorts. A MERGE (UNION) may sort the visible id set inside the subquery.
                assertTrue(rows.none { it.first == 0 && it.second.startsWith("USE TEMP B-TREE") }, "$name sorts the page: $sql → $plan")
                // The unfiltered admin page is a backward rowid walk that LIMIT stops; its COUNT(*) must
                // read every row and does so over a covering index. Any other SCAN, indexed or not, fails.
                val acceptedWalk =
                    name == "admin" &&
                        plan.size == 1 &&
                        if ("COUNT(" in sql) plan[0].startsWith("SCAN $table USING COVERING INDEX ") else plan[0] == "SCAN $table"
                assertTrue(plan.none { it.startsWith("SCAN") } || acceptedWalk, "$name scans: $sql → $plan")
                if (acceptedWalk) continue
                val used = plan.mapNotNull { Regex("USING (?:COVERING )?INDEX (\\w+)").find(it)?.groupValues?.get(1) }
                assertEquals(arms.size, used.size, "$name arms: $sql → $plan")
                for ((allowed, index) in arms.zip(used)) {
                    assertTrue(
                        index in allowed.map { "${table}_$it" },
                        "$name planned an arm on $index, expected one of $allowed: $sql → $plan",
                    )
                }
            }
        }
    }

    @Test
    fun `the settled prune and every list count and page shape stay on an index at 150k rows, with and without statistics`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("stats.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                // Production-shaped: mostly settled rows across a few agents and owners, a few still pending.
                val rows = "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 150000) "
                val status = "CASE WHEN i % 1500 = 0 THEN 'PENDING' WHEN i % 7 = 0 THEN 'FAILED' ELSE 'DISPATCHED' END"
                st.execute(
                    rows +
                        "INSERT INTO dispatches (question_id, status, agent_name, answer_param, params, origin_process_id, " +
                        "origin_agent_name, created_at, owner) SELECT 'q' || i, $status, 'agent-' || (i % 12), 'x', '{}', 1, 'o', i, " +
                        "CASE i % 5 WHEN 0 THEN 'alice' END FROM n",
                )
                st.execute(
                    rows +
                        "INSERT INTO continuations (watched_process_id, status, agent_name, resume_param, params, " +
                        "origin_process_id, origin_agent_name, created_at, owner) SELECT i, $status, 'agent-' || (i % 12), 'r', " +
                        "'{}', 1, 'o', i, CASE i % 5 WHEN 0 THEN 'alice' END FROM n",
                )
            }
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertV26Plans(st)
                assertV26PagePlans(svc, st)
            }
        }
        svc.optimize()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                val analyzed =
                    st.executeQuery("SELECT COUNT(*) FROM sqlite_stat1 WHERE tbl IN ('dispatches', 'continuations')").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                assertTrue(analyzed > 0, "no planner statistics for dispatches/continuations")
                assertV26Plans(st)
                assertV26PagePlans(svc, st)
            }
        }
        svc.close()
    }

    // watchesByContinuationTx's batched read stays on continuation_watches_continuation_id
    // (since schema v5; v26 first pinned a plan on it) with no sort: its entries for one
    // continuation_id are stored in rowid order.
    // Plans the statement it actually sends for a page of three JOIN continuations.
    private suspend fun assertContinuationWatchesBatchPlan(
        svc: DatabaseService,
        st: java.sql.Statement,
    ) {
        SqlStatementRecorder.reset()
        assertEquals(3, ContinuationRepository(svc).byThread("watch-thread", null).size)
        val sent = SqlStatementRecorder.statements().filter { " FROM continuation_watches" in it }
        assertEquals(1, sent.size, "${SqlStatementRecorder.statements()}")
        val plan = plan(st, sent.single())
        assertTrue(plan.none { it.startsWith("SCAN ") }, "table scan for '${sent.single()}': $plan")
        assertTrue(plan.any { "INDEX continuation_watches_continuation_id" in it }, "'${sent.single()}' not planned on the index: $plan")
        assertTrue(plan.none { it.startsWith("USE TEMP B-TREE") }, "'${sent.single()}' sorts in a temp b-tree: $plan")
    }

    @Test
    fun `the batched JOIN watch read stays on its index at 150k continuation_watches rows, with and without statistics`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("fresh.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                // Production-shaped fan-out: 150k watch rows spread over 30k JOIN continuations
                // (5 watches each).
                st.execute(
                    "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 150000) " +
                        "INSERT INTO continuation_watches (continuation_id, watched_process_id, interrupt_id) " +
                        "SELECT (i % 30000) + 1, i, 'int-' || i FROM n",
                )
                // Three of those JOINs, so a real read sends the batched watch query for them.
                st.execute(
                    "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 3) " +
                        "INSERT INTO continuations (id, watched_process_id, status, agent_name, resume_param, params, " +
                        "origin_process_id, origin_agent_name, created_at, kind, thread_id) " +
                        "SELECT i, 0, 'PENDING', 'a', 'r', '{}', 1, 'o', i, 'JOIN', 'watch-thread' FROM n",
                )
                assertContinuationWatchesBatchPlan(svc, st)
            }
        }
        svc.optimize()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                val analyzed =
                    st.executeQuery("SELECT COUNT(*) FROM sqlite_stat1 WHERE tbl = 'continuation_watches'").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                assertTrue(analyzed > 0, "no planner statistics for continuation_watches")
                assertContinuationWatchesBatchPlan(svc, st)
            }
        }
        svc.close()
    }

    @Test
    fun `v25 database gains the v26 indexes`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v25.db").toString()
        // A v25 DB: today's tables without the v26 indexes.
        DatabaseService(dbPath, logger).also { it.migrate() }.close()
        val names = v26Indexes.values.flatten()
        val freshDefinitions =
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
                c.createStatement().use { indexDefinitions(it, names) }
            }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                for (name in names) st.execute("DROP INDEX $name")
                st.execute("PRAGMA user_version = 25")
                // Rows survive the upgrade untouched.
                st.execute(
                    "INSERT INTO dispatches (question_id, status, agent_name, answer_param, params, origin_process_id, " +
                        "origin_agent_name, created_at) VALUES ('q1', 'FAILED', 'a', 'x', '{}', 1, 'o', 5)",
                )
            }
        }
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                for ((table, indexes) in v26Indexes) {
                    assertTrue(
                        indexNames(st, table).containsAll(indexes),
                        "$table: ${indexNames(st, table)}",
                    )
                }
                // The migration's raw SQL and the Tables.kt declarations must define the same index.
                assertEquals(freshDefinitions, indexDefinitions(st, names))
                assertV26Plans(st)
                st.executeQuery("SELECT status, created_at FROM dispatches WHERE question_id = 'q1'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("FAILED", rs.getString(1))
                    assertEquals(5L, rs.getLong(2))
                }
            }
        }
        svc.close()
    }

    // --- continuations.thread_id (schema v27) ---

    /**
     * LangGraphRunStatusService.subs' two reads, each with the index that must serve it:
     * the thread's continuations (ContinuationRepository.byThread) and the batched sub-run read
     * (ProcessRepository.subRunProgress). Neither may scan or sort.
     */
    private val v27Shapes =
        listOf(
            "SELECT * FROM continuations WHERE thread_id = 't-7' AND owner = 'owner-2' ORDER BY id" to "continuations_thread_id",
            "SELECT id, agent_name, status, executions, current_command FROM processes WHERE id IN (3, 70, 1500)" to
                "INTEGER PRIMARY KEY",
        )

    private fun assertV27Plans(st: java.sql.Statement) {
        for ((sql, index) in v27Shapes) {
            val plan = plan(st, sql)
            assertTrue(plan.none { it.startsWith("SCAN ") }, "scan for '$sql': $plan")
            assertTrue(plan.any { "USING $index" in it || "INDEX $index" in it }, "'$sql' not planned on $index: $plan")
            assertTrue(plan.none { it.startsWith("USE TEMP B-TREE") }, "'$sql' sorts in a temp b-tree: $plan")
        }
    }

    /**
     * Plans the SQL [ContinuationRepository.byThread] (for an owned and an unowned run) and
     * [ProcessRepository.subRunProgress] actually send. The owner is a residual filter on
     * `continuations_thread_id`, never a seek on `continuations_owner`.
     */
    private suspend fun assertV27SentPlans(
        svc: DatabaseService,
        st: java.sql.Statement,
    ) {
        SqlStatementRecorder.reset()
        ContinuationRepository(svc).byThread("t-7", "owner-2")
        ContinuationRepository(svc).byThread("t-7", null)
        ProcessRepository(svc, ProcessJobRegistry()).subRunProgress(listOf(3, 70, 1500))
        val sent = SqlStatementRecorder.statements()
        assertEquals(3, sent.size, "$sent")
        for ((sql, index) in sent.zip(listOf("continuations_thread_id", "continuations_thread_id", "INTEGER PRIMARY KEY"))) {
            val plan = plan(st, sql)
            assertTrue(plan.none { it.startsWith("SCAN ") }, "scan for '$sql': $plan")
            assertTrue(plan.any { "USING $index" in it || "INDEX $index" in it }, "'$sql' not planned on $index: $plan")
            assertTrue(plan.none { it.startsWith("USE TEMP B-TREE") }, "'$sql' sorts in a temp b-tree: $plan")
        }
    }

    private fun threadIds(st: java.sql.Statement): Map<Int, String?> =
        st.executeQuery("SELECT id, thread_id FROM continuations ORDER BY id").use { rs ->
            generateSequence { if (rs.next()) rs.getInt(1) to rs.getString(2) else null }.toMap()
        }

    private fun columnType(
        st: java.sql.Statement,
        table: String,
        column: String,
    ): String =
        st.executeQuery("PRAGMA table_info($table)").use { rs ->
            generateSequence { if (rs.next()) rs.getString("name") to rs.getString("type") else null }
                .first { it.first == column }
                .second
        }

    @Test
    fun `fresh migrate plans the thread lookup and the batched sub-run read on their v27 indexes`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("fresh.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertTrue("continuations_thread_id" in indexNames(st, "continuations"), "${indexNames(st, "continuations")}")
                assertV27Plans(st)
            }
        }
        svc.close()
    }

    @Test
    fun `the thread lookup and the batched sub-run read stay on their index at 150k rows, with and without statistics`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("stats.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                val rows = "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 150000) "
                // Production-shaped: a third of the continuations belong to no LangGraph thread,
                // the rest spread over 5000 threads of about 20 continuations each, owned by a
                // handful of principals, with some unowned.
                st.execute(
                    rows +
                        "INSERT INTO continuations (watched_process_id, status, agent_name, resume_param, params, " +
                        "origin_process_id, origin_agent_name, created_at, thread_id, owner) SELECT i, " +
                        "CASE WHEN i % 1500 = 0 THEN 'PENDING' ELSE 'DISPATCHED' END, 'agent-' || (i % 12), 'r', '{}', 1, 'o', i, " +
                        "CASE WHEN i % 3 = 0 THEN NULL ELSE 't-' || (i % 5000) END, " +
                        "CASE WHEN i % 7 = 0 THEN NULL ELSE 'owner-' || (i % 5) END FROM n",
                )
                st.execute(
                    rows +
                        "INSERT INTO processes (status, agent_name, history, executions, created_at) " +
                        "SELECT CASE WHEN i % 33 = 0 THEN 'FAILED' ELSE 'FINISHED' END, 'agent-' || (i % 12), '[]', '[]', i FROM n",
                )
                assertV27Plans(st)
                assertV27SentPlans(svc, st)
            }
        }
        svc.optimize()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                val analyzed =
                    st.executeQuery("SELECT COUNT(*) FROM sqlite_stat1 WHERE tbl = 'continuations'").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                assertTrue(analyzed > 0, "no planner statistics for continuations")
                assertV27Plans(st)
                assertV27SentPlans(svc, st)
            }
        }
        svc.close()
    }

    @Test
    fun `v27 database gains the question read-grant index`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v27.db").toString()
        val names = listOf("questions_agent_name")
        DatabaseService(dbPath, logger).also { it.migrate() }.close()
        val fresh = DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c -> c.createStatement().use { indexDefinitions(it, names) } }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.execute("DROP INDEX questions_agent_name")
                st.execute("PRAGMA user_version = 27")
            }
        }
        DatabaseService(dbPath, logger).also { it.migrate() }.close()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                assertEquals(fresh, indexDefinitions(st, names))
            }
        }
    }

    @Test
    fun `every question page shape stays on an index with no sort at 150k rows, with and without statistics`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("questions.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                // Production-shaped: mostly answered questions across a few agents, a few open; one
                // process in five owned by alice.
                val rows = "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 150000) "
                st.execute(
                    rows +
                        "INSERT INTO processes (status, agent_name, owner, history, executions, created_at) " +
                        "SELECT 'FINISHED', 'agent-' || (i % 12), CASE i % 5 WHEN 0 THEN 'alice' END, '[]', '[]', i FROM n",
                )
                st.execute(
                    rows +
                        "INSERT INTO questions (id, process_id, agent_name, text, options, status, created_at, dispatch_bound) " +
                        "SELECT 'q' || i, i, 'agent-' || (i % 12), 't', '[]', " +
                        "CASE WHEN i % 1500 = 0 THEN 'OPEN' ELSE 'ANSWERED' END, i, 0 FROM n",
                )
            }
        }
        assertQuestionPagePlans(svc, dbPath)
        svc.optimize()
        assertQuestionPagePlans(svc, dbPath)
        svc.close()
    }

    // Plans the statements QuestionsRepository.page sends, a count and a page per case: every arm
    // seeks an index, the page is not sorted, and only the unfiltered admin page walks questions in rowid order.
    private suspend fun assertQuestionPagePlans(
        svc: DatabaseService,
        dbPath: String,
    ) {
        val repo = QuestionsRepository(svc)
        val cases =
            listOf(
                Triple(null, null, null),
                Triple(QuestionsService.QuestionStatus.OPEN, null, null),
                Triple(null, 75, null),
                Triple(null, null, "alice" to setOf("agent-1", "agent-2")),
                Triple(QuestionsService.QuestionStatus.OPEN, null, "alice" to setOf("agent-1")),
                Triple(null, null, "alice" to emptySet()),
                Triple(null, null, null to setOf("agent-3")),
            )
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                for ((status, processId, visibility) in cases) {
                    SqlStatementRecorder.reset()
                    repo.page(status, processId, visibility?.first, visibility?.second, offset = 0, limit = 100)
                    val sent = SqlStatementRecorder.statements().filter { it.startsWith("SELECT") && " FROM questions" in it }
                    assertEquals(2, sent.size, "$status/$processId/$visibility ran: ${SqlStatementRecorder.statements()}")
                    for (sql in sent) {
                        val plan = plan(st, sql)
                        val unfilteredAdmin = status == null && processId == null && visibility == null
                        if (!unfilteredAdmin) assertTrue(plan.none { it == "SCAN questions" }, "table scan for '$sql': $plan")
                        // parent 0 is the outer read; the UNION's own merge sorts inside the rowid subquery.
                        val outer =
                            st
                                .executeQuery("EXPLAIN QUERY PLAN $sql")
                                .use { rs ->
                                    generateSequence { if (rs.next()) rs.getInt("parent") to rs.getString("detail") else null }.toList()
                                }.filter { it.first == 0 }
                                .map { it.second }
                        assertTrue(outer.none { it.startsWith("USE TEMP B-TREE") }, "'$sql' sorts the page: $plan")
                        if (visibility?.second?.isNotEmpty() == true) {
                            assertTrue(plan.any { "questions_agent_name" in it }, "'$sql' not on questions_agent_name: $plan")
                        }
                        if (visibility?.first !=
                            null
                        ) {
                            assertTrue(plan.any { "processes_owner" in it }, "'$sql' not on processes_owner: $plan")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `v26 database gains continuations thread_id, backfilled from params, and its index`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v26.db").toString()
        val params = MapSerializer(String.serializer(), String.serializer())
        DatabaseService(dbPath, logger).also { it.migrate() }.close()
        val (freshIndex, freshType) =
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
                c.createStatement().use { st ->
                    indexDefinitions(st, listOf("continuations_thread_id")) to columnType(st, "continuations", "thread_id")
                }
            }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                // A v26 DB: continuations without thread_id or its index.
                st.execute("DROP INDEX continuations_thread_id")
                st.execute("ALTER TABLE continuations DROP COLUMN thread_id")
                st.execute("PRAGMA user_version = 26")
                // params exactly as ContinuationRepository writes them: a columnJson-encoded
                // String→String object. The thread key is HMR_LG_THREAD (EnvConstants.LG_THREAD).
                val rows =
                    listOf(
                        "SINGLE" to mapOf("HMR_LG_THREAD" to "t-a", "HMR_LG_INTERRUPT" to "i1"),
                        "JOIN" to mapOf("HMR_LG_THREAD" to "t-b"),
                        "SINGLE" to emptyMap(),
                        "SINGLE" to mapOf("other" to "x"),
                        // A JSON-escaped value: backfilled as the decoded string, like the Kotlin side reads it.
                        "SINGLE" to mapOf("HMR_LG_THREAD" to "t-\"q\"-ž\\", "x" to "y"),
                    )
                for ((kind, p) in rows) {
                    val json = columnJson.encodeToString(params, p).replace("'", "''")
                    st.execute(
                        "INSERT INTO continuations (watched_process_id, status, agent_name, resume_param, params, " +
                            "origin_process_id, origin_agent_name, created_at, kind) " +
                            "VALUES (5, 'DISPATCHED', 'handler', 'r', '$json', 1, 'asker', 100, '$kind')",
                    )
                }
                // A hand-edited row whose params are not JSON must not fail the upgrade.
                st.execute(
                    "INSERT INTO continuations (watched_process_id, status, agent_name, resume_param, params, " +
                        "origin_process_id, origin_agent_name, created_at) VALUES (5, 'FAILED', 'handler', 'r', 'not-json', 1, 'a', 1)",
                )
            }
        }
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                assertEquals(
                    mapOf(1 to "t-a", 2 to "t-b", 3 to null, 4 to null, 5 to "t-\"q\"-ž\\", 6 to null),
                    threadIds(st),
                )
                // The migration's raw DDL and the Tables.kt declaration define the same column and index.
                assertEquals(freshType, columnType(st, "continuations", "thread_id"))
                assertEquals(freshIndex, indexDefinitions(st, listOf("continuations_thread_id")))
                assertV27Plans(st)
                // Re-running the step on an already-upgraded file is a no-op, not a duplicate-column failure.
                st.execute("PRAGMA user_version = 26")
            }
        }
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                assertEquals(6, threadIds(st).size)
                assertEquals("t-a", threadIds(st)[1])
            }
        }
    }

    @Test
    fun `v20 database gains processes cost_source defaulting to cli`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v20.db").toString()
        // Simulate a v20 DB: processes present without cost_source (ADR-0061).
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE processes (id INTEGER PRIMARY KEY AUTOINCREMENT, status VARCHAR(16), " +
                        "agent_name VARCHAR(255), current_command TEXT, current_command_start INTEGER, " +
                        "purge_at INTEGER, record_purge_at INTEGER, cost_usd REAL, artifacts_dir TEXT, " +
                        "input_params TEXT, history TEXT, executions TEXT, runner_state TEXT, owner TEXT, " +
                        "trace_id VARCHAR(32), created_at INTEGER, ended_at INTEGER, secret_input_keys TEXT, " +
                        "retain_artifacts TEXT, workspace_purged_at INTEGER, retained_truncated INTEGER, " +
                        "parent_process_id INTEGER)",
                )
                st.execute(
                    "INSERT INTO processes (status, agent_name, input_params, history, executions, cost_usd) " +
                        "VALUES ('FINISHED', 'a', '{}', '[]', '[]', 0.5)",
                )
                st.execute("PRAGMA user_version = 20")
            }
        }
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                val src =
                    st.executeQuery("SELECT cost_source FROM processes").use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                assertEquals("cli", src)
            }
        }
        svc.close()
    }

    @Test
    fun `v19 database upgrades to current schema adding the process_tags table without data loss`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v19.db").toString()
        // Simulate a v19 DB: processes present, no process_tags table (ADR-0060).
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE processes (id INTEGER PRIMARY KEY AUTOINCREMENT, status VARCHAR(16), " +
                        "agent_name VARCHAR(255), current_command TEXT, current_command_start INTEGER, " +
                        "purge_at INTEGER, record_purge_at INTEGER, cost_usd REAL, artifacts_dir TEXT, " +
                        "input_params TEXT, history TEXT, executions TEXT, runner_state TEXT, owner TEXT, " +
                        "trace_id VARCHAR(32), created_at INTEGER, ended_at INTEGER, secret_input_keys TEXT, " +
                        "retain_artifacts TEXT, workspace_purged_at INTEGER, retained_truncated INTEGER, " +
                        "parent_process_id INTEGER)",
                )
                st.execute(
                    "INSERT INTO processes (status, agent_name, purge_at, cost_usd, input_params, history, executions) " +
                        "VALUES ('FINISHED', 'a', 500, 0.0, '{}', '[]', '[]')",
                )
                st.execute("PRAGMA user_version = 19")
            }
        }
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                assertTrue("process_tags" in tableNames(st))
                val (surviving, tags) =
                    st.executeQuery("SELECT COUNT(*), MAX(tag) FROM process_tags").use {
                        it.next()
                        it.getInt(1) to it.getString(2)
                    }
                // The migrated run survives; it simply carries no tags (empty child table).
                assertEquals(0, surviving)
                assertEquals(null, tags)
                val processes =
                    st.executeQuery("SELECT COUNT(*) FROM processes").use {
                        it.next()
                        it.getInt(1)
                    }
                assertEquals(1, processes)
            }
        }

        // End-to-end: ProcessRepository hydrates a migrated row with an empty tag list.
        val db = DatabaseService(dbPath, logger)
        val repo = ProcessRepository(db, ProcessJobRegistry())
        val info = repo.getInfo(1)
        assertNotNull(info)
        assertEquals(emptyList(), info.tags)
        db.close()
    }

    @Test
    fun `v7 database upgrades to current schema adding processes owner without data loss`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v7.db").toString()
        // Simulate a v7 DB: processes present without owner, user_version=7.
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE processes (id INTEGER PRIMARY KEY AUTOINCREMENT, status VARCHAR(16), " +
                        "agent_name VARCHAR(255), current_command TEXT, current_command_start INTEGER, " +
                        "purge_at INTEGER, cost_usd REAL, artifacts_dir TEXT, input_params TEXT, " +
                        "history TEXT, executions TEXT, runner_state TEXT)",
                )
                st.execute(
                    "INSERT INTO processes (status, agent_name, input_params, history, executions) " +
                        "VALUES ('FINISHED', 'a', '{}', '[]', '[]')",
                )
                st.execute("PRAGMA user_version = 7")
            }
        }
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                // End-to-end chain assertion, not just this hop's column. `SchemaUtils.create`
                // never ALTERs an existing table, so every column below reached this v1
                // `processes` table through a migration block. Comparing against the model's
                // own column list is what makes a wrong guard fail here: a block written as
                // `current in 12 until 13` instead of `1 until 13` still passes its own
                // v12 test, and only this one notices that v1 never got the column.
                val expected = Processes.columns.map { it.name }.toSet()
                val actual = columnNames(st, "processes").toSet()
                assertEquals(
                    emptySet(),
                    expected - actual,
                    "migrating from v1 must produce every column the model declares",
                )
                val (surviving, owner) =
                    st.executeQuery("SELECT COUNT(*), MAX(owner) FROM processes").use {
                        it.next()
                        it.getInt(1) to it.getString(2)
                    }
                assertEquals(1, surviving)
                assertEquals(null, owner)
            }
        }
    }

    @Test
    fun `v1 database upgrades to current schema adding column, tables and processes owner without data loss`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v1.db").toString()
        // Simulate a v1 DB: questions table without dispatch_bound, processes table without
        // owner (processes has existed since v1 — regression coverage for the guard's lower
        // bound, which must include v1/v2, not just v3+ like the sibling blocks), user_version=1.
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE questions (id VARCHAR(64) PRIMARY KEY, process_id INTEGER, " +
                        "agent_name VARCHAR(255), text TEXT, options TEXT, status VARCHAR(16), " +
                        "answer TEXT, created_at INTEGER, answered_at INTEGER)",
                )
                st.execute("INSERT INTO questions VALUES ('q1', 7, 'a', 't', '[]', 'OPEN', NULL, 100, NULL)")
                st.execute(
                    "CREATE TABLE processes (id INTEGER PRIMARY KEY AUTOINCREMENT, status VARCHAR(16), " +
                        "agent_name VARCHAR(255), current_command TEXT, current_command_start INTEGER, " +
                        "purge_at INTEGER, cost_usd REAL, artifacts_dir TEXT, input_params TEXT, " +
                        "history TEXT, executions TEXT, runner_state TEXT)",
                )
                st.execute(
                    "INSERT INTO processes (status, agent_name, input_params, history, executions) " +
                        "VALUES ('FINISHED', 'a', '{}', '[]', '[]')",
                )
                st.execute("PRAGMA user_version = 1")
            }
        }
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                val bound =
                    st.executeQuery("SELECT dispatch_bound FROM questions WHERE id='q1'").use {
                        it.next()
                        it.getInt(1)
                    }
                assertEquals(0, bound)
                val tables = tableNames(st)
                assertTrue("dispatches" in tables)
                assertTrue("continuations" in tables)
                assertTrue("continuation_watches" in tables)
                assertTrue("result_tokens" in tables)
                // End-to-end chain assertion, not just this hop's column. `SchemaUtils.create`
                // never ALTERs an existing table, so every column below reached this v1
                // `processes` table through a migration block. Comparing against the model's
                // own column list is what makes a wrong guard fail here: a block written as
                // `current in 12 until 13` instead of `1 until 13` still passes its own
                // v12 test, and only this one notices that v1 never got the column.
                val expected = Processes.columns.map { it.name }.toSet()
                val actual = columnNames(st, "processes").toSet()
                assertEquals(
                    emptySet(),
                    expected - actual,
                    "migrating from v1 must produce every column the model declares",
                )
                val (surviving, owner) =
                    st.executeQuery("SELECT COUNT(*), MAX(owner) FROM processes").use {
                        it.next()
                        it.getInt(1) to it.getString(2)
                    }
                assertEquals(1, surviving)
                assertEquals(null, owner)
            }
        }
    }

    @Test
    fun `v3 database upgrades to current schema adding result_tokens, watches and kind without data loss`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v3.db").toString()
        // Simulate a v3 DB: continuations present, no result_tokens, user_version=3.
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE continuations (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "watched_process_id INTEGER, status VARCHAR(16), agent_name VARCHAR(255), " +
                        "resume_param VARCHAR(255), params TEXT, result_transformer TEXT, " +
                        "origin_process_id INTEGER, origin_agent_name VARCHAR(255), expires_at INTEGER, " +
                        "created_at INTEGER, fired_at INTEGER, fired_process_id INTEGER, error TEXT)",
                )
                st.execute(
                    "INSERT INTO continuations (watched_process_id, status, agent_name, resume_param, params, " +
                        "origin_process_id, origin_agent_name, created_at) " +
                        "VALUES (5, 'PENDING', 'handler', 'HMR_LG_RESUME', '{}', 1, 'asker', 100)",
                )
                st.execute("PRAGMA user_version = 3")
            }
        }
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                assertTrue("result_tokens" in tableNames(st))
                assertTrue("continuation_watches" in tableNames(st))
                assertTrue("kind" in columnNames(st, "continuations"))
                assertTrue("consumer_agent" in columnNames(st, "result_tokens"))
                val (surviving, kind) =
                    st.executeQuery("SELECT COUNT(*), MAX(kind) FROM continuations").use {
                        it.next()
                        it.getInt(1) to it.getString(2)
                    }
                assertEquals(1, surviving)
                // The pre-existing row gets the SINGLE default from the guarded ALTER.
                assertEquals("SINGLE", kind)
            }
        }
    }

    @Test
    fun `v6 database upgrades to current schema adding result_tokens consumer_agent without data loss`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v6.db").toString()
        // Simulate a v6 DB: result_tokens present without consumer_agent, user_version=6.
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE result_tokens (token VARCHAR(64) PRIMARY KEY, watched_process_id INTEGER, " +
                        "path TEXT, result_transformer TEXT, size_bytes INTEGER, created_at INTEGER, expires_at INTEGER)",
                )
                st.execute("INSERT INTO result_tokens VALUES ('tok-1', 5, 'cmd_0.out', NULL, 99999, 100, NULL)")
                st.execute("PRAGMA user_version = 6")
            }
        }
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                assertTrue("consumer_agent" in columnNames(st, "result_tokens"))
                val (surviving, audience) =
                    st.executeQuery("SELECT COUNT(*), MAX(consumer_agent) FROM result_tokens").use {
                        it.next()
                        it.getInt(1) to it.getString(2)
                    }
                assertEquals(1, surviving)
                // The pre-existing (ephemeral) token gets the empty default and so matches no
                // agent — it fails closed until the TTL sweep removes it.
                assertEquals("", audience)
            }
        }
    }

    @Test
    fun `v9 database upgrades to current schema adding continuation retry columns without data loss`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v9.db").toString()
        // Simulate a v9 DB: continuations present without fire_attempts / next_attempt_at.
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE continuations (id INTEGER PRIMARY KEY AUTOINCREMENT, watched_process_id INTEGER, " +
                        "status VARCHAR(16), agent_name VARCHAR(255), resume_param VARCHAR(255), params TEXT, " +
                        "result_transformer TEXT, origin_process_id INTEGER, origin_agent_name VARCHAR(255), " +
                        "expires_at INTEGER, created_at INTEGER, fired_at INTEGER, fired_process_id INTEGER, " +
                        "error TEXT, kind VARCHAR(16) DEFAULT 'SINGLE', owner TEXT)",
                )
                st.execute(
                    "INSERT INTO continuations (watched_process_id, status, agent_name, resume_param, params, " +
                        "origin_process_id, origin_agent_name, created_at) " +
                        "VALUES (42, 'PENDING', 'h', 'HMR_LG_RESUME', '{}', 7, 'asker', 1)",
                )
                st.execute("PRAGMA user_version = 9")
            }
        }
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                val columns = columnNames(st, "continuations")
                assertTrue("fire_attempts" in columns)
                assertTrue("next_attempt_at" in columns)
                // The pre-existing row survives with the pre-upgrade behaviour: no attempts
                // spent and no scheduled retry, so the sweep never picks it up.
                val (surviving, attempts, next) =
                    st.executeQuery("SELECT COUNT(*), MAX(fire_attempts), MAX(next_attempt_at) FROM continuations").use {
                        it.next()
                        Triple(it.getInt(1), it.getInt(2), it.getString(3))
                    }
                assertEquals(1, surviving)
                assertEquals(0, attempts)
                assertEquals(null, next)
            }
        }
    }

    @Test
    fun `v10 database upgrades to current schema adding processes trace_id without data loss`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v10.db").toString()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE processes (id INTEGER PRIMARY KEY AUTOINCREMENT, status VARCHAR(16), " +
                        "agent_name VARCHAR(255), current_command TEXT, current_command_start INTEGER, " +
                        "purge_at INTEGER, cost_usd REAL, artifacts_dir TEXT, input_params TEXT, " +
                        "history TEXT, executions TEXT, runner_state TEXT, owner TEXT)",
                )
                st.execute(
                    "INSERT INTO processes (status, agent_name, input_params, history, executions) " +
                        "VALUES ('FINISHED', 'a', '{}', '[]', '[]')",
                )
                st.execute("PRAGMA user_version = 10")
            }
        }
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                assertTrue("trace_id" in columnNames(st, "processes"))
                val (surviving, traceId) =
                    st.executeQuery("SELECT COUNT(*), MAX(trace_id) FROM processes").use {
                        it.next()
                        it.getInt(1) to it.getString(2)
                    }
                assertEquals(1, surviving)
                // A run from before the upgrade has no trace id, so it gets no link.
                assertEquals(null, traceId)
            }
        }
    }

    @Test
    fun `v11 database upgrades to current schema adding processes record_purge_at without data loss`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v11.db").toString()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE processes (id INTEGER PRIMARY KEY AUTOINCREMENT, status VARCHAR(16), " +
                        "agent_name VARCHAR(255), current_command TEXT, current_command_start INTEGER, " +
                        "purge_at INTEGER, cost_usd REAL, artifacts_dir TEXT, input_params TEXT, " +
                        "history TEXT, executions TEXT, runner_state TEXT, owner TEXT, trace_id VARCHAR(32))",
                )
                st.execute(
                    "INSERT INTO processes (status, agent_name, purge_at, input_params, history, executions) " +
                        "VALUES ('FINISHED', 'a', 500, '{}', '[]', '[]')",
                )
                st.execute("PRAGMA user_version = 11")
            }
        }
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                assertTrue("record_purge_at" in columnNames(st, "processes"))
                val (surviving, recordPurgeAt) =
                    st.executeQuery("SELECT COUNT(*), MAX(record_purge_at) FROM processes").use {
                        it.next()
                        it.getInt(1) to it.getString(2)
                    }
                assertEquals(1, surviving)
                // NULL, which the record sweep reads as "same as purge_at" — the row keeps
                // exactly its pre-upgrade retention.
                assertEquals(null, recordPurgeAt)
            }
        }
    }

    @Test
    fun `v12 database upgrades to current schema adding processes created_at without data loss`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v12.db").toString()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE processes (id INTEGER PRIMARY KEY AUTOINCREMENT, status VARCHAR(16), " +
                        "agent_name VARCHAR(255), current_command TEXT, current_command_start INTEGER, " +
                        "purge_at INTEGER, record_purge_at INTEGER, cost_usd REAL, artifacts_dir TEXT, " +
                        "input_params TEXT, history TEXT, executions TEXT, runner_state TEXT, owner TEXT, " +
                        "trace_id VARCHAR(32))",
                )
                st.execute(
                    "INSERT INTO processes (status, agent_name, purge_at, input_params, history, executions) " +
                        "VALUES ('FINISHED', 'a', 500, '{}', '[[100,\"CREATED\"],[200,\"FINISHED\"]]', '[]')",
                )
                st.execute(
                    "INSERT INTO processes (status, agent_name, purge_at, input_params, history, executions) " +
                        "VALUES ('FINISHED', 'b', 500, '{}', '[]', '[]')",
                )
                st.execute("PRAGMA user_version = 12")
            }
        }
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                assertTrue("created_at" in columnNames(st, "processes"))
                val (surviving, createdAt) =
                    st.executeQuery("SELECT COUNT(*), MAX(created_at) FROM processes").use {
                        it.next()
                        it.getInt(1) to it.getString(2)
                    }
                assertEquals(2, surviving)
                // Backfilled from the first history entry, so the cooldown keeps binding
                // across the upgrade instead of going blind for one run per agent.
                assertEquals("100", createdAt)
                val emptyHistoryCreatedAt =
                    st.executeQuery("SELECT created_at FROM processes WHERE agent_name='b'").use {
                        it.next()
                        it.getString(1)
                    }
                // A row with no history has no recoverable start time and stays NULL.
                assertEquals(null, emptyHistoryCreatedAt)
            }
        }
    }

    @Test
    fun `v13 database upgrades to current schema adding processes ended_at without data loss`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v13.db").toString()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE processes (id INTEGER PRIMARY KEY AUTOINCREMENT, status VARCHAR(16), " +
                        "agent_name VARCHAR(255), current_command TEXT, current_command_start INTEGER, " +
                        "purge_at INTEGER, record_purge_at INTEGER, cost_usd REAL, artifacts_dir TEXT, " +
                        "input_params TEXT, history TEXT, executions TEXT, runner_state TEXT, owner TEXT, " +
                        "trace_id VARCHAR(32), created_at INTEGER)",
                )
                st.execute(
                    "INSERT INTO processes (status, agent_name, purge_at, input_params, history, executions) " +
                        "VALUES ('FAILED', 'a', 500, '{}', '[[100,\"CREATED\"],[200,\"FAILED\"]]', '[]')",
                )
                st.execute(
                    "INSERT INTO processes (status, agent_name, purge_at, input_params, history, executions) " +
                        "VALUES ('WORKING', 'b', 500, '{}', '[[100,\"CREATED\"]]', '[]')",
                )
                st.execute("PRAGMA user_version = 13")
            }
        }
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                assertTrue("ended_at" in columnNames(st, "processes"))
                val (surviving, endedAt) =
                    st.executeQuery("SELECT COUNT(*), MAX(ended_at) FROM processes").use {
                        it.next()
                        it.getInt(1) to it.getString(2)
                    }
                assertEquals(2, surviving)
                // Backfilled from the last history entry of the terminal row.
                assertEquals("200", endedAt)
                val activeEndedAt =
                    st.executeQuery("SELECT ended_at FROM processes WHERE agent_name='b'").use {
                        it.next()
                        it.getString(1)
                    }
                // An active run has not ended, so it must stay NULL.
                assertEquals(null, activeEndedAt)
            }
        }
    }

    @Test
    fun `v14 database upgrades to current schema adding processes secret_input_keys without data loss`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v14.db").toString()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE processes (id INTEGER PRIMARY KEY AUTOINCREMENT, status VARCHAR(16), " +
                        "agent_name VARCHAR(255), current_command TEXT, current_command_start INTEGER, " +
                        "purge_at INTEGER, record_purge_at INTEGER, cost_usd REAL, artifacts_dir TEXT, " +
                        "input_params TEXT, history TEXT, executions TEXT, runner_state TEXT, owner TEXT, " +
                        "trace_id VARCHAR(32), created_at INTEGER, ended_at INTEGER)",
                )
                st.execute(
                    "INSERT INTO processes (status, agent_name, purge_at, input_params, history, executions) " +
                        "VALUES ('FINISHED', 'a', 500, '{\"TOKEN\":\"v\"}', '[]', '[]')",
                )
                st.execute("PRAGMA user_version = 14")
            }
        }
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                assertTrue("secret_input_keys" in columnNames(st, "processes"))
                val (surviving, keys) =
                    st.executeQuery("SELECT COUNT(*), MAX(secret_input_keys) FROM processes").use {
                        it.next()
                        it.getInt(1) to it.getString(2)
                    }
                assertEquals(1, surviving)
                // NULL, so the row keeps deriving redaction from the current agent
                // definition — its pre-upgrade behaviour, not a blanket redaction.
                assertEquals(null, keys)
            }
        }
    }

    @Test
    fun `v15 database upgrades to current schema adding dispatch and continuation secret_input_keys`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v15.db").toString()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE dispatches (id INTEGER PRIMARY KEY AUTOINCREMENT, question_id VARCHAR(64), " +
                        "status VARCHAR(16), agent_name VARCHAR(255), answer_param VARCHAR(255), params TEXT, " +
                        "origin_process_id INTEGER, origin_agent_name VARCHAR(255), expires_at INTEGER, " +
                        "created_at INTEGER, dispatched_at INTEGER, dispatched_process_id INTEGER, error TEXT, owner TEXT)",
                )
                st.execute(
                    "INSERT INTO dispatches (question_id, status, agent_name, answer_param, params, " +
                        "origin_process_id, origin_agent_name, created_at) " +
                        "VALUES ('q1', 'PENDING', 'a', 'ans', '{}', 1, 'o', 100)",
                )
                st.execute("PRAGMA user_version = 15")
            }
        }
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                assertTrue("secret_input_keys" in columnNames(st, "dispatches"))
                assertTrue("secret_input_keys" in columnNames(st, "continuations"))
                val (surviving, keys) =
                    st.executeQuery("SELECT COUNT(*), MAX(secret_input_keys) FROM dispatches").use {
                        it.next()
                        it.getInt(1) to it.getString(2)
                    }
                assertEquals(1, surviving)
                // NULL: a row registered before the column falls back to the target agent's
                // flags as they stand when it fires — the behaviour it already had.
                assertEquals(null, keys)
            }
        }
    }

    @Test
    fun `v16 database upgrades to current schema adding dispatch and continuation secret_param_names`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v16.db").toString()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE dispatches (id INTEGER PRIMARY KEY AUTOINCREMENT, question_id VARCHAR(64), " +
                        "status VARCHAR(16), agent_name VARCHAR(255), answer_param VARCHAR(255), params TEXT, " +
                        "origin_process_id INTEGER, origin_agent_name VARCHAR(255), expires_at INTEGER, " +
                        "created_at INTEGER, dispatched_at INTEGER, dispatched_process_id INTEGER, error TEXT, owner TEXT, " +
                        "secret_input_keys TEXT)",
                )
                st.execute(
                    "INSERT INTO dispatches (question_id, status, agent_name, answer_param, params, " +
                        "origin_process_id, origin_agent_name, created_at, secret_input_keys) " +
                        "VALUES ('q1', 'PENDING', 'a', 'ans', '{}', 1, 'o', 100, '[\"AUTH_TOKEN\"]')",
                )
                st.execute("PRAGMA user_version = 16")
            }
        }
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                assertTrue("secret_param_names" in columnNames(st, "dispatches"))
                assertTrue("secret_param_names" in columnNames(st, "continuations"))
                val (surviving, paramNames, envNames) =
                    st.executeQuery("SELECT COUNT(*), MAX(secret_param_names), MAX(secret_input_keys) FROM dispatches").use {
                        it.next()
                        Triple(it.getInt(1), it.getString(2), it.getString(3))
                    }
                assertEquals(1, surviving)
                // NULL: a row registered before the column falls back to the envName-only
                // check redactStoredParams already had — the residual this migration accepts
                // for rows it cannot retroactively snapshot. The pre-existing envName snapshot
                // survives the migration untouched.
                assertEquals(null, paramNames)
                assertEquals("[\"AUTH_TOKEN\"]", envNames)
            }
        }
    }

    @Test
    fun `v17 database upgrades to current schema adding the retain-block columns without data loss`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v17.db").toString()
        // Simulate a v17 DB: processes present without retain_artifacts / workspace_purged_at /
        // retained_truncated (ADR-0053), user_version=17.
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE processes (id INTEGER PRIMARY KEY AUTOINCREMENT, status VARCHAR(16), " +
                        "agent_name VARCHAR(255), current_command TEXT, current_command_start INTEGER, " +
                        "purge_at INTEGER, record_purge_at INTEGER, cost_usd REAL, artifacts_dir TEXT, " +
                        "input_params TEXT, history TEXT, executions TEXT, runner_state TEXT, owner TEXT, " +
                        "trace_id VARCHAR(32), created_at INTEGER, ended_at INTEGER, secret_input_keys TEXT)",
                )
                st.execute(
                    "INSERT INTO processes (status, agent_name, artifacts_dir, purge_at, cost_usd, input_params, history, executions) " +
                        "VALUES ('FINISHED', 'a', '/artifacts/1', 500, 0.0, '{}', '[]', '[]')",
                )
                st.execute("PRAGMA user_version = 17")
            }
        }
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                val columns = columnNames(st, "processes")
                assertTrue("retain_artifacts" in columns)
                assertTrue("workspace_purged_at" in columns)
                assertTrue("retained_truncated" in columns)
                val (surviving, retainArtifacts, workspacePurgedAt) =
                    st
                        .executeQuery(
                            "SELECT COUNT(*), MAX(retain_artifacts), MAX(workspace_purged_at) FROM processes",
                        ).use {
                            it.next()
                            Triple(it.getInt(1), it.getString(2), it.getString(3))
                        }
                assertEquals(1, surviving)
                // NULL: retain_artifacts IS NULL keeps doubling as "nothing retained, already
                // swept" and workspace_purged_at as "retained row swept" — a migrated row must
                // read as neither, its pre-upgrade behaviour, not a fabricated default.
                assertEquals(null, retainArtifacts)
                assertEquals(null, workspacePurgedAt)
            }
        }

        // End-to-end: ProcessRepository hydrates the new columns from a migrated row as the
        // "nothing retained" defaults, not as an exception or a non-null sentinel.
        val db = DatabaseService(dbPath, logger)
        val repo = ProcessRepository(db, ProcessJobRegistry())
        val info = repo.getInfo(1)
        assertNotNull(info)
        assertEquals(emptyList(), info.retainArtifacts)
        assertEquals(null, info.retainedTruncated)
        db.close()
    }

    @Test
    fun `v18 database upgrades to current schema adding processes parent_process_id without data loss`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v18.db").toString()
        // Simulate a v18 DB: processes present without parent_process_id (ADR-0058).
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE processes (id INTEGER PRIMARY KEY AUTOINCREMENT, status VARCHAR(16), " +
                        "agent_name VARCHAR(255), current_command TEXT, current_command_start INTEGER, " +
                        "purge_at INTEGER, record_purge_at INTEGER, cost_usd REAL, artifacts_dir TEXT, " +
                        "input_params TEXT, history TEXT, executions TEXT, runner_state TEXT, owner TEXT, " +
                        "trace_id VARCHAR(32), created_at INTEGER, ended_at INTEGER, secret_input_keys TEXT, " +
                        "retain_artifacts TEXT, workspace_purged_at INTEGER, retained_truncated INTEGER)",
                )
                st.execute(
                    "INSERT INTO processes (status, agent_name, purge_at, cost_usd, input_params, history, executions) " +
                        "VALUES ('FINISHED', 'a', 500, 0.0, '{}', '[]', '[]')",
                )
                st.execute("PRAGMA user_version = 18")
            }
        }
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                assertTrue("parent_process_id" in columnNames(st, "processes"))
                val (surviving, parent) =
                    st.executeQuery("SELECT COUNT(*), MAX(parent_process_id) FROM processes").use {
                        it.next()
                        it.getInt(1) to it.getString(2)
                    }
                assertEquals(1, surviving)
                // NULL = no known parent, the pre-upgrade meaning; never a fabricated 0.
                assertEquals(null, parent)
            }
        }

        val db = DatabaseService(dbPath, logger)
        val repo = ProcessRepository(db, ProcessJobRegistry())
        val info = repo.getInfo(1)
        assertNotNull(info)
        assertEquals(null, info.parentProcessId)
        db.close()
    }

    @Test
    fun `v21 database upgrades to current schema adding processes root_process_id without backfilling`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v21.db").toString()
        // Simulate a v21 DB: processes present without root_process_id (ADR-0058). Row 1 is
        // a root (no parent), row 2 is a child of row 1 — so a backfill would have had
        // something to resolve for row 2, and the test pins that it deliberately did not.
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE processes (id INTEGER PRIMARY KEY AUTOINCREMENT, status VARCHAR(16), " +
                        "agent_name VARCHAR(255), current_command TEXT, current_command_start INTEGER, " +
                        "purge_at INTEGER, record_purge_at INTEGER, cost_usd REAL, artifacts_dir TEXT, " +
                        "input_params TEXT, history TEXT, executions TEXT, runner_state TEXT, owner TEXT, " +
                        "trace_id VARCHAR(32), created_at INTEGER, ended_at INTEGER, secret_input_keys TEXT, " +
                        "retain_artifacts TEXT, workspace_purged_at INTEGER, retained_truncated INTEGER, " +
                        "parent_process_id INTEGER, cost_source TEXT)",
                )
                st.execute(
                    "INSERT INTO processes (status, agent_name, purge_at, cost_usd, input_params, history, " +
                        "executions, parent_process_id, cost_source) " +
                        "VALUES ('FINISHED', 'a', 500, 0.0, '{}', '[]', '[]', NULL, 'cli')",
                )
                st.execute(
                    "INSERT INTO processes (status, agent_name, purge_at, cost_usd, input_params, history, " +
                        "executions, parent_process_id, cost_source) " +
                        "VALUES ('FINISHED', 'b', 500, 0.0, '{}', '[]', '[]', 1, 'cli')",
                )
                st.execute("PRAGMA user_version = 21")
            }
        }
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                assertTrue("root_process_id" in columnNames(st, "processes"))
                val (surviving, maxRoot) =
                    st.executeQuery("SELECT COUNT(*), MAX(root_process_id) FROM processes").use {
                        it.next()
                        it.getInt(1) to it.getString(2)
                    }
                assertEquals(2, surviving)
                // Left NULL rather than backfilled: a pre-v22 parent row with no root of its
                // own is indistinguishable from a root, and an evicted/purged parent could not
                // be resolved at all — so NULL reads as "flow root unknown", which is the truth.
                assertEquals(null, maxRoot)
            }
        }

        // Re-opening an already-migrated file is a no-op: the guarded block sees the column
        // and must not attempt a second ALTER TABLE.
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                assertEquals(1, columnNames(st, "processes").count { it == "root_process_id" })
            }
        }

        val db = DatabaseService(dbPath, logger)
        val repo = ProcessRepository(db, ProcessJobRegistry())
        assertEquals(null, repo.getInfo(1)?.rootProcessId)
        assertEquals(null, repo.getInfo(2)?.rootProcessId)
        db.close()
    }

    /** A v24 `processes` table: every column of that version, including the inline input_params. */
    private val v24ProcessesColumns =
        "CREATE TABLE processes (id INTEGER PRIMARY KEY AUTOINCREMENT, status VARCHAR(16), " +
            "agent_name VARCHAR(255), current_command TEXT, current_command_start INTEGER, " +
            "purge_at INTEGER, record_purge_at INTEGER, cost_usd REAL, cost_source TEXT NOT NULL DEFAULT 'cli', " +
            "artifacts_dir TEXT, input_params TEXT, history TEXT, executions TEXT, runner_state TEXT, " +
            "owner TEXT, trace_id VARCHAR(32), created_at INTEGER, ended_at INTEGER, secret_input_keys TEXT, " +
            "retain_artifacts TEXT, workspace_purged_at INTEGER, retained_truncated INTEGER, " +
            "parent_process_id INTEGER, root_process_id INTEGER)"

    @Test
    fun `v24 database moves input_params into process_inputs without data loss`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v24.db").toString()
        // A v24 DB: inputs inline in the processes row (ADR-0020).
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.execute(v24ProcessesColumns)
                st.execute(
                    "INSERT INTO processes (status, agent_name, cost_usd, input_params, history, executions) " +
                        "VALUES ('FINISHED', 'a', 0.0, '{\"ticket\":\"T-1\"}', '[]', '[]')",
                )
                // A second row with '{}' — the empty default must survive identically.
                st.execute(
                    "INSERT INTO processes (status, agent_name, cost_usd, input_params, history, executions) " +
                        "VALUES ('WORKING', 'b', 0.0, '{}', '[]', '[]')",
                )
                st.execute("PRAGMA user_version = 24")
            }
        }
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                assertTrue("input_params" !in columnNames(st, "processes"))
                assertTrue("process_inputs" in tableNames(st))
                val moved =
                    st.executeQuery("SELECT input_params FROM process_inputs WHERE process_id = 1").use {
                        it.next()
                        it.getString(1)
                    }
                assertEquals("{\"ticket\":\"T-1\"}", moved)
                val empty =
                    st.executeQuery("SELECT input_params FROM process_inputs WHERE process_id = 2").use {
                        it.next()
                        it.getString(1)
                    }
                assertEquals("{}", empty)
            }
        }

        // End-to-end: the repository hydrates migrated rows with their inputs intact.
        val db = DatabaseService(dbPath, logger)
        val repo = ProcessRepository(db, ProcessJobRegistry())
        assertEquals(mapOf("ticket" to "T-1"), repo.getInfo(1)?.inputParams)
        assertEquals(emptyMap(), repo.getInfo(2)?.inputParams)

        // Re-opening an already-migrated file is a no-op: the guard sees no input_params
        // column and must not attempt a second backfill or a second DROP.
        db.close()
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(28, userVersion(st))
                assertEquals(
                    2,
                    st.executeQuery("SELECT COUNT(*) FROM process_inputs").use {
                        it.next()
                        it.getInt(1)
                    },
                )
            }
        }
    }

    @Test
    fun `v24 database backfills an empty input for a NULL input_params row`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v24-null.db").toString()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.execute(v24ProcessesColumns)
                st.execute(
                    "INSERT INTO processes (status, agent_name, cost_usd, input_params, history, executions) " +
                        "VALUES ('FINISHED', 'a', 0.0, NULL, '[]', '[]')",
                )
                st.execute("PRAGMA user_version = 24")
            }
        }
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        val db = DatabaseService(dbPath, logger)
        val repo = ProcessRepository(db, ProcessJobRegistry())
        // COALESCE to '{}' rather than a NULL the side table forbids; reads back as no inputs.
        assertEquals(emptyMap(), repo.getInfo(1)?.inputParams)
        db.close()
    }

    @Test
    fun `a step update of a run with a multi-MiB input writes kilobytes, not megabytes, of WAL`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("wal.db").toString()
        val db = DatabaseService(dbPath, logger).also { it.migrate() }
        val repo = ProcessRepository(db, ProcessJobRegistry())
        val id = repo.obtainProcessId("a", mapOf("big" to "x".repeat(5 * 1024 * 1024)), 100).processId

        // Passivate the connection so the WAL is checkpointed and its file holds only
        // frames written after this point — the measurement then covers the updates alone.
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                st.execute("SELECT 1")
            }
        }
        val wal = java.io.File("$dbPath-wal")
        val walBefore = wal.length()
        val sideRowBefore =
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT rowid FROM process_inputs WHERE process_id = $id").use {
                        it.next()
                        it.getLong(1)
                    }
                }
            }

        // The two hot per-step writes, plus a status transition — exactly what a run does per step.
        repo.setCurrentCommand(id, "claude", 100)
        repo.addExecution(
            id,
            ExecutionInfo(label = "cmd", start = 100, end = 101, resultCode = 0, stdOut = null, stdErr = null, errMsg = null),
        )
        repo.setCurrentCommand(id, "cmd", 102)
        repo.transitionStatus(id, ProcessStatus.WORKING, 103, null)

        val walGrowth = wal.length() - walBefore
        val warmUpWalGrowth =
            run {
                val upTo = wal.length()
                repo.setCurrentCommand(id, "cmd", 104)
                wal.length() - upTo
            }
        val mb = 1024 * 1024
        assertTrue(
            walGrowth < mb,
            "step updates of a 5 MiB-input run wrote $walGrowth bytes of WAL (warm-up update alone: $warmUpWalGrowth)",
        )
        // The side row itself must never be rewritten by a step update: same rowid, same value.
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT rowid FROM process_inputs WHERE process_id = $id").use { rs ->
                    rs.next()
                    assertEquals(sideRowBefore, rs.getLong(1))
                }
                st.executeQuery("SELECT LENGTH(input_params) FROM process_inputs WHERE process_id = $id").use { rs ->
                    rs.next()
                    assertTrue(rs.getInt(1) > mb, "expected the multi-MiB input to still be stored")
                }
            }
        }
        db.close()
    }
}
