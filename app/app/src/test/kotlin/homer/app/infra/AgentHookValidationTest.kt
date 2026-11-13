package homer.app.infra

import homer.app.fixtures.AgentFixtures
import homer.app.models.Agent
import homer.app.models.AgentHook
import homer.app.models.ProcessStatus
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
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

    @Test
    fun `any hook graph converges to survivors that are cycle-free and fully loaded`() =
        runTest {
            // The examples pin who is rejected; the property pins the post-conditions on every
            // graph: survivors declare only survivors, none reaches itself, every removal has a
            // reason, and a second pass finds nothing left to remove.
            checkAll(
                Arb.list(Arb.element(POOL_NAMES), 1..5),
                Arb.list(Arb.list(Arb.element(POOL_NAMES + "ghost"), 0..3), POOL_NAMES.size..POOL_NAMES.size),
                Arb.list(Arb.list(Arb.element(ProcessStatus.entries), 0..3), POOL_NAMES.size..POOL_NAMES.size),
            ) { chosen, targetsPerName, statusesPerName ->
                // `Agent` rejects a hook naming its declarer (Agent.kt), so a self-target is
                // filtered out of the slot; `apply` never sees one from a load either.
                val agents =
                    chosen.distinct().map { name ->
                        val slot = POOL_NAMES.indexOf(name)
                        agent(
                            name,
                            targetsPerName[slot]
                                .zip(statusesPerName[slot]) { target, status -> AgentHook(status, target) }
                                .filter { it.agent != name },
                        )
                    }
                val catalog = agents.associateBy { it.name }.toMutableMap()
                val rejected = AgentHookValidation.apply(catalog)

                assertTrue(rejected.keys.intersect(catalog.keys).isEmpty(), "kept and rejected overlap: $rejected")
                assertTrue(rejected.values.all { it.isNotEmpty() }, "a rejection without a reason: $rejected")
                for ((name, keptAgent) in catalog) {
                    assertTrue(keptAgent.hooks.all { it.agent in catalog }, "$name targets an agent that is gone")
                    assertTrue(!reaches(catalog, name), "$name reaches itself")
                }
                assertEquals(emptyMap(), AgentHookValidation.apply(catalog.toMutableMap()))
            }
        }

    /** Whether [name] reaches itself through the kept graph's hooks. */
    private fun reaches(
        kept: Map<String, Agent>,
        name: String,
    ): Boolean {
        val seen = mutableSetOf(name)
        val queue = ArrayDeque(kept.getValue(name).hooks.map { it.agent })
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next == name) return true
            if (seen.add(next)) kept[next]?.hooks?.forEach { queue.add(it.agent) }
        }
        return false
    }

    private companion object {
        val POOL_NAMES = listOf("a", "b", "c", "d", "e", "f")
    }
}
