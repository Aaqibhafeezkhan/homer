package homer.app.infra

import homer.app.EnvConstants
import homer.app.exceptions.WorkspaceException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import homer.app.models.Workspace as WorkspaceAgentConfig

class WorkspaceConfigFactoryTest {
    private val textCompiler = DefaultTextCompiler()
    private val globalEnv = mapOf("HMR_SERVER_ID" to "server-1", "GLOBAL_VAR" to "global-val")
    private val factory =
        WorkspaceConfigFactory(
            workspaceRoot = "/tmp/homer/{{HMR_SERVER_ID}}",
            globalEnv = globalEnv,
            textCompiler = textCompiler,
        )

    @Test
    fun `queryParams override agentEnv which overrides globalEnv`() {
        val config =
            factory.createWorkspaceConfig(
                processId = 42,
                agentEnv = mapOf("KEY" to "agent-val"),
                lockKeyTemplate = "lock",
                queryParams = mapOf("KEY" to "query-val"),
                workspaceAgentConfig = WorkspaceAgentConfig(),
            )
        assertEquals("query-val", config.env["KEY"])
    }

    @Test
    fun `HMR_PROCESS_ID injected from processId`() {
        val config =
            factory.createWorkspaceConfig(
                processId = 99,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(),
            )
        assertEquals("99", config.env[EnvConstants.PROCESS_ID])
    }

    @Test
    fun `HMR_PARENT_PROCESS_ID injected only when the run has a parent`() {
        val root =
            factory.createWorkspaceConfig(
                processId = 1,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(),
            )
        assertFalse(EnvConstants.PARENT_PROCESS_ID in root.env)

        val child =
            factory.createWorkspaceConfig(
                processId = 2,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                // A caller-supplied value never wins: HMR_* is injected above query params.
                queryParams = mapOf(EnvConstants.PARENT_PROCESS_ID to "999"),
                workspaceAgentConfig = WorkspaceAgentConfig(),
                parentProcessId = 1,
            )
        assertEquals("1", child.env[EnvConstants.PARENT_PROCESS_ID])
    }

    @Test
    fun `HMR_PARENT_PROCESS_ID inherited from agent env or host env is cleared at the root`() {
        val factory =
            WorkspaceConfigFactory(
                workspaceRoot = "/tmp/homer/{{HMR_SERVER_ID}}",
                globalEnv = globalEnv,
                textCompiler = textCompiler,
                parentEnvAllowlist = listOf(EnvConstants.PARENT_PROCESS_ID),
                hostEnvSupplier = { mapOf(EnvConstants.PARENT_PROCESS_ID to "555") },
            )
        val fromHost =
            factory.createWorkspaceConfig(
                processId = 1,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(),
            )
        assertFalse(EnvConstants.PARENT_PROCESS_ID in fromHost.env, "host env must not pose as a parent at the root")

        val fromAgentEnv =
            factory.createWorkspaceConfig(
                processId = 2,
                agentEnv = mapOf(EnvConstants.PARENT_PROCESS_ID to "777"),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(),
            )
        assertFalse(EnvConstants.PARENT_PROCESS_ID in fromAgentEnv.env, "agent env must not pose as a parent at the root")
    }

