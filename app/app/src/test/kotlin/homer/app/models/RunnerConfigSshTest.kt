package homer.app.models

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RunnerConfigSshTest {
    private val json = Json
    private val yaml = Yaml.default

    @Test
    fun `json ssh runner with key auth deserializes with defaults`() {
        val cfg =
            json.decodeFromString(
                RunnerConfig.serializer(),
                """{
                  "type":"ssh",
                  "host":"build01.internal",
                  "user":"homer",
                  "auth":{"type":"key","privateKeyPath":"/etc/homer/ssh/id_ed25519"},
                  "knownHostsPath":"/etc/homer/ssh/known_hosts",
                  "remoteWorkspaceRoot":"/home/homer/ws"
                }""",
            )
        val ssh = assertIs<RunnerConfig.Ssh>(cfg)
        assertEquals("build01.internal", ssh.host)
        assertEquals(22, ssh.port)
        assertEquals("homer", ssh.user)
        assertEquals(true, ssh.strictHostKeyChecking)
        assertEquals("/home/homer/ws", ssh.remoteWorkspaceRoot)
        assertEquals("/bin/sh", ssh.shell)
        assertIs<SshAuth.KeyFile>(ssh.auth)
    }

    @Test
    fun `json ssh runner with agent auth deserializes`() {
        val cfg =
            json.decodeFromString(
                RunnerConfig.serializer(),
                """{"type":"ssh","host":"h","user":"u","auth":{"type":"agent"},"remoteWorkspaceRoot":"/ws"}""",
            )
        val ssh = assertIs<RunnerConfig.Ssh>(cfg)
        assertIs<SshAuth.Agent>(ssh.auth)
    }

    @Test
    fun `yaml ssh runner with key auth deserializes with defaults using tag syntax`() {
        val cfg =
            yaml.decodeFromString(
                RunnerConfig.serializer(),
                """!<ssh>
host: build01.internal
user: homer
auth: !<key>
  privateKeyPath: /etc/homer/ssh/id_ed25519
knownHostsPath: /etc/homer/ssh/known_hosts
remoteWorkspaceRoot: /home/homer/ws""",
            )
        val ssh = assertIs<RunnerConfig.Ssh>(cfg)
        assertEquals("build01.internal", ssh.host)
        assertEquals(22, ssh.port)
        assertEquals("homer", ssh.user)
        assertEquals(true, ssh.strictHostKeyChecking)
        assertEquals("/home/homer/ws", ssh.remoteWorkspaceRoot)
        assertEquals("/bin/sh", ssh.shell)
        assertIs<SshAuth.KeyFile>(ssh.auth)
    }

    private fun ssh(extras: List<ExtraUploadSpec>) =
        RunnerConfig.Ssh(
            host = "h",
            user = "u",
            auth = SshAuth.Agent,
            remoteWorkspaceRoot = "/ws",
            extraUploads = extras,
        )

    @Test
    fun `extraUploads defaults to empty and yaml round-trips entries`() {
        val cfg =
            yaml.decodeFromString(
                RunnerConfig.serializer(),
                """!<ssh>
host: build01.internal
user: homer
auth: !<agent> {}
remoteWorkspaceRoot: /home/homer/ws
extraUploads:
  - local: /app/scripts
    remote: .homer/scripts
    env: SCRIPTS_DIR
  - local: /app/agents/shared/prompts
    remote: .homer/shared-prompts""",
            )
        val ssh = assertIs<RunnerConfig.Ssh>(cfg)
        assertEquals(2, ssh.extraUploads.size)
        assertEquals(ExtraUploadSpec("/app/scripts", ".homer/scripts", "SCRIPTS_DIR"), ssh.extraUploads[0])
        assertEquals(null, ssh.extraUploads[1].env)
        // default
        val bare =
            json.decodeFromString(
                RunnerConfig.serializer(),
                """{"type":"ssh","host":"h","user":"u","auth":{"type":"agent"},"remoteWorkspaceRoot":"/ws"}""",
            )
        assertEquals(emptyList(), assertIs<RunnerConfig.Ssh>(bare).extraUploads)
    }

    private fun validate(vararg extras: ExtraUploadSpec) = ssh(extras.toList()).validateExtraUploads("box")

    @Test
    fun `extraUploads rejects blank local and blank remote naming the runner alias`() {
        val ex = assertFailsWith<IllegalArgumentException> { validate(ExtraUploadSpec(local = " ", remote = ".homer/x")) }
        assertTrue(ex.message!!.contains("runner 'box'"))
        assertFailsWith<IllegalArgumentException> { validate(ExtraUploadSpec(local = "/a", remote = " ")) }
    }

    private fun sshWithRoot(root: String): RunnerConfig.Ssh {
        val encoded = Json.encodeToString(String.serializer(), root)
        val doc = """{"type":"ssh","host":"h","user":"u","auth":{"type":"agent"},"remoteWorkspaceRoot":$encoded}"""
        return json.decodeFromString(RunnerConfig.serializer(), doc) as RunnerConfig.Ssh
    }

    @Test
    fun `remoteWorkspaceRoot rejects blank, slash, relative and traversing values naming the runner alias`() {
        // [security_misconfiguration / CWE-732]: the root is what `cleanAfterFinish`
        // hands to `rm -rf` on the target; a value that widens it to the host is refused
        // at config load. See ADR-0067.
        for (root in listOf("", "/", "relative/ws", "/ws/../..", "/ws/./x", "/ws//x", "/ws/")) {
            val ex = assertFailsWith<IllegalArgumentException>(root) { sshWithRoot(root).validateRemoteWorkspaceRoot("box") }
            assertTrue(ex.message!!.contains("runner 'box' remoteWorkspaceRoot"), ex.message)
        }
    }

    @Test
    fun `AppConfig init validates remoteWorkspaceRoot naming the runner alias`() {
        for (root in listOf("/", "")) {
            val ex = assertFailsWith<IllegalArgumentException>(root) { appConfig(sshWithRoot(root)) }
            assertTrue(ex.message!!.contains("runner 'box' remoteWorkspaceRoot"), ex.message)
        }
    }

    @Test
    fun `remoteWorkspaceRoot accepts a normalized absolute directory`() {
        sshWithRoot("/ws").validateRemoteWorkspaceRoot("box")
        sshWithRoot("/home/homer/homer-workspaces").validateRemoteWorkspaceRoot("box")
    }

    @Test
    fun `extraUploads rejects absolute and dot-dot remote paths`() {
        assertFailsWith<IllegalArgumentException> { validate(ExtraUploadSpec(local = "/a", remote = "/abs/path")) }
        assertFailsWith<IllegalArgumentException> { validate(ExtraUploadSpec(local = "/a", remote = ".homer/../../etc")) }
    }

    @Test
    fun `extraUploads rejects reserved remote paths`() {
        listOf("workdir", "artifacts", ".homer/agent", ".homer/bin", ".homer/mcp.json").forEach { reserved ->
            assertFailsWith<IllegalArgumentException>("expected '$reserved' to be reserved") {
                validate(ExtraUploadSpec(local = "/a", remote = reserved))
            }
            assertFailsWith<IllegalArgumentException>("expected '$reserved/sub' to be reserved") {
                validate(ExtraUploadSpec(local = "/a", remote = "$reserved/sub"))
            }
        }
        // an ancestor of a reserved path merges into it on upload — reject
        assertFailsWith<IllegalArgumentException>("expected '.homer' to be reserved as an ancestor") {
            validate(ExtraUploadSpec(local = "/a", remote = ".homer"))
        }
        // sibling names that merely share a prefix are fine
        validate(ExtraUploadSpec(local = "/a", remote = "workdir-tools"))
        validate(ExtraUploadSpec(local = "/a", remote = ".homer/binaries"))
        validate(ExtraUploadSpec(local = "/a", remote = ".homer/scripts"))
    }

    @Test
    fun `extraUploads rejects non-normalized remote spellings`() {
        // './'-prefixed, double-slash, and trailing-slash spellings would evade
        // duplicate detection and leak into env values — only the canonical
        // form is accepted, and the error names it
        listOf("./.homer/scripts", ".homer//scripts", ".homer/scripts/").forEach { raw ->
            val ex =
                assertFailsWith<IllegalArgumentException>("expected '$raw' to be rejected") {
                    validate(ExtraUploadSpec(local = "/a", remote = raw))
                }
            assertTrue(ex.message!!.contains("'.homer/scripts'"))
        }
    }

    @Test
    fun `extraUploads rejects HMR_ and blank env names`() {
        assertFailsWith<IllegalArgumentException> { validate(ExtraUploadSpec(local = "/a", remote = ".homer/x", env = "HMR_WORK_DIR")) }
        assertFailsWith<IllegalArgumentException> { validate(ExtraUploadSpec(local = "/a", remote = ".homer/x", env = " ")) }
    }

    @Test
    fun `ssh runner rejects duplicate extraUploads remote paths and env names`() {
        val dupRemote =
            assertFailsWith<IllegalArgumentException> {
                validate(
                    ExtraUploadSpec("/a", ".homer/x"),
                    ExtraUploadSpec("/b", ".homer/x"),
                )
            }
        assertTrue(dupRemote.message!!.contains(".homer/x"))
        assertTrue(dupRemote.message!!.contains("runner 'box'"))
        assertFailsWith<IllegalArgumentException> {
            validate(
                ExtraUploadSpec("/a", ".homer/x", env = "TOOLS"),
                ExtraUploadSpec("/b", ".homer/y", env = "TOOLS"),
            )
        }
    }

    @Test
    fun `AppConfig init validates extraUploads naming the runner alias`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                AppConfig(
                    agentsSearchPath = "a",
                    workspaceRootPath = "w",
                    databasePath = "d",
                    runners = mapOf("build-box" to ssh(listOf(ExtraUploadSpec("/a", "workdir")))),
                )
            }
        assertTrue(ex.message!!.contains("runner 'build-box'"))
    }

    // ===== sftpTransferTimeoutSeconds (CWE-400) =====

    @Test
    fun `sftpTransferTimeoutSeconds defaults to 300`() {
        val cfg = ssh(emptyList())
        assertEquals(300L, cfg.sftpTransferTimeoutSeconds)
        appConfig(cfg) // parse-time fields must also pass AppConfig validation
    }

    @Test
    fun `sftpTransferTimeoutSeconds null disables the bound`() {
        appConfig(ssh(emptyList()).copy(sftpTransferTimeoutSeconds = null))
    }

    @Test
    fun `non-positive sftpTransferTimeoutSeconds is rejected naming the runner alias`() {
        listOf(0L, -1L).forEach { value ->
            val ex =
                assertFailsWith<IllegalArgumentException> {
                    appConfig(ssh(emptyList()).copy(sftpTransferTimeoutSeconds = value))
                }
            assertTrue(ex.message!!.contains("runner 'box'"))
            assertTrue(ex.message!!.contains("sftpTransferTimeoutSeconds"))
        }
    }

    private fun appConfig(cfg: RunnerConfig.Ssh) =
        AppConfig(
            agentsSearchPath = "a",
            workspaceRootPath = "w",
            databasePath = "d",
            runners = mapOf("box" to cfg),
        )
}
