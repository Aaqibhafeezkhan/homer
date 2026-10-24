package homer.app

import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.LangfuseConfig
import homer.app.models.OpenTelemetryExport
import homer.app.models.Server
import homer.app.services.MaintenanceModeService
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

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
}
