package homer.app

import homer.app.infra.SysEnvResolver
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.RunnerConfig
import homer.app.models.Server
import homer.app.models.SshAuth
import homer.app.models.TokenConfig
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ResolveRunnerEnvsTest {
    @Test
    fun `resolves @SYSENV placeholders in Ssh runner env`() {
        val resolver = mockk<SysEnvResolver>()
        every { resolver.resolveMapValues(mapOf("SOME_VAR" to "@SYSENV:SOME_VAR")) } returns mapOf("SOME_VAR" to "actual")

        val runners: Map<String, RunnerConfig> =
            mapOf(
                "mac" to
                    RunnerConfig.Ssh(
                        host = "mac.example.com",
                        user = "runner",
                        auth = SshAuth.Agent,
                        strictHostKeyChecking = false,
                        remoteWorkspaceRoot = "/tmp/homer",
                        env = mapOf("SOME_VAR" to "@SYSENV:SOME_VAR"),
                    ),
            )

        val resolved = resolveRunnerEnvs(runners, resolver)

        val ssh = resolved["mac"] as RunnerConfig.Ssh
        assertEquals("actual", ssh.env["SOME_VAR"], "Ssh runner env value must be resolved")
        assertFalse(ssh.env.values.any { it.startsWith("@SYSENV:") }, "No @SYSENV: literal must remain")
    }

    @Test
    fun `resolves @SYSENV placeholders in Kubernetes runner env`() {
        val resolver = mockk<SysEnvResolver>()
        every { resolver.resolveMapValues(mapOf("SOME_VAR" to "@SYSENV:SOME_VAR")) } returns mapOf("SOME_VAR" to "actual")

        val runners: Map<String, RunnerConfig> =
            mapOf(
                "k8s" to
                    RunnerConfig.Kubernetes(
                        image = "ubuntu:24.04",
                        env = mapOf("SOME_VAR" to "@SYSENV:SOME_VAR"),
                    ),
            )

        val resolved = resolveRunnerEnvs(runners, resolver)

        val k8s = resolved["k8s"] as RunnerConfig.Kubernetes
        assertEquals("actual", k8s.env["SOME_VAR"], "Kubernetes runner env value must be resolved")
        assertFalse(k8s.env.values.any { it.startsWith("@SYSENV:") }, "No @SYSENV: literal must remain")
    }

    @Test
    fun `leaves non-SYSENV values and other runner fields untouched`() {
        val resolver = mockk<SysEnvResolver>()
        every { resolver.resolveMapValues(mapOf("LITERAL" to "plain")) } returns mapOf("LITERAL" to "plain")

        val runners: Map<String, RunnerConfig> =
            mapOf(
                "mac" to
                    RunnerConfig.Ssh(
                        host = "mac.example.com",
                        user = "runner",
                        auth = SshAuth.Agent,
                        strictHostKeyChecking = false,
                        remoteWorkspaceRoot = "/tmp/homer",
                        env = mapOf("LITERAL" to "plain"),
                    ),
            )

        val resolved = resolveRunnerEnvs(runners, resolver)

        val ssh = resolved["mac"] as RunnerConfig.Ssh
        assertEquals("plain", ssh.env["LITERAL"])
        assertEquals("mac.example.com", ssh.host, "host must not be touched")
    }

    @Test
    fun `domain tokens that collide only after @SYSENV resolution fail startup (CWE-287)`() {
        val resolver = mockk<SysEnvResolver>()
        every { resolver.resolve(any()) } returns "same-resolved-secret-0123456789abcdef"
        every { resolver.resolveMapValues(any()) } returns emptyMap()
        val raw =
            AppConfig(
                server = Server(apiAuth = AuthConfig.Bearer(tokens = mapOf("ops" to TokenConfig(token = "ops-token", role = "admin")))),
                agentsSearchPath = "unused",
                workspaceRootPath = "unused",
                databasePath = "unused",
                runners =
                    mapOf(
                        "a" to RunnerConfig.External(token = "@SYSENV:TOKEN_A"),
                        "b" to RunnerConfig.External(token = "@SYSENV:TOKEN_B"),
                    ),
            )

        // main() installs the resolved runners through copy(), which re-runs AppConfig's init checks.
        val ex = assertThrows<IllegalArgumentException> { raw.copy(runners = resolveRunnerEnvs(raw.runners, resolver)) }
        assertEquals(true, ex.message!!.contains("share a token"), ex.message)
        assertEquals(false, ex.message!!.contains("same-resolved-secret"), "the credential value must not be echoed")
    }
}
