package homer.app.runners.kubernetes

import homer.app.EnvConstants
import homer.app.runners.KillCause
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/** Owns the pod's process control: the `setsid` launch wrapper, pgid file, probe, and interrupt-grace/pod-wide kill paths. */
internal class PodProcessControl(
    private val execClient: KubernetesExecClient,
    private val namespace: String,
    private val podName: String,
    /** Slack added to the grace for the signalling exec's own bound. */
    private val signalExecSlackMs: Long,
) {
    private val pgidCounter = AtomicLong(0)

    /** The pod's `setsid` argv prefix, or null where it has none. Probed only when a grace is used. */
    private val setsidPrefix: List<String>? by lazy { probeSetsid() }

    /**
     * The pod-side file this command's group leader records its pid (its pgid) in, or null when
     * [interruptGrace] is null or the pod has no `setsid` — either way today's launch is used
     * unchanged. The file lives under the workspace dir from the exec env (`HMR_WORK_DIR`) when
     * present, else a `.homer` path relative to the exec's cwd. See ADR-0106.
     */
    fun pgidFileFor(
        env: Map<String, String>,
        interruptGrace: Duration?,
    ): String? {
        if (interruptGrace == null || setsidPrefix == null) return null
        val dir = env[EnvConstants.WORK_DIR]?.takeIf { it.isNotBlank() } ?: FALLBACK_PGID_DIR
        return "$dir/homer-exec-${pgidCounter.incrementAndGet()}.pgid"
    }

    /**
     * [command] wrapped so it leads its own process group: `setsid` makes the inner `sh` the new
     * session leader, which records its own pid — the group id — to `$0` and `exec`s the argv in
     * place, so the command keeps its pid, exit code, redirected streams and stdin. Mirrors
     * RemoteCommandBuilder.buildReaping (ADR-0041).
     */
    fun setsidLaunch(
        pgidFile: String,
        command: List<String>,
    ): List<String> = setsidPrefix.orEmpty() + listOf("sh", "-c", PGID_SCRIPT, pgidFile) + command

    /**
     * Stops the pod's leftover command. On the command's own timeout, with a grace and a recorded
     * pgid, its process group is SIGINTed and — after the grace — SIGKILLed; any failure of that
     * path (no file, a rejected value, an exec error) falls back to today's pod-wide
     * `kill -KILL -1`. A cancellation or cap trip keeps the immediate pod-wide kill: only the
     * command's own timeout means it ran long enough for a graceful stop to make sense. See ADR-0106.
     */
    fun killPod(
        interruptGrace: Duration?,
        pgidFile: String?,
        kill: KillCause,
    ) {
        if (interruptGrace != null && pgidFile != null && kill.byTimeout && interruptGroupInPod(pgidFile, interruptGrace)) return
        runCatching { killInsidePod() }
    }

    /**
     * The pod's `setsid` prefix, or null where it has none — then the command is launched unchanged
     * and no pgid is recorded. `-w` (util-linux) waits for the child, so a setsid that has to fork
     * cannot close the exec channel before the command ends; BusyBox's setsid takes no `-w` and
     * waits itself.
     */
    private fun probeSetsid(): List<String>? = SETSID_FORMS.firstOrNull { probeRuns(it) }

    private fun probeRuns(prefix: List<String>): Boolean {
        val probe = runCatching { execClient.start(namespace, podName, prefix + "true", stdin = false) }.getOrNull()
        if (probe == null) return false
        return try {
            probe.waitFor(PROBE_WAIT_MS, TimeUnit.MILLISECONDS) && probe.exitValue() == 0
        } catch (e: Exception) {
            logger.warn("Failed to probe setsid in pod {}", podName, e)
            false
        } finally {
            runCatching { probe.destroyForcibly() }
        }
    }

    /**
     * Runs one bounded exec that SIGINTs the process group recorded in [pgidFile], waits out
     * [grace], then SIGKILLs it. True only when a valid pgid was interrupted, so [killPod] falls
     * back to the pod-wide kill otherwise; a hung or failed exec is abandoned at its bound.
     */
    private fun interruptGroupInPod(
        pgidFile: String,
        grace: Duration,
    ): Boolean {
        val proc =
            runCatching {
                execClient.start(
                    namespace,
                    podName,
                    listOf("sh", "-c", interruptScript(grace), SIGNAL_SCRIPT_NAME, pgidFile),
                    stdin = false,
                )
            }.getOrNull() ?: return false
        return try {
            proc.waitFor(grace.inWholeMilliseconds + signalExecSlackMs, TimeUnit.MILLISECONDS) && proc.exitValue() == SIGNAL_OK
        } catch (e: Exception) {
            logger.warn("Failed to interrupt the command's process group in pod {}", podName, e)
            false
        } finally {
            runCatching { proc.destroyForcibly() }
        }
    }

    /**
     * The script the signalling exec runs: read the pgid file, refuse it unless it is a plain
     * integer greater than 1 ([PGID_GUARD] — the file is agent-writable, so its content is
     * attacker-controlled, §12 rule 2), SIGINT the group, wait out the grace, SIGKILL it. Any
     * refused value or failed kill exits non-zero, so [killPod] falls back to the pod-wide kill
     * rather than trusting a signal that may not have reached a group. See ADR-0106.
     */
    private fun interruptScript(grace: Duration): String =
        "p=\$(cat \"\$1\" 2>/dev/null); ${KubernetesShellExecutor.PGID_GUARD}; " +
            "kill -INT -\"\$p\" 2>/dev/null || exit ${KubernetesShellExecutor.SIGNAL_REJECTED}; sleep ${grace.inWholeSeconds}; " +
            "kill -KILL -\"\$p\" 2>/dev/null || exit ${KubernetesShellExecutor.SIGNAL_REJECTED}; exit $SIGNAL_OK"

    /** Kills every process in the pod's main container PID namespace. */
    fun killInsidePod() {
        // Via the sh builtin: the procps `kill` binary (debian-ish images)
        // misparses the negative target `-1`; the dash/ash builtins accept it.
        val killProc =
            execClient.start(
                namespace,
                podName,
                listOf("sh", "-c", "kill -KILL -1"),
                stdin = false,
            )
        runCatching { killProc.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS) }
        runCatching { killProc.destroyForcibly() }
    }

    companion object {
        private const val KILL_WAIT_MS = 2_000L
        private const val PROBE_WAIT_MS = 5_000L
        private const val SIGNAL_OK = 0
        private const val SIGNAL_SCRIPT_NAME = "homer-signal"
        private const val FALLBACK_PGID_DIR = ".homer"

        /** `setsid` forms to probe, most preferred first. */
        private val SETSID_FORMS = listOf(listOf("setsid", "-w"), listOf("setsid"))

        /** $0 is the pgid file, $@ the argv: record the group leader's pid, then exec the argv in place. */
        private const val PGID_SCRIPT =
            "p=\"\$0\"; d=\"\${p%/*}\"; mkdir -p \"\$d\" 2>/dev/null; echo \$\$ > \"\$p\" 2>/dev/null; exec \"\$@\""

        private val logger = LoggerFactory.getLogger(PodProcessControl::class.java)
    }
}
