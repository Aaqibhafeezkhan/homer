package homer.runner

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** This host can no longer start a process or write the runner root; only a restart is expected to help. */
class RunnerUnhealthyException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Cheap self-check run before registering and before every claim: can this JVM spawn a process
 * and write under the runner root. The probe gets an empty environment so the token never
 * reaches a child. See ADR-0096.
 */
object RunnerHealth {
    val PROBE_ARGV = listOf("/bin/sh", "-c", "exit 0")

    /** Throws [RunnerUnhealthyException] and nothing else. The probe's exit value is ignored: only the spawn matters. */
    fun check(
        root: Path,
        probeArgv: List<String> = PROBE_ARGV,
        probeWait: Duration = 5.seconds,
    ) {
        probeSpawn(probeArgv, probeWait)
        probeRoot(root)
    }

    private fun probeSpawn(
        argv: List<String>,
        wait: Duration,
    ) {
        try {
            val builder = ProcessBuilder(argv)
            builder.environment().clear()
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            builder.redirectError(ProcessBuilder.Redirect.DISCARD)
            val process = builder.start()
            process.outputStream.close()
            if (!process.waitFor(wait.inWholeMilliseconds, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        } catch (e: Exception) {
            throw cannotStart(e)
        } catch (e: OutOfMemoryError) {
            // "unable to create native thread" from start(): the host has stopped spawning too.
            throw cannotStart(e)
        }
    }

    private fun cannotStart(e: Throwable) = RunnerUnhealthyException("cannot start a process: ${e.message}", e)

    private fun probeRoot(root: Path) {
        try {
            Files.createDirectories(root)
            val probe = Files.createTempFile(root, ".homer-runner-health-", null)
            try {
                Files.write(probe, byteArrayOf(0))
            } finally {
                Files.deleteIfExists(probe)
            }
        } catch (e: Exception) {
            throw RunnerUnhealthyException("cannot write the root $root: ${e.message}", e)
        }
    }
}