    @Test
    fun `HMR_ROOT_PROCESS_ID injected only when the run is a descendant, and never spoofable at the root`() {
        val root =
            factory.createWorkspaceConfig(
                processId = 1,
                agentEnv = mapOf(EnvConstants.ROOT_PROCESS_ID to "777"),
                lockKeyTemplate = "lock",
                // A caller-supplied value never wins: HMR_* is injected above query params.
                queryParams = mapOf(EnvConstants.ROOT_PROCESS_ID to "888"),
                workspaceAgentConfig = WorkspaceAgentConfig(),
            )
        assertFalse(EnvConstants.ROOT_PROCESS_ID in root.env, "absent — not empty — at the root")

        val descendant =
            factory.createWorkspaceConfig(
                processId = 2,
                agentEnv = mapOf(EnvConstants.ROOT_PROCESS_ID to "777"),
                lockKeyTemplate = "lock",
                queryParams = mapOf(EnvConstants.ROOT_PROCESS_ID to "888"),
                workspaceAgentConfig = WorkspaceAgentConfig(),
                parentProcessId = 1,
                rootProcessId = 1,
            )
        assertEquals("1", descendant.env[EnvConstants.ROOT_PROCESS_ID])

        // A run whose row predates the column still gets nothing rather than a guess.
        val unknownRoot =
            factory.createWorkspaceConfig(
                processId = 3,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(),
                parentProcessId = 2,
            )
        assertFalse(EnvConstants.ROOT_PROCESS_ID in unknownRoot.env)
    }

    @Test
    fun `HMR_ROOT_PROCESS_ID inherited from the host env is cleared at the root`() {
        val factory =
            WorkspaceConfigFactory(
                workspaceRoot = "/tmp/homer/{{HMR_SERVER_ID}}",
                globalEnv = globalEnv,
                textCompiler = textCompiler,
                parentEnvAllowlist = listOf(EnvConstants.ROOT_PROCESS_ID),
                hostEnvSupplier = { mapOf(EnvConstants.ROOT_PROCESS_ID to "555") },
            )
        val fromHost =
            factory.createWorkspaceConfig(
                processId = 1,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(),
            )
        assertFalse(EnvConstants.ROOT_PROCESS_ID in fromHost.env, "host env must not pose as a flow root")
    }

    @Test
    fun `serverRoot expands globalEnv variables`() {
        val config =
            factory.createWorkspaceConfig(
                processId = 1,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(path = "ws"),
            )
        assertTrue(config.serverRoot.toString().endsWith("server-1"), "serverRoot=${config.serverRoot}")
    }

    @Test
    fun `artifacts and workdir derived from projectRoot`() {
        val config =
            factory.createWorkspaceConfig(
                processId = 1,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(path = "ws"),
            )
        assertEquals(config.projectRoot.resolve("artifacts"), config.artifacts)
        assertEquals(config.projectRoot.resolve("workdir"), config.workdir)
    }

    @Test
    fun `path template escaping the server root is rejected`() {
        // A literal ".." in workspace.path is now rejected earlier, at agent load
        // (Workspace.init) — inject the escape via a query param so this still
        // exercises the resolve-time containment check (assertContained). Uses a
        // fixed template with an embedded param (distinct from the param-only
        // template in `escape injected via query param is rejected` below) so the
        // two tests carry distinct signal instead of covering the same path twice.
        assertFailsWith<WorkspaceException> {
            factory.createWorkspaceConfig(
                processId = 1,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = mapOf("dir" to "../../../../var/tmp/escape"),
                workspaceAgentConfig = WorkspaceAgentConfig(path = "{{dir}}/work"),
            )
        }
    }

    @Test
    fun `escape injected via query param is rejected`() {
        assertFailsWith<WorkspaceException> {
            factory.createWorkspaceConfig(
                processId = 1,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = mapOf("dir" to "../../etc"),
                workspaceAgentConfig = WorkspaceAgentConfig(path = "{{dir}}"),
            )
        }
    }

    @Test
    fun `a workspace path that collapses to the server root is rejected`() {
        // The template "{{dir}}" contains no literal ".." (so it passes
        // Workspace.init's load-time check), but the query-param value it
        // expands to resolves back to serverRoot itself.
        val ex =
            assertFailsWith<WorkspaceException> {
                factory.createWorkspaceConfig(
                    processId = 1,
                    agentEnv = emptyMap(),
                    lockKeyTemplate = "k",
                    queryParams = mapOf("dir" to "sub/.."),
                    workspaceAgentConfig = WorkspaceAgentConfig(path = "{{dir}}"),
                )
            }
        assertTrue(ex.message!!.contains("workspace"))
    }

