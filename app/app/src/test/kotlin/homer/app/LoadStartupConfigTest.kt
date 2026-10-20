package homer.app

import homer.app.models.RunnerConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The startup half of `main`: read, size-cap, parse and `@SYSENV`-resolve the config file. */
class LoadStartupConfigTest {
    @TempDir
    lateinit var dir: File

    private val path: String = System.getenv("PATH")

    private fun file(
        name: String,
        content: String,
    ): String = File(dir, name).apply { writeText(content) }.absolutePath

    @Test
    fun `no config path argument refuses to start`() {
        assertNull(loadStartupConfig(emptyArray()))
    }

    @Test
    fun `a missing config file refuses to start`() {
        assertNull(loadStartupConfig(arrayOf(File(dir, "absent.json").absolutePath)))
    }

    @Test
    fun `a config file over the 1 MiB bootstrap cap refuses to start without parsing it`() {
        val pad = "x".repeat(1024 * 1024)
        // Valid apart from its size, so only the cap can refuse it.
        val big = file("big.json", """{"agentsSearchPath":"a","workspaceRootPath":"w","databasePath":"d","globalEnv":{"PAD":"$pad"}}""")
        assertNull(loadStartupConfig(arrayOf(big)))
    }

    @Test
    fun `an unknown config file extension fails startup with an UnknownError`() {
        // An Error, so the parse-failure catch (Exception) does not turn it into a clean refusal.
        val error = assertFailsWith<UnknownError> { loadStartupConfig(arrayOf(file("config.txt", "agentsSearchPath: a"))) }
        assertEquals("Cant parse configuration file type txt", error.message)
    }

    @Test
    fun `a config file that does not parse refuses to start`() {
        assertNull(loadStartupConfig(arrayOf(file("config.json", "{ not json"))))
    }

    @Test
    fun `a json config is parsed, stamped with the server id and its SYSENV references resolved`() {
        val config =
            file(
                "config.json",
                """
                {
                  "agentsSearchPath": "agents",
                  "workspaceRootPath": "ws",
                  "databasePath": "@SYSENV:PATH",
                  "globalEnv": {"HOST_PATH": "@SYSENV:PATH", "PLAIN": "literal"},
                  "runners": {
                    "k8s": {"type": "kubernetes", "image": "ubuntu:24.04", "env": {"K8S_SECRET": "@SYSENV:PATH"}},
                    "mac": {
                      "type": "ssh", "host": "mac.example.com", "user": "runner", "auth": {"type": "agent"},
                      "remoteWorkspaceRoot": "/tmp/homer", "env": {"SSH_SECRET": "@SYSENV:PATH"}
                    },
                    "ext": {"type": "external", "token": "external-domain-token-0123456789", "env": {"EXT_SECRET": "@SYSENV:PATH"}}
                  }
                }
                """.trimIndent(),
            )

        val resolved = assertNotNull(loadStartupConfig(arrayOf(config)))

        assertEquals(serverId, resolved.globalEnv[EnvConstants.SERVER_ID])
        assertEquals(path, resolved.globalEnv["HOST_PATH"])
        assertEquals("literal", resolved.globalEnv["PLAIN"])
        assertEquals(path, resolved.databasePath)
        assertTrue("HOST_PATH" in resolved.sysEnvBackedGlobalEnvNames)
        assertTrue("PLAIN" !in resolved.sysEnvBackedGlobalEnvNames)
        assertEquals(path, (resolved.runners["k8s"] as RunnerConfig.Kubernetes).env["K8S_SECRET"])
        assertEquals(path, (resolved.runners["mac"] as RunnerConfig.Ssh).env["SSH_SECRET"])
        assertEquals(path, (resolved.runners["ext"] as RunnerConfig.External).env["EXT_SECRET"])
        assertTrue("K8S_SECRET" in resolved.sysEnvBackedRunnerEnvNames.getValue("k8s"))
        assertTrue("SSH_SECRET" in resolved.sysEnvBackedRunnerEnvNames.getValue("mac"))
        assertTrue("EXT_SECRET" in resolved.sysEnvBackedRunnerEnvNames.getValue("ext"))
    }

    @Test
    fun `a yaml config is parsed too`() {
        val config =
            file(
                "config.yaml",
                """
                agentsSearchPath: agents
                workspaceRootPath: ws
                databasePath: homer.db
                """.trimIndent(),
            )

        val resolved = assertNotNull(loadStartupConfig(arrayOf(config)))

        assertEquals("agents", resolved.agentsSearchPath)
        assertEquals("homer.db", resolved.databasePath)
        assertEquals(serverId, resolved.globalEnv[EnvConstants.SERVER_ID])
    }

    @Test
    fun `an unresolvable SYSENV reference refuses to start`() {
        val config =
            file(
                "config.json",
                """
                {"agentsSearchPath": "a", "workspaceRootPath": "w", "databasePath": "d",
                 "globalEnv": {"X": "@SYSENV:HOMER_TEST_VARIABLE_THAT_IS_NEVER_SET"}}
                """.trimIndent(),
            )

        assertNull(loadStartupConfig(arrayOf(config)))
    }
}
