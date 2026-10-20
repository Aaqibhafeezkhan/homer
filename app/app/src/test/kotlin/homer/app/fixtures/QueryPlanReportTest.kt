package homer.app.fixtures

import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueryPlanReportTest {
    @Test
    fun `a scan of a growing table is a finding, through an index or of another table it is not`() {
        assertEquals("questions", QueryPlanReport.scannedTable("SELECT id FROM questions", listOf("SCAN questions")))
        assertNull(
            QueryPlanReport.scannedTable("SELECT COUNT(*) FROM processes", listOf("SCAN processes USING COVERING INDEX processes_status")),
        )
        assertNull(QueryPlanReport.scannedTable("SELECT key FROM server_meta", listOf("SCAN server_meta")))
    }

    @Test
    fun `an unfiltered primary-key page is bounded, a filtered one is not`() {
        val plan = listOf("SCAN processes")
        assertNull(QueryPlanReport.scannedTable("SELECT … FROM processes WHERE 1 ORDER BY processes.id DESC LIMIT 100 OFFSET 200", plan))
        assertNull(QueryPlanReport.scannedTable("SELECT … FROM processes ORDER BY processes.id ASC LIMIT 5", plan))
        assertEquals(
            "processes",
            QueryPlanReport.scannedTable(
                "SELECT … FROM processes WHERE 1 AND (processes.owner = ?) ORDER BY processes.id ASC LIMIT 100",
                plan,
            ),
        )
    }

    @Test
    fun `report plans captured lines on the migrated schema`() {
        val path = PersistenceTestSupport.tempDatabasePath()
        val db = PersistenceTestSupport.tempDatabase(path)
        val lines =
            listOf(
                "app/A.kt\t3\tget\tSELECT processes.status FROM processes WHERE processes.id = ?",
                "app/B.kt\t7\tlist\tSELECT questions.text FROM questions WHERE 1",
                "app/C.kt\t9\tbroken\tSELECT nope FROM missing_table",
            )
        val report = DriverManager.getConnection("jdbc:sqlite:$path").use { QueryPlanReport.report(it, lines) }
        db.close()

        assertEquals(3, report.statements)
        assertEquals(listOf("app/B.kt:7 questions"), report.findings.map { "${it.path}:${it.line} ${it.table}" })
        assertEquals(listOf("app/C.kt"), report.unplanned.map { it.path })
    }
}
