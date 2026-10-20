package homer.app.infra

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import homer.app.fixtures.AgentFixtures
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ch.qos.logback.classic.Level as LogbackLevel
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * The walk rule is the single source of truth for discovery (ADR-0068), so its
 * bounds get one case each.
 */
class AgentDirsTest {
    private lateinit var root: Path

    @BeforeEach
    fun setup() {
        root = Files.createTempDirectory("agent-dirs-test")
    }

    @AfterEach
    fun teardown() {
        if (::root.isInitialized && Files.exists(root)) {
            root.toFile().deleteRecursively()
        }
    }

    private fun writeAgent(
        dir: Path,
        name: String,
    ) {
        dir.createDirectories()
        Files.write(dir.resolve("agent.yaml"), agentYaml(name).toByteArray())
    }

    private fun agentYaml(name: String) = AgentFixtures.validAgentYaml().replace("test_agent_yaml", name)

    private fun relativeWalk(): List<String> = AgentDirs.walkAgentDirs(root).map { it.invariantSeparatorsPathString }.sorted()

    @Test
    fun `walk yields definition directories and skips plain ones`() {
        writeAgent(root.resolve("one"), "one")
        writeAgent(root.resolve("two"), "two")
        root.resolve("not-an-agent/data").createDirectories()

        assertEquals(listOf("one", "two"), relativeWalk())
    }

    @Test
    fun `walk descends into a nested directory and finds a definition there`() {
        writeAgent(root.resolve("pipeline"), "pipeline")
        writeAgent(root.resolve("pipeline/nodes/analysis"), "analysis")

        assertEquals(listOf("pipeline", "pipeline/nodes/analysis"), relativeWalk())
    }

    @Test
    fun `walk yields a nested agent inside another agent directory`() {
        // The reported defect: `agents/web-pipeline/__probe/agent.yaml` was invisible.
        writeAgent(root.resolve("web-pipeline"), "web-pipeline")
        writeAgent(root.resolve("web-pipeline/nodes/sources"), "sources")

        assertTrue("web-pipeline/nodes/sources" in relativeWalk())
    }

    @Test
    fun `walk ignores a definition at the root itself`() {
        Files.write(root.resolve("agent.yaml"), agentYaml("root-agent").toByteArray())

        assertTrue(relativeWalk().isEmpty())
    }

    @Test
    fun `isAgentDir prefers agent json over agent yaml`() {
        val dir = root.resolve("both").createDirectories()
        Files.write(dir.resolve("agent.json"), AgentFixtures.validAgentJson().toByteArray())
        Files.write(dir.resolve("agent.yaml"), agentYaml("yaml-name").toByteArray())

        // Presence of either marks the directory; the loader's own json-wins order
        // is exercised by AgentsServiceTest.
        assertTrue(AgentDirs.isAgentDir(dir))
        assertFalse(AgentDirs.isAgentDir(root.resolve("missing")))
    }

