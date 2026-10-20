package homer.app

import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Denial-path guard for root CLAUDE.md §12 rule 7 (secrets travel by reference,
 * never by manifest). The image builds run Gradle, so they need ARTIFACTORY_URL
 * and the shared ARTIFACTORY_USER / ARTIFACTORY_TOKEN pair — but a `--build-arg` value stays
 * readable in `docker history` of the pushed image and an `ENV` is baked into
 * its metadata. Only a BuildKit secret mount leaves nothing in any layer, and
 * this test is what keeps the cheaper-looking alternatives out.
 *
 * It also pins the run-time half: the E2E runner's `ENTRYPOINT` runs Gradle
 * again, so the gate must be on in both phases or neither. On in one only is
 * the worst case — the repository set differs, Gradle re-resolves everything
 * against Maven Central, and the 429 this guards against simply moves.
 */
class ArtifactorySecretExposureTest {
    private val dockerfiles = listOf("app/Dockerfile", "app/e2e/Dockerfile")
    private val e2eCompose = "docker-compose.e2e.yml"

    /**
     * The CI pipeline file the build named (`homer.ciConfig`: the CI's CI_CONFIG_PATH, or the
     * checkout's root-level pipeline file), or null where there is none: the public GitHub tree
     * ships without it (ADR-0084), and there no build receives Artifactory credentials.
     */
    private fun ciPipeline(): File? = System.getProperty("homer.ciConfig")?.let(::File)?.takeIf { it.isFile }

    /** Every root compose file — any of them may grow a `build:` section. */
    private fun composeFiles(): List<File> =
        repoRoot
            .listFiles { f -> f.isFile && f.name.startsWith("docker-compose") && f.name.endsWith(".yml") }
            .orEmpty()
            .sortedBy { it.name }

