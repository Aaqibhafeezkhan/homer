package homer.app.services.persistence

import homer.app.models.ResultEnvParam
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * A persisted capability token scoping a read-by-reference result fetch to one
 * watched process's artifact. The token authorizes streaming [path] from the
 * watched process's artifacts dir, optionally transformed by [resultTransformer]
 * at fetch time. Reusable within its TTL ([expiresAt]); independent of the
 * continuation row that minted it.
 *
 * [consumerAgent] is the agent the token is minted for (the continuation's
 * target). The fetch endpoint redeems it only for a caller running that agent,
 * so a leaked token is useless to any other agent. Every resume segment of the
 * target runs the same agent, so cross-segment re-fetch still works. See ADR-0025.
 */
data class ResultTokenRecord(
    val token: String,
    val watchedProcessId: Int,
    val path: String,
    val resultTransformer: ResultEnvParam?,
    val sizeBytes: Long,
    val createdAt: Long,
    val expiresAt: Long?,
    val consumerAgent: String,
)

/**
 * Durable access to capability-token records. SQLite is authoritative (ADR-0020).
 */
class ResultTokenRepository(
    private val db: DatabaseService,
) {
    suspend fun create(record: ResultTokenRecord) {
        db.query {
            ResultTokens.insert {
                it[token] = record.token
                it[watchedProcessId] = record.watchedProcessId
                it[path] = record.path
                it[resultTransformer] =
                    record.resultTransformer?.let { t -> columnJson.encodeToString(ResultEnvParam.serializer(), t) }
                it[sizeBytes] = record.sizeBytes
                it[createdAt] = record.createdAt
                it[expiresAt] = record.expiresAt
                it[consumerAgent] = record.consumerAgent
            }
        }
    }

    suspend fun get(token: String): ResultTokenRecord? =
        db.read {
            ResultTokens
                .selectAll()
                .where { ResultTokens.token eq token }
                .singleOrNull()
                ?.toRecord()
        }

    /**
     * Deletes tokens whose TTL has lapsed at or before [now]. Returns the row count removed.
     * Deliberate exception to the "sweep through an index" rule: there is no `expires_at`
     * index, so the `db.read` pre-check scans `result_tokens`. The table holds only the
     * tokens of oversized continuation results whose TTL has not lapsed, not a row per run,
     * so it stays far below `maxProcesses` and an index would cost every insert more than
     * this scan costs. The scan runs in `read {}` with no write lock; an idle tick takes none.
     */
    suspend fun deleteExpired(now: Long): Int {
        val due =
            db.read {
                ResultTokens
                    .select(ResultTokens.token)
                    .where { (ResultTokens.expiresAt.isNotNull()) and (ResultTokens.expiresAt lessEq now) }
                    .limit(1)
                    .any()
            }
        if (!due) return 0
        return db.query {
            ResultTokens.deleteWhere {
                (ResultTokens.expiresAt.isNotNull()) and (ResultTokens.expiresAt lessEq now)
            }
        }
    }

    private fun ResultRow.toRecord(): ResultTokenRecord =
        ResultTokenRecord(
            token = this[ResultTokens.token],
            watchedProcessId = this[ResultTokens.watchedProcessId],
            path = this[ResultTokens.path],
            resultTransformer =
                this[ResultTokens.resultTransformer]?.let { columnJson.decodeFromString(ResultEnvParam.serializer(), it) },
            sizeBytes = this[ResultTokens.sizeBytes],
            createdAt = this[ResultTokens.createdAt],
            expiresAt = this[ResultTokens.expiresAt],
            consumerAgent = this[ResultTokens.consumerAgent],
        )
}
