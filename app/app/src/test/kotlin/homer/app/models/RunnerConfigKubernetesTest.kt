package homer.app.models

import com.charleskorn.kaml.Yaml
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
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

    /** The soft deadline of ADR-0107: a `gracefulStopSeconds` on a `maxRunSeconds`-bounded runner. */
    @Test
    fun `gracefulStopSeconds round-trips and defaults to no soft deadline`() {
        val cfg =
            yaml.decodeFromString(
                RunnerConfig.serializer(),
                """!<kubernetes>
image: img
maxRunSeconds: 3600
gracefulStopSeconds: 300""",
            )
        val k8s = assertIs<RunnerConfig.Kubernetes>(cfg)
        assertEquals(300L, k8s.gracefulStopSeconds)
        assertEquals(3600L, k8s.maxRunSeconds)

        // Absent = no soft deadline, which is what keeps a run that never opted in unchanged.
        assertNull(k8s().gracefulStopSeconds)
        assertNull(k8s().maxRunSeconds)
    }

    @Test
    fun `validateWorkload refuses a gracefulStopSeconds it cannot honour`() {
        // The soft deadline is measured back from maxRunSeconds, so without one there is
        // nothing to be soft about.
        val noMax =
            assertFailsWith<IllegalArgumentException> {
                k8s().copy(gracefulStopSeconds = 60).validateWorkload("build-box")
            }
        assertTrue(noMax.message!!.contains("gracefulStopSeconds requires maxRunSeconds"), noMax.message!!)

        // Zero would stop every run the moment it started.
        assertTrue(
            assertFailsWith<IllegalArgumentException> {
                k8s().copy(maxRunSeconds = 600, gracefulStopSeconds = 0).validateWorkload("build-box")
            }.message!!.contains("must be >= 1"),
        )

        // A grace that swallows the whole run leaves the hard deadline nothing to bound.
        assertTrue(
            assertFailsWith<IllegalArgumentException> {
                k8s().copy(maxRunSeconds = 600, gracefulStopSeconds = 600).validateWorkload("build-box")
            }.message!!.contains("must be less than maxRunSeconds"),
        )

        // Both ends that are legal: one second before the hard deadline, and the bare minimum.
        k8s().copy(maxRunSeconds = 600, gracefulStopSeconds = 599).validateWorkload("build-box")
        k8s().copy(maxRunSeconds = 600, gracefulStopSeconds = 1).validateWorkload("build-box")
    }

    @Test
    fun `AppConfig init validates the graceful stop bound naming the alias`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                AppConfig(
                    agentsSearchPath = "a",
                    workspaceRootPath = "w",
                    databasePath = "d",
                    runners = mapOf("build-box" to k8s().copy(gracefulStopSeconds = 60)),
                )
            }
        assertTrue(ex.message!!.contains("runner 'build-box'"))
        assertTrue(ex.message!!.contains("gracefulStopSeconds requires maxRunSeconds"), ex.message!!)
    }
}
