package homer.app

import homer.app.fixtures.DockerfileStages
import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the agent-CLI version pins (CWE-1357). The `runner` stage installs three global npm
 * packages that run agent prompts with the workspace's secrets in their environment, so each
 * build must ship a release someone chose, not whatever the registry published that day.
 *
 * Each pin is declared three times — `app/Dockerfile`, the `docker-compose.build.yml` build
 * overlay and the `DOCKER.md` build-arguments table — and this test is what keeps the three
 * from drifting apart. The Claude Code pin is also tied to the newest committed capture, so a
 * bump cannot ship a release no recorded stream was taken from (ADR-0057).
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

    /**
     * The Claude Code release a capture was recorded from, as `name to version`. The captures are
     * the streams ADR-0057 verifies a bump with, so a pin no capture reports is a pin nothing tested.
     */
    private fun captureVersions(): List<Pair<String, String>> =
        File(
            requireNotNull(javaClass.getResource("/claude")) { "missing test resource directory /claude" }.toURI(),
        ).listFiles { it.extension == "jsonl" }
            .orEmpty()
            .mapNotNull { capture -> versionIn(capture.readText())?.let { capture.name to it } }

    private fun versionIn(capture: String): String? =
        Regex(""""claude_code_version"\s*:\s*"([^"]+)"""")
            .find(capture)
            ?.groupValues
            ?.get(1)

    /** A release as a comparable key; a value that is not `x.y.z` sorts below every release. */
    private fun releaseKey(version: String): String =
        version.split('.').joinToString(".") { (it.toIntOrNull() ?: -1).toString().padStart(8, '0') }

    @Test
    fun `the Dockerfile pin is the release the newest capture was recorded from`() {
        val newest =
            assertNotNull(
                captureVersions().maxByOrNull { (_, version) -> releaseKey(version) },
                "no capture under app/app/src/test/resources/claude/ reports a claude_code_version; " +
                    "the pinned CLI would then be verified by no real stream (ADR-0057).",
            )
        assertEquals(
            dockerfileDefault("CLAUDE_CODE_VERSION"),
            newest.second,
            "app/Dockerfile must pin the release the newest capture (${newest.first}) was recorded from; " +
                "otherwise a bump ships a CLI no capture covers and the harness tests keep verifying an older " +
                "one. A bump needs a capture from the new release (ADR-0057).",
        )
    }

    @Test
    fun `every DOCKER md build example uses the pinned Claude Code release`() {
        val pinned = dockerfileDefault("CLAUDE_CODE_VERSION")
        val examples =
            Regex("""CLAUDE_CODE_VERSION=(\S+)""")
                .findAll(File(repoRoot, "DOCKER.md").readText())
                .map { it.groupValues[1] }
                .toList()
        assertTrue(
            examples.isNotEmpty(),
            "DOCKER.md no longer shows a CLAUDE_CODE_VERSION build example; move this guard with the section.",
        )
        for (example in examples) {
            assertEquals(
                pinned,
                example,
                "DOCKER.md's build examples must use the release app/Dockerfile pins; found `$example`.",
            )
        }
    }
}
