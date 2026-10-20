package homer.app.services

import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.infra.AgentDirs
import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentFilesServiceTest {
    private lateinit var root: Path
    private lateinit var agentsService: AgentsService
    private lateinit var service: AgentFilesService

    @BeforeEach
    fun setup() {
        root = Files.createTempDirectory("agent-files-test")
        val agentDir = root.resolve("my_agent")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.json"), AgentFixtures.validAgentJson().toByteArray())
        val logger = TestFixtures.mockLogger()
        agentsService = AgentsService(root.toString(), logger)
        service = AgentFilesService(agentsService, logger)
    }

    @AfterEach
    fun teardown() {
        if (::root.isInitialized && Files.exists(root)) {
            root.toFile().setWritable(true, false)
            root.toFile().walkTopDown().forEach { it.setWritable(true, false) }
            root.toFile().deleteRecursively()
        }
    }

    // Use the loader-driven name (e.g. "test_agent") so tests don't hard-code the fixture name.
    private fun loadedName() = agentsService.agents.keys.first()

    @Test
    fun `list returns agent directory entries`() {
        val name = loadedName()
        Files.write(root.resolve("my_agent/README.md"), "hi".toByteArray())
        val res = service.list(name)
        assertIs<AgentFilesService.FileOpResult.Ok<List<AgentFilesService.FileEntry>>>(res)
        val paths = res.value.map { it.path }
        assertContains(paths, "agent.json")
        assertContains(paths, "README.md")
    }

    @Test
    fun `list returns NotFound for unknown agent`() {
        val res = service.list("not_exist")
        assertIs<AgentFilesService.FileOpResult.NotFound>(res)
    }

    @Test
    fun `read returns file content`() {
        val name = loadedName()
        val res = service.read(name, "agent.json")
        assertIs<AgentFilesService.FileOpResult.Ok<String>>(res)
        assertContains(res.value, "\"name\":")
    }

    @Test
    fun `read rejects directory traversal`() {
        val name = loadedName()
        Files.write(root.resolve("secret.txt"), "no".toByteArray())
        val res = service.read(name, "../secret.txt")
        assertIs<AgentFilesService.FileOpResult.BadRequest>(res)
    }

    @Test
    fun `read rejects symlink escaping the agent dir`() {
        val name = loadedName()
        val outside = Files.createTempDirectory("agent-files-test-outside")
        try {
            val outsideFile = outside.resolve("secret.txt").also { Files.write(it, "leak".toByteArray()) }
            Files.createSymbolicLink(root.resolve("my_agent/link"), outsideFile)
            val res = service.read(name, "link")
            assertIs<AgentFilesService.FileOpResult.BadRequest>(res)
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `read rejects absolute paths`() {
        val name = loadedName()
        val res = service.read(name, "/etc/passwd")
        assertIs<AgentFilesService.FileOpResult.BadRequest>(res)
    }

    @Test
    fun `read returns NotFound for missing file`() {
        val name = loadedName()
        val res = service.read(name, "missing.txt")
        assertIs<AgentFilesService.FileOpResult.NotFound>(res)
    }

    @Test
    fun `read rejects binary files`() {
        val name = loadedName()
        Files.write(root.resolve("my_agent/blob.bin"), byteArrayOf(0x00, 0x01, 0x02, 0x00))
        val res = service.read(name, "blob.bin")
        assertIs<AgentFilesService.FileOpResult.BadRequest>(res)
    }

    @Test
    fun `write creates and updates files and reloads`() {
        val name = loadedName()
        val res = service.write(name, "notes.txt", "hello world")
        assertIs<AgentFilesService.FileOpResult.Ok<Unit>>(res)
        assertEquals("hello world", Files.readString(root.resolve("my_agent/notes.txt")))
    }

    @Test
    fun `write rejects traversal`() {
        val name = loadedName()
        val res = service.write(name, "../escape.txt", "x")
        assertIs<AgentFilesService.FileOpResult.BadRequest>(res)
    }

    @Test
    fun `write validates agent yaml schema`() {
        val name = loadedName()
        val res = service.write(name, "agent.yaml", "not: valid agent yaml")
        assertIs<AgentFilesService.FileOpResult.SchemaInvalid>(res)
        assertTrue(res.errors.isNotEmpty())
    }

    /**
     * CWE-285: a definition write bootstraps a new
     * agent into the global namespace via the reload() the write triggers, so it
     * needs the create-agent grant (EDIT on the DECLARED name), not only EDIT on
     * the parent. Denied as not-found, hiding the declared agent's existence.
     */
    @Test
    fun `write of a definition declaring an ungranted name is denied as not-found (CWE-285)`() {
        val name = loadedName()
        val granted = mutableSetOf(name)
        val res =
            service.write(name, "agent.yaml", AgentFixtures.validAgentYaml(), authorizeCreateAgent = { it in granted })
        assertIs<AgentFilesService.FileOpResult.NotFound>(res)
        assertFalse(root.resolve("my_agent/agent.yaml.new").exists())
    }

    @Test
    fun `write of a definition declaring the agent's own name needs no extra grant`() {
        val name = loadedName()
        // The declared name is the agent being edited — its own EDIT grant covers it.
        val res = service.write(name, "agent.yaml", AgentFixtures.validAgentYaml(), authorizeCreateAgent = { it == "test_agent_yaml" })
        assertIs<AgentFilesService.FileOpResult.Ok<Unit>>(res)
    }

    @Test
    fun `write of a definition creating a new nested agent is denied without the create grant (CWE-285)`() {
        val name = loadedName()
        val target = root.resolve("my_agent/nodes/analysis")
        val res =
            service.write(name, "nodes/analysis/agent.yaml", "name: analysis\ncommands: []\n", authorizeCreateAgent = { false })
        assertIs<AgentFilesService.FileOpResult.NotFound>(res)
        assertFalse(target.exists(), "a denied create-by-write must not create the directory")
    }

    /**
     * CWE-285, fail closed: an unparsable
     * definition-named file declares nothing, so the create-grant check cannot key
     * on a name — the write is denied outright. Gating on "declared a name" alone
     * let the write through to plant the boundary directory on disk, which the
     * file API then denies as not-found and no grant can remove (§12 rule 3).
     */
    @Test
    fun `write of an unparsable definition-named file is denied as not-found (CWE-285)`() {
        val name = loadedName()
        val res = service.write(name, "nodes/analysis/agent.yaml", "not: valid agent yaml", authorizeCreateAgent = { true })
        assertIs<AgentFilesService.FileOpResult.NotFound>(res)
        assertFalse(root.resolve("my_agent/nodes/analysis").exists(), "a denied malformed definition must not create the directory")
        assertFalse(agentsService.agents.containsKey("analysis"))
    }

    @Test
    fun `write of an invalid nested definition is refused before it touches disk`() {
        val name = loadedName()
        val res = service.write(name, "nodes/analysis/agent.yaml", "name: analysis\ncommands: []\n", authorizeCreateAgent = { true })
        assertIs<AgentFilesService.FileOpResult.SchemaInvalid>(res)
        assertFalse(root.resolve("my_agent/nodes/analysis").exists(), "a refused definition must not plant the boundary directory")
    }

    @Test
    fun `write of a non-definition file needs no create-agent grant`() {
        val name = loadedName()
        val res = service.write(name, "notes.txt", "hello", authorizeCreateAgent = { false })
        assertIs<AgentFilesService.FileOpResult.Ok<Unit>>(res)
    }

    /**
     * CWE-400: a PUT with more than the path-segment cap
     * builds a directory chain `createDirectories` happily materializes, and every
     * later discovery walk pays for it out of a budget other agents share. Denied
     * before any disk touch (§12 rule 4, ADR-0068).
     */
    @Test
    fun `write of an over-deep path is denied and creates nothing`() {
        val name = loadedName()
        val deep =
            (1..AgentDirs.MAX_PATH_SEGMENTS + 1)
                .joinToString("/") { "s$it" } + "/notes.txt"
        val res = service.write(name, deep, "hello")
        assertIs<AgentFilesService.FileOpResult.BadRequest>(res)
        assertFalse(root.resolve("my_agent").resolve(deep).exists(), "a denied over-deep write must not create directories")
        // A path at the cap is fine — the bound is the segment count, not depth itself.
        val atCap =
            (2..AgentDirs.MAX_PATH_SEGMENTS)
                .joinToString("/") { "s$it" } + "/notes.txt"
        val ok = service.write(name, atCap, "hello")
        assertIs<AgentFilesService.FileOpResult.Ok<Unit>>(ok)
    }

    /**
     * CWE-400: a definition the walk never reaches is still a
     * nested-agent boundary, so writing one leaves a subtree the file API answers
     * `404` for — including the definition itself. Prevention is the fix: the
     * refusal below stops the write before any disk touch. Removing a boundary
     * that is already on disk (planted out-of-band, or before this gate shipped)
     * is an operator action — a nested definition is another agent's subtree
     * (ADR-0047), so its delete is answered `404` before the denial-path check
     * here is ever reached. The segment cap above is two segments looser than the
     * walk's depth stop, so it did not close this. A non-definition file at the
     * same places stays writable (§12 rule 4, ADR-0068).
     */
    @Test
    fun `write of an over-deep or hidden definition is denied and creates nothing (CWE-400)`() {
        val name = loadedName()
        // The deepest landing isTooDeep still allows: 8 directories + the definition
        // file = MAX_PATH_SEGMENTS, one directory past the walk's MAX_DEPTH.
        val overDeep =
            (1..AgentDirs.MAX_PATH_SEGMENTS - 1)
                .joinToString("/") { "s$it" } + "/agent.yaml"
        val overDeepRes = service.write(name, overDeep, AgentFixtures.validAgentYaml(), authorizeCreateAgent = { true })
        assertIs<AgentFilesService.FileOpResult.BadRequest>(overDeepRes)
        assertFalse(root.resolve("my_agent").resolve(overDeep).exists(), "a denied over-deep definition must not create the chain")
        assertFalse(root.resolve("my_agent/s1").exists(), "a denied definition write must create no directory at all")

        val hiddenRes = service.write(name, ".hidden/tool/agent.yaml", AgentFixtures.validAgentYaml(), authorizeCreateAgent = { true })
        assertIs<AgentFilesService.FileOpResult.BadRequest>(hiddenRes)
        assertFalse(root.resolve("my_agent/.hidden").exists(), "a denied hidden definition must create nothing")

        val hiddenDelete = service.delete(name, ".hidden/tool/agent.yaml")
        assertIs<AgentFilesService.FileOpResult.BadRequest>(hiddenDelete)
        assertFalse(root.resolve("my_agent/.hidden").exists(), "a denied delete must create nothing")

        // The refusal is definition-only: a plain file under a hidden directory,
        // and one at the segment cap, are still writable and readable.
        val hiddenFile = service.write(name, ".hidden/tool/notes.txt", "hello")
        assertIs<AgentFilesService.FileOpResult.Ok<Unit>>(hiddenFile)
        val readHidden = service.read(name, ".hidden/tool/notes.txt")
        assertIs<AgentFilesService.FileOpResult.Ok<String>>(readHidden)

        val atDeepestReachable =
            (1..AgentDirs.MAX_DEPTH - 1)
                .joinToString("/") { "s$it" } + "/agent.yaml"
        val deepest = service.write(name, atDeepestReachable, AgentFixtures.validAgentYaml(), authorizeCreateAgent = { true })
        assertIs<AgentFilesService.FileOpResult.Ok<Unit>>(deepest)
    }

    @Test
    fun `read of an over-deep path is denied as bad request`() {
        val name = loadedName()
        val deep =
            (1..AgentDirs.MAX_PATH_SEGMENTS + 1)
                .joinToString("/") { "s$it" } + "/notes.txt"
        val res = service.read(name, deep)
        assertIs<AgentFilesService.FileOpResult.BadRequest>(res)
    }

    @Test
    fun `write accepts valid agent yaml`() {
        val name = loadedName()
        val res = service.write(name, "agent.yaml", AgentFixtures.validAgentYaml())
        assertIs<AgentFilesService.FileOpResult.Ok<Unit>>(res)
    }

    @Test
    fun `read of a blank path is refused`() {
        val res = service.read(loadedName(), " ")
        assertEquals("Empty path", assertIs<AgentFilesService.FileOpResult.BadRequest>(res).msg)
    }

    @Test
    fun `read of an absolute path is refused`() {
        val res = service.read(loadedName(), "/etc/passwd")
        assertEquals("Path must be relative", assertIs<AgentFilesService.FileOpResult.BadRequest>(res).msg)
    }

    @Test
    fun `read for an unknown agent is not found`() {
        val res = service.read("not_exist", "agent.json")
        assertEquals("Agent 'not_exist' not found", assertIs<AgentFilesService.FileOpResult.NotFound>(res).msg)
    }

    @Test
    fun `read after the agent directory vanished is not found`() {
        val name = loadedName()
        root.resolve("my_agent").toFile().deleteRecursively()

        val res = service.read(name, "agent.json")

        assertEquals("Agent directory missing", assertIs<AgentFilesService.FileOpResult.NotFound>(res).msg)
    }

    @Test
    fun `write for an unknown agent is not found`() {
        val res = service.write("not_exist", "notes.txt", "x")
        assertEquals("Agent 'not_exist' not found", assertIs<AgentFilesService.FileOpResult.NotFound>(res).msg)
    }

    /** The `writable` flag alone denies, whatever the filesystem bits say (a root-run CI skips the POSIX variant below). */
    @Test
    fun `write to an agent loaded as not writable is Forbidden and writes nothing`() {
        val name = loadedName()
        val spy = spyk(agentsService)
        every { spy.agents } returns mapOf(name to agentsService.agents.getValue(name).copy(writable = false))

        val res = AgentFilesService(spy, TestFixtures.mockLogger()).write(name, "notes.txt", "nope")

        assertEquals("Agent directory is read-only", assertIs<AgentFilesService.FileOpResult.Forbidden>(res).msg)
        assertFalse(root.resolve("my_agent/notes.txt").exists())
    }

    @Test
    fun `write refuses when agent is read-only`() {
        assumeTrue(
            TestFixtures.posixPermissionsEnforced(),
            "POSIX read-only bits not enforced (root or non-POSIX FS); writable detection cannot be exercised",
        )
        val name = loadedName()
        val dir = root.resolve("my_agent")
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            agentsService.reload()
            val res = service.write(name, "notes.txt", "nope")
            assertIs<AgentFilesService.FileOpResult.Forbidden>(res)
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    // ========== ADR-0004: a permission change racing the write/delete/create ==========

    /**
     * ADR-0004: a permission change between the `writable` probe and the syscall
     * makes the filesystem call throw `AccessDeniedException`. The service must map
     * it to `Forbidden` (403) — never let it escape as an unhandled 500 — and a
     * denied write must not reload.
     */
    @Test
    fun `write of a target made read-only after the check is Forbidden and does not reload (ADR-0004)`() {
        assumeTrue(
            TestFixtures.posixPermissionsEnforced(),
            "POSIX read-only bits not enforced (root or non-POSIX FS); the race cannot be exercised",
        )
        val name = loadedName()
        val target = root.resolve("my_agent/notes.txt")
        Files.write(target, "original".toByteArray())
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r--r--r--"))
        try {
            val spy = spyk(agentsService)
            val raced = AgentFilesService(spy, TestFixtures.mockLogger())
            val res = raced.write(name, "notes.txt", "nope")
            assertIs<AgentFilesService.FileOpResult.Forbidden>(res)
            assertEquals("original", Files.readString(target), "a denied write must not modify the file")
            verify(exactly = 0) { spy.reload() }
        } finally {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"))
        }
    }

    /** ADR-0004: `createDirectories` racing a permission change is a 403, not a 500. */
    @Test
    fun `write whose parent directory cannot be created is Forbidden (ADR-0004)`() {
        assumeTrue(
            TestFixtures.posixPermissionsEnforced(),
            "POSIX read-only bits not enforced (root or non-POSIX FS); the race cannot be exercised",
        )
        val name = loadedName()
        val locked = root.resolve("my_agent/locked")
        Files.createDirectories(locked)
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            val res = service.write(name, "locked/new/notes.txt", "nope")
            assertIs<AgentFilesService.FileOpResult.Forbidden>(res)
            assertFalse(locked.resolve("new").exists(), "a denied write must not create the directory chain")
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    /**
     * ADR-0004: a denied `write()` must take back the directory chain its own
     * `createDirectories` built — a leftover directory is one every later
     * discovery walk pays for and the file API cannot remove. The write is denied
     * through the `createDirectoriesAndWrite` seam: once `createDirectories` has
     * run, the built chain is owner-writable, so no POSIX bit combination produces
     * this row.
     */
    @Test
    fun `write whose parent chain is built but whose write is denied removes the built directory (ADR-0004)`() {
        val spy = spyk(service)
        every { spy.createDirectoriesAndWrite(any(), any()) } answers {
            Files.createDirectories(arg<Path>(0).parent)
            throw AccessDeniedException("denied")
        }

        val name = loadedName()
        val res = spy.write(name, "new/notes.txt", "nope")

        assertIs<AgentFilesService.FileOpResult.Forbidden>(res)
        assertFalse(root.resolve("my_agent/new").exists(), "a denied write must not leave the built directory behind")
    }

    /** ADR-0004: the sibling delete path races the same way (read-only parent directory). */
    @Test
    fun `delete in a read-only parent directory is Forbidden and leaves the file (ADR-0004)`() {
        assumeTrue(
            TestFixtures.posixPermissionsEnforced(),
            "POSIX read-only bits not enforced (root or non-POSIX FS); the race cannot be exercised",
        )
        val name = loadedName()
        val locked = root.resolve("my_agent/locked")
        Files.createDirectories(locked)
        val target = locked.resolve("scratch.txt")
        Files.write(target, "x".toByteArray())
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            val res = service.delete(name, "locked/scratch.txt")
            assertIs<AgentFilesService.FileOpResult.Forbidden>(res)
            assertTrue(target.exists(), "a denied delete must leave the file in place")
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    /**
     * ADR-0004 symmetric case: `create()` probes `isWritable(root)` then writes, so
     * a search path that becomes unwritable in that window is a 403. A path that is
     * writable but not searchable (`rw-r--r--`) passes the probe yet fails the
     * syscall — the fixed message must not carry the path (§12 rule 7).
     */
    @Test
    fun `create when the search path becomes unwritable is Forbidden without leaking the path (ADR-0004)`() {
        assumeTrue(
            TestFixtures.posixPermissionsEnforced(),
            "POSIX read-only bits not enforced (root or non-POSIX FS); the race cannot be exercised",
        )
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rw-r--r--"))
        try {
            val res = service.create("brand_new")
            val forbidden = assertIs<AgentFilesService.FileOpResult.Forbidden>(res)
            assertFalse(forbidden.msg.contains(root.toString()), "the 403 message must not leak the path: ${forbidden.msg}")
        } finally {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    /**
     * ADR-0004: a denied `create()` must take back the directory its own
     * `createDirectories` built. A definition-less directory wedges the name — every
     * retry returns `409` on `dir.exists()` and the file API refuses to delete
     * directories. The template write is denied through the
     * `createDirectoriesAndTemplate` seam: once `createDirectories` has run, the new
     * directory is owner-writable, so no POSIX bit combination produces this row.
     */
    @Test
    fun `create whose template write is denied leaves no directory behind (ADR-0004)`() {
        val spy = spyk(service)
        every { spy.createDirectoriesAndTemplate(any(), any()) } answers {
            Files.createDirectories(arg(0))
            throw AccessDeniedException("denied")
        }

        val res = spy.create("brand_new")

        assertIs<AgentFilesService.FileOpResult.Forbidden>(res)
        assertFalse(root.resolve("brand_new").exists(), "a denied create must not leave the directory behind")
    }

    @Test
    fun `delete removes a file and reloads`() {
        val name = loadedName()
        Files.write(root.resolve("my_agent/scratch.txt"), "x".toByteArray())
        val res = service.delete(name, "scratch.txt")
        assertIs<AgentFilesService.FileOpResult.Ok<Unit>>(res)
        assertFalse(root.resolve("my_agent/scratch.txt").exists())
    }

    @Test
    fun `delete refuses agent definition`() {
        val name = loadedName()
        val res = service.delete(name, "agent.json")
        assertIs<AgentFilesService.FileOpResult.Forbidden>(res)
    }

    @Test
    fun `delete refuses agent definition via non-canonical path`() {
        val name = loadedName()
        val res = service.delete(name, "./agent.json")
        assertIs<AgentFilesService.FileOpResult.Forbidden>(res)
    }

    @Test
    fun `delete rejects traversal`() {
        val name = loadedName()
        val res = service.delete(name, "../hack")
        assertIs<AgentFilesService.FileOpResult.BadRequest>(res)
    }

    @Test
    fun `delete for an unknown agent is not found`() {
        val res = service.delete("not_exist", "scratch.txt")
        assertEquals("Agent 'not_exist' not found", assertIs<AgentFilesService.FileOpResult.NotFound>(res).msg)
    }

    @Test
    fun `delete from an agent loaded as not writable is Forbidden and leaves the file`() {
        val name = loadedName()
        Files.write(root.resolve("my_agent/scratch.txt"), "x".toByteArray())
        val spy = spyk(agentsService)
        every { spy.agents } returns mapOf(name to agentsService.agents.getValue(name).copy(writable = false))

        val res = AgentFilesService(spy, TestFixtures.mockLogger()).delete(name, "scratch.txt")

        assertEquals("Agent directory is read-only", assertIs<AgentFilesService.FileOpResult.Forbidden>(res).msg)
        assertTrue(root.resolve("my_agent/scratch.txt").exists())
    }

    @Test
    fun `delete of a missing file is not found`() {
        val res = service.delete(loadedName(), "missing.txt")
        assertEquals("File not found: missing.txt", assertIs<AgentFilesService.FileOpResult.NotFound>(res).msg)
    }

    @Test
    fun `delete refuses a directory and leaves it`() {
        val name = loadedName()
        Files.createDirectories(root.resolve("my_agent/notes"))

        val res = service.delete(name, "notes")

        assertEquals("Refusing to delete a directory", assertIs<AgentFilesService.FileOpResult.BadRequest>(res).msg)
        assertTrue(root.resolve("my_agent/notes").exists())
    }

    // ========== Nested agent boundary (ADR-0047, ADR-0068) ==========

    /** Creates a nested child agent under the parent fixture's directory. */
    private fun nestedChild(
        parentDir: String,
        relative: String,
        name: String,
    ) {
        val dir = root.resolve("$parentDir/$relative")
        Files.createDirectories(dir)
        val yaml =
            """
            name: $name
            description: nested child
            commands:
              - !<cmd>
                cmd: echo hello
                timeout: 60
            """.trimIndent()
        Files.write(dir.resolve("agent.yaml"), yaml.toByteArray())
        agentsService.reload()
    }

    @Test
    fun `list omits a nested agent subtree`() {
        val name = loadedName()
        nestedChild("my_agent", "nodes/analysis", "analysis")
        Files.write(root.resolve("my_agent/nodes/notes.txt"), "nested but not an agent".toByteArray())
        Files.write(root.resolve("my_agent/README.md"), "hi".toByteArray())

        val res = service.list(name)
        assertIs<AgentFilesService.FileOpResult.Ok<List<AgentFilesService.FileEntry>>>(res)
        val paths = res.value.map { it.path }
        assertContains(paths, "README.md")
        assertContains(paths, "nodes/notes.txt")
        assertFalse(
            paths.any { it.startsWith("nodes/analysis") },
            "a nested agent's subtree is not the parent's: $paths",
        )
    }

    @Test
    fun `list omits a symlink escaping the agent directory (CWE-59)`() {
        val name = loadedName()
        val outside = Files.createTempDirectory("agent-files-test-outside")
        try {
            val outsideFile = outside.resolve("secret.txt").also { Files.write(it, "leak".toByteArray()) }
            Files.createSymbolicLink(root.resolve("my_agent/escape"), outsideFile)

            val res = service.list(name)
            assertIs<AgentFilesService.FileOpResult.Ok<List<AgentFilesService.FileEntry>>>(res)
            val paths = res.value.map { it.path }
            assertFalse(
                paths.contains("escape"),
                "an escaping symlink must not be listed with its target's size/mtime: $paths",
            )
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `list still includes a symlink contained within the agent directory`() {
        val name = loadedName()
        Files.write(root.resolve("my_agent/target.txt"), "hi".toByteArray())
        Files.createSymbolicLink(root.resolve("my_agent/link"), root.resolve("my_agent/target.txt"))

        val res = service.list(name)
        assertIs<AgentFilesService.FileOpResult.Ok<List<AgentFilesService.FileEntry>>>(res)
        val paths = res.value.map { it.path }
        assertContains(paths, "link")
    }

    @Test
    fun `read of a nested agent definition is denied as not found`() {
        val name = loadedName()
        nestedChild("my_agent", "nodes/analysis", "analysis")

        val res = service.read(name, "nodes/analysis/agent.yaml")

        assertIs<AgentFilesService.FileOpResult.NotFound>(res)
    }

    @Test
    fun `write of a nested agent definition is denied and does not touch disk`() {
        val name = loadedName()
        nestedChild("my_agent", "nodes/analysis", "analysis")
        val target = root.resolve("my_agent/nodes/analysis/agent.yaml")
        val before = Files.readString(target)

        val res = service.write(name, "nodes/analysis/agent.yaml", "name: hijacked\ncommands: []\n")

        assertIs<AgentFilesService.FileOpResult.NotFound>(res)
        assertEquals(before, Files.readString(target), "a denied write must not modify the child's definition")
    }

    @Test
    fun `delete of a nested agent definition is denied and leaves the file`() {
        val name = loadedName()
        nestedChild("my_agent", "nodes/analysis", "analysis")
        val target = root.resolve("my_agent/nodes/analysis/agent.yaml")

        val res = service.delete(name, "nodes/analysis/agent.yaml")

        assertIs<AgentFilesService.FileOpResult.NotFound>(res)
        assertTrue(target.exists(), "a denied delete must leave the child's definition in place")
    }

    @Test
    fun `nested agent denial survives normalized path variants`() {
        val name = loadedName()
        nestedChild("my_agent", "nodes/analysis", "analysis")

        for (variant in listOf("./nodes/analysis/agent.yaml", "nodes/./analysis/agent.yaml", "x/../nodes/analysis/agent.yaml")) {
            assertIs<AgentFilesService.FileOpResult.NotFound>(
                service.write(name, variant, "name: hijacked\ncommands: []\n"),
                "write of '$variant' must be denied",
            )
            assertIs<AgentFilesService.FileOpResult.NotFound>(
                service.delete(name, variant),
                "delete of '$variant' must be denied",
            )
        }
    }

    @Test
    fun `read and write reach a nested agent's plain sibling directory`() {
        val name = loadedName()
        nestedChild("my_agent", "nodes/analysis", "analysis")
        Files.write(root.resolve("my_agent/nodes/notes.txt"), "nested but not an agent".toByteArray())

        assertIs<AgentFilesService.FileOpResult.Ok<String>>(service.read(name, "nodes/notes.txt"))
        assertIs<AgentFilesService.FileOpResult.Ok<Unit>>(service.write(name, "nodes/more.txt", "ok"))
    }

    /**
     * CWE-59: the nested-agent deny must run on the
     * RESOLVED path, not the lexical candidate. A parent-relative symlink stats
     * as its own name, so a lexical gate misses it and hands the child's file to
     * read/write/delete. One denial row per symlink placement, each asserting the
     * child's file is untouched.
     */
    @Test
    fun `symlink at the child's definition is denied as not found (CWE-59)`() {
        val name = loadedName()
        nestedChild("my_agent", "nodes/analysis", "analysis")
        val target = root.resolve("my_agent/nodes/analysis/agent.yaml")
        val before = Files.readString(target)
        Files.createSymbolicLink(root.resolve("my_agent/notes.txt"), target)

        assertIs<AgentFilesService.FileOpResult.NotFound>(service.read(name, "notes.txt"))
        assertIs<AgentFilesService.FileOpResult.NotFound>(service.write(name, "notes.txt", "name: hijacked\ncommands: []\n"))
        assertIs<AgentFilesService.FileOpResult.NotFound>(service.delete(name, "notes.txt"))
        assertEquals(before, Files.readString(target), "a denied symlinked access must not touch the child's definition")
        assertFalse(Files.exists(root.resolve("my_agent/nodes/analysis/notes.txt")))
    }

    /**
     * CWE-59: the definition-file checks key on the
     * path a request lands on, not the alias it named. A symlink inside the agent
     * dir pointing at the root definition, or at a definition that does not exist
     * yet, must get the same schema, grant and delete rules as the direct path.
     */
    @Test
    fun `a symlink alias to a definition gets the definition's checks (CWE-59)`() {
        val name = loadedName()
        val rootDef = root.toAbsolutePath().resolve("my_agent/agent.json")
        val before = Files.readString(rootDef)
        Files.createSymbolicLink(root.resolve("my_agent/notes.txt"), rootDef)
        // Dangling: the nested definition does not exist yet, so nothing denies the path.
        Files.createSymbolicLink(root.resolve("my_agent/new-def.txt"), root.toAbsolutePath().resolve("my_agent/nodes/x/agent.yaml"))

        assertIs<AgentFilesService.FileOpResult.SchemaInvalid>(service.write(name, "notes.txt", "not json"))
        assertEquals(before, Files.readString(rootDef), "an invalid body must not reach the definition through an alias")
        assertIs<AgentFilesService.FileOpResult.Forbidden>(service.delete(name, "notes.txt"))
        assertTrue(Files.exists(rootDef))
        assertIs<AgentFilesService.FileOpResult.NotFound>(
            service.write(
                name,
                "new-def.txt",
                "name: planted\ncommands:\n  - !<cmd>\n    cmd: echo hi\n",
                authorizeCreateAgent = { false },
            ),
        )
        assertFalse(Files.exists(root.resolve("my_agent/nodes/x")), "an ungranted definition must not land through a dangling alias")
    }

    @Test
    fun `symlink in a parent component is denied as not found (CWE-59)`() {
        val name = loadedName()
        nestedChild("my_agent", "nodes/analysis", "analysis")
        val target = root.resolve("my_agent/nodes/analysis/agent.yaml")
        val before = Files.readString(target)
        Files.createSymbolicLink(root.resolve("my_agent/shortcut"), root.resolve("my_agent/nodes/analysis"))

        assertIs<AgentFilesService.FileOpResult.NotFound>(service.read(name, "shortcut/agent.yaml"))
        assertIs<AgentFilesService.FileOpResult.NotFound>(service.write(name, "shortcut/agent.yaml", "name: hijacked\ncommands: []\n"))
        assertIs<AgentFilesService.FileOpResult.NotFound>(service.delete(name, "shortcut/agent.yaml"))
        assertEquals(before, Files.readString(target), "a denied symlinked access must not touch the child's definition")
    }

    /**
     * CWE-59: a write to a path that does not exist
     * yet follows a dangling symlink at the candidate, and the containment check
     * only saw the link's lexical name — so a link inside the parent could steer a
     * new file, or a whole definition, into a nested agent's subtree with no grant
     * on the child. One denial row per link placement, each asserting the child's
     * subtree is unchanged (the live-link variants live in the two tests above).
     */
    @Test
    fun `a dangling symlink into a nested agent's subtree is denied for new files (CWE-59)`() {
        val name = loadedName()
        nestedChild("my_agent", "nodes/analysis", "analysis")
        // Link targets are absolute: a relative target resolves against the link's
        // own directory, which would not point into the child at all.
        val childDir = root.toAbsolutePath().resolve("my_agent/nodes/analysis")
        // A definition-less subdirectory of the child, reached by a parent-component link.
        Files.createDirectories(childDir.resolve("scratch"))
        // A leaf link to a NEW file inside the child.
        Files.createSymbolicLink(root.resolve("my_agent/link-new-file.txt"), childDir.resolve("scratch/planted.txt"))
        // A leaf link to a NEW definition inside the child.
        Files.createSymbolicLink(root.resolve("my_agent/link-new-def.yaml"), childDir.resolve("agent.yaml"))
        // A parent-component link into the definition-less subdirectory.
        Files.createSymbolicLink(root.resolve("my_agent/link-scratch"), childDir.resolve("scratch"))

        val before = Files.readString(root.resolve("my_agent/nodes/analysis/agent.yaml"))

        assertIs<AgentFilesService.FileOpResult.NotFound>(service.write(name, "link-new-file.txt", "planted"))
        assertIs<AgentFilesService.FileOpResult.NotFound>(service.write(name, "link-scratch/new.txt", "planted"))
        assertIs<AgentFilesService.FileOpResult.NotFound>(service.write(name, "link-scratch/agent.yaml", "name: planted\ncommands: []\n"))
        assertIs<AgentFilesService.FileOpResult.NotFound>(service.write(name, "link-new-def.yaml", "name: planted\ncommands: []\n"))

        assertFalse(Files.exists(root.resolve("my_agent/nodes/analysis/scratch/planted.txt")), "no file may land in the child's subtree")
        assertFalse(Files.exists(root.resolve("my_agent/nodes/analysis/scratch/new.txt")))
        assertFalse(Files.exists(root.resolve("my_agent/nodes/analysis/scratch/agent.yaml")))
        assertEquals(
            before,
            Files.readString(root.resolve("my_agent/nodes/analysis/agent.yaml")),
            "the child's definition must be untouched",
        )
        assertFalse(agentsService.agents.containsKey("planted"))
    }

    @Test
    fun `a child agent's own files remain reachable through the child`() {
        val name = loadedName()
        nestedChild("my_agent", "nodes/analysis", "analysis")
        Files.write(root.resolve("my_agent/nodes/analysis/notes.txt"), "child owned".toByteArray())

        val childRes = service.read("analysis", "notes.txt")
        assertIs<AgentFilesService.FileOpResult.Ok<String>>(childRes)
        assertEquals("child owned", childRes.value)
        assertIs<AgentFilesService.FileOpResult.Ok<String>>(service.read("analysis", "agent.yaml"))
    }

    @Test
    fun `create makes a new agent directory`() {
        val res = service.create("brand_new")
        assertIs<AgentFilesService.FileOpResult.Ok<String>>(res)
        assertEquals("brand_new", res.value)
        assertTrue(root.resolve("brand_new/agent.yaml").exists())
        assertTrue(agentsService.agents.containsKey("brand_new"))
    }

    @Test
    fun `create rejects invalid names`() {
        val res = service.create("bad!name")
        assertIs<AgentFilesService.FileOpResult.BadRequest>(res)
    }

    @Test
    fun `create rejects duplicate names`() {
        val res = service.create("my_agent")
        assertIs<AgentFilesService.FileOpResult.Conflict>(res)
    }

    // ========== Config-File Size Cap Tests (audit P1 #14) ==========

    @Test
    fun `write rejects payloads larger than maxConfigFileBytes with BadRequest`() {
        val name = loadedName()
        val logger = TestFixtures.mockLogger()
        val cappedService = AgentFilesService(agentsService, logger, maxConfigFileBytes = 512L)

        // 2 KiB ASCII (1 byte per char) — comfortably exceeds the 512-byte cap.
        val oversized = "x".repeat(2048)

        val res = cappedService.write(name, "README.md", oversized)

        val bad = assertIs<AgentFilesService.FileOpResult.BadRequest>(res)
        assertTrue(
            bad.msg.contains("2048") && bad.msg.contains("512"),
            "Error should mention payload size and cap; got: ${bad.msg}",
        )
        // File must NOT have been written. The agent dir on disk is "my_agent";
        // `name` is the loader-derived name from agent.json ("test_agent").
        assertFalse(
            root.resolve("my_agent/README.md").exists(),
            "Oversized write should not have hit disk",
        )
    }

    @Test
    fun `write accepts payloads up to maxConfigFileBytes`() {
        val name = loadedName()
        val logger = TestFixtures.mockLogger()
        val cappedService = AgentFilesService(agentsService, logger, maxConfigFileBytes = 4096L)

        val payload = "y".repeat(2048) // well under the 4 KiB cap

        val res = cappedService.write(name, "NOTES.md", payload)

        assertIs<AgentFilesService.FileOpResult.Ok<Unit>>(res)
        assertTrue(
            root.resolve("my_agent/NOTES.md").exists(),
            "Under-cap write should land on disk",
        )
    }
}
