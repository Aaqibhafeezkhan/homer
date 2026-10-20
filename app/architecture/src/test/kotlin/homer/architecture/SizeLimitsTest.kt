package homer.architecture

import java.io.File
import kotlin.test.Test

/**
 * Size limits on the main code of :app, agentdebug and homer-runner — the complement of the CRAP check
 * (ADR-0081), which misses long code that branches little. They restate root CLAUDE.md §2 ("If you write
 * 200 lines and it could be 50, rewrite it") as numbers; app/CLAUDE.md "Architecture tests" records them.
 */
class SizeLimitsTest {
    @Test
    fun `source files have at most 600 lines`() {
        val violations =
            mainScopes().flatMap { it.files }.mapNotNull { file ->
                val lines = File(file.path).readLines().size
                if (lines <= MAX_FILE_LINES) return@mapNotNull null
                violation(file.path, 1, "${file.name}.kt has $lines lines (limit $MAX_FILE_LINES)")
            }
        assertNoViolations("Source files ≤ $MAX_FILE_LINES lines (root CLAUDE.md §2)", violations)
    }

    @Test
    fun `classes have at most 400 lines`() {
        val violations =
            mainScopes().flatMap { it.classesAndObjects(includeNested = false, includeLocal = false) }.mapNotNull { cls ->
                val lines = declarationLines(cls.text)
                if (lines <= MAX_CLASS_LINES) return@mapNotNull null
                violation(cls, "${cls.name} has $lines lines (limit $MAX_CLASS_LINES)")
            }
        assertNoViolations("Classes ≤ $MAX_CLASS_LINES lines (root CLAUDE.md §2)", violations)
    }

    @Test
    fun `function bodies have at most 60 lines`() {
        val violations =
            mainScopes().flatMap { it.functions(includeNested = true, includeLocal = false) }.mapNotNull { fn ->
                if (!fn.hasBlockBody && !fn.hasExpressionBody) return@mapNotNull null
                val lines = KotlinText.bodyLines(fn.text) ?: return@mapNotNull null
                if (lines <= MAX_BODY_LINES) return@mapNotNull null
                violation(fn, "${fn.name} has a $lines-line body (limit $MAX_BODY_LINES)")
            }
        assertNoViolations("Function bodies ≤ $MAX_BODY_LINES lines (root CLAUDE.md §2)", violations)
    }

    private fun mainScopes() = Sources.modules.map { Sources.main(it) }

    /** Lines of a declaration without its leading KDoc and annotations. */
    private fun declarationLines(text: String): Int {
        val code = KotlinText.codeOnly(text)
        val start = DECLARATION_KEYWORD.find(code)?.range?.first ?: 0
        return code
            .substring(start)
            .trimEnd()
            .lines()
            .size
    }

    private companion object {
        const val MAX_FILE_LINES = 600
        const val MAX_CLASS_LINES = 400
        const val MAX_BODY_LINES = 60
        val DECLARATION_KEYWORD = Regex("""\b(fun|class|object|interface)\b""")
    }
}
