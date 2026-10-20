package homer.app.infra

import homer.app.models.CommandResult
import homer.app.models.When
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WhenEvaluatorTest {
    private val artifacts: Path = Files.createTempDirectory("when-eval-test")

    private fun result(
        exit: Int,
        out: Path,
        err: Path,
    ) = CommandResult(exitCode = exit, stdOut = out, stdErr = err)

    @Test
    fun `null clause returns Run without invoking shell`() =
        runTest {
            val shell = mockk<ShellExecutor>()
            val evaluator = WhenEvaluator(shell, artifacts)
            val r = evaluator.evaluate(null, emptyMap(), 0)
            assertEquals(WhenEvaluator.Result.Run, r)
            coVerify(exactly = 0) { shell.cmd(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `all short-circuits on first false`() =
        runTest {
            val shell = mockk<ShellExecutor>()
            // 3 expressions, second exits 1 → Skip after second; third never invoked.
            val exitCodes = listOf(0, 1, 0)
            var calls = 0
            coEvery {
                shell.cmd(any(), any(), any(), any(), any(), any())
            } answers {
                val out = arg<Path>(1)
                val err = arg<Path>(2)
                result(exitCodes[calls++], out, err)
            }

            val evaluator = WhenEvaluator(shell, artifacts)
            val r =
                evaluator.evaluate(
                    When(When.Operator.ALL, listOf("e1", "e2", "e3")),
                    emptyMap(),
                    0,
                )

            assertTrue(r is WhenEvaluator.Result.Skip)
            assertEquals(2, calls)
            coVerify(exactly = 2) { shell.cmd(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `any short-circuits on first true`() =
        runTest {
            val shell = mockk<ShellExecutor>()
            val exitCodes = listOf(1, 0, 1)
            var calls = 0
            coEvery {
                shell.cmd(any(), any(), any(), any(), any(), any())
            } answers {
                val out = arg<Path>(1)
                val err = arg<Path>(2)
                result(exitCodes[calls++], out, err)
            }

            val evaluator = WhenEvaluator(shell, artifacts)
            val r =
                evaluator.evaluate(
                    When(When.Operator.ANY, listOf("e1", "e2", "e3")),
                    emptyMap(),
                    0,
                )

            assertEquals(WhenEvaluator.Result.Run, r)
            assertEquals(2, calls)
        }

    @Test
    fun `all returns Run when every expression true`() =
        runTest {
            val shell = mockk<ShellExecutor>()
            coEvery {
                shell.cmd(any(), any(), any(), any(), any(), any())
            } answers {
                result(0, arg(1), arg(2))
            }

            val evaluator = WhenEvaluator(shell, artifacts)
            val r =
                evaluator.evaluate(
                    When(When.Operator.ALL, listOf("e1", "e2")),
                    emptyMap(),
                    1,
                )
            assertEquals(WhenEvaluator.Result.Run, r)
        }

    @Test
    fun `any returns Skip when no expression true`() =
        runTest {
            val shell = mockk<ShellExecutor>()
            coEvery {
                shell.cmd(any(), any(), any(), any(), any(), any())
            } answers {
                result(1, arg(1), arg(2))
            }

            val evaluator = WhenEvaluator(shell, artifacts)
            val r =
                evaluator.evaluate(
                    When(When.Operator.ANY, listOf("e1", "e2")),
                    emptyMap(),
                    1,
                )
            assertTrue(r is WhenEvaluator.Result.Skip)
        }

    @Test
    fun `eval error (exit 2) returns EvalError not Skip`() =
        runTest {
            val shell = mockk<ShellExecutor>()
            coEvery {
                shell.cmd(any(), any(), any(), any(), any(), any())
            } answers {
                val err = arg<Path>(2)
                err.writeText("bash: e1: syntax error")
                result(2, arg(1), err)
            }

            val evaluator = WhenEvaluator(shell, artifacts)
            val r =
                evaluator.evaluate(
                    When(When.Operator.ALL, listOf("e1", "e2")),
                    emptyMap(),
                    0,
                )

            assertTrue(r is WhenEvaluator.Result.EvalError)
            assertEquals("e1", r.expression)
            assertEquals(2, r.exitCode)
            assertTrue(r.stderrSummary.contains("syntax error"))
        }

    @Test
    fun `shell timeout returns EvalError when the coroutine is still active`() =
        runTest {
            val shell = mockk<ShellExecutor>()
            coEvery { shell.cmd(any(), any(), any(), any(), any(), any()) } throws
                CancellationException("Timeout reached. 5s.")

            val evaluator = WhenEvaluator(shell, artifacts)
            val r =
                evaluator.evaluate(
                    When(When.Operator.ALL, listOf("e1")),
                    emptyMap(),
                    0,
                )

            assertTrue(r is WhenEvaluator.Result.EvalError, "expected EvalError, got: $r")
            assertEquals("e1", r.expression)
            assertEquals(-1, r.exitCode)
            assertTrue(
                r.stderrSummary.contains("timed out"),
                "expected timeout summary, got: ${r.stderrSummary}",
            )
        }

    @Test
    fun `shell timeout short-circuits later expressions`() =
        runTest {
            val shell = mockk<ShellExecutor>()
            coEvery { shell.cmd(any(), any(), any(), any(), any(), any()) } throws
                CancellationException("Timeout reached. 5s.")

            val evaluator = WhenEvaluator(shell, artifacts)
            val r =
                evaluator.evaluate(
                    When(When.Operator.ALL, listOf("e1", "e2", "e3")),
                    emptyMap(),
                    0,
                )

            assertTrue(r is WhenEvaluator.Result.EvalError)
            coVerify(exactly = 1) { shell.cmd(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a genuine caller cancellation propagates instead of becoming EvalError`() =
        runTest {
            val shell = mockk<ShellExecutor>()
            lateinit var job: Job
            var caught: Throwable? = null
            coEvery { shell.cmd(any(), any(), any(), any(), any(), any()) } coAnswers {
                // The run is being killed: the caller's coroutine is no longer active.
                job.cancel()
                throw CancellationException("Command cancelled while waiting for completion")
            }

            val evaluator = WhenEvaluator(shell, artifacts)
            job =
                launch {
                    try {
                        evaluator.evaluate(When(When.Operator.ALL, listOf("e1")), emptyMap(), 0)
                    } catch (e: Throwable) {
                        caught = e
                    }
                }
            job.join()

            assertTrue(
                caught is CancellationException,
                "expected the CancellationException to propagate, got: $caught",
            )
        }

    @Test
    fun `env is passed through unchanged`() =
        runTest {
            val shell = mockk<ShellExecutor>()
            val capturedEnv = slot<Map<String, String>>()
            coEvery {
                shell.cmd(any(), any(), any(), any(), env = capture(capturedEnv), timeout = any())
            } answers {
                result(0, arg(1), arg(2))
            }

            val env = mapOf("FOO" to "bar", "BAZ" to "qux")
            val evaluator = WhenEvaluator(shell, artifacts)
            evaluator.evaluate(When(When.Operator.ALL, listOf("e")), env, 0)

            assertEquals(env, capturedEnv.captured)
        }

    @Test
    fun `artifacts paths follow when_idx_i naming`() =
        runTest {
            val shell = mockk<ShellExecutor>()
            val capturedOut = mutableListOf<Path>()
            val capturedErr = mutableListOf<Path>()
            coEvery {
                shell.cmd(any(), capture(capturedOut), capture(capturedErr), any(), any(), any())
            } answers {
                result(0, arg(1), arg(2))
            }

            val evaluator = WhenEvaluator(shell, artifacts)
            evaluator.evaluate(When(When.Operator.ALL, listOf("a", "b")), emptyMap(), 3)

            assertEquals(artifacts.resolve("when_3_0.out"), capturedOut[0])
            assertEquals(artifacts.resolve("when_3_0.err"), capturedErr[0])
            assertEquals(artifacts.resolve("when_3_1.out"), capturedOut[1])
            assertEquals(artifacts.resolve("when_3_1.err"), capturedErr[1])
        }
}
