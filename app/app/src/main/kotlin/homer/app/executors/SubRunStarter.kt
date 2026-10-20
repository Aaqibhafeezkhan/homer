package homer.app.executors

import homer.app.models.Agent

/**
 * What [RunAgentExecutor] needs from the services to start a `!<agent>` sub-run under its caller's
 * authority: the target, the hop gate, the flow root the child may carry and the start itself.
 * Implemented by `AgentSubRunStarter`. See ADR-0030 and ADR-0083.
 */
internal interface SubRunStarter {
    /** The loaded agent named [name], or null when there is none. */
    fun agent(name: String): Agent?

    /** Whether a run of [dispatchingAgent] owned by [root] may start [target] (ADR-0047 rule 3). */
    fun canDispatchHop(
        root: String?,
        dispatchingAgent: String,
        target: String,
    ): Boolean

    /**
     * [information_disclosure / CWE-200] The flow root a child of [originProcessId] attributed to
     * [principal] may carry: null when the origin is unreadable or [principal] cannot see the root.
     * See ADR-0058. §12.
     */
    suspend fun visibleOriginRootProcessId(
        originProcessId: Int,
        principal: String?,
    ): Int?

    /**
     * Starts [agent]'s run behind the invocation-chain cycle and depth checks, the cost cap and the
     * cooldown, and returns its process id; a refusal throws its HomerException.
     */
    suspend fun start(
        agent: Agent,
        invocationChain: List<String>,
        source: String,
        ancestorLockKeys: List<String>,
        owner: String?,
        parentProcessId: Int?,
        rootProcessId: Int?,
        resolveInputs: suspend () -> Map<String, String>,
    ): Int
}
