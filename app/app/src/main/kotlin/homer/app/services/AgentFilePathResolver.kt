package homer.app.services

import homer.app.infra.AgentDirs
import homer.app.infra.PathSafety
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name

/**
 * Path containment for [AgentFilesService]: resolves a caller-supplied path against
 * an agent's directory and refuses `..`, absolute paths, symlink escapes, a nested
 * agent's subtree (ADR-0047, ADR-0068) and landings discovery would never reach.
 * Every file-API read, write and delete resolves through [resolveSafe].
 */
internal class AgentFilePathResolver(
    private val agentsService: AgentsService,
) {
    fun resolveSafe(
        agentName: String,
        relPath: String,
    ): ResolveResult {
        if (relPath.isBlank()) return ResolveError(AgentFilesService.FileOpResult.BadRequest("Empty path"))
        if (relPath.startsWith("/")) return ResolveError(AgentFilesService.FileOpResult.BadRequest("Path must be relative"))
        val dir =
            agentsService.getAgentDir(agentName)
                ?: return ResolveError(AgentFilesService.FileOpResult.NotFound("Agent '$agentName' not found"))
        if (!dir.exists()) return ResolveError(AgentFilesService.FileOpResult.NotFound("Agent directory missing"))

        val candidate = dir.resolve(relPath).normalize()
        val resolved =
            try {
                PathSafety.assertContained(dir, candidate)
            } catch (_: SecurityException) {
                return ResolveError(
                    AgentFilesService.FileOpResult.BadRequest("Path escapes the agent directory: $relPath"),
                )
            }
        return gateContained(relPath, dir, candidate, resolved)
    }

    /** The nested-agent, landing, depth and discovery gates [resolveSafe] runs on a contained [resolved] path. */
    private fun gateContained(
        relPath: String,
        dir: Path,
        candidate: Path,
        resolved: Path,
    ): ResolveResult {
        // ADR-0047 boundary: an agent nested inside this one is a different agent
        // with its own grant, so containment in the parent's directory is not
        // authorization. Nothing at or inside a nested agent's subtree is reachable
        // through the parent — not its `agent.yaml` (which isAgentDef deliberately
        // keeps matching at the root only) and not any other file of its definition.
        // Denied as not-found, identical to a path that does not exist, so the
        // parent's caller learns nothing about the child (§12 rule 3).
        //
        // CWE-59: the gate runs on the resolved path
        // (`assertContained` returns the realpath of an existing candidate), not on
        // the lexical candidate — a parent-relative symlink like
        // `notes.txt -> nodes/analysis/agent.yaml` lexically stats as `notes.txt`,
        // so a lexical gate misses it and hands the child's file to
        // read/write/delete. Relative to the agent dir's OWN real path: the agent
        // directory itself may legitimately be a symlink, so `resolved` lives under
        // the real root, not the lexical one. A path that does not exist resolves
        // lexically and is gated as the candidate. A realpath that still escapes
        // the real root after containment is impossible (assertContained rejects
        // it first); nothing else falls through.
        val realDir = dir.toRealPath()
        val gateRel = if (resolved.startsWith(realDir)) realDir.relativize(resolved) else dir.relativize(candidate)
        if (AgentDirs.isBelowNestedAgentDir(dir, gateRel)) {
            return ResolveError(AgentFilesService.FileOpResult.NotFound("File not found: $relPath"))
        }
        // CWE-59: a WRITE to a path that does not exist
        // yet follows any dangling symlink at the candidate — including one in an
        // intermediate component (`link/new.txt` is not itself a link) — and
        // `assertContained` only saw the link's lexical name, so a link could steer
        // the write outside the agent dir or into a nested agent's subtree with no
        // grant on the child. Gate where the write would land — the candidate with
        // its link chain followed — as both a containment check (the counterpart
        // the read path gets from assertContained's realpath) and the nested-agent
        // boundary, relative to the real root. A live (resolving) link is already
        // covered above: `resolved` is its realpath there.
        val landingAbsolute = PathSafety.resolveDanglingLinkTarget(candidate)
        if (!landingAbsolute.startsWith(realDir)) {
            return ResolveError(AgentFilesService.FileOpResult.NotFound("File not found: $relPath"))
        }
        val landing = realDir.relativize(landingAbsolute)
        if (AgentDirs.isBelowNestedAgentDir(realDir, landing)) {
            return ResolveError(AgentFilesService.FileOpResult.NotFound("File not found: $relPath"))
        }
        return landingShapeError(relPath, realDir, landing) ?: ResolveOk(resolved, realDir, landingAbsolute)
    }

    /** The depth and discovery-reach gates on a request's [landing] below [realDir]; null when it passes both. */
    private fun landingShapeError(
        relPath: String,
        realDir: Path,
        landing: Path,
    ): ResolveError? {
        // A deep path is the walk's entry cap pointed at the disk instead: the
        // directories a write's createDirectories builds are real, so one PUT with
        // enough segments could make every later discovery walk pay for the chain
        // — and the entry budget it drains is other agents' availability (§12
        // rule 4). Bound the caller-supplied depth before any disk touch.
        // CWE-400; a read is bounded by the same check for
        // free, since it resolves through here too.
        if (AgentDirs.isTooDeep(landing)) {
            return ResolveError(
                AgentFilesService.FileOpResult.BadRequest("Path is too deep (more than ${AgentDirs.MAX_PATH_SEGMENTS} segments): $relPath"),
            )
        }
        // A definition landing discovery never reaches (deeper than MAX_DEPTH below
        // the search path, or under a hidden directory) would leave a subtree this
        // API can only answer 404 for. Refused before any disk touch, like the
        // segment cap above. See ADR-0068.
        if (isDefFileName(landing.invariantSeparatorsPathString) &&
            AgentDirs.isUnreachableByDiscovery(agentRootDepth(realDir), landing)
        ) {
            return ResolveError(
                AgentFilesService.FileOpResult.BadRequest(
                    "Definition path discovery would never load (deeper than ${AgentDirs.MAX_DEPTH} or hidden): $relPath",
                ),
            )
        }
        return null
    }

    /**
     * How many levels [dir] sits below the agents search path — the depth
     * discovery starts counting from. [dir] is the resolved (real) agent
     * directory, the one root the rest of [resolveSafe] keys on, so the search
     * path is resolved to its real path too (the config value may be relative or
     * reach the directory through a symlink); a dir outside it is impossible for
     * a loaded agent and fails loudly rather than passing a made-up depth into
     * the reach check.
     */
    private fun agentRootDepth(dir: Path): Int {
        val root = agentsService.rootPath.toRealPath()
        require(dir.startsWith(root)) { "Agent directory '$dir' is not below the agents search path '$root'" }
        return root.relativize(dir).nameCount
    }
}

