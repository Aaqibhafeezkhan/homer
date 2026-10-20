plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    testImplementation(libs.konsist)
    testImplementation(libs.kotlinTest)
    testImplementation(libs.kotlinTestJunit5)
    testImplementation(libs.junitJupiter)
}

// A rule tagged report-only (ReportOnly.kt) still has findings: it runs in architectureReportOnly, which
// never fails the build, and is reported by the warn-mode check architecture-backend-pending, listed only
// while a rule carries the tag (ADR-0082).
val reportOnlyTag = "report-only"

tasks.withType<Test>().configureEach {
    val repoRoot = rootDir.parentFile
    systemProperty("homer.repoRoot", repoRoot.absolutePath)
    // Konsist parses every main and test source of the backend modules into one JVM. Gradle's 512m
    // default left no headroom: the rules ran out of heap ("Java heap space") in CI.
    maxHeapSize = "1g"
    // The rules read other modules' sources; undeclared, editing them would replay a cached result.
    inputs
        .files(
            fileTree(repoRoot) {
                include("app/app/src/**/*.kt", "app/agentdebug/src/**/*.kt", "app/homer-runner/src/**/*.kt")
            },
        ).withPropertyName("scannedSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.test {
    useJUnitPlatform { excludeTags(reportOnlyTag) }
}

val architectureReportOnly =
    tasks.register<Test>("architectureReportOnly") {
        group = "verification"
        description = "Runs the report-only architecture rules; their findings never fail the build (ADR-0082)."
        testClassesDirs =
            sourceSets.test
                .get()
                .output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform { includeTags(reportOnlyTag) }
        ignoreFailures = true
        // An empty lane (every rule promoted) is not an error.
        failOnNoDiscoveredTests = false
        reports.junitXml.outputLocation = layout.buildDirectory.dir("test-results/architectureReportOnly")
        shouldRunAfter(tasks.test)
    }

tasks.check { dependsOn(architectureReportOnly) }
