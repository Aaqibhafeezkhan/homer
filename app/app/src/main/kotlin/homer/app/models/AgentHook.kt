package homer.app.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Upper bound on `hooks` entries per agent: a local cap on the fan-out one run can cause. §12 rule 4. */
const val MAX_AGENT_HOOKS = 16

/**
 * One agent-level status hook: when a run of the declaring agent reaches [status],
 * Homer starts [agent] — whatever started the run (cron, HTTP, MCP, console retry,
 * a sub-run). Declared as a one-key `{STATUS: agentName}` entry in the agent's
 * `hooks` list; several entries may name the same status, and each one starts its
 * own agent. See ADR-0107.
 */
@Serializable(with = AgentHookSerializer::class)
data class AgentHook(
    val status: ProcessStatus,
    val agent: String,
) {
    init {
        require(AGENT_NAME_PATTERN.matches(agent)) {
            "hook target '$agent' is not a valid agent name: only letters, digits, '-', '_' allowed"
        }
    }
}

/**
 * The agent-name charset (AGENT.md § Agent Fields, `name` row), defined here in the kernel and
 * aliased by `AgentDirs`: a hook target and an agent directory are the same contract, so they
 * read one pattern — a widening that reaches one reaches both. The JSON schema's `pattern` is
 * the only other copy, as a contract surface.
 */
internal val AGENT_NAME_PATTERN = Regex("^[a-zA-Z0-9_-]+$")

/**
 * Reads and writes the one-key `{STATUS: agentName}` wire form of an [AgentHook].
 *
 * The entry is a single-entry string map, so the same shape is expressible in YAML
 * and in JSON (a list of statuses as the key is not — JSON object keys are strings).
 * A decode names only the offending key, never the value: the value is an agent name
 * an untrusted definition supplies and the error text reaches the load log and the
 * definition-write API response. See ADR-0107, root §12 rule 7.
 */
object AgentHookSerializer : KSerializer<AgentHook> {
    private val entries = MapSerializer(String.serializer(), String.serializer())

    override val descriptor: SerialDescriptor = entries.descriptor

    override fun serialize(
        encoder: Encoder,
        value: AgentHook,
    ) {
        entries.serialize(encoder, mapOf(value.status.name to value.agent))
    }

    override fun deserialize(decoder: Decoder): AgentHook {
        val entry = entries.deserialize(decoder)
        if (entry.size != 1) {
            throw SerializationException(
                "a hook entry must have exactly one key (a run status); got ${entry.size}",
            )
        }
        val (key, target) = entry.entries.single()
        val status =
            ProcessStatus.entries.firstOrNull { it.name == key }
                ?: throw SerializationException("hook key '$key' is not a run status")
        return AgentHook(status, target)
    }
}
