package homer.app.infra

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.PriorityQueue
import kotlin.io.path.name

/**
 * The one implementation of the "what is an agent directory, and where are they"
 * rule (ADR-0068). Discovery is recursive: any directory under
 * `agentsSearchPath` holding an `agent.json`/`agent.yaml` is an agent,
 * **including a directory nested inside another agent's directory** — a
 * multi-agent pipeline can live in one tree, and its members' names still share
 * one flat global namespace.
 *
 * Directory *identity* is the path relative to the search-path root, never the
 * leaf name: two nested `analysis/` directories would otherwise collide, and a
 * moved agent would read as removed + added instead of updated.
 *
 * Bounds are local and explicit (§12 rule 4): a depth cap, an entry cap per
 * top-level directory with a whole-walk outer ceiling, the same two-level
 * budget on raw entries read, hidden directories skipped, and symlinked
 * directories never followed. An agent directory can hold a whole code tree,
 * and every reload walks it — the per-subtree budgets are what keep one
 * principal's subtree from spending another's (CWE-400).
 */
internal object AgentDirs {
    /** How many levels below the search-path root are walked. A definition deeper than this is not an agent. */
    const val MAX_DEPTH: Int = 8

    /**
     * Ceiling on directories visited within ONE top-level agent directory's
     * subtree; past it that subtree's walk stops. The budget is fresh for every
     * top-level directory — see [walkAgentDirs].
     */
    const val MAX_ENTRIES: Int = 1000

    /** Outer ceiling on directories visited in one whole-catalog walk, across every top-level directory. */
    const val MAX_TOTAL_ENTRIES: Int = 10_000

    /**
     * Ceiling on raw entries — files, hidden and symlinked entries included —
     * read from all listings within ONE top-level directory's subtree. The
     * budgets above count directories only; without this, a principal could
     * multiply listings full of files and make every reload read all of them
     * (CWE-400). Fresh per top-level directory, like [MAX_ENTRIES]. See ADR-0068.
     */
    const val MAX_SUBTREE_RAW_ENTRIES: Int = 200_000

    /** Outer ceiling on raw entries read in one whole-catalog walk, the search-path listing included. */
    const val MAX_TOTAL_RAW_ENTRIES: Int = 2_000_000

    /**
     * Ceiling on the number of path segments ANY caller-supplied relative path may
     * carry into a directory-creating operation. The walk bounds what discovery
     * visits; this bounds what a single write can plant — a deep chain is the
     * same CWE-400 vector pointed at the disk instead of the walk. A *definition*
     * landing is bounded further, to discovery's own reach, by
     * [isUnreachableByDiscovery].
     */
    const val MAX_PATH_SEGMENTS: Int = MAX_DEPTH + 2

    private val logger = LoggerFactory.getLogger(AgentDirs::class.java)

    /** The agent-name charset (AGENT.md § Agent Fields, `name` row): a directory or `agent.name` outside it is rejected. */
    val AGENT_NAME_PATTERN: Regex = Regex("^[a-zA-Z0-9_-]+$")

    /** The recognized definition-file names, `agent.json` first so it wins. */
    val DEFINITION_FILE_NAMES: List<String> = listOf("agent.json", "agent.yaml")

    /**
     * True when [dir] is itself an agent directory. The definition must be a real
     * file inside the walked directory (`LinkOption.NOFOLLOW_LINKS`): a symlinked
     * `agent.yaml` would otherwise be read through to its target — one outside the
     * search path, or one shared by two directories, failing every claimant — and
     * no file-API request is involved, so [resolveSafe]'s deny cannot fire.
     * CWE-59. The walk refuses a symlinked *directory*
     * before this runs; this closes the definition-file case.
     */
    fun isAgentDir(dir: Path): Boolean = definitionFile(dir) != null

    /** The definition file discovery reads for [dir], under the same no-symlink rule as [isAgentDir]; null when there is none. */
    fun definitionFile(dir: Path): Path? =
        DEFINITION_FILE_NAMES
            .map { dir.resolve(it) }
            .firstOrNull { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }

