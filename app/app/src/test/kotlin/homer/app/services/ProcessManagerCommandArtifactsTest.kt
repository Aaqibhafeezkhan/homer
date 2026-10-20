package homer.app.services

import homer.app.executors.CommandArtifactPaths
import homer.app.fixtures.PersistenceTestSupport
import homer.app.models.ProcessStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * In-memory command-artifact-path tracking: [ProcessManager] tracks the
 * actual per-command stdout/stderr paths a running executor wrote to, so
 * [LogTailService] can follow a per-attempt `artifactNameTemplate` instead of assuming
 * today's `cmd_N.out`/`cmd_N.err` naming. Deliberately not part of the serializable
 * `ExecutionInfo`/`ProcessInfo` API — see `ProcessManager.commandArtifacts`.
 */
class ProcessManagerCommandArtifactsTest {
    @Test
    fun `getCommandArtifacts returns null for an unknown process`() =
        runTest {
            val pm = PersistenceTestSupport.processManager()
            assertNull(pm.getCommandArtifacts(processId = 999, cmdIndex = 0))
        }

    @Test
    fun `getCommandArtifacts returns null for an unknown command index on a known process`() =
        runTest {
            val pm = PersistenceTestSupport.processManager()
            val id = pm.obtainProcessId("agent-a")
            pm.setCommandArtifacts(
                id,
                0,
                CommandArtifactPaths(Path.of("/artifacts/cmd_0.out"), Path.of("/artifacts/cmd_0.err")),
            )

            assertNull(pm.getCommandArtifacts(id, cmdIndex = 1))
        }

    @Test
    fun `setCommandArtifacts then getCommandArtifacts returns the tracked paths`() =
        runTest {
            val pm = PersistenceTestSupport.processManager()
            val id = pm.obtainProcessId("agent-a")
            val stdOut = Path.of("/artifacts/cmd_0_attempt_0.out")
            val stdErr = Path.of("/artifacts/cmd_0_attempt_0.err")

            pm.setCommandArtifacts(id, 0, CommandArtifactPaths(stdOut, stdErr))

            val tracked = pm.getCommandArtifacts(id, 0)
            assertEquals(stdOut, tracked?.stdOut)
            assertEquals(stdErr, tracked?.stdErr)
        }

    @Test
    fun `setCommandArtifacts overwrites a previous entry for the same process and command index`() =
        runTest {
            val pm = PersistenceTestSupport.processManager()
            val id = pm.obtainProcessId("agent-a")
            pm.setCommandArtifacts(
                id,
                0,
                CommandArtifactPaths(Path.of("/artifacts/cmd_0_attempt_0.out"), Path.of("/artifacts/cmd_0_attempt_0.err")),
            )

            pm.setCommandArtifacts(
                id,
                0,
                CommandArtifactPaths(Path.of("/artifacts/cmd_0_attempt_1.out"), Path.of("/artifacts/cmd_0_attempt_1.err")),
            )

            val tracked = pm.getCommandArtifacts(id, 0)
            assertEquals(Path.of("/artifacts/cmd_0_attempt_1.out"), tracked?.stdOut)
            assertEquals(Path.of("/artifacts/cmd_0_attempt_1.err"), tracked?.stdErr)
        }

    @Test
    fun `setCommandArtifacts tracks distinct command indices independently on the same process`() =
        runTest {
            val pm = PersistenceTestSupport.processManager()
            val id = pm.obtainProcessId("agent-a")
            pm.setCommandArtifacts(
                id,
                0,
                CommandArtifactPaths(Path.of("/artifacts/cmd_0.out"), Path.of("/artifacts/cmd_0.err")),
            )
            pm.setCommandArtifacts(
                id,
                1,
                CommandArtifactPaths(Path.of("/artifacts/cmd_1.out"), Path.of("/artifacts/cmd_1.err")),
            )

            assertEquals(Path.of("/artifacts/cmd_0.out"), pm.getCommandArtifacts(id, 0)?.stdOut)
            assertEquals(Path.of("/artifacts/cmd_1.out"), pm.getCommandArtifacts(id, 1)?.stdOut)
        }

