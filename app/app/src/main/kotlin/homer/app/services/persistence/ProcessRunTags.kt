package homer.app.services.persistence

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select

// Run-time subject tags declared through the callback route after a run's INSERT; the
// `fromInputs` tags are written by [ProcessRepository.obtainProcessId] itself. See ADR-0060.

/**
 * Appends a run-time tag written through the callback route, after the INSERT
 * transaction. Idempotent by the `(process_id, tag)` primary key: an exact
 * duplicate collapses (`INSERT OR IGNORE`), matching the tag set's set semantics.
 * Enforces the per-run ceiling atomically with the write.
 *
 * The ceiling bounds runtime-declared tags only, so `fromInputs` tags — which
 * share this table but have keys disjoint from the runtime keys (enforced at agent
 * load) — are excluded from the count via [fromInputsKeys]. See ADR-0060.
 */
suspend fun ProcessRepository.insertRuntimeTag(
    processId: Int,
    tag: String,
    maxTags: Int,
    fromInputsKeys: Set<String> = emptySet(),
): ProcessRepository.RuntimeTagResult =
    db.query {
        // [sensitive_data_exposure / CWE-459] ProcessTags carries no foreign key by
        // design, so an INSERT would succeed for a processId whose row was already
        // purged — the record purge (remove) and the registration close that gates the
        // route are not one transaction, so a late callback can arrive after the row is
        // gone. Guard on row existence inside this transaction so a tag can never
        // outlive the run it describes. See ADR-0048 and ADR-0060.
        if (!Processes.select(Processes.id).where { Processes.id eq processId }.any()) {
            return@query ProcessRepository.RuntimeTagResult(written = false, count = 0)
        }
        val existing =
            ProcessTags
                .select(ProcessTags.tag)
                .where { ProcessTags.processId eq processId }
                .map { it[ProcessTags.tag] }
                .toSet()
        val runtimeCount = existing.count { it.substringBefore(':') !in fromInputsKeys }
        if (tag in existing) {
            return@query ProcessRepository.RuntimeTagResult(written = true, count = runtimeCount)
        }
        if (runtimeCount >= maxTags) {
            return@query ProcessRepository.RuntimeTagResult(written = false, count = runtimeCount)
        }
        ProcessTags.insert {
            it[ProcessTags.processId] = processId
            it[ProcessTags.tag] = tag
        }
        ProcessRepository.RuntimeTagResult(written = true, count = runtimeCount + 1)
    }

/** Every tag on a run, in tag order (ORDER BY, served by the (process_id, tag) primary key). */
suspend fun ProcessRepository.getTags(processId: Int): List<String> = db.read { tagsForTx(processId) }
