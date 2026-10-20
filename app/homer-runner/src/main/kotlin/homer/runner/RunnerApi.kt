package homer.runner

import homer.app.runners.external.ClaimResponse
import homer.app.runners.external.OpResult
import homer.app.runners.external.RegisterInstanceRequest
import homer.app.runners.external.RegisterInstanceResponse
import homer.app.runners.external.RunnerOp
import java.io.InputStream
import java.nio.file.Path

/** The runner side of the protocol (SERVER.md § Runner protocol reference). One implementation talks HTTP; tests fake it. */
interface RunnerApi {
    suspend fun register(request: RegisterInstanceRequest): RegisterInstanceResponse

    suspend fun deregister(instanceId: String)

    /** Long-poll for a run; null when Homer answered 204. */
    suspend fun claim(instanceId: String): ClaimResponse?

    /** Reports [completed] (when given) and long-polls for the next op; null when Homer answered 204. */
    suspend fun next(
        sessionId: String,
        completed: OpResult?,
    ): RunnerOp?

    /** Streams the tar of an `upload` op into [consume]; the stream is closed afterwards. */
    suspend fun fetchTar(
        sessionId: String,
        opId: String,
        consume: (InputStream) -> Unit,
    )

    /** Posts the tar of a `download` op; [kind] is `dir` or `file`. */
    suspend fun postTar(
        sessionId: String,
        opId: String,
        kind: String,
        file: Path,
    )

    suspend fun postOutput(
        sessionId: String,
        opId: String,
        stream: String,
        seq: Long,
        bytes: ByteArray,
    )
}

/** A non-success answer from Homer. 4xx means the request itself is wrong and a retry cannot help. */
class RunnerApiException(
    val status: Int,
    message: String,
) : RuntimeException(message) {
    val isClientError: Boolean get() = status in 400..499
}
