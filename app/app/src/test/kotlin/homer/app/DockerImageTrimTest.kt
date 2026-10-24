package homer.app

import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Guards the `homer-app` image trims (ADR-0015 amendment): npm's download cache never stays in a
 * layer, and the image's jar keeps only the SQLite native library its musl base loads.
 */
class DockerImageTrimTest {
    /** The Dockerfile's instructions, each with its `\`-continued lines joined. */
    private val instructions: List<String> =
        File(repoRoot, "app/Dockerfile")
            .readText()
            .replace(Regex("""\\\n"""), " ")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

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
        // FROM [--platform=…] <image> [AS <name>]; stage names are case-insensitive.
        val froms =
            instructions
                .filter { it.startsWith("FROM ", ignoreCase = true) }
                .map { line -> line.split(Regex("""\s+""")).drop(1).filterNot { it.startsWith("--") } }
                .map { parts -> parts[0] to parts.getOrNull(2)?.takeIf { parts[1].equals("AS", ignoreCase = true) }?.lowercase() }
        // A stage built FROM another stage of this file inherits that stage's base; every other base must be musl.
        val stageNames = froms.mapNotNull { it.second }.toSet()
        val runtimeBases =
            froms
                .filter { (_, name) -> name != "builder" }
                .map { it.first }
                .filter { it.lowercase() !in stageNames }
        assertTrue(
            runtimeBases.isNotEmpty() && runtimeBases.all { it.contains("alpine") },
            "the image's jar loads only the Linux-Musl SQLite library, so every runtime stage must stay on a musl " +
                "(Alpine) base; found $runtimeBases. A glibc base needs the full source-built jar (ADR-0015).",
        )
    }
}
