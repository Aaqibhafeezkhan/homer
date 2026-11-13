package homer.app.infra

import java.nio.file.Path
import kotlin.time.Duration

/** Why a command was killed, for the note each shell executor appends to its stderr. */
internal class KillCause {
    var byTimeout = false
    var byCap = false
}

/**
 * Appends the note naming why [kill] stopped the command: the TTL-timeout marker, the output-cap
 * marker, or nothing when neither flag is set (a clean stop or a caller cancellation). Shared so
 * every shell executor words the note alike. [graceNote] extends the TTL marker with the stop's
 * SIGINT grace, where the backend interrupted the group before the kill (ADR-0108).
 */
internal fun appendKillNotices(
    stdErr: Path,
    kill: KillCause,
    timeout: Duration,
    maxOutputBytes: Long,
    graceNote: String = "",
) {
    if (kill.byTimeout) stdErr.toFile().appendText("\n[HOMER] Command killed due to TTL timeout ($timeout)$graceNote\n")
    if (kill.byCap) stdErr.toFile().appendText("\n[HOMER] Output cap exceeded ($maxOutputBytes bytes)\n")
}
