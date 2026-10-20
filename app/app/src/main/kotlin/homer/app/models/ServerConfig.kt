package homer.app.models

import kotlinx.serialization.Serializable

@Serializable
data class RateLimitConfig(
    val limit: Int = 1000,
    val refillPeriod: Long = 60, // seconds
)

@Serializable
data class Server(
    val port: Int = 8080,
    /**
     * API authentication. Required: `null` means the config omitted it, and startup
     * fails — running without auth needs an explicit `apiAuth: !<unsecured>` opt-in
     * (see ADR-0036).
     */
    val apiAuth: AuthConfig? = null,
    val rateLimit: RateLimitConfig = RateLimitConfig(),
    val perCallerRateLimit: RateLimitConfig = RateLimitConfig(limit = 300, refillPeriod = 60),
    val loginRateLimit: RateLimitConfig = RateLimitConfig(limit = 5, refillPeriod = 60),
    val corsAllowedOrigins: List<String> = emptyList(),
    /**
     * Maximum allowed depth of nested RunAgent invocations. The root API call is
     * depth 1; an agent that runs another agent makes that child depth 2, and so
     * on. A misconfigured agent that calls itself (directly or transitively)
     * would otherwise burn unbounded tokens before anyone notices. 5 is generous
     * for legitimate composition (A → B → C → D → E); raise it explicitly if
     * deeper chains are needed.
     */
    val maxAgentInvocationDepth: Int = 5,
    /**
     * Maximum number of process rows retained. At the cap a new run evicts the
     * oldest *terminal* row (deleting its workspace); if none can be evicted the
     * run is refused with 503. This is the hard ceiling on retention: raising
     * `workspace.retain.recordFor` only keeps records until the cap forces them
     * out, so raise this too when you want a long record history.
     */
    val maxProcesses: Int = 10_000,
    /**
     * Maximum accepted HTTP request body size in bytes for all endpoints.
     * Bodies over the cap are rejected with 413 before being read into
     * memory. Closes audit 2026-07-11 H1 (unbounded `receiveText`).
     */
    val maxRequestBodyBytes: Long = 10L * 1024 * 1024,
    /**
     * Maximum seconds to wait for in-flight ProcessExecutor jobs to
     * complete before the HTTP engine stops. Closes audit P0 #3. The
     * shutdown sequence: stop accepting cron + sweep work, wait for the
     * application coroutine scope's children up to this many seconds,
     * then stop the Ktor engine. Set higher if your agents commonly run
     * longer than 30s; set lower for ephemeral debug containers.
     */
    val shutdownDrainSec: Int = 30,
    /**
     * If set, every successful Claude run must report this exact value in
     * its `system` event's `claude_code_version` field. A mismatch (or a
     * missing field when the pin is set) fails the run with
     * `ClaudeCliVersionMismatchException` and is *not* retried — a CLI
     * upgrade between attempts cannot fix it. Leave null to skip the
     * check. Closes audit P1 #15.
     */
    val requireClaudeCodeVersion: String? = null,
    /**
     * `system`-event subtypes dropped from a Claude step's stdout before it is
     * written. Entries must match `^[a-zA-Z0-9_]+$`; `init` is refused (it carries
     * `claude_code_version`). `[]` disables. See ADR-0063.
     */
    val claudeDropSystemSubtypes: List<String> = listOf("thinking_tokens"),
    /**
     * Maximum size in bytes of a single artifact file served by
     * `GET /api/v1/artifacts`. Files larger than this are rejected with
     * `413 Payload Too Large` before any bytes are sent, so a misbehaving
     * agent that fills its artifacts directory with a multi-GB file
     * cannot turn an artifact fetch into a sustained bandwidth/time DoS.
     * The check is pre-flight `Files.size()` only — Range support for
     * partial downloads of legitimately-large files is not implemented;
     * raise this value (or split the artifact at the agent) if a real
     * use case needs >100 MiB downloads. Audit P1 #18.
     */
    val maxArtifactBytes: Long = 100L * 1024 * 1024,
    /**
     * Per-run byte cap on artifacts retained past the workspace purge
     * (`workspace.retain.artifacts`). The retained set is agent-written and
     * lives until the record dies, so the operator's disk ceiling is
     * `maxProcesses` x this value. Overflow keeps what fits in declared-glob
     * order and flags the record `retainedTruncated`. See ADR-0053.
     */
    val maxRetainedArtifactBytes: Long = 16L * 1024 * 1024,
    /**
     * Maximum size in bytes of a single agent definition file (`agent.json`
     * / `agent.yaml`), agent-directory file accessed via the files API,
     * or the top-level Homer config file (`homer-config.{json,yaml}`).
     * Files larger than this are rejected pre-flight, before kaml /
     * kotlinx.serialization parse them. Default 1 MiB — real agent
     * definitions are kilobytes; this cap exists to bound DoS exposure
     * from a pathological YAML (oversized strings, huge anchor lists)
     * regardless of what the underlying parser allows. Note: the kaml
     * parser is built on snakeyaml-engine 2.7, which already enforces
     * `nestingDepthLimit=50` and `maxAliasesForCollections=50` by
     * default, so the classic billion-laughs / deep-nesting vectors
     * are bounded; this cap is the missing piece for total input
     * size. Audit P1 #14.
     */
    val maxConfigFileBytes: Long = 1L * 1024 * 1024,
    /** Claim-queue facets of the built-in `local` runner. See ADR-0066. */
    val localRunner: LocalRunnerConfig = LocalRunnerConfig(),
    /** Default seconds a run waits for a runner to claim it; agents may override. See ADR-0066. */
    val runnerClaimTimeoutSec: Int = 300,
    /** Local bound on pending claims; a run arriving at the bound fails at once. See ADR-0066. */
    val maxPendingRunnerClaims: Int = 1000,
    /**
     * Maximum size in bytes of a single command's stdout file
     * (`cmd_N.out`) and stderr file (`cmd_N.err`) — each stream is
     * capped independently at this value. When either file's size
     * exceeds the cap, the command is killed via the same
     * `destroyForcibly`+descendants path used for TTL timeouts, an
     * `[HOMER] Output cap exceeded ($cap bytes)` line is appended to
     * the stderr file, and the executor throws
     * `OutputCapExceededException`. The per-command
     * `ExecutionInfo.outputTruncated` flag surfaces the kill reason
     * over the API. Default 10 MiB (`10485760`). The cap is enforced
     * inside the existing 1-second polling loop, so the effective
     * on-disk size at kill time may exceed the cap by one polling
     * window's worth of writes — treat it as a soft ceiling.
     * Audit P1 #17.
     */
    val maxOutputBytes: Long = 10L * 1024 * 1024,
    /**
     * Polling interval in milliseconds for the file poll behind
     * `GET /api/v1/stream/processes/{id}/llm-events`. Lower =
     * lower first-event latency, higher = less CPU. Coerced to 50..2000 at
     * startup. Default 150 (~6 polls/sec; invisible latency to humans).
     */
    val claudeStreamPollMs: Int = 150,
    val mcp: McpServerConfig = McpServerConfig(),
    val questions: QuestionsConfig = QuestionsConfig(),
    val dispatch: DispatchConfig = DispatchConfig(),
    val langGraph: LangGraphConfig = LangGraphConfig(),
    /**
     * Per-agent grants keyed by agent name: which target agents (and actions)
     * that agent's own dispatches (e.g. RunAgent, ask-and-dispatch) may reach.
     * See ADR-0037.
     */
    val agentGrants: Map<String, List<Grant>> = emptyMap(),
) {
    init {
        require(maxRequestBodyBytes > 0) { "server.maxRequestBodyBytes must be > 0" }
        claudeDropSystemSubtypes.forEach { subtype ->
            require(SYSTEM_SUBTYPE_PATTERN.matches(subtype)) {
                "server.claudeDropSystemSubtypes entries must match ^[a-zA-Z0-9_]+$; got '$subtype'"
            }
            require(subtype != "init") {
                "server.claudeDropSystemSubtypes must not contain 'init': that event carries " +
                    "claude_code_version, which server.requireClaudeCodeVersion reads"
            }
        }
        require(runnerClaimTimeoutSec >= 1) { "server.runnerClaimTimeoutSec must be >= 1; got $runnerClaimTimeoutSec" }
        require(maxPendingRunnerClaims >= 1) { "server.maxPendingRunnerClaims must be >= 1; got $maxPendingRunnerClaims" }
    }

    companion object {
        private val SYSTEM_SUBTYPE_PATTERN = Regex("^[a-zA-Z0-9_]+$")
    }
}

