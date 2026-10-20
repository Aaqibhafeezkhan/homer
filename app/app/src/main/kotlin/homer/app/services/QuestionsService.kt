package homer.app.services

import homer.app.exceptions.QuestionNotFoundException
import homer.app.exceptions.QuestionNotOpenException
import homer.app.exceptions.QuestionsClosedForProcessException
import homer.app.exceptions.TooManyConcurrentWaitsException
import homer.app.exceptions.TooManyOpenQuestionsException
import homer.app.exceptions.ValidationException
import homer.app.executors.QuestionExpiry
import homer.app.executors.QuestionRegistration
import homer.app.infra.SecretRedactor
import homer.app.infra.matchesTokenConstantTime
import homer.app.models.QuestionsConfig
import homer.app.services.persistence.QuestionsRepository
import homer.app.services.persistence.RecordPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import java.security.SecureRandom
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Store of operator questions asked by running commands (`homer-ask` helper,
 * MCP question tools) and answered over the API. Durable question state lives
 * in SQLite via [QuestionsRepository] (SQLite is authoritative; see ADR-0020).
 *
 * This instance still owns the runtime-only state that cannot be persisted and
 * is rebuilt empty on boot:
 * - [registrations] — the per-process callback-token registry. `registerProcess`
 *   mints a random token; `resolveToken` maps a presented token back to its
 *   process id (constant-time). The token is the only credential agent
 *   workspaces ever see — real API credentials never enter them.
 * - [completions] — one [CompletableDeferred] per OPEN question that
 *   answer-waiters (REST long-poll, MCP blocking tool) suspend on, keyed by
 *   question id.
 * - [activeWaits] — per-process count of held answer-waits.
 *
 * A restart loses open questions (startup reconciliation expires them) and the
 * token registry (waiting helpers get 401 and exit). Process terminal status
 * expires open questions (waiters resume); workspace purge and capacity
 * eviction remove them. See ADR-0019.
 */
