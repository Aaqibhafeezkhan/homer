package homer.app.infra

import homer.app.EnvConstants
import homer.app.exceptions.ValidationException
import io.ktor.http.Parameters

/**
 * Environment variable names that an agent's `Param.envName` cannot target.
 * Matched case-sensitively (POSIX env names are conventionally upper-case
 * and `PATH` ≠ `path` to the shell).
 *
 * Two reasons to reject:
 *
 *  1. **Homer's own namespace** (`HMR_*`). Names like `HMR_AGENT_NAME`,
 *     `HMR_PROCESS_ID`, `HMR_SERVER_ID`, `HMR_API_URL` are populated by
 *     the orchestrator. Caller-supplied input lands in the merged env at
 *     a precedence layer below some — but not all — of these (see
 *     `WorkspaceConfigFactory`), so binding an input to one of them
 *     would let an external caller spoof the agent's view of "who am
 *     I" / "what's my process id" / "where do I call back".
 *  2. **Host-controlled names** (`PATH`, `HOME`, `LD_*`, `JAVA_*`,
 *     `USER`, `SHELL`, `LANG`, `LC_*`, `TZ`, and the bash startup-file
 *     vars `BASH_ENV` / `ENV`). Letting a caller override these in the
 *     workspace env is an arbitrary-command-execution primitive — a
 *     tampered `PATH` is enough on its own, and `BASH_ENV` is sourced by
 *     the non-interactive `bash -c` that evaluates `when` predicates
 *     (`ENV` likewise in POSIX mode) before the trusted expression runs.
 *     Agent-author `env:` map entries can still set these (the author is
 *     trusted to own the agent definition), but caller-supplied input cannot.
 *
 * Validated at agent scan time so misconfiguration surfaces at startup,
 * not per request. Audit P1 #20.
 */
object ReservedEnvNames {
    private val exact =
        setOf(
            "PATH",
            "HOME",
            "USER",
            "SHELL",
            "LANG",
            "TZ",
            "PWD",
            "OLDPWD",
            "IFS",
            "BASH_ENV",
            "ENV",
        )
    private val prefixes = listOf("HMR_", "LD_", "JAVA_", "LC_")

    fun isReserved(name: String): Boolean {
        if (name in exact) return true
        return prefixes.any { name.startsWith(it) }
    }
}

/**
 * The LangGraph control markers the runtime sets on a resume — `HMR_LG_THREAD`,
 * `HMR_LG_RESUME`, `HMR_LG_INTERRUPT`. Every one is `HMR_`-prefixed and thus reserved,
 * so an agent cannot declare them as inputs (rejected at load) and [AgentInputResolver],
 * which emits only declared inputs, drops them. The `fire` paths merge them back over
 * the resolved declared inputs so they reach the resumed run's env; without that, every
 * resume starts a fresh thread and the graph re-parks forever. See ADR-0023.
 *
 * An explicit allowlist, not `ReservedEnvNames.isReserved`: dispatch and continuation
 * params come from a running process over the callback API, so re-admitting the whole
 * reserved set would let a caller set `PATH` / `LD_*` in the *target* agent's env — a
 * command-execution primitive across the agent boundary — or spoof `HMR_*` identity.
 * `HMR_LG_INPUT` is excluded deliberately too: it seeds a graph's fresh-run input rather
 * than steering a resume, and `AGENT.md` promises callers cannot set it. `HMR_LG_RECOVER`
 * is excluded as well: it is server-minted only by the resume route (`POST
 * /processes/{id}/resume`), never by a running process, so admitting it here would let a
 * dispatch/continuation callback-API caller steer a recover themselves.
 */
private val LANGGRAPH_CONTROL_PARAMS =
    setOf(EnvConstants.LG_THREAD, EnvConstants.LG_RESUME, EnvConstants.LG_INTERRUPT)

/** The control-marker subset of [params], as a single-valued map (last value wins per key). */
internal fun reservedControlParams(params: Parameters): Map<String, String> =
    params
        .entries()
        .filter { it.key in LANGGRAPH_CONTROL_PARAMS }
        .associate { (key, values) -> key to values.last() }

/** Map-based overload of [reservedControlParams] for internal callers that hold a plain map. */
internal fun reservedControlParams(params: Map<String, String>): Map<String, String> = params.filterKeys { it in LANGGRAPH_CONTROL_PARAMS }

/** True when [name] is reserved but not one of the [LANGGRAPH_CONTROL_PARAMS] a caller may steer a resume with. */
internal fun isNonControlReserved(name: String): Boolean = ReservedEnvNames.isReserved(name) && name !in LANGGRAPH_CONTROL_PARAMS

/**
 * Rejects [name] as the param a dispatch or continuation hands its payload to the target under
 * ([field] names it in the error): blank, already a key in [params], or a reserved name other than
 * a control marker. Rejected at registration rather than dropped at fire time: only the control
 * markers survive the [reservedControlParams] merge, so any other reserved name would start the
 * target with its payload silently missing.
 */
internal fun validatePayloadParam(
    name: String,
    field: String,
    params: Map<String, String>,
) {
    if (name.isBlank()) throw ValidationException("$field must not be blank", field)
    if (name in params) throw ValidationException("$field '$name' must not be a key in params", field)
    if (isNonControlReserved(name)) {
        throw ValidationException("$field '$name' must not target a reserved environment name", field)
    }
}
