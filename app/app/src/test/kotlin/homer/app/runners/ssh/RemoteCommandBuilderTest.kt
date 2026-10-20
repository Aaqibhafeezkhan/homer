package homer.app.runners.ssh

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class RemoteCommandBuilderTest {
    @Test
    fun `single-quotes a plain token`() {
        assertEquals("'echo'", RemoteCommandBuilder.quote("echo"))
    }

    @Test
    fun `escapes embedded single quotes`() {
        // it's -> 'it'\''s'
        assertEquals("'it'\\''s'", RemoteCommandBuilder.quote("it's"))
    }

    @Test
    fun `builds cd + exec env + escaped argv`() {
        val cmd =
            RemoteCommandBuilder.build(
                remoteWorkdir = "/home/homer/ws/p1/workdir",
                env = linkedMapOf("HMR_WORK_DIR" to "/home/homer/ws/p1/workdir"),
                argv = listOf("echo", "hello world"),
            )
        assertEquals(
            "cd '/home/homer/ws/p1/workdir' && exec env 'HMR_WORK_DIR=/home/homer/ws/p1/workdir' 'echo' 'hello world'",
            cmd,
        )
    }

    @Test
    fun `omits env prefix when env is empty`() {
        val cmd = RemoteCommandBuilder.build("/w", emptyMap(), listOf("ls", "-la"))
        assertEquals("cd '/w' && exec 'ls' '-la'", cmd)
    }

    @Test
    fun `preserves argv semantics for tokens with spaces and globs`() {
        val cmd = RemoteCommandBuilder.build("/w", emptyMap(), listOf("grep", "a b", "*.kt"))
        assertEquals("cd '/w' && exec 'grep' 'a b' '*.kt'", cmd)
    }

    @Test
    fun `buildReaping wraps argv in setsid and records the pgid side-file`() {
        val cmd =
            RemoteCommandBuilder.buildReaping(
                remoteWorkdir = "/w",
                env = linkedMapOf("FOO" to "bar"),
                argv = listOf("echo", "hi"),
                pgidFile = "/w/.homer/reap/cmd-1.pgid",
            )
        assertEquals(
            "cd '/w' && exec env 'FOO=bar' setsid -w sh -c " +
                "'d=\$(dirname \"\$0\"); mkdir -p \"\$d\"; echo \$\$ > \"\$0\"; exec \"\$@\"' " +
                "'/w/.homer/reap/cmd-1.pgid' 'echo' 'hi'",
            cmd,
        )
    }

    @Test
    fun `buildReaping omits the env prefix when env is empty`() {
        val cmd = RemoteCommandBuilder.buildReaping("/w", emptyMap(), listOf("ls"), "/w/.homer/reap/cmd-1.pgid")
        assertEquals(
            "cd '/w' && exec setsid -w sh -c " +
                "'d=\$(dirname \"\$0\"); mkdir -p \"\$d\"; echo \$\$ > \"\$0\"; exec \"\$@\"' " +
                "'/w/.homer/reap/cmd-1.pgid' 'ls'",
            cmd,
        )
    }

    @Test
    fun `buildReapKill numerically guards the pgid before kill`() {
        assertEquals(
            "p=\$(cat '/w/.homer/reap/cmd-1.pgid' 2>/dev/null); case \"\$p\" in ''|*[!0-9]*) exit 0;; esac; " +
                "[ \"\$p\" -gt 1 ] && kill -KILL -\"\$p\" 2>/dev/null",
            RemoteCommandBuilder.buildReapKill("/w/.homer/reap/cmd-1.pgid"),
        )
    }

    /**
     * Runs the real buildReapKill command with `kill` shadowed by a shell
     * function so nothing is actually signalled, and asserts the numeric guard
     * lets only a plain integer > 1 through. Proves a forged `1`/`0`, a
     * non-numeric value, or an injection attempt is a no-op.
     */
    @Test
    fun `buildReapKill only kills a plain pgid greater than one`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))

        fun decision(pgidContent: String): String {
            val f = tmp.resolve("pgid").also { it.writeText(pgidContent) }
            val cmd = "kill() { echo \"WOULDKILL \$*\"; }; " + RemoteCommandBuilder.buildReapKill(f.toString())
            val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            return p.inputStream
                .readBytes()
                .decodeToString()
                .trim()
        }
        assertEquals("WOULDKILL -KILL -12345", decision("12345"))
        assertEquals("", decision("1"))
        assertEquals("", decision("0"))
        assertEquals("", decision("abc"))
        assertEquals("", decision(""))
        assertEquals("", decision("1; touch /tmp/pwned")) // non-numeric -> guarded out
    }
}
