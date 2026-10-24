package homer.app

import homer.app.fixtures.DockerfileStages
import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Denial-path guard (root CLAUDE.md §12 rule 8): no compose file binds JDWP off
 * container loopback or publishes port 5005, and the opt-in overlay is not
 * dropped. CWE-489. See ADR-0076 and DOCKER.md § Development Mode.
 */
class JdwpNetworkExposureTest {
    /** The whole `-agentlib:jdwp=…` token; it carries no whitespace, so this ends at the next space. */
    private val jdwpClause = Regex("""-agentlib:jdwp=\S*""")

    /** The `address=` value of a JDWP clause, e.g. `127.0.0.1:5005` or `*:5005`. */
    private val jdwpAddress = Regex("""address=([^\s"']+)""")

    /** Only an explicit loopback host passes; a bare port or `*`/`0.0.0.0` is a wildcard bind. */
    private val loopbackAddress = Regex("""^(127\.0\.0\.1|localhost|\[::1\]):\d+$""")

    /** A `key:` (or `- key:`) line of a compose mapping, e.g. `backend:` or `- target: 5005`. */
    private val mappingKey = Regex("""^\s*-?\s*[\w.-]+:(\s.*)?$""")

    /** Every root compose file, including any opt-in overlay that arrives later. */
    private fun composeFiles(): List<File> =
        repoRoot
            .listFiles { f -> f.isFile && f.name.startsWith("docker-compose") && f.name.endsWith(".yml") }
            .orEmpty()
            .sortedBy { it.name }

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

    /** Indices of `ports:` blocks nested under a service of the top-level `services:` block. */
    private fun servicePortsBlocks(lines: List<String>): List<Int> {
        val services = lines.indexOfFirst { indentOf(it) == 0 && it.trimStart() == "services:" }
        if (services < 0) return emptyList()
        return childKeys(lines, services).flatMap { service ->
            childKeys(lines, service).filter { lines[it].trimStart().startsWith("ports:") }
        }
    }

    /** The `ports:` entries of the block at [index]: one list per entry — its `- ` line plus any long-syntax key lines. */
    private fun portEntries(
        lines: List<String>,
        index: Int,
    ): List<List<String>> {
        val entries = mutableListOf<MutableList<String>>()
        for (line in nestedBlock(lines, index)) {
            if (line.trimStart().startsWith("- ")) entries += mutableListOf(line) else entries.lastOrNull()?.add(line)
        }
        return entries
    }

    /**
     * The container-side port of one compose `ports:` entry — short syntax
     * (`host:container`, `ip:host:container`, bare) or long syntax (`target:` / `published:`).
     *
     * Inline YAML comments are stripped first: a port value never contains ` #`, but a
     * trailing `# …` would otherwise ride into the returned port and defeat the `5005` guard.
     *
     * Long syntax reads the `target:` key, not whichever of `target`/`published` comes first:
     * YAML mapping order is free, so a `published:` listed first must not stand in for the
     * container-side port (CWE-693).
     */
    private fun containerPortOf(entry: List<String>): String {
        val uncommented = entry.map { it.substringBefore(" #").trim() }
        val longSyntax = Regex("""^\s*-?\s*target:\s*(\S+)\s*$""")
        uncommented.firstOrNull { longSyntax.containsMatchIn(it) }?.let {
            return longSyntax
                .find(it)!!
                .groupValues[1]
                .trim('"', '\'')
                .substringBefore('/')
        }
        val spec =
            uncommented
                .first()
                .removePrefix("-")
                .trim()
                .trim('"', '\'')
                .substringBefore('/')
        return spec.split(':').last().trim()
    }

    /** True when [port] is container port 5005 or a `lo-hi` range covering it (CWE-693). */
    private fun exposesJdwpPort(port: String): Boolean {
        val bounds = port.split('-', limit = 2).map { it.trim() }
        val (lo, hi) = bounds.mapNotNull { it.toIntOrNull() }.let { if (it.size == 2) it else null } ?: return port == "5005"
        return lo <= 5005 && 5005 <= hi
    }

    private fun contentLines(compose: File) = compose.readLines().filterNot { isFiller(it) }

    /** The value of the single `- JAVA_OPTS=…` entry of a compose file's backend environment. */
    private fun javaOptsValue(compose: File): String =
        contentLines(compose)
            .mapNotNull { Regex("""^\s*- JAVA_OPTS=(.*)$""").find(it)?.groupValues?.get(1) }
            .single()

