// The code in this file is a convention plugin - a Gradle mechanism for sharing reusable build logic.
// `buildSrc` is a Gradle-recognized directory and every plugin there will be easily available in the rest of the build.
package buildsrc.convention

import CrapReportTask
import CrapScore
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion

plugins {
    // Apply the Kotlin JVM plugin to add support for Kotlin in JVM projects.
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("org.jetbrains.kotlinx.kover")
    id("dev.detekt")
}

kotlin {
    // Use a specific Java version to make it easier to work in different environments.
    jvmToolchain(25)
    // A compiler warning fails the build, locally as in CI (`kotlin-warnings`, ADR-0082).
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

tasks.withType<Test>().configureEach {
    // Configure all test Gradle tasks to use JUnitPlatform.
    useJUnitPlatform()

    // Log information about all test results, not only the failed ones.
    testLogging {
        events(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED,
        )
    }
}

ktlint {
    version.set("1.8.0")
    android.set(false)
    ignoreFailures.set(false)
    filter {
        exclude { entry -> entry.file.path.contains("/build/") }
    }
}

// detekt (ADR-0082 check `detekt`, required); why these rule sets: see the ADR amendment.
// `detektMain` runs with type resolution; findings never fail it, the quality-gate reports them.
detekt {
    buildUponDefaultConfig.set(true)
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
    basePath.set(rootProject.layout.projectDirectory.dir(".."))
    ignoreFailures.set(true)
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    reports {
        sarif.required.set(true)
        html.required.set(true)
        checkstyle.required.set(false)
        markdown.required.set(false)
    }
}

// Only the type-resolved `detektMain` runs, on its own (CI job homer-app-detekt): the plugin's
// untyped `detekt` task would analyse the same sources again, and inside `check` the Analysis API
// pushes the build past the CI runner's memory.
tasks.named("detekt") { enabled = false }

// CRAP score report (ADR-0081): complexity from the main sources, coverage from Kover's XML report.
// Violations never fail this task; CI's `quality-gate` job fails the pipeline on them (the `crap` check).
val crapAnalyzer =
    configurations.create("crapAnalyzer") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
dependencies {
    // Same version as the Kotlin plugin, so the parser reads exactly the language this build compiles.
    crapAnalyzer("org.jetbrains.kotlin:kotlin-compiler-embeddable:${getKotlinPluginVersion()}")
}

tasks.register<CrapReportTask>("crapReport") {
    group = "verification"
    description = "Writes build/reports/crap/ (CRAP score per function) from the Kover XML report."
    dependsOn("koverXmlReport")
    analyzerClasspath.from(crapAnalyzer)
    sources.from(
        kotlin.sourceSets
            .getByName("main")
            .kotlin.sourceDirectories,
    )
    koverXml.set(layout.buildDirectory.file("reports/kover/report.xml"))
    repoRoot.set(rootDir.parentFile.absolutePath)
    // One threshold for both stacks, in tools/quality/checks.json; -Pcrap.threshold overrides it locally.
    val checksJson = rootProject.layout.projectDirectory.file("../tools/quality/checks.json")
    threshold.set(
        providers
            .gradleProperty("crap.threshold")
            .map(String::toDouble)
            .orElse(providers.fileContents(checksJson).asText.map { QualityConfig.crapThreshold(it) ?: CrapScore.DEFAULT_THRESHOLD })
            .orElse(CrapScore.DEFAULT_THRESHOLD),
    )
    outputDir.set(layout.buildDirectory.dir("reports/crap"))
}

kover {
    reports {
        verify {
            rule {
                // Coverage threshold intentionally 0% at adoption. Tune upward as
                // coverage is measured and improved.
                minBound(0)
            }
        }
    }
}