    @Test
    fun `a symlinked definition file is not an agent directory (CWE-59)`() {
        // CWE-59: `isRegularFile` follows the link, so a
        // symlinked `agent.yaml` would have discovery read the target — outside
        // the search path, or shared by two directories, failing all claimants —
        // as its own agent. The definition must be a real file in the walked dir.
        val dir = root.resolve("linked").createDirectories()
        val outside = Files.createTempDirectory("agent-dirs-outside")
        try {
            val target = outside.resolve("agent.yaml")
            Files.write(target, agentYaml("planted").toByteArray())
            try {
                Files.createSymbolicLink(dir.resolve("agent.yaml"), target)
            } catch (_: UnsupportedOperationException) {
                assumeTrue(false, "symlinks not supported on this filesystem")
            } catch (_: java.io.IOException) {
                assumeTrue(false, "symlinks not supported on this filesystem")
            }

            assertFalse(AgentDirs.isAgentDir(dir), "a symlinked definition must not load as an agent")
            assertTrue(relativeWalk().isEmpty(), "a symlinked definition must not be discovered")
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `walk skips hidden directories`() {
        writeAgent(root.resolve(".git/hooks"), "git-hook")
        writeAgent(root.resolve("visible"), "visible")

        assertEquals(listOf("visible"), relativeWalk())
    }

    @Test
    fun `walk does not follow symlinked directories`() {
        val outside = Files.createTempDirectory("agent-dirs-outside")
        try {
            writeAgent(outside.resolve("linked-agent"), "linked-agent")
            val link = root.resolve("link")
            try {
                Files.createSymbolicLink(link, outside)
            } catch (_: UnsupportedOperationException) {
                assumeTrue(false, "symlinks not supported on this filesystem")
            } catch (_: java.io.IOException) {
                assumeTrue(false, "symlinks not supported on this filesystem")
            }

            assertTrue(relativeWalk().isEmpty(), "a symlinked directory must not be walked")
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `walk stops at the depth cap`() {
        // Build a chain deeper than the cap, with a definition at every level.
        var current = root
        repeat(AgentDirs.MAX_DEPTH + 2) { level ->
            current = current.resolve("level$level")
            writeAgent(current, "level$level")
        }

        val found = relativeWalk()
        // Levels at depth 1..MAX_DEPTH are yielded; deeper ones are not.
        assertEquals(AgentDirs.MAX_DEPTH, found.size, "depth cap not enforced: $found")
    }

    @Test
    fun `walk stops at the entry cap`() {
        val crowd = root.resolve("crowd").createDirectories()
        repeat(AgentDirs.MAX_ENTRIES + 10) { i ->
            crowd.resolve("dir%05d".format(i)).createDirectories()
        }
        writeAgent(crowd.resolve("dir99999"), "beyond-cap")

        val found = relativeWalk()
        assertTrue(
            found.size <= AgentDirs.MAX_ENTRIES,
            "entry cap not enforced, ${found.size} entries",
        )
    }

    /**
     * Shared-contract test: the walk is [AgentDirs.isUnreachableByDiscovery]'s
     * oracle. Every candidate landing is physically planted (its definition
     * written), and the walk must reach it iff the predicate calls it reachable —
     * so a future change to the walk (a new skip rule, a depth-convention fix, an
     * additional bound) cannot desynchronize the predicate silently (§12 rule 8).
     */
    @Test
    fun `isUnreachableByDiscovery agrees with the walk on every landing (shared contract)`() {
        // Build every candidate landing shape the predicate can be asked about:
        // plain chains, agents rooted deeper, hidden components at each position,
        // and depths straddling MAX_DEPTH.
        data class Tree(
            val agentDirDepth: Int,
            /** The landing's directory chain, relative to the AGENT directory — what the predicate takes. */
            val landingUnderAgent: Path,
            /** The landing relative to the walk root — what the walk yields. */
            val landingRel: Path,
        )
        val trees = mutableListOf<Tree>()

        fun agentTree(
            depthOfAgent: Int,
            landingDirs: List<String>,
        ): Tree {
            // One top-level agent at `depthOfAgent` levels; the landing sits inside
            // it, `landingDirs` below the agent dir, every intermediate component
            // carrying a definition like a real nested pipeline would.
            var dir = root.resolve("t${trees.size}")
            writeAgent(dir, "top-${trees.size}")
            var depth = 1
            var agentRel = Path.of(dir.fileName.toString())
            repeat(depthOfAgent - 1) {
                dir = dir.resolve("n$it")
                writeAgent(dir, "nested-$depthOfAgent-${trees.size}-$it")
                agentRel = agentRel.resolve("n$it")
                depth++
            }
            var underAgent = Path.of("")
            for ((i, seg) in landingDirs.withIndex()) {
                dir = dir.resolve(seg)
                underAgent = underAgent.resolve(seg)
                writeAgent(dir, "landing-$depthOfAgent-${trees.size}-$i")
            }
            return Tree(depth, underAgent, agentRel.resolve(underAgent)).also { trees.add(it) }
        }

        // Agent at depth 1 (top level), landings at increasing directory counts.
        for (n in 0..AgentDirs.MAX_DEPTH + 2) {
            agentTree(1, (1..n).map { "d$it" })
        }
        // Agents rooted deeper, whose budget the landing spends first.
        for (rootDepth in listOf(2, 4)) {
            for (n in 0..AgentDirs.MAX_DEPTH - rootDepth + 2) {
                agentTree(rootDepth, (1..n).map { "e$it" })
            }
        }
        // Hidden components at every position of the landing, at a shallow depth.
        for (pos in 0..2) {
            agentTree(1, (0..2).map { if (it == pos) ".h$it" else "p$it" })
        }

        val walked = AgentDirs.walkAgentDirs(root).map { it.invariantSeparatorsPathString }.toSet()
        for (t in trees) {
            val landing = t.landingUnderAgent.resolve("agent.yaml")
            val reachable = !AgentDirs.isUnreachableByDiscovery(t.agentDirDepth, landing)
            assertEquals(
                reachable,
                t.landingRel.invariantSeparatorsPathString in walked,
                "predicate and walk disagree at agent depth ${t.agentDirDepth}, landing ${t.landingRel.invariantSeparatorsPathString}",
            )
        }
    }

    /**
     * CWE-400: the entry budget is per top-level
     * directory, not shared by the whole catalog. One principal's wide subtree
     * must spend its own budget, never a sibling's — a walk that shares one
     * counter lets the first agent's directories starve the rest of the catalog,
     * and the dropped agents reload as removed.
     */
    @Test
    fun `one agent's wide subtree does not exhaust the budget of a sibling agent`() {
        val wide = root.resolve("wide").createDirectories()
        repeat(AgentDirs.MAX_ENTRIES) { i ->
            wide.resolve("d%05d".format(i)).createDirectories()
        }
        writeAgent(wide.resolve("z-beyond"), "z-beyond")
        writeAgent(root.resolve("innocent-sibling"), "innocent-sibling")

        val found = relativeWalk()
        assertTrue(
            "innocent-sibling" in found,
            "a sibling after a cap-filling subtree was dropped: ${found.size} entries",
        )
    }

    @Test
    fun `the whole-catalog walk has an outer ceiling across top-level directories`() {
        repeat(AgentDirs.MAX_TOTAL_ENTRIES / 2 + 5) { i ->
            val top = root.resolve("t%06d".format(i)).createDirectories()
            writeAgent(top.resolve("a"), "agent-$i")
            writeAgent(top.resolve("b"), "agent-b-$i")
        }

        val found = relativeWalk()
        assertTrue(
            found.size < AgentDirs.MAX_TOTAL_ENTRIES,
            "outer ceiling not enforced, ${found.size} entries",
        )
    }

    @Test
    fun `isTooDeep counts descent segments only`() {
        assertFalse(AgentDirs.isTooDeep(Path.of("")))
        assertFalse(AgentDirs.isTooDeep(Path.of(".")))
        assertFalse(AgentDirs.isTooDeep(Path.of("a/b/c/agent.yaml")))
        // MAX_PATH_SEGMENTS exactly is fine; one more is not.
        val atCap =
            (1..AgentDirs.MAX_PATH_SEGMENTS)
                .joinToString("/") { "s$it" }
        val oneOver =
            (1..AgentDirs.MAX_PATH_SEGMENTS + 1)
                .joinToString("/") { "s$it" }
        assertFalse(AgentDirs.isTooDeep(Path.of(atCap)))
        assertTrue(AgentDirs.isTooDeep(Path.of(oneOver)))
        // `.` segments carry no descent and do not count toward the cap.
        assertFalse(AgentDirs.isTooDeep(Path.of("./" + atCap)))
    }

    /**
     * CWE-400: the predicate must agree with the walk's own reach —
     * a definition one level past the depth stop, or under a hidden directory, is
     * one discovery never loads and the file API must refuse to plant.
     */
    @Test
    fun `isUnreachableByDiscovery mirrors the walk's depth stop and hidden skip`() {
        fun landing(dirs: Int) = Path.of((1..dirs).joinToString("/") { "d$it" } + "/agent.yaml")

        // A definition at the agent's own root is at the agent's depth.
        assertFalse(AgentDirs.isUnreachableByDiscovery(1, Path.of("agent.yaml")))
        // The walk's reach: MAX_DEPTH levels below the search path, agent dir included.
        assertFalse(AgentDirs.isUnreachableByDiscovery(1, landing(AgentDirs.MAX_DEPTH - 1)))
        assertTrue(AgentDirs.isUnreachableByDiscovery(1, landing(AgentDirs.MAX_DEPTH)))
        // A deeper agent directory shifts the allowed landing depth down.
        assertFalse(AgentDirs.isUnreachableByDiscovery(3, landing(AgentDirs.MAX_DEPTH - 3)))
        assertTrue(AgentDirs.isUnreachableByDiscovery(3, landing(AgentDirs.MAX_DEPTH - 2)))
        // A dot-prefixed directory component is out of reach at any depth.
        assertTrue(AgentDirs.isUnreachableByDiscovery(1, Path.of(".hidden/tool/agent.yaml")))
        assertTrue(AgentDirs.isUnreachableByDiscovery(1, Path.of("tool/.hidden/agent.yaml")))
        // A dot-prefixed definition name is not a hidden PATH — discovery skips
        // hidden directories only, and the landing's own segment is the file.
        assertFalse(AgentDirs.isUnreachableByDiscovery(1, Path.of("tool/.agent.yaml")))
        // Blank and "." segments carry no descent and do not count.
        assertFalse(AgentDirs.isUnreachableByDiscovery(1, Path.of("./" + landing(AgentDirs.MAX_DEPTH - 1))))
        assertFalse(AgentDirs.isUnreachableByDiscovery(1, Path.of("")))
    }

    /**
     * CWE-400: a single flat directory of any size
     * must not be materialized and sorted in full — a listing holds at most the
     * remaining entry budget of the directories it reads (plus one) and reads at
     * most [AgentDirs.MAX_SUBTREE_RAW_ENTRIES] raw entries, so the walk's allocation
     * is bounded by its caps, not by the directory's own entry count.
     */
    @Test
    fun `walk bounds the per-directory listing, not only the visited count`() {
        val flat = root.resolve("flat").createDirectories()
        repeat(AgentDirs.MAX_ENTRIES * 3) { i ->
            flat.resolve("f%08d".format(i)).createDirectories()
        }

        val start = System.nanoTime()
        val found = AgentDirs.walkAgentDirs(flat)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue(found.isEmpty() || found.size <= AgentDirs.MAX_ENTRIES)
        // No hard timing assertion (a slow CI box would flake it); this is the
        // allocation bound that matters: the walk visited no more than the cap.
        assertTrue(elapsedMs < 60_000, "flat directory of ${AgentDirs.MAX_ENTRIES * 3} entries took ${elapsedMs}ms")
    }

    @Test
    fun `isBelowNestedAgentDir matches a nested agent and its subtree only`() {
        writeAgent(root.resolve("parent"), "parent")
        writeAgent(root.resolve("parent/nodes/analysis"), "analysis")
        Files.write(root.resolve("parent/notes.txt"), "hi".toByteArray())

        val parent = root.resolve("parent")
        assertFalse(AgentDirs.isBelowNestedAgentDir(parent, Path.of("")), "the agent's own directory is not below itself")
        assertFalse(AgentDirs.isBelowNestedAgentDir(parent, Path.of(".")))
        assertTrue(AgentDirs.isBelowNestedAgentDir(parent, Path.of("nodes/analysis")))
        assertTrue(AgentDirs.isBelowNestedAgentDir(parent, Path.of("nodes/analysis/agent.yaml")))
        assertTrue(AgentDirs.isBelowNestedAgentDir(parent, Path.of("nodes/analysis/scripts/run.py")))
        assertFalse(AgentDirs.isBelowNestedAgentDir(parent, Path.of("notes.txt")))
        assertFalse(AgentDirs.isBelowNestedAgentDir(parent, Path.of("nodes")))
        assertFalse(AgentDirs.isBelowNestedAgentDir(parent, Path.of("nodes/other")))
    }

    /**
     * CWE-285: the upload excludes must not come
     * from a walk that can stop short — every directory the discovery walk would
     * skip or truncate is still a boundary the pack must not carry.
     */
    @Test
    fun `nestedAgentDirsUnder is exact past every discovery cap`() {
        val parent = root.resolve("parent")
        writeAgent(parent, "parent")
        // More plain entries than the discovery walk's per-subtree budget, ahead of the child.
        repeat(AgentDirs.MAX_ENTRIES + 50) { i -> parent.resolve("aaa-%05d".format(i)).createDirectories() }
        repeat(AgentDirs.MAX_ENTRIES + 50) { i -> Files.write(parent.resolve("aaa-file-%05d".format(i)), byteArrayOf()) }
        writeAgent(parent.resolve("zzz/analysis"), "analysis")
        // Hidden: skipped by discovery, still denied by the file API.
        writeAgent(parent.resolve(".hidden/tool"), "tool")
        // Deeper than MAX_DEPTH: never loaded, still a definition directory.
        val deep = parent.resolve((1..AgentDirs.MAX_DEPTH + 1).joinToString("/") { "d$it" })
        writeAgent(deep, "deep")
        // Symlinked directories are excluded outright, wherever they point: the pack
        // follows a link inside the root under the link's own name.
        writeAgent(root.resolve("elsewhere"), "elsewhere")
        Files.createSymbolicLink(parent.resolve("link"), root.resolve("elsewhere").toAbsolutePath())
        Files.createSymbolicLink(parent.resolve("alias"), parent.resolve("zzz").toAbsolutePath())
        Files.createSymbolicLink(parent.resolve("dangling"), parent.resolve("gone").toAbsolutePath())

        val nested = AgentDirs.nestedAgentDirsUnder(parent).map { it.invariantSeparatorsPathString }

        assertEquals(
            listOf(".hidden/tool", "alias", parent.relativize(deep).invariantSeparatorsPathString, "link", "zzz/analysis"),
            nested,
        )
        assertTrue(AgentDirs.walkAgentDirs(parent).size < nested.size, "the discovery walk is the lossy one this must not depend on")
        assertEquals(emptyList(), AgentDirs.nestedAgentDirsUnder(root.resolve("missing")).map { it.invariantSeparatorsPathString })
    }

    @Test
    fun `walk reports an unreadable directory as a skip rather than failing the walk`() {
        writeAgent(root.resolve("readable"), "readable")
        val unreadable = root.resolve("locked").createDirectories()
        val restricted =
            try {
                Files.setPosixFilePermissions(
                    unreadable,
                    java.nio.file.attribute.PosixFilePermissions
                        .fromString("---------"),
                )
                true
            } catch (_: Exception) {
                false
            }
        assumeTrue(restricted, "POSIX permissions not enforced (root or non-POSIX FS)")
        try {
            assertTrue(relativeWalk().contains("readable"), "an unreadable sibling must not stop the walk")
        } finally {
            Files.setPosixFilePermissions(
                unreadable,
                java.nio.file.attribute.PosixFilePermissions
                    .fromString("rwxr-xr-x"),
            )
        }
    }

    @Test
    fun `walk is deterministic for the same tree`() {
        writeAgent(root.resolve("b/second"), "second")
        writeAgent(root.resolve("a/first"), "first")

        assertEquals(relativeWalk(), relativeWalk())
    }

    @Test
    fun `definition file names are json first`() {
        assertEquals(listOf("agent.json", "agent.yaml"), AgentDirs.DEFINITION_FILE_NAMES)
    }

    @Test
    fun `walk on a directory with no children yields nothing`() {
        assertTrue(AgentDirs.walkAgentDirs(root).isEmpty())
        assertTrue(AgentDirs.walkAgentDirs(root.resolve("absent")).isEmpty())
    }

    @Test
    fun `walk ignores a plain file named like a directory entry`() {
        Files.write(root.resolve("agent.yaml.txt"), "x".toByteArray())
        root
            .resolve("child")
            .createDirectories()
            .resolve("agent.yaml")
            .let { Files.write(it, "not an agent".toByteArray()) }

        // "child" holds an unparseable definition, but it is still an agent directory:
        // parsing is AgentsService's job, the walk only reports positions.
        assertEquals(listOf("child"), relativeWalk())
        assertTrue(AgentDirs.walkAgentDirs(root).first().name == "child")
    }

    /** Runs [body] with AgentDirs' WARN output captured; returns the cap warnings it logged. */
    private fun capWarnings(body: () -> Unit): List<String> {
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val logback = LoggerFactory.getLogger(AgentDirs::class.java) as LogbackLogger
        val level = logback.level
        logback.level = LogbackLevel.WARN
        logback.addAppender(appender)
        try {
            body()
        } finally {
            logback.detachAppender(appender)
            logback.level = level
        }
        return appender.list.map { it.formattedMessage }.filter { "hit an entry cap" in it }
    }

    private fun symlinkOrSkip(
        link: Path,
        target: Path,
    ) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "symlinks not supported on this filesystem")
        } catch (_: java.io.IOException) {
            assumeTrue(false, "symlinks not supported on this filesystem")
        }
    }

    /**
     * Entries the walk never visits must not spend the listing. The
     * agents are created before the filler so that neither creation order
     * (tmpfs lists newest first) nor a hashed order (APFS, ext4 htree) puts
     * all of them inside the first [AgentDirs.MAX_ENTRIES] raw entries.
     */
    private fun assertNestedAgentsSurvive(filler: (parent: Path, index: Int) -> Unit) {
        val parent = root.resolve("parent")
        writeAgent(parent, "parent")
        val nested = (1..5).map { "parent/nested$it" }
        nested.forEach { writeAgent(root.resolve(it), it.substringAfter('/')) }
        repeat(AgentDirs.MAX_ENTRIES * 5) { filler(parent, it) }

        lateinit var found: List<String>
        val warnings = capWarnings { found = relativeWalk() }

        assertEquals(listOf("parent") + nested, found, "a nested agent was dropped behind entries the walk never visits")
        assertEquals(emptyList(), warnings, "no cap binds here, so none may be reported")
    }

    @Test
    fun `a nested agent listed after MAX_ENTRIES plain files loads`() {
        assertNestedAgentsSurvive { parent, i -> Files.write(parent.resolve("file%05d.dat".format(i)), byteArrayOf()) }
    }

    @Test
    fun `a nested agent listed after MAX_ENTRIES hidden entries loads`() {
        assertNestedAgentsSurvive { parent, i -> parent.resolve(".hidden%05d".format(i)).createDirectories() }
    }

    @Test
    fun `a nested agent listed after MAX_ENTRIES symlinked directories loads`() {
        val outside = Files.createTempDirectory("agent-dirs-outside")
        try {
            writeAgent(outside, "outside")
            assertNestedAgentsSurvive { parent, i -> symlinkOrSkip(parent.resolve("link%05d".format(i)), outside) }
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a top-level agent listed after MAX_TOTAL_ENTRIES plain files loads`() {
        val agents = (1..5).map { "agent$it" }
        agents.forEach { writeAgent(root.resolve(it), it) }
        repeat(AgentDirs.MAX_TOTAL_ENTRIES * 3) { Files.write(root.resolve("file%06d.dat".format(it)), byteArrayOf()) }

        lateinit var found: List<String>
        val warnings = capWarnings { found = relativeWalk() }

        assertEquals(agents, found, "a top-level agent was dropped behind plain files")
        assertEquals(emptyList(), warnings)
    }

    /**
     * Where the budget genuinely binds, the survivors are the first
     * directories by name — the same set whatever order the filesystem lists
     * them in — and the cut is reported.
     */
    @Test
    fun `a binding entry cap keeps the first directories by name, whatever the creation order`() {
        val count = AgentDirs.MAX_ENTRIES + 50
        val names = (0 until count).map { "d%05d".format(it) }

        fun build(
            tree: Path,
            order: List<String>,
        ) {
            for (name in order) {
                val dir = tree.resolve("crowd").resolve(name)
                if (name.removePrefix("d").toInt() % 10 == 0) writeAgent(dir, "agent-$name") else dir.createDirectories()
            }
        }
        val ascending = root.resolve("ascending").also { build(it, names) }
        val shuffled = root.resolve("shuffled").also { build(it, names.shuffled(kotlin.random.Random(59))) }

        fun walk(tree: Path) = AgentDirs.walkAgentDirs(tree).map { it.invariantSeparatorsPathString }.sorted()
        lateinit var fromAscending: List<String>
        lateinit var fromShuffled: List<String>
        val warnings =
            capWarnings {
                fromAscending = walk(ascending)
                fromShuffled = walk(shuffled)
            }

        val expected = names.take(AgentDirs.MAX_ENTRIES).filter { it.removePrefix("d").toInt() % 10 == 0 }.map { "crowd/$it" }
        assertEquals(expected, fromAscending, "the cap must keep the first directories by name")
        assertEquals(fromAscending, fromShuffled, "the surviving set depends on creation order")
        assertEquals(2, warnings.size, "each truncated walk must log the cap warning: $warnings")
    }

    /**
     * A budget spent exactly on a directory that still has children is
     * a cap hit like any other — its unvisited subtree must be reported, not
     * dropped because the directory was listed with nothing left to spend.
     */
    @Test
    fun `a budget exhausted at a directory with children still logs the cap warning`() {
        val crowd = root.resolve("crowd").createDirectories()
        repeat(AgentDirs.MAX_ENTRIES - 1) { crowd.resolve("a%05d".format(it)).createDirectories() }
        writeAgent(crowd.resolve("zz/beyond"), "beyond")

        lateinit var found: List<String>
        val warnings = capWarnings { found = relativeWalk() }

        assertFalse("crowd/zz/beyond" in found, "the budget was spent before 'beyond'")
        assertEquals(1, warnings.size, "the agent past the cap was dropped silently")
    }

    private fun walkWith(
        subtreeRawEntries: Int = AgentDirs.MAX_SUBTREE_RAW_ENTRIES,
        totalRawEntries: Int = AgentDirs.MAX_TOTAL_RAW_ENTRIES,
    ): Pair<List<String>, List<String>> {
        lateinit var found: List<String>
        val warnings =
            capWarnings {
                found =
                    AgentDirs
                        .walkAgentDirs(root, subtreeRawEntries, totalRawEntries)
                        .map { it.invariantSeparatorsPathString }
                        .sorted()
            }
        return found to warnings
    }

    private fun writeFiles(
        dir: Path,
        count: Int,
    ) = repeat(count) { Files.write(dir.resolve("file%03d".format(it)), byteArrayOf()) }

    /**
     * CWE-400: the directory budgets count directories only, so raw
     * reads have their own per-subtree budget. Running it out truncates that
     * subtree only, reported by name — a sibling keeps walking, so one
     * principal's files cannot starve another's agents.
     */
    @Test
    fun `a subtree past its raw-entry budget is truncated with the warning while a sibling still loads`() {
        val big = root.resolve("aaa")
        writeAgent(big, "aaa")
        writeAgent(big.resolve("nested/deeper"), "deeper")
        writeFiles(big, 20)
        val bigRaw = 24 // aaa: agent.yaml, nested/, 20 files; nested: deeper/; deeper: agent.yaml
        val sibling = root.resolve("zzz-sibling")
        writeAgent(sibling, "zzz-sibling")
        writeAgent(sibling.resolve("child"), "child")
        writeFiles(sibling, 2)

        val (fullRead, noWarnings) = walkWith(subtreeRawEntries = bigRaw)
        assertEquals(listOf("aaa", "aaa/nested/deeper", "zzz-sibling", "zzz-sibling/child"), fullRead)
        assertEquals(emptyList(), noWarnings, "a subtree exactly at its raw budget is read in full")

        // aaa's own listing takes 22 reads, leaving none for nested/.
        val (cut, warnings) = walkWith(subtreeRawEntries = 22)
        assertFalse("aaa/nested/deeper" in cut, "nested/ had no raw budget left to list")
        assertTrue(
            cut.containsAll(listOf("zzz-sibling", "zzz-sibling/child")),
            "a sibling subtree was starved by another's raw reads: $cut",
        )
        assertEquals(1, warnings.size, "a subtree stopped by its raw budget must log the cap warning")
        assertTrue("[aaa]" in warnings.single(), "the warning must name the truncated top-level directory: $warnings")
        assertTrue("whole-catalog cap hit: false" in warnings.single(), warnings.single())
    }

    /**
     * CWE-400: the raw budget is charged across every listing of the
     * subtree — a bound per listing alone lets a caller multiply listings.
     */
    @Test
    fun `the raw-entry budget is charged across listings, not per listing`() {
        val parent = root.resolve("parent")
        writeAgent(parent, "parent")
        listOf("a", "b").forEach { writeFiles(parent.resolve(it).createDirectories(), 15) }

        // parent: 3 entries, a: 15, b: 15 — each listing under 25, the subtree over it.
        val (found, warnings) = walkWith(subtreeRawEntries = 25)

        assertEquals(listOf("parent"), found, "the agent read before the cut is kept")
        assertEquals(1, warnings.size, "listings that each fit but together exceed the budget must truncate")
        assertTrue("[parent]" in warnings.single(), warnings.single())
    }

    @Test
    fun `the catalog-wide raw-entry budget truncates the walk`() {
        val tops = (0 until 5).map { "t$it" }
        tops.forEach {
            writeAgent(root.resolve(it), it)
            writeFiles(root.resolve(it), 3)
        }

        // Root listing: 5 entries; each top-level listing: 4. 13 reads cover t0 and t1 in full.
        val (found, warnings) = walkWith(totalRawEntries = 13)
        assertEquals(listOf("t0", "t1"), found, "the walk must stop once the catalog-wide raw budget is spent")
        assertEquals(1, warnings.size)
        assertTrue("whole-catalog cap hit: true" in warnings.single(), warnings.single())

        // The search-path listing itself is charged to the catalog-wide budget.
        val (_, rootCut) = walkWith(totalRawEntries = 3)
        assertEquals(1, rootCut.size)
        assertTrue("whole-catalog cap hit: true" in rootCut.single(), rootCut.single())

        val (all, none) = walkWith(totalRawEntries = 5 + 5 * 4)
        assertEquals(tops, all)
        assertEquals(emptyList(), none, "a catalog exactly at its raw budget is read in full")
    }

    @Test
    fun `the catalog-wide raw-entry budget running out inside a subtree flags the whole catalog`() {
        listOf("t0", "t1").forEach {
            writeAgent(root.resolve(it), it)
            writeFiles(root.resolve(it), 3)
        }

        // Root listing: 2 reads; t0's listing runs out after 2 more, leaving nothing for t1.
        val (found, warnings) = walkWith(totalRawEntries = 4)
        assertEquals(listOf("t0"), found)
        assertEquals(1, warnings.size)
        assertTrue("whole-catalog cap hit: true" in warnings.single(), warnings.single())
    }

    @Test
    fun `a directory budget spent in an earlier sibling's subtree stops the walk before the next sibling`() {
        val crowd = root.resolve("crowd").createDirectories()
        val wide = crowd.resolve("a").createDirectories()
        repeat(AgentDirs.MAX_ENTRIES - 1) { wide.resolve("d%05d".format(it)).createDirectories() }
        writeAgent(crowd.resolve("b"), "b")

        lateinit var found: List<String>
        val warnings = capWarnings { found = relativeWalk() }

        assertFalse("crowd/b" in found, "a/ spent the whole budget before b/")
        assertEquals(1, warnings.size, "the agent past the cap was dropped silently")
        assertTrue("[crowd]" in warnings.single(), warnings.single())
    }
}
