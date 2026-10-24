package homer.app

import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards what the slim server image promises (ADR-0094): the tools the server itself runs, and none
 * of the agent toolchain, which only the full `runner` stage built `FROM slim` adds.
 */
class SlimImageContractTest {
    /** Each stage of app/Dockerfile by lowercased name: its base and its instructions, `\`-continued lines joined. */
    private val stages: Map<String, Pair<String, List<String>>> =
        File(repoRoot, "app/Dockerfile")
            .readText()
            .replace(Regex("""\\\n"""), " ")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .fold(mutableListOf<Pair<List<String>, MutableList<String>>>()) { acc, line ->
                if (line.startsWith("FROM ", ignoreCase = true)) {
                    acc += line.split(Regex("""\s+""")).drop(1).filterNot { it.startsWith("--") } to mutableListOf()
                } else {
                    acc.lastOrNull()?.second?.add(line)
                }
                acc
            }.filter { (from, _) -> from.size == 3 && from[1].equals("AS", ignoreCase = true) }
            .associate { (from, body) -> from[2].lowercase() to (from[0] to body) }

    private fun stage(name: String) = stages[name] ?: error("app/Dockerfile has no `$name` stage; move this guard with it.")

    /** The packages the stage's `apk add` instructions install. */
    private fun apkPackages(body: List<String>): Set<String> =
        body
            .filter { it.startsWith("RUN apk add") }
            .flatMap { run ->
                run
                    .split("&&")
                    .first()
                    .split(Regex("""\s+"""))
                    .drop(3) // RUN apk add
            }.filterNot { it.startsWith("-") || it.startsWith(".") }
            .toSet()

    @Test
    fun `the slim stage installs what the server runs and no agent toolchain`() {
        val (_, body) = stage("slim")
        val packages = apkPackages(body)
        val required = setOf("bash", "curl", "su-exec", "python3", "py3-pip")
        assertTrue(
            packages.containsAll(required),
            "the slim stage installs $packages; ADR-0094 promises $required (shell, entrypoint privilege drop, " +
                "health check, the server-side homer_langgraph inspect and state calls).",
        )
        val toolchain = setOf("git", "git-lfs", "nodejs", "npm", "openssh-client", "glab", "jq", "unzip")
        assertTrue(
            packages.intersect(toolchain).isEmpty(),
            "the slim stage installs ${packages.intersect(toolchain)}, agent toolchain the slim image promises not " +
                "to ship (ADR-0094); add it to the full `runner` stage instead.",
        )
        assertTrue(body.none { it.contains("npm install") }, "the slim stage must not install npm packages (ADR-0094).")
        assertTrue(
            body.any { it.startsWith("RUN pip install") && it.contains("homer_langgraph") },
            "the slim stage no longer installs homer_langgraph, which the server runs for LangGraph inspect and state.",
        )
    }

    @Test
    fun `the full image is the slim one plus the agent CLIs`() {
        val (base, body) = stage("runner")
        assertEquals("slim", base.lowercase(), "the full `runner` stage must be built FROM slim so it stays a superset.")
        val install = body.firstOrNull { it.contains("npm install -g") }.orEmpty()
        assertTrue(
            install.contains("@anthropic-ai/claude-code") && install.contains("@openai/codex"),
            "the full `runner` stage no longer installs the Claude Code and Codex CLIs (found `$install`).",
        )
    }
}
