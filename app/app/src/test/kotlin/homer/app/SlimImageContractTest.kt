package homer.app

import homer.app.fixtures.DockerfileStages
import homer.app.fixtures.DockerfileStages.Companion.apkPackages
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards what the slim server image promises (ADR-0094): the tools the server itself runs, and none
 * of the agent toolchain, which only the full `runner` stage built `FROM slim` adds.
 */
class SlimImageContractTest {
    private val dockerfile = DockerfileStages.app

    /** The agent toolchain: absent from the slim stage, installed by the full one. */
    private val toolchain = setOf("git", "git-lfs", "nodejs", "npm", "openssh-client", "glab", "jq", "unzip")

    @Test
    fun `the slim stage installs what the server runs and no agent toolchain`() {
        val slim = dockerfile.stage("slim")
        val packages = apkPackages(slim)
        val required = setOf("bash", "curl", "su-exec", "python3", "py3-pip")
        assertTrue(
            packages.containsAll(required),
            "the slim stage installs $packages; ADR-0094 promises $required (shell, entrypoint privilege drop, " +
                "health check, the server-side homer_langgraph inspect and state calls).",
        )
        assertTrue(
            packages.intersect(toolchain).isEmpty(),
            "the slim stage installs ${packages.intersect(toolchain)}, agent toolchain the slim image promises not " +
                "to ship (ADR-0094); add it to the full `runner` stage instead.",
        )
        assertTrue(slim.instructions.none { it.contains("npm install") }, "the slim stage must not install npm packages (ADR-0094).")
        assertTrue(
            slim.instructions.any { it.startsWith("RUN pip install") && it.contains("homer_langgraph") },
            "the slim stage no longer installs homer_langgraph, which the server runs for LangGraph inspect and state.",
        )
    }

    @Test
    fun `the full image is the slim one plus the agent toolchain`() {
        val runner = dockerfile.stage("runner")
        assertEquals("slim", runner.base.lowercase(), "the full `runner` stage must be built FROM slim so it stays a superset.")
        val missing = toolchain - apkPackages(runner)
        assertTrue(missing.isEmpty(), "the full `runner` stage no longer installs $missing, which DOCKER.md says it adds.")
        val install = runner.instructions.firstOrNull { it.contains("npm install -g") }.orEmpty()
        assertTrue(
            install.contains("@anthropic-ai/claude-code") && install.contains("@openai/codex"),
            "the full `runner` stage no longer installs the Claude Code and Codex CLIs (found `$install`).",
        )
    }
}
