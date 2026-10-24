package homer.app.runners

import homer.app.exceptions.ConfigurationException
import homer.app.models.Agent
import homer.app.models.RunnerFilter
import homer.app.runners.external.ExternalRunnerDomain
import homer.app.runners.local.LocalRunner
import homer.app.runners.queue.QueuedRunner
import homer.app.runners.queue.RegisteredRunner
import homer.app.runners.queue.RunnerClaimQueue
import homer.app.runners.queue.StaticRunnerConsumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configured runners plus the claim queue they serve. [get] returns the raw
 * backend (consumers, diagnostics); [forAgent] / [forAlias] return the queued
 * view every run must go through. [start] launches one consumer per entry on
 * the application scope and must be called exactly once; [stop] cancels them
 * again at shutdown. See ADR-0066.
 */
class RunnerRegistry(
    private val entries: List<RegisteredRunner>,
    val externalDomains: List<ExternalRunnerDomain> = emptyList(),
    val queue: RunnerClaimQueue = RunnerClaimQueue(maxPending = 1000),
    private val defaultClaimTimeout: Duration = 300.seconds,
    private val sweepInterval: Duration = 5.seconds,
) : RunnerLookup,
    ConfiguredRunners {
    constructor(runners: List<Runner>) : this(runners.map { RegisteredRunner(it) })

    private val byAlias: Map<String, Runner> =
        (entries.map { it.runner } + externalDomains).associateBy { it.alias }.also {
            require(it.size == entries.size + externalDomains.size) {
                "Duplicate runner aliases: ${entries.map { e -> e.runner.alias } + externalDomains.map { d -> d.alias }}"
            }
        }

    @Volatile
    private var consumerJobs: List<Job> = emptyList()

    /** Consumers for the static runners plus, when domains exist, the heartbeat sweeper (ADR-0066). */
    fun start(scope: CoroutineScope): List<Job> {
        check(consumerJobs.isEmpty()) { "RunnerRegistry.start() must be called once" }
        val consumers = entries.map { StaticRunnerConsumer(it.runner, it.tags, it.concurrency, queue).start(scope) }
        val sweeper =
            if (externalDomains.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    scope.launch {
                        while (isActive) {
                            delay(sweepInterval)
                            externalDomains.forEach {
                                runCatching { it.sweepExpired() }.onFailure { e ->
                                    logger.warn("Runner '{}': heartbeat sweep failed", it.alias, e)
                                }
                            }
                        }
                    },
                )
            }
        consumerJobs = consumers + sweeper
        return consumerJobs
    }

    /**
     * Cancels the consumer loops so a shutdown drain only waits for real run
     * jobs; call before draining the application scope.
     */
    fun stop() {
        consumerJobs.forEach { it.cancel() }
        consumerJobs = emptyList()
    }

    override fun get(alias: String): Runner = byAlias[alias] ?: error("Unknown runner '$alias'. Known: ${byAlias.keys.sorted()}")

    override fun aliases(): Set<String> = byAlias.keys

    override fun forAlias(alias: String): Runner = forAlias(alias, defaultClaimTimeout)

    /**
     * Fails at once with [ConfigurationException] for `local` when no local runner is
     * registered (`server.localRunner.enabled: false`), rather than queueing a claim
     * nothing can take.
     */
    fun forAlias(
        alias: String,
        claimTimeout: Duration,
    ): Runner {
        if (alias == LocalRunner.ALIAS && alias !in byAlias) {
            throw ConfigurationException(
                "runner '${LocalRunner.ALIAS}' is disabled (server.localRunner.enabled: false); route the agent to another runner",
            )
        }
        return QueuedRunner(RunnerFilter.Alias(alias), queue, listOf(get(alias)), claimTimeout)
    }

    fun forAgent(agent: Agent): Runner {
        val timeout = agent.runnerClaimTimeoutSec?.seconds ?: defaultClaimTimeout
        return when (val filter = agent.runnerFilter) {
            is RunnerFilter.Alias -> forAlias(filter.alias, timeout)
            is RunnerFilter.Tags -> QueuedRunner(filter, queue, candidates(filter), timeout)
        }
    }

    /** Runners whose configured tags could serve [filter], plus every domain whose `allowedTags` admit them; empty when none can. */
    private fun candidates(filter: RunnerFilter.Tags): List<Runner> =
        entries.filter { it.tags.containsAll(filter.tags) }.map { it.runner } + externalDomains.filter { it.couldServe(filter.tags) }

    companion object {
        private val logger = LoggerFactory.getLogger(RunnerRegistry::class.java)
    }
}
