package homer.app

import homer.app.fixtures.DockerfileStages
import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the agent-CLI version pins (CWE-1357). The `runner` stage installs three global npm
 * packages that run agent prompts with the workspace's secrets in their environment, so each
 * build must ship a release someone chose, not whatever the registry published that day.
 *
 * Each pin is declared three times — `app/Dockerfile`, the `docker-compose.build.yml` build
 * overlay and the `DOCKER.md` build-arguments table — and this test is what keeps the three
 * from drifting apart.
 */
class CliVersionPinTest {
    /** Build argument → the npm package it pins. */
    private val packages =
        mapOf(
            "CLAUDE_CODE_VERSION" to "@anthropic-ai/claude-code",
            "CODEX_VERSION" to "@openai/codex",
            "OPENCODE_VERSION" to "opencode-ai",
        )

    /** An exact release: no `latest`, no `^`/`~`/`*` range, no dist-tag. */
    private val exactRelease = Regex("""^\d+\.\d+\.\d+$""")

    /** The `ARG <name>=<value>` default declared in the Dockerfile's `runner` stage. */
    private fun dockerfileDefault(name: String): String? =
        DockerfileStages.app
            .stage("runner")
            .instructions
            .firstOrNull { it.startsWith("ARG $name=") }
            ?.removePrefix("ARG $name=")
            ?.trim()

    /** The `:-` default the compose build overlay forwards for [name]. */
    private fun composeDefault(name: String): String? =
        Regex("""(?m)^\s*$name:\s*\$\{$name:-([^}]*)\}\s*$""")
            .find(File(repoRoot, "docker-compose.build.yml").readText())
            ?.groupValues
            ?.get(1)

    /** The Default column of the `DOCKER.md` build-arguments table row for [name]. */
    private fun dockerMdDefault(name: String): String? =
        Regex("""(?m)^\|\s*`$name`\s*\|\s*`([^`]*)`\s*\|""")
            .find(File(repoRoot, "DOCKER.md").readText())
            ?.groupValues
            ?.get(1)

    @Test
    fun `every agent CLI is pinned to one exact release in the Dockerfile, compose and DOCKER md`() {
        for ((name, pkg) in packages) {
            val pinned = dockerfileDefault(name)
            assertTrue(
                pinned != null && exactRelease.matches(pinned),
                "app/Dockerfile's runner stage must declare `ARG $name=<x.y.z>`; found `$pinned`. " +
                    "`latest`, a range or a dist-tag ships a different $pkg on every build (CWE-1357).",
            )
            assertEquals(
                pinned,
                composeDefault(name),
                "docker-compose.build.yml must forward the same $name default as app/Dockerfile, so compose and " +
                    "the Dockerfile cannot drift apart.",
            )
            assertEquals(
                pinned,
                dockerMdDefault(name),
                "DOCKER.md's build-arguments table must document the same $name default as app/Dockerfile.",
            )
        }
    }

    @Test
    fun `the install layer installs each CLI at its build arg and names no credential`() {
        val install =
            DockerfileStages.app
                .stage("runner")
                .instructions
                .firstOrNull { it.contains("npm install -g") }
                ?: error("the runner stage no longer installs the agent CLIs; move this guard with it.")
        for ((name, pkg) in packages) {
            assertTrue(
                install.contains("$pkg@\${$name}"),
                "the runner stage must install `$pkg` at its `$name` build arg, so the pin it declares is the " +
                    "version it installs; found `$install`.",
            )
        }
        // A credential in the install instruction would land in `docker history` of the pushed
        // image (root CLAUDE.md §12 rule 7). The three version args are plain releases.
        val credential = Regex("""(?i)(_auth|npm_config_|registry\.json|password|secret|api[_-]?key|token)""")
        assertFalse(
            credential.containsMatchIn(install),
            "the agent-CLI install instruction names a credential-looking build arg; an ARG is readable in " +
                "`docker history`. Use a BuildKit secret instead: `$install`",
        )
    }
}
