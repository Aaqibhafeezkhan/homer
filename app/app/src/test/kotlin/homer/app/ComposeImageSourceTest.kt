package homer.app

import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * The default compose stack pulls the published release images and builds nothing
 * (ADR-0093), so a fresh clone starts Homer in seconds. This guard keeps the two
 * halves of that split apart — a `build:` section creeping back into
 * `docker-compose.yml` silently restores the multi-minute first run, and a
 * forgotten `HOMER_VERSION` bump ships a stale release to every new clone.
 *
 * It also pins the hardening the image switch must not drop (root CLAUDE.md §12
 * rule 8's regression check) and that no compose file or quick start carries a
 * literal admin token (rule 7: secrets travel by reference).
 */
class ComposeImageSourceTest {
    /** A `key:` (or `- key:`) line of a compose mapping, e.g. `backend:` or `- target: runner`. */
    private val mappingKey = Regex("""^\s*-?\s*[\w.-]+:(\s.*)?$""")

    /** `image: ghcr.io/livesport/homer/<name>:${HOMER_VERSION:-<version>}` — the pull the default stack must make. */
    private fun publishedImage(name: String) = Regex("""^ghcr\.io/livesport/homer/$name:\$\{HOMER_VERSION:-([^}]+)}$""")

    /** Every root compose file — the base, the overlays and the e2e stack. */
    private fun composeFiles(): List<File> =
        repoRoot
            .listFiles { f -> f.isFile && f.name.startsWith("docker-compose") && f.name.endsWith(".yml") }
            .orEmpty()
            .sortedBy { it.name }

    /** The quick-start doc set: the docs that walk a first reader through the compose stack. */
    private fun quickStartDocs(): List<File> =
        listOf("README.md", "DOCKER.md", "console/DEPLOYMENT.md", "docs/homer-feature-atlas.html")
            .map { File(repoRoot, it) }

    /** Every file this class guards: the compose files, the quick-start docs, the rebuild script and `.env.example`. */
    private fun guardedFiles(): List<File> =
        composeFiles() + quickStartDocs() + listOf("rebuild-docker.sh", ".env.example").map { File(repoRoot, it) }

    private fun indentOf(line: String) = line.takeWhile { it == ' ' }.length

    private fun isFiller(line: String) = line.isBlank() || line.trimStart().startsWith("#")

    /** The YAML lines nested under [index], comments and blanks excluded. */
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

    /** Indices of the direct `key:` children of the block at [index], assuming 2-space indents. */
    private fun childKeys(
        lines: List<String>,
        index: Int,
    ): List<Int> {
        val indent = indentOf(lines[index])
        return lines
            .withIndex()
            .drop(index + 1)
            .takeWhile { (_, line) -> isFiller(line) || indentOf(line) > indent }
            .filter { (_, line) -> indentOf(line) == indent + 2 && mappingKey.matches(line) }
            .map { it.index }
    }

    /** Index of the direct `key:` child of the block at [index], or -1. */
    private fun childKey(
        lines: List<String>,
        index: Int,
        key: String,
    ): Int = childKeys(lines, index).firstOrNull { lines[it].trimStart().startsWith("$key:") } ?: -1

    /** The value of the `key: value` line at [index]; empty when the key opens a nested block. */
    private fun valueAt(
        lines: List<String>,
        index: Int,
    ): String = if (index < 0) "" else lines[index].substringAfter(':', "").trim()

    /** Index of the service named [name] under the top-level `services:` block, or -1. */
    private fun service(
        lines: List<String>,
        name: String,
    ): Int {
        val services = lines.indexOfFirst { indentOf(it) == 0 && it.trimStart() == "services:" }
        if (services < 0) return -1
        return childKeys(lines, services).firstOrNull { lines[it].trimStart() == "$name:" } ?: -1
    }

    /** The `image:` value of service [name] in [file]; fails when the service or its `image:` is gone. */
    private fun imageOf(
        file: File,
        name: String,
    ): String {
        val lines = file.readLines()
        val index = service(lines, name)
        assertTrue(index >= 0, "${file.name} no longer defines a `$name` service.")
        val image = childKey(lines, index, "image")
        assertTrue(
            image >= 0,
            "${file.name}: `$name` declares no `image:`. Without one the stack cannot pull a published " +
                "image, and `docker compose up` on a fresh clone either builds from source or fails (ADR-0093).",
        )
        return valueAt(lines, image)
    }

    @Test
    fun `the base compose pulls the published release images and builds nothing`() {
        val base = File(repoRoot, "docker-compose.yml")
        assertTrue(base.isFile, "docker-compose.yml is missing.")
        val lines = base.readLines()
        for ((name, image) in listOf("backend" to "homer-app", "frontend" to "homer-console")) {
            val value = imageOf(base, name)
            assertTrue(
                publishedImage(image).matches(value),
                "docker-compose.yml: `$name` runs `$value`, not the published release image. A fresh clone " +
                    "then builds from source again — minutes of Gradle/Next and a Maven Central connection " +
                    "before anything starts (ADR-0093).",
            )
            val index = service(lines, name)
            assertTrue(
                childKey(lines, index, "build") < 0,
                "docker-compose.yml: `$name` declares a `build:` section again, so compose builds the working " +
                    "tree instead of pulling the release image. Building from source belongs in " +
                    "docker-compose.build.yml, which is opt-in (ADR-0093).",
            )
        }
    }

    @Test
    fun `the pinned default is the newest released version`() {
        val base = File(repoRoot, "docker-compose.yml")
        val newest =
            Regex("""^## \[(\d+\.\d+\.\d+)]""", RegexOption.MULTILINE)
                .find(File(repoRoot, "CHANGELOG.md").readText())
                ?.groupValues
                ?.get(1)
        assertTrue(newest != null, "CHANGELOG.md has no released `## [x.y.z]` heading to compare against.")
        for ((name, image) in listOf("backend" to "homer-app", "frontend" to "homer-console")) {
            val pinned = publishedImage(image).find(imageOf(base, name))?.groupValues?.get(1)
            assertTrue(
                pinned == newest,
                "docker-compose.yml pins `$name` to `${pinned ?: "nothing"}` while CHANGELOG.md's newest " +
                    "release is `$newest`: the release bumped the changelog without bumping HOMER_VERSION, so " +
                    "a fresh clone of main pulls the previous release (ADR-0093).",
            )
        }
    }

    @Test
    fun `the build overlay restores a build section for both services`() {
        val overlay = File(repoRoot, "docker-compose.build.yml")
        assertTrue(
            overlay.isFile,
            "docker-compose.build.yml is missing, so building from source is gone: the base compose pulls " +
                "release images and nothing else builds the working tree (ADR-0093, DOCKER.md § Quick Start).",
        )
        val lines = overlay.readLines()
        val buildSpecs =
            listOf(
                Triple("backend", ".", "app/Dockerfile"),
                Triple("frontend", "./console", "Dockerfile"),
            )
        for ((name, context, dockerfile) in buildSpecs) {
            val index = service(lines, name)
            assertTrue(index >= 0, "docker-compose.build.yml no longer defines a `$name` service.")
            val build = childKey(lines, index, "build")
            assertTrue(
                build >= 0,
                "docker-compose.build.yml: `$name` has no `build:` section, so the overlay adds nothing for " +
                    "that service and `docker compose -f docker-compose.yml -f docker-compose.build.yml up` " +
                    "pulls the release image instead of building the working tree (ADR-0093).",
            )
            for ((key, expected) in listOf("context" to context, "dockerfile" to dockerfile)) {
                val actual = valueAt(lines, childKey(lines, build, key))
                assertTrue(
                    actual == expected,
                    "docker-compose.build.yml: `$name` builds with `$key: ${actual.ifEmpty { "(missing)" }}` instead " +
                        "of `$expected`, so it builds something other than the working tree it claims to (ADR-0093).",
                )
            }
            if (name == "backend") {
                val actualTarget = valueAt(lines, childKey(lines, build, "target"))
                assertTrue(
                    actualTarget == "runner",
                    "docker-compose.build.yml: the backend builds target `${actualTarget.ifEmpty { "(none)" }}` instead " +
                        "of `runner`. app/Dockerfile's last stage is homer-runner, so without `target: runner` the " +
                        "build produces the external runner image instead of the backend.",
                )
            }
            val image = imageOf(overlay, name)
            assertTrue(
                image.startsWith("homer-") && !image.contains("ghcr.io"),
                "docker-compose.build.yml: `$name` tags what it builds `$image`. A tag under " +
                    "ghcr.io/livesport/homer would shadow the pulled release image with a local build " +
                    "(ADR-0093).",
            )
            assertTrue(
                childKey(lines, index, "ports") < 0,
                "docker-compose.build.yml: `$name` publishes ports. The overlay adds a build, nothing else — " +
                    "the base file owns the topology, and a second copy of the bindings drifts from it.",
            )
        }
    }

    @Test
    fun `every dev-overlay command in the docs also names the build overlay`() {
        // docker-compose.dev.yml sets build arguments and no context, so stacked on the base file
        // alone it merges build args into a service with no `build:` section and `docker compose up`
        // fails the merge — every command that adds the dev overlay needs the build overlay under it.
        val scanned =
            quickStartDocs() + listOf("rebuild-docker.sh", "docker-compose.build.yml").map { File(repoRoot, it) }
        for (file in scanned) {
            assertTrue(file.isFile, "${file.name} is missing; move this guard with the dev-overlay docs rather than deleting it.")
            val logical = mutableListOf<String>()
            for (line in file.readLines()) {
                // A trailing `\` continues the command on the next line — the compose stacks are wrapped.
                if (logical.isNotEmpty() && logical.last().endsWith("\\")) {
                    logical[logical.lastIndex] = logical.last().dropLast(1) + line
                } else {
                    logical.add(line)
                }
            }
            for (line in logical) {
                assertTrue(
                    !line.contains("-f docker-compose.dev.yml") || line.contains("-f docker-compose.build.yml"),
                    "${file.name} runs `-f docker-compose.dev.yml` without the build overlay: the dev overlay sets " +
                        "build arguments and no context, so stacked on the base file alone `docker compose up` " +
                        "merges them into a service with no `build:` section and fails (ADR-0093). Add " +
                        "`-f docker-compose.build.yml` before it.",
                )
            }
        }
    }

    @Test
    fun `the pulled services keep the base compose hardening`() {
        val lines = File(repoRoot, "docker-compose.yml").readLines()
        for (name in listOf("backend", "frontend")) {
            val index = service(lines, name)
            assertTrue(index >= 0, "docker-compose.yml no longer defines a `$name` service.")
            val securityOpt = childKey(lines, index, "security_opt")
            assertTrue(
                securityOpt >= 0 && nestedBlock(lines, securityOpt).any { it.contains("no-new-privileges:true") },
                "docker-compose.yml: `$name` no longer sets `no-new-privileges:true`, so a process in the " +
                    "container can gain privileges through a setuid binary (root CLAUDE.md §12; ADR-0047).",
            )
        }
        val frontend = service(lines, "frontend")
        val capDrop = childKey(lines, frontend, "cap_drop")
        assertTrue(
            capDrop >= 0 && nestedBlock(lines, capDrop).any { it.trim() == "- ALL" },
            "docker-compose.yml: `frontend` no longer drops all capabilities; the console needs none " +
                "(ADR-0047).",
        )
        assertTrue(
            valueAt(lines, childKey(lines, frontend, "read_only")) == "true",
            "docker-compose.yml: `frontend`'s root filesystem is no longer read-only, so a compromised " +
                "console can rewrite its own image content (ADR-0047).",
        )
        val tmpfs = childKey(lines, frontend, "tmpfs")
        val mounts = if (tmpfs < 0) emptyList() else nestedBlock(lines, tmpfs).map { it.trim() }
        assertTrue(
            mounts.containsAll(listOf("- /tmp", "- /app/.next/cache")),
            "docker-compose.yml: `frontend` mounts $mounts as tmpfs, but a read-only root leaves the Next.js " +
                "standalone server nowhere to write without `/tmp` and `/app/.next/cache` (ADR-0047).",
        )
    }

    @Test
    fun `no compose file or quick start carries a literal admin token`() {
        val assignment = Regex("""(^|[^'\w])HOMER_ADMIN_TOKEN=(\S*)""")
        val scanned = guardedFiles()
        for (file in scanned) {
            assertTrue(file.isFile, "${file.name} is missing; move this guard with the quick start rather than deleting it.")
            for ((number, line) in file.readLines().withIndex()) {
                for (match in assignment.findAll(line)) {
                    // Inside a single-quoted string it is a search pattern (`grep '^HOMER_ADMIN_TOKEN=.'`),
                    // not an assignment.
                    if (line.take(match.range.first + match.groupValues[1].length).count { it == '\'' } % 2 == 1) continue
                    val value = match.groupValues[2]
                    // An empty value, a reference/command substitution, or a placeholder (`...`) is not a
                    // credential; a value with a character in it is one somebody could paste a token into.
                    assertTrue(
                        value.isEmpty() || value.startsWith("$") || value.none { it.isLetterOrDigit() },
                        "${file.name}:${number + 1} sets HOMER_ADMIN_TOKEN to the literal `$value`. The token " +
                            "resolves from the environment (`@SYSENV`), so a literal here ships a working " +
                            "credential to everyone who clones the repository (root CLAUDE.md §12 rule 7).",
                    )
                }
            }
        }
    }

    @Test
    fun `no guarded file ships an unresolved merge-conflict marker`() {
        // `<<<<<<< HEAD`, `=======`, `>>>>>>> branch` — a merge left unresolved and committed anyway.
        val marker = Regex("""^(<{7}|={7}|>{7})($| )""")
        for (file in guardedFiles()) {
            assertTrue(file.isFile, "${file.name} is missing; move this guard with the guarded files rather than deleting it.")
            for ((number, line) in file.readLines().withIndex()) {
                assertTrue(
                    !marker.containsMatchIn(line),
                    "${file.name}:${number + 1} carries a merge-conflict marker, so the file shipped from an " +
                        "unresolved merge: every fresh clone renders the marker as part of the instructions " +
                        "or the compose stack it is meant to describe.",
                )
            }
        }
    }
}
