// Artifactory dependency proxy for CI. Applied from both settings scripts
// (`settings.gradle.kts`, `buildSrc/settings.gradle.kts`); it only adds a
// repository when ARTIFACTORY_URL, ARTIFACTORY_USER and ARTIFACTORY_TOKEN are
// all set (CI/CD variables), so local builds are untouched and a
// CI job without the variables falls back to Maven Central. ARTIFACTORY_URL is
// a bare https origin: a path or embedded credentials fail the build. Plugin repositories
// cannot be configured from an applied script — each settings file repeats
// the same gate inside its own `pluginManagement` block.
val artifactoryUrl = providers.environmentVariable("ARTIFACTORY_URL").orNull?.takeIf { it.isNotBlank() }
val artifactoryUser = providers.environmentVariable("ARTIFACTORY_USER").orNull?.takeIf { it.isNotBlank() }
val artifactoryToken = providers.environmentVariable("ARTIFACTORY_TOKEN").orNull?.takeIf { it.isNotBlank() }

if (artifactoryUrl != null && artifactoryUser != null && artifactoryToken != null) {
    require(Regex("https://[A-Za-z0-9.-]+(:[0-9]+)?").matches(artifactoryUrl)) { "ARTIFACTORY_URL must be https://<host>[:port]" }
    dependencyResolutionManagement {
        @Suppress("UnstableApiUsage")
        repositories {
            maven {
                name = "artifactory"
                // CWE-319: the credentials below travel in a Basic
                // Authorization header, so every Artifactory origin stays https and no
                // site may re-add isAllowInsecureProtocol — a handshake failure must
                // fail the build rather than downgrade.
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
    }
}
