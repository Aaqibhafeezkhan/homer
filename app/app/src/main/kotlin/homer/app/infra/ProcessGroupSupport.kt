package homer.app.infra

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText

/**
 * Best-effort POSIX process-group reaping for locally-launched commands.
 *
 * `ProcessHandle.descendants()` misses a child spawned in the snapshot→kill
 * window and any double-fork/`setsid`-detached child, so killing only the
 * direct process and its descendants can leak orphaned processes on kill or
 * timeout. Launching the command under `setsid` makes it the leader of a new
 * session/process group; killing the negative pgid then reaps the whole group,
 * including late-spawned and reparented (but not re-`setsid`'d) children.
 *
 * Degrades gracefully: where `setsid` is not on `PATH` (e.g. macOS dev) the
 * command is launched unchanged and callers fall back to the descendants() kill.
 * See ADR-0041.
 */
object ProcessGroupSupport {
    private const val KILL_GROUP_WAIT_SEC = 2L

    /** Absolute path to `setsid`, or null when it is unavailable on this host. */
    val setsidPath: String? by lazy { resolveOnPath("setsid") }

    /** Prepends `setsid` so [command] leads a new process group, when available. */
    fun launchArgv(command: List<String>): List<String> = setsidPath?.let { listOf(it) + command } ?: command

    /**
     * The process group id to reap for [process], or null when a group kill is
     * not safe/possible. Returns a pgid ONLY when the process leads its own group
     * (pgid == pid, read from `/proc`), which is exactly what a non-forking
     * `setsid` produces — so a group kill can never target the JVM's own group.
     *
     * Prefer KILL time to right after launch: the freshly-spawned child only
     * becomes a leader once it has run setsid(2), and a single immediate read
     * races that transition (loses reliably under CPU contention). A launch-time
     * read is safe only when retried until the transition is observed
     * (`GraphSubprocessOutcome.captureOwnProcessGroupId`), which is what lets the
     * pgid be known before the leader exits.
     */
    fun ownProcessGroupId(process: java.lang.Process): Long? = if (setsidPath == null) null else groupLeaderPgid(process, ::readPgid)

    /**
     * [process]'s pid when [pgidOf] reports it as its own group's leader and the pid still
     * names [process]; null otherwise. The decision of [ownProcessGroupId], `/proc` read injected.
     */
    internal fun groupLeaderPgid(
        process: java.lang.Process,
        pgidOf: (Long) -> Long?,
    ): Long? {
        val pid = process.pid()
        val pgid = pgidOf(pid) ?: return null
        // PID-reuse guard (TOCTOU): confirm AFTER the /proc read that the pid
        // still belongs to THIS process — ProcessHandle equality includes the
        // start time. If the child died and the kernel recycled the pid, the
        // stat we just read described a stranger; returning null falls back to
        // the descendants-only kill instead of group-killing an innocent.
        val isSameProcess =
            ProcessHandle
                .of(pid)
                .map { it == process.toHandle() }
                .orElse(false)
        if (!isSameProcess) return null
        return pid.takeIf { pgid == it }
    }

    /**
     * Sends SIGKILL to the whole process group [pgid] (best-effort). Must go
     * through the `sh` builtin `kill`: the standalone binaries misparse the
     * negative target — procps rejects `kill -KILL -<pgid>` outright and
     * busybox rejects the `kill -KILL -- -<pgid>` form — while the dash and
     * busybox-ash builtins both accept it. [pgid] is a Long, so the
     * interpolation cannot inject.
     */
    fun killGroup(pgid: Long) {
        runCatching {
            ProcessBuilder("/bin/sh", "-c", "kill -KILL -$pgid")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor(KILL_GROUP_WAIT_SEC, TimeUnit.SECONDS)
        }
    }

    private fun readPgid(pid: Long): Long? =
        runCatching {
            // /proc/<pid>/stat: "pid (comm) state ppid pgrp ...". comm can contain
            // spaces and ')', so parse the fields after the final ") ".
            val fields =
                Path
                    .of("/proc/$pid/stat")
                    .readText()
                    .substringAfterLast(") ")
                    .trim()
                    .split(" ")
            fields[2].toLong() // [0]=state [1]=ppid [2]=pgrp
        }.getOrNull()

    private fun resolveOnPath(name: String): String? = firstExecutableOnPath(name, System.getenv("PATH"))

    /**
     * First executable named [name] found on [pathValue], as an **absolute**
     * path. Relative `PATH` entries are ignored: a relative wrapper path would
     * be resolved against the per-run job workdir (which the command can write),
     * letting a planted binary hijack `setsid`/`kill`. Pure/testable — the
     * production callers pass `System.getenv("PATH")`.
     */
    internal fun firstExecutableOnPath(
        name: String,
        pathValue: String?,
    ): String? {
        if (pathValue == null) return null
        return pathValue
            .split(File.pathSeparatorChar)
            .filter { it.isNotEmpty() }
            .map { Path.of(it) }
            .filter { it.isAbsolute }
            .map { it.resolve(name) }
            .firstOrNull { Files.isExecutable(it) }
            ?.toString()
    }
}