    /**
     * Walks [root] and yields every agent directory found, as paths relative to
     * [root], depth-first and in sorted listing order. [root] itself is never
     * yielded: a definition directly in the search path is not an agent
     * (agents are the subdirectories of it).
     *
     * The walk descends *through* an agent directory — an agent nested inside
     * another agent is found at its own position, not as the parent's content —
     * except where a bound, a hidden name or a symlink stops it.
     */
    fun walkAgentDirs(
        root: Path,
        subtreeRawEntries: Int = MAX_SUBTREE_RAW_ENTRIES,
        totalRawEntries: Int = MAX_TOTAL_RAW_ENTRIES,
    ): List<Path> {
        val walk = CatalogWalk(subtreeRawEntries, totalRawEntries)
        walk.walkCatalog(root)
        warnIfTruncated(root, subtreeRawEntries, totalRawEntries, walk.truncatedTops, walk.catalogTruncated)
        return walk.found
    }

    /**
     * One whole-catalog walk of [walkAgentDirs]: the agent directories it found and
     * the budgets it hit. [subtreeRawEntries] is the fresh raw-entry budget each
     * top-level directory gets; [totalRawEntries] the whole walk's.
     */
    private class CatalogWalk(
        private val subtreeRawEntries: Int,
        totalRawEntries: Int,
    ) {
        val found = mutableListOf<Path>()
        val truncatedTops = mutableListOf<String>()
        var catalogTruncated = false
            private set
        private var totalVisited = 0

        // The search-path listing belongs to no subtree: only the total budget charges it.
        private val raw = RawBudget(subtree = Int.MAX_VALUE, total = totalRawEntries)
        private var subtreeTruncated = false

        fun walkCatalog(root: Path) {
            // Each top-level directory walks its own subtree with a fresh entry budget;
            // the whole-catalog cap stays an outer ceiling. CWE-400:
            // one shared budget let a single principal's wide or
            // deep subtree exhaust the walk and drop the catalog's remaining agents
            // — other principals' agents vanished (removedAgentNames) because one
            // caller could fill its own agent dir with directories. The per-subtree
            // budget isolates that blast radius; the outer cap bounds the whole walk.
            // Every top-level directory costs one unit of the total budget, so
            // more than MAX_TOTAL_ENTRIES of them can contribute nothing the walk would keep.
            val topLevel = readableChildren(root, MAX_TOTAL_ENTRIES, raw)
            if (topLevel.truncated) catalogTruncated = true
            for (top in topLevel.dirs) {
                if (totalVisited >= MAX_TOTAL_ENTRIES || raw.total <= 0) {
                    catalogTruncated = true
                    break
                }
                totalVisited++
                val topRelative = Path.of(top.name)
                if (isAgentDir(top)) found.add(topRelative)
                // `top` sits at depth 1 below the root; the inner walk receives the
                // depth of the directory it lists, +1 — the same convention that makes
                // the old one-level walk stop at MAX_DEPTH levels below the root.
                raw.subtree = subtreeRawEntries
                subtreeTruncated = false
                walk(top, topRelative, 2, MAX_ENTRIES)
                if (subtreeTruncated) truncatedTops.add(top.name)
            }
        }

        private fun walk(
            dir: Path,
            relative: Path,
            depth: Int,
            budgetIn: Int,
        ): Int {
            var budget = budgetIn
            if (depth > MAX_DEPTH) return budget
            val listing = readableChildren(dir, budget, raw)
            if (listing.truncated) {
                subtreeTruncated = true
                if (raw.total <= 0) catalogTruncated = true
            }
            for (child in listing.dirs) {
                if (budget <= 0) {
                    subtreeTruncated = true
                    return budget
                }
                budget--
                totalVisited++
                val childRelative = relative.resolve(child.name)
                if (isAgentDir(child)) found.add(childRelative)
                budget = walk(child, childRelative, depth + 1, budget)
            }
            return budget
        }
    }

