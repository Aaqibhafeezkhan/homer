package homer.app.infra

import homer.app.fixtures.AgentFixtures
import homer.app.models.Agent
import homer.app.models.AgentHook
import homer.app.models.ProcessStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentHookValidationTest {
    private fun agent(
        name: String,
        hooks: List<AgentHook>,
    ): Agent = AgentFixtures.simpleAgent(name = name).copy(hooks = hooks)

    private fun hook(
        status: ProcessStatus = ProcessStatus.FINISHED,
        target: String,
    ) = AgentHook(status, target)

    private fun validate(vararg agents: Agent): Pair<Map<String, Agent>, Map<String, List<String>>> {
        val catalog = agents.associateBy { it.name }.toMutableMap()
        val rejected = AgentHookValidation.apply(catalog)
        return catalog to rejected
    }

    @Test
    fun `a hook naming an agent that is not loaded rejects the declaring agent`() {
        val (kept, rejected) = validate(agent("declarer", listOf(hook(target = "ghost"))))

        assertEquals(emptyMap(), kept)
        assertEquals(listOf("hook target 'ghost' is not a loaded agent"), rejected["declarer"])
    }

    @Test
    fun `a two-agent cycle rejects both`() {
        val (kept, rejected) =
            validate(
                agent("a", listOf(hook(target = "b"))),
                agent("b", listOf(hook(target = "a"))),
            )

        assertEquals(emptyMap(), kept)
        assertEquals(setOf("a", "b"), rejected.keys)
        assertTrue(rejected.getValue("a").single().contains("hook cycle"), rejected.toString())
    }

    @Test
    fun `a transitive cycle rejects every agent on it`() {
        val (kept, rejected) =
            validate(
                agent("a", listOf(hook(target = "b"))),
                agent("b", listOf(hook(target = "c"))),
                agent("c", listOf(hook(target = "a"))),
            )

        assertEquals(emptyMap(), kept)
        assertEquals(setOf("a", "b", "c"), rejected.keys)
    }

    @Test
    fun `rejection cascades to the agents whose only target was rejected`() {
        // `mid` is only invalid because `top` is; the pass repeats until nothing changes, so
        // the agent that named it is rejected in the same load rather than one reload later.
        val (kept, rejected) =
            validate(
                agent("mid", listOf(hook(target = "top"))),
                agent("top", listOf(hook(target = "ghost"))),
            )

        assertEquals(emptyMap(), kept)
        assertEquals(listOf("hook target 'top' is not a loaded agent"), rejected["mid"])
    }

    @Test
    fun `a diamond is not a cycle`() {
        // Two paths to the same target are a DAG: only a path back to the start is a cycle.
        val (kept, rejected) =
            validate(
                agent("a", listOf(hook(target = "b"), hook(target = "c"))),
                agent("b", listOf(hook(target = "d"))),
                agent("c", listOf(hook(target = "d"))),
                agent("d", emptyList()),
            )

        assertEquals(setOf("a", "b", "c", "d"), kept.keys)
        assertEquals(emptyMap(), rejected)
    }

    @Test
    fun `an agent with no hooks and a valid chain are both kept`() {
        val (kept, rejected) =
            validate(
                agent("plain", emptyList()),
                agent("first", listOf(hook(status = ProcessStatus.FAILED, target = "second"))),
                agent("second", listOf(hook(status = ProcessStatus.KILLED, target = "third"))),
                agent("third", emptyList()),
            )

        assertEquals(setOf("plain", "first", "second", "third"), kept.keys)
        assertEquals(emptyMap(), rejected)
    }

    @Test
    fun `only the offending agents are removed`() {
        val (kept, rejected) =
            validate(
                agent("good", listOf(hook(target = "leaf"))),
                agent("leaf", emptyList()),
                agent("bad", listOf(hook(target = "ghost"))),
            )

        assertEquals(setOf("good", "leaf"), kept.keys)
        assertEquals(setOf("bad"), rejected.keys)
    }

    @Test
    fun `an unknown target is reported once per distinct name`() {
        val (_, rejected) =
            validate(
                agent(
                    "declarer",
                    listOf(
                        hook(ProcessStatus.CREATED, "ghost"),
                        hook(ProcessStatus.FINISHED, "ghost"),
                        hook(ProcessStatus.KILLED, "phantom"),
                    ),
                ),
            )

        assertEquals(
            listOf("hook target 'ghost' is not a loaded agent", "hook target 'phantom' is not a loaded agent"),
            rejected["declarer"],
        )
    }
}
