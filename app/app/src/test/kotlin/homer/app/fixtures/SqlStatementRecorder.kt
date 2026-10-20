package homer.app.fixtures

import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.v1.core.statements.StatementContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Records every SQL statement Exposed executes, JVM-wide. Registered through
 * `META-INF/services` so it needs no hook in production code; tests call
 * [reset] before the code under test and inspect [statements] afterwards to
 * assert which queries did — or did not — run, and [transactionCount] to
 * assert how many transactions ran them.
 */
class SqlStatementRecorder : GlobalStatementInterceptor {
    override fun beforeExecution(
        transaction: Transaction,
        context: StatementContext,
    ) {
        recorded += transaction.transactionId to context.sql(transaction)
    }

    companion object {
        private val recorded = CopyOnWriteArrayList<Pair<String, String>>()

        fun reset() = recorded.clear()

        fun statements(): List<String> = recorded.map { it.second }

        /** Distinct transactions that executed at least one recorded statement. */
        fun transactionCount(): Int = recorded.map { it.first }.distinct().size
    }
}
