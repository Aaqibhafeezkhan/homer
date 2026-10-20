package homer.app.executors

import homer.app.EnvConstants
import homer.app.exceptions.CommandExecutionException
import homer.app.infra.DefaultTextCompiler
import homer.app.infra.SecretRedactor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Unit tests for [ArtifactNameResolver] — the per-command, per-attempt stdout/stderr
 * artifact naming used by [AgentExecutor] and [homer.app.executors.llm.LlmHarnessExecutor]'s
 * retry loop.
 */
class ArtifactNameResolverTest {
    private val textCompiler = DefaultTextCompiler()

    private fun resolver(
        template: String,
        artifactsDir: Path,
        cmdIndex: Int = 3,
        env: Map<String, String> = emptyMap(),
        secretEnvNames: Set<String> = emptySet(),
        secretRedactor: SecretRedactor = SecretRedactor.NONE,
    ): ArtifactNameResolver =
        ArtifactNameResolver(
            template = template,
            textCompiler = textCompiler,
            artifactsDir = artifactsDir,
            cmdIndex = cmdIndex,
            envProvider = { env },
            secretEnvNames = secretEnvNames,
            secretRedactor = secretRedactor,
        )

    @Test
    fun `default template renders cmd_index out and err`(
        @TempDir artifactsDir: Path,
    ) {
        val r = resolver("cmd_{{${EnvConstants.CMD_INDEX}}}", artifactsDir, cmdIndex = 7)
        assertEquals(artifactsDir.resolve("cmd_7.out"), r.stdOut(0))
        assertEquals(artifactsDir.resolve("cmd_7.err"), r.stdErr(0))
    }

    @Test
    fun `template using HMR_CMD_ATTEMPT varies per attempt`(
        @TempDir artifactsDir: Path,
    ) {
        val r = resolver("cmd_{{${EnvConstants.CMD_INDEX}}}_a{{${EnvConstants.CMD_ATTEMPT}}}", artifactsDir, cmdIndex = 2)
        assertEquals(artifactsDir.resolve("cmd_2_a0.out"), r.stdOut(0))
        assertEquals(artifactsDir.resolve("cmd_2_a1.out"), r.stdOut(1))
        assertEquals(artifactsDir.resolve("cmd_2_a0.err"), r.stdErr(0))
        assertEquals(artifactsDir.resolve("cmd_2_a1.err"), r.stdErr(1))
    }

    @Test
    fun `template referencing a workspace env var renders it`(
        @TempDir artifactsDir: Path,
    ) {
        val r = resolver("{{MY_LABEL}}", artifactsDir, env = mapOf("MY_LABEL" to "release-build"))
        assertEquals(artifactsDir.resolve("release-build.out"), r.stdOut(0))
    }

    // ========== Denial paths (root CLAUDE.md §12: fail closed, message omits the stem) ==========

    @Test
    fun `env value containing a slash is rejected`(
        @TempDir artifactsDir: Path,
    ) {
        val r = resolver("{{EVIL}}", artifactsDir, env = mapOf("EVIL" to "a/b"))
        val ex = assertThrows<CommandExecutionException> { r.stdOut(0) }
        assertFalse(ex.message!!.contains("a/b"), "message must not leak the rendered stem: ${ex.message}")
    }

    @Test
    fun `env value attempting path traversal is rejected`(
        @TempDir artifactsDir: Path,
    ) {
        val r = resolver("{{EVIL}}", artifactsDir, env = mapOf("EVIL" to ".."))
        val ex = assertThrows<CommandExecutionException> { r.stdOut(0) }
        assertFalse(ex.message!!.contains(".."), "message must not leak the rendered stem: ${ex.message}")
    }

    @Test
    fun `blank render is rejected`(
        @TempDir artifactsDir: Path,
    ) {
        val r = resolver("{{EVIL}}", artifactsDir, env = mapOf("EVIL" to ""))
        assertThrows<CommandExecutionException> { r.stdOut(0) }
    }

