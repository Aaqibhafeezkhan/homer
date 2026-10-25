# agentdebug — Homer Agent Debugger

A CLI tool for locally debugging Homer agents without running the full Ktor server.

## Features

- **list** — show all available agents from config
- **validate** — load and validate an agent's structure
- **show-commands** — inspect commands, inputs, and outputs
- **run** — execute commands with controlled inputs, dry-run, and live output streaming
- **hash-password** — generate a bcrypt hash for `AuthConfig.Password.users`

## Build

From the repo root:

```bash
JAVA_HOME=<path to a JDK 25> app/gradlew -p app :agentdebug:shadowJar
```

Output: `app/agentdebug/build/libs/agentdebug-0.1.0-all.jar`

Gradle uses `JAVA_HOME`, so pin it to a JDK 25 even if one is on `PATH`.
`agentdebug.sh` execs the `java` on `PATH`, which must also be JDK 25.

## Quick Start

The bundled `example/config/config.json` points `agentsSearchPath` /
`workspaceRootPath` at container paths (`/app/agents`, `/app/workspace/runs`)
meant for the Docker image, so it won't work from a plain repo checkout.
Point a local config at host paths instead. All commands below are run from
the **repo root** (the jar it builds also needs a JDK 25 `java` on `PATH` —
see [Build](#build)):

```bash
chmod +x app/agentdebug.sh

cat > /tmp/agentdebug-config.json <<'EOF'
{
  "agentsSearchPath": "example/agents",
  "workspaceRootPath": "/tmp/agentdebug-workspace",
  "databasePath": "/tmp/agentdebug-workspace/homer.db"
}
EOF
```

Agents are keyed by their `name` field, not their directory — the sample
agent in `example/agents/hello_world/agent.json` is named `hello`:

```bash
# Help
./app/agentdebug.sh --help

# List all agents
./app/agentdebug.sh --config /tmp/agentdebug-config.json list

# Validate an agent
./app/agentdebug.sh --config /tmp/agentdebug-config.json validate hello

# Inspect commands
./app/agentdebug.sh --config /tmp/agentdebug-config.json show-commands hello

# Dry run (no execution)
./app/agentdebug.sh --config /tmp/agentdebug-config.json run hello --dry-run

# Run a single command with an input (index 0 is the `script` command,
# which echoes $project_id)
echo 'project_id=demo-project' > /tmp/input.env
./app/agentdebug.sh --config /tmp/agentdebug-config.json run hello --cmd 0 --input /tmp/input.env

# Run with env overrides, keep workspace after run
echo 'GREETING_SUFFIX=team' > /tmp/extra.env
./app/agentdebug.sh --config /tmp/agentdebug-config.json --env /tmp/extra.env run hello --cmd 0 --input /tmp/input.env --keep-workspace

# Generate a bcrypt hash for AuthConfig.Password.users (reads password from stdin)
echo 'my-password' | ./app/agentdebug.sh hash-password --cost 12
```

(`hello`'s command[1] is a real `claude` call — running the full agent
without `--cmd` executes it too, which requires a logged-in `claude` CLI.)

## CLI Reference

```
agentdebug --config <config> <subcommand> [args] [options]
```

### Global Options

| Option | Required | Description |
|--------|----------|-------------|
| `--config <path>` | Yes, except for `hash-password` | Homer app config file (app.yaml or app.json) |
| `--env <path>` | No | `key=value` env file. Resolves `@SYSENV:` references in the **app config file** only (agent `env:` blocks resolve from the real process env); on `run` its values also apply as the highest-priority env override |

### Subcommands

#### `list`
List all available agents.
```bash
./app/agentdebug.sh --config /tmp/agentdebug-config.json list
```

#### `validate <agentname>`
Load and report on an agent's structure (commands, params, types).
```bash
./app/agentdebug.sh --config /tmp/agentdebug-config.json validate hello
```

#### `show-commands <agentname>`
Show all commands with their types, timeouts, outputs, and required inputs.
```bash
./app/agentdebug.sh --config /tmp/agentdebug-config.json show-commands hello
```

#### `run <agentname> [options]`
Execute agent commands locally.

| Option | Description |
|--------|-------------|
| `--input <path>` | `key=value` file with input values |
| `--dry-run` | Print what would run without executing; declared secret inputs and `@SYSENV:` values (agent `env:` and `globalEnv`) print as `***` |
| `--cmd <N>` | Run only command at index N (0-based); an out-of-range N is silently a no-op (no command runs, no error) |
| `--keep-workspace` | Don't delete the workspace after run |

(`--env` is a global option — see [Global Options](#global-options) — but its values apply as the highest-priority env override on `run`.)

`run` exits 1 if a command fails (non-zero exit without `continueIfFail`) or
a `when` predicate fails to evaluate; it exits 0 otherwise, including when
commands were skipped.

Commands with a `when:` predicate are evaluated before each real run (dry-run
only prints the predicate, never evaluates it): a false predicate prints
`[SKIP when] ...` and the run continues; an evaluation error prints
`[ERROR] when-predicate evaluation failed ...` and aborts the run.

Workspace cleanup after a real run: by default (no `--keep-workspace`) only
`workdir/` (and any leftover `.homer/`) is deleted — `artifacts/` and the workspace's project root
are left on disk either way. Omitting `--keep-workspace` forces cleanup even
if the agent's own `workspace.cleanAfterFinish: false` says otherwise. In
`--dry-run` the workspace is created but never cleaned up.

#### `hash-password [--cost N]`
Generate a bcrypt hash for `AuthConfig.Password.users`. Reads the
password from stdin; `--cost` sets the bcrypt cost (4..15, default 12).
Does not require `--config`. On a TTY it prompts `Password:` / `Confirm:`
(no echo) and aborts with exit 2 if they don't match or are empty; prompts
and errors go to stderr, and the hash itself is the only thing written to
stdout, so `agentdebug hash-password > hash.txt` works either way.
```bash
echo 'my-password' | ./app/agentdebug.sh hash-password --cost 12
```

## Input/Env File Format

Both `--env` and `--input` accept `key=value` files (dotenv-style; blank
lines and `#` comments are ignored, and a value may be double-quoted):

```
SERVICE_NAME=my-service
DEPLOY_ENV=staging
```

## Environment Variable Merge Order

Variables are merged in this priority order (later overrides earlier):

1. Allowlisted host env vars (`parentEnvAllowlist` in the app config,
   default `PATH, HOME, LANG, TZ`) — only the keys present on the host running
   `agentdebug` are included
2. `globalEnv` from app config (with `@SYSENV:VAR` resolved)
3. Agent's `env` block (with `@SYSENV:VAR` resolved) — this also carries
   `HMR_AGENT_DIR`, the agent's own config directory, injected into `env` when
   the agent is loaded (see [Architecture](#architecture))
4. `--input` file values (input values)
5. `HMR_*` injected variables:
   - `HMR_SERVER_ID = "agentdebug"`
   - `HMR_PROCESS_ID = "0"`
   - `HMR_INVOCATION_CHAIN = ""` (always empty — agentdebug never chains sub-agent runs)
   - `HMR_AGENT_NAME = <agent name>`
   - `HMR_WORK_DIR = <workspace>/workdir`
   - `HMR_ARTIFACTS_DIR = <workspace>/artifacts`
6. `--env` file values (highest priority)

## Output Format

Agent-loading logs (`[main] INFO agentdebug - ...`) go to stderr; append
`2>/dev/null` to see only the output shown below.

### list
Against the Quick Start config:
```
Available agents (11):
  body-input           - Demonstrates inputs sourced from JSON request body (1 command)
  header-input         - Demonstrates inputs sourced from request headers (1 command)
  hello                - A sample greeting agent that demonstrates commands and query parameters (2 commands)
  jsonpath             (1 command)
  langgraph_demo       - Demonstrates the !<langgraph> command launching the Python homer_langgraph runtime (1 command)
  langgraph_demo_check - Check sub-agent the langgraph_demo graph fans out to; always reports ok (1 command)
  mcp-greet            - Demonstrates exposing an agent as an MCP tool on /mcp (1 command)
  quickstart           - The Quick start agent: no LLM credentials, one operator question, and a
result that follows the answer.
 (5 commands)
  result               (5 commands)
  sleep                (1 command)
  when-example         - Demonstrates the `when:` predicate. Each command runs only if its predicate
evaluates to true. Predicates use bash test syntax; exit 0 = true, 1 = false,
≥2 = eval error (fails the agent). Per-expression timeout is 5 s.
 (4 commands)
```

Agents are listed by `name`; one without a `description` shows only the
command count, a multi-line `description` is printed as written, and an agent
with a `cron` schedule gets `, cron: <expression>` after its command count.

### validate

Success case (`validate hello` against the sample config):
```
Loading config: /tmp/agentdebug-config.json
Agents path: example/agents
Agent: hello

✓ Loaded successfully
✓ Name: hello
✓ Commands: 2 (non-empty)
✓ Command[0] script: valid
✓ Command[1] claude: valid
✓ Inputs: 1 params
  project_id (STR, required) -> env: project_id
✓ Schema: valid
Validation passed.
```

If the agent's structure fails the bundled JSON-schema validation instead
(the same schema the server and `SchemaValidationTest` enforce), the tail
looks like this instead — the exact error list depends on the violation:
```
✗ Schema validation failed:
  - must be valid to one and only one schema, but 0 are valid
  - must have a minimum value of 0
  ... (remaining oneOf-branch errors for the other command types)
Validation FAILED.
```

### run (live)

Real run of the bundled `example/agents/result/agent.yaml` (no `--dry-run`),
which demonstrates `[ENV ]` lines printed for `resultEnvParam` captures and
`continueIfFail` letting a non-zero exit continue to the next command
(durations will vary run to run):
```
[INFO] Agent: result (5 commands)
[INFO] Workspace: /tmp/agentdebug-workspace/0
[INFO] Env: 0 global + 1 agent + 0 input = 9 vars
─────────────────────────────────────────────────────
[CMD 0/4] script "script"  timeout=60s
[OUT] {"a": [1,2,3,4,5,6]}
[CMD 0] exit=1  duration=0.019s
[ENV ] VALUE_JSON=4
[ENV ] RESULT_CODEX=1
─────────────────────────────────────────────────────
[CMD 1/4] script "script"  timeout=60s
[OUT] 4
[CMD 1] exit=0  duration=0.012s
─────────────────────────────────────────────────────
[CMD 2/4] cmd "cmd"  timeout=60s
[OUT] TEST123
[CMD 2] exit=0  duration=0.007s
[ENV ] VALUE_X=TEST123
─────────────────────────────────────────────────────
[CMD 3/4] script "script"  timeout=60s
[OUT] 1
[CMD 3] exit=0  duration=0.01s
─────────────────────────────────────────────────────
[CMD 4/4] script "script"  timeout=60s
[OUT] TEST123
[CMD 4] exit=0  duration=0.01s
─────────────────────────────────────────────────────
[INFO] Done: 5 executed, 0 skipped
```

The `[INFO] Env: ...` counts are input counts only (`global`/`agent`/`input`
tally `globalEnv`/agent `env`/`--input` file entries) — the total is the
size of the fully merged env map, which also includes the allowlisted host
vars and `HMR_*` injected vars from the [merge order](#environment-variable-merge-order)
above, plus any `--env` overrides, so it never equals the sum of the three
counts. For example, with `globalEnv` set to 2 entries: `[INFO] Env: 2 global + 1 agent + 1 input = 12 vars`.

Sub-agent, LangGraph, and RunnerCmd commands (`type: agent` / `langgraph` /
`runnerCmd`) are always skipped in a real run, e.g.:
```
[CMD 0/0] langgraph "langgraph"  timeout=120s
[SKIP] LangGraph commands need server-side dispatch/continuation; not executed in debug mode
```

### run --dry-run

Real dry-run of the bundled `hello` agent — every command is prefixed
by the same 53-`─` separator and `[CMD i/N]` header used in a live run, with
`timeout=Ns` included:
```
[INFO] Agent: hello (2 commands)
[INFO] Workspace: /tmp/agentdebug-workspace/0
[INFO] Env: 0 global + 1 agent + 0 input = 9 vars
─────────────────────────────────────────────────────
[CMD 0/1] script "script"  timeout=60s
[DRY ] shell: sh
[DRY ] stdin:
[DRY ]   echo $project_id
─────────────────────────────────────────────────────
[CMD 1/1] claude "claude"  timeout=60s
[DRY ] cmd: claude --model haiku --max-turns 40 --output-format stream-json --print --verbose
[DRY ] stdin:
[DRY ]   Say a short, friendly one-line hello to project {{project_id}}.
```

A command with a `when:` predicate additionally prints a `[WHEN <op>]` line
before its `[DRY ]` output (from `example/agents/when-example/agent.yaml`):
```
[CMD 2/3] script "all-of"  timeout=60s
[WHEN all] [ -n "$region" ] ; [[ "$region" == eu-* ]]
[DRY ] shell: sh
[DRY ] stdin:
[DRY ]   echo "deploying to EU region $region"
```

A `!<claude>` or `!<codex>` step with `escalate` tiers additionally prints one
`[ESCALATE n]` line per tier after its `[DRY ]` output: the tier's model, the
argv that tier would run (built through the harness, like the step's own) and
its predicate, redacted like `[WHEN]`:
```
[CMD 1/3] claude "claude"  timeout=5400s
[DRY ] cmd: claude --model gpt-5.6-luna --max-turns 300 --continue --output-format stream-json --print --verbose
[DRY ] stdin:
[DRY ]   <langfuse: Developer agent prompt>
[ESCALATE 1] model=claude-opus-5-5[1m] cmd=claude --model claude-opus-5-5[1m] --max-turns 300 --output-format stream-json --print --verbose when=any([ "$HMR_LLM_OUTCOME" != success ])
```

## Architecture

The `agentdebug` module depends on `:app` and reuses all production executor classes unchanged:
- `CmdExecutor`, `ScriptExecutor`, `LlmHarnessExecutor` (+ `ClaudeHarness` · `CodexHarness`) — run as-is (codex commands execute for real, same as claude)
- `CommandExecutorFactory` — wired with debug stubs
- `Workspace`, `ShellExecutor`, `WorkspaceConfigFactory` — used directly
- `ResultToEnvMapper`, `TextCompiler`, `SysEnvResolver` — used directly

Five thin stubs replace production services:
- `DebugFileOperations` — delegates to `kotlin.io.path` extensions
- `DebugLangfuseClient` — uses `TextCompiler` for templates; warns on Langfuse fetches
- `SkipRunAgentExecutor` / `SkipLangGraphExecutor` / `SkipRunnerCmdExecutor` —
  placeholders that satisfy `CommandExecutorFactory`; the runner prints `[SKIP]`
  for these command types before any executor is called

`ProcessManager` and `CostMeter` are wired against a throwaway temp SQLite
database (created fresh per run, deleted on exit) purely to satisfy the
`CommandExecutorFactory` constructor — the debug run's process id stays `-1`
and agent name stays `""`, so nothing meaningful is ever read back from it.

## Running Inside Docker

Images built with the `HOMER_DEBUG=1` **build argument** ship an `agentdebug`
wrapper that defaults `--config` to `$CONFIG_PATH`:

```bash
docker exec homer-backend agentdebug run my_agent --dry-run
```

A `docker exec -e VAR=...` value reaches commands only if `VAR` is on
`parentEnvAllowlist` or referenced via `@SYSENV:`. Setup and more examples:
[DOCKER.md § agentdebug in Docker](../../DOCKER.md#agentdebug-in-docker).

## Limitations

- `type: agent` (sub-agent), `type: langgraph`, and `type: runnerCmd` commands are always skipped
- Langfuse prompts are not available (placeholder text is used)
- No lock management or parallel runs control
- Authentication not required (running locally)
- Telemetry exports to the local log only (no Langfuse); declared `secret: true` inputs and `@SYSENV:` values (agent `env:` and `globalEnv`) are still redacted in spans, log lines and `--dry-run` output
- The questions tooling is not available: no `homer-ask` helper on `PATH`, and `HMR_CALLBACK_TOKEN` / `HMR_MCP_CONFIG` are unset
