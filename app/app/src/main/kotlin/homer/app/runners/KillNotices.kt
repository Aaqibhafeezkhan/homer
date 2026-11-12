package homer.app.runners

import java.nio.file.Path
import kotlin.time.Duration

/**
 * Appends the note naming why [kill] stopped the command: the TTL-timeout marker, the output-cap
 * marker, or nothing when neither flag is set (a clean stop or a caller cancellation). Shared so
 * every shell executor words the note alike.
 */
internal fun appendKillNotices(
    stdErr: Path,
    kill: KillCause,
    timeout: Duration,
    maxOutputBytes: Long,
) {
    if (kill.byTimeout) stdErr.toFile().appendText("\n[HOMER] Command killed due to TTL timeout ($timeout)\n")
    if (kill.byCap) stdErr.toFile().appendText("\n[HOMER] Output cap exceeded ($maxOutputBytes bytes)\n")
}
