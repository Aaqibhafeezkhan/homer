package homer.app.executors.llm

/**
 * What [LlmHarnessExecutor] charges after a priced attempt: the agent's spend, counted against the
 * cost caps. Implemented by `CostMeter`. See ADR-0035 and ADR-0083.
 */
interface CostRecorder {
    /** Records [costUsd] against [agentName]'s cap windows; zero or negative deltas are no-ops. */
    suspend fun recordCost(
        agentName: String,
        costUsd: Double,
    )
}
