package homer.app.infra

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LocalShellExecutorEnvIsolationTest {
    @Test
    fun `child sees only the supplied env - nothing from parent JVM`() =
        runTest {
            // ProcessBuilder.environment() is pre-populated from System.getenv()
            // by the JDK. LocalShellExecutor must clear it before applying the
            // supplied env so children see ONLY what WorkspaceConfigFactory
            // composed (allowlisted host env + globalEnv + agent.env +
            // queryParams + HMR_*).
            val workDir = Files.createTempDirectory("homer-shell-env-iso")
            val stdOut = workDir.resolve("out.log")
            val stdErr = workDir.resolve("err.log")

            val executor = LocalShellExecutor(workDir)
            // Absolute path to /usr/bin/env so the child does NOT need PATH
            // for binary lookup. That proves the isolation: even with an
            // empty PATH the command can still run.
            executor.cmd(
                command = listOf("/usr/bin/env"),
                stdOut = stdOut,
                stdErr = stdErr,
                stdIn = null,
                env =
                    mapOf(
                        "HMR_PROCESS_ID" to "1",
                        "ANTHROPIC_AUTH_TOKEN" to "sk-ant-from-operator-config",
                        "SAFE_VAR" to "ok",
                    ),
                timeout = 5.seconds,
            )

            val printed = stdOut.readText()
            val keys =
                printed
                    .lines()
                    .filter { it.isNotBlank() && it.contains("=") }
                    .map { it.substringBefore("=") }
                    .toSet()

            assertEquals(
                setOf("HMR_PROCESS_ID", "ANTHROPIC_AUTH_TOKEN", "SAFE_VAR"),
                keys,
                "Child env must contain ONLY the supplied keys; got: $keys",
            )
            assertTrue("HMR_PROCESS_ID=1" in printed)
            assertTrue("ANTHROPIC_AUTH_TOKEN=sk-ant-from-operator-config" in printed)
            assertTrue("SAFE_VAR=ok" in printed)
            // Negatives — these are almost always set on a dev machine or
            // in CI but must NOT bypass the WorkspaceConfigFactory allowlist
            // via JVM inheritance.
            assertFalse(keys.contains("PATH"), "PATH must not be inherited via ProcessBuilder")
            assertFalse(keys.contains("HOME"), "HOME must not be inherited via ProcessBuilder")
            assertFalse(keys.contains("USER"), "USER must not be inherited via ProcessBuilder")
            assertFalse(keys.contains("JAVA_HOME"), "JAVA_HOME must not be inherited via ProcessBuilder")
        }
}
