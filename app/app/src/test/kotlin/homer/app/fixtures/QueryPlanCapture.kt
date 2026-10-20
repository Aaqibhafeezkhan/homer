package homer.app.fixtures

import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.v1.core.statements.StatementContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Collects every distinct SQL statement production code runs during `:app:test`, with the
 * production source line that ran it, for the `query-plans` check (ADR-0082): `queryPlanReport`
 * then plans each one ([QueryPlanReport]). It also records, for the `query-repeats` check, each
 * statement production code runs [REPEAT_THRESHOLD] times in one transaction: a query per row in
 * a loop (SQLite rule 2 in `app/CLAUDE.md`). Twice is not enough: two separate reads of one
 * shape (a root's visibility, then the page's roots) are no loop. Registered through `META-INF/services` like
 * [SqlStatementRecorder]; inert unless the test task sets [DIR_PROPERTY].
 *
 * A statement whose nearest `homer.app` frame is test code (seeding, assertions) is not
 * production SQL: it is neither planned nor counted toward a repeat. Each new statement is
 * appended to its file at once, so nothing depends on a shutdown hook.
 */
class QueryPlanCapture : GlobalStatementInterceptor {
    override fun beforeExecution(
        transaction: Transaction,
        context: StatementContext,
    ) {
        val dir = captureDir ?: return
        val sql = normalize(context.sql(transaction))
        val newPlanned = PLANNED.containsMatchIn(sql) && sql !in seen
        val repeatable = REPEATABLE.containsMatchIn(sql)
        if (!newPlanned && !repeatable) return
        // A test may issue the same SQL first, so seen is marked only once production code ran it.
        val caller = productionCaller(Throwable().stackTrace, ::lineCount) ?: return
        if (newPlanned && seen.add(sql)) append(dir.resolve(CAPTURE_FILE), caller, sql)
        val reached = repeatable && repeatReached(transaction.transactionId, context.statement, sql, caller)
        if (reached && repeated.add("${caller.path}#${caller.function}#$sql")) append(dir.resolve(REPEATS_FILE), caller, sql)
    }

    override fun afterCommit(transaction: Transaction) = repeats.end(transaction.transactionId)

    override fun afterRollback(transaction: Transaction) = repeats.end(transaction.transactionId)

    /**
     * Runs of each statement shape per open transaction. A batch (`batchInsert`) is one statement
     * with a context per row, so the same statement object in a row counts once.
     */
    internal class RepeatCounter(
        private val threshold: Int,
    ) {
        private class Counter(
            var count: Int = 0,
            var last: Any? = null,
        )

        private val perTransaction = ConcurrentHashMap<String, ConcurrentHashMap<String, Counter>>()

        /** True on the run of [sql] that reaches the threshold in [transactionId], and only on that one. */
        fun count(
            transactionId: String,
            statement: Any,
            sql: String,
        ): Boolean {
            val counter = perTransaction.computeIfAbsent(transactionId) { ConcurrentHashMap() }.computeIfAbsent(sql) { Counter() }
            if (counter.last === statement) return false
            counter.last = statement
            return ++counter.count == threshold
        }

        fun end(transactionId: String) {
            perTransaction.remove(transactionId)
        }
    }

    /** Where a statement came from: a repo-relative source path, its line and the enclosing function. */
    data class Caller(
        val path: String,
        val line: Int,
        val function: String,
    )