    private fun warnIfTruncated(
        root: Path,
        subtreeRawEntries: Int,
        totalRawEntries: Int,
        truncatedTops: List<String>,
        catalogTruncated: Boolean,
    ) {
        if (catalogTruncated || truncatedTops.isNotEmpty()) {
            logger.warn(
                "Agent discovery under '{}' hit an entry cap ({} directories and {} raw entries per top-level directory, " +
                    "{} directories and {} raw entries total); some agents were not loaded. " +
                    "Truncated top-level directories: {}{}; whole-catalog cap hit: {}",
                root,
                MAX_ENTRIES,
                subtreeRawEntries,
                MAX_TOTAL_ENTRIES,
                totalRawEntries,
                truncatedTops.take(MAX_NAMED_TRUNCATIONS),
                if (truncatedTops.size > MAX_NAMED_TRUNCATIONS) " and ${truncatedTops.size - MAX_NAMED_TRUNCATIONS} more" else "",
                catalogTruncated,
            )
        }
    }

    /** How many truncated top-level directories the cap warning names; the rest are counted. */
    private const val MAX_NAMED_TRUNCATIONS = 10

    /** Raw entries the walk may still read: [subtree] for the current top-level directory, [total] for the catalog. */
    private class RawBudget(
        var subtree: Int,
        var total: Int,
    )

    private class Listing(
        val dirs: List<Path>,
        val truncated: Boolean,
    )

    /** [walkableChildren], or none (logged as a skip) when [dir] cannot be listed. */
    private fun readableChildren(
        dir: Path,
        keep: Int,
        raw: RawBudget,
    ): Listing =
        try {
            walkableChildren(dir, keep, raw)
        } catch (e: Exception) {
            logger.warn("Skipping unreadable directory '{}': {}", dir, e.message)
            Listing(emptyList(), truncated = false)
        }

    /**
     * The children of [dir] the walk visits, the first [keep] by name, sorted.
     * Filtered before the budget applies, so entries the walk skips never spend
     * it, and ordered by name before the cut, so the kept set does not depend on
     * the filesystem's listing order. Every raw entry read, before
     * filtering, is charged to [raw], and at most [keep] + 1 are held (CWE-400).
     * `truncated` when a walkable entry was cut or [raw] ran out mid-listing.
     */
    private fun walkableChildren(
        dir: Path,
        keep: Int,
        raw: RawBudget,
    ): Listing {
        // Largest name on top, so the one evicted is the one past the cut.
        val kept = PriorityQueue<Path>(Comparator.reverseOrder())
        var truncated = false
        Files.newDirectoryStream(dir).use { stream ->
            for (entry in stream) {
                if (raw.subtree <= 0 || raw.total <= 0) {
                    truncated = true
                    break
                }
                raw.subtree--
                raw.total--
                // Hidden directories hold tooling (.git, .venv), not definitions, and
                // descending into them would cost the walk on every reload. A symlinked
                // directory is not a directory under NOFOLLOW_LINKS, so it is never
                // followed: a link can leave the search path, and its target would be
                // walked under a name that does not describe it (§12 rule 2, CWE-59).
                if (entry.name.startsWith(".")) continue
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue
                if (keep <= 0) {
                    truncated = true
                    break
                }
                kept.add(entry)
                if (kept.size > keep) {
                    kept.poll()
                    truncated = true
                }
            }
        }
        return Listing(kept.sorted(), truncated)
    }

    /**
     * True when [relPath]'s segment count (its depth, in path terms) exceeds
     * [MAX_PATH_SEGMENTS]. `.`/blank segments carry no descent and do not count;
     * the lexical check runs before any disk touch, so an over-deep `PUT` is
     * refused before `createDirectories` can build the chain it names.
     */
    fun isTooDeep(relPath: Path): Boolean =
        (0 until relPath.nameCount)
            .map { relPath.getName(it).toString() }
            .filter { it.isNotEmpty() && it != "." }
            .size > MAX_PATH_SEGMENTS

