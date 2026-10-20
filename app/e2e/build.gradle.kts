plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    // Deliberately NO project(":app") dependency: this suite is a black-box
    // client of the shipped HTTP contract. Response shapes are re-declared as
    // minimal DTOs in src/test so serialization regressions in :app fail here
    // instead of being shared by both sides. See README.md.
    testImplementation(libs.kotlinTest)
    testImplementation(libs.kotlinTestJunit5)
    testImplementation(libs.junitJupiter)
    testImplementation(libs.kotlinxSerialization)
    testImplementation(libs.kotlinxCoroutines)
    testImplementation(libs.bundles.ktorClient)
    testImplementation(libs.ktorSerializationKotlinxJson)
    testImplementation(libs.slf4jSimple)
}

tasks.test {
    // Live-server tests: results depend on the server under test, never on
    // task inputs — always rerun.
    outputs.upToDateWhen { false }
    environment("E2E_BASE_URL", System.getenv("E2E_BASE_URL") ?: "")
    environment("E2E_TOKEN", System.getenv("E2E_TOKEN") ?: "")
}
