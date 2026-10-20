package homer.app.runners.external

import homer.app.exceptions.RunnerRegistrationRejectedException
import homer.app.exceptions.TooManyRunnerInstancesException
import homer.app.models.RunnerConfig
import homer.app.models.RunnerTagValidation
import homer.app.runners.Runner
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import homer.app.runners.queue.RunnerClaimQueue
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * One `!<external>` alias: the token its instances authenticate with, the live
 * instance registry and the heartbeat sweep. Implements [Runner] only for the
 * facets `QueuedRunner` reads (`isRemote`, `runnerEnv`, `sysEnvBackedEnvNames`);
 * sessions are created by the claim route, never by [openSession]. See ADR-0066.
 */
class ExternalRunnerDomain(
    override val alias: String,
    val config: RunnerConfig.External,
    private val queue: RunnerClaimQueue,
    override val sysEnvBackedEnvNames: Set<String> = emptySet(),
    private val clock: () -> Long = System::currentTimeMillis,
) : Runner {
    override val isRemote: Boolean = true
    override val runnerEnv: Map<String, String> = config.env

    private val instances = ConcurrentHashMap<String, ExternalRunnerInstance>()
    private val sessions = ConcurrentHashMap<String, ExternalRunnerSession>()
    private val registerLock = Any()

    override suspend fun openSession(params: RunnerSessionParams): RunnerSession =
        error("External runner '$alias' sessions are created by the claim route, never by openSession")

    fun register(request: RegisterInstanceRequest): ExternalRunnerInstance {
        val tags =
            try {
                RunnerTagValidation.validate("runner '$alias' registration", request.tags)
            } catch (e: IllegalArgumentException) {
                throw RunnerRegistrationRejectedException(alias, e.message ?: "invalid tags", e)
            }
        config.allowedTags?.let { allowed ->
            val outside = tags - allowed.toSet()
            if (outside.isNotEmpty()) {
                throw RunnerRegistrationRejectedException(alias, "tags ${outside.sorted()} are not in allowedTags ${allowed.sorted()}")
            }
        }
        if (request.concurrency < 1 || request.concurrency > config.maxConcurrencyPerInstance) {
            throw RunnerRegistrationRejectedException(
                alias,
                "concurrency must be in 1..${config.maxConcurrencyPerInstance}; got ${request.concurrency}",
            )
        }
        val name = validatedName(request.name)
        val id = RunnerProtocol.newId()
        val instance =
            ExternalRunnerInstance(
                id = id,
                name = name ?: id.take(8),
                alias = alias,
                tags = tags,
                declaredConcurrency = request.concurrency,
                version = request.version,
                registeredAt = clock(),
            )
        synchronized(registerLock) {
            if (instances.size >= config.maxInstances) throw TooManyRunnerInstancesException(alias, config.maxInstances)
            instances[id] = instance
        }
        queue.registerConsumer(instance)
        logger.info(
            "Runner '{}': instance '{}' registered with tags {} concurrency {}",
            alias,
            instance.name,
            tags.sorted(),
            request.concurrency,
        )
        return instance
    }

    /** The instance name [requested], null when absent or blank; rejected unless it has a tag's shape. */
    private fun validatedName(requested: String?): String? {
        // Names are logged (claim/next), so an unvalidated one is a log-injection vector — the
        // route never validates this itself. Same shape as a tag: safe to log verbatim. §12.
        val name = requested?.takeIf { it.isNotBlank() }
        if (name != null && !RunnerTagValidation.PATTERN.matches(name)) {
            throw RunnerRegistrationRejectedException(alias, "name must match ${RunnerTagValidation.PATTERN}")
        }
        return name
    }

    fun instance(id: String): ExternalRunnerInstance? = instances[id]

    fun instances(): List<ExternalRunnerInstance> = instances.values.toList()

    fun now(): Long = clock()

    /** Creates the session a successful claim hands to the waiting run. */
    fun openSession(
        instance: ExternalRunnerInstance,
        params: RunnerSessionParams,
    ): ExternalRunnerSession {
        val session =
            ExternalRunnerSession(RunnerProtocol.newId(), instance, this, params) { closedSession ->
                closedSession.instance.sessionClosed()
                releaseIfIdle(closedSession.instance)
            }
        instance.sessionOpened()
        sessions[session.id] = session
        return session
    }

    fun session(id: String): ExternalRunnerSession? = sessions[id]

    fun sessions(): List<ExternalRunnerSession> = sessions.values.toList()

    /** Stops offering claims; the instance stays addressable while it has sessions in flight. */
    fun deregister(id: String): Boolean {
        val instance = instances[id] ?: return false
        instance.draining = true
        queue.unregisterConsumer(instance)
        releaseIfIdle(instance)
        logger.info("Runner '{}': instance '{}' deregistered ({} session(s) still open)", alias, instance.name, instance.inUse)
        return true
    }

    /** Forgets a draining instance once its last session closed. */
    fun releaseIfIdle(instance: ExternalRunnerInstance) {
        if (instance.draining && instance.inUse == 0) instances.remove(instance.id)
    }

    fun touch(instance: ExternalRunnerInstance) {
        instance.lastSeenAt = clock()
    }

    fun couldServe(tags: Set<String>): Boolean = config.allowedTags?.let { it.toSet().containsAll(tags) } ?: true

    fun instanceTagSets(): Collection<Set<String>> = instances.values.filter { !it.draining }.map { it.tags }

    /** Drops every instance silent for longer than `heartbeatTimeoutSec`, failing each of its sessions. */
    fun sweepExpired(): List<ExternalRunnerInstance> {
        val deadline = clock() - config.heartbeatTimeoutSec * 1000
        val expired = instances.values.filter { it.lastSeenAt < deadline }
        expired.forEach { instance ->
            instances.remove(instance.id)
            queue.unregisterConsumer(instance)
            logger.warn(
                "Runner '{}': instance '{}' missed its heartbeat ({} s); dropping it and failing {} run(s)",
                alias,
                instance.name,
                config.heartbeatTimeoutSec,
                instance.inUse,
            )
            sessions.values.filter { it.instance === instance }.forEach { session ->
                session.fail(
                    ExternalRunnerException("Runner instance '${instance.name}' missed its heartbeat (${config.heartbeatTimeoutSec} s)"),
                )
                // Forgotten now, not deferred like a closed session below: `fail` posts no Close
                // op, and the instance that would have polled for one was just dropped.
                sessions.remove(session.id)
            }
        }
        // A closed session stays addressable so the instance's `next` poll can still receive its
        // Close op; forget it once the instance had heartbeatTimeoutSec to collect it.
        sessions.values.filter { s -> s.closedAt?.let { it < deadline } == true }.forEach { sessions.remove(it.id) }
        return expired
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ExternalRunnerDomain::class.java)
    }
}
