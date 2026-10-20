package homer.app.runners.local

import homer.app.EnvConstants
import homer.app.fixtures.TestFixtures
import homer.app.runners.RunnerSessionParams
import io.opentelemetry.context.Context
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LocalRunnerTest {
    @Test
    fun `alias is local`() {
        assertEquals("local", LocalRunner().alias)
    }

    @Test
    fun `openSession returns a session with a non-null shell`() =
        runTest {
            val runner = LocalRunner()
            val workspace = TestFixtures.mockWorkspaceConfig()

            val session =
                runner.openSession(
                    RunnerSessionParams(
                        processId = 1,
                        agentName = "demo",
                        workspace = workspace,
                        env = emptyMap(),
                        parentContext = Context.current(),
                    ),
                )

            assertNotNull(session.shell)
            session.close()
        }

    @Test
    fun `close is idempotent`() =
        runTest {
            val session = LocalRunnerSession(TestFixtures.mockWorkspaceConfig().workdir)

            session.close()
            session.close()
        }

    @Test
    fun `openSession contributes HMR_API_URL default when configured and absent`() =
        runTest {
            val runner = LocalRunner(defaultApiUrl = "http://127.0.0.1:8080")

            val session = runner.openSession(params(env = emptyMap()))

            assertEquals(mapOf(EnvConstants.API_URL to "http://127.0.0.1:8080"), session.env)
            session.close()
        }

    @Test
    fun `openSession keeps an operator-provided HMR_API_URL`() =
        runTest {
            val runner = LocalRunner(defaultApiUrl = "http://127.0.0.1:8080")

            val session = runner.openSession(params(env = mapOf(EnvConstants.API_URL to "http://homer:9999")))

            assertEquals(emptyMap(), session.env)
            session.close()
        }

    @Test
    fun `openSession contributes no env without a default api url`() =
        runTest {
            val session = LocalRunner().openSession(params(env = emptyMap()))

            assertEquals(emptyMap(), session.env)
            session.close()
        }

    private fun params(env: Map<String, String>) =
        RunnerSessionParams(
            processId = 1,
            agentName = "demo",
            workspace = TestFixtures.mockWorkspaceConfig(),
            env = env,
            parentContext = Context.current(),
        )
}