    @Test
    fun `a root-collapsing path is rejected even once the workspace root already exists on disk`(
        @TempDir root: Path,
    ) {
        // Regression for a canonicalization asymmetry: an empty path resolves to
        // serverRoot's own (unnormalized) form, which already exists on disk
        // (created by @TempDir) — so assertContained takes the toRealPath()
        // branch and returns the *canonical* projectRoot, while a naive
        // comparison against the raw, uncanonicalized serverRoot never resolves
        // symlinks. On Darwin/CI the temp root sits behind one (e.g.
        // /var -> /private/var), so `projectRoot == serverRoot` alone silently
        // fails to fire here — this must still be rejected.
        val rootFactory =
            WorkspaceConfigFactory(
                workspaceRoot = root.toString(),
                globalEnv = emptyMap(),
                textCompiler = textCompiler,
            )
        val ex =
            assertFailsWith<WorkspaceException> {
                rootFactory.createWorkspaceConfig(
                    processId = 1,
                    agentEnv = emptyMap(),
                    lockKeyTemplate = "k",
                    queryParams = emptyMap(),
                    workspaceAgentConfig = WorkspaceAgentConfig(path = ""),
                )
            }
        assertTrue(ex.message!!.contains("workspace"))
    }

    @Test
    fun `an empty workspace path is rejected as collapsing to the root`() {
        assertFailsWith<WorkspaceException> {
            factory.createWorkspaceConfig(
                processId = 1,
                agentEnv = emptyMap(),
                lockKeyTemplate = "k",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(path = ""),
            )
        }
    }

    @Test
    fun `legitimate nested path stays under the server root`() {
        val config =
            factory.createWorkspaceConfig(
                processId = 1,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(path = "sub/dir"),
            )
        assertTrue(
            config.projectRoot.startsWith(config.serverRoot),
            "projectRoot=${config.projectRoot} serverRoot=${config.serverRoot}",
        )
    }

    @Test
    fun `lock key template expanded with merged env`() {
        val config =
            factory.createWorkspaceConfig(
                processId = 7,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock-{{HMR_PROCESS_ID}}",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(),
            )
        assertEquals("lock-7", config.lockKey)
    }

    @Test
    fun `HMR_ARTIFACTS_DIR and HMR_WORK_DIR injected into result env`() {
        val config =
            factory.createWorkspaceConfig(
                processId = 1,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(),
            )
        assertEquals(config.artifacts.toString(), config.env[EnvConstants.ARTIFACTS_DIR])
        assertEquals(config.workdir.toString(), config.env[EnvConstants.WORK_DIR])
    }

    @Test
    fun `allowlisted host env keys are layered into the child env`() {
        val hostEnv =
            mapOf(
                "PATH" to "/usr/bin",
                "HOME" to "/Users/op",
                "SECRET_LEAK" to "must-not-flow",
                "LANG" to "C.UTF-8",
            )
        val factory =
            WorkspaceConfigFactory(
                workspaceRoot = "/tmp/homer/{{HMR_SERVER_ID}}",
                globalEnv = globalEnv,
                textCompiler = textCompiler,
                parentEnvAllowlist = listOf("PATH", "HOME", "LANG", "TZ"),
                hostEnvSupplier = { hostEnv },
            )
        val config =
            factory.createWorkspaceConfig(
                processId = 1,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(),
            )
        assertEquals("/usr/bin", config.env["PATH"])
        assertEquals("/Users/op", config.env["HOME"])
        assertEquals("C.UTF-8", config.env["LANG"])
        assertTrue("SECRET_LEAK" !in config.env, "Non-allowlisted host env must not leak")
    }

    @Test
    fun `globalEnv overrides allowlisted host env on collision`() {
        val factory =
            WorkspaceConfigFactory(
                workspaceRoot = "/tmp/homer/{{HMR_SERVER_ID}}",
                globalEnv = mapOf("PATH" to "/custom/bin"),
                textCompiler = textCompiler,
                parentEnvAllowlist = listOf("PATH"),
                hostEnvSupplier = { mapOf("PATH" to "/usr/bin") },
            )
        val config =
            factory.createWorkspaceConfig(
                processId = 1,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(),
            )
        assertEquals("/custom/bin", config.env["PATH"])
    }

