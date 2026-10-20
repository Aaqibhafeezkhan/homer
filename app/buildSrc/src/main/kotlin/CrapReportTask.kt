import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import java.io.File
import javax.inject.Inject

/**
 * Writes the module's CRAP report ([CrapScore]) from its Kotlin sources and Kover XML report:
 * `crap-report.txt`, `gl-code-quality-crap.json` (Code Climate / Code Quality JSON) and `crap-summary.txt`
 * (read by the CI `quality-gate` job). Violations never fail this task; the gate decides. ADR-0081.
 *
 * The analysis runs in a worker classloader holding only [analyzerClasspath] (the embeddable Kotlin
 * compiler): the Kotlin Gradle plugin on the build classpath carries clashing copies of its classes.
 */
abstract class CrapReportTask
    @Inject
    constructor(
        private val workers: WorkerExecutor,
    ) : DefaultTask() {
        @get:Classpath
        abstract val analyzerClasspath: ConfigurableFileCollection

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val sources: ConfigurableFileCollection

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val koverXml: RegularFileProperty

        /** Report paths are relative to this directory (the repository root), as Code Quality readers expect. */
        @get:Input
        abstract val repoRoot: Property<String>

        @get:Input
        abstract val threshold: Property<Double>

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun report() {
            workers.classLoaderIsolation { classpath.from(analyzerClasspath) }.submit(CrapReportAction::class.java) {
                sources.from(this@CrapReportTask.sources)
                koverXml.set(this@CrapReportTask.koverXml)
                repoRoot.set(this@CrapReportTask.repoRoot)
                threshold.set(this@CrapReportTask.threshold)
                outputDir.set(this@CrapReportTask.outputDir)
            }
        }
    }

interface CrapReportParameters : WorkParameters {
    val sources: ConfigurableFileCollection
    val koverXml: RegularFileProperty
    val repoRoot: Property<String>
    val threshold: Property<Double>
    val outputDir: DirectoryProperty
}

abstract class CrapReportAction : WorkAction<CrapReportParameters> {
    override fun execute() {
        val logger = Logging.getLogger(CrapReportAction::class.java)
        val coverage =
            CrapScore.coverageByFile(
                parameters.koverXml
                    .get()
                    .asFile
                    .readText(),
            )
        val root = File(parameters.repoRoot.get())
        val scored = mutableListOf<CrapScore.Scored>()
        var unscored = 0
        CrapScore.Parser().use { parser ->
            for (source in parameters.sources.asFileTree
                .matching { include("**/*.kt") }
                .files
                .sorted()) {
                val file = parser.parse(source.name, source.readText())
                val path = source.relativeTo(root).invariantSeparatorsPath
                val (fileScored, fileUnscored) = CrapScore.score(path, CrapScore.units(file), coverage[CrapScore.coverageKey(file)])
                scored += fileScored
                unscored += fileUnscored
            }
        }
        val result = CrapScore.Result(scored, unscored, parameters.threshold.get())
        val dir = parameters.outputDir.get().asFile
        val reportFile = dir.resolve("crap-report.txt")
        reportFile.writeText(CrapScore.reportText(result))
        dir.resolve("gl-code-quality-crap.json").writeText(CrapScore.codeQualityJson(result))
        dir.resolve("crap-summary.txt").writeText(CrapScore.summary(result))

        logger.lifecycle(CrapScore.summaryLine(result))
        if (result.violations.isNotEmpty()) {
            reportFile
                .readLines()
                .drop(1)
                .take(TOP_VIOLATIONS_LOGGED)
                .forEach { logger.warn(it) }
            logger.warn("Full report: ${reportFile.relativeTo(root).invariantSeparatorsPath}")
        }
    }

    private companion object {
        const val TOP_VIOLATIONS_LOGGED = 20
    }
}
