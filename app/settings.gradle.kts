// The settings file is the entry point of every Gradle build.
// Its primary purpose is to define the subprojects.
// It is also used for some aspects of project-wide configuration, like managing plugins, dependencies, etc.
// https://docs.gradle.org/current/userguide/settings_file_basics.html

pluginManagement {
    repositories {
        // Same gate as gradle/artifactory.settings.gradle.kts — pluginManagement
        // must be the first block and cannot be fed from an applied script.
        val artifactoryUrl = providers.environmentVariable("ARTIFACTORY_URL").orNull?.takeIf { it.isNotBlank() }
        val artifactoryUser = providers.environmentVariable("ARTIFACTORY_USER").orNull?.takeIf { it.isNotBlank() }
        val artifactoryToken = providers.environmentVariable("ARTIFACTORY_TOKEN").orNull?.takeIf { it.isNotBlank() }
        if (artifactoryUrl != null && artifactoryUser != null && artifactoryToken != null) {
            require(Regex("https://[A-Za-z0-9.-]+(:[0-9]+)?").matches(artifactoryUrl)) { "ARTIFACTORY_URL must be https://<host>[:port]" }
            maven {
                name = "artifactory"
                // CWE-319: TLS only — never isAllowInsecureProtocol here.
                url = uri("$artifactoryUrl/artifactory/maven-all/")
                credentials {
                    username = artifactoryUser
                    password = artifactoryToken
                }
                authentication {
                    create<BasicAuthentication>("basic")
                }
            }
        }
        gradlePluginPortal()
    }
}

plugins {
    // Use the Foojay Toolchains plugin to automatically download JDKs required by subprojects.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// CI: resolve dependencies through Artifactory when the CI/CD variables are set.
apply(from = "gradle/artifactory.settings.gradle.kts")

dependencyResolutionManagement {
    // Use Maven Central as the default repository (where Gradle will download dependencies) in all subprojects.
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

// Remote build cache in Artifactory, enabled only when the CI/CD variables are
// set. Entries are pushed from protected refs only (main, release tags): a
// merge-request pipeline can read the cache but cannot seed entries that a
// protected build would later reuse. Remote-cache errors are non-fatal in
// Gradle, so an unreachable cache degrades to a slower build, not a red one.
buildCache {
    val artifactoryUrl = providers.environmentVariable("ARTIFACTORY_URL").orNull?.takeIf { it.isNotBlank() }
    val artifactoryUser = providers.environmentVariable("ARTIFACTORY_USER").orNull?.takeIf { it.isNotBlank() }
    val artifactoryToken = providers.environmentVariable("ARTIFACTORY_TOKEN").orNull?.takeIf { it.isNotBlank() }
    if (artifactoryUrl != null && artifactoryUser != null && artifactoryToken != null) {
        require(Regex("https://[A-Za-z0-9.-]+(:[0-9]+)?").matches(artifactoryUrl)) { "ARTIFACTORY_URL must be https://<host>[:port]" }
        remote<HttpBuildCache> {
            // CWE-319: TLS only — never isAllowInsecureProtocol here.
            url = uri("$artifactoryUrl/artifactory/gradle-build/homer/")
            credentials {
                username = artifactoryUser
                password = artifactoryToken
            }
            isPush = providers.environmentVariable("CI_COMMIT_REF_PROTECTED").orNull == "true"
        }
    }
}

// Include the `app` and `utils` subprojects in the build.
// If there are changes in only one of the projects, Gradle will rebuild only the one that has changed.
// Learn more about structuring projects with Gradle - https://docs.gradle.org/8.7/userguide/multi_project_builds.html
include(":app")
include(":agentdebug")
include(":homer-runner")
include(":e2e")
include(":architecture")
//include(":utils")

rootProject.name = "Homer"