    @Test
    fun `allowlist entries missing from host env are skipped silently`() {
        val factory =
            WorkspaceConfigFactory(
                workspaceRoot = "/tmp/homer/{{HMR_SERVER_ID}}",
                globalEnv = emptyMap(),
                textCompiler = textCompiler,
                parentEnvAllowlist = listOf("PATH", "NOT_SET_ANYWHERE"),
                hostEnvSupplier = { mapOf("PATH" to "/usr/bin") },
            )
        val config =
            factory.createWorkspaceConfig(
                processId = 1,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(),
            )
        assertEquals("/usr/bin", config.env["PATH"])
        assertTrue("NOT_SET_ANYWHERE" !in config.env)
    }

    // ---- Remote runners: no parent-env layering, per-runner env layer ----

    @Test
    fun `includeParentEnv false drops every allowlisted host key`() {
        val factory =
            WorkspaceConfigFactory(
                workspaceRoot = "/tmp/homer/{{HMR_SERVER_ID}}",
                globalEnv = emptyMap(),
                textCompiler = textCompiler,
                parentEnvAllowlist = listOf("PATH", "HOME", "LANG", "TZ"),
                hostEnvSupplier = {
                    mapOf(
                        "PATH" to "/usr/bin",
                        "HOME" to "/home/homer",
                        "LANG" to "C.UTF-8",
                        "TZ" to "Asia/Tokyo",
                    )
                },
            )
        val config =
            factory.createWorkspaceConfig(
                processId = 1,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(),
                includeParentEnv = false,
            )
        assertTrue("PATH" !in config.env)
        assertTrue("HOME" !in config.env)
        assertTrue("LANG" !in config.env)
        assertTrue("TZ" !in config.env)
    }

    @Test
    fun `includeParentEnv false still honours globalEnv runnerEnv agentEnv and query params`() {
        val factory =
            WorkspaceConfigFactory(
                workspaceRoot = "/tmp/homer/{{HMR_SERVER_ID}}",
                globalEnv = mapOf("HOME" to "/from-global"),
                textCompiler = textCompiler,
                parentEnvAllowlist = listOf("PATH", "HOME"),
                hostEnvSupplier = { mapOf("PATH" to "/usr/bin", "HOME" to "/home/homer") },
            )
        val config =
            factory.createWorkspaceConfig(
                processId = 1,
                agentEnv = mapOf("LANG" to "from-agent"),
                lockKeyTemplate = "lock",
                queryParams = mapOf("TZ" to "from-query"),
                workspaceAgentConfig = WorkspaceAgentConfig(),
                runnerEnv = mapOf("CI" to "true"),
                includeParentEnv = false,
            )
        assertEquals("/from-global", config.env["HOME"], "explicitly-set globalEnv must survive")
        assertEquals("true", config.env["CI"], "runner env must survive")
        assertEquals("from-agent", config.env["LANG"], "agent env must survive")
        assertEquals("from-query", config.env["TZ"], "query params must survive")
        assertTrue("PATH" !in config.env, "host PATH must not reach a remote runner")
    }