    /** Joins `\`-continuations so a multi-line RUN is one instruction, and drops comments. */
    private fun instructionsOf(relativePath: String): List<String> =
        File(repoRoot, relativePath)
            .readText()
            .replace(Regex("""\\\r?\n\s*"""), " ")
            .lines()
            .map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("#") }

    private fun indentOf(line: String) = line.takeWhile { it == ' ' }.length

    private fun isFiller(line: String) = line.isBlank() || line.trimStart().startsWith("#")

    /**
     * The YAML lines nested under [index], i.e. indented deeper than it. Blank and
     * comment lines neither end the block nor appear in it — a comment naming the
     * constraint ("never pass ARTIFACTORY_TOKEN here") must not trip the guard.
     */
    private fun nestedBlock(
        lines: List<String>,
        index: Int,
    ): List<String> {
        val indent = indentOf(lines[index])
        return lines
            .drop(index + 1)
            .takeWhile { isFiller(it) || indentOf(it) > indent }
            .filterNot { isFiller(it) }
    }

    private fun indexOfKey(
        lines: List<String>,
        key: Regex,
    ): Int = lines.indexOfFirst { key.containsMatchIn(it) }

    @Test
    fun `artifactory credentials are never declared as build args or image env`() {
        val exposing = Regex("""^(ARG|ENV)\s+ARTIFACTORY_""", RegexOption.IGNORE_CASE)
        for (path in dockerfiles) {
            for (instruction in instructionsOf(path)) {
                assertTrue(
                    !exposing.containsMatchIn(instruction),
                    "$path exposes an Artifactory credential via `$instruction`. A build arg is " +
                        "readable in `docker history` and an ENV ships in the image metadata; use " +
                        "`--mount=type=secret` instead (root CLAUDE.md §12 rule 7).",
                )
            }
        }

        // Both spellings leak identically: `--build-arg X=y` and `--build-arg=X=y`.
        val buildArg = Regex("""--build-arg[= ]\s*ARTIFACTORY""", RegexOption.IGNORE_CASE)
        val ci = ciPipeline()?.readText() ?: return
        assertTrue(
            !buildArg.containsMatchIn(ci),
            "The CI pipeline passes an Artifactory credential as --build-arg; it would be readable " +
                "in `docker history` of the pushed image. Use --secret id=...,env=... instead.",
        )
    }

    /** `build.args` in compose is the same leak as `--build-arg`, one file over. */
    @Test
    fun `artifactory credentials are never passed as a compose build arg`() {
        val argsKey = Regex("""^\s*args:""")
        for (compose in composeFiles()) {
            val lines = compose.readLines()
            for ((index, line) in lines.withIndex()) {
                if (!argsKey.containsMatchIn(line)) continue
                for (nested in nestedBlock(lines, index)) {
                    assertTrue(
                        !nested.contains("ARTIFACTORY", ignoreCase = true),
                        "${compose.name} routes an Artifactory credential through a build arg " +
                            "(`${nested.trim()}`). It would be readable in `docker history` of the " +
                            "pushed image; declare it under `build.secrets` instead " +
                            "(root CLAUDE.md §12 rule 7).",
                    )
                }
            }
        }
    }

    @Test
    fun `the e2e runner keeps both credentials as run-time environment`() {
        val lines = File(repoRoot, e2eCompose).readLines()
        val serviceIndex = indexOfKey(lines, Regex("""^\s{2}e2e-runner:"""))
        assertTrue(serviceIndex >= 0, "$e2eCompose no longer defines an `e2e-runner` service.")

        val service = nestedBlock(lines, serviceIndex)
        val environmentIndex = indexOfKey(service, Regex("""^\s*environment:"""))
        assertTrue(environmentIndex >= 0, "$e2eCompose: e2e-runner declares no `environment:` block.")

        val environment = nestedBlock(service, environmentIndex).map { it.trim() }
        for (name in listOf("ARTIFACTORY_URL", "ARTIFACTORY_USER", "ARTIFACTORY_TOKEN")) {
            assertTrue(
                environment.contains("- $name"),
                "$e2eCompose: e2e-runner no longer passes `$name` through at run time. Its " +
                    "ENTRYPOINT runs Gradle again, so dropping this leaves the gate on while " +
                    "building and off while running — the repository sets then disagree, Gradle " +
                    "re-resolves against Maven Central, and the 429 comes back at `:e2e:test`.",
            )
        }
    }

    /**
     * The mounts above are only half the wiring. Drop a supplier and every other
     * guard stays green while both image builds quietly go back to Maven Central.
     * Scoped to the E2E compose deliberately: `docker-compose.yml` also builds
     * `app/Dockerfile`, but it is a local dev stack with no credentials, so
     * demanding `build.secrets` of every compose file would be wrong.
     */
    @Test
    fun `both secret suppliers stay wired`() {
        // Tie each supplier to the image it supplies. Asserting the flags appear
        // *somewhere* in the file would stay green if they moved to another job's
        // build — e.g. homer-console, which needs none. Keyed on the Dockerfile
        // rather than a job name, so a future image build is covered on arrival.
        val ci = ciPipeline()?.readLines().orEmpty()
        val secretMounting = Regex("""-f\s+\S*app/(e2e/)?Dockerfile""")
        val builds = ci.filter { it.contains("docker build") && secretMounting.containsMatchIn(it) }
        assertTrue(
            ciPipeline() == null || builds.isNotEmpty(),
            "The CI pipeline no longer builds a secret-mounting Dockerfile via `docker build`; if " +
                "that moved to another tool, move this guard with it rather than deleting it.",
        )
        for (build in builds) {
            for (id in listOf("artifactory_url", "artifactory_user", "artifactory_token")) {
                assertTrue(
                    Regex("""--secret\s+id=$id,env=""").containsMatchIn(build),
                    "The CI pipeline builds a secret-mounting Dockerfile without " +
                        "`--secret id=$id,env=...`, so the mount resolves to nothing and that image " +
                        "silently falls back to Maven Central: ${build.trim()}",
                )
            }
        }

        val lines = File(repoRoot, e2eCompose).readLines()
        var buildBlocks = 0
        for ((index, line) in lines.withIndex()) {
            if (!Regex("""^\s*build:""").containsMatchIn(line)) continue
            buildBlocks++
            val build = nestedBlock(lines, index)
            val secretsIndex = indexOfKey(build, Regex("""^\s*secrets:"""))
            assertTrue(secretsIndex >= 0, "$e2eCompose: a `build:` block declares no `secrets:`.")

            val declared = nestedBlock(build, secretsIndex).map { it.trim() }
            for (id in listOf("artifactory_url", "artifactory_user", "artifactory_token")) {
                assertTrue(
                    declared.contains("- $id"),
                    "$e2eCompose: a `build:` block no longer declares the `$id` secret, so that " +
                        "image silently resolves from Maven Central again.",
                )
            }
        }
        assertTrue(buildBlocks == 4, "$e2eCompose should build exactly 4 images, found $buildBlocks.")
    }

    @Test
    fun `every gradlew build step mounts both artifactory secrets`() {
        for (path in dockerfiles) {
            val gradleRuns = instructionsOf(path).filter { it.startsWith("RUN ") && it.contains("./gradlew") }
            assertTrue(gradleRuns.isNotEmpty(), "$path has no gradlew RUN step — did the build move?")
            for (run in gradleRuns) {
                for (id in listOf("artifactory_url", "artifactory_user", "artifactory_token")) {
                    assertTrue(
                        run.contains("--mount=type=secret,id=$id"),
                        "$path runs Gradle without mounting the `$id` secret, so it resolves from " +
                            "Maven Central and gets rate-limited (429) instead of going through " +
                            "Artifactory: $run",
                    )
                }
            }
        }
    }
}
