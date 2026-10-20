package homer.app.services.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdleConnectionCacheTest {
    private fun cache(
        dir: Path,
        maxIdle: Int,
    ): Pair<IdleConnectionCache, MutableList<Connection>> {
        val url = "jdbc:sqlite:${dir.resolve("c.db")}"
        val created = mutableListOf<Connection>()
        return IdleConnectionCache(maxIdle) { DriverManager.getConnection(url).also { created += it } } to created
    }

    @Test
    fun `sequential transactions reuse one connection`(
        @TempDir dir: Path,
    ) {
        val (cache, created) = cache(dir, maxIdle = 4)
        repeat(10) { cache.borrow().use { c -> c.createStatement().use { it.executeQuery("SELECT 1").close() } } }
        assertEquals(1, cache.opened.get())
        assertEquals(1, created.size)
        cache.close()
    }

    @Test
    fun `a burst opens what it needs and keeps only maxIdle`(
        @TempDir dir: Path,
    ) {
        val (cache, created) = cache(dir, maxIdle = 2)
        val leases = List(5) { cache.borrow() }
        assertEquals(5, created.size, "no caller waits: a burst opens new connections")
        leases.forEach { it.close() }
        assertEquals(2, created.count { !it.isClosed }, "only maxIdle stay open")
        cache.close()
        assertTrue(created.all { it.isClosed })
    }

    @Test
    fun `a returned connection holds no transaction`(
        @TempDir dir: Path,
    ) {
        val (cache, created) = cache(dir, maxIdle = 4)
        cache.borrow().use { c ->
            c.autoCommit = false
            c.createStatement().use { it.executeQuery("SELECT 1").close() }
            c.commit()
        }
        assertTrue(created.single().autoCommit, "idle connection left inside a transaction")
        cache.close()
    }

    @Test
    fun `a lease closes once and reads as closed afterwards`(
        @TempDir dir: Path,
    ) {
        val (cache, created) = cache(dir, maxIdle = 4)
        val lease = cache.borrow()
        lease.close()
        lease.close()
        assertTrue(lease.isClosed)
        assertFalse(created.single().isClosed, "the underlying connection is cached, not closed")
        // A double close must not return the connection twice: two borrows get two connections.
        val a = cache.borrow()
        val b = cache.borrow()
        assertEquals(2, created.size)
        a.close()
        b.close()
        cache.close()
    }

    @Test
    fun `a returned lease refuses further calls instead of reaching a connection another caller may hold`(
        @TempDir dir: Path,
    ) {
        val (cache, _) = cache(dir, maxIdle = 4)
        val lease = cache.borrow()
        lease.close()
        val next = cache.borrow()
        kotlin.test.assertFailsWith<java.sql.SQLException> { lease.createStatement() }
        next.createStatement().use { it.executeQuery("SELECT 1").close() }
        next.close()
        cache.close()
    }

    @Test
    fun `a connection returned after close is closed, not cached`(
        @TempDir dir: Path,
    ) {
        val (cache, created) = cache(dir, maxIdle = 4)
        val lease = cache.borrow()
        cache.close()
        lease.close()
        assertTrue(created.single().isClosed)
    }
}
