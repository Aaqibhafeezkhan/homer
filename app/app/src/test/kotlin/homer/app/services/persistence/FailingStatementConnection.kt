package homer.app.services.persistence

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException

/**
 * A [Connection] whose `createStatement()` throws, so every raw-SQL path on the writer handle —
 * `DatabaseService.rawStatement` and everything built on it — fails before it can run. It wraps at
 * the same `wrapWriterConnection` seam as [PostCommitFailureConnection], which is the only seam a
 * test has for making a writer statement fail.
 */
internal class FailingStatementConnection(
    private val real: Connection,
) : InvocationHandler {
    override fun invoke(
        proxy: Any,
        method: Method,
        args: Array<out Any?>?,
    ): Any? {
        if (method.name == "createStatement") throw SQLException("database is locked")
        return try {
            method.invoke(real, *(args ?: emptyArray()))
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }

    companion object {
        /** Wraps [connection] so every statement it hands out fails. */
        fun wrap(connection: Connection): Connection =
            Proxy.newProxyInstance(
                FailingStatementConnection::class.java.classLoader,
                arrayOf(Connection::class.java),
                FailingStatementConnection(connection),
            ) as Connection
    }
}
