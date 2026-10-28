package homer.app.runners.external

import homer.app.models.RUNNER_LONG_POLL_SEC
import homer.app.runners.DownloadMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.HexFormat

/**
 * Wire contract between Homer and a pull-mode runner instance (ADR-0066). Every
 * connection is opened by the instance; Homer answers long-polls with the next
 * thing to do. Shared by the server routes and the `homer-runner` module.
 */
object RunnerProtocol {
    /** Longest a `claim` or `next` long-poll is held before answering 204. */
    const val POLL_SEC = RUNNER_LONG_POLL_SEC

    /** Upper bound on one `output` chunk body. */
    const val MAX_OUTPUT_CHUNK_BYTES = 1_048_576

    /** Upper bound on one `next` body; it carries at most a single small [OpResult]. */
    const val MAX_NEXT_BODY_BYTES = 65_536

    const val STREAM_STDOUT = "stdout"
    const val STREAM_STDERR = "stderr"

    private val random = SecureRandom()

    /** 128-bit random id, lowercase hex. Used for instances, sessions and ops. */
    fun newId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return HexFormat.of().formatHex(bytes)
    }
}

/**
 * Wire format of the bodies this protocol carries. Deliberately not `apiJson`: that one is
 * strict, so a key this binary does not know fails the whole body — and an instance and the
 * Homer it reports to are upgraded independently, while every DTO here only ever gains
 * optional fields. An unknown key is ignored instead of answered with a 400. The instance's
 * own client reads with the same rule — `HomerClient` uses this one definition, so both ends
 * of one wire cannot drift apart.
 */
val runnerProtocolJson = Json { ignoreUnknownKeys = true }

@Serializable
data class RegisterInstanceRequest(
    val name: String? = null,
    val tags: List<String> = emptyList(),
    val concurrency: Int = 1,
    val version: String? = null,
)

@Serializable
data class RegisterInstanceResponse(
    val instanceId: String,
    val pollSec: Int,
    val heartbeatTimeoutSec: Long,
)

@Serializable
data class ClaimResponse(
    val sessionId: String,
    val processId: Int,
    val agentName: String,
    val tags: List<String>,
)

@Serializable
data class NextRequest(
    val completed: OpResult? = null,
)

/** One instruction for the instance; mirrors a `RunnerSession` primitive. */
@Serializable
sealed class RunnerOp {
    abstract val opId: String

    @Serializable
    @SerialName("createWorkspace")
    data class CreateWorkspace(
        override val opId: String,
    ) : RunnerOp()

    @Serializable
    @SerialName("upload")
    data class Upload(
        override val opId: String,
        val remoteRelative: String,
        /** Whether the tar holds a directory's contents (extract under `remoteRelative`) or one file named by it. */
        val isDir: Boolean = true,
    ) : RunnerOp()

    @Serializable
    @SerialName("cmd")
    data class Cmd(
        override val opId: String,
        val argv: List<String>,
        val env: Map<String, String>,
        val stdin: String?,
        val timeoutMs: Long,
        val cwd: String,
    ) : RunnerOp()

    @Serializable
    @SerialName("download")
    data class Download(
        override val opId: String,
        val remoteRelative: String,
        val mode: DownloadMode,
    ) : RunnerOp()

    @Serializable
    @SerialName("cleanWorkspace")
    data class CleanWorkspace(
        override val opId: String,
    ) : RunnerOp()

    @Serializable
    @SerialName("close")
    data class Close(
        override val opId: String,
        val reason: String,
    ) : RunnerOp()

    /** [opId] is the running `cmd`'s id; the instance kills its process group and then reports the cmd result as usual. */
    @Serializable
    @SerialName("cancel")
    data class Cancel(
        override val opId: String,
    ) : RunnerOp()
}

/**
 * Result of one op. `createWorkspace` fills [root]; `cmd` fills [exitCode], [timedOut]
 * and the two chunk counts; `download` may set [missing]; any op may set [error] with
 * [ok] false.
 */
@Serializable
data class OpResult(
    val opId: String,
    val ok: Boolean = true,
    val root: String? = null,
    val missing: Boolean = false,
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    /**
     * How many chunks a `cmd` handed to the `output` route for this stream — one past the
     * last `seq` it posted, a chunk whose post it gave up on included. Null when the
     * instance reports no count (an older runner), which leaves the check inert.
     */
    val stdoutChunks: Long? = null,
    /** The same for `stderr`. */
    val stderrChunks: Long? = null,
    val error: String? = null,
)
