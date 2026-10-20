package homer.app.services.persistence

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select

/**
 * Durable access to the append-only Claude spend ledger. SQLite is
 * authoritative (ADR-0020); the ledger design is ADR-0035. All month
 * bucketing is UTC.
 */
class CostRepository(
    private val db: DatabaseService,
) {
    private val sumCol = CostEntries.costUsd.sum()

    /**
     * Replay-unsafe (the append-only ledger of ADR-0035 has no key a replay
     * could collide with); safe only because the writer handle is
     * single-attempt. See ADR-0020.
     */
    suspend fun insert(
        agentName: String,
        timestampSec: Long,
        costUsd: Double,
    ) {
        db.query {
            CostEntries.insert {
                it[CostEntries.agentName] = agentName
                it[CostEntries.timestampSec] = timestampSec
                it[CostEntries.costUsd] = costUsd
            }
        }
    }

    suspend fun sumGlobalSince(cutoffSec: Long): Double =
        db.read {
            CostEntries
                .select(sumCol)
                .where { CostEntries.timestampSec greaterEq cutoffSec }
                .single()[sumCol] ?: 0.0
        }

    suspend fun sumAgentSince(
        agentName: String,
        cutoffSec: Long,
    ): Double =
        db.read {
            CostEntries
                .select(sumCol)
                .where { (CostEntries.agentName eq agentName) and (CostEntries.timestampSec greaterEq cutoffSec) }
                .single()[sumCol] ?: 0.0
        }

    suspend fun sumPerAgentSince(cutoffSec: Long): Map<String, Double> =
        db.read {
            CostEntries
                .select(CostEntries.agentName, sumCol)
                .where { CostEntries.timestampSec greaterEq cutoffSec }
                .groupBy(CostEntries.agentName)
                .associate { it[CostEntries.agentName] to (it[sumCol] ?: 0.0) }
        }

    suspend fun sumPerAgentBetween(
        startSec: Long,
        endSecExclusive: Long,
    ): Map<String, Double> =
        db.read {
            CostEntries
                .select(CostEntries.agentName, sumCol)
                .where {
                    (CostEntries.timestampSec greaterEq startSec) and (CostEntries.timestampSec less endSecExclusive)
                }.groupBy(CostEntries.agentName)
                .associate { it[CostEntries.agentName] to (it[sumCol] ?: 0.0) }
        }

    suspend fun totalPerAgent(): Map<String, Double> =
        db.read {
            CostEntries
                .select(CostEntries.agentName, sumCol)
                .groupBy(CostEntries.agentName)
                .associate { it[CostEntries.agentName] to (it[sumCol] ?: 0.0) }
        }

    /** Distinct UTC months ("YYYY-MM") with at least one entry, newest first. */
    suspend fun availableMonths(): List<String> =
        db.read {
            exec(
                "SELECT DISTINCT strftime('%Y-%m', timestamp_sec, 'unixepoch') AS ym FROM cost_entries ORDER BY ym DESC",
            ) { rs ->
                buildList { while (rs.next()) add(rs.getString("ym")) }
            } ?: emptyList()
        }
}
