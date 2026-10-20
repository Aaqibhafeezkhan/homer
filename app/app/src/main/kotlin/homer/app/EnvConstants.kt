package homer.app

object EnvConstants {
    const val WORK_DIR = "HMR_WORK_DIR"
    const val ARTIFACTS_DIR = "HMR_ARTIFACTS_DIR"
    const val PROCESS_ID = "HMR_PROCESS_ID"
    const val SERVER_ID = "HMR_SERVER_ID"
    const val AGENT_NAME = "HMR_AGENT_NAME"

    /**
     * Absolute path to the agent's own on-disk config directory (the directory
     * holding its `agent.yaml` / `agent.json`). Lets a command read sibling files
     * shipped with the agent definition (e.g. `cat "$HMR_AGENT_DIR/prompt.txt"`).
     * Populated by `AgentsService` at load time from the agent's source directory.
     */
    const val AGENT_DIR = "HMR_AGENT_DIR"
    const val API_URL = "HMR_API_URL"

    /**
     * Per-process random token authorizing every callback route
     * under `/api/v1/callback/` (questions, tags, dispatches, runs, joins and
     * results) on behalf of exactly this process. Minted by
     * `QuestionsService.registerProcess` when the process starts; never a
     * real operator credential. See ADR-0019.
     */
    const val CALLBACK_TOKEN = "HMR_CALLBACK_TOKEN"

    /**
     * Absolute path to the pre-generated `.homer/mcp.json` wiring Claude at
     * the `/mcp/questions` endpoint. Only set when `server.mcp.enabled`;
     * agent authors opt in via `mcpConfig: "{{HMR_MCP_CONFIG}}"`. See ADR-0019.
     */
    const val MCP_CONFIG = "HMR_MCP_CONFIG"

    /**
     * Comma-separated chain of agent names that led to the current invocation
     * (root call is ""; depth 1 is "root"; depth 2 is "root,child"; etc.).
     * Threaded from parent to child by RunAgent's in-process dispatch and used
     * for RunAgent recursion cycle/depth enforcement. See ADR-0030.
     */
    const val INVOCATION_CHAIN = "HMR_INVOCATION_CHAIN"

    /**
     * Id of the run that caused the current one (RunAgent, dispatch, continuation,
     * or an external caller's [PARENT_PROCESS_HEADER]). Absent at the root of a
     * flow. Persisted on the process row and served by the status API. See ADR-0058.
     */
    const val PARENT_PROCESS_ID = "HMR_PARENT_PROCESS_ID"

    /**
     * Id of the first run of the current one's flow (the root), so an agent can
     * address its whole flow without reading `langgraph.json`. Absent at the root
     * of a flow itself, and persisted on the process row as `root_process_id`.
     * See ADR-0058.
     */
    const val ROOT_PROCESS_ID = "HMR_ROOT_PROCESS_ID"

    /**
     * Request header an external caller of `POST /api/v1/agent/{name}` sets to link
     * the new run to a parent it can see. See ADR-0058.
     */
    const val PARENT_PROCESS_HEADER = "X-Homer-Parent-Process-Id"

    /**
     * LangGraph thread id carried by a completion continuation from its `params`
     * into the resumed target run, so the parked graph resumes the right thread.
     * See ADR-0023.
     */
    const val LG_THREAD = "HMR_LG_THREAD"

    /**
     * Default env var name a completion continuation writes the watched process's
     * terminal result into (`{status, processId, result}` JSON) when firing the
     * target run. Overridable per continuation via `resumeParam`. See ADR-0023.
     */
    const val LG_RESUME = "HMR_LG_RESUME"

    /**
     * Interrupt id(s) the runtime carries into a resume run — one id for a
     * sequential resume, comma-joined ids for a join. See ADR-0026.
     */
    const val LG_INTERRUPT = "HMR_LG_INTERRUPT"

    /**
     * Set to "1" by `POST /api/v1/processes/{id}/resume` to re-enter a crashed
     * LangGraph thread from its latest checkpoint. Read by the Python runtime;
     * ignored when [LG_RESUME] is present (a crashed interrupt-resume segment
     * replays its resume value instead). See ADR-0044.
     */
    const val LG_RECOVER = "HMR_LG_RECOVER"

    /**
     * Default env var name a `!<langgraph>` command reads its initial input
     * payload from. Overridable per command via `inputEnv`. See ADR-0023.
     */
    const val LG_INPUT = "HMR_LG_INPUT"

    /**
     * Absolute path to the durable LangGraph checkpoint directory, injected into
     * the env of every `!<langgraph>` command so the Python `homer_langgraph`
     * runtime can open its checkpointer there. Resolved from server config via
     * [homer.app.models.AppConfig.resolveLangGraphStateDir]; uses the `HOMER_`
     * prefix (not `HMR_`) because it is consumed by the Python runtime, not the
     * Homer-internal env contract. See ADR-0024.
     */
    const val LG_STATE_DIR = "HOMER_LG_STATE_DIR"

    /**
     * Zero-based command index and zero-based retry-attempt index. Template-only
     * variables for `Agent.artifactNameTemplate` (rendered by `ArtifactNameResolver`);
     * never exported into a command's process env. See AGENT.md.
     */
    const val CMD_INDEX = "HMR_CMD_INDEX"
    const val CMD_ATTEMPT = "HMR_CMD_ATTEMPT"

    /**
     * Outcome of the LLM tier that just ran (`success`, `failed`, `capped`, `malformed`) and its
     * exit code (`-1` for `malformed`). Set only in the env of an `escalate[].when` predicate,
     * never in a command's process env. See ADR-0079.
     */
    const val LLM_OUTCOME = "HMR_LLM_OUTCOME"
    const val LLM_EXIT_CODE = "HMR_LLM_EXIT_CODE"

    /**
     * Default `Agent.artifactNameTemplate` stem: renders the same path on every
     * attempt, so a command's artifacts truncate in place exactly as before that
     * field existed. Single source shared by the model default
     * (`Agent.artifactNameTemplate`), `ExecutionContext.artifactNames`'s
     * constructible default, and `AgentExecutor`'s placeholder-path fallback when
     * rendering the agent's own (possibly custom) template fails.
     */
    const val DEFAULT_ARTIFACT_NAME_TEMPLATE = "cmd_{{$CMD_INDEX}}"
}
