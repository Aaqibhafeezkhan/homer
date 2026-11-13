package homer.app.infra

import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.MAX_INTERRUPT_GRACE_SECONDS
import homer.app.models.MAX_ON_STOP_STEPS
import homer.app.models.OnTimeout
import homer.app.models.RunnerFilter
import org.slf4j.Logger

/**
 * The load-time validation of one decoded agent definition for `AgentsService`: its
 * name, command shape, runner filter and runnerCmds, the [AgentInputChecks], the cron
 * schedule and the inputSchema. Each check reports its own failure into `errors`; a
 * failed check skips the agent, the rest of the catalog still loads.
 */
internal class AgentDefinitionValidator(
    private val logger: Logger,
    private val runnerAliases: Set<String>,
    /** Read only by [checkRunnerFilter]'s warn-only unmatched-tags check (ADR-0066 deviation (9)). */
    private val runnerTagSets: () -> Collection<Set<String>>,
    private val inputResolver: AgentInputResolver,
    private val inputSchemaValidator: InputSchemaValidator,
    private val inputChecks: AgentInputChecks,
) {
    private fun validateAgentName(name: String): String? {
        if (!AgentDirs.AGENT_NAME_PATTERN.matches(name)) {
            return null
        }
        return name
    }

    /** [agent]'s validated name; `null` after reporting an invalid one into [errors]. */
    fun checkAgentName(
        agent: Agent,
        rel: String,
        errors: MutableList<String>,
    ): String? {
        val validatedName = validateAgentName(agent.name)
        if (validatedName == null) {
            logger.error(
                "Invalid agent name '{}' in {}. Only letters, digits, '_', '-' are allowed.",
                agent.name,
                rel,
            )
            errors.add("Invalid agent name '${agent.name}' in $rel")
        }
        return validatedName
    }

    /**
     * The shape, cron and input-schema checks, in order: whether the agent's cron schedule is
     * disabled, or `null` at the first failure, which reported itself into [errors].
     */
    fun checkDefinition(
        agent: Agent,
        rel: String,
        errors: MutableList<String>,
    ): Boolean? {
        if (!passesShapeChecks(agent, rel, errors)) return null
        inputChecks.warnOnDeprecatedInputs(agent)
        inputChecks.warnOnUncontainedClaudeTools(agent)
        val cronDisabled = checkCron(agent, rel, errors) ?: return null
        if (!checkInputSchema(agent, rel, errors)) return null
        return cronDisabled
    }

    /** The command, runner, input and tag checks, in order; false at the first failure, which reported itself into [errors]. */
    private fun passesShapeChecks(
        agent: Agent,
        rel: String,
        errors: MutableList<String>,
    ): Boolean =
        checkCommandShape(agent, rel, errors) &&
            checkStopPolicy(agent, rel, errors) &&
            checkRunnerFilter(agent, rel, errors) &&
            checkRunnerCmds(agent, rel, errors) &&
            inputChecks.checkInputEnvNames(agent, errors) &&
            inputChecks.checkTags(agent, errors) &&
            inputChecks.checkInputUniqueness(agent, errors) &&
            inputChecks.checkCommandBindings(agent, errors)

    /**
     * Rejects a graceful-stop declaration that cannot work: `onTimeout: stop` with no `onStop:`
     * steps to run, a stop step that is itself `onTimeout: stop` (a stop step is the run's last
     * chance, so it may not hand the run back to a stop phase), an interrupt grace outside
     * `0..MAX_INTERRUPT_GRACE_SECONDS`, and more stop steps than [MAX_ON_STOP_STEPS].
     *
     * A bare `onStop:` is valid — an agent may declare stop steps while keeping the default
     * `onTimeout: fail` and be stopped only by the run's deadline. See ADR-0108.
     */
    private fun checkStopPolicy(
        agent: Agent,
        rel: String,
        errors: MutableList<String>,
    ): Boolean {
        val steps = agent.commands + agent.onStop
        val badGrace = steps.firstOrNull { it.interruptGraceSeconds !in 0..MAX_INTERRUPT_GRACE_SECONDS }
        val failure =
            when {
                agent.onStop.size > MAX_ON_STOP_STEPS -> {
                    "declares ${agent.onStop.size} onStop steps, more than $MAX_ON_STOP_STEPS"
                }

                agent.commands.any { it.onTimeout == OnTimeout.STOP } && agent.onStop.isEmpty() -> {
                    "sets onTimeout: stop but declares no onStop steps"
                }

                agent.onStop.any { it.onTimeout == OnTimeout.STOP } -> {
                    "sets onTimeout: stop on an onStop step"
                }

                badGrace != null -> {
                    "declares interruptGraceSeconds ${badGrace.interruptGraceSeconds}, outside 0..$MAX_INTERRUPT_GRACE_SECONDS"
                }

                else -> {
                    null
                }
            } ?: return true
        logger.error("Agent '{}' in {} {}. Skipping.", agent.name, rel, failure)
        errors.add("Agent '${agent.name}' in $rel $failure")
        return false
    }

    /** Rejects an agent with no commands, or a langgraph command without dispatch; `false` = skip it. */
    private fun checkCommandShape(
        agent: Agent,
        rel: String,
        errors: MutableList<String>,
    ): Boolean {
        if (agent.commands.isEmpty()) {
            logger.error("Agent '{}' in {} has no commands. Skipping.", agent.name, rel)
            errors.add("Agent '${agent.name}' in $rel has no commands")
            return false
        }

        if (agent.commands.any { it is Command.LangGraph } && agent.dispatch?.enabled != true) {
            logger.error(
                "Agent '{}' in {} has a langgraph command but dispatch.enabled is not true. Skipping.",
                agent.name,
                rel,
            )
            errors.add(
                "Agent '${agent.name}' in $rel has a langgraph command but dispatch.enabled is not true",
            )
            return false
        }

        return true
    }

    /** Rejects an unknown runner alias and warns on runner tags no runner serves; `false` = skip the agent. */
    private fun checkRunnerFilter(
        agent: Agent,
        rel: String,
        errors: MutableList<String>,
    ): Boolean {
        val runnerFilter = agent.runnerFilter
        if (runnerFilter is RunnerFilter.Alias && runnerFilter.alias !in runnerAliases) {
            val msg =
                "Agent '${agent.name}' in $rel references unknown runner '${runnerFilter.alias}' " +
                    "(known: ${runnerAliases.sorted()}). Skipping."
            logger.error(msg)
            errors.add(msg)
            return false
        }
        if (runnerFilter is RunnerFilter.Tags) {
            // Warn-only: a throw from the provider (e.g. a registry read that
            // outruns the schema) must never skip the agent. The warn-only
            // extension is deviation (9) in ADR-0066; the migrate-before-first-
            // access invariant that makes such a throw impossible is ADR-0020.
            val servedTagSets =
                try {
                    runnerTagSets()
                } catch (e: Exception) {
                    logger.warn(
                        "Could not read the configured runner tag sets while checking agent '{}' in {}: {}. " +
                            "Skipping the unmatched-tags warning.",
                        agent.name,
                        rel,
                        e.message,
                    )
                    null
                }
            if (servedTagSets != null && servedTagSets.none { it.containsAll(runnerFilter.tags) }) {
                logger.warn(
                    "Agent '{}' in {} requests runner tags {} that no configured runner currently serves; " +
                        "its runs will wait for the claim TTL and then fail",
                    agent.name,
                    rel,
                    runnerFilter.tags.sorted(),
                )
            }
        }

        return true
    }

    /** Rejects runnerCmd commands off `runner: local`, on an unknown runner or with a bad sync path; `false` = skip. */
    private fun checkRunnerCmds(
        agent: Agent,
        rel: String,
        errors: MutableList<String>,
    ): Boolean {
        val runnerFilter = agent.runnerFilter
        val runnerCmds = agent.commands.filterIsInstance<Command.RunnerCmd>()
        if (runnerCmds.isNotEmpty()) {
            if (runnerFilter != RunnerFilter.Alias("local")) {
                val msg =
                    "Agent '${agent.name}' in $rel contains a runnerCmd command but uses ${runnerFilter.describe()}; " +
                        "agents with runnerCmd must run on runner: local. Skipping."
                logger.error(msg)
                errors.add(msg)
                return false
            }
            runnerCmds.firstOrNull { it.runner !in runnerAliases }?.let { bad ->
                val msg =
                    "Agent '${agent.name}' in $rel references unknown runner '${bad.runner}' " +
                        "in a runnerCmd (known: ${runnerAliases.sorted()}). Skipping."
                logger.error(msg)
                errors.add(msg)
                return false
            }
            val badPath =
                runnerCmds
                    .flatMap { it.syncToRemote + it.syncFromRemote }
                    .firstOrNull { p -> p.startsWith("/") || p.split('/').contains("..") || p.isBlank() }
            if (badPath != null) {
                val msg =
                    "Agent '${agent.name}' in $rel has an invalid runnerCmd sync path '$badPath' " +
                        "(must be relative, non-blank, without '..'). Skipping."
                logger.error(msg)
                errors.add(msg)
                return false
            }
        }

        return true
    }

    /** Validates the cron schedule: `null` = skip the agent, `true` = load it with the schedule disabled. */
    private fun checkCron(
        agent: Agent,
        rel: String,
        errors: MutableList<String>,
    ): Boolean? {
        val cron = agent.cron
        var cronDisabled = false
        if (cron != null) {
            val cronError = CronSupport.validate(cron)
            if (cronError != null) {
                logger.error(
                    "Agent '{}' in {} has an invalid cron schedule: {}. Skipping.",
                    agent.name,
                    rel,
                    cronError,
                )
                errors.add("Agent '${agent.name}' in $rel has an invalid cron schedule: $cronError")
                return null
            }
            // A schedule a required input can never satisfy is refused here,
            // not at tick time — see the invalid-cron branch above.
            val unsatisfiable = inputResolver.unsatisfiableWithoutDefaults(agent)
            if (unsatisfiable.isNotEmpty()) {
                val reason =
                    "cron schedule disabled — required input ${unsatisfiable.first()} has no default; " +
                        "scheduled runs pass no inputs"
                logger.error("Agent '{}' in {}: {}", agent.name, rel, reason)
                errors.add("Agent '${agent.name}' in $rel: $reason")
                cronDisabled = true
            }
        }

        return cronDisabled
    }

    /** Rejects a malformed inputSchema; `false` = skip the agent. */
    private fun checkInputSchema(
        agent: Agent,
        rel: String,
        errors: MutableList<String>,
    ): Boolean {
        val inputSchema = agent.inputSchema
        if (inputSchema != null) {
            try {
                inputSchemaValidator.precompile(inputSchema)
            } catch (e: Exception) {
                logger.error(
                    "Agent '{}' in {} has a malformed inputSchema: {}. Skipping.",
                    agent.name,
                    rel,
                    e.message,
                )
                errors.add("Agent '${agent.name}' in $rel has a malformed inputSchema: ${e.message}")
                return false
            }
        }

        return true
    }
}
