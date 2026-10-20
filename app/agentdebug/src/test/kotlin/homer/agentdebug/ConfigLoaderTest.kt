package homer.agentdebug

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigLoaderTest {
    private fun configFile(
        tempDir: Path,
        globalEnvYaml: String,
    ): String {
        val path = tempDir.resolve("app.yaml")
        path.writeText(
            """
            agentsSearchPath: "${tempDir.resolve("agents")}"
            workspaceRootPath: "${tempDir.resolve("ws")}"
            databasePath: "${tempDir.resolve("db.sqlite")}"
            globalEnv:
            $globalEnvYaml
            """.trimIndent(),
        )
        return path.toString()
    }

    @Test
    fun `a whole-value @SYSENV globalEnv captures the key and the host variable`(
        @TempDir tempDir: Path,
    ) {
        // No --env here: the placeholder resolves from the real host environment, so
        // the name set must come from the raw map before resolveMapValues erases it.
        val path = configFile(tempDir, "  TOKEN: \"@SYSENV:PATH\"")

        val config = ConfigLoader.loadAppConfig(path)

        assertEquals(System.getenv("PATH"), config.globalEnv["TOKEN"])
        assertEquals(setOf("TOKEN", "PATH"), config.sysEnvBackedGlobalEnvNames)
    }

    @Test
    fun `an embedded @SYSENV globalEnv captures the key and the referenced host name`(
        @TempDir tempDir: Path,
    ) {
        val path = configFile(tempDir, "  HEADER: \"Bearer @SYSENV:PATH\"")

        val config = ConfigLoader.loadAppConfig(path)

        assertEquals("Bearer ${System.getenv("PATH")}", config.globalEnv["HEADER"])
        assertEquals(setOf("HEADER", "PATH"), config.sysEnvBackedGlobalEnvNames)
    }

    @Test
    fun `names are captured before the --env literal rewrite replaces the placeholders`(
        @TempDir tempDir: Path,
    ) {
        // The --env path rewrites @SYSENV:REPO_TOKEN to a literal before decode, so
        // without the pre-rewrite decode the placeholder would be gone and the set empty.
        val path = configFile(tempDir, "  TOKEN: \"@SYSENV:REPO_TOKEN\"")

        val config = ConfigLoader.loadAppConfig(path, mapOf("REPO_TOKEN" to "s3cr3t-value"))

        assertEquals("s3cr3t-value", config.globalEnv["TOKEN"])
        assertEquals(setOf("TOKEN", "REPO_TOKEN"), config.sysEnvBackedGlobalEnvNames)
    }

    @Test
    fun `a literal globalEnv value contributes no secret names`(
        @TempDir tempDir: Path,
    ) {
        val path = configFile(tempDir, "  PLAIN: \"not-a-secret\"")

        val config = ConfigLoader.loadAppConfig(path)

        assertEquals("not-a-secret", config.globalEnv["PLAIN"])
        assertEquals(emptySet(), config.sysEnvBackedGlobalEnvNames)
    }

    @Test
    fun `a malformed config fails the load instead of loading with an empty secret set`(
        @TempDir tempDir: Path,
    ) {
        // Fail closed: the name capture rides on the decode of the raw text, so a
        // decode failure must throw rather than continue with an empty needle set.
        val path = tempDir.resolve("app.yaml").apply { writeText("globalEnv: [unclosed") }

        assertFailsWith<Exception> { ConfigLoader.loadAppConfig(path.toString()) }
    }

    @Test
    fun `an env file skips blanks and comments, trims, and unquotes double-quoted values`(
        @TempDir tempDir: Path,
    ) {
        val path = tempDir.resolve("debug.env")
        path.writeText(
            """
            # a comment

            PLAIN=value
              SPACED  =  padded value
            QUOTED="quoted value"
            HALF="unterminated
            EQUALS=a=b
            EMPTY=
            LONE="
            """.trimIndent(),
        )

        assertEquals(
            mapOf(
                "PLAIN" to "value",
                "SPACED" to "padded value",
                "QUOTED" to "quoted value",
                "HALF" to "\"unterminated",
                "EQUALS" to "a=b",
                "EMPTY" to "",
                "LONE" to "\"",
            ),
            ConfigLoader.loadEnvFile(path.toString()),
        )
    }

    @Test
    fun `an env file line without a key fails naming the file and the line`(
        @TempDir tempDir: Path,
    ) {
        val path = tempDir.resolve("bad.env")
        path.writeText("GOOD=1\n=no-key\n")

        val error = assertFailsWith<IllegalArgumentException> { ConfigLoader.loadEnvFile(path.toString()) }
        assertEquals("Invalid line in env file '$path': =no-key", error.message)
    }

    @Test
    fun `an env file line without an equals sign fails`(
        @TempDir tempDir: Path,
    ) {
        val path = tempDir.resolve("bad.env")
        path.writeText("JUST_A_WORD\n")

        assertFailsWith<IllegalArgumentException> { ConfigLoader.loadEnvFile(path.toString()) }
    }

    @Test
    fun `a missing env file fails`(
        @TempDir tempDir: Path,
    ) {
        val error = assertFailsWith<IllegalArgumentException> { ConfigLoader.loadEnvFile(tempDir.resolve("absent.env").toString()) }
        assertEquals("Env file not found: ${tempDir.resolve("absent.env")}", error.message)
    }
}
