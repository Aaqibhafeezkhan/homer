package homer.app.runners

import homer.app.models.AppConfig
import homer.app.models.RunnerConfig
import homer.app.models.RunnerFilter
import homer.app.models.RunnerFilterView
import homer.app.models.RunnerInstanceStatus
import homer.app.models.RunnerQueueGroup
import homer.app.models.RunnerQueueStatusResponse
import homer.app.models.RunnerStatus
import homer.app.models.RunnersStatusResponse
import homer.app.runners.external.ExternalRunnerDomain
import homer.app.runners.local.LocalRunner
import homer.app.runners.queue.PendingClaim
import homer.app.runners.queue.StaticRunnerConsumer

/**
 * Generic backend kind for [alias] — `local` for the built-in runner, the
 * configured backend type (`ssh` / `kubernetes` / `external`) otherwise, and
 * `unknown` for an alias no longer in config (e.g. a run served by a runner that
 * was later removed). One mapping serves `GET /api/v1/runners`, `/health` and the
 * non-admin status response. See ADR-0077.
 */
fun runnerType(
    config: AppConfig,
    alias: String,
): String =
    when (config.runners[alias]) {
        is RunnerConfig.Ssh -> "ssh"
        is RunnerConfig.Kubernetes -> "kubernetes"
        is RunnerConfig.External -> "external"
        null -> if (alias == LocalRunner.ALIAS) "local" else "unknown"
    }

/** Operator view of every configured runner; admin-only, never carries a token or env value. See ADR-0066. */
fun runnersStatus(
    config: AppConfig,
    registry: RunnerRegistry,
): RunnersStatusResponse {
    val inUse =
        registry.queue
            .consumers()
            .filterIsInstance<StaticRunnerConsumer>()
            .associate { it.alias to it.inUse }
    val domains = registry.externalDomains.associateBy { it.alias }
    val local = config.server.localRunner
    val rows =
        buildList {
            if (local.enabled) {
                add(
                    RunnerStatus(
                        LocalRunner.ALIAS,
                        runnerType(config, LocalRunner.ALIAS),
                        local.tags.sorted(),
                        local.concurrency,
                        inUse[LocalRunner.ALIAS] ?: 0,
                    ),
                )
            }
            config.runners.forEach { (alias, cfg) ->
                add(
                    when (cfg) {
                        is RunnerConfig.Ssh -> {
                            RunnerStatus(alias, runnerType(config, alias), cfg.tags.sorted(), cfg.concurrency, inUse[alias] ?: 0)
                        }

                        is RunnerConfig.Kubernetes -> {
                            RunnerStatus(
                                alias,
                                runnerType(config, alias),
                                cfg.tags.sorted(),
                                cfg.concurrency,
                                inUse[alias] ?: 0,
                            )
                        }

                        is RunnerConfig.External -> {
                            externalRunnerStatus(alias, runnerType(config, alias), domains[alias])
                        }
                    },
                )
            }
        }
    return RunnersStatusResponse(rows)
}

/** An `!<external>` domain's row: no tags or concurrency of its own, one entry per registered instance, oldest first. */
private fun externalRunnerStatus(
    alias: String,
    kind: String,
    domain: ExternalRunnerDomain?,
): RunnerStatus {
    val instances = domain?.instances().orEmpty().sortedBy { it.registeredAt }
    return RunnerStatus(
        alias = alias,
        kind = kind,
        tags = emptyList(),
        concurrency = null,
        inUse = instances.sumOf { it.inUse },
        instances =
            instances.map {
                RunnerInstanceStatus(
                    instanceId = it.id,
                    name = it.name,
                    tags = it.tags.sorted(),
                    concurrency = it.declaredConcurrency,
                    activeSessions = it.inUse,
                    lastSeenAt = it.lastSeenAt / 1000,
                    registeredAt = it.registeredAt / 1000,
                    draining = it.draining,
                )
            },
    )
}

/** Pending claims grouped by filter, oldest group first; ages clamp at 0. */
fun runnerQueueStatus(
    pending: List<PendingClaim>,
    nowMs: Long,
): RunnerQueueStatusResponse {
    fun ageSec(enqueuedAtMs: Long) = ((nowMs - enqueuedAtMs) / 1000).coerceAtLeast(0)
    val groups =
        pending
            .groupBy { it.filter }
            .map { (filter, claims) ->
                RunnerQueueGroup(
                    filter =
                        when (filter) {
                            is RunnerFilter.Alias -> RunnerFilterView(alias = filter.alias)
                            is RunnerFilter.Tags -> RunnerFilterView(tags = filter.tags.sorted())
                        },
                    pending = claims.size,
                    oldestAgeSec = ageSec(claims.minOf { it.enqueuedAtMs }),
                )
            }.sortedByDescending { it.oldestAgeSec }
    return RunnerQueueStatusResponse(
        pending = pending.size,
        oldestAgeSec = pending.minOfOrNull { it.enqueuedAtMs }?.let(::ageSec),
        byFilter = groups,
    )
}
