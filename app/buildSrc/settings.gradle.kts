pluginManagement {
    repositories {
        // Same gate as ../gradle/artifactory.settings.gradle.kts — pluginManagement
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

// CI: resolve dependencies through Artifactory when the CI/CD variables are set.
apply(from = "../gradle/artifactory.settings.gradle.kts")

dependencyResolutionManagement {

    // Use Maven Central and the Gradle Plugin Portal for resolving dependencies in the shared build logic (`buildSrc`) project.
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }

    // Reuse the version catalog from the main build.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "buildSrc"
