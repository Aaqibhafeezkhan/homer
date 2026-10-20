package homer.app.fixtures

import homer.app.infra.ProcessJobRegistry
import homer.app.infra.PurgeSchedule
import homer.app.services.persistence.DatabaseService
import homer.app.services.persistence.ProcessRepository
import homer.app.services.persistence.QuestionsRepository
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueryPlanCaptureTest {
    private fun frame(
        className: String,
        method: String,
        file: String = "F.kt",
        line: Int = 1,
    ) = StackTraceElement(className, method, file, line)

    @Test
    fun `normalize folds whitespace and IN lists of any length into one shape`() {
        assertEquals(
            "SELECT id FROM processes WHERE id IN (?, ?) AND status = ?",
            QueryPlanCapture.normalize("SELECT id\n  FROM processes WHERE id IN (?, ?, ?,?) AND status = ?"),
        )
    }

    @Test
    fun `the function of a lambda frame is the function that declared it`() {
        assertEquals(
            "getAll",
            QueryPlanCapture.functionOf(frame("homer.app.services.persistence.ProcessQueriesKt\$getAll\$2", "invokeSuspend")),
        )
        assertEquals("transitionStatus", QueryPlanCapture.functionOf(frame("homer.app.X", "transitionStatus\$lambda\$0")))
        assertEquals("migrate", QueryPlanCapture.functionOf(frame("homer.app.X\$1", "migrate")))
    }

    @Test
    fun `the caller is the first homer frame when it is production code, none when it is a test`() {
        val production = frame(DatabaseService::class.java.name, "migrate", "DatabaseService.kt", 42)
        val caller = QueryPlanCapture.productionCaller(arrayOf(frame("org.jetbrains.exposed.X", "exec"), production)) { null }
        assertEquals(
            QueryPlanCapture.Caller("app/app/src/main/kotlin/homer/app/services/persistence/DatabaseService.kt", 42, "migrate"),
            caller,
        )
        assertNull(QueryPlanCapture.productionCaller(arrayOf(frame(QueryPlanCaptureTest::class.java.name, "seed"), production)) { null })
        assertNull(QueryPlanCapture.productionCaller(arrayOf(frame("org.jetbrains.exposed.X", "exec"))) { null })
    }

    @Test
    fun `a line past the end of the file, from inlined code, takes the next frame's line in that file`() {
        val repo = DatabaseService::class.java.name
        // A real nested class: an unknown one counts as test code.
        val inlined = frame(DatabaseService.Companion::class.java.name, "list\$lambda\$0", "DatabaseService.kt", 900)
        val outer = frame(repo, "list", "DatabaseService.kt", 120)
        val lines = { _: String -> 300 }

        assertEquals(120, QueryPlanCapture.productionCaller(arrayOf(inlined, outer), lines)?.line)
        assertEquals(1, QueryPlanCapture.productionCaller(arrayOf(inlined), lines)?.line)
        assertEquals("list", QueryPlanCapture.productionCaller(arrayOf(inlined, outer), lines)?.function)
    }

    @Test
    fun `under the test task a repository statement is captured with its source line, a test's own is not`() =
        runTest {
            val dir = System.getProperty(QueryPlanCapture.DIR_PROPERTY)
            assumeTrue(dir != null, "only the Gradle test task sets ${QueryPlanCapture.DIR_PROPERTY}")
            val db = PersistenceTestSupport.tempDatabase()
            QuestionsRepository(db).get("no-such-question")
            transaction(db.database) { exec("SELECT questions.answer FROM questions WHERE questions.text = 'issued by a test'") }
            db.close()

            val lines = File(dir, QueryPlanCapture.CAPTURE_FILE).readLines()
            // Whichever repository function ran this SELECT first is the one recorded (get, markAnswered, …).
            val get =
                lines.map { it.split('\t') }.single {
                    it[3].startsWith("SELECT ") &&
                        it[3].endsWith("FROM questions WHERE questions.id = ?")
                }
            assertEquals("app/app/src/main/kotlin/homer/app/services/persistence/QuestionsRepository.kt", get[0])
            val source = File(repoRoot, get[0]).readLines()
            val declared = source.indexOfFirst { "fun ${get[2]}(" in it } + 1
            assertTrue(declared > 0 && get[1].toInt() in declared..declared + 40, "line ${get[1]} is not in ${get[2]} (line $declared)")
            assertTrue(lines.none { "issued by a test" in it })
            assertTrue(lines.none { it.split('\t')[3].startsWith("CREATE") }, "DDL is never planned")
        }

    @Test
    fun `under the test task a statement production code runs three times in one transaction is a repeat`() =
        runTest {
            val dir = System.getProperty(QueryPlanCapture.DIR_PROPERTY)
            assumeTrue(dir != null, "only the Gradle test task sets ${QueryPlanCapture.DIR_PROPERTY}")
            val db = PersistenceTestSupport.tempDatabase()
            val repo = ProcessRepository(db, ProcessJobRegistry())
            val agents = listOf("a", "b", "c")
            for (agent in agents) repo.obtainProcessId(agent, emptyMap(), maxProcesses = 10)
            repo.markInterruptedAsUnknown(nowSec = 100)
            // Three agents, three schedules: one UPDATE per schedule in the one boot transaction.
            repo.stampUnknownPurgeTimes(nowSec = 100) { PurgeSchedule(60L * (agents.indexOf(it) + 1), 60, emptyList()) }
            db.close()

            val repeats = File(dir, QueryPlanCapture.REPEATS_FILE).readLines().map { it.split('\t') }
            assertTrue(
                repeats.any { it[0].endsWith("persistence/ProcessRepository.kt") && it[2] == "stampUnknownPurgeTimes" },
                "no repeat recorded for stampUnknownPurgeTimes: $repeats",
            )
        }

    @Test
    fun `the counter fires on the third run in one transaction, not on two, a batch or other transactions`() {
        val counter = QueryPlanCapture.RepeatCounter(threshold = 3)
        val sql = "SELECT a FROM t WHERE id = ?"

        assertEquals(listOf(false, false, true, false), List(4) { counter.count("tx1", Any(), sql) })
        val batch = Any()
        assertEquals(listOf(false, false, false), List(3) { counter.count("tx2", batch, sql) }, "a batch is one statement")
        assertEquals(listOf(false, false), List(2) { counter.count("tx3", Any(), sql) })
        counter.end("tx3")
        assertEquals(false, counter.count("tx3", Any(), sql), "a new transaction starts from zero")
    }

    @Test
    fun `under the test task a batch of three rows records no repeat`() =
        runTest {
            val dir = System.getProperty(QueryPlanCapture.DIR_PROPERTY)
            assumeTrue(dir != null, "only the Gradle test task sets ${QueryPlanCapture.DIR_PROPERTY}")
            val db = PersistenceTestSupport.tempDatabase()
            ProcessRepository(db, ProcessJobRegistry()).obtainProcessId("a", emptyMap(), maxProcesses = 10, tags = listOf("x", "y", "z"))
            db.close()

            val repeats = File(dir, QueryPlanCapture.REPEATS_FILE).takeIf { it.isFile }?.readLines().orEmpty()
            assertTrue(repeats.none { it.split('\t')[2] == "obtainProcessId" }, "batchInsert of three tags recorded as a repeat: $repeats")
        }

    @Test
    fun `a run test code issued never counts toward a repeat`() {
        val production = QueryPlanCapture.Caller("app/app/src/main/kotlin/homer/app/A.kt", 3, "list")
        val sql = "SELECT a FROM gate_probe WHERE id = ?"
        val run = { tx: String, caller: QueryPlanCapture.Caller? -> QueryPlanCapture.repeatReached(tx, Any(), sql, caller) }

        assertEquals(listOf(false, false, false, false), listOf(null, null, null, production).map { run("gate-1", it) })
        // Counting the test's run would fire on it; only the third production run does.
        assertEquals(listOf(false, false, false, true), listOf(production, production, null, production).map { run("gate-2", it) })
    }
}
