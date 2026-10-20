package homer.app.infra

import com.charleskorn.kaml.Yaml
import homer.app.EnvConstants
import homer.app.models.Agent
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText

/**
 * One pass over `agentsSearchPath` for `AgentsService`: walks it with [AgentDirs]
 * (ADR-0068), reads each definition under the size cap, validates it with
 * [AgentDefinitionValidator] and claims its name. Every directory isolates its own
 * failure, so a broken agent never stops the rest of the catalog from loading.
 */
internal class AgentScanner(
    private val agentsSearchPath: String,
    private val logger: Logger,
    private val sysEnvResolver: SysEnvResolver,
    private val maxConfigFileBytes: Long,
    private val validator: AgentDefinitionValidator,
    private val inputChecks: AgentInputChecks,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun scan(): AgentScan {
        val basePath = Path.of(agentsSearchPath)
        if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
            logger.warn("Agents search path does not exist or is not a directory: {}", agentsSearchPath)
            return AgentScan(emptyMap(), emptyMap(), emptySet(), emptyList())
        }

        val parsed = mutableMapOf<String, Agent>()
        val sourceDirs = mutableMapOf<String, String>()
        val presentDirNames = mutableSetOf<String>()
        val errors = mutableListOf<String>()
        // Names claimed by more than one directory. Directory iteration order is
        // unspecified, so last-writer-wins would be nondeterministic and let a
        // writable dir shadow a trusted agent — a collision fails ALL claimants.
        val collidedNames = mutableSetOf<String>()

        try {
            // Recursive, bounded, symlink-free walk: a multi-agent pipeline lives in
            // one tree, so a directory holding a definition at any depth is an agent —
            // including one nested inside another agent's directory. Every hit goes
            // through the same parseAgentDir, which isolates its own failures, so a
            // broken parent never stops its children from loading. See ADR-0068.
            AgentDirs.walkAgentDirs(basePath).forEach { rel ->
                val relName = rel.invariantSeparatorsPathString
                parseAgentDir(basePath.resolve(rel), relName, parsed, sourceDirs, presentDirNames, errors, collidedNames)
            }
        } catch (e: Exception) {
            logger.error("Error scanning agents directory: {}", e.message, e)
            errors.add("Directory scan failed: ${e.message}")
        }

        return AgentScan(parsed, sourceDirs, presentDirNames, errors)
    }

    /**
     * Parse one agent directory. [rel] is the directory's path relative to
     * `agentsSearchPath` and is its identity everywhere a name is not enough
     * (the loaded-map key, the collision report, the messages below): the leaf
     * name stops being unique once directories nest (ADR-0068). [dir] stays the
     * filesystem path.
     */
    private fun parseAgentDir(
        dir: Path,
        rel: String,
        parsed: MutableMap<String, Agent>,
        sourceDirs: MutableMap<String, String>,
        presentDirNames: MutableSet<String>,
        errors: MutableList<String>,
        collidedNames: MutableSet<String>,
    ) {
        val agentPath = AgentDirs.definitionFile(dir) ?: return

        presentDirNames.add(rel)

        try {
            val agent = readAgent(agentPath, rel, errors) ?: return
            val validatedName = validator.checkAgentName(agent, rel, errors) ?: return
            val cronDisabled = validator.checkDefinition(agent, rel, errors) ?: return

            // HMR_AGENT_DIR is a Homer-owned system var (the agent's own config
            // directory), injected last so it wins over any author-set value.
            // The HMR_ prefix is reserved, so caller inputs cannot override it.
            val sysEnvBackedEnvNames = sysEnvResolver.secretNamesFor(agent.env)
            val resolvedEnv =
                sysEnvResolver.resolveMapValues(agent.env) +
                    (EnvConstants.AGENT_DIR to dir.toAbsolutePath().normalize().toString())
            if (!claimName(validatedName, rel, parsed, sourceDirs, collidedNames, errors)) return

            inputChecks.warnOnInputSteering(agent)
            parsed[validatedName] =
                agent.copy(
                    name = validatedName,
                    env = resolvedEnv,
                    // A disabled schedule is dropped here so `CronSchedulerService.tick`
                    // and `getScheduleStatus` (both keyed off `agent.cron`) need no change.
                    cron = agent.cron.takeUnless { cronDisabled },
                    writable = Files.isWritable(dir),
                    sysEnvBackedEnvNames = sysEnvBackedEnvNames,
                )
            sourceDirs[validatedName] = rel
            logger.info("Loaded agent: {} from {}", validatedName, rel)
        } catch (e: Exception) {
            logger.error("Error loading agent from {}: {}", rel, e.message, e)
            errors.add("Error loading agent from $rel: ${e.message}")
        }
    }

    /** Reads and decodes [agentPath], refusing a file over the size cap; `null` = skip it. */
    private fun readAgent(
        agentPath: Path,
        rel: String,
        errors: MutableList<String>,
    ): Agent? {
        // Cap input size before reading into memory. This is the DoS
        // backstop against a 1 GB agent.yaml — kaml's underlying
        // snakeyaml-engine already bounds depth and alias expansion,
        // but neither caps total input size. Audit P1 #14.
        val size = Files.size(agentPath)
        if (size > maxConfigFileBytes) {
            logger.error(
                "Agent definition '{}' in {} is {} bytes (cap {} bytes); skipping.",
                agentPath.fileName,
                rel,
                size,
                maxConfigFileBytes,
            )
            errors.add(
                "Agent definition '${agentPath.fileName}' in $rel is $size bytes; cap is $maxConfigFileBytes",
            )
            return null
        }
        val raw = agentPath.readText()
        val agent =
            if (agentPath.extension == "json") {
                json.decodeFromString<Agent>(raw)
            } else {
                Yaml.default.decodeFromString(Agent.serializer(), raw)
            }

        return agent
    }

    /** Claims [validatedName] for [rel], failing every directory that declares the same name; `false` = skip. */
    private fun claimName(
        validatedName: String,
        rel: String,
        parsed: MutableMap<String, Agent>,
        sourceDirs: MutableMap<String, String>,
        collidedNames: MutableSet<String>,
        errors: MutableList<String>,
    ): Boolean {
        // Deterministic duplicate-name guard: a name claimed by more than one
        // directory fails ALL claimants (never silently shadows). Iteration
        // order is unspecified, so picking a winner would be nondeterministic
        // and a writable dir could shadow a trusted agent.
        if (validatedName in collidedNames) {
            val msg =
                "Agent name '$validatedName' is declared by multiple directories (including $rel); " +
                    "all are skipped. Make each agent.name unique."
            logger.error(msg)
            errors.add(msg)
            return false
        }
        val priorDir = sourceDirs[validatedName]
        if (priorDir != null) {
            collidedNames.add(validatedName)
            parsed.remove(validatedName)
            sourceDirs.remove(validatedName)
            val msg =
                "Agent name '$validatedName' is declared by multiple directories ('$priorDir' and '$rel'); " +
                    "all are skipped. Make each agent.name unique."
            logger.error(msg)
            errors.add(msg)
            return false
        }

        return true
    }
}

/** What one [AgentScanner.scan] found: the loaded agents, their directories, every definition directory present, and the errors. */
internal data class AgentScan(
    val parsed: Map<String, Agent>,
    val sourceDirs: Map<String, String>,
    val presentDirNames: Set<String>,
    val errors: List<String>,
) {
    fun toState() = AgentCatalogState(parsed, sourceDirs)
}
