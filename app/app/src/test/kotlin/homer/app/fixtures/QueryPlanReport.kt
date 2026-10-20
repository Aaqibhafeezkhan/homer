package homer.app.fixtures

import homer.app.services.persistence.DatabaseService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * The `query-plans` check's report (ADR-0082): plans every statement [QueryPlanCapture] collected
 * during `:app:test` against a freshly migrated schema and lists each full scan of a table that
 * grows with use — SQLite rule 1 in `app/CLAUDE.md`. Run by `:app:queryPlanReport`;
 * `tools/quality/query-plans.mjs` turns the JSON into the gate's report.
 *
 * The schema has no `sqlite_stat1`, as on a first boot, so the plans are the planner's
 * statistics-free choice. Parameters are bound to NULL: a plan does not depend on their values.
 */
object QueryPlanReport {
    /** The tables SQLite rule 1 in `app/CLAUDE.md` names as growing with use; a scan of one is a finding. */
    val GROWING_TABLES =
        setOf("processes", "process_inputs", "process_tags", "cost_entries", "questions", "dispatches", "continuations")

    private val SCAN = Regex("""^SCAN (\w+)""")
    private val json = Json { prettyPrint = true }

    @Serializable
    data class Finding(
        val path: String,
        val line: Int,
        val function: String,
        val table: String,
        val sql: String,
        val plan: List<String>,
    )

    @Serializable
    data class Unplanned(
        val path: String,
        val line: Int,
        val sql: String,
        val error: String,
    )

    /** A statement production code ran three or more times in one transaction (the `query-repeats` check). */
    @Serializable
    data class Repeat(
        val path: String,
        val line: Int,
        val function: String,
        val sql: String,
    )

    @Serializable
    data class Report(
        val statements: Int,
        val findings: List<Finding>,
        val unplanned: List<Unplanned>,
        val repeats: List<Repeat> = emptyList(),
    )

    // An unfiltered page in primary-key (or rowid) order: the scan stops after LIMIT + OFFSET rows.
    private val PK_PAGE = Regex("""\bFROM (\w+)(?: WHERE 1)? ORDER BY \1\.(?:id|rowid) (?:ASC|DESC) LIMIT \d+(?: OFFSET \d+)?$""")

    /**
     * A full scan of a growing table in [plan] of [sql], or null. Not one: a scan through an
     * index, and an unfiltered primary-key page, which reads only the rows it returns.
     */
    fun scannedTable(
        sql: String,
        plan: List<String>,
    ): String? =
        plan
            .firstNotNullOfOrNull { detail ->
                SCAN
                    .find(detail)
                    ?.groupValues
                    ?.get(1)
                    ?.takeIf { it in GROWING_TABLES && " INDEX " !in "$detail " }
            }?.takeUnless { table -> PK_PAGE.find(sql)?.groupValues?.get(1) == table }

    /** The plan of [sql], one `detail` per row, parameters bound to NULL. */
    fun plan(
        connection: Connection,
        sql: String,
    ): List<String> =
        connection.prepareStatement("EXPLAIN QUERY PLAN $sql").use { statement ->
            for (i in 1..statement.parameterMetaData.parameterCount) statement.setObject(i, null)
            statement.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString("detail") else null }.toList() }
        }

    /** Plans every captured line (`path \t line \t function \t sql`) on [connection]. */
    fun report(
        connection: Connection,
        lines: List<String>,
    ): Report {
        val findings = mutableListOf<Finding>()
        val unplanned = mutableListOf<Unplanned>()
        val statements = lines.filter { it.isNotBlank() }.map { it.split('\t', limit = 4) }
        for ((path, line, function, sql) in statements) {
            val plan =
                runCatching { plan(connection, sql) }.getOrElse {
                    unplanned += Unplanned(path, line.toInt(), sql, it.message.orEmpty())
                    continue
                }
            scannedTable(sql, plan)?.let { findings += Finding(path, line.toInt(), function, it, sql, plan) }
        }
        return Report(statements.size, findings.sortedWith(compareBy({ it.path }, { it.line })), unplanned)
    }

    /** The captured repeats (`path \t line \t function \t sql`), sorted by place. */
    fun repeats(lines: List<String>): List<Repeat> =
        lines
            .filter { it.isNotBlank() }
            .map { it.split('\t', limit = 4) }
            .map { (path, line, function, sql) -> Repeat(path, line.toInt(), function, sql) }
            .sortedWith(compareBy({ it.path }, { it.line }))

    /** `queryPlanReport`: `<capture dir> <report json>`. */
    @JvmStatic
    fun main(args: Array<String>) {
        val (captureDir, out) = args
        val captured = File(captureDir, QueryPlanCapture.CAPTURE_FILE)
        check(captured.isFile) { "no captured statements at $captured: run :app:test first" }
        val lines = captured.readLines().filter { it.isNotBlank() }
        // An empty capture means the capture broke, not that every query is fine.
        check(lines.isNotEmpty()) { "$captured is empty: QueryPlanCapture recorded no production statement" }
        val db = Files.createTempFile("homer-query-plans-", ".db").toFile().apply { deleteOnExit() }
        DatabaseService(db.path, LoggerFactory.getLogger(QueryPlanReport::class.java)).apply {
            migrate()
            close()
        }
        val repeats = repeats(File(captureDir, QueryPlanCapture.REPEATS_FILE).takeIf { it.isFile }?.readLines().orEmpty())
        val report = DriverManager.getConnection("jdbc:sqlite:${db.path}").use { report(it, lines) }.copy(repeats = repeats)
        File(out).apply { parentFile.mkdirs() }.writeText(json.encodeToString(Report.serializer(), report))
        println(
            "query-plans: ${report.statements} statements, ${report.findings.size} full scans, " +
                "${report.unplanned.size} unplanned, ${report.repeats.size} repeated in one transaction",
        )
    }
}
