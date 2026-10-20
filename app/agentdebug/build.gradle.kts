import com.github.jk1.license.filter.DependencyFilter
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.ReportRenderer
import com.github.jk1.license.render.TextReportRenderer

plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.shadow)
    alias(libs.plugins.licenseReport)
}

dependencies {
    // Exclude logback (:app's logging backend) so this CLI keeps slf4j-simple as its
    // sole SLF4J binding — two bindings on one classpath is an error.
    implementation(project(":app")) {
        exclude(group = "ch.qos.logback")
    }
    implementation(libs.clikt)
    // Explicit transitive deps needed for compilation (not exposed via :app's implementation scope)
    implementation(libs.kotlinxSerialization)
    implementation(libs.kotlinXSetializationYaml)
    implementation(libs.kotlinxCoroutines)
    implementation(libs.slf4jSimple)
    implementation(libs.opentelemetryApi)
    implementation(libs.bcrypt)
    // ProcessManager (constructed by DebugAgentRunner to satisfy the executor-factory
    // contract) is now SQLite-backed, so the driver + Exposed are needed at runtime.
    implementation(libs.bundles.persistence)

    testImplementation(libs.bundles.testing)
}

application {
    mainClass = "homer.agentdebug.MainKt"
}

// Runtime-dependency license gate; policy and rationale: see ADR-0056.
licenseReport {
    configurations = arrayOf("runtimeClasspath")
    // Explicit type parameters matter: the plugin's checkLicense internals do
    // `renderers += [new JsonReportRenderer(...)]` on the configured array, which
    // throws a ClassCastException if Kotlin's `arrayOf(...)` narrowed the runtime
    // array type to the single renderer class instead of the declared ReportRenderer[].
    filters = arrayOf<DependencyFilter>(LicenseBundleNormalizer())
    renderers = arrayOf<ReportRenderer>(TextReportRenderer("THIRD-PARTY-NOTICES.txt"))
    allowedLicensesFile = rootProject.layout.projectDirectory.file("config/allowed-licenses.json")
}

tasks.check {
    dependsOn("checkLicense")
}

tasks.shadowJar {
    archiveBaseName.set("agentdebug")
    archiveClassifier.set("all")
    archiveVersion.set("0.1.0")
    manifest { attributes["Main-Class"] = "homer.agentdebug.MainKt" }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    from(tasks.named("generateLicenseReport")) {
        include("THIRD-PARTY-NOTICES.txt")
        into("META-INF")
    }
}
