package homer.app

import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Denial-path guard for CWE-319. The Gradle settings scripts
 * attach the shared ARTIFACTORY_USER / ARTIFACTORY_TOKEN pair as Basic auth, so
 * a cleartext origin replays that credential in a plaintext Authorization header
 * on every CI resolution (plugins, dependencies, remote build cache). The origin
 * comes from ARTIFACTORY_URL, so each gate must refuse anything but a bare https
 * origin before using it: no cleartext scheme, and no userinfo that Gradle would
 * echo into its logs. The gate is repeated per script because `pluginManagement` cannot be
 * fed from an applied script, so this test is also what keeps the copies from
 * drifting apart.
 */
class ArtifactoryTransportTest {
    private val expectedOrigin = "\$artifactoryUrl/artifactory/"
    private val originGuard = """require(Regex("https://[A-Za-z0-9.-]+(:[0-9]+)?").matches(artifactoryUrl))"""

    private val settingsScripts =
        listOf(
            "app/settings.gradle.kts",
            "app/gradle/artifactory.settings.gradle.kts",
            "app/buildSrc/settings.gradle.kts",
        )

    /** Comments name the constraint, so only executable lines may be scanned for it. */
    private fun codeOf(relativePath: String): String =
        File(repoRoot, relativePath)
            .readLines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    @Test
    fun `cleartextArtifactoryUrl_isRejected_y_a2jFs_`() {
        for (script in settingsScripts) {
            val code = codeOf(script)
            assertTrue(
                !code.contains("http://artifactory"),
                "$script declares a cleartext Artifactory origin. The Basic-auth credential " +
                    "would travel in a plaintext Authorization header (CWE-319).",
            )
            assertTrue(
                !code.contains("isAllowInsecureProtocol"),
                "$script re-enables isAllowInsecureProtocol. A certificate that cannot be " +
                    "verified must fail the build, not downgrade it (CWE-319).",
            )
        }
    }

    @Test
    fun `every Artifactory origin comes from ARTIFACTORY_URL behind an origin guard`() {
        val origin = Regex("""uri\("(\S*?artifactory\S*?)"\)""")
        var total = 0
        for (script in settingsScripts) {
            val code = codeOf(script)
            val urls = origin.findAll(code).map { it.groupValues[1] }.toList()
            total += urls.size
            for (url in urls) {
                assertTrue(
                    url.startsWith(expectedOrigin),
                    "$script resolves Artifactory from '$url'; it must be '$expectedOrigin...', the " +
                        "origin from ARTIFACTORY_URL, never a literal host (CWE-319).",
                )
            }
            assertTrue(
                Regex(Regex.escape(originGuard)).findAll(code).count() == urls.size,
                "$script uses ARTIFACTORY_URL without `$originGuard` in front of every use, so an " +
                    "http:// value would send the Basic-auth credential in cleartext, and a user:token@ " +
                    "value would land in Gradle's logs (CWE-319).",
            )
        }
        assertTrue(total == 4, "Expected 4 Artifactory URLs across the settings scripts, found $total.")
    }

    /** The pinned text above is only half the guard; this runs its pattern against the shapes it must refuse. */
    @Test
    fun `the origin guard admits only a bare https host and port`() {
        val pattern = Regex(originGuard.substringAfter("Regex(\"").substringBefore("\")"))
        for (accepted in listOf("https://h", "https://h:8080", "https://artifactory.example.test")) {
            assertTrue(pattern.matches(accepted), "The origin guard rejects '$accepted', a bare https origin.")
        }
        val refused =
            listOf(
                "http://h",
                "https://h/",
                "https://h/x",
                "https://u:p@h",
                "https://h?x",
                "https://h#f",
                "https://h ",
                " https://h",
                "https://h:",
                "https://h:x",
            )
        for (value in refused) {
            assertTrue(
                !pattern.matches(value),
                "The origin guard admits '$value'. Only https://<host>[:port] may pass: another scheme sends the " +
                    "credential in cleartext, userinfo lands in Gradle's logs (CWE-319).",
            )
        }
    }
}
