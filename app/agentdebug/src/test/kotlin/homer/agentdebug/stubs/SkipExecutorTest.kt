package homer.agentdebug.stubs

import homer.app.executors.ExecutionContext
import homer.app.models.Command
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class SkipExecutorTest {
    @Test
    fun `a skipped command empties its stdout and stderr and succeeds`(
        @TempDir dir: Path,
    ) = runTest {
        val out = dir.resolve("out").also { it.writeText("stale") }
        val err = dir.resolve("err").also { it.writeText("stale") }
        val context =
            mockk<ExecutionContext> {
                every { stdOut } returns out
                every { stdErr } returns err
            }

        val result = SkipRunAgentExecutor().execute(Command.RunAgent(agentName = "child"), context, Span.getInvalid())

        assertEquals(0, result.exitCode)
        assertEquals(out to err, result.stdOut to result.stdErr)
        assertEquals("" to "", out.readText() to err.readText())
    }
}
