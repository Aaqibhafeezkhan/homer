package homer.app.services

import homer.app.exceptions.RunNotAcceptingMessagesException
import homer.app.exceptions.TooManyRunMessagesException
import homer.app.exceptions.ValidationException
import homer.app.executors.RunMessageExpiry
import homer.app.models.RunMessagesConfig
import homer.app.services.persistence.RunMessagesRepository
import homer.app.services.persistence.RunMessagesRepository.InsertResult
import org.slf4j.Logger
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Per-run inbox of operator messages: an operator queues text for a running run and the run takes it
 * (QUEUED to DELIVERED) through its callback token; messages still QUEUED when the run ends become
 * UNDELIVERED. Durable state lives in SQLite via [RunMessagesRepository]. Message text is operator
 * free text and never reaches logs or exception messages. See ADR-0097.
 */
@OptIn(ExperimentalTime::class)
class RunMessagesService(
    private val repository: RunMessagesRepository,
    private val broadcaster: ProcessEventBroadcaster,
    private val config: RunMessagesConfig,
    private val logger: Logger,
    private val clock: () -> Long = { Clock.System.now().epochSeconds },
) : RunMessageExpiry {
    /** Queues [text] for [processId]; 400 on a blank or over-long text, 409 on an ended run, 429 at the cap. */
    suspend fun send(
        processId: Int,
        sender: String?,
        text: String,
    ): RunMessage {
        if (text.isBlank()) throw ValidationException("Message text must not be blank", "text")
        if (text.length > config.maxTextLength) {
            throw ValidationException("Message text exceeds ${config.maxTextLength} characters", "text")
        }
        val message =
            RunMessage(
                id = UUID.randomUUID().toString(),
                processId = processId,
                text = text,
                status = RunMessageStatus.QUEUED,
                sender = sender,
                createdAt = clock(),
                deliveredAt = null,
            )
        when (repository.tryInsert(message, config.maxPerRun)) {
            InsertResult.Inserted -> {}

            InsertResult.RunNotActive -> {
                throw RunNotAcceptingMessagesException(processId)
            }

            InsertResult.CapReached -> {
                throw TooManyRunMessagesException(processId, config.maxPerRun)
            }
        }
        logger.info("Message {} queued for process {}", message.id, processId)
        broadcaster.emit(ProcessEvent.MessageQueued(processId, message.id))
        return message
    }

    suspend fun list(processId: Int): List<RunMessage> = repository.list(processId)

    /** Delivers the run's QUEUED messages, oldest first; each is returned by exactly one call. */
    suspend fun take(processId: Int): List<RunMessage> {
        val taken = repository.take(processId, clock())
        if (taken.isNotEmpty()) logger.info("Delivered {} message(s) to process {}", taken.size, processId)
        taken.forEach { broadcaster.emit(ProcessEvent.MessageDelivered(processId, it.id)) }
        return taken
    }

    override suspend fun closeForProcess(processId: Int) {
        val undelivered = repository.markUndelivered(processId)
        if (undelivered.isNotEmpty()) {
            logger.info("Marked {} queued message(s) of terminal process {} undelivered", undelivered.size, processId)
        }
        undelivered.forEach { broadcaster.emit(ProcessEvent.MessageUndelivered(processId, it.id)) }
    }

    // Boot only, before any SSE subscriber exists, so no events are emitted.
    override suspend fun closeForEndedRuns() {
        val undelivered = repository.markUndeliveredForEndedRuns()
        if (undelivered > 0) logger.info("Marked {} queued message(s) of ended processes undelivered", undelivered)
    }
}
