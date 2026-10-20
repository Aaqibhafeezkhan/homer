package homer.app.services

import homer.app.infra.matchesTokenConstantTime
import homer.app.models.DispatchConfig
import homer.app.models.ResultEnvParam
import homer.app.services.persistence.ResultTokenRecord
import homer.app.services.persistence.ResultTokenRepository
import org.slf4j.Logger
import java.security.SecureRandom
import java.util.HexFormat
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Mints and resolves capability tokens for read-by-reference fetch of large
 * continuation results. A token is scoped to one (watched process, artifact
 * path) and audience-bound to the continuation's target agent, and is reusable
 * within its TTL — a resumed graph re-runs the node and re-fetches idempotently
 * (every resume segment runs the same target agent, so the binding holds). The
 * token's lifetime is independent of the continuation row that minted it (it
 * lives in its own table). See ADR-0025.
 */
@OptIn(ExperimentalTime::class)
internal class ResultTokenService(
    private val repository: ResultTokenRepository,
    private val logger: Logger,
    private val config: DispatchConfig = DispatchConfig(),
    private val nowSec: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val secureRandom = SecureRandom()

    /**
     * Mints a token authorizing a fetch of [path] from process [watchedProcessId]'s
     * artifacts, optionally [transformer]-mapped at fetch time. The token is
     * audience-bound to [consumerAgent] (the continuation's target) — only a caller
     * running that agent may redeem it. Expires after `config.resultTokenTtlSec`
     * (floored in config). Returns the token string.
     */
    suspend fun mint(
        watchedProcessId: Int,
        path: String,
        transformer: ResultEnvParam?,
        sizeBytes: Long,
        consumerAgent: String,
    ): String {
        val token = generateToken()
        val now = nowSec()
        repository.create(
            ResultTokenRecord(
                token = token,
                watchedProcessId = watchedProcessId,
                path = path,
                resultTransformer = transformer,
                sizeBytes = sizeBytes,
                createdAt = now,
                expiresAt = now + config.resultTokenTtlSec,
                consumerAgent = consumerAgent,
            ),
        )
        return token
    }

    /**
     * Resolves a token, enforcing TTL. Returns null when the token is unknown or
     * expired. The constant-time compare guards the token value the same way the
     * callback-token registry does, even though the row was fetched by exact key.
     */
    suspend fun resolve(token: String): ResultTokenRecord? {
        val record = repository.get(token) ?: return null
        if (!matchesTokenConstantTime(record.token, token)) return null
        val expiresAt = record.expiresAt
        if (expiresAt != null && expiresAt <= nowSec()) return null
        return record
    }

    /** Reaps expired token rows. Wired into the continuation sweep cadence. */
    suspend fun sweepExpired() {
        val removed = repository.deleteExpired(nowSec())
        if (removed > 0) logger.info("Swept {} expired result token(s)", removed)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return HexFormat.of().formatHex(bytes)
    }

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
