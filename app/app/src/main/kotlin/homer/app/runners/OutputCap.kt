package homer.app.runners

import java.nio.file.Path

/** Whether either output file of a remote command has grown past [maxBytes]; the SSH and Kubernetes executors share it (the external one trips its cap in OutputSink). */
internal fun exceedsOutputCap(
    stdOut: Path,
    stdErr: Path,
    maxBytes: Long,
): Boolean = stdOut.toFile().length() > maxBytes || stdErr.toFile().length() > maxBytes

/** Why a remote command was killed, for the note each shell executor appends to its stderr. */
internal class KillCause {
    var byTimeout = false
    var byCap = false
}