    @Test
    fun `unresolved template reference is rejected (fails via charset)`(
        @TempDir artifactsDir: Path,
    ) {
        // No MISSING key in env: DefaultTextCompiler leaves "{{MISSING}}" as a literal
        // (braces are outside the safe charset), so this fails closed at validation
        // rather than silently producing a file named "{{MISSING}}.out".
        val r = resolver("{{MISSING}}", artifactsDir)
        assertThrows<CommandExecutionException> { r.stdOut(0) }
    }

    @Test
    fun `stem over 200 characters is rejected`(
        @TempDir artifactsDir: Path,
    ) {
        val longValue = "a".repeat(201)
        val r = resolver("{{EVIL}}", artifactsDir, env = mapOf("EVIL" to longValue))
        val ex = assertThrows<CommandExecutionException> { r.stdOut(0) }
        assertFalse(ex.message!!.contains(longValue), "message must not leak the rendered stem: ${ex.message}")
    }

    @Test
    fun `exactly 200 characters is accepted`(
        @TempDir artifactsDir: Path,
    ) {
        val value = "a".repeat(200)
        val r = resolver("{{OK}}", artifactsDir, env = mapOf("OK" to value))
        assertEquals(artifactsDir.resolve("$value.out"), r.stdOut(0))
    }

    @Test
    fun `stem of exactly dot-dot is rejected even though it passes the charset check`(
        @TempDir artifactsDir: Path,
    ) {
        // ".." matches ^[A-Za-z0-9._-]+$ but must still be rejected by the dedicated check.
        val r = resolver("..", artifactsDir)
        assertThrows<CommandExecutionException> { r.stdOut(0) }
    }

    @Test
    fun `exception message names the template, not the stem`(
        @TempDir artifactsDir: Path,
    ) {
        val r = resolver("{{EVIL}}", artifactsDir, env = mapOf("EVIL" to "a/b/c"))
        val ex = assertThrows<CommandExecutionException> { r.stdOut(0) }
        assertEquals(true, ex.message!!.contains("{{EVIL}}"))
    }

    // ========== Secret exposure via rendered stem ==========

    @Test
    fun `template referencing a secret-classified env name fails closed`(
        @TempDir artifactsDir: Path,
    ) {
        // TOKEN is excluded from the render map because it is secret-classified, so
        // "{{TOKEN}}" is left as a literal by the compiler (missing key) and then
        // rejected by the charset check — never reaching the child process's stdout
        // filename, and the exception message must not carry the secret value.
        val r =
            resolver(
                "{{TOKEN}}",
                artifactsDir,
                env = mapOf("TOKEN" to "s3cr3t-value"),
                secretEnvNames = setOf("TOKEN"),
            )
        val ex = assertThrows<CommandExecutionException> { r.stdOut(0) }
        assertFalse(ex.message!!.contains("s3cr3t-value"), "message must not leak the secret value: ${ex.message}")
        // Layer 1 (name exclusion) fails via the ordinary syntax/containment message,
        // not the layer-2 secret-value message — MINOR 4: the two are distinguishable.
        assertEquals(true, ex.message!!.contains("rendered an invalid artifact file name"))
    }

    @Test
    fun `rendered stem embedding a derived secret value fails closed`(
        @TempDir artifactsDir: Path,
    ) {
        // STEM_VAR is not itself secret-classified, but a resultEnvParam capture copied
        // a secret's value into it — the run's SecretRedactor still recognizes the value
        // and the stem must be rejected even though it passes the charset check.
        val redactor = SecretRedactor.from(mapOf("REAL_SECRET" to "s3cr3t-value"), setOf("REAL_SECRET"))
        val r =
            resolver(
                "{{STEM_VAR}}",
                artifactsDir,
                env = mapOf("STEM_VAR" to "s3cr3t-value"),
                secretRedactor = redactor,
            )
        val ex = assertThrows<CommandExecutionException> { r.stdOut(0) }
        assertFalse(ex.message!!.contains("s3cr3t-value"), "message must not leak the secret value: ${ex.message}")
        // Layer 2 (value screen) gets its own message, distinct from layer 1's.
        assertEquals(true, ex.message!!.contains("rendered a name matching a secret value"))
    }
}