@OptIn(ExperimentalTime::class)
class QuestionsService(
    private val repository: QuestionsRepository,
    private val logger: Logger,
    private val broadcaster: ProcessEventBroadcaster,
    private val config: QuestionsConfig = QuestionsConfig(),
) : QuestionExpiry,
    QuestionRegistration {
    // Serializes registration changes against ask's registration checks (and the
    // activeWaits read-modify-write) so a question never stays OPEN in a process
    // whose registration was closed or removed (the ADR-0019 invariant). Never held
    // across a database write. NOT taken by the hot `resolveToken` auth path.
    private val mutex = Mutex()
    private val registrations = ConcurrentHashMap<Int, ProcessRegistration>()
    private val activeWaits = mutableMapOf<Int, Int>()
    private val completions = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** Deferreds still held, for tests that pin cleanup on the paths that never return a question. */
    internal val heldCompletions: Int get() = completions.size
    private val secureRandom = SecureRandom()

    /**
     * Mints and stores a callback token for [processId]. Calling again for the
     * same process replaces the previous token. [secretRedactor] receives the minted
     * token and returns the run's secret screen (its own token included); the
     * registration is never observable without it.
     */
    override suspend fun registerProcess(
        processId: Int,
        agentName: String,
        secretRedactor: (callbackToken: String) -> SecretRedactor,
    ): String {
        val token = generateToken()
        val redactor = secretRedactor(token)
        mutex.withLock {
            registrations[processId] = ProcessRegistration(agentName = agentName, token = token, secretRedactor = redactor)
        }
        return token
    }

    /**
     * Resolves a presented callback token to its process id, or null when no
     * registration matches. Each candidate comparison is constant-time, so a
     * wrong token leaks nothing about any registered token's content.
     */
    fun resolveToken(token: String): Int? =
        registrations.entries
            .firstOrNull { matchesTokenConstantTime(it.value.token, token) }
            ?.key

    /**
     * Validates question text + options against the configured limits. Shared by
     * `ask` and the ask-and-dispatch create path so both enforce the same caps.
     */
    fun validateQuestionInput(
        text: String,
        options: List<String>,
    ) {
        if (text.isBlank()) throw ValidationException("Question text must not be blank", "text")
        if (text.length >
            config.maxTextLength
        ) {
            throw ValidationException("Question text exceeds ${config.maxTextLength} characters", "text")
        }
        if (options.size > config.maxOptions) throw ValidationException("At most ${config.maxOptions} options are allowed", "options")
        if (options.any { it.isBlank() }) throw ValidationException("Options must not be blank", "options")
        if (options.any {
                it.length > config.maxTextLength
            }
        ) {
            throw ValidationException("Options exceed ${config.maxTextLength} characters", "options")
        }
        if (options.size != options.distinct().size) throw ValidationException("Options must not contain duplicates", "options")
    }

    suspend fun ask(
        processId: Int,
        text: String,
        options: List<String> = emptyList(),
    ): Question {
        validateQuestionInput(text, options)
        val question: Question
        mutex.withLock {
            val registration = registrations[processId]
            if (registration == null || registration.closed) {
                throw QuestionsClosedForProcessException(processId)
            }
            question =
                Question(
                    id = UUID.randomUUID().toString(),
                    processId = processId,
                    agentName = registration.agentName,
                    text = text,
                    options = options,
                    status = QuestionStatus.OPEN,
                    createdAt = Clock.System.now().epochSeconds,
                )
            completions[question.id] = CompletableDeferred()
        }
        // The insert runs outside the mutex, so a close or purge may land meanwhile.
        // expireForProcess/removeForProcess change the registration under the mutex
        // before their DB write: an insert they missed is caught by the re-check below.
        // NonCancellable: a committed insert must reach the re-check even if the caller
        // is cancelled on the way back.
        var outcome: AskOutcome? = null
        try {
            outcome =
                withContext(NonCancellable) {
                    if (!repository.tryInsert(question, config.maxOpenPerProcess)) return@withContext AskOutcome.CAP_REACHED
                    val registration = mutex.withLock { registrations[processId] }
                    when {
                        registration == null -> repository.delete(question.id)
                        registration.closed -> repository.expireIfOpen(question.id)
                        else -> return@withContext AskOutcome.OPEN
                    }
                    AskOutcome.CLOSED
                }
        } finally {
            // Every outcome but OPEN, a throwing write included, releases the question's waiters.
            if (outcome != AskOutcome.OPEN) completions.remove(question.id)?.complete(Unit)
        }
        when (outcome) {
            AskOutcome.OPEN -> {}

            AskOutcome.CAP_REACHED -> {
                throw TooManyOpenQuestionsException(processId, config.maxOpenPerProcess)
            }

            AskOutcome.CLOSED -> {
                throw QuestionsClosedForProcessException(processId)
            }
        }
        logger.info("Process {} asked question {}", processId, question.id)
        broadcaster.emit(ProcessEvent.QuestionAsked(processId, question.id, question.agentName))
        return question
    }

    suspend fun answer(
        id: String,
        answer: String,
    ): Question {
        if (answer.isBlank()) {
            throw ValidationException("Answer must not be blank", "answer")
        }
        when (val result = repository.markAnswered(id, answer, Clock.System.now().epochSeconds)) {
            is QuestionsRepository.AnswerResult.NotFound -> {
                throw QuestionNotFoundException(id)
            }

            is QuestionsRepository.AnswerResult.NotOpen -> {
                throw QuestionNotOpenException(id, result.status.name)
            }

            is QuestionsRepository.AnswerResult.Answered -> {
                completions.remove(id)?.complete(Unit)
                logger.info("Question {} (process {}) answered", id, result.question.processId)
                broadcaster.emit(ProcessEvent.QuestionAnswered(result.question.processId, id))
                return result.question
            }
        }
    }

    suspend fun get(id: String): Question? = repository.get(id)

    /**
     * Waits up to [waitSec] seconds (clamped to `0..maxWaitSec`) for the
     * question to leave OPEN, then returns its latest snapshot — still OPEN on
     * timeout, so callers can simply wait again on the same id. Returns null
     * for an unknown id.
     */
    suspend fun awaitAnswer(
        id: String,
        waitSec: Int,
    ): Question? {
        val snapshot = repository.get(id) ?: return null
        if (snapshot.status != QuestionStatus.OPEN) return snapshot
        val clampedSec = waitSec.coerceIn(0, config.maxWaitSec)
        if (clampedSec <= 0) return repository.get(id)

        // Each held wait pins a connection for up to maxWaitSec, so the
        // per-process count is capped — otherwise a workspace command could
        // exhaust the server by piling up long-polls.
        mutex.withLock {
            val held = activeWaits.getOrDefault(snapshot.processId, 0)
            if (held >= config.maxConcurrentWaitsPerProcess) {
                throw TooManyConcurrentWaitsException(snapshot.processId, config.maxConcurrentWaitsPerProcess)
            }
            activeWaits[snapshot.processId] = held + 1
        }
        try {
            val completion = completions.getOrPut(id) { CompletableDeferred() }
            // Re-read after registering the completion: an answer/expiry that
            // fired between the snapshot read and now would have completed a
            // different (or no) deferred, so without this re-check we could
            // block until timeout on an already-resolved question.
            val recheck = repository.get(id)
            if (recheck != null && recheck.status == QuestionStatus.OPEN) {
                withTimeoutOrNull(clampedSec.seconds) { completion.await() }
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    val held = activeWaits.getOrDefault(snapshot.processId, 0)
                    if (held <= 1) {
                        activeWaits.remove(snapshot.processId)
                    } else {
                        activeWaits[snapshot.processId] = held - 1
                    }
                }
            }
        }
        return repository.get(id)
    }

    /** One page of the questions the caller can see: [QuestionsRepository.page]. */
    suspend fun page(
        status: QuestionStatus?,
        processId: Int?,
        visibleOwner: String?,
        visibleAgents: Set<String>?,
        offset: Int,
        limit: Int,
    ): RecordPage<Question> = repository.page(status, processId, visibleOwner, visibleAgents, offset, limit)

    /** Count of OPEN questions per process id (processes without one are absent). */
    suspend fun openCountByProcess(): Map<Int, Int> = repository.openCountByProcess()

    /**
     * Marks the process as no longer accepting questions and expires its OPEN
     * questions, resuming every waiter. Called on every terminal transition
     * (FINISHED/FAILED/KILLED) and on startup reconciliation. The token
     * registration stays until [removeForProcess] so late helper calls get a
     * clean 409, not a 401.
     */
    override suspend fun expireForProcess(processId: Int) {
        val expired: List<Question>
        mutex.withLock {
            // A closed registration answers 409 before any guard runs: drop the needles.
            registrations[processId]?.let { registrations[processId] = it.copy(closed = true, secretRedactor = SecretRedactor.NONE) }
        }
        expired = repository.expireForProcess(processId)
        // Remove as we resume so the map tracks only OPEN questions, not every
        // question ever asked (a held waiter keeps its own deferred reference).
        expired.forEach { completions.remove(it.id)?.complete(Unit) }
        if (expired.isNotEmpty()) {
            logger.info("Expired {} open question(s) for terminal process {}", expired.size, processId)
        }
        expired.forEach { broadcaster.emit(ProcessEvent.QuestionExpired(processId, it.id)) }
    }

    /** The asker's registration, for ask-and-dispatch create-time checks. Null if unknown. */
    fun registrationFor(processId: Int): Registration? =
        registrations[processId]?.let { Registration(it.agentName, it.closed, it.secretRedactor) }

    data class Registration(
        val agentName: String,
        val closed: Boolean,
        /** Screens runtime tag values against the run's secrets; [SecretRedactor.NONE] once closed. */
        val secretRedactor: SecretRedactor = SecretRedactor.NONE,
    )

    /** Drops the token registration and every question of a purged process. */
    suspend fun removeForProcess(processId: Int) {
        mutex.withLock { registrations.remove(processId) }
        val removedIds = repository.removeForProcess(processId)
        removedIds.forEach { completions.remove(it)?.complete(Unit) }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return HexFormat.of().formatHex(bytes)
    }

    private enum class AskOutcome { OPEN, CAP_REACHED, CLOSED }

    private data class ProcessRegistration(
        val agentName: String,
        val token: String,
        val closed: Boolean = false,
        val secretRedactor: SecretRedactor = SecretRedactor.NONE,
    )

    data class Question(
        val id: String,
        val processId: Int,
        val agentName: String,
        val text: String,
        val options: List<String> = emptyList(),
        val status: QuestionStatus,
        val answer: String? = null,
        val createdAt: Long,
        val answeredAt: Long? = null,
        val dispatchBound: Boolean = false,
    )

    enum class QuestionStatus {
        OPEN,
        ANSWERED,
        EXPIRED,
    }

    companion object {
        private const val TOKEN_BYTES = 32
    }
}
