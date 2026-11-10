package homer.app.services

import homer.app.models.Agent
import homer.app.models.AgentContinuationConfig
import homer.app.models.Command
import homer.app.models.Param
import homer.app.models.ParamSource
import homer.app.models.ParamType
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OriginInputInheritanceTest {
    private val logger = LoggerFactory.getLogger("t")

    private val ticket = Param(paramName = "ticket", envName = "TICKET_ID", src = ParamSource.QUERY, type = ParamType.ANY)
    private val svc = Param(paramName = "svc", envName = "SVC_ID", src = ParamSource.QUERY, type = ParamType.ANY)

    // No loaded agent can declare a reserved envName (AgentInputChecks), so this param
    // exists only to prove the helper strips one if it ever sees it.
    private val reserved = Param(paramName = "legacy", envName = "HMR_LEGACY", src = ParamSource.QUERY, type = ParamType.ANY)

    private fun agent(
        name: String = "graph",
        inherit: Boolean = true,
        inputs: List<Param> = listOf(ticket, svc),
    ) = Agent(
        name = name,
        commands = listOf(Command.Cmd(cmd = listOf("true"), timeout = 60)),
        inputs = inputs,
        continuation = if (inherit) AgentContinuationConfig(inheritInputs = true) else null,
    )

    private fun origin(
        owner: String? = "alice",
        inputParams: Map<String, String> = mapOf("TICKET_ID" to "123", "SVC_ID" to "checkout"),
        secretInputKeys: Set<String>? = setOf("SVC_ID"),
    ) = ProcessInfo(
        id = 7,
        status = ProcessStatus.FINISHED,
        agentName = "graph",
        inputParams = inputParams,
        owner = owner,
        secretInputKeys = secretInputKeys,
    )

    private suspend fun inherit(
        origin: ProcessInfo? = origin(),
        originAgentName: String = "graph",
        originOwner: String? = "alice",
        target: Agent = agent(),
    ) = inheritedOriginInputs({ origin }, originAgentName, originOwner, target, "Continuation", logger)

    @Test
    fun `the flag off inherits nothing`() =
        runTest {
            assertEquals(InheritedOriginInputs.NONE, inherit(target = agent(inherit = false)))
        }

    @Test
    fun `a continuation to another agent inherits nothing`() =
        runTest {
            assertEquals(InheritedOriginInputs.NONE, inherit(originAgentName = "other"))
        }

    @Test
    fun `an owner mismatch inherits nothing`() =
        runTest {
            assertEquals(InheritedOriginInputs.NONE, inherit(origin = origin(owner = "bob")))
        }

    @Test
    fun `an absent origin row inherits nothing`() =
        runTest {
            assertEquals(InheritedOriginInputs.NONE, inherit(origin = null))
        }

    @Test
    fun `the origin run's stored inputs come back keyed by paramName with its secret snapshot`() =
        runTest {
            val inherited = inherit()

            assertEquals(mapOf("ticket" to "123", "svc" to "checkout"), inherited.params)
            assertEquals(setOf("SVC_ID"), inherited.secretInputKeys)
        }

    @Test
    fun `an envName the agent no longer declares is dropped`() =
        runTest {
            val inherited = inherit(origin = origin(inputParams = mapOf("TICKET_ID" to "123", "GONE" to "x")))

            assertEquals(mapOf("ticket" to "123"), inherited.params)
        }

    @Test
    fun `a reserved envName is dropped even when declared`() =
        runTest {
            val inherited =
                inherit(
                    origin = origin(inputParams = mapOf("TICKET_ID" to "123", "HMR_LEGACY" to "x", "HMR_LG_THREAD" to "t-1")),
                    target = agent(inputs = listOf(ticket, reserved)),
                )

            assertEquals(mapOf("ticket" to "123"), inherited.params)
        }

    @Test
    fun `a row with no secret snapshot inherits an empty secret set`() =
        runTest {
            assertEquals(emptySet(), inherit(origin = origin(secretInputKeys = null)).secretInputKeys)
        }

    /**
     * §6: security-relevant input handling gets a property test. Whatever the origin
     * run stored and whatever the continuation carries itself, the inherited map is
     * bounded by the agent's declared inputs, never reserved, and never wins over the
     * continuation's own param of the same name.
     */
    @Test
    fun `inherited params are declared, unreserved, and lose to the continuation's own params`() =
        runTest {
            val target = agent(inputs = listOf(ticket, svc, reserved))
            val declaredParamNames =
                target.effectiveInputs
                    .orEmpty()
                    .map { it.paramName }
                    .toSet()
            val storedNames = Arb.element("TICKET_ID", "SVC_ID", "HMR_LEGACY", "HMR_LG_THREAD", "UNDECLARED", "PATH")
            val ownNames = Arb.element("ticket", "svc", "legacy", "HMR_LG_THREAD", "other")
            checkAll(
                Arb.map(storedNames, Arb.string(0..8), minSize = 0, maxSize = 6),
                Arb.map(ownNames, Arb.string(0..8), minSize = 0, maxSize = 6),
            ) { stored, own ->
                val inherited = inherit(origin = origin(inputParams = stored), target = target)

                assertTrue(inherited.params.keys.all { it in declaredParamNames }, "undeclared key in ${inherited.params}")
                assertTrue(inherited.params.keys.none { it == "legacy" }, "reserved envName rode in: ${inherited.params}")

                val merged = inherited.params + own
                own.forEach { (name, value) -> assertEquals(value, merged[name], "own param '$name' must win") }
            }
        }
}
