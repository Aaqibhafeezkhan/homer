package homer.app.fixtures

import java.io.File

/**
 * A Dockerfile read the way Docker reads it, for tests that guard `app/Dockerfile`: comments
 * dropped, `\`-continued lines joined into one instruction, instructions grouped by `FROM` stage.
 */
internal class DockerfileStages(
    text: String,
) {
    /** One stage: its `FROM` base, its `AS` name (lowercased, as Docker resolves names), its instructions. */
    data class Stage(
        val base: String,
        val name: String?,
        val instructions: List<String>,
    )

    /** Every instruction in file order, `FROM` lines included. */
    val instructions: List<String> =
        text
            .replace(Regex("""\\\n"""), " ")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    val stages: List<Stage> =
        instructions.fold(mutableListOf<Stage>()) { acc, line ->
            if (line.startsWith("FROM ", ignoreCase = true)) {
                // FROM [--platform=…] <image> [AS <name>]
                val parts = line.split(Regex("""\s+""")).drop(1).filterNot { it.startsWith("--") }
                val name = parts.getOrNull(2)?.takeIf { parts[1].equals("AS", ignoreCase = true) }?.lowercase()
                acc += Stage(parts[0], name, emptyList())
            } else if (acc.isNotEmpty()) {
                acc[acc.lastIndex] = acc.last().let { it.copy(instructions = it.instructions + line) }
            }
            acc
        }

    /** The stage named [name]; fails naming the guard to move when the stage is gone. */
    fun stage(name: String): Stage =
        stages.firstOrNull { it.name == name.lowercase() } ?: error("Dockerfile has no `$name` stage; move this guard with it.")

    /** Stage [name], then each stage of this file it is built `FROM`, in that order. */
    fun chain(name: String): List<Stage> =
        generateSequence(stage(name)) { s -> stages.firstOrNull { it.name == s.base.lowercase() } }.toList()

    /** Whether [base] names a stage of this file rather than an image. */
    fun isStage(base: String): Boolean = stages.any { it.name == base.lowercase() }

    companion object {
        /** The repository's `app/Dockerfile`. */
        val app: DockerfileStages by lazy { DockerfileStages(File(repoRoot, "app/Dockerfile").readText()) }

        /** Every package [stage]'s `RUN` instructions pass to `apk add`, in any `&&`- or `;`-chained segment. */
        fun apkPackages(stage: Stage): Set<String> =
            stage.instructions
                .filter { it.startsWith("RUN ") }
                .flatMap { it.removePrefix("RUN ").split(Regex("""&&|;""")) }
                .map { it.trim().split(Regex("""\s+""")) }
                .filter { it.size > 2 && it[0] == "apk" && it[1] == "add" }
                .flatMap { tokens ->
                    // `--virtual <name>` takes a value; other flags stand alone.
                    tokens
                        .drop(2)
                        .fold(Pair(emptyList<String>(), false)) { (pkgs, skipNext), t ->
                            when {
                                skipNext -> pkgs to false
                                t == "--virtual" || t == "-t" -> pkgs to true
                                t.startsWith("-") -> pkgs to false
                                else -> (pkgs + t) to false
                            }
                        }.first
                }.toSet()
    }
}
