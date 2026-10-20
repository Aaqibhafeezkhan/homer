package homer.app.executors

import homer.app.EnvConstants
import homer.app.exceptions.CommandExecutionException
import homer.app.infra.SecretRedactor
import homer.app.infra.TextCompiler
import java.nio.file.Path

/**
 * Renders the per-command, per-attempt stdout/stderr artifact file names from an
 * agent's `artifactNameTemplate` (see AGENT.md). The template's *stem* is rendered
 * via [TextCompiler] against the workspace env plus two synthetic, template-only
 * variables ([EnvConstants.CMD_INDEX], [EnvConstants.CMD_ATTEMPT]) and validated at
 * the point of use — fail closed, per root CLAUDE.md §12 — because the workspace env
 * is attacker-influenced (`resultEnvParam` writes into it) and the rendered value
 * becomes a server-local file name. The exception message names the template, never
 * the rendered stem, since the stem can embed secret env values.
 *
 * Two layers keep a secret value out of the rendered stem: [secretEnvNames]
 * is excluded from the render map, so a template referencing one leaves `{{NAME}}` literal
 * and fails the existing charset check; [secretRedactor] then catches DERIVED secrets (e.g.
 * a `resultEnvParam`-captured value copied into a non-classified var) by rejecting any stem
 * it would alter, even one that otherwise passes validation. Callers building
 * [secretRedactor] for this class must raise its needle-length floor
 * (`SecretRedactor.ARTIFACT_STEM_MIN_NEEDLE_LENGTH`) — unlike telemetry redaction, a short
 * needle here would match as a substring of nearly any stem, including the default
 * template, and hard-fail every command (§12: availability is in scope).
 */
class ArtifactNameResolver(
    private val template: String,
    private val textCompiler: TextCompiler,
    private val artifactsDir: Path,
    private val cmdIndex: Int,
    private val envProvider: () -> Map<String, String>,
    private val secretEnvNames: Set<String> = emptySet(),
    private val secretRedactor: SecretRedactor = SecretRedactor.NONE,
) {
    fun stdOut(attempt: Int): Path = resolve(attempt, "out")

    fun stdErr(attempt: Int): Path = resolve(attempt, "err")

    private fun resolve(
        attempt: Int,
        suffix: String,
    ): Path {
        val stem = render(attempt)
        validate(stem)
        return artifactsDir.resolve("$stem.$suffix")
    }

    private fun render(attempt: Int): String =
        textCompiler.replaceArguments(
            template,
            envProvider().filterKeys { it !in secretEnvNames } +
                mapOf(
                    EnvConstants.CMD_INDEX to cmdIndex.toString(),
                    EnvConstants.CMD_ATTEMPT to attempt.toString(),
                ),
        )

    private fun validate(stem: String) {
        val normalizedRoot = artifactsDir.normalize()
        val syntaxValid =
            SAFE_STEM.matches(stem) &&
                stem.length <= MAX_STEM_LENGTH &&
                stem != "." &&
                stem != ".." &&
                artifactsDir.resolve("$stem.out").normalize().parent == normalizedRoot
        if (!syntaxValid) {
            throw CommandExecutionException(
                message = "artifactNameTemplate '$template' rendered an invalid artifact file name",
                exitCode = -1,
                command = template,
            )
        }
        // Defense in depth for DERIVED secrets (e.g. a resultEnvParam-captured value
        // copied into a non-secret-classified var): reject any stem the run's
        // SecretRedactor would alter, even though it passed the syntax check above. A
        // distinct message helps operators diagnose this case without naming the value.
        if (secretRedactor.redact(stem) != stem) {
            throw CommandExecutionException(
                message = "artifactNameTemplate '$template' rendered a name matching a secret value",
                exitCode = -1,
                command = template,
            )
        }
    }

    private companion object {
        val SAFE_STEM = Regex("^[A-Za-z0-9._-]+$")
        const val MAX_STEM_LENGTH = 200
    }
}
