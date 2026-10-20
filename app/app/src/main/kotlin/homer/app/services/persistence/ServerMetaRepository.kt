package homer.app.services.persistence

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

/**
 * Durable server-instance metadata (the [ServerMeta] table). Today it holds one
 * value: the stable per-instance id used to scope the Kubernetes startup sweep.
 * SQLite is authoritative (ADR-0020).
 */
class ServerMetaRepository(
    private val db: DatabaseService,
) {
    /**
     * The stable per-instance identifier, created and persisted on first call.
     *
     * Unlike `HMR_SERVER_ID` (regenerated every boot) this survives restarts, so
     * the Kubernetes startup sweep can reap *this* instance's own orphan pods
     * after an unclean restart while never matching another instance's pods —
     * they carry a different id and live in a different database. See ADR-0033.
     *
     * Blocking and startup-only: resolved synchronously while the runner registry
     * is built, before traffic, so it does not go through the suspend [DatabaseService.query]
     * path. Requires [DatabaseService.migrate] to have created the `server_meta` table first.
     */
    fun getOrCreateInstanceId(): String =
        transaction(db.database) {
            ServerMeta
                .selectAll()
                .where { ServerMeta.key eq INSTANCE_ID_KEY }
                .firstOrNull()
                ?.get(ServerMeta.value)
                ?: UUID.randomUUID().toString().also { id ->
                    ServerMeta.insert {
                        it[key] = INSTANCE_ID_KEY
                        it[value] = id
                    }
                }
        }

    private companion object {
        const val INSTANCE_ID_KEY = "instance_id"
    }
}
