package homer.app.runners.ssh

/**
 * Assembles the single shell command string sent over an SSH exec channel.
 * SSH exec always runs through the remote login shell, so argv semantics are
 * preserved by POSIX single-quoting every token: `cd <workdir> && exec env
 * <K=V...> <argv...>`. `exec` replaces the shell so signals/exit codes come
 * straight from the command. The `env` prefix sets the environment (many sshd
 * configs reject SendEnv/AcceptEnv), mirroring the kubernetes runner.
 */
object RemoteCommandBuilder {
    fun build(
        remoteWorkdir: String,
        env: Map<String, String>,
        argv: List<String>,
    ): String {
        val parts = mutableListOf("cd", quote(remoteWorkdir))
        val head = mutableListOf("&&", "exec")
        if (env.isNotEmpty()) {
            head.add("env")
            env.forEach { (k, v) -> head.add(quote("$k=$v")) }
        }
        argv.forEach { head.add(quote(it)) }
        return (parts + head).joinToString(" ")
    }

    /**
     * Reaping variant of [build]: makes the command lead its own process group
     * and records that group's id to [pgidFile], so a later control channel can
     * `kill -KILL -<pgid>` the whole group (a backgrounded remote child that
     * outlives `channel.close(true)` would otherwise orphan on the target host).
     *
     * `setsid -w` runs the command as a new session/group leader and waits for
     * it, so exit code and signal propagate unchanged. The inner `sh` writes its
     * own pid — which, being the new session leader, equals the group id — then
     * `exec`s the real argv in place, so the command keeps that pid/pgid and its
     * stdin/stdout/stderr and argv semantics are identical to [build]. The inner
     * script self-provisions [pgidFile]'s parent dir so it is robust against the
     * workspace not yet existing. Requires `setsid -w` (util-linux) on the
     * target — gated by [SshRunner]'s capability probe; see ADR-0041.
     */
    fun buildReaping(
        remoteWorkdir: String,
        env: Map<String, String>,
        argv: List<String>,
        pgidFile: String,
    ): String {
        val parts = mutableListOf("cd", quote(remoteWorkdir), "&&", "exec")
        if (env.isNotEmpty()) {
            parts.add("env")
            env.forEach { (k, v) -> parts.add(quote("$k=$v")) }
        }
        parts.add("setsid")
        parts.add("-w")
        parts.add("sh")
        parts.add("-c")
        // $0 is the pgid file; $@ is the real argv. `exec "$@"` keeps the pid/pgid.
        parts.add(quote("d=\$(dirname \"\$0\"); mkdir -p \"\$d\"; echo \$\$ > \"\$0\"; exec \"\$@\""))
        parts.add(quote(pgidFile))
        argv.forEach { parts.add(quote(it)) }
        return parts.joinToString(" ")
    }

    /**
     * Builds the control-channel command that reaps the process group recorded
     * in [pgidFile] (written by [buildReaping]). The value is validated to be a
     * plain positive integer greater than 1 before `kill -KILL -<pgid>` runs, so
     * a stale, corrupt, or forged side-file can never turn into `kill -KILL -1`
     * (SIGKILL to every process the remote user owns) or `-0`. Runs via the
     * remote login shell; an empty, non-numeric, or `<= 1` value is a no-op.
     */
    fun buildReapKill(pgidFile: String): String {
        val q = quote(pgidFile)
        return "p=\$(cat $q 2>/dev/null); case \"\$p\" in ''|*[!0-9]*) exit 0;; esac; " +
            "[ \"\$p\" -gt 1 ] && kill -KILL -\"\$p\" 2>/dev/null"
    }

    /** POSIX single-quote escaping: wrap in '...', and render embedded ' as '\''. */
    fun quote(token: String): String = "'" + token.replace("'", "'\\''") + "'"
}
