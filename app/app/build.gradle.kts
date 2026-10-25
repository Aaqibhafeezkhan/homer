import com.github.jk1.license.filter.DependencyFilter
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.ReportRenderer
import com.github.jk1.license.render.TextReportRenderer
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")

    // Apply the Application plugin to add support for building an executable JVM application.
    application
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.shadow)
    alias(libs.plugins.dependencyCheck)
    alias(libs.plugins.licenseReport)
    alias(libs.plugins.pitest)
}

dependencies {
//    implementation(project(":utils"))

    implementation(libs.bundles.ktor)
    implementation(libs.bundles.ktorClient)
    implementation(libs.bundles.opentelemetry)
    implementation(libs.bundles.koin)
    implementation(libs.logbackClassic)
    implementation(libs.langfuseClient) {
        // langfuse-java 0.2.0 leaks JUnit into its compile scope; keep test
        // frameworks off the runtime classpath (and out of the shadow JAR).
        exclude(group = "org.junit")
        exclude(group = "org.junit.jupiter")
        exclude(group = "org.junit.platform")
        exclude(group = "org.opentest4j")
    }
    implementation(libs.kotlinXSetializationYaml)
    implementation(libs.jsonPath)
    implementation(libs.cronUtils)
    implementation(libs.kubernetesClient)
    implementation(libs.bcrypt)
    implementation(libs.jsonSchemaValidator)
    implementation(libs.mcpKotlinSdkServer)
    implementation(libs.bundles.persistence)
    implementation(libs.bundles.sshd)
    implementation(libs.commonsCompress)

    // Testing
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotestProperty)

    // Transitive dependencies flagged by dependency scanners whose direct
    // consumers (langfuse-java, json-schema-validator, kubernetes client-java)
    // have no release on a patched version yet. Drop a constraint once the
    // consumer's own release resolves to at least this version.
    // Ktor (and other consumers) pin Netty 4.2.13, which carries high-severity advisories fixed by
    // 4.2.17; the BOM lifts every Netty module together.
    implementation(platform(libs.nettyBom))
    constraints {
        implementation(libs.jackson2Databind)
        implementation(libs.jackson3Databind)
        implementation(libs.bcprov)
        implementation(libs.bcpkix)
        implementation(libs.bcutil)
        // The AWS SDK's apache5-client resolves httpclient5 5.6.1 and httpcore5-h2 5.4.
        implementation(libs.httpclient5)
        implementation(libs.httpcore5)
        implementation(libs.httpcore5H2)
        // Kept at the project's Kotlin version; a consumer resolves 2.3.21, which predates the fix
        // for CVE-2026-53914.
        implementation(libs.kotlinReflect)
        // Pinned for provenance, not patch level: the newer POMs point at a
        // non-Square project URL (see the catalog comment).
        implementation(libs.okhttp)
        implementation(libs.okio)
    }
}

// kotlinx-coroutines-test's virtual-time API (runCurrent, advanceTimeBy, currentTime) is
// ExperimentalCoroutinesApi; opt the tests in once instead of per class. Main code opts in per use.
tasks.named<KotlinJvmCompile>("compileTestKotlin") {
    compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
}

// Bundle the project's JSON schemas as classpath resources so the running
// server can serve them (e.g. for Monaco editor validation in the console)
// without needing the repo layout at runtime. Single source of truth: the
// `app/schemas/` directory. Must live inside the Gradle project root so that
// Docker builds which scope their build context to `app/` can still reach it.
tasks.processResources {
    val schemasDir = rootProject.layout.projectDirectory.dir("schemas")
    doFirst {
        val files = schemasDir.asFile.listFiles { f -> f.name.endsWith(".json") }
        require(!files.isNullOrEmpty()) {
            "Schema source directory is empty or missing: ${schemasDir.asFile}. " +
                "Expected homer-agent.schema.json / homer-config.schema.json. " +
                "If you reorganise the tree, update this path — a silent empty " +
                "bundle is what caused /api/v1/schemas/agent to 404 at runtime."
        }
    }
    from(schemasDir) {
        include("homer-agent.schema.json", "homer-config.schema.json")
        into("schemas")
    }
}

