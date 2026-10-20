import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhileExpression
import org.w3c.dom.Element
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow

/**
 * CRAP (Change Risk Anti-Patterns) score per Kotlin function body: `cc² × (1 − cov)³ + cc`, with
 * cyclomatic complexity `cc` counted from the source and coverage `cov` from Kover's per-line XML
 * report. The console computes the same metric the same way (`console/scripts/crap.mjs`); the
 * definition and the warning-then-required rollout are ADR-0081.
 *
 * Everything but [Parser] works on already-read text so it stays unit testable; the Gradle task
 * (`CrapReportTask`) owns the file reads and writes. This object loads compiler classes, so it runs
 * only inside that task's isolated worker classloader (see `app/buildSrc/build.gradle.kts`).
 */
object CrapScore {
    const val DEFAULT_THRESHOLD = 30.0

    /** One scored function body; [depth] orders nested units after the ones enclosing them. */
    data class CodeUnit(
        val name: String,
        val startLine: Int,
        val endLine: Int,
        val complexity: Int,
        val depth: Int,
    )

    /** Kover's instruction counters for one source line. */
    data class LineCounts(
        val missed: Int,
        val covered: Int,
    )

    data class Scored(
        val path: String,
        val unit: CodeUnit,
        val coverage: Double,
        val crap: Double,
    )

    data class Result(
        val scored: List<Scored>,
        val unscored: Int,
        val threshold: Double,
    ) {
        val violations: List<Scored> get() = scored.filter { it.crap > threshold }.sortedByDescending { it.crap }
    }

    fun crap(
        complexity: Int,
        coverage: Double,
    ): Double = complexity.toDouble().pow(2) * (1 - coverage).pow(3) + complexity

    /**
     * Parses Kotlin source into PSI without resolving anything; close it to release the environment.
     * Setting up the environment takes internal compiler APIs, hence the opt-ins.
     */
    @OptIn(CompilerConfiguration.Internals::class, ExperimentalCompilerApi::class, CoreEnvironmentDeprecation::class)
    class Parser : AutoCloseable {
        private val disposable = Disposer.newDisposable("crap-score")
        private val factory: KtPsiFactory

        init {
            // PSI only, but the environment still needs an (empty) compiler-plugin extension storage.
            val configuration = CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() }
            val environment =
                KotlinCoreEnvironment.createForProduction(disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
            factory = KtPsiFactory(environment.project, false)
        }

        fun parse(
            fileName: String,
            text: String,
        ): KtFile = factory.createFile(fileName, text)

        override fun close() = Disposer.dispose(disposable)
    }

    /** Every function body in [file], each with the decision points of its own body only. */
    fun units(file: KtFile): List<CodeUnit> {
        val lineOf = lineIndex(file.text)
        val out = mutableListOf<CodeUnit>()

        fun visit(
            element: PsiElement,
            chain: List<String>,
            depth: Int,
        ) {
            for (child in element.children) {
                val name = unitName(child, lineOf)
                when {
                    name != null -> {
                        val qualified = chain + name
                        val body = bodyOf(child)
                        out +=
                            CodeUnit(
                                name = qualified.joinToString("."),
                                startLine = lineOf(child.textRange.startOffset),
                                endLine = lineOf(child.textRange.endOffset - 1),
                                complexity = 1 + (body?.let { decisions(it) } ?: 0),
                                depth = depth,
                            )
                        visit(child, qualified, depth + 1)
                    }

                    child is KtClassOrObject -> {
                        visit(child, chain + (child.name ?: "<anonymous>"), depth)
                    }

                    else -> {
                        visit(child, chain, depth)
                    }
                }
            }
        }
        visit(file, emptyList(), 0)
        return out
    }

    /**
     * Kover XML (`<package name="a/b"><sourcefile name="F.kt"><line nr mi ci …/>`) as per-line
     * counters keyed by `a/b/F.kt`.
     */
    fun coverageByFile(koverXml: String): Map<String, Map<Int, LineCounts>> {
        val factory = DocumentBuilderFactory.newInstance()
        // Kover writes a JaCoCo DOCTYPE; never fetch its DTD.
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val document = factory.newDocumentBuilder().parse(koverXml.byteInputStream())
        val result = mutableMapOf<String, Map<Int, LineCounts>>()
        val packages = document.getElementsByTagName("package")
        for (i in 0 until packages.length) {
            val pkg = packages.item(i) as Element
            val sourceFiles = pkg.getElementsByTagName("sourcefile")
            for (j in 0 until sourceFiles.length) {
                val sourceFile = sourceFiles.item(j) as Element
                val lines = sourceFile.getElementsByTagName("line")
                val counts = mutableMapOf<Int, LineCounts>()
                for (k in 0 until lines.length) {
                    val line = lines.item(k) as Element
                    counts[line.getAttribute("nr").toInt()] =
                        LineCounts(line.getAttribute("mi").toInt(), line.getAttribute("ci").toInt())
                }
                result[coverageKey(pkg.getAttribute("name"), sourceFile.getAttribute("name"))] = counts
            }
        }
        return result
    }

