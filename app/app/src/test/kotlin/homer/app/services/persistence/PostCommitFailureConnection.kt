package homer.app.services.persistence

import org.jetbrains.exposed.v1.core.DatabaseConfig
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [Connection] whose `commit()` delegates to the real connection — the write
 * lands — and then throws `SQLITE_BUSY`, exactly as recent sqlite-jdbc
 * releases can from the re-`BEGIN IMMEDIATE` they issue inside `commit()`
 * once another
 * connection has taken the write lock in that window.
 *
 * Exposed treats a commit-phase `SQLException` as a failed attempt, so this is
 * what drives the replay the ticket is about: with the default `maxAttempts`
 * the whole `query { }` block runs again; with the writer handle's
 * single-attempt config it runs once and the failure propagates.
 */
internal class PostCommitFailureConnection(
    private val real: Connection,
) : InvocationHandler {
    val commits = AtomicInteger(0)

    override fun invoke(
        proxy: Any,
        method: Method,
        args: Array<out Any?>?,
    ): Any? {
        val result =
            try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        // After the delegation, never before: the commit has landed.
        if (method.name == "commit") {
            commits.incrementAndGet()
            throw SQLException("database is locked")
        }
        return result
    }

    companion object {
        /**
         * Wraps [connection] so every `commit()` lands and then throws. A fresh
         * Exposed transaction opens a fresh connection, so this fires on every
         * attempt rather than only the first.
         */
        fun wrap(connection: Connection): Connection =
            Proxy.newProxyInstance(
                PostCommitFailureConnection::class.java.classLoader,
                arrayOf(Connection::class.java),
                PostCommitFailureConnection(connection),
            ) as Connection

        /** Exposed's own default writer config — the one the post-commit failure replays a block under. */
        fun multiAttemptDatabaseConfig(maxAttempts: Int = 3): DatabaseConfig = DatabaseConfig { defaultMaxAttempts = maxAttempts }
    }
}