// Refresh the bundled copies under `plugins/homer-authoring/` (the distributed
// Claude Code authoring plugin) from their canonical sources. The plugin ships
// to external users without the repo layout, so it carries its own copy of
// AGENT.md and the JSON schemas. `AuthoringPluginBundleTest` fails the build if
// these copies drift — re-run this task in the same commit as any AGENT.md /
// schema change (root CLAUDE.md §10).
tasks.register<Copy>("syncAuthoringPlugin") {
    val pluginRoot = rootProject.layout.projectDirectory.dir("../plugins/homer-authoring")
    into(pluginRoot)
    from(rootProject.layout.projectDirectory.file("AGENT.md")) {
        into("skills/homer-agent-authoring/reference")
    }
    from(rootProject.layout.projectDirectory.dir("schemas")) {
        include("homer-agent.schema.json", "homer-config.schema.json")
        into("schemas")
    }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    jvmArgs("-Dnet.bytebuddy.experimental=true")
    maxHeapSize = "2g"

    // Tests resolve repo files against this (homer.app.fixtures.repoRoot), under the name the
    // architecture module uses.
    val repoRoot = rootDir.parentFile
    systemProperty("homer.repoRoot", repoRoot.absolutePath)
    // The CI pipeline file: the one the CI names in its own CI_CONFIG_PATH, or else the single
    // root-level `.<forge>-ci.yml` of a checkout that has one, so the guards that read it run on a
    // developer's machine too. The published tree ships without it; there they have nothing to check.
    // A pipeline file that cannot be told (a named one missing, or several candidates) skips
    // the guards with a warning rather than in silence.
    val namedCiConfig =
        providers
            .environmentVariable("CI_CONFIG_PATH")
            .orNull
            ?.takeIf { it.isNotBlank() }
            ?.let { repoRoot.resolve(it) }
    val rootCiConfigs =
        repoRoot
            .listFiles { f -> f.isFile && Regex("""\.[a-z]+-ci\.yml""").matches(f.name) }
            .orEmpty()
            .toList()
    val ciConfig = if (namedCiConfig != null) namedCiConfig.takeIf { it.isFile } else rootCiConfigs.singleOrNull()
    if (namedCiConfig != null && ciConfig == null) {
        logger.warn("CI_CONFIG_PATH names $namedCiConfig, which does not exist: the CI pipeline guards are skipped")
    } else if (namedCiConfig == null && rootCiConfigs.size > 1) {
        logger.warn("Several root-level CI pipeline files ${rootCiConfigs.map { it.name }}: the CI pipeline guards are skipped")
    }
    ciConfig?.let { systemProperty("homer.ciConfig", it.absolutePath) }

    // ArtifactoryTransportTest, ArtifactorySecretExposureTest, ReleaseWorkflowGateTest,
    // ExampleAgentsDeprecationTest and ExampleHelloAgentInputTest read repo files
    // that are not otherwise inputs of this task. Undeclared, they leave the cache
    // key unchanged, so editing a Dockerfile or a compose file replays a cached
    // pass and the guard never runs -- precisely when it is meant to fire.
    inputs
        .files(
            listOfNotNull(ciConfig),
            repoRoot.resolve("app/Dockerfile"),
            repoRoot.resolve("app/e2e/Dockerfile"),
            repoRoot.resolve("app/settings.gradle.kts"),
            repoRoot.resolve("app/buildSrc/settings.gradle.kts"),
            repoRoot.resolve("app/gradle/artifactory.settings.gradle.kts"),
            repoRoot.resolve(".github/workflows/release.yml"),
            repoRoot.resolve(".github/workflows/pages.yml"),
            fileTree(repoRoot) { include("docker-compose*.yml") },
            fileTree(repoRoot.resolve("example/agents")),
        ).withPropertyName("guardedRepoFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // DtoContractTest writes the console DTO contract here for the dto-contract check (ADR-0082).
    // Declared as an output so a test run restored from the build cache restores the file too.
    val dtoContractDir = layout.buildDirectory.dir("reports/dto-contract")
    outputs.dir(dtoContractDir).withPropertyName("dtoContract")
    systemProperty("homer.dtoContract.dir", dtoContractDir.get().asFile.absolutePath)

    // QueryPlanCapture collects production SQL here for queryPlanReport (check `query-plans`,
    // ADR-0082); emptied first, as the capture only ever appends.
    val queryPlansDir = layout.buildDirectory.dir("reports/query-plans/captured")
    outputs.dir(queryPlansDir).withPropertyName("queryPlansCapture")
    systemProperty("homer.queryPlans.dir", queryPlansDir.get().asFile.absolutePath)
    val queryPlansCaptureDir = queryPlansDir.get().asFile
    doFirst { queryPlansCaptureDir.deleteRecursively() }
}

// Plans every statement :app:test captured and lists full scans of growing tables (ADR-0082).
tasks.register<JavaExec>("queryPlanReport") {
    description = "EXPLAIN QUERY PLAN for the SQL production code ran in :app:test"
    group = "verification"
    dependsOn(tasks.test)
    val captured = layout.buildDirectory.dir("reports/query-plans/captured")
    val report = layout.buildDirectory.file("reports/query-plans/query-plans.json")
    inputs.dir(captured).withPropertyName("captured")
    outputs.file(report).withPropertyName("report")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "homer.app.fixtures.QueryPlanReport"
    args(captured.get().asFile.absolutePath, report.get().asFile.absolutePath)
}

// Mutation-testing pilot (ADR-0082): a narrow, pure package; run on demand, not part of `check`.
pitest {
    pitestVersion = libs.versions.pitest
    junit5PluginVersion = libs.versions.pitestJunit5
    targetClasses = setOf("homer.app.config.*")
    targetTests = setOf("homer.app.config.*")
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    threads = 4
    failWhenNoMutations = false
}

application {
    // Define the Fully Qualified Name for the application main class
    mainClass = "homer.app.ApplicationKt"
}

dependencyCheck {
    failBuildOnCVSS = 7.0f
    // What the backend ships, like the console's `npm audit --omit=dev`: build tools (compiler,
    // detekt, ktlint) never reach production. :homer-runner runs the same check (ADR-0088).
    scanConfigurations = listOf("runtimeClasspath")
    suppressionFile = file("dependency-check-suppression.xml").absolutePath
    nvd {
        // The dependency-check project's own NVD mirror instead of the NVD API (ADR-0088). With a
        // data feed set, the plugin never calls the API, so no API key is needed.
        datafeedUrl = "https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz"
    }
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

// Committed third-party attributions at the repo root: `NOTICE` (the texts bundled dependencies
// require us to reproduce) and the backend region of `THIRD-PARTY-LICENSES.md` (the inventory).
// Rendering lives in `buildSrc/src/main/kotlin/ThirdPartyNotices.kt`; policy is ADR-0056, whose
// addendum also explains why these depend on `checkLicense` rather than `generateLicenseReport`.
//
// Every value a task action touches is copied into a local of its configuration block first: a
// `doLast` reading a script-level property captures the build script itself, which the
// configuration cache cannot serialize.
val repoRoot = rootProject.layout.projectDirectory.dir("..")
val noticeFile = repoRoot.file("NOTICE").asFile
val inventoryFile = repoRoot.file("THIRD-PARTY-LICENSES.md").asFile
val backendRegionBegin = "<!-- BEGIN GENERATED: backend (./gradlew :app:generateThirdPartyNotices) -->"
val backendRegionEnd = "<!-- END GENERATED: backend -->"
val homerNoticeHeader =
    """
    Homer (Hub for Orchestrating Managed Execution Runs)
    Copyright 2026 Livesport s.r.o.
    """.trimIndent()
val licenseReportDirs =
    listOf(
        layout.buildDirectory.dir("reports/dependency-license"),
        project(":agentdebug").layout.buildDirectory.dir("reports/dependency-license"),
        project(":homer-runner").layout.buildDirectory.dir("reports/dependency-license"),
    ).map { it.get().asFile }
val licenseReportFiles = licenseReportDirs.map { it.resolve(ThirdPartyNotices.REPORT_FILE_NAME) }

tasks.register("generateThirdPartyNotices") {
    group = "documentation"
    description = "Regenerates NOTICE and the backend region of THIRD-PARTY-LICENSES.md."
    dependsOn(":app:checkLicense", ":agentdebug:checkLicense", ":homer-runner:checkLicense")
    inputs.files(licenseReportFiles)
    outputs.files(noticeFile, inventoryFile)

    val reportDirs = licenseReportDirs
    val notice = noticeFile
    val inventory = inventoryFile
    val regionBegin = backendRegionBegin
    val regionEnd = backendRegionEnd
    val header = homerNoticeHeader
    doLast {
        val rendered = ThirdPartyNotices.render(reportDirs, inventory.readText(), regionBegin, regionEnd, header)
        notice.writeText(rendered.notice)
        inventory.writeText(rendered.inventoryDocument)
    }
}

tasks.register("checkThirdPartyNotices") {
    group = "verification"
    description = "Fails if NOTICE or the backend region of THIRD-PARTY-LICENSES.md is out of date."
    dependsOn(":app:checkLicense", ":agentdebug:checkLicense", ":homer-runner:checkLicense")
    inputs.files(licenseReportFiles)
    inputs.files(noticeFile, inventoryFile)
    outputs.dir(layout.buildDirectory.dir("third-party-notices"))

    val reportDirs = licenseReportDirs
    val notice = noticeFile
    val inventory = inventoryFile
    val regionBegin = backendRegionBegin
    val regionEnd = backendRegionEnd
    val header = homerNoticeHeader
    val renderedDir =
        layout.buildDirectory
            .dir("third-party-notices")
            .get()
            .asFile
    doLast {
        val committedInventory = inventory.readText()
        val rendered = ThirdPartyNotices.render(reportDirs, committedInventory, regionBegin, regionEnd, header)
        renderedDir.mkdirs()
        renderedDir.resolve("NOTICE").writeText(rendered.notice)
        renderedDir.resolve("THIRD-PARTY-LICENSES.md").writeText(rendered.inventoryDocument)
        val stale =
            listOfNotNull(
                "NOTICE".takeIf { rendered.notice != notice.readText() },
                "THIRD-PARTY-LICENSES.md (backend region)".takeIf { rendered.inventoryDocument != committedInventory },
            )
        require(stale.isEmpty()) {
            "Out of date with the dependency license report: ${stale.joinToString(" and ")}. " +
                "Run ./gradlew :app:generateThirdPartyNotices and commit the result."
        }
    }
}

tasks.check {
    dependsOn("checkLicense", "checkThirdPartyNotices")
}

tasks.shadowJar {
    archiveBaseName.set("homer")
    archiveClassifier.set("all")
    archiveVersion.set("0.0.2")
    manifest {
        attributes["Main-Class"] = "homer.app.ApplicationKt"
    }
    // Exclude signature files to avoid conflicts
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    from(tasks.named("generateLicenseReport")) {
        include("THIRD-PARTY-NOTICES.txt")
        into("META-INF")
    }
}
