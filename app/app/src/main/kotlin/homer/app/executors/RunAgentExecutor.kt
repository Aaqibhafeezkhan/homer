package homer.app.executors

import com.jayway.jsonpath.JsonPath
import homer.app.EnvConstants
import homer.app.exceptions.HomerException
import homer.app.infra.AgentInputResolver
import homer.app.infra.BoundedRegex
import homer.app.infra.TextCompiler
import homer.app.models.Command
import homer.app.models.CommandResult
import homer.app.models.ResultTransformer
import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import kotlin.io.path.writeText

/**
 * Executes a `Command.RunAgent` (`!<agent>`) by dispatching the target agent
 * **in-process** through [SubRunStarter], over the same call-independent core
 * (`AgentRunStarter`) the HTTP route (`handleAgentExecution`) and `DispatchService`
 * use. There is no HTTP self-call, so dispatch works regardless of how the
 * originating run was authenticated (bearer, HMAC, or console session cookie).
 * Invocation-chain cycle/depth checks and ancestor-lock-key propagation are
 * preserved by passing them through to [SubRunStarter.start]. See ADR-0030.
 */
internal class RunAgentExecutor(
    private val subRuns: SubRunStarter,
    private val agentInputResolver: AgentInputResolver,
    private val textCompiler: TextCompiler,
) : CommandExecutor<Command.RunAgent> {
    override suspend fun execute(
        command: Command.RunAgent,
        context: ExecutionContext,
        span: Span,
    ): CommandResult {
        val params = resolveQueryParams(command, context)
        val agentName = textCompiler.replaceArguments(command.agentName, context.workspace.config.env)
        span.setAttribute("agent.name", agentName)
        span.setAttribute("agent.params", params.keys.joinToString(", "))

        // An unauthorized hop reads exactly like an unknown agent: the target name can come from
        // env, so this must not let agent code probe which agents exist (ADR-0047 rule 3).
        val target = subRuns.agent(agentName)?.takeIf { subRuns.canDispatchHop(context.owner, context.agentName, agentName) }
        if (target == null) {
            context.stdErr.writeText("agent '$agentName' not found")
            return CommandResult(exitCode = 255, stdOut = context.stdOut, stdErr = context.stdErr)
        }

        val invocationChain = invocationChainOf(context)
        // Forward the flow's held lock keys plus this run's own resolved key so a
        // descendant that opts into acceptParentLock can detect the shared key and
        // pass through instead of self-colliding. See ADR-0029.
        val ancestorLockKeys = context.workspace.config.ancestorLockKeys + context.workspace.config.lockKey

        return try {
            val processId =
                subRuns.start(
                    agent = target,
                    invocationChain = invocationChain,
                    source = "agent",
                    ancestorLockKeys = ancestorLockKeys,
                    owner = context.owner,
                    parentProcessId = context.processId.takeIf { it > 0 },
                    // [information_disclosure / CWE-200] The child is attributed to this
                    // run's owner, so the root it inherits is gated on that owner's
                    // visibility — a foreign flow-root id reaches no row, env or span. §12.
                    rootProcessId = subRuns.visibleOriginRootProcessId(context.processId, context.owner),
                ) {
                    agentInputResolver.resolveFromParams(params, target)
                }
            context.stdOut.writeText("processId: $processId")
            span.setAttribute("agent.processId", processId.toString())
            CommandResult(exitCode = 0, stdOut = context.stdOut, stdErr = context.stdErr)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HomerException) {
            // A child cycle/depth/cost-cap/validation rejection mirrors today's
            // HTTP-error → 255 mapping, so continueIfFail / finishExitCode still
            // apply at the AgentExecutor level.
            context.stdErr.writeText(e.message ?: "agent dispatch failed")
            CommandResult(exitCode = 255, stdOut = context.stdOut, stdErr = context.stdErr)
        }
    }

    /** The target's query params from this run's env, each through its transformer; an unset or unmatched one is dropped. */
    private fun resolveQueryParams(
        command: Command.RunAgent,
        context: ExecutionContext,
    ): Map<String, String> {
        // §12 rule 4: the stall a dispatch can be made to pay is one bounded match, not
        // one per param. After a regex is shed on timeout the remaining regex
        // transformers are dropped too; the other transformer kinds are untouched.
        var regexTimedOut = false
        return command.queryParams
            .mapNotNull { param ->
                val envValue = context.workspace.config.env[param.envName] ?: return@mapNotNull null
                if (regexTimedOut && param.transformer is ResultTransformer.Regex) return@mapNotNull null
                val transformed =
                    applyTransformer(param.envName, envValue, param.transformer) { regexTimedOut = true }
                        ?: return@mapNotNull null
                param.paramName to transformed
            }.toMap()
    }

    private fun invocationChainOf(context: ExecutionContext): List<String> =
        context.workspace.config.env[EnvConstants.INVOCATION_CHAIN]
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun applyTransformer(
        envName: String,
        value: String,
        transformer: ResultTransformer,
        onRegexTimeout: () -> Unit,
    ): String? =
        when (transformer) {
            ResultTransformer.Pass -> {
                value
            }

            is ResultTransformer.JsonPath -> {
                runCatching {
                    JsonPath.parse(value).read<Any>(transformer.path)?.toString()
                }.getOrNull()
            }

            is ResultTransformer.Regex -> {
                // Author-written pattern, caller-influenceable subject: matched under the
                // same bound as `resultEnvParam`, and a shed match drops the param exactly
                // as a non-match does. §12 rule 4.
                BoundedRegex.findOrOmit(transformer.regex, value, envName, logger, onRegexTimeout)?.let { match ->
                    if (transformer.group != null) match.groupValues.getOrNull(transformer.group) else match.value
                }
            }
        }

    private companion object {
        private val logger = LoggerFactory.getLogger(RunAgentExecutor::class.java)
    }
}