    /** The [coverageByFile] key of [file]: its package as a path plus its file name. */
    fun coverageKey(file: KtFile): String = coverageKey(file.packageFqName.asString().replace('.', '/'), file.name)

    /**
     * Scores [units] of the file at [path]. Each line belongs to the innermost unit containing it; a
     * unit owning no instrumented line is unscored. A file Kover does not list ([lines] null) counts
     * as 0% covered.
     */
    fun score(
        path: String,
        units: List<CodeUnit>,
        lines: Map<Int, LineCounts>?,
    ): Pair<List<Scored>, Int> {
        if (lines == null) return units.map { Scored(path, it, 0.0, crap(it.complexity, 0.0)) } to 0
        val owner = mutableMapOf<Int, CodeUnit>()
        for (unit in units.sortedBy { it.depth }) {
            for (line in unit.startLine..unit.endLine) owner[line] = unit
        }
        val totals = mutableMapOf<CodeUnit, LineCounts>()
        for ((line, counts) in lines) {
            val unit = owner[line] ?: continue
            val sum = totals[unit] ?: LineCounts(0, 0)
            totals[unit] = LineCounts(sum.missed + counts.missed, sum.covered + counts.covered)
        }
        var unscored = 0
        val scored =
            units.mapNotNull { unit ->
                val counts = totals[unit]
                val instructions = counts?.let { it.missed + it.covered } ?: 0
                if (counts == null || instructions == 0) {
                    unscored++
                    null
                } else {
                    val coverage = counts.covered.toDouble() / instructions
                    Scored(path, unit, coverage, crap(unit.complexity, coverage))
                }
            }
        return scored to unscored
    }

    /** `crap-report.txt`: a one-line summary, then every violation, worst first. */
    fun reportText(result: Result): String =
        buildString {
            appendLine(summaryLine(result))
            for (v in result.violations) {
                appendLine(
                    String.format(
                        Locale.ROOT,
                        "%8.1f  cc=%-3d cov=%5.1f%%  %s:%d  %s",
                        v.crap,
                        v.unit.complexity,
                        v.coverage * 100,
                        v.path,
                        v.unit.startLine,
                        v.unit.name,
                    ),
                )
            }
        }

    fun summaryLine(result: Result): String =
        "CRAP report: ${result.violations.size} violation(s) over threshold ${formatNumber(result.threshold)} " +
            "(${result.scored.size} units scored, ${result.unscored} unscored)"

    /** `crap-summary.txt`, read by the CI `quality-gate` job. */
    fun summary(result: Result): String =
        "violations=${result.violations.size}\nscored=${result.scored.size}\nthreshold=${formatNumber(result.threshold)}\n"

    /**
     * `gl-code-quality-crap.json`: one Code Quality (Code Climate) entry per violation. `lines.end` is the
     * unit's last line, so the diff mode (`tools/quality/changed.mjs`) can tell a changed function.
     */
    fun codeQualityJson(result: Result): String {
        val seen = mutableMapOf<String, Int>()
        val entries =
            result.violations.sortedWith(compareBy({ it.path }, { it.unit.startLine })).map { v ->
                val stableName = v.unit.name.replace(LINE_SUFFIX, "")
                val key = "${v.path}|$stableName"
                val occurrence = seen.getOrDefault(key, 0)
                seen[key] = occurrence + 1
                val description =
                    "CRAP ${oneDecimal(v.crap)} (complexity ${v.unit.complexity}, coverage " +
                        "${oneDecimal(v.coverage * 100)}%) > ${formatNumber(result.threshold)} in ${v.unit.name}"
                val severity = if (v.crap > 2 * result.threshold) "major" else "minor"
                val lines = """{"begin":${v.unit.startLine},"end":${v.unit.endLine}}"""
                """{"description":${json(description)},"check_name":"crap","fingerprint":"${sha1("$key|$occurrence")}",""" +
                    """"severity":"$severity","location":{"path":${json(v.path)},"lines":$lines}}"""
            }
        return entries.joinToString(",\n", prefix = "[\n", postfix = "\n]\n").takeIf { entries.isNotEmpty() } ?: "[]\n"
    }