/**
 * LangGraph runtime configuration. `stateDir` is the durable directory for
 * SQLite checkpoints, which must live OUTSIDE the ephemeral per-process
 * workspace so continuations survive across runs. When null, the effective
 * directory is derived beside `databasePath` — see [AppConfig.resolveLangGraphStateDir].
 */
@Serializable
data class LangGraphConfig(
    val stateDir: String? = null,
)

/**
 * Streamable HTTP MCP endpoint at `/mcp`. Disabled by default; when enabled,
 * agents with `mcp.enabled: true` are exposed as MCP tools alongside the
 * run-management tools. See ADR-0018.
 */
@Serializable
data class McpServerConfig(
    val enabled: Boolean = false,
    /**
     * Upper bound (seconds) for the per-call `_wait` argument on agent tools.
     * Requested waits are clamped to `0..maxWaitSec`.
     */
    val maxWaitSec: Int = 300,
    /**
     * Maximum artifact size (bytes) the `get_artifact` tool inlines into the
     * tool result. Larger files are truncated to this many bytes.
     */
    val maxInlineArtifactBytes: Long = 65_536,
) {
    init {
        require(maxWaitSec >= 0) { "server.mcp.maxWaitSec must be >= 0; got $maxWaitSec" }
        require(maxInlineArtifactBytes > 0) {
            "server.mcp.maxInlineArtifactBytes must be > 0; got $maxInlineArtifactBytes"
        }
    }
}

