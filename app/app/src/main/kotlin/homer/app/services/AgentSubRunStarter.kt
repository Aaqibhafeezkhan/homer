package homer.app.services

import homer.app.executors.SubRunStarter
import homer.app.models.Agent

/**
 * [SubRunStarter] over the services: a `!<agent>` hop's target comes from [agentsService], its gate and
 * the child's flow root from [authorizer], and the start from [agentRunStarter] with the same checks
 * every entry point gets. See ADR-0030.
 */
internal class AgentSubRunStarter(
    // Provider resolved at call time, not construction, to break the DI cycle
    // RunAgentExecutor → AgentRunStarter → ProcessFactory → CommandExecutorFactory
    // → RunAgentExecutor. Same precedent as ProcessExecutor.onTerminal (ADR-0023).
    private val agentRunStarter: () -> AgentRunStarter,
    private val agentsService: AgentsService,
    private val authorizer: Authorizer,
    // Read for the origin run's root so the dispatched child joins the same flow.
    private val processManager: ProcessManager,
) : SubRunStarter {
    override fun agent(name: String): Agent? = agentsService.agents[name]

    override fun canDispatchHop(
        root: String?,
        dispatchingAgent: String,
        target: String,
    ): Boolean = authorizer.canDispatchHop(root, dispatchingAgent, target)

    override suspend fun visibleOriginRootProcessId(
        originProcessId: Int,
        principal: String?,
    ): Int? = processManager.visibleOriginRootProcessId(originProcessId, principal, authorizer)

    // The adapter forwards the port's parameters one for one.
    // jscpd:ignore-start
    override suspend fun start(
        agent: Agent,
        invocationChain: List<String>,
        source: String,
        ancestorLockKeys: List<String>,
        owner: String?,
        parentProcessId: Int?,
        rootProcessId: Int?,
        resolveInputs: suspend () -> Map<String, String>,
    ): Int =
        // jscpd:ignore-end
        agentRunStarter().start(
            agent = agent,
            invocationChain = invocationChain,
            source = source,
            ancestorLockKeys = ancestorLockKeys,
            owner = owner,
            parentProcessId = parentProcessId,
            rootProcessId = rootProcessId,
            resolveInputs = resolveInputs,
        )
}
