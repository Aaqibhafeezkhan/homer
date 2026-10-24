package homer.app.routes

import homer.app.exceptions.ValidationException
import homer.app.models.CostByTagResponse
import homer.app.models.CostsResponse
import homer.app.models.HealthResponse
import homer.app.models.HeartbeatResponse
import homer.app.models.MonthlyCostsResponse
import homer.app.models.RunnerSummary
import homer.app.models.ServerStatusResponse
import homer.app.models.SweepDeletedPodResponse
import homer.app.models.SweepStatusResponse
import homer.app.models.TotalCostsResponse
import homer.app.runners.runnerQueueStatus
import homer.app.runners.runnerType
import homer.app.runners.runnersStatus
import homer.app.screenTags
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** `/heartbeat`, `/health`, and the admin-only `/costs*`, `/status` and `/runners*` endpoints. */
internal fun Route.serverRoutes(deps: ApiDeps) {
    serverHealthRoutes(deps)
    costMeterRoutes(deps)
    costByTagRoute(deps)
    serverStatusRoutes(deps)
}

/** `GET /heartbeat` and `GET /health`: the version ping and the runner and sweep health. */
private fun Route.serverHealthRoutes(deps: ApiDeps) {
    with(deps) {
        get("/heartbeat") {
            call.respond(HeartbeatResponse(version = System.getenv("HOMER_VERSION") ?: "dev"))
        }

        get("/health") {
            // The alias is admin-only (CWE-200, fail closed) — the same gate as
            // GET /runners; non-admins get the generic backend type. See ADR-0077.
            val aliasVisible = authorizer.isAdmin(call.principalName())
            val runners =
                buildList {
                    add(RunnerSummary(alias = "local", type = runnerType(config, "local")))
                    config.runners.forEach { (alias, _) ->
                        add(RunnerSummary(alias = if (aliasVisible) alias else null, type = runnerType(config, alias)))
                    }
                }
            val sweep =
                sweepStatus.get()?.let { snap ->
                    SweepStatusResponse(
                        ranAt = snap.ranAt,
                        scanned = snap.scanned,
                        deleted =
                            snap.deleted.map {
                                SweepDeletedPodResponse(
                                    namespace = it.namespace,
                                    name = it.name,
                                    processId = it.processId,
                                    runnerAlias = if (aliasVisible) it.runnerAlias else null,
                                )
                            },
                        errors = snap.errors,
                    )
                }
            call.respond(
                HealthResponse(
                    runners = runners,
                    sweep = sweep,
                    // The in-memory flag, so a console poll costs no database read (ADR-0092).
                    maintenance = maintenanceModeService.isEnabled(),
                ),
            )
        }
    }
}

/** `GET /costs`, `/costs/monthly` and `/costs/totals`: the admin-only cost-meter reads. */
private fun Route.costMeterRoutes(deps: ApiDeps) {
    with(deps) {
        get("/costs") {
            if (call.deniedUnlessAdmin(authorizer)) return@get
            val snap = costMeter.snapshot()
            call.respond(
                CostsResponse(
                    globalUsdLast60s = snap.globalUsdLast60s,
                    globalCapUsdPerMin = snap.globalCapUsdPerMin,
                    perAgentUsdLast24h = snap.perAgentUsdLast24h,
                    perAgentCapUsdPerDay = snap.perAgentCapUsdPerDay,
                    snapshotAtSec = snap.snapshotAtSec,
                    costTrackedTagKeys = config.costTrackedTagKeys,
                ),
            )
        }

        get("/costs/monthly") {
            if (call.deniedUnlessAdmin(authorizer)) return@get
            val monthly = costMeter.monthly(call.request.queryParameters["month"])
            call.respond(
                MonthlyCostsResponse(
                    month = monthly.month,
                    perAgentUsd = monthly.perAgentUsd,
                    totalUsd = monthly.totalUsd,
                    availableMonths = monthly.availableMonths,
                ),
            )
        }

        get("/costs/totals") {
            if (call.deniedUnlessAdmin(authorizer)) return@get
            val totals = costMeter.totals()
            call.respond(
                TotalCostsResponse(
                    perAgentUsd = totals.perAgentUsd,
                    totalUsd = totals.totalUsd,
                ),
            )
        }
    }
}

/** `GET /costs/by-tag/{key}`: the admin-only spend per value of a cost-tracked tag key. */
private fun Route.costByTagRoute(deps: ApiDeps) {
    with(deps) {
        get("/costs/by-tag/{key}") {
            if (call.deniedUnlessAdmin(authorizer)) return@get
            val key = call.pathParameter("key")
            if (key !in config.costTrackedTagKeys) {
                throw ValidationException("Tag key '$key' is not configured for cost tracking", "key")
            }
            val result =
                processManager.costByTag(key) { info ->
                    screenTags(info.tags, info.inputParams, agentsService.agents[info.agentName], info.secretInputKeys)
                }
            call.respond(
                CostByTagResponse(
                    key = key,
                    valuesUsd = result.valuesUsd,
                    totalUsd = result.valuesUsd.values.sum(),
                    truncated = result.truncated,
                ),
            )
        }
    }
}

/** `GET /status`, `/runners` and `/runners/queue`: the admin-only server and runner state. */
private fun Route.serverStatusRoutes(deps: ApiDeps) {
    with(deps) {
        get("/status") {
            if (call.deniedUnlessAdmin(authorizer)) return@get
            val response =
                ServerStatusResponse(
                    startTime = processManager.startTime,
                    currentTime =
                        java.time.Instant
                            .now()
                            .toString(),
                    lastProcessId = processManager.lastProcessId(),
                )
            call.respond(response)
        }

        get("/runners") {
            if (call.deniedUnlessAdmin(authorizer)) return@get
            call.respond(runnersStatus(config, runnerRegistry))
        }

        get("/runners/queue") {
            if (call.deniedUnlessAdmin(authorizer)) return@get
            call.respond(runnerQueueStatus(runnerRegistry.queue.pendingSnapshot(), runnerRegistry.queue.nowMs()))
        }
    }
}