/** What [AgentFilePathResolver.resolveSafe] made of a request's path. */
internal sealed class ResolveResult

internal data class ResolveOk(
    val path: Path,
    /** The agent directory's real path — the containment root for a write through [path]. */
    val agentDir: Path,
    /** Where a write through [path] lands — the request's symlink chain followed, inside [agentDir]. */
    val landing: Path,
) : ResolveResult() {
    /** The path a request lands on, relative to the agent directory's real path. */
    val landingRel: String get() = agentDir.relativize(landing).invariantSeparatorsPathString
}

internal data class ResolveError(
    val result: AgentFilesService.FileOpResult<Nothing>,
) : ResolveResult()

// Normalize before matching: the raw string is checked before resolveSafe
// normalizes it, so "./agent.yaml" or "x/../agent.yaml" would otherwise slip
// past the delete/validate guards yet still resolve to the real definition.

/** True when [relPath] is the agent's own root definition file. */
internal fun isAgentDef(relPath: String): Boolean {
    val normalized = runCatching { Path.of(relPath).normalize().toString() }.getOrNull() ?: return false
    return normalized == "agent.yaml" || normalized == "agent.json"
}

// Any definition file at any depth: whatever its path, reload() loads it as an
// agent the moment its directory holds it (ADR-0068), so a write here creates
// an agent even when the file is not this agent's own root definition.
internal fun isDefFileName(relPath: String): Boolean =
    runCatching { Path.of(relPath).normalize().name }.getOrNull() in AgentDirs.DEFINITION_FILE_NAMES
