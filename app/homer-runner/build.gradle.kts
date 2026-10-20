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
    alias(libs.plugins.dependencyCheck)
}

dependencies {
    // The protocol DTOs, TarStreams and ProcessGroupSupport come from :app. Logback
    // (its logging backend) is excluded so slf4j-simple stays the sole SLF4J
    // binding, as in :agentdebug.
    implementation(project(":app")) {
        exclude(group = "ch.qos.logback")
    }
    implementation(libs.clikt)
    implementation(libs.kotlinxSerialization)
    implementation(libs.kotlinxCoroutines)
    implementation(libs.slf4jSimple)
    implementation(libs.bundles.ktorClient)

    testImplementation(libs.bundles.testing)
}

application {
    mainClass = "homer.runner.MainKt"
}

// Runtime-dependency license gate; policy and rationale: see ADR-0056.
licenseReport {
    configurations = arrayOf("runtimeClasspath")
    filters = arrayOf<DependencyFilter>(LicenseBundleNormalizer())
    renderers = arrayOf<ReportRenderer>(TextReportRenderer("THIRD-PARTY-NOTICES.txt"))
    allowedLicensesFile = rootProject.layout.projectDirectory.file("config/allowed-licenses.json")
}

tasks.check {
    dependsOn("checkLicense")
}

// The runner's dumpability hardening makes an FFM downcall to prctl; the JDK
// warns on the restricted method without this, as the runtime image does not.
tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Dumpability.harden() is process-wide, so a class that calls it in the shared
    // test JVM would make every later class' same-uid /proc read fail with EPERM
    // (and could let a status read see the wrong process). One class per JVM.
    forkEvery = 1
}

tasks.shadowJar {
    archiveBaseName.set("homer-runner")
    archiveClassifier.set("all")
    archiveVersion.set("0.1.0")
    manifest { attributes["Main-Class"] = "homer.runner.MainKt" }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    from(tasks.named("generateLicenseReport")) {
        include("THIRD-PARTY-NOTICES.txt")
        into("META-INF")
    }
}

// The runner image ships its own few runtime dependencies on top of :app's, so it gets the same
// CVE gate as :app (ADR-0088): the NVD mirror, runtimeClasspath only, :app's suppressions.
dependencyCheck {
    failBuildOnCVSS = 7.0f
    suppressionFile = project(":app").file("dependency-check-suppression.xml").absolutePath
    scanConfigurations = listOf("runtimeClasspath")
    nvd {
        datafeedUrl = "https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz"
    }
}