    @Test
    fun `no compose file binds JDWP off container loopback`() {
        for (compose in composeFiles()) {
            for (line in contentLines(compose)) {
                val clause = jdwpClause.find(line)?.value ?: continue
                val address = jdwpAddress.find(clause)?.groupValues?.get(1)
                assertTrue(
                    address != null && loopbackAddress.matches(address),
                    "${compose.name} loads the JDWP agent with `$clause`, whose bind address is not " +
                        "container loopback. A wildcard (`*`) or host-less bind is reachable from every " +
                        "peer on homer-network, so the unauthenticated agent is remote code execution " +
                        "there (CWE-489); bind `address=127.0.0.1:5005` and attach " +
                        "through a tunnel instead (ADR-0076, DOCKER.md § Development Mode).",
                )
            }
        }
    }

    @Test
    fun `no compose file publishes container port 5005`() {
        for (compose in composeFiles()) {
            val lines = compose.readLines()
            for (ports in servicePortsBlocks(lines)) {
                for (entry in portEntries(lines, ports)) {
                    assertTrue(
                        !exposesJdwpPort(containerPortOf(entry)),
                        "${compose.name} publishes container port 5005 (`${entry.joinToString(" ").trim()}`). " +
                            "A published port NATs to the container's eth0 address, so it re-exposes the " +
                            "unauthenticated JDWP agent to homer-network even under a loopback host " +
                            "binding (CWE-489); attach through a tunnel instead (ADR-0076).",
                    )
                }
            }
        }
    }

    @Test
    fun `the ports guard parses long syntax and scans only service-scoped ports blocks`() {
        val shortSyntax = listOf("    ports:", "      - \"127.0.0.1:5005:5005\"")
        val longSyntax =
            listOf(
                "    ports:",
                "      - target: 5005",
                "        published: \"5005\"",
                "        host_ip: 127.0.0.1",
            )
        // Inline YAML comments must not ride into the parsed port: a future
        // `- "127.0.0.1:9000:5005"  # jdwp tunnel` entry must still be flagged.
        val commentedShortSyntax = listOf("    ports:", "      - \"127.0.0.1:9000:5005\"  # jdwp tunnel")
        val commentedLongSyntax =
            listOf(
                "    ports:",
                "      - target: 5005  # jdwp",
                "        published: \"5005\"  # jdwp tunnel",
                "        host_ip: 127.0.0.1",
            )
        for (block in listOf(shortSyntax, longSyntax, commentedShortSyntax, commentedLongSyntax)) {
            val lines = listOf("services:", "  backend:") + block
            val ports = servicePortsBlocks(lines)
            assertTrue(
                ports.size == 1 && portEntries(lines, ports.first()).any { exposesJdwpPort(containerPortOf(it)) },
                "the guard must flag a published 5005 in a service-scoped `ports:` block, short or long " +
                    "syntax; it parsed $block as ${ports.map { portEntries(lines, it) }}",
            )
        }
        // CWE-693: YAML mapping order is free, so the guard must read the
        // `target:` key even when `published:` is listed first, and a range covering 5005
        // must be flagged, not matched literally.
        val publishedFirst = listOf("    ports:", "      - published: 9000", "        target: 5005")
        val range = listOf("    ports:", "      - \"127.0.0.1:9000:5005-5010\"")
        val longRange =
            listOf(
                "    ports:",
                "      - target: 5005-5010",
                "        published: \"9000\"",
            )
        for (block in listOf(publishedFirst, range, longRange)) {
            val lines = listOf("services:", "  backend:") + block
            val ports = servicePortsBlocks(lines)
            assertTrue(
                ports.size == 1 && portEntries(lines, ports.first()).any { exposesJdwpPort(containerPortOf(it)) },
                "the guard must flag a publish exposing container port 5005 whichever way the entry " +
                    "spells it (a `target:` after `published:`, or a range covering 5005); it parsed " +
                    "$block as ${ports.map { portEntries(lines, it) }}",
            )
        }
        // A `ports:` key outside a service (top-level or mis-indented) is not a publish.
        val stray = listOf("ports:", "  - \"127.0.0.1:5005:5005\"")
        assertTrue(
            servicePortsBlocks(stray).isEmpty(),
            "the guard scanned a `ports:` block that no service owns",
        )
    }

