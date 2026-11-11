package homer.app

import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.LangfuseConfig
import homer.app.models.OpenTelemetryExport
import homer.app.models.Server
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.RunnerRegistry
import homer.app.runners.queue.RunnerClaimQueue
import homer.app.services.MaintenanceModeService
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Job
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/**
 * The shutdown drain's wiring of the maintenance gate: `ApplicationStopping` must call
 * `MaintenanceModeService.stop()`, so the parked starts fail with their wait-timeout error
 * instead of holding their requests for the whole `queueWaitSec` and burning the drain
 * window (ADR-0092). The service is constructed through Koin during the boot, so the
 * constructor is intercepted and the drain's call verified on the constructed instance.
 */
class MaintenanceShutdownWiringTest {
    @TempDir
    lateinit var agentsDir: File

    @TempDir
    lateinit var workspaceDir: File

    @AfterEach
    fun cleanup() {
        unmockkAll()
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
    fun `the drain joins the run jobs before the runner consumers are stopped`() {
        // The order is the whole fix: stopping the consumers first (as it was) is what left a
        // run queued at SIGTERM with nobody to claim it, burning the drain window and ending
        // UNKNOWN. The drain's end is observable as `ActiveSessionsRegistry.closeAll`, its last
        // statement. Recorded through `answers` rather than `verifyOrder`, which MockK refuses
        // to combine with `anyConstructed`. See ADR-0066, ADR-0102.
        val order = mutableListOf<String>()
        mockkConstructor(MaintenanceModeService::class)
        every { anyConstructed<MaintenanceModeService>().isEnabled() } returns false
        every { anyConstructed<MaintenanceModeService>().stop() } answers { order += "maintenance-stop" }
        mockkConstructor(ActiveSessionsRegistry::class)
        every { anyConstructed<ActiveSessionsRegistry>().closeAll() } answers { order += "drain-end" }
        mockkConstructor(RunnerRegistry::class)
        every { anyConstructed<RunnerRegistry>().externalDomains } returns emptyList()
        every { anyConstructed<RunnerRegistry>().queue } returns RunnerClaimQueue(maxPending = 16)
        every { anyConstructed<RunnerRegistry>().aliases() } returns emptySet()
        every { anyConstructed<RunnerRegistry>().start(any()) } returns emptyList<Job>()
        every { anyConstructed<RunnerRegistry>().consumerJobs() } returns emptyList()
        every { anyConstructed<RunnerRegistry>().stop() } answers { order += "consumers-stop" }

        testApplication {
            application { configureServer(config()) }
            // Leaving the block stops the engine, which fires ApplicationStopping — the drain.
        }

        assertEquals(
            listOf("maintenance-stop", "drain-end", "consumers-stop"),
            order,
            "the parked starts are failed first, then the run jobs are joined, and only then are the consumers stopped",
        )
    }

    @Test
    fun `configureServer clears a drain flag left set in the JVM`() {
        // `isDraining` is JVM-global and ApplicationStopping never clears it on the way out,
        // so a second application configured in the same JVM would start up already draining
        // — /readyz answering 503 and every run start refused — unless configureServer
        // clears it. Deleting that clear leaves this green. See ADR-0102.
        isDraining.set(true)
        try {
            testApplication {
                application { configureServer(config()) }
                assertEquals(
                    HttpStatusCode.OK,
                    client.get("/api/v1/readyz").status,
                    "a freshly configured application must not report draining",
                )
            }
        } finally {
            isDraining.set(false)
        }
    }
}
