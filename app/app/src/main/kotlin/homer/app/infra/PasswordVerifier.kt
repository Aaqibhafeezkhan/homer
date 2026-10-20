package homer.app.infra

import at.favre.lib.crypto.bcrypt.BCrypt
import java.util.concurrent.ConcurrentHashMap

/**
 * Thin wrapper over `at.favre.lib:bcrypt` for the username + password auth path
 * (`AuthConfig.Password`, see ADR-0011).
 *
 * `verify` always performs a bcrypt comparison even when the caller knows the
 * user does not exist — the caller passes [dummyHashAtCost] so the wall-clock
 * cost of "no such user" matches "user exists but wrong password", preventing
 * account enumeration via login latency. The dummy hash must be at the same
 * cost as the configured `bcryptCost`; a hardcoded constant at cost 12 would
 * re-introduce the oracle when the operator deviates from 12.
 */
class PasswordVerifier {
    private val dummyHashCache = ConcurrentHashMap<Int, String>()

    fun verify(
        plain: String,
        hash: String,
    ): Boolean = BCrypt.verifyer().verify(plain.toCharArray(), hash.toCharArray()).verified

    fun hash(
        plain: String,
        cost: Int,
    ): String = BCrypt.withDefaults().hashToString(cost, plain.toCharArray())

    /**
     * Bcrypt hash of [DUMMY_PLAINTEXT] at [cost], computed once per cost and
     * memoized. The login handler passes this to [verify] on the unknown-user
     * path so the rejection takes the same wall-clock time as a real
     * wrong-password rejection at the configured cost. Pre-warm by calling
     * once during server bootstrap so the first unknown-user login isn't
     * slower than subsequent ones.
     */
    fun dummyHashAtCost(cost: Int): String = dummyHashCache.computeIfAbsent(cost) { hash(DUMMY_PLAINTEXT, it) }

    companion object {
        const val DUMMY_PLAINTEXT: String = "homer-dummy-password"
    }
}