    @Test
    fun `the default dev stack starts no JDWP listener`() {
        val dev = File(repoRoot, "docker-compose.dev.yml")
        assertTrue(dev.isFile, "docker-compose.dev.yml is missing.")
        assertTrue(
            contentLines(dev).none { jdwpClause.containsMatchIn(it) },
            "docker-compose.dev.yml loads the JDWP agent, so a plain `docker compose ... up` starts " +
                "the unauthenticated debug listener (CWE-489). Keep the agent in " +
                "docker-compose.debug.yml only (ADR-0076).",
        )
    }

    @Test
    fun `the opt-in debug overlay exists and loads the agent`() {
        val debug = File(repoRoot, "docker-compose.debug.yml")
        assertTrue(
            debug.isFile,
            "docker-compose.debug.yml is missing, so the JDWP denial checks above pass vacuously " +
                "while the host-side IDE debug path is silently gone.",
        )
        // Scope to the surface DOCKER.md documents — services.backend.environment —
        // not any line of the file: a clause nested under another service or key
        // would merge no agent into `backend` while this check stayed green.
        val lines = contentLines(debug)
        val services = lines.indexOfFirst { indentOf(it) == 0 && it.trimStart() == "services:" }
        assertTrue(services >= 0, "docker-compose.debug.yml has no `services:` block.")
        val environment =
            childKeys(lines, services)
                .firstOrNull { lines[it].trimStart().startsWith("backend:") }
                ?.let { backend -> childKeys(lines, backend).firstOrNull { lines[it].trimStart().startsWith("environment:") } }
        assertTrue(
            environment != null,
            "docker-compose.debug.yml has no services.backend.environment block, so the opt-in " +
                "debug path is broken.",
        )
        assertTrue(
            nestedBlock(lines, environment).any { jdwpClause.containsMatchIn(it) },
            "docker-compose.debug.yml does not load the JDWP agent in services.backend.environment, " +
                "so the opt-in debug path is broken.",
        )
    }

    @Test
    fun `the debug overlay keeps the dev JAVA_OPTS base`() {
        // Compose's list-style `environment` overrides a base value wholesale rather
        // than merging, so the overlay re-specifies the dev value plus the agent
        // clause; pin that it keeps the dev flags as its prefix so the two copies
        // cannot drift apart silently.
        val dev = javaOptsValue(File(repoRoot, "docker-compose.dev.yml"))
        val debug = javaOptsValue(File(repoRoot, "docker-compose.debug.yml"))
        assertTrue(
            debug.startsWith(dev),
            "docker-compose.debug.yml's JAVA_OPTS (`$debug`) no longer starts with the dev overlay's " +
                "value (`$dev`): the debug stack would run a JVM configuration plain dev does not " +
                "(compose list env overrides rather than merges).",
        )
    }

    @Test
    fun `the documented tunnel reaches the loopback-bound agent`() {
        // The attach path in DOCKER.md opens /dev/tcp/127.0.0.1/5005 inside the
        // backend container, so the overlay's agent must bind exactly that loopback
        // port, and the runtime image must ship a bash to expand /dev/tcp.
        val debug = File(repoRoot, "docker-compose.debug.yml")
        val addresses =
            contentLines(debug).mapNotNull { line ->
                jdwpClause.find(line)?.value?.let { clause -> jdwpAddress.find(clause)?.groupValues?.get(1) }
            }
        assertTrue(
            addresses.size == 1 && addresses.single() == "127.0.0.1:5005",
            "the documented tunnel opens /dev/tcp/127.0.0.1/5005, so docker-compose.debug.yml must " +
                "load the agent on exactly one loopback address `127.0.0.1:5005` (found $addresses); " +
                "a different bind silently breaks the IDE attach path (ADR-0076, DOCKER.md § " +
                "Development Mode).",
        )
        val apkPackages = DockerfileStages.app.chain("runner").flatMap { DockerfileStages.apkPackages(it) }
        assertTrue(
            "bash" in apkPackages,
            "the tunnel's container half runs `bash -c \"exec 3<>/dev/tcp/127.0.0.1/5005 …\"`, so the " +
                "runtime image (app/Dockerfile's runner stage or a stage it is built FROM) must install bash; without it the " +
                "documented attach path fails (ADR-0076, DOCKER.md § Development Mode).",
        )
        val dockerMd = File(repoRoot, "DOCKER.md").readText()
        assertTrue(
            dockerMd.contains("/dev/tcp/127.0.0.1/5005") && dockerMd.contains("docker exec -i homer-backend bash"),
            "DOCKER.md no longer documents the `docker exec` tunnel this test pins; update the test, " +
                "the doc and ADR-0076 together (root CLAUDE.md §9).",
        )
    }
}
