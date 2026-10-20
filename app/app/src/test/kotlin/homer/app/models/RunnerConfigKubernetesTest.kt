package homer.app.models

import com.charleskorn.kaml.Yaml
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RunnerConfigKubernetesTest {
    private val yaml = Yaml.default

    private fun k8s(
        extras: List<ExtraUploadSpec> = emptyList(),
        excludes: List<String> = emptyList(),
    ) = RunnerConfig.Kubernetes(
        image = "img",
        syncExcludes = excludes,
        extraUploads = extras,
    )

    @Test
    fun `syncExcludes and extraUploads default empty and yaml round-trip`() {
        val cfg =
            yaml.decodeFromString(
                RunnerConfig.serializer(),
                """!<kubernetes>
namespace: homer
image: registry/builder:1
syncExcludes: [".git", ".gradle", "build"]
extraUploads:
  - local: /app/scripts
    remote: .homer/scripts
    env: SCRIPTS_DIR""",
            )
        val k8s = assertIs<RunnerConfig.Kubernetes>(cfg)
        assertEquals(listOf(".git", ".gradle", "build"), k8s.syncExcludes)
        assertEquals(ExtraUploadSpec("/app/scripts", ".homer/scripts", "SCRIPTS_DIR"), k8s.extraUploads.single())
        val bare =
            yaml.decodeFromString(
                RunnerConfig.serializer(),
                """!<kubernetes>
image: img""",
            )
        assertEquals(emptyList(), assertIs<RunnerConfig.Kubernetes>(bare).syncExcludes)
        assertEquals(emptyList(), assertIs<RunnerConfig.Kubernetes>(bare).extraUploads)
    }

    @Test
    fun `validateExtraUploads enforces the ADR-0042 rules naming the alias`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                k8s(extras = listOf(ExtraUploadSpec("/a", "workdir"))).validateExtraUploads("build-box")
            }
        assertTrue(ex.message!!.contains("runner 'build-box'"))
        assertFailsWith<IllegalArgumentException> {
            k8s(
                extras =
                    listOf(
                        ExtraUploadSpec("/a", ".homer/x"),
                        ExtraUploadSpec("/b", ".homer/x"),
                    ),
            ).validateExtraUploads("build-box")
        }
    }

    @Test
    fun `AppConfig init validates kubernetes extraUploads naming the alias`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                AppConfig(
                    agentsSearchPath = "a",
                    workspaceRootPath = "w",
                    databasePath = "d",
                    runners = mapOf("build-box" to k8s(extras = listOf(ExtraUploadSpec("/a", "artifacts/x")))),
                )
            }
        assertTrue(ex.message!!.contains("runner 'build-box'"))
    }
}