    @Test
    fun `parentEnvLayerNames tracks only allowlisted keys still holding their host value`() {
        val hostEnv = mapOf("PATH" to "/usr/bin", "HOME" to "/home/homer", "LANG" to "C.UTF-8")
        val factory =
            WorkspaceConfigFactory(
                workspaceRoot = "/tmp/homer/{{HMR_SERVER_ID}}",
                globalEnv = mapOf("PATH" to "/custom/bin"),
                textCompiler = textCompiler,
                parentEnvAllowlist = listOf("PATH", "HOME", "LANG", "TZ"),
                hostEnvSupplier = { hostEnv },
            )
        val config =
            factory.createWorkspaceConfig(
                processId = 1,
                // HOME re-set by the agent: it must drop out of the host layer set.
                agentEnv = mapOf("HOME" to "/home/agent"),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(),
            )
        assertTrue("PATH" !in config.parentEnvLayerNames, "a globalEnv-overridden key is the operator's value, not the host's")
        assertTrue("HOME" !in config.parentEnvLayerNames, "an agent-overridden key must not count as host-layer")
        assertEquals("C.UTF-8", config.env["LANG"])
        assertTrue("LANG" in config.parentEnvLayerNames, "a key still holding its host value is host-layer")
        assertTrue("TZ" !in config.parentEnvLayerNames, "a key absent from the host env is not host-layer")
    }

    @Test
    fun `includeParentEnv false leaves parentEnvLayerNames empty`() {
        val factory =
            WorkspaceConfigFactory(
                workspaceRoot = "/tmp/homer/{{HMR_SERVER_ID}}",
                globalEnv = emptyMap(),
                textCompiler = textCompiler,
                parentEnvAllowlist = listOf("HOME"),
                hostEnvSupplier = { mapOf("HOME" to "/home/homer") },
            )
        val config =
            factory.createWorkspaceConfig(
                processId = 1,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(),
                includeParentEnv = false,
            )
        assertTrue(config.parentEnvLayerNames.isEmpty(), "a remote run's env carries no host layer to track")
    }

    @Test
    fun `runnerEnv overrides globalEnv and is overridden by agentEnv`() {
        val config =
            factory.createWorkspaceConfig(
                processId = 1,
                agentEnv = mapOf("RUNNER_KEY" to "from-agent"),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(),
                runnerEnv = mapOf("RUNNER_KEY" to "from-runner", "GLOBAL_VAR" to "from-runner"),
            )
        assertEquals("from-agent", config.env["RUNNER_KEY"])
        assertEquals("from-runner", config.env["GLOBAL_VAR"], "runnerEnv overrides globalEnv")
    }

    @Test
    fun `includeParentEnv default keeps the allowlisted host keys for the local runner`() {
        val factory =
            WorkspaceConfigFactory(
                workspaceRoot = "/tmp/homer/{{HMR_SERVER_ID}}",
                globalEnv = emptyMap(),
                textCompiler = textCompiler,
                parentEnvAllowlist = listOf("PATH", "HOME"),
                hostEnvSupplier = { mapOf("PATH" to "/usr/bin", "HOME" to "/home/homer") },
            )
        val config =
            factory.createWorkspaceConfig(
                processId = 1,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(),
            )
        assertEquals("/usr/bin", config.env["PATH"])
        assertEquals("/home/homer", config.env["HOME"])
    }

    @Test
    fun `@SYSENV-backed runnerEnv names are marked secret for the matching alias only`() {
        val redactingFactory =
            WorkspaceConfigFactory(
                workspaceRoot = "/tmp/homer",
                globalEnv = emptyMap(),
                textCompiler = textCompiler,
                sysEnvBackedRunnerEnvNames = mapOf("macos" to setOf("REPO_TOKEN")),
            )

        val macosRun =
            redactingFactory.createWorkspaceConfig(
                processId = 1,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(),
                runnerEnv = mapOf("REPO_TOKEN" to "tok-secret"),
                runnerAlias = "macos",
            )
        assertTrue("REPO_TOKEN" in macosRun.secretEnvNames)

        val otherRun =
            redactingFactory.createWorkspaceConfig(
                processId = 2,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(),
                runnerEnv = mapOf("REPO_TOKEN" to "tok-secret"),
                runnerAlias = "other-runner",
            )
        assertFalse("REPO_TOKEN" in otherRun.secretEnvNames)
    }

    // ---- Telemetry redaction seeding ----

