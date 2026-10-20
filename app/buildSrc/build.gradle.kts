plugins {
    // The Kotlin DSL plugin provides a convenient way to develop convention plugins.
    // Convention plugins are located in `src/main/kotlin`, with the file extension `.gradle.kts`,
    // and are applied in the project's `build.gradle.kts` files as required.
    `kotlin-dsl`
    // buildSrc cannot apply its own `buildsrc.convention.kotlin-jvm`, so ktlint is applied
    // directly here and configured to match that plugin's block.
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(25)
}

ktlint {
    version.set("1.8.0")
    android.set(false)
    ignoreFailures.set(false)
    filter {
        exclude { entry -> entry.file.path.contains("/build/") }
    }
}

dependencies {
    // The CRAP report's Kotlin parser (PSI only, no resolution). ADR-0081. Never `implementation`:
    // the Kotlin Gradle plugin ships its own relocated copy of a few compiler classes
    // (KotlinCoreEnvironment among them), so the two must not share a classloader. CrapReportTask
    // runs the analysis in an isolated worker classloader holding the embeddable compiler; here it
    // is only compiled and tested against, declared first so it wins over the plugin's copy.
    compileOnly(libs.kotlinCompilerEmbeddable)
    testImplementation(libs.kotlinCompilerEmbeddable)
    // Add a dependency on the Kotlin Gradle plugin, so that convention plugins can apply it.
    implementation(libs.kotlinGradlePlugin)
    implementation(libs.ktlintGradlePlugin)
    implementation(libs.koverGradlePlugin)
    implementation(libs.detektGradlePlugin)

    // Unit tests for the plain-Kotlin build logic in `src/main/kotlin` (e.g. ThirdPartyNotices).
    testImplementation(libs.kotlinTest)
    testImplementation(libs.kotlinTestJunit5)
    testImplementation(libs.junitJupiter)
}

tasks.test {
    useJUnitPlatform()
}