    /**
     * True when [relPath] — relative to [agentDir] — is at or below a *nested*
     * agent directory, i.e. some ancestor component of it (itself included) holds a
     * definition. This is the ADR-0047 boundary check: a parent's file API must not
     * reach a nested agent's subtree, because the parent's grant is not the child's.
     *
     * O(path depth) `stat` calls rather than a subtree walk, and it needs no
     * directory listing — so it is safe to run on the read/write/delete path.
     * [relPath] must already be contained in [agentDir] (the caller asserts that).
     */
    fun isBelowNestedAgentDir(
        agentDir: Path,
        relPath: Path,
    ): Boolean {
        // By index, not by iterator: an empty path iterates as one empty name (and
        // `resolve("")` is the agent's own directory, which would match every call).
        // Blank and "." segments carry no descent and are skipped.
        val segments =
            (0 until relPath.nameCount)
                .map { relPath.getName(it).toString() }
                .filter { it.isNotEmpty() && it != "." }
        var prefix: Path? = null
        for (segment in segments) {
            prefix = if (prefix == null) Path.of(segment) else prefix.resolve(segment)
            if (isAgentDir(agentDir.resolve(prefix))) return true
        }
        return false
    }

    /**
     * True when a definition landing at [landingRel] — relative to an agent
     * directory sitting [agentDirDepth] levels below the search-path root — is a
     * directory [walkAgentDirs] never reaches: deeper than [MAX_DEPTH], or under
     * a dot-prefixed directory component. Such a directory still counts as a
     * nested-agent boundary to [isAgentDir]/[isBelowNestedAgentDir], so leaving
     * one behind makes the whole subtree unreadable — a definition file this API
     * could write but discovery would never load is a self-inflicted dead
     * subtree (CWE-400).
     *
     * Mirrors the walk's depth stop and hidden-name skip exactly; the landing's
     * own last segment is the definition file, never a directory, and does not
     * count as descent. A definition directly in the agent's own directory is at
     * this agent's depth, so its directory count is `agentDirDepth`.
     */
    fun isUnreachableByDiscovery(
        agentDirDepth: Int,
        landingRel: Path,
    ): Boolean {
        val directories =
            (0 until landingRel.nameCount)
                .map { landingRel.getName(it).toString() }
                .filter { it.isNotEmpty() && it != "." }
                .dropLast(1)
        if (directories.any { it.startsWith(".") }) return true
        return agentDirDepth + directories.size > MAX_DEPTH
    }

    /**
     * The nested agent directories under [agentDir], relative to it — what a
     * parent's `HMR_AGENT_DIR` upload must exclude (ADR-0068). Exact, not the
     * discovery walk: no depth or entry cap and hidden directories included, so it
     * agrees with [isBelowNestedAgentDir] on every directory the pack would
     * otherwise carry — a walk that can stop short ships a child's subtree under
     * the parent's identity (CWE-285). A symlinked
     * directory is excluded outright rather than followed: both upload paths
     * follow a link that resolves inside the root under the link's own name,
     * where no real-path exclude would match — so the agent-dir upload never
     * carries one, the rule discovery already applies (CWE-59). The pack that
     * follows walks the same tree, so this adds no cost class of its own.
     */
    fun nestedAgentDirsUnder(agentDir: Path): List<Path> {
        // A missing source is the upload's own failure to report, not this walk's.
        if (!Files.isDirectory(agentDir)) return emptyList()
        val found = mutableListOf<Path>()
        Files.walkFileTree(
            agentDir,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (dir != agentDir && isAgentDir(dir)) {
                        found.add(agentDir.relativize(dir))
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    // Without FOLLOW_LINKS a symlinked directory arrives here; a dangling one is not a directory.
                    if (attrs.isSymbolicLink && Files.isDirectory(file)) found.add(agentDir.relativize(file))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    logger.warn("Skipping unreadable entry '{}' while listing nested agents: {}", file, exc.message)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return found.sorted()
    }
}
