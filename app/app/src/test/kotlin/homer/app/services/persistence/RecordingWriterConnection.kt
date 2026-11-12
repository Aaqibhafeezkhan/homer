package homer.app.services.persistence

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Statement
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Records the raw SQL every statement of the writer handle's connections runs, for
 * `DatabaseService`'s `wrapWriterConnection` seam.
 *
 * [SchemaMigrations] runs its DDL through `DatabaseService.rawStatement` — a raw
 * `java.sql.Statement` taken off the connection — so the Exposed-level
 * `homer.app.fixtures.SqlStatementRecorder` never sees it. This is what lets a migration
 * test assert which steps ran, and how many times, instead of only their end state.
 *
 * One instance per test: the recorded list is per-instance, so a parallel PIT run cannot mix
 * two tests' statements.
 */
internal class RecordingWriterConnection {
    /** Every raw statement's SQL, in execution order. */
    val statements = CopyOnWriteArrayList<String>()

    /** The `wrapWriterConnection` value that records through this instance. */
    val wrap: (Connection) -> Connection = { connection ->
        Proxy.newProxyInstance(
            RecordingWriterConnection::class.java.classLoader,
            arrayOf(Connection::class.java),
            Handler(connection, statements),
        ) as Connection
    }

    /** The recorded statements whose SQL contains [fragment] — the ones a migration step issues. */
    fun matching(fragment: String): List<String> = statements.filter { fragment in it }

    /** A [Connection] that delegates to `real` and records what the statements it hands out run. */
    private class Handler(
        private val real: Connection,
        private val statements: MutableList<String>,
    ) : InvocationHandler {
        override fun invoke(
            proxy: Any,
            method: Method,
            args: Array<out Any?>?,
        ): Any? {
            val result = delegate(real, method, args)
            return if (method.name == "createStatement") recording(result as Statement) else result
        }

        private fun recording(statement: Statement): Statement =
            Proxy.newProxyInstance(
                RecordingWriterConnection::class.java.classLoader,
                arrayOf(Statement::class.java),
                InvocationHandler { _, method, args ->
                    val sql = args?.firstOrNull() as? String
                    val result = delegate(statement, method, args)
                    if (sql != null && method.name.startsWith("execute")) statements += sql
                    result
                },
            ) as Statement
    }
}
