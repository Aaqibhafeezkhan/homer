package homer.app.infra

import homer.app.models.Agent
import homer.app.models.AgentReloadResult

/**
 * The merge and diff of a fresh [AgentScan] against the published [AgentCatalogState] —
 * the state `AgentsService.reload` swaps in and the `agent.reload` diff it reports
 * (ADR-0068). Pure: no state mutation, no IO.
 */
internal object AgentReloadDiff {
    /** Merge a fresh scan onto a state snapshot and compute the caller-facing diff. Pure: no state mutation. */
    fun compute(
        scan: AgentScan,
        state: AgentCatalogState,
    ): ReloadComputation {
        val removed = removedAgentNames(state.agents, state.sourceDirs, scan)
        val newState =
            AgentCatalogState(
                agents = mergeAgents(state.agents, scan.parsed, removed),
                sourceDirs = mergeSourceDirs(state.sourceDirs, scan.sourceDirs, removed),
            )
        return ReloadComputation(newState, result(state.agents, scan, removed))
    }

    fun removedAgentNames(
        old: Map<String, Agent>,
        oldSourceDirs: Map<String, String>,
        scan: AgentScan,
    ): Set<String> =
        old.keys
            .filter { name ->
                name !in scan.parsed && oldSourceDirs[name] !in scan.presentDirNames
            }.toSet()

    private fun mergeAgents(
        old: Map<String, Agent>,
        parsed: Map<String, Agent>,
        removed: Set<String>,
    ): Map<String, Agent> {
        val merged = old.toMutableMap()
        parsed.forEach { (name, agent) -> merged[name] = agent }
        removed.forEach { merged.remove(it) }
        return merged
    }

    private fun mergeSourceDirs(
        old: Map<String, String>,
        newDirs: Map<String, String>,
        removed: Set<String>,
    ): Map<String, String> {
        val merged = old.toMutableMap()
        newDirs.forEach { (name, dir) -> merged[name] = dir }
        removed.forEach { merged.remove(it) }
        return merged
    }

    fun result(
        old: Map<String, Agent>,
        scan: AgentScan,
        removed: Set<String>,
    ): AgentReloadResult {
        val added = (scan.parsed.keys - old.keys).sorted()
        val updated =
            scan.parsed.keys
                .filter { it in old.keys && scan.parsed[it] != old[it] }
                .sorted()
        return AgentReloadResult(added, updated, removed.sorted(), scan.errors)
    }
}

/** The published agent catalog: the loaded agents and each one's directory relative to the search path. */
internal data class AgentCatalogState(
    val agents: Map<String, Agent>,
    val sourceDirs: Map<String, String>,
)

/** One merge/diff computation of a scan against a state snapshot: the state to publish plus the caller-facing diff. */
internal data class ReloadComputation(
    val newState: AgentCatalogState,
    val result: AgentReloadResult,
)
