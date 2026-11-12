package homer.app.services.persistence

import homer.app.models.ProcessStatus
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select

// The cooldown bound's two lookups in the transaction-local form `obtainProcessId` evaluates inside
// the INSERT's transaction (ADR-0051), plus the subject filter they share, so a keyed and a keyless
// start cannot scope differently (ADR-0105). The `db.read` forms `CooldownGate` calls stay on
// ProcessRepository, where the pre-flight gate can stub them.

// Transaction-local forms of the two reads `CooldownGate` calls, so the authoritative cooldown
// check in obtainProcessId can run inside the INSERT's transaction. `db.query` opens its own, and
// nesting one inside another would deadlock the writer.
internal fun ProcessRepository.latestRunStartedAtTx(
    agentName: String,
    cooldownKey: String?,
): Long? =
    Processes
        .select(Processes.createdAt)
        .where { cooldownPredicate(agentName, cooldownKey) }
        .orderBy(Processes.id, SortOrder.DESC)
        .limit(1)
        .singleOrNull()
        ?.get(Processes.createdAt)

/**
 * The subject filter both cooldown lookups share: the agent, plus the rendered key when
 * the start carries one. A null key adds nothing, so a keyless cooldown keeps counting
 * every row of the agent — including rows written by a keyed definition, and rows from
 * before v31 — exactly as it did before the key existed. A key is compared with `eq`
 * (a bound parameter), so a `%`, `_` or `'` in it matches nothing but itself. ADR-0105.
 */
private fun ProcessRepository.cooldownPredicate(
    agentName: String,
    cooldownKey: String?,
): Op<Boolean> =
    listOfNotNull(
        Processes.agentName eq agentName,
        cooldownKey?.let { Processes.cooldownKey eq it },
    ).reduce { a, b -> a and b }

// MAX over ended_at, not the newest row's history: `id` is INSERT order, and
// same-agent runs overlap by default (the default lockStrategy.key is per
// process and maxParallelRuns is 10), so an older run can fail AFTER a newer
// one. Ordering by id then picked the newer row's EARLIER failure and let a
// start through inside the afterFailure window. Rows from before v14 have a
// null ended_at and are skipped by MAX. See ADR-0051.
//
// Only a run that failed while executing a command arms the window: a run refused
// before it started never ran and carries a `failure_reason`, written only in that
// case (ADR-0098 — PreStartFailureReason holds the five causes).
internal fun ProcessRepository.latestFailureEndedAtTx(
    agentName: String,
    cooldownKey: String?,
): Long? =
    Processes
        .select(Processes.endedAt)
        .where {
            cooldownPredicate(agentName, cooldownKey) and
                (Processes.status eq ProcessStatus.FAILED.name) and
                Processes.failureReason.isNull()
        }
        // Ordered by ended_at, not id. SQLite sorts NULLs last under DESC, so a
        // pre-v14 row without a recorded terminal time never masks a real one.
        .orderBy(Processes.endedAt, SortOrder.DESC)
        .limit(1)
        .singleOrNull()
        ?.get(Processes.endedAt)