    @Test
    fun `@SYSENV-backed globalEnv names are marked secret so telemetry redacts them`() {
        val redactingFactory =
            WorkspaceConfigFactory(
                workspaceRoot = "/tmp/homer",
                globalEnv = mapOf("REPO_TOKEN" to "tok-secret", "API_URL" to "https://example.test"),
                textCompiler = textCompiler,
                sysEnvBackedGlobalEnvNames = setOf("REPO_TOKEN"),
            )

        val config =
            redactingFactory.createWorkspaceConfig(
                processId = 1,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(),
            )

        assertTrue("REPO_TOKEN" in config.secretEnvNames)
        // A literal (non-@SYSENV) global stays unredacted: Homer cannot tell a
        // token from a URL, and masking every global would gut telemetry.
        assertFalse("API_URL" in config.secretEnvNames)
        assertEquals("***", SecretRedactor.from(config.env, config.secretEnvNames).redact("tok-secret"))
    }

    @Test
    fun `declared secret inputs are kept alongside the @SYSENV-backed globals`() {
        val redactingFactory =
            WorkspaceConfigFactory(
                workspaceRoot = "/tmp/homer",
                globalEnv = mapOf("REPO_TOKEN" to "tok-secret"),
                textCompiler = textCompiler,
                sysEnvBackedGlobalEnvNames = setOf("REPO_TOKEN"),
            )

        val config =
            redactingFactory.createWorkspaceConfig(
                processId = 1,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(),
                secretEnvNames = setOf("DECLARED_SECRET"),
            )

        assertTrue("DECLARED_SECRET" in config.secretEnvNames)
        assertTrue("REPO_TOKEN" in config.secretEnvNames)
    }

    @Test
    fun `callback token is always marked secret even though it is appended after config creation`() {
        val config =
            factory.createWorkspaceConfig(
                processId = 1,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(),
            )

        assertTrue(EnvConstants.CALLBACK_TOKEN in config.secretEnvNames)
        // The redactor resolves names to values at redact time, so a token appended
        // to the env after this call (Process.execute) is still covered.
        val withToken = config.env + (EnvConstants.CALLBACK_TOKEN to "tok-abc123")
        assertEquals(
            "callback ***",
            SecretRedactor.from(withToken, config.secretEnvNames).redact("callback tok-abc123"),
        )
    }

    @Test
    fun `a per-run path that resolves through a planted symlink is rejected`(
        @TempDir root: Path,
    ) {
        // An earlier same-UID run plants root/<future-id> as a symlink into its own dir.
        val shared = Files.createDirectories(root.resolve("attacker/shared"))
        Files.createSymbolicLink(root.resolve("7"), shared) // predictable next process id = 7
        val factory =
            WorkspaceConfigFactory(
                workspaceRoot = root.toString(),
                globalEnv = emptyMap(),
                textCompiler = DefaultTextCompiler(),
            )
        val ex =
            assertFailsWith<WorkspaceException> {
                factory.createWorkspaceConfig(
                    processId = 7,
                    agentEnv = emptyMap(),
                    lockKeyTemplate = "lock",
                    queryParams = emptyMap(),
                    workspaceAgentConfig = WorkspaceAgentConfig(path = "{{HMR_PROCESS_ID}}"),
                )
            }
        assertTrue(ex.message!!.contains("symlink"), "message=${ex.message}")
    }

    @Test
    fun `a normal per-run path on a real root still succeeds`(
        @TempDir root: Path,
    ) {
        val factory =
            WorkspaceConfigFactory(
                workspaceRoot = root.toString(),
                globalEnv = emptyMap(),
                textCompiler = DefaultTextCompiler(),
            )
        val cfg =
            factory.createWorkspaceConfig(
                processId = 7,
                agentEnv = emptyMap(),
                lockKeyTemplate = "lock",
                queryParams = emptyMap(),
                workspaceAgentConfig = WorkspaceAgentConfig(path = "{{HMR_PROCESS_ID}}"),
            )
        assertTrue(cfg.projectRoot.endsWith("7"), "projectRoot=${cfg.projectRoot}")
    }
}