/**
 * Human-in-the-loop questions: running commands ask the operator via the
 * injected `homer-ask` helper or the MCP question tools and wait for an
 * answer. See ADR-0019.
 */
@Serializable
data class QuestionsConfig(
    /**
     * Upper bound (seconds) for a single answer-wait. Caller-supplied waits
     * (REST `waitSec`, helper `--wait`/`--ttl`) are clamped to this value.
     * Waits are resumable — timing out returns the still-open question id and
     * the caller waits again — so this caps one HTTP hold, not the total time
     * a question may stay open. Default 86400 (1 day).
     */
    val maxWaitSec: Int = 86_400,
    /** Maximum simultaneously open questions per process; further asks are rejected. */
    val maxOpenPerProcess: Int = 10,
    /**
     * Maximum length (characters) of the question text and of each option.
     * Question payloads come from semi-trusted workspace commands and are held
     * in server memory until purge, so they are size-capped like every other
     * workspace-controlled input (`maxOutputBytes`, `maxArtifactBytes`, ...).
     */
    val maxTextLength: Int = 10_000,
    /** Maximum number of options per question. */
    val maxOptions: Int = 20,
    /**
     * Maximum simultaneously held answer-waits per process. Each wait pins a
     * connection and a coroutine for up to [maxWaitSec], so without a cap a
     * workspace command could exhaust the server by piling up long-polls.
     * Further waits are rejected with 429.
     */
    val maxConcurrentWaitsPerProcess: Int = 32,
) {
    init {
        require(maxWaitSec > 0) { "server.questions.maxWaitSec must be > 0; got $maxWaitSec" }
        require(maxOpenPerProcess > 0) {
            "server.questions.maxOpenPerProcess must be > 0; got $maxOpenPerProcess"
        }
        require(maxTextLength > 0) { "server.questions.maxTextLength must be > 0; got $maxTextLength" }
        require(maxOptions > 0) { "server.questions.maxOptions must be > 0; got $maxOptions" }
        require(maxConcurrentWaitsPerProcess > 0) {
            "server.questions.maxConcurrentWaitsPerProcess must be > 0; got $maxConcurrentWaitsPerProcess"
        }
    }
}

/**
 * ask-and-dispatch: a running command registers a question + a target agent;
 * answering starts that agent with the params and the answer. See the
 * ask-and-dispatch ADR.
 */
@Serializable
data class DispatchConfig(
    /** Global cap on PENDING dispatches; creating past this returns 429. */
    val maxPending: Int = 100,
    /** Default TTL (seconds) when the caller omits one; null = no expiry. */
    val defaultTtlSec: Int? = null,
    /** TTL sweep cadence (seconds). */
    val sweepIntervalSec: Int = 60,
    /**
     * How long (seconds, from creation) a settled dispatch or continuation — any status but
     * PENDING and DISPATCHING — is kept before the sweeps delete it. Floored at one hour.
     */
    val settledRetentionSec: Long = 604_800,
    /**
     * TTL (seconds) for read-by-reference result tokens minted when a watched
     * sub-process's result exceeds the inline cap. Floored at 60 s so a token
     * always outlives the continuation's immediate dispatch and the resumed
     * parent's first segment. See ADR-0025.
     */
    val resultTokenTtlSec: Int = 3600,
    /**
     * Max sub-agents a single `/callback/joins` request may fan out to. Caps the
     * synchronous per-request fan-out (each sub starts a process immediately); a
     * request over this is rejected with 400.
     */
    val maxJoinSubs: Int = 32,
) {
    init {
        require(maxPending > 0) { "server.dispatch.maxPending must be > 0; got $maxPending" }
        require(maxJoinSubs > 0) { "server.dispatch.maxJoinSubs must be > 0; got $maxJoinSubs" }
        require(defaultTtlSec == null || defaultTtlSec > 0) {
            "server.dispatch.defaultTtlSec must be > 0 (or null); got $defaultTtlSec"
        }
        require(sweepIntervalSec > 0) { "server.dispatch.sweepIntervalSec must be > 0; got $sweepIntervalSec" }
        require(settledRetentionSec >= 3600) {
            "server.dispatch.settledRetentionSec must be >= 3600; got $settledRetentionSec"
        }
        require(resultTokenTtlSec >= 60) {
            "server.dispatch.resultTokenTtlSec must be >= 60; got $resultTokenTtlSec"
        }
    }
}
