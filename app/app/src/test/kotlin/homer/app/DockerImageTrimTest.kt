package homer.app

import homer.app.fixtures.DockerfileStages
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Guards the `homer-app` image trims (ADR-0015 amendment): npm's download cache never stays in a
 * layer, and the image's jar keeps only the SQLite native library its musl base loads.
 */
class DockerImageTrimTest {
    private val dockerfile = DockerfileStages.app
    private val instructions = dockerfile.instructions

    @Test
    fun `every global npm install cleans the npm cache in the same layer`() {
        val installs = instructions.filter { it.startsWith("RUN ") && it.contains("npm install -g") }
        assertTrue(installs.isNotEmpty(), "app/Dockerfile no longer runs `npm install -g`; move this guard with it.")
        installs.forEach {
            assertTrue(
                it.contains("npm cache clean --force"),
                "`$it` leaves npm's download cache in its layer (about 280 MB compressed); end the same RUN " +
                    "with `npm cache clean --force`.",
            )
        }
    }

    @Test
    fun `the image jar keeps only the Linux-Musl SQLite natives, on a musl base`() {
        assertTrue(
            instructions.any {
                it.startsWith("RUN zip ") && it.contains(" -d ") && it.contains("'org/sqlite/native/*'") &&
                    it.contains("-x 'org/sqlite/native/Linux-Musl/*'")
            },
            "app/Dockerfile no longer strips the non-musl SQLite natives from the image's jar (about 11 MB).",
        )
        // A stage built FROM another stage of this file inherits that stage's base; every other base must be musl.
        val runtimeBases =
            dockerfile.stages
                .filter { it.name != "builder" }
                .map { it.base }
                .filterNot { dockerfile.isStage(it) }
        assertTrue(
            runtimeBases.isNotEmpty() && runtimeBases.all { it.contains("alpine") },
            "the image's jar loads only the Linux-Musl SQLite library, so every runtime stage must stay on a musl " +
                "(Alpine) base; found $runtimeBases. A glibc base needs the full source-built jar (ADR-0015).",
        )
    }
}
