package homer.app.runners.external

import homer.app.EnvConstants
import homer.app.exceptions.OutputCapExceededException
import homer.app.exceptions.RunnerOutputSequenceException
import homer.app.infra.OutputLineFilter
import homer.app.infra.WorkspaceConfig
import homer.app.models.ExtraUploadSpec
import homer.app.models.RunnerConfig
import homer.app.runners.DownloadMode
import homer.app.runners.RunnerSessionParams
import homer.app.runners.queue.RunnerClaimQueue
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.context.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ExternalRunnerSessionTest {
    @TempDir
    lateinit var tmp: Path

    private var now = 1_000_000L
    private val queue = RunnerClaimQueue(maxPending = 10)

    private fun domain(config: RunnerConfig.External = RunnerConfig.External(token = "t", homerApiUrl = "https://homer.internal")) =
        ExternalRunnerDomain("workers", config, queue, clock = { now })

    private fun params(projectRoot: Path): RunnerSessionParams {
        val ws =
            mockk<WorkspaceConfig> {
                every { this@mockk.projectRoot } returns projectRoot
                every { maxOutputBytes } returns 64
            }
        return RunnerSessionParams(
            processId = 7,
            agentName = "gpu-job",
            workspace = ws,
            env =
                mapOf(
                    EnvConstants.WORK_DIR to "$projectRoot/workdir",
                    EnvConstants.ARTIFACTS_DIR to "$projectRoot/artifacts",
                    "PATH" to "/usr/bin",
                ),
            parentContext = Context.root(),
        )
    }

    private fun openSession(d: ExternalRunnerDomain = domain()): ExternalRunnerSession {
        val inst = d.register(RegisterInstanceRequest(name = "host-1", tags = listOf("gpu")))
        return d.openSession(inst, params(tmp.resolve("runs/7").createDirectories()))
    }

    @Test
    fun `createWorkspace maps the instance root onto the HMR env and the API URL`() =
        runTest {
            val s = openSession()
            val overrides = async { s.createWorkspace() }
            runCurrent()

            val op = s.mailbox.next(1.seconds) as RunnerOp.CreateWorkspace
            s.mailbox.complete(OpResult(op.opId, root = "/var/homer-runner/7/"))

            val env = overrides.await()
            assertEquals("/var/homer-runner/7/workdir", env[EnvConstants.WORK_DIR])
            assertEquals("/var/homer-runner/7/artifacts", env[EnvConstants.ARTIFACTS_DIR])
            assertEquals("/var/homer-runner/7/.homer/agent", env[EnvConstants.AGENT_DIR])
            assertEquals("https://homer.internal", env[EnvConstants.API_URL])
            assertEquals("/var/homer-runner/7/workdir", s.remoteWorkdir)
            assertEquals(mapOf("runner.instance" to "host-1"), s.metadata)
        }

    @Test
    fun `createWorkspace points each extraUploads env at its copy under the instance root`() =
        runTest {
            val extras = listOf(ExtraUploadSpec("/app/scripts", ".homer/scripts", "SCRIPTS_DIR"), ExtraUploadSpec("/app/x", ".homer/x"))
            val s = openSession(domain(RunnerConfig.External(token = "t", homerApiUrl = "https://homer.internal", extraUploads = extras)))
            assertEquals(extras, s.extraUploads)
            val overrides = async { s.createWorkspace() }
            runCurrent()

            val op = s.mailbox.next(1.seconds) as RunnerOp.CreateWorkspace
            s.mailbox.complete(OpResult(op.opId, root = "/var/homer-runner/7"))

            val env = overrides.await()
            assertEquals("/var/homer-runner/7/.homer/scripts", env["SCRIPTS_DIR"])
            assertFalse(env.values.any { it.endsWith("/.homer/x") }, "an entry without env adds no variable")
        }

    @Test
    fun `createWorkspace refuses a relative or missing root`() =
        runTest {
            val s = openSession()
            val result = async { runCatching { s.createWorkspace() } }
            runCurrent()
            val op = s.mailbox.next(1.seconds)!!
            s.mailbox.complete(OpResult(op.opId, root = "relative/root"))
            assertTrue(result.await().exceptionOrNull() is ExternalRunnerException)
        }

    @Test
    fun `an instance-supplied error is flattened and capped before it reaches the exception text`() =
        runTest {
            val s = openSession()
            val result = async { runCatching { s.cleanWorkspace() } }
            runCurrent()
            val op = s.mailbox.next(1.seconds)!!
            s.mailbox.complete(
                OpResult(op.opId, ok = false, error = "boom\nWARN forged log line\r" + "x".repeat(ExternalRunnerSession.MAX_ERROR_CHARS)),
            )

            val message = assertNotNull(result.await().exceptionOrNull()).message!!
            assertFalse('\n' in message || '\r' in message, "an instance cannot forge a log line: $message")
            assertTrue("boom WARN forged log line" in message, message)
            assertTrue(message.endsWith("..."), "the text is capped")
            assertTrue(message.length < ExternalRunnerSession.MAX_ERROR_CHARS + 200, "capped to the bound plus the prefix")
        }

    @Test
    fun `an instance-supplied cmd error is flattened and capped before it reaches the exception text`() =
        runTest {
            val s = openSession()
            val result =
                async {
                    runCatching {
                        s.shell.cmd(listOf("sh", "-c", "false"), tmp.resolve("o"), tmp.resolve("e"), timeout = 30.seconds)
                    }
                }
            runCurrent()
            val op = s.mailbox.next(1.seconds)!!
            s.mailbox.complete(
                OpResult(op.opId, ok = false, error = "boom\nWARN forged log line\r" + "x".repeat(ExternalRunnerSession.MAX_ERROR_CHARS)),
            )

            val message = assertNotNull(result.await().exceptionOrNull()).message!!
            assertFalse('\n' in message || '\r' in message, "an instance cannot forge a log line: $message")
            assertTrue("boom WARN forged log line" in message, message)
            assertTrue(message.endsWith("..."), "the text is capped")
            assertTrue(message.length < ExternalRunnerSession.MAX_ERROR_CHARS + 200, "capped to the bound plus the prefix")
        }

    @Test
    fun `cmd becomes an op, streamed chunks land in the files through the filter, exit code returns`() =
        runTest {
            val s = openSession()
            val out = tmp.resolve("cmd_0.out")
            val err = tmp.resolve("cmd_0.err")
            val filter = OutputLineFilter { line -> !line.startsWith("drop") }
            val result =
                async {
                    s.shell.cmd(
                        listOf("sh", "-c", "echo hi"),
                        out,
                        err,
                        stdIn = "in",
                        env = mapOf("A" to "1"),
                        timeout = 30.seconds,
                        stdOutFilter = filter,
                    )
                }
            runCurrent()

            val op = s.mailbox.next(1.seconds) as RunnerOp.Cmd
            assertEquals(listOf("sh", "-c", "echo hi"), op.argv)
            assertEquals(mapOf("A" to "1"), op.env)
            assertEquals("in", op.stdin)
            assertEquals(30_000, op.timeoutMs)
            val sink = assertNotNull(s.sink(op.opId))
            sink.append("stdout", 0, "keep 1\ndrop me\nke".toByteArray())
            sink.append("stdout", 1, "ep 2\n".toByteArray())
            sink.append("stderr", 0, "warn\n".toByteArray())
            s.mailbox.complete(OpResult(op.opId, exitCode = 3))

            assertEquals(3, result.await().exitCode)
            assertEquals("keep 1\nkeep 2\n", out.readText())
            assertEquals("warn\n", err.readText())
            assertNull(s.sink(op.opId), "the sink is released with the op")
        }

    @Test
    fun `an out-of-order chunk is refused and does not advance the sequence`() =
        runTest {
            val s = openSession()
            val result = async { runCatching { s.shell.cmd(listOf("x"), tmp.resolve("o"), tmp.resolve("e"), timeout = 30.seconds) } }
            runCurrent()
            val op = s.mailbox.next(1.seconds)!!
            val sink = s.sink(op.opId)!!
            assertThrows<RunnerOutputSequenceException> { sink.append("stdout", 1, "late".toByteArray()) }
            sink.append("stdout", 0, "first".toByteArray())
            assertThrows<RunnerOutputSequenceException> { sink.append("stdout", 0, "again".toByteArray()) }
            s.mailbox.complete(OpResult(op.opId, exitCode = 0))
            assertEquals(0, result.await().getOrThrow().exitCode)
        }

    @Test
    fun `a timed-out cmd fails like a TTL kill and appends the marker`() =
        runTest {
            val s = openSession()
            val err = tmp.resolve("e")
            val result = async { runCatching { s.shell.cmd(listOf("sleep"), tmp.resolve("o"), err, timeout = 2.seconds) } }
            runCurrent()
            val op = s.mailbox.next(1.seconds)!!
            s.mailbox.complete(OpResult(op.opId, exitCode = 137, timedOut = true))

            val failure = result.await().exceptionOrNull()
            assertTrue(failure is CancellationException && failure !is OutputCapExceededException, "$failure")
            assertTrue(err.readText().contains("[HOMER] Command killed due to TTL timeout"))
        }

    @Test
    fun `passing the output cap cancels the cmd and fails with OutputCapExceededException`() =
        runTest {
            val s = openSession()
            val result = async { runCatching { s.shell.cmd(listOf("yes"), tmp.resolve("o"), tmp.resolve("e"), timeout = 30.seconds) } }
            runCurrent()
            val op = s.mailbox.next(1.seconds)!!
            s.sink(op.opId)!!.append("stdout", 0, ByteArray(65))
            runCurrent()

            assertTrue(result.await().exceptionOrNull() is OutputCapExceededException)
            val cancel = s.mailbox.next(1.seconds)
            assertTrue(cancel is RunnerOp.Cancel && cancel.opId == op.opId, "$cancel")
        }

    @Test
    fun `a chunk that would cross the output cap never reaches the file`() =
        runTest {
            val s = openSession()
            val out = tmp.resolve("o")
            val result = async { runCatching { s.shell.cmd(listOf("yes"), out, tmp.resolve("e"), timeout = 30.seconds) } }
            runCurrent()
            val op = s.mailbox.next(1.seconds)!!
            val sink = s.sink(op.opId)!!
            sink.append("stdout", 0, ByteArray(60) { 'a'.code.toByte() })
            sink.append("stdout", 1, ByteArray(40) { 'b'.code.toByte() })
            runCurrent()

            assertTrue(result.await().exceptionOrNull() is OutputCapExceededException)
            assertTrue(sink.capTripped.isCompleted, "the crossing chunk trips the cap")
            assertEquals(60, sink.bytesWritten("stdout"))
            assertEquals("a".repeat(60), out.readText(), "no byte past the 64-byte cap is on disk")
        }

    @Test
    fun `a cap trip that races behind a clean result still fails with OutputCapExceededException`() =
        runTest {
            val s = openSession()
            val result = async { runCatching { s.shell.cmd(listOf("yes"), tmp.resolve("o"), tmp.resolve("e"), timeout = 30.seconds) } }
            runCurrent()
            val op = s.mailbox.next(1.seconds)!!
            // Both the cap trip and a clean result are posted before the coroutine is
            // rescheduled, so `select` sees both clauses ready; registration order alone
            // (deferred before capTripped) must not let a truncated result look clean.
            s.sink(op.opId)!!.append("stdout", 0, ByteArray(65))
            s.mailbox.complete(OpResult(op.opId, exitCode = 0))
            runCurrent()

            assertTrue(result.await().exceptionOrNull() is OutputCapExceededException)
            val cancel = s.mailbox.next(1.seconds)
            assertTrue(cancel is RunnerOp.Cancel && cancel.opId == op.opId, "$cancel")
        }

    @Test
    fun `an oversized chunk is refused and does not consume its sequence number`() =
        runTest {
            val s = openSession()
            val result = async { runCatching { s.shell.cmd(listOf("x"), tmp.resolve("o"), tmp.resolve("e"), timeout = 30.seconds) } }
            runCurrent()
            val op = s.mailbox.next(1.seconds)!!
            val sink = s.sink(op.opId)!!
            assertThrows<IllegalArgumentException> { sink.append("stdout", 0, ByteArray(RunnerProtocol.MAX_OUTPUT_CHUNK_BYTES + 1)) }
            sink.append("stdout", 0, "ok".toByteArray())
            s.mailbox.complete(OpResult(op.opId, exitCode = 0))
            assertEquals(0, result.await().getOrThrow().exitCode)
        }

    @Test
    fun `a cmd result whose counts match the chunks Homer holds returns the clean result`() =
        runTest {
            val s = openSession()
            val result =
                async {
                    runCatching {
                        s.shell.cmd(listOf("sh", "-c", "x"), tmp.resolve("o"), tmp.resolve("e"), timeout = 30.seconds)
                    }
                }
            runCurrent()
            val op = s.mailbox.next(1.seconds)!!
            val sink = s.sink(op.opId)!!
            sink.append("stdout", 0, "hi\n".toByteArray())
            sink.append("stdout", 1, "there\n".toByteArray())
            sink.append("stderr", 0, "warn\n".toByteArray())
            s.mailbox.complete(OpResult(op.opId, exitCode = 0, stdoutChunks = 2, stderrChunks = 1))

            assertEquals(0, result.await().getOrThrow().exitCode)
        }

    @Test
    fun `a count above the chunks Homer holds fails the step as output incomplete`() =
        runTest {
            val s = openSession()
            val result =
                async {
                    runCatching {
                        s.shell.cmd(listOf("sh", "-c", "x"), tmp.resolve("o"), tmp.resolve("e"), timeout = 30.seconds)
                    }
                }
            runCurrent()
            val op = s.mailbox.next(1.seconds)!!
            s.sink(op.opId)!!.append("stdout", 0, "hi\n".toByteArray())
            s.mailbox.complete(OpResult(op.opId, exitCode = 0, stdoutChunks = 2, stderrChunks = 0))
            runCurrent()
            assertFalse(result.isCompleted, "the step gives the last chunk one bounded wait to arrive")

            testScheduler.advanceTimeBy(ExternalShellExecutor.INCOMPLETE_OUTPUT_WAIT.inWholeMilliseconds + 1)
            val message = assertNotNull(result.await().exceptionOrNull()).message!!
            assertTrue("output incomplete on stdout" in message, message)
        }

    @Test
    fun `a chunk that arrives during the bounded wait completes the step`() =
        runTest {
            val s = openSession()
            val result =
                async {
                    runCatching {
                        s.shell.cmd(listOf("sh", "-c", "x"), tmp.resolve("o"), tmp.resolve("e"), timeout = 30.seconds)
                    }
                }
            runCurrent()
            val op = s.mailbox.next(1.seconds)!!
            val sink = s.sink(op.opId)!!
            sink.append("stdout", 0, "hi\n".toByteArray())
            s.mailbox.complete(OpResult(op.opId, exitCode = 0, stdoutChunks = 2, stderrChunks = 0))
            runCurrent()

            sink.append("stdout", 1, "there\n".toByteArray())
            testScheduler.advanceTimeBy(ExternalShellExecutor.INCOMPLETE_OUTPUT_WAIT.inWholeMilliseconds + 1)

            assertEquals(0, result.await().getOrThrow().exitCode)
        }

    @Test
    fun `a count that cannot be real fails at once and cannot extend the wait`() =
        runTest {
            val s = openSession(domain())
            val result =
                async {
                    runCatching {
                        s.shell.cmd(listOf("sh", "-c", "x"), tmp.resolve("o"), tmp.resolve("e"), timeout = 30.seconds)
                    }
                }
            runCurrent()
            val op = s.mailbox.next(1.seconds)!!
            s.mailbox.complete(OpResult(op.opId, exitCode = 0, stdoutChunks = -1))
            runCurrent()

            assertTrue(result.isCompleted, "a negative count is refused without waiting")
            val message = assertNotNull(result.await().exceptionOrNull()).message!!
            assertTrue("output incomplete on stdout" in message, message)
        }

    @Test
    fun `a count past the output cap is a claim to wait for, not an impossible one`() =
        runTest {
            // A chunk may be empty: the sink advances the per-stream sequence for a zero-byte
            // chunk, so a stream can legitimately report more chunks than its cap has bytes.
            val s = openSession(domain())
            val result =
                async {
                    runCatching {
                        s.shell.cmd(listOf("sh", "-c", "x"), tmp.resolve("o"), tmp.resolve("e"), timeout = 30.seconds)
                    }
                }
            runCurrent()
            val op = s.mailbox.next(1.seconds)!!
            s.mailbox.complete(OpResult(op.opId, exitCode = 0, stdoutChunks = 65))
            runCurrent()
            assertFalse(result.isCompleted, "a positive count is never refused without the wait")

            testScheduler.advanceTimeBy(ExternalShellExecutor.INCOMPLETE_OUTPUT_WAIT.inWholeMilliseconds + 1)
            val message = assertNotNull(result.await().exceptionOrNull()).message!!
            assertTrue("output incomplete on stdout" in message, message)
            assertTrue("65 chunk(s)" in message, message)
        }

    @Test
    fun `a cap-tripping chunk that lands during the bounded wait fails with the cap error`() =
        runTest {
            val s = openSession(domain())
            val result =
                async {
                    runCatching {
                        s.shell.cmd(listOf("sh", "-c", "x"), tmp.resolve("o"), tmp.resolve("e"), timeout = 30.seconds)
                    }
                }
            runCurrent()
            val op = s.mailbox.next(1.seconds)!!
            val sink = s.sink(op.opId)!!
            sink.append("stdout", 0, ByteArray(10))
            // Reported ahead of what Homer holds, so the step enters the bounded wait; the
            // chunk that then lands crosses the 64-byte cap, consumes its seq and drops its
            // bytes — closing the very gap the wait was for.
            s.mailbox.complete(OpResult(op.opId, exitCode = 0, stdoutChunks = 2, stderrChunks = 0))
            runCurrent()
            sink.append("stdout", 1, ByteArray(60))
            assertTrue(sink.capTripped.isCompleted, "the landing chunk trips the cap")
            testScheduler.advanceTimeBy(ExternalShellExecutor.INCOMPLETE_OUTPUT_WAIT.inWholeMilliseconds + 1)

            val failure = result.await().exceptionOrNull()
            assertTrue(failure is OutputCapExceededException, "$failure")
        }

    @Test
    fun `two short streams are named by one output-incomplete error each`() =
        runTest {
            val s = openSession(domain())
            val result =
                async {
                    runCatching {
                        s.shell.cmd(listOf("sh", "-c", "x"), tmp.resolve("o"), tmp.resolve("e"), timeout = 30.seconds)
                    }
                }
            runCurrent()
            val op = s.mailbox.next(1.seconds)!!
            s.mailbox.complete(OpResult(op.opId, exitCode = 0, stdoutChunks = 2, stderrChunks = 1))
            runCurrent()
            testScheduler.advanceTimeBy(ExternalShellExecutor.INCOMPLETE_OUTPUT_WAIT.inWholeMilliseconds + 1)

            val message = assertNotNull(result.await().exceptionOrNull()).message!!
            assertTrue("output incomplete on stdout: the instance reported 2 chunk(s)" in message, message)
            assertTrue("; output incomplete on stderr: the instance reported 1 chunk(s)" in message, message)
        }

    @Test
    fun `a result that carries no counts keeps today's behavior`() =
        runTest {
            // An older runner binary reports nothing to compare, so the check stays inert.
            val s = openSession()
            val result =
                async {
                    runCatching {
                        s.shell.cmd(listOf("sh", "-c", "x"), tmp.resolve("o"), tmp.resolve("e"), timeout = 30.seconds)
                    }
                }
            runCurrent()
            val op = s.mailbox.next(1.seconds)!!
            s.mailbox.complete(OpResult(op.opId, exitCode = 0))
            runCurrent()

            assertEquals(0, result.await().getOrThrow().exitCode)
        }

    @Test
    fun `the output-incomplete error never carries output bytes`() =
        runTest {
            val s = openSession()
            val result =
                async {
                    runCatching {
                        s.shell.cmd(listOf("sh", "-c", "x"), tmp.resolve("o"), tmp.resolve("e"), timeout = 30.seconds)
                    }
                }
            runCurrent()
            val op = s.mailbox.next(1.seconds)!!
            s.sink(op.opId)!!.append("stdout", 0, "SECRET-VALUE\n".toByteArray())
            s.mailbox.complete(OpResult(op.opId, exitCode = 0, stdoutChunks = 3, stderrChunks = 0))
            runCurrent()
            testScheduler.advanceTimeBy(ExternalShellExecutor.INCOMPLETE_OUTPUT_WAIT.inWholeMilliseconds + 1)

            val message = assertNotNull(result.await().exceptionOrNull()).message!!
            assertTrue("output incomplete on stdout" in message, message)
            assertFalse("SECRET-VALUE" in message, "the error names ids and counts, never output: $message")
        }

    @Test
    fun `losing the instance fails the pending op and the run`() =
        runTest {
            val d = domain(RunnerConfig.External(token = "t", heartbeatTimeoutSec = 60))
            val s = openSession(d)
            val result = async { runCatching { s.shell.cmd(listOf("x"), tmp.resolve("o"), tmp.resolve("e"), timeout = 300.seconds) } }
            runCurrent()
            s.mailbox.next(1.seconds)

            now += 61_000
            val expired = d.sweepExpired()

            assertEquals(listOf(s.instance), expired)
            assertTrue(result.await().exceptionOrNull() is ExternalRunnerException)
            assertNull(d.instance(s.instance.id))
        }

    @Test
    fun `the sweep forgets a session it failed instead of leaving it resolvable`() =
        runTest {
            val d = domain(RunnerConfig.External(token = "t", heartbeatTimeoutSec = 60))
            val s = openSession(d)
            val result = async { runCatching { s.shell.cmd(listOf("x"), tmp.resolve("o"), tmp.resolve("e"), timeout = 300.seconds) } }
            runCurrent()
            s.mailbox.next(1.seconds)

            now += 61_000
            d.sweepExpired()

            assertTrue(result.await().exceptionOrNull() is ExternalRunnerException)
            assertNull(d.session(s.id), "a failed session is forgotten by the sweep that failed it")
            assertEquals(0, s.instance.inUse, "failing the session frees the instance's session share")
            assertNull(s.mailbox.next(1.seconds), "no Close op is posted to an instance that is gone")

            s.close()
            assertEquals(0, s.instance.inUse, "a later close must not fire onClosed a second time")
            assertNull(d.session(s.id))
        }

    @Test
    fun `a cmd result that never arrives fails at timeout plus heartbeat`() =
        runTest {
            val s = openSession(domain(RunnerConfig.External(token = "t", heartbeatTimeoutSec = 10)))
            val result = async { runCatching { s.shell.cmd(listOf("x"), tmp.resolve("o"), tmp.resolve("e"), timeout = 5.seconds) } }
            runCurrent()
            s.mailbox.next(1.seconds)

            testScheduler.advanceTimeBy(14_999)
            runCurrent()
            assertFalse(result.isCompleted)
            testScheduler.advanceTimeBy(2)
            runCurrent()
            assertTrue(result.await().exceptionOrNull() is ExternalRunnerException)
        }

    @Test
    fun `download MIRROR prunes only after an ok result with a posted tar`() =
        runTest {
            val s = openSession()
            val local = tmp.resolve("workdir").createDirectories()
            local.resolve("extra").writeText("x")
            val result = async { s.download("workdir", local, DownloadMode.MIRROR) }
            runCurrent()
            val op = s.mailbox.next(1.seconds) as RunnerOp.Download
            assertEquals("workdir", op.remoteRelative)
            assertEquals(DownloadMode.MIRROR, op.mode)
            val target = assertNotNull(s.downloadTarget(op.opId))
            target.materialized = setOf(Path.of("kept"))
            s.mailbox.complete(OpResult(op.opId, ok = true))
            result.await()
            assertFalse(local.resolve("extra").exists(), "MIRROR prunes extras after the ok result")
            assertNull(s.downloadTarget(op.opId))
        }

    @Test
    fun `download of a missing remote path leaves the local target untouched`() =
        runTest {
            val s = openSession()
            val local = tmp.resolve("artifacts").createDirectories()
            local.resolve("extra").writeText("x")
            val result = async { s.download("artifacts", local, DownloadMode.MIRROR) }
            runCurrent()
            val op = s.mailbox.next(1.seconds)!!
            s.mailbox.complete(OpResult(op.opId, ok = true, missing = true))
            result.await()
            assertTrue(local.resolve("extra").exists())
        }

    @Test
    fun `upload exposes its source for the tar route until the result arrives`() =
        runTest {
            val s = openSession()
            val src = tmp.resolve("src").createDirectories()
            val result = async { s.upload(src, ".") }
            runCurrent()
            val op = s.mailbox.next(1.seconds) as RunnerOp.Upload
            assertEquals(".", op.remoteRelative)
            assertEquals(src, s.uploadSource(op.opId)!!.local)
            assertEquals(".", s.uploadSource(op.opId)!!.remoteRelative)
            assertTrue(op.isDir)
            s.mailbox.complete(OpResult(op.opId))
            result.await()
            assertNull(s.uploadSource(op.opId))
        }

    @Test
    fun `close posts a Close op, frees the instance permit and stays addressable until swept`() =
        runTest {
            val d = domain(RunnerConfig.External(token = "t", heartbeatTimeoutSec = 60))
            val s = openSession(d)
            assertEquals(1, s.instance.inUse)

            s.close()
            s.close()

            assertEquals(0, s.instance.inUse)
            val op = s.mailbox.next(1.seconds)
            assertTrue(op is RunnerOp.Close, "$op")
            assertNull(s.mailbox.next(1.seconds), "close is the last op")
            assertEquals(s, d.session(s.id))
            now += 61_000
            d.touch(s.instance)
            d.sweepExpired()
            assertNull(d.session(s.id))
            assertNotNull(d.instance(s.instance.id), "a live instance is not dropped by a closed session's expiry")
        }
}