    @Test
    fun `removeProcess clears tracked command artifacts`() =
        runTest {
            val pm = PersistenceTestSupport.processManager()
            val id = pm.obtainProcessId("agent-a")
            pm.setCommandArtifacts(
                id,
                0,
                CommandArtifactPaths(Path.of("/artifacts/cmd_0.out"), Path.of("/artifacts/cmd_0.err")),
            )

            pm.removeProcess(id)

            assertNull(pm.getCommandArtifacts(id, 0))
        }

    @Test
    fun `latestCommandArtifacts returns null for a process with nothing tracked`() =
        runTest {
            val pm = PersistenceTestSupport.processManager()
            val id = pm.obtainProcessId("agent-a")
            assertNull(pm.latestCommandArtifacts(id))
        }

    @Test
    fun `latestCommandArtifacts returns the highest tracked index`() =
        runTest {
            val pm = PersistenceTestSupport.processManager()
            val id = pm.obtainProcessId("agent-a")
            pm.setCommandArtifacts(
                id,
                0,
                CommandArtifactPaths(Path.of("/artifacts/cmd_0.out"), Path.of("/artifacts/cmd_0.err")),
            )
            pm.setCommandArtifacts(
                id,
                3,
                CommandArtifactPaths(Path.of("/artifacts/cmd_3.out"), Path.of("/artifacts/cmd_3.err")),
            )
            pm.setCommandArtifacts(
                id,
                1,
                CommandArtifactPaths(Path.of("/artifacts/cmd_1.out"), Path.of("/artifacts/cmd_1.err")),
            )

            val latest = pm.latestCommandArtifacts(id)

            assertEquals(3, latest?.first)
            assertEquals(Path.of("/artifacts/cmd_3.out"), latest?.second?.stdOut)
        }

    @Test
    fun `latestCommandArtifacts is isolated per process`() =
        runTest {
            val pm = PersistenceTestSupport.processManager()
            val id0 = pm.obtainProcessId("agent-a")
            val id1 = pm.obtainProcessId("agent-a")
            pm.setCommandArtifacts(
                id0,
                5,
                CommandArtifactPaths(Path.of("/artifacts/cmd_5.out"), Path.of("/artifacts/cmd_5.err")),
            )
            pm.setCommandArtifacts(
                id1,
                0,
                CommandArtifactPaths(Path.of("/artifacts/cmd_0.out"), Path.of("/artifacts/cmd_0.err")),
            )

            assertEquals(5, pm.latestCommandArtifacts(id0)?.first)
            assertEquals(0, pm.latestCommandArtifacts(id1)?.first)
        }

    @Test
    fun `capacity eviction clears tracked command artifacts for the evicted process`() =
        runTest {
            // Capacity eviction (ProcessRepository.obtainProcessId) deletes the victim
            // row directly, bypassing removeProcess — it must clear commandArtifacts
            // itself, or an evicted process's tracked paths leak for the server's
            // remaining uptime. Mirrors ProcessManagerTest's eviction test pattern.
            val pm = PersistenceTestSupport.processManager(maxProcesses = 2)
            val id0 = pm.obtainProcessId("agent-a")
            pm.obtainProcessId("agent-a")
            pm.setCommandArtifacts(
                id0,
                0,
                CommandArtifactPaths(Path.of("/artifacts/cmd_0.out"), Path.of("/artifacts/cmd_0.err")),
            )
            pm.setProcessStatus(id0, ProcessStatus.FINISHED)

            // At capacity → obtaining a new id evicts id0.
            pm.obtainProcessId("agent-a")

            assertNull(pm.getCommandArtifacts(id0, 0))
        }
}
