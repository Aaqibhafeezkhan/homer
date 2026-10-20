package homer.app

import homer.app.exceptions.ConfigurationException
import homer.app.models.AppConfig
import homer.app.models.ExtraUploadSpec
import homer.app.models.RunnerConfig
import homer.app.models.SshAuth
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertFailsWith

class ExtraUploadsValidationTest {
    private fun config(extras: List<ExtraUploadSpec>) =
        AppConfig(
            agentsSearchPath = "agents",
            workspaceRootPath = "ws",
            databasePath = "homer.db",
            runners =
                mapOf(
                    "box" to
                        RunnerConfig.Ssh(
                            host = "h",
                            user = "u",
                            auth = SshAuth.Agent,
                            remoteWorkspaceRoot = "/ws",
                            extraUploads = extras,
                        ),
                ),
        )

    @Test
    fun `missing local path fails startup validation naming runner and path`() {
        val ex =
            assertFailsWith<ConfigurationException> {
                validateExtraUploads(config(listOf(ExtraUploadSpec("/definitely/not/here", ".homer/scripts"))))
            }
        kotlin.test.assertTrue(ex.message!!.contains("box"))
        kotlin.test.assertTrue(ex.message!!.contains("/definitely/not/here"))
    }

    @Test
    fun `readable local file and directory pass`(
        @TempDir dir: Path,
    ) {
        dir.resolve("git.sh").writeText("#!/bin/sh")
        validateExtraUploads(config(listOf(ExtraUploadSpec(dir.toString(), ".homer/scripts"))))
        validateExtraUploads(config(listOf(ExtraUploadSpec(dir.resolve("git.sh").toString(), ".homer/scripts/git.sh"))))
    }

    @Test
    fun `config without ssh runners passes`() {
        validateExtraUploads(
            AppConfig(agentsSearchPath = "a", workspaceRootPath = "w", databasePath = "d"),
        )
    }

    @Test
    fun `missing local path on a kubernetes runner fails startup validation`() {
        val cfg =
            AppConfig(
                agentsSearchPath = "agents",
                workspaceRootPath = "ws",
                databasePath = "homer.db",
                runners =
                    mapOf(
                        "kbox" to
                            RunnerConfig.Kubernetes(
                                image = "img",
                                extraUploads = listOf(ExtraUploadSpec("/definitely/not/here", ".homer/scripts")),
                            ),
                    ),
            )
        val ex = assertFailsWith<ConfigurationException> { validateExtraUploads(cfg) }
        kotlin.test.assertTrue(ex.message!!.contains("kbox"))
    }

    @Test
    fun `missing local path on an external domain fails startup validation`() {
        val cfg =
            AppConfig(
                agentsSearchPath = "agents",
                workspaceRootPath = "ws",
                databasePath = "homer.db",
                runners =
                    mapOf(
                        "workers" to
                            RunnerConfig.External(
                                token = "t".repeat(32),
                                extraUploads = listOf(ExtraUploadSpec("/definitely/not/here", ".homer/scripts")),
                            ),
                    ),
            )
        val ex = assertFailsWith<ConfigurationException> { validateExtraUploads(cfg) }
        kotlin.test.assertTrue(ex.message!!.contains("workers"))
    }
}

class RunnerEnvValidationTest {
    private fun sshConfig(env: Map<String, String>) =
        AppConfig(
            agentsSearchPath = "agents",
            workspaceRootPath = "ws",
            databasePath = "homer.db",
            runners =
                mapOf(
                    "box" to RunnerConfig.Ssh(host = "h", user = "u", auth = SshAuth.Agent, remoteWorkspaceRoot = "/ws", env = env),
                ),
        )

    private fun kubernetesConfig(env: Map<String, String>) =
        AppConfig(
            agentsSearchPath = "agents",
            workspaceRootPath = "ws",
            databasePath = "homer.db",
            runners = mapOf("kbox" to RunnerConfig.Kubernetes(image = "img", env = env)),
        )

    @Test
    fun `HMR_-prefixed runner env key is rejected on an ssh runner`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                sshConfig(mapOf("HMR_WORK_DIR" to "/tmp/evil"))
            }
        kotlin.test.assertTrue(ex.message!!.contains("box"))
        kotlin.test.assertTrue(ex.message!!.contains("HMR_"))
    }

    @Test
    fun `HMR_-prefixed runner env key is rejected on a kubernetes runner`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                kubernetesConfig(mapOf("HMR_PROCESS_ID" to "7"))
            }
        kotlin.test.assertTrue(ex.message!!.contains("kbox"))
    }

    @Test
    fun `blank runner env key is rejected on an ssh runner`() {
        assertFailsWith<IllegalArgumentException> { sshConfig(mapOf("   " to "v")) }
    }

    @Test
    fun `blank runner env key is rejected on a kubernetes runner`() {
        assertFailsWith<IllegalArgumentException> { kubernetesConfig(mapOf("" to "v")) }
    }

    @Test
    fun `ordinary runner env keys parse on both runner types`() {
        sshConfig(mapOf("PATH" to "/opt/homebrew/bin", "CI" to "true"))
        kubernetesConfig(mapOf("HOME" to "/home/homer"))
    }
}
