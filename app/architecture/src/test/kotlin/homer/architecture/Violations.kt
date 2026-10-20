package homer.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.provider.KoLocationProvider
import com.lemonappdev.konsist.api.provider.KoPathProvider
import java.io.File
import kotlin.test.fail

/** Source sets the rules scan, by module directory relative to the Gradle root (`app/`). */
internal object Sources {
    /** :app and the two modules built on it; their tests mirror their packages too (root CLAUDE.md §6). */
    val modules = listOf("app", "agentdebug", "homer-runner")

    fun main(module: String): KoScope = Konsist.scopeFromDirectory("$module/src/main/kotlin")

    fun test(module: String): KoScope = Konsist.scopeFromDirectory("$module/src/test/kotlin")

    val appMain: KoScope get() = main("app")
    val appTest: KoScope get() = test("app")

    private val repoRoot: File =
        File(System.getProperty("homer.repoRoot") ?: error("homer.repoRoot system property is not set"))

    fun repoPath(absolutePath: String): String = File(absolutePath).relativeTo(repoRoot).invariantSeparatorsPath
}

private val lineSuffix = Regex(""":(\d+):\d+$""")

/** One finding, rendered as `VIOLATION <repo-relative path>:<line> <text>` for the quality gate (ADR-0082). */
internal data class Violation(
    val path: String,
    val line: Int,
    val text: String,
) {
    override fun toString(): String = "VIOLATION $path:$line $text"
}

internal fun violation(
    absolutePath: String,
    line: Int,
    text: String,
): Violation = Violation(Sources.repoPath(absolutePath), line, text)

internal fun <T> violation(
    declaration: T,
    text: String,
): Violation where T : KoPathProvider, T : KoLocationProvider {
    val line =
        lineSuffix
            .find(declaration.location)
            ?.groupValues
            ?.get(1)
            ?.toInt() ?: 1
    return violation(declaration.path, line, text)
}

internal fun lineOf(
    text: String,
    offset: Int,
): Int = text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' } + 1

internal fun assertNoViolations(
    rule: String,
    violations: List<Violation>,
) {
    if (violations.isNotEmpty()) {
        val unique = violations.distinct().sortedWith(compareBy({ it.path }, { it.line }))
        fail("$rule: ${unique.size} violation(s)\n" + unique.joinToString("\n"))
    }
}
