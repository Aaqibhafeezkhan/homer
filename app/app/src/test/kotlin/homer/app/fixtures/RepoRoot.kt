package homer.app.fixtures

import java.io.File

/** Set by `:app`'s test task to the repository root, as the architecture module's is. */
internal const val REPO_ROOT_PROPERTY = "homer.repoRoot"

/**
 * The repository root, for tests that read files outside the Gradle build (compose files,
 * workflows, examples, schemas). Gradle passes it as [REPO_ROOT_PROPERTY]; a runner that does
 * not (an IDE's own JUnit launcher) falls back to walking up from `user.dir` to the first
 * directory holding `app/settings.gradle.kts`.
 */
internal val repoRoot: File by lazy {
    System.getProperty(REPO_ROOT_PROPERTY)?.let(::File) ?: run {
        val start = File(System.getProperty("user.dir")).absoluteFile
        generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "app/settings.gradle.kts").isFile }
            ?: error("$REPO_ROOT_PROPERTY is unset and no app/settings.gradle.kts is above $start")
    }
}
