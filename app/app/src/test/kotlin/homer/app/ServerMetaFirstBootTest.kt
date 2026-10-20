package homer.app

import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.LangfuseConfig
import homer.app.models.OpenTelemetryExport
import homer.app.models.RunnerConfig
import homer.app.models.Server
import homer.app.models.TokenConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteException
import java.io.File
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * First boot against a fresh database with a kubernetes runner configured.
 *
 * The runner registry reads `server_meta` (the durable per-instance id,
 * ADR-0033) whenever a kubernetes runner is configured, and the `runnerTags`
 * servability check in `AgentsService` resolves that registry during the
 * directory scan. If `migrate()` has not run yet, the read fails with
 * "no such table: server_meta" and the agent is skipped. See ADR-0020.
 */
class ServerMetaFirstBootTest {
    @TempDir
    lateinit var agentsDir: File

    @TempDir
    lateinit var workspaceDir: File

    @Test
    fun `a runnerTags agent loads on the first boot of a fresh database with a kubernetes runner`() {
        File(agentsDir, "tagged").mkdirs()
        File(agentsDir, "tagged/agent.yaml").writeText(
            """
            name: tagged
            runnerTags: [gpu]
            commands:
              - !<cmd>
                cmd: "echo hello"
            """.trimIndent(),
        )
        val config =
            AppConfig(
                server = Server(apiAuth = AuthConfig.Bearer(tokens = mapOf("ops" to TokenConfig(token = "ops-token", role = "admin")))),
                agentsSearchPath = agentsDir.absolutePath,
                workspaceRootPath = workspaceDir.absolutePath,
                databasePath = File(workspaceDir, "homer.db").absolutePath,
                langfuse = LangfuseConfig(serverUrl = "http://localhost:9", publicKey = "pk-test", secretKey = "sk-test"),
                openTelemetryExport = OpenTelemetryExport.LOG,
                runners =
                    mapOf(
                        "k8s" to
                            RunnerConfig.Kubernetes(
                                kubeconfig = writeKubeconfig().absolutePath,
                                image = "alpine:3",
                                tags = listOf("gpu"),
                            ),
                    ),
            )
        testApplication {
            application { configureServer(config) }
            val body =
                client.get("/api/v1/agents") { header(HttpHeaders.Authorization, "Bearer ops-token") }.bodyAsText()
            assertTrue(body.contains("\"tagged\""), "agent 'tagged' not loaded; /api/v1/agents returned: $body")
        }
    }

    /**
     * The migration call sits before every other injection and fails fast: a
     * database that cannot be opened aborts boot instead of starting with
     * unreadable durable state. A directory path is not a SQLite database, so
     * opening it fails. The assertion names the concrete driver failure, not
     * `Throwable`, so a wiring or validation break elsewhere in boot cannot
     * satisfy it — only the migration-failure path itself. See ADR-0020.
     */
    @Test
    fun `a failed first-boot migration aborts boot instead of starting empty`() {
        val notADatabase = File(workspaceDir, "not-a-database").apply { mkdirs() }
        val config =
            AppConfig(
                server = Server(apiAuth = AuthConfig.Bearer(tokens = mapOf("ops" to TokenConfig(token = "ops-token", role = "admin")))),
                agentsSearchPath = agentsDir.absolutePath,
                workspaceRootPath = workspaceDir.absolutePath,
                databasePath = notADatabase.absolutePath,
                langfuse = LangfuseConfig(serverUrl = "http://localhost:9", publicKey = "pk-test", secretKey = "sk-test"),
                openTelemetryExport = OpenTelemetryExport.LOG,
            )

        assertFailsWith<SQLiteException> {
            testApplication {
                application { configureServer(config) }
                startApplication()
            }
        }
    }

    /**
     * A pinned kubeconfig keeps the test hermetic. The no-kubeconfig fallback
     * (`Config.defaultClient()`) is not inert: on a host that looks like a pod
     * — a serviceaccount ca.crt under `/var/run/secrets/...` — it takes the
     * cluster branch and throws on the absent `KUBERNETES_SERVICE_*` env vars.
     * Construction still never talks to a cluster, so no server is needed here.
     */
    private fun writeKubeconfig(): File =
        File(workspaceDir, "kubeconfig.yaml").apply {
            writeText(
                """
                apiVersion: v1
                kind: Config
                clusters:
                  - name: homer-test
                    cluster:
                      server: https://127.0.0.1:6443
                      insecure-skip-tls-verify: true
                contexts:
                  - name: homer-test
                    context:
                      cluster: homer-test
                      user: homer-test
                current-context: homer-test
                users:
                  - name: homer-test
                    user:
                      token: test-token
                """.trimIndent(),
            )
        }
}
