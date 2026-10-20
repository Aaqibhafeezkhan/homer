package homer.app.services

import homer.app.exceptions.QuestionNotFoundException
import homer.app.exceptions.QuestionNotOpenException
import homer.app.exceptions.QuestionsClosedForProcessException
import homer.app.exceptions.TooManyConcurrentWaitsException
import homer.app.exceptions.TooManyOpenQuestionsException
import homer.app.exceptions.ValidationException
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.TestFixtures
import homer.app.fixtures.all
import homer.app.infra.SecretRedactor
import homer.app.models.QuestionsConfig
import homer.app.services.QuestionsService.QuestionStatus
import homer.app.services.persistence.QuestionsRepository
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class QuestionsServiceTest {
    private fun service(
        broadcaster: ProcessEventBroadcaster = ProcessEventBroadcaster(),
        config: QuestionsConfig = QuestionsConfig(),
    ) = QuestionsService(QuestionsRepository(PersistenceTestSupport.tempDatabase()), TestFixtures.mockLogger(), broadcaster, config)

    private fun spiedRepository() = spyk(QuestionsRepository(PersistenceTestSupport.tempDatabase()))

    /** A gate a repository call suspends on: [entered] completes when the call arrives, [release] lets it go on. */
    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        suspend fun pass() {
            entered.complete(Unit)
            release.await()
        }
    }

    @Test
    fun `ask returns an open question listed for its process`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")

            val question = service.ask(1, "Deploy to prod?", options = listOf("yes", "no"))

            assertEquals(QuestionStatus.OPEN, question.status)
            assertEquals(1, question.processId)
            assertEquals("agent-a", question.agentName)
            assertEquals("Deploy to prod?", question.text)
            assertEquals(listOf("yes", "no"), question.options)
            assertEquals(listOf(question.id), service.all(processId = 1).map { it.id })
        }

    @Test
    fun `ask rejects blank text`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")
            assertThrows<ValidationException> { service.ask(1, "  ") }
        }

    @Test
    fun `ask rejects blank and duplicate options`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")
            assertThrows<ValidationException> { service.ask(1, "Pick one", options = listOf("a", " ")) }
            assertThrows<ValidationException> { service.ask(1, "Pick one", options = listOf("a", "a")) }
        }

    @Test
    fun `ask rejects oversized text and options`() =
        runTest {
            val service = service(config = QuestionsConfig(maxTextLength = 10, maxOptions = 2))
            service.registerProcess(1, "agent-a")
            assertThrows<ValidationException> { service.ask(1, "x".repeat(11)) }
            assertThrows<ValidationException> { service.ask(1, "Pick", options = listOf("a", "b", "c")) }
            assertThrows<ValidationException> { service.ask(1, "Pick", options = listOf("y".repeat(11))) }
            // At the limits everything is accepted.
            service.ask(1, "x".repeat(10), options = listOf("a", "y".repeat(10)))
        }

    @Test
    fun `ask on an unregistered process throws`() =
        runTest {
            assertThrows<QuestionsClosedForProcessException> { service().ask(99, "anyone there?") }
        }

    @Test
    fun `ask after expireForProcess throws`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")
            service.expireForProcess(1)
            assertThrows<QuestionsClosedForProcessException> { service.ask(1, "too late?") }
        }

    @Test
    fun `ask beyond maxOpenPerProcess throws`() =
        runTest {
            val service = service(config = QuestionsConfig(maxOpenPerProcess = 2))
            service.registerProcess(1, "agent-a")
            service.ask(1, "one")
            service.ask(1, "two")
            assertThrows<TooManyOpenQuestionsException> { service.ask(1, "three") }
            // The rejected ask was never stored, so it must not keep a deferred behind.
            assertEquals(2, service.heldCompletions)
            assertEquals(2, service.all(processId = 1).size)

            // Answering one frees a slot.
            service.answer(service.all(processId = 1).first().id, "ok")
            service.ask(1, "three")
        }

    @Test
    fun `answer resolves the question`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")
            val question = service.ask(1, "Proceed?")

            val answered = service.answer(question.id, "yes, carefully")

            assertEquals(QuestionStatus.ANSWERED, answered.status)
            assertEquals("yes, carefully", answered.answer)
            assertTrue(answered.answeredAt != null)
            assertEquals(QuestionStatus.ANSWERED, service.get(question.id)?.status)
        }

    @Test
    fun `answer rejects blank answer`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")
            val question = service.ask(1, "Proceed?")
            assertThrows<ValidationException> { service.answer(question.id, "   ") }
            // The question is untouched and can still be answered properly.
            assertEquals(QuestionStatus.OPEN, service.get(question.id)?.status)
        }

    @Test
    fun `answer on unknown id throws not found`() =
        runTest {
            assertThrows<QuestionNotFoundException> { service().answer("nope", "answer") }
        }

    @Test
    fun `answer twice throws conflict`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")
            val question = service.ask(1, "Proceed?")
            service.answer(question.id, "yes")
            assertThrows<QuestionNotOpenException> { service.answer(question.id, "no") }
        }

    @Test
    fun `awaitAnswer returns immediately when already answered`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")
            val question = service.ask(1, "Proceed?")
            service.answer(question.id, "yes")

            val result = service.awaitAnswer(question.id, waitSec = 600)

            assertEquals(QuestionStatus.ANSWERED, result?.status)
            assertEquals("yes", result?.answer)
            assertEquals(0, currentTime)
        }

    @Test
    fun `awaitAnswer resumes when answered mid-wait`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")
            val question = service.ask(1, "Proceed?")

            val waiter = async { service.awaitAnswer(question.id, waitSec = 600) }
            yield()
            service.answer(question.id, "go")

            val result = waiter.await()
            assertEquals(QuestionStatus.ANSWERED, result?.status)
            assertEquals("go", result?.answer)
        }

    @Test
    fun `awaitAnswer times out OPEN and is resumable`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")
            val question = service.ask(1, "Proceed?")

            val first = service.awaitAnswer(question.id, waitSec = 5)
            assertEquals(QuestionStatus.OPEN, first?.status)

            // Same id can be waited on again; this wait sees the answer.
            val waiter = async { service.awaitAnswer(question.id, waitSec = 5) }
            yield()
            service.answer(question.id, "resumed")
            assertEquals("resumed", waiter.await()?.answer)
        }

    @Test
    fun `awaitAnswer clamps waitSec to maxWaitSec`() =
        runTest {
            val service = service(config = QuestionsConfig(maxWaitSec = 5))
            service.registerProcess(1, "agent-a")
            val question = service.ask(1, "Proceed?")

            service.awaitAnswer(question.id, waitSec = 999_999)

            assertEquals(5_000, currentTime)
        }

    @Test
    fun `awaitAnswer rejects waits beyond maxConcurrentWaitsPerProcess`() =
        runTest {
            val service = service(config = QuestionsConfig(maxConcurrentWaitsPerProcess = 2))
            service.registerProcess(1, "agent-a")
            val question = service.ask(1, "Proceed?")

            val first = async { service.awaitAnswer(question.id, waitSec = 600) }
            val second = async { service.awaitAnswer(question.id, waitSec = 600) }
            yield()

            assertThrows<TooManyConcurrentWaitsException> { service.awaitAnswer(question.id, waitSec = 600) }

            // Resolving the question releases the held waits; new waits are
            // accepted again afterwards.
            service.answer(question.id, "go")
            first.await()
            second.await()
            assertEquals(QuestionStatus.ANSWERED, service.awaitAnswer(question.id, waitSec = 600)?.status)
        }

    @Test
    fun `awaitAnswer returns null for unknown id`() =
        runTest {
            assertNull(service().awaitAnswer("nope", waitSec = 1))
        }

    @Test
    fun `expireForProcess expires open questions and wakes waiters`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")
            val question = service.ask(1, "Proceed?")
            val answered = service.ask(1, "Already done?")
            service.answer(answered.id, "yes")

            val waiter = async { service.awaitAnswer(question.id, waitSec = 600) }
            yield()
            service.expireForProcess(1)

            assertEquals(QuestionStatus.EXPIRED, waiter.await()?.status)
            // Answered questions are left alone.
            assertEquals(QuestionStatus.ANSWERED, service.get(answered.id)?.status)
        }

    @Test
    fun `removeForProcess drops questions and registration`() =
        runTest {
            val service = service()
            val token = service.registerProcess(1, "agent-a")
            val question = service.ask(1, "Proceed?")

            service.removeForProcess(1)

            assertNull(service.get(question.id))
            assertNull(service.resolveToken(token))
            assertTrue(service.all().isEmpty())
        }

    @Test
    fun `resolveToken maps tokens to their own process only`() =
        runTest {
            val service = service()
            val tokenA = service.registerProcess(1, "agent-a")
            val tokenB = service.registerProcess(2, "agent-b")

            assertNotEquals(tokenA, tokenB)
            assertEquals(1, service.resolveToken(tokenA))
            assertEquals(2, service.resolveToken(tokenB))
            assertNull(service.resolveToken("not-a-token"))
        }

    @Test
    fun `registrationFor exposes the redactor supplied at registration and drops it once closed`() =
        runTest {
            val service = service()
            val redactor = SecretRedactor.from(mapOf("TOKEN" to "s3cr3tvalue1"), setOf("TOKEN"))
            service.registerProcess(1, "agent-a") { redactor }

            assertSame(redactor, service.registrationFor(1)?.secretRedactor)

            service.expireForProcess(1)
            // A closed registration answers 409 before any guard runs, so the needles go too.
            assertSame(SecretRedactor.NONE, service.registrationFor(1)?.secretRedactor)
        }

    @Test
    fun `registerProcess hands the minted token to the redactor factory`() =
        runTest {
            val service = service()
            var seen: String? = null

            val token =
                service.registerProcess(1, "agent-a") {
                    seen = it
                    SecretRedactor.NONE
                }

            assertEquals(token, seen)
        }

    @Test
    fun `registerProcess without a redactor factory registers a no-op redactor`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")
            assertSame(SecretRedactor.NONE, service.registrationFor(1)?.secretRedactor)
        }

    @Test
    fun `resolveToken still resolves after expireForProcess`() =
        runTest {
            val service = service()
            val token = service.registerProcess(1, "agent-a")
            service.expireForProcess(1)
            // Terminal-but-unpurged processes keep their token so a late
            // helper call gets a clean 409, not a 401.
            assertEquals(1, service.resolveToken(token))
        }

    @Test
    fun `openCountByProcess counts only open questions`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")
            service.registerProcess(2, "agent-b")
            service.ask(1, "one")
            service.ask(1, "two")
            val answered = service.ask(2, "three")
            service.answer(answered.id, "done")

            assertEquals(mapOf(1 to 2), service.openCountByProcess())
        }

    @Test
    fun `list filters by status and processId`() =
        runTest {
            val service = service()
            service.registerProcess(1, "agent-a")
            service.registerProcess(2, "agent-b")
            val first = service.ask(1, "one")
            service.ask(2, "two")
            service.answer(first.id, "done")

            assertEquals(listOf(first.id), service.all(status = QuestionStatus.ANSWERED).map { it.id })
            assertEquals(listOf("two"), service.all(processId = 2).map { it.text })
            assertEquals(2, service.all().size)
        }

    @Test
    fun `expireForProcess emits a QuestionExpired event per expired question`() =
        runTest {
            val broadcaster = ProcessEventBroadcaster()
            val service = service(broadcaster)
            service.registerProcess(1, "agent-a")
            val question = service.ask(1, "Proceed?")

            // Subscribed after ask(), so the only event seen is the expiry.
            val received = async { broadcaster.subscribe().take(1).toList() }
            yield()
            service.expireForProcess(1)

            val expired = received.await()[0] as ProcessEvent.QuestionExpired
            assertEquals(1, expired.processId)
            assertEquals(question.id, expired.questionId)
        }

    @Test
    fun `ask and answer emit broadcaster events`() =
        runTest {
            val broadcaster = ProcessEventBroadcaster()
            val service = service(broadcaster)
            service.registerProcess(1, "agent-a")

            val received = async { broadcaster.subscribe().take(2).toList() }
            yield()
            val question = service.ask(1, "Proceed?")
            service.answer(question.id, "yes")

            val events = received.await()
            val asked = events[0] as ProcessEvent.QuestionAsked
            assertEquals(1, asked.processId)
            assertEquals(question.id, asked.questionId)
            assertEquals("agent-a", asked.agentName)
            val answered = events[1] as ProcessEvent.QuestionAnswered
            assertEquals(1, answered.processId)
            assertEquals(question.id, answered.questionId)
        }

    @Test
    fun `no repository write runs while the mutex is held`() =
        runTest {
            val repository = spiedRepository()
            val service = QuestionsService(repository, TestFixtures.mockLogger(), ProcessEventBroadcaster())
            service.registerProcess(1, "agent-a")
            val insert = Gate()
            val expire = Gate()
            val remove = Gate()
            coEvery { repository.tryInsert(any(), any()) } coAnswers {
                insert.pass()
                callOriginal()
            }
            coEvery { repository.expireForProcess(any()) } coAnswers {
                expire.pass()
                callOriginal()
            }
            coEvery { repository.removeForProcess(any()) } coAnswers {
                remove.pass()
                callOriginal()
            }

            // While each write is suspended, another process can still register (it needs the mutex).
            val asking = launch { service.ask(1, "Proceed?") }
            insert.entered.await()
            withTimeout(1.seconds) { service.registerProcess(2, "agent-b") }
            insert.release.complete(Unit)
            asking.join()

            val expiring = launch { service.expireForProcess(1) }
            expire.entered.await()
            withTimeout(1.seconds) { service.registerProcess(3, "agent-c") }
            expire.release.complete(Unit)
            expiring.join()

            val removing = launch { service.removeForProcess(1) }
            remove.entered.await()
            withTimeout(1.seconds) { service.registerProcess(4, "agent-d") }
            remove.release.complete(Unit)
            removing.join()

            // The compensating writes of an ask that lost its race against the run ending.
            val lateInsert = Gate()
            val expireIfOpen = Gate()
            val delete = Gate()
            coEvery { repository.tryInsert(any(), any()) } coAnswers {
                callOriginal().also { lateInsert.pass() }
            }
            coEvery { repository.expireIfOpen(any()) } coAnswers {
                expireIfOpen.pass()
                callOriginal()
            }
            coEvery { repository.delete(any()) } coAnswers {
                delete.pass()
                callOriginal()
            }

            service.registerProcess(5, "agent-e")
            val closedAsk = async { runCatching { service.ask(5, "Proceed?") } }
            lateInsert.entered.await()
            service.expireForProcess(5)
            lateInsert.release.complete(Unit)
            expireIfOpen.entered.await()
            // Released in finally: the gated write runs NonCancellable, so a failed timeout must not strand it.
            try {
                withTimeout(1.seconds) { service.registerProcess(6, "agent-f") }
            } finally {
                expireIfOpen.release.complete(Unit)
            }
            assertTrue(closedAsk.await().exceptionOrNull() is QuestionsClosedForProcessException)

            val removedInsert = Gate()
            coEvery { repository.tryInsert(any(), any()) } coAnswers {
                callOriginal().also { removedInsert.pass() }
            }
            service.registerProcess(7, "agent-g")
            val removedAsk = async { runCatching { service.ask(7, "Proceed?") } }
            removedInsert.entered.await()
            service.removeForProcess(7)
            removedInsert.release.complete(Unit)
            delete.entered.await()
            try {
                withTimeout(1.seconds) { service.registerProcess(8, "agent-h") }
            } finally {
                delete.release.complete(Unit)
            }
            assertTrue(removedAsk.await().exceptionOrNull() is QuestionsClosedForProcessException)
        }

    @Test
    fun `ask whose insert commits after expireForProcess leaves no OPEN question and throws`() =
        runTest {
            val repository = spiedRepository()
            val broadcaster = mockk<ProcessEventBroadcaster>(relaxed = true)
            val service = QuestionsService(repository, TestFixtures.mockLogger(), broadcaster)
            service.registerProcess(1, "agent-a")
            val insert = Gate()
            coEvery { repository.tryInsert(any(), any()) } coAnswers {
                insert.pass()
                callOriginal()
            }

            val asking = async { runCatching { service.ask(1, "Proceed?") } }
            insert.entered.await()
            service.expireForProcess(1)
            insert.release.complete(Unit)

            assertTrue(asking.await().exceptionOrNull() is QuestionsClosedForProcessException)
            val questions = service.all(processId = 1)
            assertEquals(listOf(QuestionStatus.EXPIRED), questions.map { it.status })
            verify(exactly = 0) { broadcaster.emit(match { it is ProcessEvent.QuestionAsked }) }
        }

    @Test
    fun `ask whose insert commits before expireForProcess ends expired and throws`() =
        runTest {
            val repository = spiedRepository()
            val service = QuestionsService(repository, TestFixtures.mockLogger(), ProcessEventBroadcaster())
            service.registerProcess(1, "agent-a")
            val afterInsert = Gate()
            coEvery { repository.tryInsert(any(), any()) } coAnswers {
                callOriginal().also { afterInsert.pass() }
            }

            val asking = async { runCatching { service.ask(1, "Proceed?") } }
            afterInsert.entered.await()
            service.expireForProcess(1)
            afterInsert.release.complete(Unit)

            assertTrue(asking.await().exceptionOrNull() is QuestionsClosedForProcessException)
            assertEquals(listOf(QuestionStatus.EXPIRED), service.all(processId = 1).map { it.status })
        }

    @Test
    fun `ask whose insert commits after removeForProcess leaves no question and throws`() =
        runTest {
            val repository = spiedRepository()
            val service = QuestionsService(repository, TestFixtures.mockLogger(), ProcessEventBroadcaster())
            service.registerProcess(1, "agent-a")
            val insert = Gate()
            coEvery { repository.tryInsert(any(), any()) } coAnswers {
                insert.pass()
                callOriginal()
            }

            val asking = async { runCatching { service.ask(1, "Proceed?") } }
            insert.entered.await()
            service.removeForProcess(1)
            insert.release.complete(Unit)

            assertTrue(asking.await().exceptionOrNull() is QuestionsClosedForProcessException)
            assertTrue(service.all().isEmpty())
        }

    @Test
    fun `ask whose insert commits before removeForProcess deletes it leaves no question and throws`() =
        runTest {
            val repository = spiedRepository()
            val broadcaster = mockk<ProcessEventBroadcaster>(relaxed = true)
            val service = QuestionsService(repository, TestFixtures.mockLogger(), broadcaster)
            service.registerProcess(1, "agent-a")
            val afterInsert = Gate()
            coEvery { repository.tryInsert(any(), any()) } coAnswers {
                callOriginal().also { afterInsert.pass() }
            }
            val compensatingDeletes = mutableListOf<Boolean>()
            coEvery { repository.delete(any()) } coAnswers {
                callOriginal().also { compensatingDeletes += it }
            }

            val asking = async { runCatching { service.ask(1, "Proceed?") } }
            afterInsert.entered.await()
            // The committed question is deleted by the purge before ask re-checks the registration.
            service.removeForProcess(1)
            afterInsert.release.complete(Unit)

            assertTrue(asking.await().exceptionOrNull() is QuestionsClosedForProcessException)
            assertEquals(listOf(false), compensatingDeletes)
            assertTrue(service.all().isEmpty())
            verify(exactly = 0) { broadcaster.emit(match { it is ProcessEvent.QuestionAsked }) }
        }

    @Test
    fun `ask whose compensating write throws still releases its waiters and rethrows`() =
        runTest {
            val repository = spiedRepository()
            val service = QuestionsService(repository, TestFixtures.mockLogger(), ProcessEventBroadcaster())
            service.registerProcess(1, "agent-a")
            val afterInsert = Gate()
            coEvery { repository.tryInsert(any(), any()) } coAnswers {
                callOriginal().also { afterInsert.pass() }
            }
            // The run's expiry write ran before the insert committed, so ask must compensate itself.
            coEvery { repository.expireForProcess(1) } returns emptyList()
            coEvery { repository.expireIfOpen(any()) } throws IllegalStateException("disk I/O error")

            val asking = async { runCatching { service.ask(1, "Proceed?") } }
            afterInsert.entered.await()
            val id = service.all(processId = 1).single().id
            val waiter = async { service.awaitAnswer(id, waitSec = 600) }
            testScheduler.runCurrent()
            service.expireForProcess(1)
            afterInsert.release.complete(Unit)

            assertEquals("disk I/O error", (asking.await().exceptionOrNull() as? IllegalStateException)?.message)
            // The waiter holding ask's deferred resumes instead of sleeping out its wait.
            withTimeout(1.seconds) { waiter.await() }
            // The deferred is gone too: a new wait on the question (still OPEN) gets a fresh one and times out.
            val start = currentTime
            assertEquals(QuestionStatus.OPEN, service.awaitAnswer(id, waitSec = 5)?.status)
            assertEquals(5_000, currentTime - start)
        }

    @Test
    fun `ask cancelled after its late insert committed still leaves no OPEN question`() =
        runTest {
            val repository = spiedRepository()
            val service = QuestionsService(repository, TestFixtures.mockLogger(), ProcessEventBroadcaster())
            service.registerProcess(1, "agent-a")
            val beforeInsert = Gate()
            val afterInsert = Gate()
            coEvery { repository.tryInsert(any(), any()) } coAnswers {
                beforeInsert.pass()
                callOriginal().also { afterInsert.pass() }
            }

            val asking = async { runCatching { service.ask(1, "Proceed?") } }
            beforeInsert.entered.await()
            service.expireForProcess(1)
            beforeInsert.release.complete(Unit)
            afterInsert.entered.await()
            // A committed write whose caller is cancelled on the way back (the helper dying with its run).
            asking.cancel()
            afterInsert.release.complete(Unit)
            asking.join()

            assertEquals(listOf(QuestionStatus.EXPIRED), service.all(processId = 1).map { it.status })
        }
}
