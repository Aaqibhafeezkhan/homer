package homer.app.services

import homer.app.models.Agent
import homer.app.models.Command
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class LangGraphInspectServiceTest {
    private val langGraphCmd = Command.LangGraph(module = "pkg.mod")

    private val okJson =
        """{"mermaid":"graph TD; a-->b","topology":{"nodes":[{"id":"a"},{"id":"b"}],""" +
            """"edges":[{"source":"a","target":"b","label":null,"conditional":false}]}}"""

    @Test
    fun `maps inspect output to topology and mermaid`() =
        runBlocking {
            val agent =
                mockk<Agent> {
                    every { commands } returns listOf(langGraphCmd)
                    every { env } returns emptyMap()
                }
            val runner = GraphInspectRunner { _, _, _, _ -> GraphInspectResult.Ok(okJson) }

            val result = LangGraphInspectService(runner).inspect(agent)

            assertEquals(1, result.size)
            assertEquals(listOf("a", "b"), result[0].topology?.nodes?.map { it.id })
            assertEquals("graph TD; a-->b", result[0].mermaid)
            assertEquals("pkg.mod", result[0].module)
            assertNull(result[0].error)
        }

    @Test
    fun `maps failure to error field`() =
        runBlocking {
            val agent =
                mockk<Agent> {
                    every { commands } returns listOf(langGraphCmd)
                    every { env } returns emptyMap()
                }
            val runner = GraphInspectRunner { _, _, _, _ -> GraphInspectResult.Err("boom") }

            val result = LangGraphInspectService(runner).inspect(agent)

            assertEquals("boom", result[0].error)
            assertNull(result[0].mermaid)
        }

    @Test
    fun `a runner error never gains the configured interpreter path`() =
        runBlocking {
            val python = "/opt/secret-venv/bin/python"
            val agent =
                mockk<Agent> {
                    every { commands } returns listOf(Command.LangGraph(module = "pkg.mod", python = python))
                    every { env } returns emptyMap()
                }
            val usage =
                "usage: homer_langgraph [-h] {run,inspect} ...\n" +
                    "homer_langgraph: error: unrecognized arguments: --json"
            val runner = GraphInspectRunner { _, _, _, _ -> GraphInspectResult.Err(usage) }

            val result = LangGraphInspectService(runner).inspect(agent)

            assertEquals(usage, result[0].error)
            assertFalse(python in result[0].error.orEmpty())
        }

    @Test
    fun `ignores non-langgraph commands`() =
        runBlocking {
            val agent = mockk<Agent> { every { commands } returns listOf(Command.Cmd(cmd = listOf("echo", "hi"))) }
            val runner = GraphInspectRunner { _, _, _, _ -> GraphInspectResult.Ok(okJson) }

            assertTrue(LangGraphInspectService(runner).inspect(agent).isEmpty())
        }

    @Test
    fun `forwards the agent env to the runner`() =
        runBlocking {
            val agentEnv = mapOf("PYTHONPATH" to "/app/agents/factory")
            val agent =
                mockk<Agent> {
                    every { commands } returns listOf(langGraphCmd)
                    every { env } returns agentEnv
                }
            var captured: Map<String, String>? = null
            val runner =
                GraphInspectRunner { _, _, env, _ ->
                    captured = env
                    GraphInspectResult.Ok(okJson)
                }

            LangGraphInspectService(runner).inspect(agent)

            assertEquals(agentEnv, captured)
        }

    @Test
    fun `never more inspect subprocesses run at once than the configured limit`() =
        runBlocking {
            val agent =
                mockk<Agent> {
                    every { commands } returns listOf(langGraphCmd)
                    every { env } returns emptyMap()
                }
            val inFlight = AtomicInteger()
            val peak = AtomicInteger()
            val runner =
                GraphInspectRunner { _, _, _, _ ->
                    val now = inFlight.incrementAndGet()
                    peak.getAndUpdate { max(it, now) }
                    delay(30)
                    inFlight.decrementAndGet()
                    GraphInspectResult.Ok(okJson)
                }
            val service = LangGraphInspectService(runner, maxConcurrentInspects = 2)

            coroutineScope { repeat(10) { launch(Dispatchers.Default) { service.inspect(agent) } } }

            assertTrue(peak.get() in 1..2, "peak concurrency was ${peak.get()}, limit is 2")
        }

    @Test
    fun `a caller that waits out the queue limit is shed, and the slot comes back`() =
        runBlocking {
            val agent =
                mockk<Agent> {
                    every { commands } returns listOf(langGraphCmd)
                    every { env } returns emptyMap()
                }
            val runner =
                GraphInspectRunner { _, _, _, _ ->
                    delay(300)
                    GraphInspectResult.Ok(okJson)
                }
            val service =
                LangGraphInspectService(runner, maxConcurrentInspects = 1, maxQueueWait = 40.milliseconds)

            val occupier = launch(Dispatchers.Default) { service.inspect(agent) }
            delay(60) // let it take the only permit
            val shed = service.inspect(agent)
            occupier.join()

            assertEquals(LangGraphInspectService.BUSY_MESSAGE, shed[0].error)
            assertNull(shed[0].mermaid)
            // Permit released, so the next caller is served normally.
            assertEquals("graph TD; a-->b", service.inspect(agent)[0].mermaid)
        }

    @Test
    fun `an agent with several graphs takes one permit for the whole request`() =
        runBlocking {
            // Otherwise an N-graph agent queues N times and can wait N * maxQueueWait.
            val agent =
                mockk<Agent> {
                    every { commands } returns
                        listOf(
                            Command.LangGraph(module = "pkg.a"),
                            Command.LangGraph(module = "pkg.b"),
                            Command.LangGraph(module = "pkg.c"),
                        )
                    every { env } returns emptyMap()
                }
            val acquisitions = AtomicInteger()
            val runner =
                GraphInspectRunner { _, module, _, _ ->
                    acquisitions.incrementAndGet()
                    GraphInspectResult.Ok("""{"mermaid":"graph TD; $module","topology":{"nodes":[{"id":"a"}],"edges":[]}}""")
                }
            val service = LangGraphInspectService(runner, maxConcurrentInspects = 1)

            val result = service.inspect(agent)

            assertEquals(3, result.size)
            assertEquals(3, acquisitions.get(), "every command still runs")
            assertTrue(result.all { it.error == null })
        }

    @Test
    fun `unparseable inspect output becomes an error`() =
        runBlocking {
            val agent =
                mockk<Agent> {
                    every { commands } returns listOf(langGraphCmd)
                    every { env } returns emptyMap()
                }
            val runner = GraphInspectRunner { _, _, _, _ -> GraphInspectResult.Ok("graph TD; a-->b") }
            val result = LangGraphInspectService(runner).inspect(agent)

            assertNull(result[0].topology)
            assertTrue(result[0].error!!.startsWith("unparseable inspect output"))
        }

    @Test
    fun `inspect output without topology becomes an error`() =
        runBlocking {
            val agent =
                mockk<Agent> {
                    every { commands } returns listOf(langGraphCmd)
                    every { env } returns emptyMap()
                }
            val runner = GraphInspectRunner { _, _, _, _ -> GraphInspectResult.Ok("""{"mermaid":"graph TD"}""") }
            val result = LangGraphInspectService(runner).inspect(agent)

            assertNull(result[0].topology)
            assertNull(result[0].mermaid)
            assertEquals("inspect output has no graph topology", result[0].error)
        }

    @Test
    fun `an over-bound topology is refused, not served`() =
        runBlocking {
            val agent =
                mockk<Agent> {
                    every { commands } returns listOf(langGraphCmd)
                    every { env } returns emptyMap()
                }
            val dup = """{"mermaid":"m","topology":{"nodes":[{"id":"a"},{"id":"a"}],"edges":[]}}"""
            val result = LangGraphInspectService(GraphInspectRunner { _, _, _, _ -> GraphInspectResult.Ok(dup) }).inspect(agent)

            assertNull(result[0].topology)
            assertNull(result[0].mermaid)
            assertEquals("graph node id 'a' is duplicated", result[0].error)
        }
}
