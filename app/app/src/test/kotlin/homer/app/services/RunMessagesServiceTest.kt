package homer.app.services

import homer.app.exceptions.RunNotAcceptingMessagesException
import homer.app.exceptions.TooManyRunMessagesException
import homer.app.exceptions.ValidationException
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.TestFixtures
import homer.app.infra.ProcessJobRegistry
import homer.app.models.ProcessStatus
import homer.app.models.RunMessagesConfig
import homer.app.services.persistence.ProcessRepository
import homer.app.services.persistence.RunMessagesRepository
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.Logger
import java.lang.reflect.Proxy
import java.util.concurrent.CyclicBarrier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunMessagesServiceTest {
    private val db = PersistenceTestSupport.tempDatabase()
    private val processes = ProcessRepository(db, ProcessJobRegistry())
    private val repository = spyk(RunMessagesRepository(db))
    private val broadcaster = ProcessEventBroadcaster()
    private val loggedCalls = mutableListOf<String>()
    private val logger: Logger =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Logger::class.java)) { _, method, args ->
            loggedCalls +=
                method.name + " " + (args ?: emptyArray()).joinToString { a -> if (a is Array<*>) a.joinToString() else a.toString() }
            if (method.returnType == Boolean::class.javaPrimitiveType) true else null
        } as Logger

    private fun service(config: RunMessagesConfig = RunMessagesConfig()) =
        RunMessagesService(repository, broadcaster, config, logger, clock = { 500L })

    private suspend fun run(status: ProcessStatus = ProcessStatus.WORKING): Int {
        val id = processes.obtainProcessId("a", emptyMap(), 100).processId
        processes.transitionStatus(id, status, 1, null)
        return id
    }

    @Test
    fun `send rejects blank and over-long text with 400 before touching the database`() =
        runTest {
            val service = service(RunMessagesConfig(maxTextLength = 5, maxPerRun = 10))
            val blank = assertThrows<ValidationException> { service.send(1, "alice", "   ") }
            val long = assertThrows<ValidationException> { service.send(1, "alice", "123456") }

            assertEquals("Validation error for 'text': Message text must not be blank", blank.message)
            assertEquals("Validation error for 'text': Message text exceeds 5 characters", long.message)
            coVerify(exactly = 0) { repository.tryInsert(any(), any()) }
        }

    @Test
    fun `send queues a message with sender and timestamp`() =
        runTest {
            val id = run()
            val message = service().send(id, "alice", "hello")

            assertEquals(RunMessageStatus.QUEUED, message.status)
            assertEquals("alice", message.sender)
            assertEquals(500L, message.createdAt)
            assertEquals(listOf(message), service().list(id))
        }

    @Test
    fun `send to an ended run throws RunNotAcceptingMessagesException without the text in the message`() =
        runTest {
            val id = run(ProcessStatus.FINISHED)
            val e = assertThrows<RunNotAcceptingMessagesException> { service().send(id, null, "secret-text") }

            assertEquals("Process $id is no longer accepting messages", e.message)
            assertEquals(emptyList(), service().list(id))
        }

    @Test
    fun `send at the cap throws TooManyRunMessagesException`() =
        runTest {
            val id = run()
            val service = service(RunMessagesConfig(maxTextLength = 100, maxPerRun = 2))
            service.send(id, null, "one")
            service.send(id, null, "two")

            val e = assertThrows<TooManyRunMessagesException> { service.send(id, null, "three-secret") }
            assertEquals("Process $id already has 2 messages", e.message)
        }

    @Test
    fun `send emits MessageQueued, take emits MessageDelivered per message, closeForProcess emits MessageUndelivered per QUEUED message`() =
        runTest {
            val id = run()
            val service = service()
            val events = mutableListOf<ProcessEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { broadcaster.subscribe().toList(events) }

            val first = service.send(id, null, "one")
            val taken = service.take(id)
            val second = service.send(id, null, "two")
            val third = service.send(id, null, "three")
            service.closeForProcess(id)

            assertEquals(listOf(first.id), taken.map { it.id })
            assertEquals(
                listOf(
                    ProcessEvent.MessageQueued(id, first.id),
                    ProcessEvent.MessageDelivered(id, first.id),
                    ProcessEvent.MessageQueued(id, second.id),
                    ProcessEvent.MessageQueued(id, third.id),
                    ProcessEvent.MessageUndelivered(id, second.id),
                    ProcessEvent.MessageUndelivered(id, third.id),
                ),
                events,
            )
            assertEquals(
                listOf(RunMessageStatus.DELIVERED, RunMessageStatus.UNDELIVERED, RunMessageStatus.UNDELIVERED),
                service.list(id).map { it.status },
            )
            assertEquals(emptyList(), service.take(id))
        }

    @Test
    fun `concurrent takes on real threads deliver each message exactly once`() =
        runBlocking(Dispatchers.Default) {
            // Blocking JDBC on Dispatchers.IO and a barrier per round, so the takers really overlap.
            val ioDb = PersistenceTestSupport.tempDatabase(dispatcher = Dispatchers.IO)
            val ioProcesses = ProcessRepository(ioDb, ProcessJobRegistry())
            val id = ioProcesses.obtainProcessId("a", emptyMap(), 100).processId
            ioProcesses.transitionStatus(id, ProcessStatus.WORKING, 1, null)
            val takers = 4
            val rounds = 50
            val perRound = 8
            val service =
                RunMessagesService(
                    RunMessagesRepository(ioDb),
                    broadcaster,
                    RunMessagesConfig(maxTextLength = 100, maxPerRun = rounds * perRound),
                    TestFixtures.mockLogger(),
                    clock = { 500L },
                )

            repeat(rounds) { round ->
                val sent = (1..perRound).map { service.send(id, null, "m$round-$it").id }.toSet()
                val barrier = CyclicBarrier(takers)
                val taken =
                    (1..takers)
                        .map {
                            async(Dispatchers.IO) {
                                barrier.await()
                                service.take(id).map { it.id }
                            }
                        }.awaitAll()

                assertEquals(sent, taken.flatten().toSet(), "round $round")
                assertEquals(sent.size, taken.sumOf { it.size }, "round $round: a message was taken twice: $taken")
            }
        }

    @Test
    fun `log lines never contain the message text`() =
        runTest {
            val id = run()
            val service = service()
            service.send(id, "alice", "TOP-SECRET-TEXT")
            service.take(id)
            service.send(id, "alice", "TOP-SECRET-TWO")
            service.closeForProcess(id)

            assertTrue(loggedCalls.isNotEmpty())
            assertTrue(loggedCalls.none { it.contains("SECRET") }, loggedCalls.toString())
        }
}
