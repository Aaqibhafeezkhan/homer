package homer.app

import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.LangfuseConfig
import homer.app.models.OpenTelemetryExport
import homer.app.models.Server
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.RunnerRegistry
import homer.app.services.ContinuationSweepService
import homer.app.services.CronSchedulerService
import homer.app.services.DispatchSweepService
import homer.app.services.MaintenanceModeService
import homer.app.services.WorkspacePurgeService
import homer.app.services.persistence.DatabaseService
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The shutdown drain's wiring (`subscribeShutdownDrain`): `ApplicationStopping` must stop the
 * background services, drain the in-flight jobs and close the database last, so dropping any of
 * those calls fails a test instead of leaving pods, parked requests or a truncated WAL behind
 * (ADR-0020, ADR-0066, ADR-0092).
 *
 * The services are constructed through Koin during the boot, so their constructors are
 * intercepted and the drain's calls verified on the constructed instances. Drain timing itself
 * lives in `ShutdownDrainTest` — these cases pin the wiring, not the window.
 */
class ShutdownWiringTest {
    @TempDir
    lateinit var agentsDir: File

    @TempDir
    lateinit var workspaceDir: File

    @AfterEach
    fun cleanup() {
        unmockkAll()
        // Every boot-and-stop test flips the process-wide drain flag and it is never cleared;
        // left set, a later `/readyz` assertion in this JVM would see 503.
        isDraining.set(false)
    }

    private fun config() =
        AppConfig(
            server = Server(apiAuth = AuthConfig.Unsecured),
            agentsSearchPath = agentsDir.absolutePath,
            workspaceRootPath = workspaceDir.absolutePath,
            databasePath = File(workspaceDir, "homer.db").absolutePath,
            langfuse =
                LangfuseConfig(
                    serverUrl = "http://localhost:9",
                    publicKey = "pk-test",
                    secretKey = "sk-test",
                ),
            openTelemetryExport = OpenTelemetryExport.LOG,
        )

    /**
     * Intercepts the constructors of every service the drain stops. MockK only records calls on a
     * constructor mock that already exists when the object is built, so each one is touched here
     * before the boot; `callOriginal()` keeps the real method, which the drain's timing needs —
     * a stubbed `stop()` would leave the loop it cancels running and burn the drain window.
     */
    private fun interceptServiceConstructors() {
        mockkConstructor(RunnerRegistry::class)
        every { anyConstructed<RunnerRegistry>().stop() } answers { callOriginal() }
        mockkConstructor(CronSchedulerService::class)
        every { anyConstructed<CronSchedulerService>().stop() } answers { callOriginal() }
        mockkConstructor(MaintenanceModeService::class)
        every { anyConstructed<MaintenanceModeService>().isEnabled() } returns false
        mockkConstructor(WorkspacePurgeService::class)
        every { anyConstructed<WorkspacePurgeService>().stop() } answers { callOriginal() }
        mockkConstructor(DispatchSweepService::class)
        every { anyConstructed<DispatchSweepService>().stop() } answers { callOriginal() }
        mockkConstructor(ContinuationSweepService::class)
        every { anyConstructed<ContinuationSweepService>().stop() } answers { callOriginal() }
        mockkConstructor(ActiveSessionsRegistry::class)
        every { anyConstructed<ActiveSessionsRegistry>().closeAll() } answers { callOriginal() }
        mockkConstructor(DatabaseService::class)
        every { anyConstructed<DatabaseService>().close() } answers { callOriginal() }
    }

    @Test
    fun `the shutdown drain stops the maintenance mode service`() {
        mockkConstructor(MaintenanceModeService::class)
        every { anyConstructed<MaintenanceModeService>().isEnabled() } returns false

        testApplication {
            application { configureServer(config()) }
            // Leaving the block stops the engine, which fires ApplicationStopping — the drain.
        }

        verify(exactly = 1) { anyConstructed<MaintenanceModeService>().stop() }
    }

    @Test
    fun `the shutdown drain stops every background service, drains and closes the database last`() {
        interceptServiceConstructors()

        testApplication {
            application { configureServer(config()) }
            // Leaving the block stops the engine, which fires ApplicationStopping — the drain.
        }

        verify(exactly = 1) { anyConstructed<RunnerRegistry>().stop() }
        verify(exactly = 1) { anyConstructed<CronSchedulerService>().stop() }
        verify(exactly = 1) { anyConstructed<MaintenanceModeService>().stop() }
        verify(exactly = 1) { anyConstructed<WorkspacePurgeService>().stop() }
        verify(exactly = 1) { anyConstructed<DispatchSweepService>().stop() }
        verify(exactly = 1) { anyConstructed<ContinuationSweepService>().stop() }
        // closeAll() is the drain's observable end: it runs after the in-flight join.
        verify(exactly = 1) { anyConstructed<ActiveSessionsRegistry>().closeAll() }
        verify(exactly = 1) { anyConstructed<DatabaseService>().close() }

        verifyOrder {
            // Claim-queue consumers stop before the drain; their `while (isActive)` loops never
            // finish on their own (ADR-0066).
            anyConstructed<RunnerRegistry>().stop()
            anyConstructed<ActiveSessionsRegistry>().closeAll()
            // The database closes last, once in-flight writes have drained (ADR-0020).
            anyConstructed<DatabaseService>().close()
        }
    }
}