    private val LINE_SUFFIX = Regex("@\\d+")

    private fun coverageKey(
        packagePath: String,
        fileName: String,
    ) = if (packagePath.isEmpty()) fileName else "$packagePath/$fileName"

    /** The unit name of [element] when it is a function body worth scoring, else null. */
    private fun unitName(
        element: PsiElement,
        lineOf: (Int) -> Int,
    ): String? =
        when (element) {
            is KtNamedFunction -> {
                element.takeIf { it.bodyExpression != null }?.let { it.name ?: "<anonymous>" }
            }

            is KtLambdaExpression -> {
                "${lambdaHolder(element)}@${lineOf(element.textRange.startOffset)}"
            }

            is KtPropertyAccessor -> {
                element.takeIf { it.bodyExpression != null }?.let {
                    "${it.property.name}.${if (it.isGetter) "get" else "set"}"
                }
            }

            is KtAnonymousInitializer -> {
                "init"
            }

            is KtSecondaryConstructor -> {
                element.takeIf { it.bodyExpression != null }?.let { "constructor" }
            }

            else -> {
                null
            }
        }

    private fun bodyOf(element: PsiElement): PsiElement? =
        when (element) {
            is KtNamedFunction -> element.bodyExpression
            is KtLambdaExpression -> element.bodyExpression
            is KtPropertyAccessor -> element.bodyExpression
            is KtAnonymousInitializer -> element.body
            is KtSecondaryConstructor -> element.bodyExpression
            else -> null
        }

    /** What holds an anonymous lambda: `callee` or `callee("first string arg")`, an assigned property, or `<lambda>`. */
    private fun lambdaHolder(lambda: KtLambdaExpression): String {
        val parent = lambda.parent
        val call =
            when (parent) {
                is KtLambdaArgument -> parent.parent as? KtCallExpression
                is KtValueArgument -> parent.parent?.parent as? KtCallExpression
                else -> null
            }
        if (call != null) {
            val callee = call.calleeExpression?.text ?: "<lambda>"
            val firstString = call.valueArguments.firstOrNull()?.getArgumentExpression() as? KtStringTemplateExpression
            return if (firstString != null) "$callee(${firstString.text})" else callee
        }
        return (parent as? KtProperty)?.name ?: "<lambda>"
    }

    /** Decision points in [element]'s subtree, not descending into nested units or classes. */
    private fun decisions(element: PsiElement): Int =
        element.children.sumOf { child ->
            if (isUnitOrClass(child)) 0 else decisionAt(child) + decisions(child)
        }

    private fun isUnitOrClass(element: PsiElement) =
        element is KtNamedFunction ||
            element is KtLambdaExpression ||
            element is KtClassOrObject ||
            element is KtPropertyAccessor ||
            element is KtAnonymousInitializer ||
            element is KtSecondaryConstructor

    private fun decisionAt(element: PsiElement): Int =
        when (element) {
            is KtIfExpression, is KtForExpression, is KtWhileExpression, is KtDoWhileExpression, is KtCatchClause -> 1
            is KtWhenEntry -> if (element.isElse) 0 else 1
            is KtBinaryExpression -> if (element.operationToken in BRANCHING_OPERATORS) 1 else 0
            else -> 0
        }

    private val BRANCHING_OPERATORS = setOf(KtTokens.ANDAND, KtTokens.OROR, KtTokens.ELVIS)

    /** 1-based line of a character offset in [text]. */
    private fun lineIndex(text: String): (Int) -> Int {
        val starts = mutableListOf(0)
        text.forEachIndexed { i, c -> if (c == '\n') starts += i + 1 }
        return { offset ->
            val found = starts.binarySearch(offset)
            (if (found >= 0) found else -found - 2) + 1
        }
    }

    private fun formatNumber(value: Double): String = if (value == Math.floor(value)) value.toLong().toString() else oneDecimal(value)

    private fun oneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    private fun json(value: String): String =
        buildString {
            append('"')
            for (c in value) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append(String.format(Locale.ROOT, "\\u%04x", c.code)) else append(c)
                }
            }
            append('"')
        }

    private fun sha1(value: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(value.toByteArray()))
}