    companion object {
        const val DIR_PROPERTY = "homer.queryPlans.dir"
        const val CAPTURE_FILE = "statements.tsv"
        const val REPEATS_FILE = "repeats.tsv"
        const val REPEAT_THRESHOLD = 3

        // Statements whose plan reads a table; DDL, PRAGMAs and plain INSERT ... VALUES plan nothing.
        private val PLANNED = Regex("""^\s*(SELECT|UPDATE|DELETE|WITH|INSERT\b.*\bSELECT\b)""", RegexOption.IGNORE_CASE)

        // Statements a loop could run per row; DDL and PRAGMAs run once by construction.
        private val REPEATABLE = Regex("""^\s*(SELECT|UPDATE|DELETE|INSERT|WITH)""", RegexOption.IGNORE_CASE)
        private val IN_LIST = Regex("""\?(\s*,\s*\?)+""")
        private val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val repeated: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val repeats = RepeatCounter(REPEAT_THRESHOLD)

        /** Whether this run of [sql] reaches the repeat threshold; a run test code issued ([caller] null) never counts. */
        internal fun repeatReached(
            transactionId: String,
            statement: Any,
            sql: String,
            caller: Caller?,
        ): Boolean = caller != null && repeats.count(transactionId, statement, sql)

        private val fileLock = Any()
        private val isTestClass = ConcurrentHashMap<String, Boolean>()
        private val lineCounts = ConcurrentHashMap<String, Int>()

        private val captureDir: File? by lazy { System.getProperty(DIR_PROPERTY)?.let { File(it).apply { mkdirs() } } }

        private fun append(
            file: File,
            caller: Caller,
            sql: String,
        ) = synchronized(fileLock) { file.appendText("${caller.path}\t${caller.line}\t${caller.function}\t$sql\n") }

        /** One line per statement, and one shape per `IN (?, ?, …)` list whatever its length. */
        internal fun normalize(sql: String): String = IN_LIST.replace(sql.replace(Regex("""\s+"""), " ").trim(), "?, ?")

        /**
         * The first `homer.app` frame, if it is production code; null for SQL a test issued itself.
         * Code inlined from Exposed (`selectAll().where { }.map { }`) reports a line past the end of
         * the file, so such a frame takes the line of the next frame in the same file, else line 1.
         */
        internal fun productionCaller(
            stack: Array<StackTraceElement>,
            lineCount: (String) -> Int?,
        ): Caller? {
            val homer = stack.filter { it.className.startsWith("homer.app.") && !isOwn(it.className) }
            val frame = homer.firstOrNull() ?: return null
            if (isTest(frame.className)) return null
            val path = pathOf(frame)
            val inFile = { f: StackTraceElement -> f.lineNumber in 1..(lineCount(path) ?: Int.MAX_VALUE) }
            val line = homer.firstOrNull { pathOf(it) == path && inFile(it) }?.lineNumber ?: 1
            return Caller(path, line, functionOf(frame))
        }

        private fun pathOf(frame: StackTraceElement): String =
            "app/app/src/main/kotlin/${frame.className.substringBeforeLast('.').replace('.', '/')}/${frame.fileName}"

        /** Lines in the repo file at [path]; null when the repo root is unset or the file is missing. */
        private fun lineCount(path: String): Int? {
            val root = System.getProperty(REPO_ROOT_PROPERTY) ?: return null
            return lineCounts
                .computeIfAbsent(path) { File(root, it).takeIf(File::isFile)?.useLines { l -> l.count() } ?: -1 }
                .takeIf { it >= 0 }
        }

        /** `getAll` for `ProcessQueriesKt$getAll$2.invokeSuspend` and for `getAll$lambda$0`; the method name otherwise. */
        internal fun functionOf(frame: StackTraceElement): String {
            val simple = frame.className.substringAfterLast('.')
            val lambdaOwner = simple.split('$').getOrNull(1)?.takeIf { it.isNotEmpty() && !it.first().isDigit() }
            val method = if (frame.methodName in LAMBDA_METHODS && lambdaOwner != null) lambdaOwner else frame.methodName
            return method.substringBefore("\$lambda")
        }

        private fun isTest(className: String): Boolean =
            isTestClass.computeIfAbsent(className) {
                val location =
                    runCatching {
                        Class
                            .forName(it, false, QueryPlanCapture::class.java.classLoader)
                            .protectionDomain
                            ?.codeSource
                            ?.location
                            ?.path
                    }.getOrNull()
                location == null || "/test/" in location
            }

        private val OWN = QueryPlanCapture::class.java.name

        private fun isOwn(className: String) = className == OWN || className.startsWith("$OWN\$")

        private val LAMBDA_METHODS = setOf("invokeSuspend", "invoke")
    }
}
