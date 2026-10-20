package homer.app.infra

import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.ParamSource
import homer.app.models.TagValidation
import org.slf4j.Logger

/**
 * The load-time checks `AgentsService` runs on an agent's inputs and on what its commands
 * bind them to: reserved and malformed envNames, tags (ADR-0060), duplicate and invalid
 * input declarations, result-capture envNames and input-chosen executables — plus the
 * warn-only deprecation and run-identity-steering notes. Each `check*` reports its own
 * failure into `errors` and returns `false` to skip the agent.
 */
internal class AgentInputChecks(
    private val logger: Logger,
    private val paramValidator: ParamValidator,
) {
    private val envNamePattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val templateRefPattern = Regex("""\{\{(\w+)\}\}""")

    /** Rejects reserved header params and reserved or malformed input envNames; `false` = skip the agent. */
    fun checkInputEnvNames(
        agent: Agent,
        errors: MutableList<String>,
    ): Boolean {
        val invalidHeader =
            agent.effectiveInputs
                ?.firstOrNull { it.src == ParamSource.HEADER && ReservedHeaders.isReserved(it.paramName) }
        if (invalidHeader != null) {
            logger.error(
                "Agent '{}': header param '{}' is reserved and cannot be bound. Skipping.",
                agent.name,
                invalidHeader.paramName,
            )
            errors.add(
                "Agent '${agent.name}': header param '${invalidHeader.paramName}' is reserved and cannot be bound",
            )
            return false
        }

        val invalidEnvName =
            agent.effectiveInputs
                ?.firstOrNull { ReservedEnvNames.isReserved(it.envName) }
        if (invalidEnvName != null) {
            logger.error(
                "Agent '{}': input '{}' targets reserved envName '{}' (Homer / host-controlled). Skipping.",
                agent.name,
                invalidEnvName.paramName,
                invalidEnvName.envName,
            )
            errors.add(
                "Agent '${agent.name}': input '${invalidEnvName.paramName}' targets reserved envName '${invalidEnvName.envName}'",
            )
            return false
        }

        val malformedEnvName =
            agent.effectiveInputs?.firstOrNull { !envNamePattern.matches(it.envName) }
        if (malformedEnvName != null) {
            logger.error(
                "Agent '{}': input '{}' has envName '{}' that is not a valid environment variable name " +
                    "(^[A-Za-z_][A-Za-z0-9_]*$). Declare an explicit envName. Skipping.",
                agent.name,
                malformedEnvName.paramName,
                malformedEnvName.envName,
            )
            errors.add(
                "Agent '${agent.name}': input '${malformedEnvName.paramName}' has invalid envName '${malformedEnvName.envName}'",
            )
            return false
        }

        return true
    }

    /** Validates tags.fromInputs and tags.runtime (ADR-0060); `false` = skip the agent. */
    fun checkTags(
        agent: Agent,
        errors: MutableList<String>,
    ): Boolean = checkFromInputTags(agent, errors) && checkRuntimeTags(agent, errors)

    /** Validates tags.fromInputs and its deprecated telemetry.traceTags alias; `false` = skip the agent. */
    private fun checkFromInputTags(
        agent: Agent,
        errors: MutableList<String>,
    ): Boolean {
        // tags.fromInputs (and the deprecated telemetry.traceTags alias it unions with)
        // name inputs by paramName. Validating here rather than per-input is what the
        // indirection costs: a renamed input must fail loudly at load, not silently stop
        // tagging. Three ways a fromInputs entry can be wrong:
        @Suppress("DEPRECATION")
        if (agent.telemetry.traceTags.isNotEmpty()) {
            logger.warn(
                "Agent '{}': telemetry.traceTags is deprecated; use the top-level tags.fromInputs block instead.",
                agent.name,
            )
        }
        val badFromInputTag = fromInputTagProblem(agent)
        if (badFromInputTag != null) {
            logger.error(
                "Agent '{}': tags.fromInputs entry '{}' {}. Skipping.",
                agent.name,
                badFromInputTag.first,
                badFromInputTag.second,
            )
            errors.add("Agent '${agent.name}': tags.fromInputs entry '${badFromInputTag.first}' ${badFromInputTag.second}")
            return false
        }

        return true
    }

    /** The first tags.fromInputs entry that cannot become a tag, paired with why; null when every entry can. */
    private fun fromInputTagProblem(agent: Agent): Pair<String, String>? {
        val declaredParamNames = agent.effectiveInputs.orEmpty().associateBy { it.paramName }
        return agent.effectiveFromInputTags.firstNotNullOfOrNull { name ->
            val param = declaredParamNames[name]
            when {
                param == null -> {
                    "names no declared input"
                }

                param.secret -> {
                    "names the secret input '$name'; a run tag is exported in the clear"
                }

                // [denial_of_service / CWE-400] The paramName becomes the stored tag
                // key, so it must fit the key charset and bound: an off-charset or
                // over-long name would mint tags no `?tag=` query can ever match, on
                // every run. paramName is otherwise unconstrained (headers).
                !TagValidation.KEY_PATTERN.matches(name) -> {
                    "names input '$name', whose paramName is not ^[A-Za-z0-9._-]{1,64}\$ and cannot be a tag key"
                }

                else -> {
                    null
                }
            }?.let { name to it }
        }
    }

    /** Validates tags.runtime: each key's charset, and disjointness from tags.fromInputs (ADR-0060); `false` = skip. */
    private fun checkRuntimeTags(
        agent: Agent,
        errors: MutableList<String>,
    ): Boolean {
        // A tags.runtime key is filled at run time via the callback route, so it is
        // validated for its charset only. It must also be disjoint from the fromInputs
        // keys: a key in both would let a run add a second, contradictory value for its
        // own subject (re-pointing what it ran over). See ADR-0060.
        val badRuntimeKey = agent.tags.runtime.firstOrNull { !TagValidation.KEY_PATTERN.matches(it) }
        if (badRuntimeKey != null) {
            logger.error(
                "Agent '{}': tags.runtime key '{}' is not ^[A-Za-z0-9._-]{{1,64}}$. Skipping.",
                agent.name,
                badRuntimeKey,
            )
            errors.add("Agent '${agent.name}': tags.runtime key '$badRuntimeKey' is not ^[A-Za-z0-9._-]{1,64}\$")
            return false
        }
        val overlappingKey = agent.tags.runtime.firstOrNull { it in agent.effectiveFromInputTags }
        if (overlappingKey != null) {
            logger.error(
                "Agent '{}': tags.runtime key '{}' also appears in tags.fromInputs; keys must be disjoint. Skipping.",
                agent.name,
                overlappingKey,
            )
            errors.add(
                "Agent '${agent.name}': tags.runtime key '$overlappingKey' also appears in tags.fromInputs; keys must be disjoint",
            )
            return false
        }

        return true
    }

    /** Rejects duplicate input envNames / paramNames and invalid input declarations; `false` = skip the agent. */
    fun checkInputUniqueness(
        agent: Agent,
        errors: MutableList<String>,
    ): Boolean {
        val duplicateEnvName =
            agent.effectiveInputs
                ?.groupBy { it.envName }
                ?.entries
                ?.firstOrNull { it.value.size > 1 }
        if (duplicateEnvName != null) {
            logger.error(
                "Agent '{}': multiple inputs target envName '{}'. Skipping.",
                agent.name,
                duplicateEnvName.key,
            )
            errors.add("Agent '${agent.name}': multiple inputs target envName '${duplicateEnvName.key}'")
            return false
        }

        val duplicateParam =
            agent.effectiveInputs
                ?.groupBy { it.src to it.paramName }
                ?.entries
                ?.firstOrNull { it.value.size > 1 }
        if (duplicateParam != null) {
            logger.error(
                "Agent '{}': duplicate input paramName '{}' for src '{}'. Skipping.",
                agent.name,
                duplicateParam.key.second,
                duplicateParam.key.first,
            )
            errors.add(
                "Agent '${agent.name}': duplicate input paramName '${duplicateParam.key.second}' for src '${duplicateParam.key.first}'",
            )
            return false
        }

        val declarationErrors =
            agent.effectiveInputs.orEmpty().flatMap { paramValidator.validateDeclaration(it) }
        if (declarationErrors.isNotEmpty()) {
            logger.error(
                "Agent '{}': invalid input declaration: {}. Skipping.",
                agent.name,
                declarationErrors.joinToString("; "),
            )
            errors.add("Agent '${agent.name}': " + declarationErrors.joinToString("; "))
            return false
        }

        return true
    }

    /** Rejects result captures into invalid envNames and input-chosen LLM executables; `false` = skip the agent. */
    fun checkCommandBindings(
        agent: Agent,
        errors: MutableList<String>,
    ): Boolean {
        // Result-capture env names get the same guardrail as input env names:
        // the captured *value* is attacker-influenceable (command output / Claude
        // output steerable by prompt injection), and appendToEnv merges it into the
        // env consumed by later commands. Without this, a `resultEnvParam` targeting
        // PATH or HMR_CALLBACK_TOKEN poisons subsequent commands. See ReservedEnvNames.
        val invalidResultEnvName =
            agent.commands
                .flatMap { cmd -> cmd.resultEnvParam.map { cmd to it } }
                .firstOrNull { (_, param) ->
                    ReservedEnvNames.isReserved(param.envName) || !envNamePattern.matches(param.envName)
                }
        if (invalidResultEnvName != null) {
            val (cmd, param) = invalidResultEnvName
            logger.error(
                "Agent '{}': command '{}' captures a result into invalid envName '{}' " +
                    "(reserved Homer/host name, or not matching ^[A-Za-z_][A-Za-z0-9_]*$). Skipping.",
                agent.name,
                cmd.label,
                param.envName,
            )
            errors.add(
                "Agent '${agent.name}': command '${cmd.label}' captures a result into invalid envName '${param.envName}'",
            )
            return false
        }

        // An LLM command's `cmd` is argv[0]: an input reference there lets the
        // caller choose the host executable Homer runs. Unlike the steering
        // warnings below, this is never legitimate. See AGENT.md security notes.
        val inputEnvNames =
            agent.effectiveInputs
                .orEmpty()
                .map { it.envName }
                .toSet()
        val inputBoundExecutable =
            agent.commands
                .filterIsInstance<Command.LlmCommand>()
                .firstNotNullOfOrNull { cmd ->
                    templateRefPattern
                        .findAll(cmd.cmd)
                        .map { it.groupValues[1] }
                        .firstOrNull { it in inputEnvNames }
                        ?.let { cmd to it }
                }
        if (inputBoundExecutable != null) {
            val (cmd, input) = inputBoundExecutable
            val msg =
                "Agent '${agent.name}': command '${cmd.label}' cmd references input '{{$input}}'; " +
                    "an input must not choose the executable"
            logger.error("{}. Skipping.", msg)
            errors.add(msg)
            return false
        }

        return true
    }

    /** WARNs on deprecated param types and the deprecated `queryParams` alias. */
    fun warnOnDeprecatedInputs(agent: Agent) {
        // One WARN per agent, not per param: `type` defaults to STRING, so
        // per-param warnings would spam every agent that never set a type.
        if (agent.effectiveInputs.orEmpty().any { it.type.isLegacy }) {
            logger.warn(
                "Agent '{}' uses deprecated param types (STRING/NUMBER/ANY); " +
                    "migrate to typed params (string, integer, number, boolean).",
                agent.name,
            )
        }
        @Suppress("DEPRECATION")
        if (agent.queryParams != null) {
            logger.warn(
                "Agent '{}' uses the deprecated 'queryParams' alias; rename it to 'inputs'.",
                agent.name,
            )
        }
    }

    /**
     * WARN when caller-supplied inputs can steer run identity: an input-bound
     * envName referenced from the workspace path, the lock key, or a RunAgent
     * target template lets a caller pick another run's directory / lock / target.
     * Legitimate (e.g. per-tenant lock keys) — hence a warning, not an error.
     */
    fun warnOnInputSteering(agent: Agent) {
        val inputEnvNames =
            agent.effectiveInputs
                .orEmpty()
                .map { it.envName }
                .toSet()
        if (inputEnvNames.isEmpty()) return

        fun refs(template: String) = templateRefPattern.findAll(template).map { it.groupValues[1] }.toSet() intersect inputEnvNames
        val steered =
            buildList {
                refs(agent.workspace.path).forEach { add("workspace.path uses input '{{$it}}'") }
                refs(agent.lockStrategy.key).forEach { add("lockStrategy.key uses input '{{$it}}'") }
                agent.commands.filterIsInstance<Command.RunAgent>().forEach { cmd ->
                    refs(cmd.agentName).forEach { add("agent command '${cmd.label}' target uses input '{{$it}}'") }
                }
                // Claude path/mode options grant filesystem, config, or permission
                // power; a caller-steered value here is an execution-control surface.
                agent.commands.filterIsInstance<Command.Claude>().forEach { cmd ->
                    val o = cmd.options

                    fun flag(
                        name: String,
                        it: String,
                    ) = add("claude command '${cmd.label}' option '$name' uses input '{{$it}}'")
                    mapOf(
                        "settings" to o.settings,
                        "systemPromptFile" to o.systemPromptFile,
                        "appendSystemPromptFile" to o.appendSystemPromptFile,
                        "permissionMode" to o.permissionMode,
                        "permissionPromptTool" to o.permissionPromptTool,
                    ).forEach { (name, value) ->
                        if (value != null) refs(value).forEach { flag(name, it) }
                    }
                    mapOf(
                        "addDir" to o.addDir,
                        "mcpConfig" to o.mcpConfig,
                        "pluginDir" to o.pluginDir,
                        "pluginUrl" to o.pluginUrl,
                        "file" to o.file,
                    ).forEach { (name, values) ->
                        values?.forEach { value -> refs(value).forEach { flag(name, it) } }
                    }
                }
            }
        if (steered.isNotEmpty()) {
            logger.warn(
                "Agent '{}': caller-supplied inputs steer run identity — {}. " +
                    "Ensure this is intentional (see AGENT.md security notes).",
                agent.name,
                steered.joinToString("; "),
            )
        }
    }
}
