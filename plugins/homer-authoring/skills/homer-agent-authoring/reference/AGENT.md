# Agent Format Reference

This document provides comprehensive documentation for creating Homer agents.

## Introduction

Agents are the fundamental execution units in Homer. An agent defines:
- A sequence of commands to execute (shell commands, scripts, Claude AI interactions)
- Input parameters with validation and transformation
- Workspace configuration and cleanup
- Lock strategies for concurrency control
- Environment variable management

**Security Model**: Agents execute with the permissions configured by the user. Homer does not impose security restrictions — it executes commands as defined in agent files. Users are responsible for defining appropriate permissions and validating agent configurations.

### Agent Discovery

Homer discovers agents by walking the directory specified in `agentsSearchPath` (configured in `AppConfig`). **Any** subdirectory in that tree holding one of:
- `agent.json`
- `agent.yaml`

is an agent, **including a directory nested inside another agent's directory**. The `agentsSearchPath` root itself is never an agent, and a definition file that is a symlink is not read. If both files exist in one directory, `agent.json` is used.

The two formats differ on unknown keys: `agent.json` ignores them (a misspelled field is silently dropped), while `agent.yaml` is strict — an unknown key is a load error and the agent is skipped.

Each agent file defines exactly one agent, identified by the `name` field. The walk descends at most 8 levels below `agentsSearchPath` and visits at most 1000 directories within any one top-level directory's subtree (10 000 across the whole catalog) — files, hidden entries and symlinks do not count, and the directories kept under a cap are the first by name, except when a raw-entry budget binds, in which case the entries read before the cut follow filesystem order — and reads at most 200 000 entries (files and skipped entries included) within any one top-level directory's subtree (2 000 000 across the whole catalog); it skips hidden directories (`.git`, `.venv`, …) and never follows a symlinked directory. A walk that hits any of these caps logs a warning.

Nesting is layout only. Agent names stay one flat global namespace: `name` must be unique across the whole tree, and the same name declared by two directories fails **all** of them (never a silent winner). A nested agent's directory is its own `HMR_AGENT_DIR`, and it is loaded and run independently of its parent — a parent that fails to parse does not take its children down. See [ADR-0068](../docs/adr/0068-nested-agent-discovery.md).

A multi-agent pipeline therefore lives in one tree:

```
agents/web-pipeline/agent.yaml             orchestrator
agents/web-pipeline/nodes/analysis/agent.yaml
agents/web-pipeline/nodes/sources/agent.yaml
```

## Agent Data Model

Agent structure showing the most common fields (see [Agent Fields](#agent-fields) for all of them):

```yaml
name: example-agent
description: Example agent demonstrating all features
group: platform              # Optional label; the console groups agents by it (default: ungrouped)

commands:
  - !<cmd>
    cmd: "echo 'Hello'"

inputs:
  - paramName: input
    envName: INPUT
    src: query
    type: string
    required: true

env:
  LOG_LEVEL: "info"

workspace:
  path: "{{HMR_PROCESS_ID}}"
  cleanAfterFinish: true   # Deletes workdir/ and .homer/ after the run, unless a sibling still uses the path (default: true)
  purgeAfter: 5d           # Deletes entire workspace + removes process record after duration (default: 5d)

lockStrategy:
  key: "example-{{INPUT}}"
  onCollision: WAIT

mcp:
  enabled: true            # Expose this agent as an MCP tool on /mcp (default: false)
```

<details>
<summary>JSON format</summary>

```json
{
  "name": "example-agent",
  "description": "Example agent demonstrating all features",
  "group": "platform",
  "commands": [
    {
      "type": "cmd",
      "cmd": "echo 'Hello'"
    }
  ],
  "inputs": [
    {
      "paramName": "input",
      "envName": "INPUT",
      "src": "query",
      "type": "string",
      "required": true
    }
  ],
  "env": {
    "LOG_LEVEL": "info"
  },
  "workspace": {
    "path": "{{HMR_PROCESS_ID}}",
    "cleanAfterFinish": true,
    "purgeAfter": "5d"
  },
  "lockStrategy": {
    "key": "example-{{INPUT}}",
    "onCollision": "WAIT"
  },
  "mcp": {
    "enabled": true
  }
}
```
</details>

### Agent Fields

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `name` | `String` | Yes | - | Unique agent identifier; must match `^[a-zA-Z0-9_-]+$` (letters, digits, `_`, `-`) |
| `description` | `String?` | No | `null` | Human-readable description |
| `group` | `String?` | No | `null` | Optional operator-facing label for this agent. The console's Agents page renders an "All" tab plus one tab per distinct group (sorted by name), so a large catalog can be browsed by area; an ungrouped agent appears under "All" only. Must be non-blank, at most 64 characters, and free of control characters; a violation fails agent load. Returned by `GET /api/v1/agents` and, read from the current definition, by the process API. See [Agent Fields](#agent-fields). |
| `commands` | `List<Command>` | Yes | - | Sequential commands to execute. An empty list fails agent load (the agent is skipped). |
| `inputs` | `List<Param>` | No | `null` | Input parameter definitions (query, body, headers). Setting both `inputs` and `queryParams` fails agent load. |
| `queryParams` | `List<Param>` | No | `null` | **Deprecated alias for `inputs`.** Kept for backward compatibility. Mutually exclusive with `inputs`; setting both fails agent load. |
| `env` | `Map<String, String>` | No | `{}` | Agent-specific environment variables |
| `workspace` | `Workspace` | No | Default workspace | Workspace configuration |
| `lockStrategy` | `LockStrategy` | No | Default lock strategy | Concurrency control strategy |
| `maxParallelRuns` | `Int` | No | `10` | Maximum number of concurrent executions of this agent |
| `onMaxParallelRuns` | `OnMaxParallelRunsStrategy` | No | `WAIT` | Behavior when `maxParallelRuns` is reached: `WAIT` (queue) or `REJECT` (fail immediately) |
| `maxQueuedRuns` | `Int?` | No | `null` | Under `onMaxParallelRuns: WAIT`, the maximum number of runs that may be queued waiting for a slot. `null` (default) = unbounded, a surplus run waits indefinitely; a non-null value fails the surplus run once the queue is full (the start returns `200`; the run ends `FAILED`), bounding how many process rows one agent can pin. Must be `>= 0`. No effect under `REJECT`. |
| `cron` | `CronConfig` | No | `null` | Cron schedule configuration |
| `runner` | `String?` | No | `null` (= `local`) | Alias of the execution backend to run commands on. Omitted = `local` (Homer's host); any other value must match a key in [`AppConfig.runners`](SERVER.md#runners-pluggable-execution-backends); a blank value fails agent load. All commands in the agent run inside the same runner session. Mutually exclusive with `runnerTags`. See [Runner selection](#runner-selection). |
| `runnerTags` | `List<String>` | No | `[]` | Select a runner by tags instead of alias: any configured runner whose `tags` contain every listed tag may take the run. Shape `^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$`, at most 32, no duplicates. Mutually exclusive with `runner`; setting both fails agent load. |
| `runnerClaimTimeoutSec` | `Int?` | No | `null` (= server default `300`) | Seconds a run waits in the [claim queue](SERVER.md#runner-claim-queue) for a runner with a free permit before it fails. Must be `>= 1` when set. Applies to the run's own claim; a `runnerCmd` step waits under `server.runnerClaimTimeoutSec`. |
| `inputSchema` | `object` | No | `null` | JSON Schema (Draft 2020-12) validating the JSON request body of `POST /api/v1/agent/{name}`. A non-conforming payload is rejected with `422` before any process is spawned; a malformed schema skips the agent at load. See [Input Schema](#input-schema-payload-validation). |
| `mcp` | `AgentMcpConfig` | No | `null` | Opt-in exposure of this agent as an MCP tool on the `/mcp` endpoint (requires `server.mcp.enabled`). `mcp: { enabled: true }` exposes the agent; omitted or `enabled: false` hides it. See [MCP Exposure](#mcp-exposure). |
| `dispatch` | `AgentDispatchConfig` | No | `null` | Opt-in: allow this agent to be a dispatch target (`ask-and-dispatch` **or** `homer-dispatch`). `dispatch: { enabled: true }` permits it; omitted or `enabled: false` rejects the dispatch with `403` (`404`, identical to an unknown agent, when the run's hop may not reach the target at all). See [Ask and dispatch](#ask-and-dispatch) and [Dispatching another agent](#dispatching-another-agent). |
| `cooldown` | `Cooldown` | No | `null` | Minimum spacing between runs of this agent. A start inside the window is refused with `429`. See [Cooldown](#cooldown). |
| `telemetry` | `AgentTelemetry` | No | `{}` | What this agent's runs send to the OTel collector: whether they are exported at all, and which inputs become trace tags. See [Telemetry](#telemetry). |
| `tags` | `Tags` | No | `{}` | Subject tags a run carries — `key:value` strings describing what it ran over, so operators can find runs by their subject (`?tag=mr:15`) and see them as chips in the console. `fromInputs` resolves tags from declared inputs at start; `runtime` allows the agent to declare more mid-run via `homer-tag`. See [Run tags](#run-tags). |
| `artifactNameTemplate` | `String` | No | `"cmd_{{HMR_CMD_INDEX}}"` | Template for the *stem* of the autogenerated per-command stdout/stderr artifact files (Homer appends `.out`/`.err`). Rendered per command and per attempt against the workspace env (secret-classified variables excluded — see [Template-only Variables](#template-only-variables)) plus the template-only `HMR_CMD_INDEX` / `HMR_CMD_ATTEMPT` variables. A rendered name that would still contain a secret value fails the command. The default renders identically on every attempt, so a command's artifacts truncate in place exactly as before this field existed. A template that omits `{{HMR_CMD_INDEX}}` renders the same stem for every command in the agent, so every command shares one `.out`/`.err` pair and each later command truncates the one before it. |

### Runner selection

Every run waits in one claim queue until a runner with a free permit takes it
(see [Runner claim queue](SERVER.md#runner-claim-queue)). An agent chooses the
runner in exactly one of two ways, or in neither (then `local` serves it):

```yaml
name: gpu-job
runnerTags: [linux, gpu]      # any runner serving both tags may take the run
runnerClaimTimeoutSec: 600    # optional; default server.runnerClaimTimeoutSec
commands:
  - !<cmd>
    cmd: nvidia-smi
```

<details>
<summary>JSON format</summary>

```json
{
  "name": "gpu-job",
  "runnerTags": ["linux", "gpu"],
  "runnerClaimTimeoutSec": 600,
  "commands": [
    { "type": "cmd", "cmd": "nvidia-smi" }
  ]
}
```
</details>

`runner: build-host` addresses one runner by alias, as before:

```yaml
name: build-job
runner: build-host
commands:
  - !<cmd>
    cmd: make
```

<details>
<summary>JSON format</summary>

```json
{
  "name": "build-job",
  "runner": "build-host",
  "commands": [
    { "type": "cmd", "cmd": "make" }
  ]
}
```
</details>

Setting both
`runner` and `runnerTags` fails agent load with
`runner and runnerTags are mutually exclusive`. An agent whose `runnerTags` no
configured runner serves still loads (with a warning); its runs wait for the
claim TTL and then fail naming the unmatched tags.

### Sharing steps between agents

There is no `include`, `extends`, or step-template mechanism, and there is not
going to be one: an `agent.yaml` is deliberately self-contained, so what you
read is what runs — for the schema validator, the console editor, `agentdebug`,
and the authoring plugin alike. YAML anchors are rejected by the parser and
would only work within one file regardless.

To share logic across a fleet, in rough order of preference: **generate** the
definitions from templates in your own repo and commit the rendered files
(Homer does not care who wrote them, and this removes the duplication
completely); **share the shell** by putting scripts in the runtime image or
using runner `extraUploads`, called from a one-line `!<cmd>`; or **make an
independent block its own agent** where it is substantial enough to be worth a
dispatch. The reasoning and the tradeoffs are in
[ADR-0049](../docs/adr/0049-agent-definitions-stay-self-contained.md).

> **Note on `writable`:** `GET /api/v1/agents` exposes a per-agent
> `writable: Boolean` field. This is a *runtime property* reflecting
> whether the agent's directory on disk is writable (via
> `Files.isWritable`) — it is **not** part of the on-disk agent
> schema and cannot be set in `agent.yaml` / `agent.json`. The
> console uses it to gate the edit UI; see
> [ADR-0004](../docs/adr/0004-agent-file-editing-api.md).

## Command Types

Homer supports eight command types: `Cmd` (shell command), `Script` (shell script), `Claude` (Claude AI execution), `Codex` (OpenAI Codex CLI execution), `Opencode` (opencode CLI execution), `RunAgent` (invoke another agent), `LangGraph` (LangGraph runtime launcher), and `RunnerCmd` (run a command on another runner).

> **No `{{VAR}}` templating in `!<cmd>` `cmd` / `!<script>` `content`.** Both are passed to the process verbatim. Homer substitutes `{{VAR}}` only in Claude/Codex/opencode prompts, options and `cmd` (the CLI binary), `!<agent>` `agentName`, workspace paths, lock keys and artifact names. Commands read variables from the environment: in a `!<script>` use `"$VAR"`; a `!<cmd>` runs without a shell, so it cannot expand variables — use a `!<script>` instead.

### Command.Cmd - Shell Command Execution

Executes a command directly via `ProcessBuilder` — **no shell is invoked**, so `$VAR`, pipes, redirection, globbing and command substitution do not work. Use `Command.Script` when you need shell features.

`cmd` accepts two forms:

- **Scalar string** — tokenized with POSIX-style quote handling (single quotes, double quotes, backslash escapes). Convenient for simple commands.
- **List of strings** — passed to `ProcessBuilder` unchanged. Use this when any argument contains spaces or quotes.

```yaml
commands:
  - !<cmd>
    cmd: "ls -la /srv/data"
    timeout: 30
    continueIfFail: false
    resultEnvParam:
      - src: STD_OUT
        envName: FILE_LIST
        transformer: !<pass> {}

  # Equivalent list form — required if any argument contains a space or quote
  - !<cmd>
    cmd: ["curl", "-H", "Content-Type: application/json", "https://api.example.com/health"]
```

<details>
<summary>JSON format</summary>

```json
{
  "commands": [
    {
      "type": "cmd",
      "cmd": "ls -la /srv/data",
      "timeout": 30,
      "continueIfFail": false,
      "resultEnvParam": [
        {
          "src": "STD_OUT",
          "envName": "FILE_LIST",
          "transformer": {
            "type": "pass"
          }
        }
      ]
    }
  ]
}
```
</details>

#### Cmd Fields

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `cmd` | `String` \| `List<String>` | Yes | - | Command argv. Scalar string is tokenized with POSIX-style quote handling; list is passed through unchanged. Must be non-empty. |
| `label` | `String` | No | `"cmd"` | Human-readable label shown in the console execution list |
| `timeout` | `Long` | No | `60` (60 sec) | Command timeout in seconds |
| `continueIfFail` | `Boolean` | No | `false` | Continue when the command exits non-zero. Timeouts, output-cap kills and other thrown errors fail the agent regardless, even with `continueIfFail`. |
| `finishExitCode` | `Int` | No | `null` | Exit code that causes the agent to finish early (successfully) instead of failing |
| `resultEnvParam` | `List<ResultEnvParam>` | No | `[]` | Extract command outputs to environment variables |
| `when` | `When` | No | `null` | Conditional execution predicate. See [Conditional execution (`when`)](#conditional-execution-when). |

#### Examples

**Simple command:**
```yaml
- !<cmd>
  cmd: "echo 'Starting process'"
```

**Command with custom label (shown in console):**
```yaml
- !<cmd>
  label: "Fetch user data"
  cmd: "curl -s https://api.example.com/users"
- !<cmd>
  cmd: "echo done"  # label defaults to "cmd"
```

**Reading variables (needs a `!<script>` — a `!<cmd>` cannot expand them):**
```yaml
- !<script>
  content: curl -s "$API_ENDPOINT/users/$USER_ID"
```

**Command with result extraction:**
```yaml
- !<cmd>
  cmd: "git rev-parse HEAD"
  resultEnvParam:
    - src: STD_OUT
      envName: GIT_COMMIT
      transformer: !<pass> {}
```

**Command with custom timeout:**
```yaml
- !<cmd>
  cmd: "npm install"
  timeout: 300  # 5 minutes
```

**Command that allows failure (a glob needs a shell, so this is a `!<script>`):**
```yaml
- !<script>
  content: rm -f /tmp/cache/*
  continueIfFail: true
```

### Command.Script - Shell Script Execution

Executes a multi-line shell script:

```yaml
commands:
  - !<script>
    content: |
      #!/bin/bash
      set -e
      echo "Processing $INPUT_FILE"
      cat "$INPUT_FILE" | jq '.data[] | .id' > output.txt
      echo "Done"
    shell: bash
    timeout: 60
    continueIfFail: false
    resultEnvParam:
      - src: STD_OUT
        envName: SCRIPT_OUTPUT
        transformer: !<pass> {}
```

<details>
<summary>JSON format</summary>

```json
{
  "commands": [
    {
      "type": "script",
      "content": "#!/bin/bash\nset -e\necho \"Processing $INPUT_FILE\"\ncat \"$INPUT_FILE\" | jq '.data[] | .id' > output.txt\necho \"Done\"",
      "shell": "bash",
      "timeout": 60,
      "continueIfFail": false,
      "resultEnvParam": [
        {
          "src": "STD_OUT",
          "envName": "SCRIPT_OUTPUT",
          "transformer": {
            "type": "pass"
          }
        }
      ]
    }
  ]
}
```
</details>

#### Script Fields

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `content` | `String` | Yes | - | Script content (multi-line supported) |
| `shell` | `String` | No | `sh` | Interpreter on the runner's PATH; content is piped to `<shell> -s -`, so a shebang line is ignored |
| `label` | `String` | No | `"script"` | Human-readable label shown in the console execution list |
| `timeout` | `Long` | No | `60` (60 sec) | Script timeout in seconds |
| `continueIfFail` | `Boolean` | No | `false` | Continue when the command exits non-zero. Timeouts, output-cap kills and other thrown errors fail the agent regardless, even with `continueIfFail`. |
| `finishExitCode` | `Int` | No | `null` | Exit code that causes the agent to finish early (successfully) instead of failing |
| `resultEnvParam` | `List<ResultEnvParam>` | No | `[]` | Extract script outputs to environment variables |
| `when` | `When` | No | `null` | Conditional execution predicate. See [Conditional execution (`when`)](#conditional-execution-when). |

#### Common shell values

Any interpreter accepting `-s -` works; an unavailable one fails the agent at run time.

| Value | Description |
|-------|-------------|
| `sh` | POSIX shell (default, most compatible) |
| `bash` | Bash shell (supports advanced features) |
| `zsh` | Zsh shell |

#### Examples

**Simple script:**
```yaml
- !<script>
  content: |
    echo "Hello, $USER_NAME"
    date
```

**Bash script with error handling:**
```yaml
- !<script>
  content: |
    #!/bin/bash
    set -euo pipefail

    if [ ! -f "$INPUT_FILE" ]; then
      echo "File not found" >&2
      exit 1
    fi

    cat "$INPUT_FILE" | jq '.'
  shell: bash
```

**Script with result extraction:**
```yaml
- !<script>
  content: |
    #!/bin/bash
    curl -s "https://api.github.com/users/$USERNAME" | jq -r '.name'
  shell: bash
  resultEnvParam:
    - src: STD_OUT
      envName: GITHUB_NAME
      transformer: !<pass> {}
```

### Command.Claude - Claude AI Execution

Executes Claude Code CLI with prompts and options:

```yaml
commands:
  - !<claude>
    cmd: "claude"
    prompt: !<langfuse>
      promptName: "code-review-prompt"
      label: "production"
    options:
      model: sonnet
      agents:
        code-reviewer:
          prompt: "You review pull requests for clarity, bugs, and style."
      allowedTools:
        - Read
        - Grep
    timeout: 600
    continueIfFail: false
    resultEnvParam:
      - src: STD_OUT
        envName: REVIEW_RESULT
        transformer: !<pass> {}
```

<details>
<summary>JSON format</summary>

```json
{
  "commands": [
    {
      "type": "claude",
      "cmd": "claude",
      "prompt": {
        "type": "langfuse",
        "promptName": "code-review-prompt",
        "label": "production"
      },
      "options": {
        "model": "sonnet",
        "agents": {
          "code-reviewer": {
            "prompt": "You review pull requests for clarity, bugs, and style."
          }
        },
        "allowedTools": ["Read", "Grep"]
      },
      "timeout": 600,
      "continueIfFail": false,
      "resultEnvParam": [
        {
          "src": "STD_OUT",
          "envName": "REVIEW_RESULT",
          "transformer": {
            "type": "pass"
          }
        }
      ]
    }
  ]
}
```
</details>

#### Claude Fields

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `cmd` | `String` | No | `"claude"` | Claude CLI command. `{{VAR}}` is substituted; a reference to an input's `envName` fails the agent at load |
| `prompt` | `LlmPrompt` | Yes | - | Prompt configuration (inline or Langfuse) |
| `options` | `ClaudeCmdOptions` | Yes | - | Claude CLI options |
| `retry` | `LlmRetry` | No | `null` | Retry policy for transient LLM failures (see below) |
| `label` | `String` | No | `"claude"` | Human-readable label shown in the console execution list |
| `timeout` | `Long` | No | `60` (60 sec) | Command timeout in seconds |
| `continueIfFail` | `Boolean` | No | `false` | Continue when the command exits non-zero. Timeouts, output-cap kills and other thrown errors fail the agent regardless, even with `continueIfFail`. |
| `finishExitCode` | `Int` | No | `null` | Exit code that causes the agent to finish early (successfully) instead of failing |
| `resultEnvParam` | `List<ResultEnvParam>` | No | `[]` | Extract Claude outputs to environment variables |
| `when` | `When` | No | `null` | Conditional execution predicate. See [Conditional execution (`when`)](#conditional-execution-when). |
| `escalate` | `List<LlmEscalation>` | No | `[]` | Stronger models to rerun the step on when a predicate judges the previous outcome bad. Empty runs the step once, exactly as without the field. See [Claude Escalation](#claude-escalation). |

#### Claude Retry

The optional `retry` block wraps the entire CLI invocation (stdin, env, and stdout validation). Retries fire when an attempt finishes with a non-zero exit code OR when its stdout fails JSON-event-stream validation (`MalformedLlmOutputException`). Other exceptions (timeouts, cancellation) propagate immediately. Backoff is exponential with full jitter: `min(maxDelayMs, baseDelayMs * 2^(n-1) + random(0, baseDelayMs))` for the n-th retry (n = 1 for the first). With the default `attempts: 1` no retry happens.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `attempts` | `Int` | No | `1` | Total attempts including the first; `1` = no retry |
| `baseDelayMs` | `Long` | No | `1000` | Base delay in ms; doubles on each subsequent attempt |
| `maxDelayMs` | `Long` | No | `30000` | Hard cap on the per-attempt backoff delay; must be >= `baseDelayMs` (rejected at load otherwise) |

```yaml
- !<claude>
  prompt: !<prompt>
    prompt: "Summarize {{INPUT}}"
  options: {}
  retry:
    attempts: 3
    baseDelayMs: 2000
    maxDelayMs: 20000
```

#### Claude Escalation

The optional `escalate` list runs the step on a cheaper model first and reruns it on a stronger one only when the outcome is bad. Each entry is a tier, tried in order. After a tier finishes (its `retry` policy included), the **next** tier's `when` is evaluated like a command's `when` (bash `test` expressions, same 5 s timeout and eval-error rule, workspace directory, the step's env) with two extra variables describing the tier that just ran:

| Variable | Value |
|----------|-------|
| `HMR_LLM_OUTCOME` | `success`, `failed` (non-zero exit after retries), `capped` (stopped at `maxTurns` with `maxTurnsExitCode` set) or `malformed` (output the harness could not use) |
| `HMR_LLM_EXIT_CODE` | That tier's exit code; `-1` for `malformed` |

True runs the tier: the same compiled prompt with the step's options, except `model` and — when the tier sets it — the raw CLI `options` list. Whatever the previous tier left in the workspace stays. False stops escalating and the step returns what it has.

- The last tier that ran is the step's result: `resultEnvParam`, `continueIfFail` and `finishExitCode` apply to it.
- A `malformed` outcome that no tier takes over fails the step exactly as without `escalate`. Timeouts and cancellations are never escalated.
- `timeout` and `retry` apply to each tier separately, so a step can run up to (tiers + 1) × `retry.attempts` times its timeout, plus backoff delays. At most 3 tiers.
- Every attempt of every tier is cost-accounted; every tier is also sent to Langfuse except a `malformed` one the next tier takes over, which is accounted only. Tiers continue `HMR_CMD_ATTEMPT`, so each writes its own attempt artifacts when `artifactNameTemplate` uses it.
- Without `escalate` nothing changes: no predicate runs and the step executes once.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `model` | `String` | Yes | - | Model for this tier. `{{VAR}}` is substituted. |
| `options` | `List<String>` | No | `null` | Replaces the step's raw `options` list for this tier, e.g. `[]` so a step that `--continue`s starts the stronger model in a fresh session. `null` keeps the step's list. |
| `when` | `When` | Yes | - | Predicate judging the previous tier's outcome; true runs this tier. |

```yaml
- !<claude>
  prompt: !<prompt>
    prompt: "Fix the failing test in {{HMR_WORK_DIR}}/repo and write done.txt when it passes"
  options:
    model: "{{CHEAP_MODEL}}"
    maxTurns: 100
    options: ["--continue"]
  escalate:
    - model: "claude-opus-5-5[1m]"
      options: []
      when:
        any:
          - '[ "$HMR_LLM_OUTCOME" != success ]'
          - '[ ! -f done.txt ]'
```

<details>
<summary>JSON format</summary>

```json
{
  "type": "claude",
  "prompt": {
    "type": "prompt",
    "prompt": "Fix the failing test in {{HMR_WORK_DIR}}/repo and write done.txt when it passes"
  },
  "options": {
    "model": "{{CHEAP_MODEL}}",
    "maxTurns": 100,
    "options": ["--continue"]
  },
  "escalate": [
    {
      "model": "claude-opus-5-5[1m]",
      "options": [],
      "when": {
        "any": [
          "[ \"$HMR_LLM_OUTCOME\" != success ]",
          "[ ! -f done.txt ]"
        ]
      }
    }
  ]
}
```

</details>

#### Claude CLI Command

The `cmd` field specifies the Claude CLI command (defaults to `"claude"`). This is typically the base command name, with options and arguments specified via the `options` field.

```yaml
# Default Claude command
cmd: "claude"

# Custom Claude wrapper
cmd: "my-claude-wrapper"
```

#### Claude stdout is NDJSON

Claude writes its stdout as **NDJSON** — one JSON event per line, not a single
JSON document. For `jsonPath` transformers with `src: STD_OUT`, Homer
re-assembles those lines into a JSON **array** of events before evaluating the
path, so the path sees the whole event stream as one array — the same shape the
pre-streaming `--output-format json` output had.

```yaml
# Capture the run's total cost from the result event:
resultEnvParam:
  - src: STD_OUT
    envName: CLAUDE_COST
    transformer: !<jsonPath>
      path: "$[?(@.type=='result')].total_cost_usd"
```

A filter (`$[?(...)]`) is an *indefinite* path, so JsonPath returns the match
wrapped in a JSON array — `CLAUDE_COST` above becomes `[0.42]`. If you need the
bare scalar, a `regex` transformer is the simplest option:

```yaml
resultEnvParam:
  - src: STD_OUT
    envName: CLAUDE_COST
    transformer: !<regex>
      regex: '"total_cost_usd":\s*([0-9.]+)'
      group: 1
```

To extract several fields, add one `resultEnvParam` entry per field.

Note: this is the Claude CLI's own estimate. The cost Homer charges may differ when `llmPrice.sessionSpend` is configured (see `SERVER.md → LLM price`); the charged figure is on the status API as `llmAccounting.costUsd`.

### Command.Codex - OpenAI Codex CLI Execution

Executes the OpenAI Codex CLI (`codex exec`) with the same interface shape as
`Command.Claude`: the prompt (inline or Langfuse) is passed via stdin, option
values support `{{VAR}}` placeholders, and the same `retry` policy applies.
The executor always appends `--json --color never` and reads the prompt from
stdin, so Codex writes its events to stdout as JSONL.

```yaml
commands:
  - !<codex>
    cmd: "codex"
    prompt: !<prompt>
      prompt: "Summarize the repository at {{HMR_WORK_DIR}}/repo"
    options:
      model: gpt-5.2-codex
      sandbox: read-only
      cd: "repo"
      skipGitRepoCheck: true
    timeout: 600
    continueIfFail: false
    resultEnvParam:
      - src: STD_OUT
        envName: CODEX_ANSWER
        transformer: !<jsonPath>
          path: "$[?(@.item.type=='agent_message')].item.text"
```

<details>
<summary>JSON format</summary>

```json
{
  "commands": [
    {
      "type": "codex",
      "cmd": "codex",
      "prompt": {
        "type": "prompt",
        "prompt": "Summarize the repository at {{HMR_WORK_DIR}}/repo"
      },
      "options": {
        "model": "gpt-5.2-codex",
        "sandbox": "read-only",
        "cd": "repo",
        "skipGitRepoCheck": true
      },
      "timeout": 600,
      "continueIfFail": false,
      "resultEnvParam": [
        {
          "src": "STD_OUT",
          "envName": "CODEX_ANSWER",
          "transformer": {
            "type": "jsonPath",
            "path": "$[?(@.item.type=='agent_message')].item.text"
          }
        }
      ]
    }
  ]
}
```
</details>

#### Codex Fields

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `cmd` | `String` | No | `"codex"` | Codex CLI command. `{{VAR}}` is substituted; a reference to an input's `envName` fails the agent at load |
| `prompt` | `LlmPrompt` | Yes | - | Prompt configuration (inline or Langfuse), same type as `Command.Claude` |
| `options` | `CodexCmdOptions` | No | `{}` | Codex CLI options |
| `retry` | `LlmRetry` | No | `null` | Retry policy, same semantics as `Command.Claude` |
| `label` | `String` | No | `"codex"` | Human-readable label shown in the console execution list |
| `timeout` | `Long` | No | `60` (60 sec) | Command timeout in seconds |
| `continueIfFail` | `Boolean` | No | `false` | Continue when the command exits non-zero. Timeouts, output-cap kills and other thrown errors fail the agent regardless, even with `continueIfFail`. |
| `finishExitCode` | `Int` | No | `null` | Exit code that causes the agent to finish early (successfully) instead of failing |
| `resultEnvParam` | `List<ResultEnvParam>` | No | `[]` | Extract Codex outputs to environment variables |
| `when` | `When` | No | `null` | Conditional execution predicate. See [Conditional execution (`when`)](#conditional-execution-when). |
| `escalate` | `List<LlmEscalation>` | No | `[]` | Escalation tiers, same semantics as `Command.Claude` (see [Claude Escalation](#claude-escalation)) |

#### Codex Options (CodexCmdOptions)

Each option maps 1:1 to a `codex exec` CLI flag.

| Field | Type | Default | CLI Flag | Description |
|-------|------|---------|----------|-------------|
| `model` | `String?` | `null` | `--model` | Model the agent should use (e.g. `gpt-5.2-codex`) |
| `profile` | `String?` | `null` | `--profile` | Config profile from `$CODEX_HOME/<name>.config.toml` |
| `sandbox` | `String?` | `null` | `--sandbox` | `read-only`, `workspace-write`, or `danger-full-access` |
| `cd` | `String?` | `null` | `--cd` | Working root for the agent |
| `addDir` | `String?` | `null` | `--add-dir` | Additional writable directory |
| `skipGitRepoCheck` | `Boolean` | `false` | `--skip-git-repo-check` | Allow running outside a Git repository |
| `ephemeral` | `Boolean` | `false` | `--ephemeral` | Do not persist Codex session files to disk |
| `ignoreUserConfig` | `Boolean` | `false` | `--ignore-user-config` | Do not load `$CODEX_HOME/config.toml` (auth still uses `CODEX_HOME`) |
| `ignoreRules` | `Boolean` | `false` | `--ignore-rules` | Do not load execpolicy `.rules` files |
| `outputSchema` | `String?` | `null` | `--output-schema` | JSON Schema file for the final response shape |
| `outputLastMessage` | `String?` | `null` | `--output-last-message` | File where the last agent message is written |
| `dangerouslyBypassApprovalsAndSandbox` | `Boolean` | `false` | `--dangerously-bypass-approvals-and-sandbox` | **Security: opt-in.** Skips all prompts and sandboxing; only safe in isolated environments. Ignored when `sandbox` is set. |
| `config` | `List<String>?` | `null` | `--config` (repeated) | `key=value` config overrides (TOML values) |
| `options` | `List<String>?` | `null` | - | Raw CLI arguments appended before the hardcoded tail |

Behind an OpenAI-compatible proxy, `OPENAI_BASE_URL` is ignored — define a custom
provider via `config` overrides instead, where `env_key` names the env var
holding the token:

```yaml
options:
  config:
    - model_provider=proxy
    - model_providers.proxy.name=proxy
    - model_providers.proxy.base_url=https://your-proxy.example.com/v1
    - model_providers.proxy.env_key=PROXY_API_TOKEN
    - model_providers.proxy.wire_api=responses
```

In a container, Codex's own sandbox may not work. On Linux the `read-only` and
`workspace-write` modes run each shell call under bubblewrap, which needs
unprivileged user namespaces. Docker's default security profile blocks them, and so
does Homer's Docker image run that way. Every shell call then fails with exit code 1
(`bwrap: No permissions to create a new namespace`), yet the attempt still succeeds:
the model just answers without the command's output. There, set
`sandbox: danger-full-access` and treat the container as the isolation boundary, or
run Codex where user namespaces are allowed.

#### Codex stdout is JSONL

With `--json`, Codex writes one JSON event per line: `thread.started`,
`turn.started`, `item.started/updated/completed`, and `turn.completed` (with
token `usage`) on success, or `turn.failed` on failure. Homer validates the
stream fail-closed: an attempt only counts as successful when it exits 0,
stdout parses as JSONL, a `turn.completed` event is present, and no
`turn.failed` event appears. Standalone `error` events are transient
retry/reconnect notices and are logged, not fatal. Token usage is recorded on the run
(`llmAccounting.usage`) and as `llm.usage.*` span attributes. Codex reports tokens, not
USD: with `llmPrice.sessionSpend` configured the run is priced from the spend endpoint
like a Claude run; otherwise its cost source is `none` and it does not count against
cost caps.

The final answer is the last `agent_message` item:

```yaml
resultEnvParam:
  - src: STD_OUT
    envName: CODEX_ANSWER
    transformer: !<jsonPath>
      path: "$[?(@.item.type=='agent_message')].item.text"
```

As with Claude, a filter path returns a JSON array of matches — take the last
element when the run produced several agent messages. Alternatively set
`options.outputLastMessage` to a file path and read the bare text from there in
a follow-up `script` command.

### Command.Opencode - opencode CLI Execution

Executes the opencode CLI (`opencode run`) with the same interface shape as
`Command.Claude` and `Command.Codex`: the prompt (inline or Langfuse) is passed
via stdin, option values support `{{VAR}}` placeholders, and the same `retry`
policy applies. The executor always appends `--format json`, so opencode writes
its events to stdout as JSONL.

```yaml
commands:
  - !<opencode>
    cmd: "opencode"
    prompt: !<prompt>
      prompt: "Summarize the repository at {{HMR_WORK_DIR}}/repo"
    options:
      model: anthropic/claude-sonnet-4-5
      agent: build
      dir: "repo"
    timeout: 600
    continueIfFail: false
    resultEnvParam:
      - src: STD_OUT
        envName: OPENCODE_ANSWER
        transformer: !<jsonPath>
          path: "$[?(@.type=='text')].part.text"
```

<details>
<summary>JSON format</summary>

```json
{
  "commands": [
    {
      "type": "opencode",
      "cmd": "opencode",
      "prompt": {
        "type": "prompt",
        "prompt": "Summarize the repository at {{HMR_WORK_DIR}}/repo"
      },
      "options": {
        "model": "anthropic/claude-sonnet-4-5",
        "agent": "build",
        "dir": "repo"
      },
      "timeout": 600,
      "continueIfFail": false,
      "resultEnvParam": [
        {
          "src": "STD_OUT",
          "envName": "OPENCODE_ANSWER",
          "transformer": {
            "type": "jsonPath",
            "path": "$[?(@.type=='text')].part.text"
          }
        }
      ]
    }
  ]
}
```
</details>

#### Opencode Fields

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `cmd` | `String` | No | `"opencode"` | opencode CLI command. `{{VAR}}` is substituted; a reference to an input's `envName` fails the agent at load |
| `prompt` | `LlmPrompt` | Yes | - | Prompt configuration (inline or Langfuse), same type as `Command.Claude` |
| `options` | `OpencodeCmdOptions` | No | `{}` | opencode CLI options |
| `retry` | `LlmRetry` | No | `null` | Retry policy, same semantics as `Command.Claude` |
| `label` | `String` | No | `"opencode"` | Human-readable label shown in the console execution list |
| `timeout` | `Long` | No | `60` (60 sec) | Command timeout in seconds |
| `continueIfFail` | `Boolean` | No | `false` | Continue when the command exits non-zero. Timeouts, output-cap kills and other thrown errors fail the agent regardless, even with `continueIfFail`. |
| `finishExitCode` | `Int` | No | `null` | Exit code that causes the agent to finish early (successfully) instead of failing |
| `resultEnvParam` | `List<ResultEnvParam>` | No | `[]` | Extract opencode outputs to environment variables |
| `when` | `When` | No | `null` | Conditional execution predicate. See [Conditional execution (`when`)](#conditional-execution-when). |
| `escalate` | `List<LlmEscalation>` | No | `[]` | Escalation tiers, same semantics as `Command.Claude` (see [Claude Escalation](#claude-escalation)) |

#### Opencode Options (OpencodeCmdOptions)

| Field | Type | Default | CLI Flag | Description |
|-------|------|---------|----------|-------------|
| `model` | `String?` | `null` | `--model` | Model the agent should use, as `provider/model` (e.g. `anthropic/claude-sonnet-4-5`) |
| `agent` | `String?` | `null` | `--agent` | Named agent profile to run |
| `dir` | `String?` | `null` | `--dir` | Working directory for the run |
| `file` | `List<String>?` | `null` | `--file` (repeated) | Files to attach to the prompt |
| `auto` | `Boolean` | `false` | `--auto` | **Security: opt-in.** Approves the agent's tool calls without prompting; only safe in an isolated environment. |
| `options` | `List<String>?` | `null` | - | Raw CLI arguments appended before the hardcoded tail |

#### Opencode stdout is JSON

With `--format json`, opencode writes one JSON event per line: `step_start`,
`text`, `tool_use`, `step_finish` (with token `usage` and the step's own USD
`cost`), and `error`. Homer validates the stream fail-closed: an attempt only
counts as successful when it exits 0, stdout parses as JSONL, a `step_finish`
event with a successful finish reason (`stop`, `length`, `tool-calls`,
`unknown`) is present, and no `step_finish` with any other reason appears —
`content-filter` and `error` fail the attempt, as does a reason a future CLI
adds. Standalone `error` events are transient retry/reconnect notices and are
logged, not fatal. Token usage is recorded on the run (`llmAccounting.usage`)
and as `llm.usage.*` span attributes; the reported USD cost is accumulated per
session, so the run is charged the whole conversation, not its last step.

The prompt arrives on **stdin**, not as a positional argument: the executor
never emits a message argument, because `opencode run` reads stdin when it is
given none.

The final answer is the concatenation of the run's `text` events:

```yaml
resultEnvParam:
  - src: STD_OUT
    envName: OPENCODE_ANSWER
    transformer: !<jsonPath>
      path: "$[?(@.type=='text')].part.text"
```

As with Claude and Codex, a filter path returns a JSON array of matches — take
the last element when the run produced several text events.

### Command.RunAgent - Agent Invocation

Launches another agent by name asynchronously (fire-and-forget). The resulting process id is written to stdout as `processId: <id>` and can be captured via `resultEnvParam` for use in subsequent commands. The command fails if the target agent is not found, if the run's owner may not run it (and `server.agentGrants` does not delegate the hop — see [Grants and roles](SERVER.md#grants-and-roles) and [ADR-0039](../docs/adr/0039-nested-dispatch-authority.md)), or if process creation fails. A denied target fails exactly like an unknown one: stderr `agent '<name>' not found`, exit code `255`.

Dispatch is **in-process**: Homer starts the child run directly via the same core the HTTP API uses, so it works regardless of how the current run was authenticated (bearer token, HMAC, or console session cookie). Invocation-chain cycle/depth checks still apply. See [ADR-0030](../docs/adr/0030-runagent-in-process-dispatch.md).

The child run is **linked to its parent**: its `parentProcessId` (status API) and `HMR_PARENT_PROCESS_ID` (env) carry the calling run's process id, and `GET /api/v1/status/all?parentProcessId=<id>` lists a run's children. Runs started by dispatch or continuation are linked the same way; a retry or resume keeps the source run's parent. The child also inherits the **flow root**: it carries `rootProcessId` (status API) and `HMR_ROOT_PROCESS_ID` (env), the first run of the flow the parent belongs to, and `GET /api/v1/status/all?rootProcessId=<id>` lists every run below the flow root — every descendant at every depth, the root's own row excepted — in one read. See [ADR-0058](../docs/adr/0058-parent-child-run-linkage.md).

```yaml
commands:
  - !<agent>
    agentName: deploy-service
    queryParams:
      - envName: SERVICE_NAME
        paramName: name
        transformer: !<pass> {}
      - envName: DEPLOY_CONFIG_JSON
        paramName: version
        transformer: !<jsonPath>
          path: "$.version"
    timeout: 60
    continueIfFail: false
    resultEnvParam:
      - src: STD_OUT
        envName: DEPLOY_PROCESS_ID
        transformer: !<regex>
          regex: 'processId: (\d+)'
          group: 1
```

<details>
<summary>JSON format</summary>

```json
{
  "commands": [
    {
      "type": "agent",
      "agentName": "deploy-service",
      "queryParams": [
        {
          "envName": "SERVICE_NAME",
          "paramName": "name",
          "transformer": { "type": "pass" }
        },
        {
          "envName": "DEPLOY_CONFIG_JSON",
          "paramName": "version",
          "transformer": { "type": "jsonPath", "path": "$.version" }
        }
      ],
      "timeout": 60,
      "continueIfFail": false,
      "resultEnvParam": [
        {
          "src": "STD_OUT",
          "envName": "DEPLOY_PROCESS_ID",
          "transformer": { "type": "regex", "regex": "processId: (\\d+)", "group": 1 }
        }
      ]
    }
  ]
}
```
</details>

#### RunAgent Fields

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `agentName` | `String` | Yes | - | Name of the target agent to launch |
| `queryParams` | `List<AgentCommandParam>` | No | `[]` | Environment variables to pass as query params to the child agent. **Not the same field as the agent-level `queryParams`** — see the warning under [Agent Inputs](#agent-inputs). |
| `label` | `String` | No | `"agent"` | Human-readable label shown in the console execution list |
| `timeout` | `Long` | No | `60` | **No effect for this command type.** Dispatch is in-process and returns the child `processId` immediately, so there is nothing to time out; the child's own command timeouts govern its runtime. Retained for schema compatibility. |
| `continueIfFail` | `Boolean` | No | `false` | Continue execution if agent launch fails |
| `finishExitCode` | `Int` | No | `null` | Exit code that causes the agent to finish early (successfully) instead of failing |
| `resultEnvParam` | `List<ResultEnvParam>` | No | `[]` | Extract the child process id (stdout is `processId: <id>`; capture it with a `regex` transformer) to environment variables |
| `when` | `When` | No | `null` | Conditional execution predicate. See [Conditional execution (`when`)](#conditional-execution-when). |

#### AgentCommandParam Fields

Each entry in `queryParams` maps a value from the current execution environment to a parameter of the child agent:

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `envName` | `String` | Yes | - | Name of the environment variable in the current execution context |
| `paramName` | `String` | Yes | - | Query parameter name to pass to the child agent |
| `transformer` | `ResultTransformer` | No | `pass` | Transform the value before passing (pass, jsonPath, regex). A `regex` match is evaluated with a wall-clock time bound; a match that exceeds it is aborted and the parameter is omitted, like a non-match. Once one `regex` match is shed, later `regex` transformers in the same `queryParams` list are omitted without being evaluated |

If an env var referenced by `envName` is not present in the execution environment, or its `transformer` yields no value (a `jsonPath` that does not match or fails to parse, a `regex` that does not match, times out or does not compile), that parameter is silently omitted from the child agent call — the dispatch proceeds without it. After a `regex` match is shed (its time bound exceeded), the remaining `regex` transformers of that `queryParams` list are omitted without being evaluated — including ones whose own match would have succeeded; the other transformer kinds still run. Forwarded values are routed to the child's declared input src: query inputs arrive as query params, body inputs as JSON body fields (validated against the child's inputSchema when present, as strings). header inputs can never be satisfied by a parent agent.

#### Example: Orchestrating Agents

```yaml
name: orchestrator
commands:
  - !<agent>
    agentName: deploy-service
    queryParams:
      - envName: SERVICE_NAME
        paramName: name
        transformer: !<pass> {}
    resultEnvParam:
      - src: STD_OUT
        envName: DEPLOY_PROCESS_ID
        transformer: !<regex>
          regex: 'processId: (\d+)'
          group: 1
  - !<script>
    content: echo "Deployment started with processId $DEPLOY_PROCESS_ID"
```

### Command.LangGraph - LangGraph Runtime Launcher

Launches the Python `homer_langgraph` runtime, which runs a LangGraph graph and owns all side effects and durable checkpointing. The Kotlin side is a **thin launcher**: it runs `python -m homer_langgraph run --module <module>`, passes the workspace env through (so resume params arriving in env reach the runtime), injects the durable state directory as `HOMER_LG_STATE_DIR`, captures stdout/stderr, and returns the command result.

```yaml
commands:
  - !<langgraph>
    module: my_graphs.approval_flow
    python: python3
    inputEnv: HMR_LG_INPUT
    timeout: 120
```

<details>
<summary>JSON format</summary>

```json
{
  "commands": [
    {
      "type": "langgraph",
      "module": "my_graphs.approval_flow",
      "python": "python3",
      "inputEnv": "HMR_LG_INPUT",
      "timeout": 120
    }
  ]
}
```
</details>

#### LangGraph Fields

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `module` | `String` | Yes | - | Python module path passed to `homer_langgraph run --module`; the runtime imports it to find the graph |
| `python` | `String` | No | `python3` | Python interpreter to invoke |
| `inputEnv` | `String` | No | `HMR_LG_INPUT` | Name of the env var the runtime reads the graph's fresh-run JSON input from (passed to the runtime as `--input-env`). See [Delivering fresh-run input](#delivering-fresh-run-input). |
| `label` | `String` | No | `"langgraph"` | Human-readable label shown in the console execution list |
| `timeout` | `Long` | No | `60` (60 sec) | Command timeout in seconds |
| `continueIfFail` | `Boolean` | No | `false` | Continue when the command exits non-zero. Timeouts, output-cap kills and other thrown errors fail the agent regardless, even with `continueIfFail`. |
| `finishExitCode` | `Int` | No | `null` | Exit code that causes the agent to finish early (successfully) instead of failing |
| `resultEnvParam` | `List<ResultEnvParam>` | No | `[]` | Extract runtime outputs to environment variables |
| `when` | `When` | No | `null` | Conditional execution predicate. See [Conditional execution (`when`)](#conditional-execution-when). |

The durable state directory is **not** a command field: Homer injects it as the `HOMER_LG_STATE_DIR` env var, resolved from `server.langGraph.stateDir` (or derived beside the database path) plus a per-owner segment, so each principal's checkpoints are separate. See [`SERVER.md`](SERVER.md).

#### Delivering fresh-run input

On a fresh run the runtime reads the graph's initial JSON input from the env var named by `inputEnv`. The default, `HMR_LG_INPUT`, is in Homer's reserved `HMR_*` namespace — a caller's `inputs:` param **cannot** target a reserved name, so with the default the graph always starts from `{}` (and must read whatever it needs from other workspace env vars directly).

To pass structured fresh-run input, declare an `inputs:` param that maps a caller value to a **non-reserved** env var, and set `inputEnv` to that same name:

```yaml
name: approval_flow
dispatch:
  enabled: true
inputs:
  - paramName: payload
    envName: GRAPH_INPUT
    type: string
commands:
  - !<langgraph>
    module: my_graphs.approval_flow
    inputEnv: GRAPH_INPUT
```

The runtime then reads `GRAPH_INPUT` (the caller's `payload`, JSON-decoded) as the graph's initial state.

#### `dispatch.enabled` is required

An agent containing a `!<langgraph>` command **must** set `dispatch.enabled: true`. This is enforced at load time — an agent that omits it is rejected and skipped. The requirement exists because a parked LangGraph thread resumes through Homer's dispatch/continuation machinery.

#### Resume re-runs the whole agent

When a parked LangGraph thread resumes — or a failed run is recovered via `POST /api/v1/processes/{id}/resume` — Homer **re-runs the agent from the top** (carrying the thread id in `HMR_LG_THREAD`), not just the langgraph step. Keep the langgraph step self-contained, or guard earlier commands so they don't repeat their side effects on resume — e.g. gate them with `when:` on `HMR_LG_THREAD` being unset:

```yaml
name: approval_flow
dispatch:
  enabled: true
commands:
  - !<cmd>
    cmd: ./prepare-once.sh
    when: '[ -z "$HMR_LG_THREAD" ]'
  - !<langgraph>
    module: my_graphs.approval_flow
```

`HMR_LG_THREAD` is reserved — external HTTP/MCP callers and declared inputs cannot set it — and is absent on a fresh run. It is set only when re-entering an existing thread: by a running agent's dispatch/continuation request (`continueThread`, which is how the runtime resumes a parked thread), or by Homer on a recover via the resume endpoint. That is what makes `when: '[ -z "$HMR_LG_THREAD" ]'` a reliable "is this a fresh run, not a resume?" gate.

#### Multiple simultaneous interrupts

When a graph parks on several interrupts at once (e.g. parallel branches each calling `homer_interrupt`/`homer_fanout`), the runtime resolves them in one of two modes. **Sequential (default):** it picks one per resume cycle deterministically, carries its id in the reserved param `HMR_LG_INTERRUPT`, and leaves the rest pending — N interrupts take N round-trips. **Parallel (join):** when ≥2 `homer_fanout` interrupts in a segment share a non-None `group=` marker, the runtime dispatches them all at once via `homer-join` and resumes the graph **once** after the last finishes, carrying all results keyed by interrupt id — the group costs a single round-trip (wall-clock ≈ slowest sub). Opt-in is per `homer_fanout` call, so one graph can mix grouped and ungrouped fan-outs; grouped fan-out nodes must keep their bodies idempotent (node bodies re-run on resume). See [ADR-0026](../docs/adr/0026-join-continuations.md). Multiple `interrupt()` calls in the *same* node are not individually targeted (they replay by index); the targeted model covers distinct parallel interrupts.

### Command.RunnerCmd - Run a Command on Another Runner

Runs a single command on any configured runner alias (`local`, `ssh`, `kubernetes`, or an `external` domain) via `check → syncToRemote → execute → syncFromRemote` phases: pre-flight checks on the target, selective upload, the script piped to `<shell> -s -`, then selective download back — so one step of an otherwise-local agent can run on a build box or GPU host without moving the whole agent there. See [ADR-0034](../docs/adr/0034-runnercmd-command-type.md).

```yaml
commands:
  - !<runnerCmd>
    runner: build-box
    check:
      - which cargo
    syncToRemote:
      - src/
    execute: |
      cargo build --release
    syncFromRemote:
      - target/release/app
    timeout: 600
```

<details>
<summary>JSON format</summary>

```json
{
  "commands": [
    {
      "type": "runnerCmd",
      "runner": "build-box",
      "check": ["which cargo"],
      "syncToRemote": ["src/"],
      "execute": "cargo build --release\n",
      "syncFromRemote": ["target/release/app"],
      "timeout": 600
    }
  ]
}
```
</details>

#### RunnerCmd Fields

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `runner` | `String` | Yes | - | Runner alias from server config, or `local` |
| `execute` | `String` | Yes | - | Script piped to `<shell> -s -` on the target |
| `shell` | `String` | No | `sh` | Shell interpreter used for both `check` (`<shell> -c <expr>`) and `execute` (`<shell> -s -`) |
| `check` | `List<String>` | No | `[]` | Pre-flight expressions run on the target after the runner's `extraUploads` but before `syncToRemote`; each must exit `0` |
| `syncToRemote` | `List<String>` | No | `[]` | Workdir-relative paths (files or directories) uploaded to the target before `execute` |
| `syncFromRemote` | `List<String>` | No | `[]` | Target-workdir-relative paths downloaded back after a successful `execute`. With `runner: local` the download is a filesystem no-op, so each entry effectively asserts the path exists in the workdir after `execute` — the command fails otherwise. |
| `label` | `String` | No | `"runnerCmd"` | Human-readable label shown in the console execution list |
| `timeout` | `Long` | No | `60` (60 sec) | `execute` timeout in seconds (`check` expressions each get a fixed 60 s, not configurable) |
| `continueIfFail` | `Boolean` | No | `false` | Continue when the command exits non-zero. Timeouts, output-cap kills and other thrown errors fail the agent regardless, even with `continueIfFail`. |
| `finishExitCode` | `Int` | No | `null` | Exit code that causes the agent to finish early (successfully) instead of failing |
| `resultEnvParam` | `List<ResultEnvParam>` | No | `[]` | Extract command outputs to environment variables |
| `when` | `When` | No | `null` | Conditional execution predicate. See [Conditional execution (`when`)](#conditional-execution-when). |

#### Semantics and restrictions

- **Phase order:** `check` (if any) runs first — the runner's `extraUploads` are already on the target by then, but a non-zero check **returns that exit code as the command's result** (it does not throw), so `continueIfFail`/`finishExitCode` apply exactly as with any other command, and neither `syncToRemote`, `execute`, nor `syncFromRemote` run. Transport/config failures (can't connect, upload fails, an expected `syncFromRemote` path never appears) throw and fail the agent regardless of `continueIfFail`.
- **`syncToRemote`/`syncFromRemote` paths are workdir-relative** — resolved against the local `HMR_WORK_DIR` and the target's translated workdir; absolute paths and `..` are rejected at agent load time.
- **`syncFromRemote` only runs on success** — `execute` exiting `0` or matching `finishExitCode`. A requested path that isn't there afterward fails the command.
- **The agent itself must run on `runner: local`** — enforced at load time; a `runnerCmd` step inside an agent targeting a non-local runner is rejected and the agent is skipped, since the server's own workspace copy would be stale mid-run on a remote agent runner.
- **A `runnerCmd` step waits in the claim queue for its target runner** like any other run, under the server default claim TTL (`server.runnerClaimTimeoutSec`) — the agent's own `runnerClaimTimeoutSec` does not apply to it. See [Runner claim queue](SERVER.md#runner-claim-queue).
- **The target's remote workspace is always swept** after the command, success or failure — nothing tracks remote leftovers, so keep-on-failure would accumulate litter.
- **`HMR_AGENT_DIR` is not auto-synced** to the target — it only exists there if `syncToRemote` explicitly uploads something into it.
- **Other translated `HMR_*` env also points at paths a `runnerCmd` never fills on remote (ssh, kubernetes, external) targets** — `RemotePathTranslator` rewrites every env value containing the local project root, so `HMR_ARTIFACTS_DIR` resolves to a remote directory that is never synced back (an ssh or kubernetes target never creates it; an external instance creates it empty), and only paths explicitly listed in `syncFromRemote` are downloaded, so writing to `HMR_ARTIFACTS_DIR` silently loses the output; the `.homer/bin` entry prepended to `PATH` points at a remote dir with no helpers in it, so the `homer-ask` question helper is unavailable; and `HMR_MCP_CONFIG` points at a file that was never uploaded.
- **A remote target does not receive the backend's host env** — the target's env is the agent's composed env minus any `parentEnvAllowlist` key that still carries the host value it was layered with (they describe Homer's host, not the target's — the same rule that keeps them out of a remote runner's own env; see SERVER.md's `parentEnvAllowlist` → "Remote runners"). A key re-set via `globalEnv`, the runner's `env`, or the agent's `env` reaches the target; a `runner: local` target keeps everything.

#### Examples

**Minimal (local target):**
```yaml
- !<runnerCmd>
  runner: local
  execute: |
    echo "hello from $(hostname)"
```

**Full (ssh target with checks and sync both ways):**
```yaml
- !<runnerCmd>
  runner: gpu-box
  check:
    - test -x /usr/local/bin/nvidia-smi
    - which python3
  syncToRemote:
    - train.py
    - data/
  execute: |
    python3 train.py --epochs 5
  syncFromRemote:
    - model.bin
    - metrics.json
  timeout: 1800
```

### Conditional execution (`when`)

Every command type accepts an optional `when:` predicate that decides at runtime whether the command should run. If present and the predicate evaluates to false, the command is **skipped**: an entry is recorded in the process executions list with `skipped: true`, and the agent moves on to the next command unchanged. `continueIfFail` and `finishExitCode` do not apply to skipped commands.

The predicate is one or more bash-`test`-style expressions joined by `all` (AND) or `any` (OR), evaluated against the same env the command would have seen — including any vars produced by earlier commands' `resultEnvParam`. Three input forms are accepted:

```yaml
# String shorthand — equivalent to { all: [<string>] }
- !<cmd>
  cmd: "deploy.sh"
  when: '[ -n "$REGION" ]'

# AND of N expressions (all must be true)
- !<cmd>
  cmd: "deploy-eu.sh"
  when:
    all:
      - '[ -n "$REGION" ]'
      - '[[ "$REGION" =~ ^eu- ]]'

# OR of N expressions (at least one must be true)
- !<cmd>
  cmd: "smoke-test.sh"
  when:
    any:
      - '[ "$ENV" = "dev" ]'
      - '[ "$ENV" = "staging" ]'
```

#### Evaluation semantics

- Each expression runs as `bash -c <expr>` with the command's env and `cwd = workspace.workdir`.
- Per-expression timeout is **5 seconds**, hard-coded.
- Short-circuit: `all` stops at the first false expression; `any` stops at the first true.
- Bash exit codes drive the result: `0` → true, `1` → false, `≥ 2` → eval error.
- An eval error (syntax error, unbound `set -u` var, timeout, etc.) **fails the agent** with `CommandExecutionException`. Silent skip on error would mask predicate bugs; if you want a permissive default, write the expression so it returns `0` or `1` explicitly.
- The predicate gates the command **even when it was selected explicitly** via `commandIndices` (debug runs, single-command launches). The predicate is part of the command's semantics, not a list-time filter.

#### Common bash-test idioms

| Goal | Expression |
|---|---|
| Var is set and non-empty | `[ -n "$VAR" ]` |
| Var is empty or unset | `[ -z "$VAR" ]` |
| String equality | `[ "$VAR" = "expected" ]` |
| Regex match | `[[ "$VAR" =~ ^pattern ]]` |
| Negation | `! [ "$VAR" = "x" ]` |
| File exists | `[ -f "/path/to/file" ]` |

#### Dry-run behavior

`agentdebug run <agent> --dry-run` prints the predicate (`[WHEN all] …` / `[WHEN any] …`) but does **not** evaluate it — the dry-run env may not match production, and the printer's job is to show the plan, not to commit to a specific outcome. `show-commands` likewise prints a one-line predicate summary without evaluating.

## Claude Prompt Types

The `prompt` field in Claude commands is a sealed interface with two implementations:

### Prompt - Inline Prompt

Provides the prompt text directly in the agent definition:

```yaml
prompt: !<prompt>
  prompt: |
    Review the code in src/main.kt and identify potential bugs.
    Focus on:
    - Null safety issues
    - Resource leaks
    - Performance problems
```

<details>
<summary>JSON format</summary>

```json
{
  "prompt": {
    "type": "prompt",
    "prompt": "Review the code in src/main.kt and identify potential bugs.\nFocus on:\n- Null safety issues\n- Resource leaks\n- Performance problems"
  }
}
```
</details>

**Fields:**
- `prompt`: The prompt text (supports multi-line, variable substitution)

**Example with variable substitution:**
```yaml
prompt: !<prompt>
  prompt: "Analyze the file {{TARGET_FILE}} and summarize its purpose."
```

### Langfuse - Langfuse-Managed Prompt

References a prompt managed in Langfuse:

```yaml
prompt: !<langfuse>
  promptName: "code-review-prompt"
  version: 2          # optional: specific version number
  label: "production" # optional: deployment label
```

<details>
<summary>JSON format</summary>

```json
{
  "prompt": {
    "type": "langfuse",
    "promptName": "code-review-prompt",
    "version": 2,
    "label": "production"
  }
}
```
</details>

**Fields:**
- `promptName`: Langfuse prompt name
- `version`: Specific version number to fetch (optional). When omitted, Langfuse resolves by `label`.
- `label`: Deployment label to fetch (optional, e.g. `"production"`, `"staging"`). When both `version` and `label` are omitted, Langfuse defaults to the `"production"` label.

**Benefits:**
- Centralized prompt management
- Version control for prompts
- A/B testing capabilities

**How it works:**
1. Homer fetches the prompt from Langfuse on every run (a chat prompt is flattened to one `role: content` line per message)
2. `{{VAR}}` placeholders in the prompt text are substituted from the workspace env
3. The compiled text is piped to the CLI on stdin
4. The trace records the compiled prompt text (`input.value`, secrets redacted), not a link to the Langfuse prompt version

## Claude Command Options

The `options` field in Claude commands provides fine-grained control over Claude CLI behavior:

> **Templating.** Option values support `{{VAR}}` placeholders, resolved against
> the agent/workspace environment (the same env and mechanism as the prompt),
> including system variables such as `{{HMR_AGENT_DIR}}`. Because options reach
> the Claude CLI as literal arguments (no shell), this is how you reference a
> file shipped beside the agent definition — e.g.
> `systemPromptFile: "{{HMR_AGENT_DIR}}/system.md"` or `addDir: "{{HMR_AGENT_DIR}}"`.
> Unknown placeholders are left untouched.

```yaml
options:
  model: sonnet
  agents:
    code-reviewer:
      prompt: "You review pull requests for clarity, bugs, and style."
    test-automator:
      prompt: "You write unit tests for the code you are shown."
  allowedTools:
    - Read
    - Grep
    - Bash
  disallowedTools:
    - Write
  addDir:
    - "{{HMR_AGENT_DIR}}/shared"
    - /srv/reference
  pluginDir:
    - /srv/plugins/a
    - /srv/plugins/b
  includeHookEvents: true
  maxTurns: 50
  maxTurnsExitCode: 75
```

<details>
<summary>JSON format</summary>

```json
{
  "options": {
    "model": "sonnet",
    "agents": {
      "code-reviewer": { "prompt": "You review pull requests for clarity, bugs, and style." },
      "test-automator": { "prompt": "You write unit tests for the code you are shown." }
    },
    "allowedTools": ["Read", "Grep", "Bash"],
    "disallowedTools": ["Write"],
    "addDir": ["{{HMR_AGENT_DIR}}/shared", "/srv/reference"],
    "pluginDir": ["/srv/plugins/a", "/srv/plugins/b"],
    "includeHookEvents": true,
    "maxTurns": 50,
    "maxTurnsExitCode": 75
  }
}
```
</details>

### ClaudeCmdOptions Fields

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `agent` | `String` | No | `null` | Single agent to use |
| `agents` | `Map<String, ClaudeSubagent>` | No | `null` | Inline subagent definitions; serialized to `--agents` as a JSON object |
| `maxTurns` | `Int` | No | `40` | Maximum agentic turns |
| `maxTurnsExitCode` | `Int` | No | `null` | Opt-in: when Claude stops at `maxTurns` (result subtype `error_max_turns`), the command returns this exit code instead of failing the run. Must be in `1..255` (a value outside that range, e.g. `0`, is rejected at load time so a max-turns failure cannot masquerade as success). Combine with `continueIfFail: true` and a `resultEnvParam` with `src: RESULT_CODE` so a later deterministic step can push or discard the partial work. Cost is charged as usual; the attempt is not retried. |
| `maxMcpOutputTokens` | `Int` | No | `null` | Maximum MCP output tokens (`MAX_MCP_OUTPUT_TOKENS` env var) |
| `model` | `String` | No | `null` | Claude model to use (`sonnet`, `opus`, `haiku`) |
| `fallbackModel` | `String` | No | `null` | Fallback model if the primary is overloaded |
| `systemPrompt` | `String` | No | `null` | Literal system prompt (mutex with `systemPromptFile`) |
| `systemPromptFile` | `String` | No | `null` | Path to system-prompt file (mutex with `systemPrompt`) |
| `appendSystemPrompt` | `String` | No | `null` | Text appended after the default system prompt |
| `appendSystemPromptFile` | `String` | No | `null` | File appended after the default system prompt |
| `permissionMode` | `String` | No | `null` | One of `default`, `acceptEdits`, `plan`, `auto`, `dontAsk`, `bypassPermissions`. When set, `--dangerously-skip-permissions` is not emitted. |
| `permissionPromptTool` | `String` | No | `null` | MCP tool used to prompt for permission in headless runs |
| `maxBudgetUsd` | `Double` | No | `null` | Upper bound on spend for this run |
| `sessionId` | `String` | No | `null` | UUID identifying the session |
| `addDir` | `String \| List<String>` | No | `null` | Additional director(y/ies) to add to context. A bare string adds one; a list adds several (emitted as `--add-dir a b`). |
| `allowedTools` | `List<String>` | No | `null` | Tools that run without a permission prompt. Not a restriction on its own: with `dangerouslySkipPermissions` (or `permissionMode: bypassPermissions`) every tool runs anyway. See [Tool Allowlisting/Denylisting](#tool-allowlistingdenylisting) |
| `disallowedTools` | `List<String>` | No | `null` | Tools removed from the session; holds under every permission setting |
| `mcpConfig` | `String \| List<String>` | No | `null` | MCP configuration file path(s). A bare string loads one; a list loads several (emitted as `--mcp-config a b`). |
| `strictMcpConfig` | `Boolean` | No | `false` | Only use MCP servers from `mcpConfig`, ignoring all other MCP configurations |
| `includeHookEvents` | `Boolean` | No | `false` | Emit hook events into the stream-json output |
| `includePartialMessages` | `Boolean` | No | `false` | Emit partial (streaming) message chunks. **Footgun:** partial chunks can inflate stdout past `maxOutputBytes`, truncating the final `result` event and failing the run. |
| `inputFormat` | `String` | No | `null` | Input format: `text` (default) or `stream-json`. **Footgun:** Homer feeds the prompt as plain-text stdin, so only `text` works; `stream-json` breaks the run. Passed through unvalidated. |
| `pluginDir` | `String \| List<String>` | No | `null` | Plugin director(y/ies). A bare string adds one; a list repeats the flag (`--plugin-dir a --plugin-dir b`). |
| `pluginUrl` | `String \| List<String>` | No | `null` | URL(s) of a plugin `.zip` fetched for this session only. A bare string adds one; a list repeats the flag (`--plugin-url a --plugin-url b`). |
| `file` | `String \| List<String>` | No | `null` | File resource(s) to download at startup, format `file_id:relative_path` (emitted as `--file a b`) |
| `settings` | `String` | No | `null` | Settings file path |
| `settingSources` | `List<String>` | No | `null` | Subset of `user`, `project`, `local` to load settings from |
| `tools` | `List<String>` | No | `null` | The built-in tools the session has; any other built-in tool is unavailable |
| `disableSlashCommands` | `Boolean` | No | `false` | Disable slash-command interpretation |
| `effort` | `String` | No | `null` | One of `low`, `medium`, `high`, `xhigh`, `max` |
| `excludeDynamicSystemPromptSections` | `Boolean` | No | `false` | Move per-machine sections (cwd, env, git status) from the system prompt into the first user message |
| `noSessionPersistence` | `Boolean` | No | `false` | Do not persist the session to disk |
| `bare` | `Boolean` | No | `false` | Minimal mode: skips hooks, LSP, plugin sync, auto-memory, keychain reads and CLAUDE.md auto-discovery; auth only via `ANTHROPIC_API_KEY`/`apiKeyHelper` |
| `jsonSchema` | `String` | No | `null` | JSON-Schema document the final response must conform to |
| `betas` | `String` | No | `null` | Space-separated beta feature names (API-key users only) |
| `debugFile` | `String` | No | `null` | Path to write CLI debug log |
| `dangerouslySkipPermissions` | `Boolean` | No | `false` | Skip permission prompts (explicit opt-in). Ignored when `permissionMode` is set. |
| `options` | `List<String>` | No | `null` | Additional raw CLI options (escape hatch) |

> **Security:** `dangerouslySkipPermissions` defaults to `false`. Setting it to
> `true` passes `--dangerously-skip-permissions` to the Claude CLI, granting Claude
> unrestricted file-system and tool access with no confirmation — opt in only in
> isolated or sandboxed environments. Homer runs Claude headlessly, so with the
> default `false` and no `permissionMode`/`allowedTools` grant, tool calls that
> would need interactive approval are denied. Prefer scoping the agent with
> `allowedTools` or `permissionMode` over blanket-skipping permissions, and when
> permissions are skipped, restrict tools with `disallowedTools` or `tools`:
> `allowedTools` then restricts nothing.
> See [ADR-0017](../docs/adr/0017-claude-skip-permissions-default.md).

### Agent Configuration

Agents are inline subagent definitions handed to Claude Code via `--agents`. Each
key is the subagent name; each value is a `ClaudeSubagent` with a required `prompt`
and optional `description`, `tools`, and `model`:

```yaml
options:
  agents:
    code-reviewer:
      prompt: "You review pull requests for clarity, bugs, and style."
      description: Code review specialist
      tools: [Read, Grep]
      model: sonnet
    test-automator:
      prompt: "You write unit tests for the code you are shown."
```

<details>
<summary>JSON format</summary>

```json
{
  "options": {
    "agents": {
      "code-reviewer": {
        "prompt": "You review pull requests for clarity, bugs, and style.",
        "description": "Code review specialist",
        "tools": ["Read", "Grep"],
        "model": "sonnet"
      },
      "test-automator": {
        "prompt": "You write unit tests for the code you are shown."
      }
    }
  }
}
```
</details>

Homer serializes this map to a single JSON-object argument on `--agents` (absent
optional fields are omitted, not rendered as `null`).

### Tool Allowlisting/Denylisting

Control which tools Claude can use. The three options differ in how they hold
when permissions are skipped:

- `allowedTools` lists tools that run **without a permission prompt**. Headless,
  a tool that needs approval and is not listed is denied, so with the default
  permissions it works as an allowlist for those tools. With
  `dangerouslySkipPermissions` (or `permissionMode: bypassPermissions`) nothing
  needs approval, and `allowedTools` restricts nothing.
- `disallowedTools` removes tools from the session under every permission
  setting. Deny the delegation tools (`Agent`, `Task`) here when a step must not
  spawn subagents: each subagent has its own turn budget, so `maxTurns` does not
  bound them.
- `tools` sets which built-in tools the session has at all.

**Pre-approved tools (headless, with the default permissions):**
```yaml
options:
  allowedTools:
    - Read
    - Grep
    - Glob
```

**Denylist (all tools except these, under any permission setting):**
```yaml
options:
  disallowedTools:
    - Bash
    - Write
    - Edit
```

**Note:** A step that skips permissions is contained by `disallowedTools` (or `tools`), not by `allowedTools`. See the case study [Containing a Claude step](../docs/case-studies/claude-tool-limits.md).

### MCP Configuration

Configure MCP using the `mcpConfig` field:

```yaml
options:
  mcpConfig: "/path/to/mcp/config.json"
```

MCP servers must be configured in the MCP configuration file.

## Result-to-Environment Mapping

The `resultEnvParam` field is a list that extracts command outputs and stores them as environment variables for subsequent commands. You can extract multiple values from different sources (stdout, stderr, exit code) in a single command.

### ResultEnvParam Structure

```yaml
resultEnvParam:
  - src: STD_OUT
    envName: EXTRACTED_VALUE
    transformer: !<jsonPath>
      path: "$.data.id"
  - src: STD_ERR
    envName: ERROR_MSG
    transformer: !<pass> {}
```

<details>
<summary>JSON format</summary>

```json
{
  "resultEnvParam": [
    {
      "src": "STD_OUT",
      "envName": "EXTRACTED_VALUE",
      "transformer": {
        "type": "jsonPath",
        "path": "$.data.id"
      }
    },
    {
      "src": "STD_ERR",
      "envName": "ERROR_MSG",
      "transformer": {
        "type": "pass"
      }
    }
  ]
}
```
</details>

#### Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `src` | `SrcType` | Yes | Where to extract from (`STD_OUT`, `STD_ERR`, `RESULT_CODE`) |
| `envName` | `String` | Yes | Environment variable name to store result. Same restrictions as input `envName`: **cannot** target Homer-controlled names (`HMR_*`) or host-controlled names (`PATH`, `HOME`, `USER`, `SHELL`, `LANG`, `TZ`, `PWD`, `OLDPWD`, `IFS`, `BASH_ENV`, `ENV`, or anything beginning with `LD_`, `JAVA_`, or `LC_`), and must match `^[A-Za-z_][A-Za-z0-9_]*$`. Captured values are merged into the env of later commands, so an agent that captures into a reserved/malformed name is skipped at load time with an error in the reload result. |
| `transformer` | `ResultTransformer` | No | How to transform the output (default: Pass) |

#### Source Types (SrcType)

| Source | Description |
|--------|-------------|
| `STD_OUT` | Standard output stream |
| `STD_ERR` | Standard error stream |
| `RESULT_CODE` | Exit code as string |

### Transformer Types

#### Transformer.Pass - Use As-Is

Uses the output without transformation:

```yaml
resultEnvParam:
  - src: STD_OUT
    envName: RAW_OUTPUT
    transformer: !<pass> {}
```

<details>
<summary>JSON format</summary>

```json
{
  "resultEnvParam": [
    {
      "src": "STD_OUT",
      "envName": "RAW_OUTPUT",
      "transformer": {
        "type": "pass"
      }
    }
  ]
}
```
</details>

**Example:**
```yaml
- !<cmd>
  cmd: "cat version.txt"
  resultEnvParam:
    - src: STD_OUT
      envName: VERSION
      transformer: !<pass> {}
# A later !<script> can read it as "$VERSION"
```

#### Transformer.JsonPath - Extract JSON Values

Extracts values from JSON output using JSONPath syntax:

```yaml
resultEnvParam:
  - src: STD_OUT
    envName: USER_ID
    transformer: !<jsonPath>
      path: "$.user.id"
```

<details>
<summary>JSON format</summary>

```json
{
  "resultEnvParam": [
    {
      "src": "STD_OUT",
      "envName": "USER_ID",
      "transformer": {
        "type": "jsonPath",
        "path": "$.user.id"
      }
    }
  ]
}
```
</details>

**Fields:**
- `path`: JSONPath expression

**JSONPath Syntax Examples:**

```yaml
# Extract single field
path: "$.name"

# Extract nested field
path: "$.user.profile.email"

# Extract from array (first element)
path: "$.users[0].name"

# Extract array of values
path: "$.users[*].id"

# Filter array
path: "$.users[?(@.active == true)].name"
```

**Complete Example:**
```yaml
- !<script>
  content: curl -s "https://api.github.com/users/$USERNAME"
  resultEnvParam:
    - src: STD_OUT
      envName: GITHUB_ID
      transformer: !<jsonPath>
        path: "$.id"

- !<script>
  content: |
    echo "GitHub ID: $GITHUB_ID"
```

#### Transformer.Regex - Extract with Regular Expressions

Extracts values using regex pattern matching:

```yaml
resultEnvParam:
  - src: STD_OUT
    envName: VERSION_NUMBER
    transformer: !<regex>
      regex: "version: (\\d+\\.\\d+\\.\\d+)"
      group: 1
```

<details>
<summary>JSON format</summary>

```json
{
  "resultEnvParam": [
    {
      "src": "STD_OUT",
      "envName": "VERSION_NUMBER",
      "transformer": {
        "type": "regex",
        "regex": "version: (\\d+\\.\\d+\\.\\d+)",
        "group": 1
      }
    }
  ]
}
```
</details>

**Fields:**
- `regex`: Regular expression pattern. Evaluated with a wall-clock time bound; a
  catastrophic-backtracking pattern that exceeds it — and a pattern that does not
  compile — aborts the match and the value is omitted instead of pinning a CPU or
  failing the run (guards against ReDoS and bad patterns on command output). Once
  one match is shed, later `regex` transformers in the same `queryParams` or
  `resultEnvParam` list are omitted without being evaluated.
- `group`: Capture group index (0 = full match, 1+ = groups, null = full match). In a `resultEnvParam`, a `group` beyond the number of groups in the pattern is an error that fails the agent, even with `continueIfFail`.

**Examples:**

```yaml
# Extract version number
transformer: !<regex>
  regex: "v(\\d+\\.\\d+\\.\\d+)"
  group: 1

# Extract email
transformer: !<regex>
  regex: "([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})"
  group: 1

# Extract full match
transformer: !<regex>
  regex: "ERROR: .*"
  group: 0

# Extract commit hash
transformer: !<regex>
  regex: "commit ([a-f0-9]{40})"
  group: 1
```

**Complete Example:**
```yaml
- !<cmd>
  cmd: "npm --version"
  resultEnvParam:
    - src: STD_OUT
      envName: NPM_VERSION
      transformer: !<regex>
        regex: "(\\d+\\.\\d+\\.\\d+)"
        group: 1

- !<script>
  content: echo "Using npm version $NPM_VERSION"
```

## Agent Inputs

The `inputs` field on the agent defines parameters the caller supplies. Each `Param` declares its source via `src` (`query`, `body`, or `header`) and is bound to an environment variable via `envName`.

> **Legacy alias:** Older agent files use `queryParams:` for the same list — it still works. New agents should use `inputs:`. Setting both on one agent fails agent load.

> **Naming collision warning:** `queryParams` also appears as a field on the `RunAgent` command (`!<agent>`), but it has a **different shape and meaning** there — it is a list of env-to-param mappings (`AgentCommandParam`) used to forward variables from the current execution into the child agent's HTTP call. The two are unrelated. See [Command.RunAgent](#commandrunagent---agent-invocation).

### Param Structure

```yaml
inputs:
  - paramName: userId
    envName: USER_ID
    type: integer
    required: true
    transformer: !<pass> {}
```

<details>
<summary>JSON format</summary>

```json
{
  "inputs": [
    {
      "paramName": "userId",
      "envName": "USER_ID",
      "type": "integer",
      "required": true,
      "transformer": {
        "type": "pass"
      }
    }
  ]
}
```
</details>

### Param Fields

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `paramName` | `String` | Yes | - | Parameter name — query-string key, JSON body key, or HTTP header name depending on `src`. |
| `envName` | `String` | No | `paramName` | Environment variable name (defaults to paramName). **Cannot** target Homer-controlled names (`HMR_*`) or host-controlled names (`PATH`, `HOME`, `USER`, `SHELL`, `LANG`, `TZ`, `PWD`, `OLDPWD`, `IFS`, `BASH_ENV`, `ENV`, or anything beginning with `LD_`, `JAVA_`, or `LC_`). Agents that declare a reserved `envName` are skipped at load time with an error in the reload result. Must match `^[A-Za-z_][A-Za-z0-9_]*$`. Header params whose name contains dashes (e.g. `X-Trace-Id`) must declare an explicit `envName` — the default (`envName = paramName`) would be invalid. Duplicate `envName`s, and duplicate `paramName`s within the same `src`, are rejected at load. |
| `src` | `ParamSource` | No | `query` | Where to read the value from: `query` (URL query string), `body` (JSON request body), or `header` (HTTP header). |
| `type` | `ParamType` | No | `STRING` | Parameter type validation |
| `required` | `Boolean` | No | `true` | Whether parameter is required |
| `transformer` | `Transformer` | No | `Pass` | How to transform the parameter value |
| `secret` | `Boolean` | No | `false` | Redact this input's value in API responses, and in telemetry spans across all command types (`cmd`/`script`/`claude`/`codex`/`opencode`/`runnerCmd`/`langgraph`). Declaring it also drops the input's `default` and `values` from the `GET /api/v1/agents` summary and the MCP tool schema. Never redacted in stdout/stderr artifacts or the raw stdout/stderr log-tail (`GET /stream/processes/{id}/logs`) — if a command echoes the value, it is stored and served there in the clear; the canonical LLM event stream (`GET /stream/processes/{id}/llm-events`, `!<claude>`/`!<codex>`/`!<opencode>`) does redact declared-secret values. The value still reaches the run env and the server database. Stored values whose envName no longer matches any current input are always redacted (fail closed). See [Security notes for input authors](#security-notes-for-input-authors). |
| `values` | list of strings | no | – | Typed types only (`string`/`integer`/`number`): value must equal one of these. Numeric comparison for `integer`/`number`. On a `secret: true` input the list is dropped from the `GET /api/v1/agents` summary and the MCP tool schema, and a value outside it gets a generic denial (`is not one of the allowed values`) that never lists the set. See [ADR-0071](../docs/adr/0071-secret-input-defaults-are-dropped-from-the-agents-list.md). |
| `pattern` | string | no | – | `string` type only: regex the whole value must match. Evaluated with a wall-clock time bound; a value that makes the pattern backtrack past it is rejected (ReDoS guard). |
| `min` / `max` | number | no | – | `integer`/`number` types only: inclusive bounds. Must be integral for `integer`. |
| `minLength` / `maxLength` | integer | no | – | `string` type only: length bounds. |
| `default` | string | no | – | Typed types only: used when the param is absent. Requires `required: false`. Must itself satisfy the param's constraints (checked at load). On a `secret: true` input the default is dropped from the `GET /api/v1/agents` summary and the MCP tool schema. |

### Parameter Types

| Type | Validation | Constraints available |
| --- | --- | --- |
| `string` | any string (spaces allowed) | `values`, `pattern`, `minLength`, `maxLength`, `default` |
| `integer` | 64-bit integer (`Long`); decimals rejected | `values`, `min`, `max`, `default` |
| `number` | decimal (`Double`); `NaN`/`Infinity` rejected | `values`, `min`, `max`, `default` |
| `boolean` | `true`/`false`, case-insensitive; env value normalized to lowercase | `default` |

Missing required inputs and constraint violations are rejected with
`400` and a message naming the parameter and the violated rule.

**Deprecated legacy types.** Uppercase `STRING`, `NUMBER`, `ANY` are the
previous generation and log a WARN at agent load. Semantics are frozen:
`STRING` rejects any value containing a space **or a control character**
(e.g. a newline), `NUMBER` accepts only `Int`-parseable values, and `ANY`
performs **no validation at all** — an `ANY` value is fully attacker-controlled,
so never template it into a `when` predicate or a Claude path/mode option
without your own sanitization. Constraint fields
(`values`, `pattern`, `min`/`max`, `minLength`/`maxLength`, `default`)
are **rejected at load** on legacy types — migrate to the lowercase
type. `type: STRING` → `type: string` also lifts the no-spaces rule;
use `pattern` if you relied on it.

A missing required parameter, or a parameter that is present but fails validation (whether or not it is required), causes the request to fail with 400 Bad Request and a JSON error body (`{"msg": "..."}`) whose message names the offending parameter. A missing optional parameter is simply omitted from the environment. The same rules apply to values produced by a jsonPath transformer.

**Body source and an empty request body.** When at least one input has
`src: body`, the run requires `POST`. For an agent **without** an
`inputSchema`, a request that carries no bytes — or only whitespace, such
as a trailing newline from `echo | curl --data-binary @-` — is treated as
`{}`: the body inputs resolve to their `default` (or are omitted when
optional), and a missing **required** body input is still rejected with the
usual `400` naming that input. This lets a caller omit the body entirely —
the console's Run Agent dialog and its Export Curl command both do — so no
`Content-Type` is needed when nothing is sent. A **non-empty** body must
still carry `application/json` (or `application/<subtype>+json`) and be a
JSON object; an agent **with** an `inputSchema` keeps requiring a non-empty
body. The relaxation is POST-only: `GET` on an agent with body inputs keeps
failing with `requires request body; use POST` even when every body input is
optional. See [ADR-0072](../docs/adr/0072-bodyless-post-only-get-stays-tight.md).

### Parameter Transformers

**Pass - Use value as-is:**
```yaml
transformer: !<pass> {}
```

**JsonPath - Extract from JSON parameter:**
```yaml
transformer: !<jsonPath>
  path: "$.id"
```

### Parameter Examples

**Required string parameter:**
```yaml
inputs:
  - paramName: name
    envName: USER_NAME
    type: string
    required: true
```

**Optional number parameter:**
<!-- legacy-syntax-example -->
```yaml
queryParams:  # legacy alias
  - paramName: limit
    envName: RESULT_LIMIT
    type: NUMBER
    required: false
```

**JSON parameter with extraction:**
```yaml
inputs:
  - paramName: config
    envName: API_KEY
    type: string
    required: true
    transformer: !<jsonPath>
      path: "$.apiKey"
```

**Usage:**
```bash
# Required parameter
curl -X POST "http://localhost:8080/api/v1/agent/greet?name=Alice"

# Optional parameter
curl -X POST "http://localhost:8080/api/v1/agent/list?limit=10"

# JSON parameter
curl -X POST "http://localhost:8080/api/v1/agent/setup?config=%7B%22apiKey%22%3A%22xxx%22%7D"
```

**Body input (JSON):**
```yaml
inputs:
  - paramName: user
    envName: USER_NAME
    src: body
    type: string
```
```bash
curl -X POST "http://localhost:8080/api/v1/agent/body-input" \
  -H "Content-Type: application/json" \
  -d '{"user":"alice"}'
```

**Body input with nested extraction:**
```yaml
inputs:
  - paramName: payload
    envName: SERVICE_ID
    src: body
    type: string
    transformer: !<jsonPath>
      path: "$.service.id"
```
```bash
curl -X POST "http://localhost:8080/api/v1/agent/body-input" \
  -H "Content-Type: application/json" \
  -d '{"payload":{"service":{"id":"svc-1"}}}'
```

**Header input:**
```yaml
inputs:
  - paramName: X-Trace-Id
    envName: TRACE_ID
    src: header
  - paramName: X-Api-Token
    envName: API_TOKEN
    src: header
    secret: true
```

<details>
<summary>JSON format</summary>

```json
{
  "inputs": [
    { "paramName": "X-Trace-Id", "envName": "TRACE_ID", "src": "header" },
    { "paramName": "X-Api-Token", "envName": "API_TOKEN", "src": "header", "secret": true }
  ]
}
```
</details>

```bash
curl -X POST "http://localhost:8080/api/v1/agent/header-input" \
  -H "X-Trace-Id: abc123" \
  -H "X-Api-Token: shh"
```

The `API_TOKEN` value is redacted (`***`) in status API responses and Claude
telemetry spans; it still reaches the run's environment and is stored as-is
in the server database (retry / dispatch re-fire need the real value).

Header names are matched case-insensitively (RFC 7230). The following header
names are reserved and rejected at agent-load time: `Authorization`, `Host`,
`Content-Type`, `Content-Length`, `Connection`, `Transfer-Encoding`,
`Upgrade`, `Cookie`, `Set-Cookie`, plus any name starting with `X-Homer-`
or `X-Forwarded-`.

**Typed parameters with constraints:**
```yaml
inputs:
  - paramName: environment
    type: string
    values: [dev, staging, prod]
  - paramName: count
    type: integer
    min: 1
    max: 100
    default: 10
    required: false
  - paramName: ticket
    type: string
    pattern: "^[A-Z]+-[0-9]+$"
    required: false
  - paramName: dryRun
    type: boolean
    default: "false"
    required: false
```

<details>
<summary>JSON format</summary>

```json
{
  "inputs": [
    { "paramName": "environment", "type": "string", "values": ["dev", "staging", "prod"] },
    { "paramName": "count", "type": "integer", "min": 1, "max": 100, "default": "10", "required": false },
    { "paramName": "ticket", "type": "string", "pattern": "^[A-Z]+-[0-9]+$", "required": false },
    { "paramName": "dryRun", "type": "boolean", "default": "false", "required": false }
  ]
}
```
</details>

### Security notes for input authors

Input values come from **untrusted callers**. Homer guarantees they cannot
override reserved environment names (`HMR_*`, `PATH`, …) or escape the
workspace root, but everything an agent *does* with an input is the author's
responsibility:

- **Prompt interpolation.** `{{VAR}}` placeholders in Claude prompts and CLI
  options substitute raw caller input into the prompt. Treat interpolated
  inputs as untrusted content — delimit them (e.g. fenced blocks) and never
  combine free-form inputs with `dangerouslySkipPermissions` unless the run
  is fully sandboxed.
- **Lock keys.** Templating `lockStrategy.key` on an input lets callers choose
  lock collisions. With `KILL_OLD_RUN_CURRENT` that means a caller can kill
  another caller's run; with `WAIT` they can queue behind it. Intentional for
  per-tenant serialization — but make sure the input is trusted to name the
  tenant. Lock keys are **global across agents**, not namespaced per agent
  (this is deliberate — it lets a `RunAgent` flow or several agents share one
  lock). The flip side: two *different* agents whose keys resolve to the same
  string share the lock, so with `KILL_OLD_RUN_CURRENT` a run of one agent can
  kill a colliding run of another. Only agent authors choose key templates, so
  a collision is a configuration choice; keep colliding keys to agents that are
  meant to serialize together.
- **Workspace paths.** Templating `workspace.path` on an input keeps runs
  inside the workspace root, but callers can deliberately share a directory
  with another run of the same agent.
- **RunAgent targets.** Templating a `!<agent>` command's `agentName` on an
  input lets the caller pick which agent runs next — any agent the run's owner
  may run, or that `server.agentGrants` delegates (see
  [Grants and roles](SERVER.md#grants-and-roles)). A denied target fails
  exactly like an unknown one (`agent '<name>' not found`).
- **Claude path/mode options.** Templating a Claude command's `settings`,
  `systemPromptFile`, `appendSystemPromptFile`, `permissionMode`,
  `permissionPromptTool`, `addDir`, `mcpConfig`, `pluginDir`, `pluginUrl`, or
  `file` on an input lets the caller steer configuration, permissions, or
  filesystem access for the run — a `--settings` file can define hooks that run
  commands.
- **LLM CLI binary.** The `cmd` of a `!<claude>`, `!<codex>` or `!<opencode>` command is
  templated too, and templating it on an input would let the caller pick the
  executable Homer runs. This is rejected, not warned: an agent whose `cmd`
  references an input's `envName` fails to load, with an error naming the
  command label and the input. References to agent `env`, `globalEnv` or
  `@SYSENV` values stay allowed.

Homer logs a load-time warning for the lock-key, workspace-path, RunAgent-target
and Claude path/mode patterns.

### Input Schema (payload validation)

An agent may declare an optional `inputSchema` — a standard JSON Schema
(Draft 2020-12) that validates the **entire JSON request body** of
`POST /api/v1/agent/{name}` before any process is spawned. `inputSchema` is
independent of `inputs`: the schema gates the payload shape; `inputs` still
extracts individual fields into env vars.

- A well-formed JSON body that violates the schema is rejected with
  **422 Unprocessable Entity**.
- A missing/empty body (an empty body is accepted, as `{}`, only for an agent
  **without** an `inputSchema` — see *Body source and an empty request body*),
  a Content-Type other than `application/json` or `application/<subtype>+json`
  on a non-empty body, an unparseable body, or a `GET` when a schema is set is
  rejected with **400 Bad Request**.
- A malformed `inputSchema` causes the agent to be **skipped at load** (logged
  as an error), so a broken schema never reaches request handling.
- A `pattern` (or `patternProperties` key) whose shape is prone to catastrophic
  backtracking (ReDoS) — nested unbounded quantifiers such as `(a+)+`, or a
  quantified alternation with overlapping branches such as `(a|ab)*` — also
  **skips the agent at load**. The schema validator matches these author regexes
  against caller-supplied bodies with an unbounded engine, so a vulnerable
  pattern is rejected up front rather than allowed to pin a request. This is a
  conservative heuristic; restructure a flagged pattern to load the agent.

> Agent-level field `inputSchema` (type `object`, optional, default `null`):
> a JSON Schema (Draft 2020-12) validating the JSON request body. A violation
> returns 422; a malformed schema skips the agent at load.

```yaml
name: deploy-webhook
commands:
  - !<cmd>
    cmd: [echo, deploying]
    timeout: 60
inputs:
  - paramName: service
    src: body
    type: string
inputSchema:
  type: object
  required: [service, version]
  properties:
    service:
      type: string
    version:
      type: string
    replicas:
      type: integer
      minimum: 1
  additionalProperties: false
```

<details>
<summary>JSON format</summary>

```json
{
  "name": "deploy-webhook",
  "commands": [
    { "type": "cmd", "cmd": ["echo", "deploying"], "timeout": 60 }
  ],
  "inputs": [
    { "paramName": "service", "src": "body", "type": "string" }
  ],
  "inputSchema": {
    "type": "object",
    "required": ["service", "version"],
    "properties": {
      "service": { "type": "string" },
      "version": { "type": "string" },
      "replicas": { "type": "integer", "minimum": 1 }
    },
    "additionalProperties": false
  }
}
```
</details>

### MCP Exposure

When the server's MCP endpoint is enabled (`server.mcp.enabled`, see
[SERVER.md](SERVER.md#mcp-endpoint)), an agent with `mcp: { enabled: true }`
is exposed as an MCP tool on `/mcp`. The tool's name is the agent's `name`,
its description is the agent's `description`, and its input schema is derived
from the agent definition:

- Each `inputs` entry with `src: query` or `src: body` becomes a top-level
  tool argument (`STRING`/`ANY`/`string` → `"string"`, `NUMBER`/`number` →
  `"number"`, `integer` → `"integer"`, `boolean` → `"boolean"`; typed
  constraints such as `values`, `pattern` and `min`/`max` carry over; required
  per the param's `required` flag). On a `secret: true` input the `default`
  and `values` constraints do **not** carry over — they are dropped from the
  tool schema exactly as from the `GET /api/v1/agents` summary (see the
  `secret` field row above).
- `src: header` params are **never** exposed over MCP — headers carry
  authentication material, and an LLM client must not be prompted to supply
  them. An agent with a required header input is not exposed over MCP at all
  (see below); optional header params are simply omitted from the tool.
- `inputSchema` properties and `required` entries are merged into the tool
  schema (body payload shape).
- A reserved optional `_wait` argument (number, seconds) is injected: by
  default the tool returns `{runId, status}` immediately; with `_wait` it
  blocks up to that many seconds (clamped to `server.mcp.maxWaitSec`) and
  returns the final status, cost, and artifact list.

Agents are skipped from MCP (with a server-side warning) when their name
doesn't match `^[a-zA-Z0-9_-]{1,128}$`, when a param is named `_wait`, or when
they declare a required `src: header` input.

```yaml
name: mcp-example
description: Greets the caller; exposed as an MCP tool
commands:
  - !<script>
    content: echo "Hello $NAME"
inputs:
  - paramName: name
    envName: NAME
    src: query
    type: string
    required: true
mcp:
  enabled: true
```

<details>
<summary>JSON format</summary>

```json
{
  "name": "mcp-example",
  "description": "Greets the caller; exposed as an MCP tool",
  "commands": [
    { "type": "script", "content": "echo \"Hello $NAME\"" }
  ],
  "inputs": [
    { "paramName": "name", "envName": "NAME", "src": "query", "type": "string", "required": true }
  ],
  "mcp": {
    "enabled": true
  }
}
```
</details>

## Workspace Configuration

The `workspace` field configures the isolated workspace for agent execution. Each agent defines its own workspace path template, allowing for flexible organization within the global `workspaceRootPath` configured in the server settings:

```yaml
workspace:
  path: "{{HMR_PROCESS_ID}}"
  cleanAfterFinish: true
```

<details>
<summary>JSON format</summary>

```json
{
  "workspace": {
    "path": "{{HMR_PROCESS_ID}}",
    "cleanAfterFinish": true
  }
}
```
</details>

### Workspace Fields

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `path` | `String` | No | `{{HMR_PROCESS_ID}}` | Workspace directory template |
| `cleanAfterFinish` | `Boolean` | No | `true` | Delete `workdir/` and `.homer/` after completion; `artifacts/` is kept (it goes with `purgeAfter`). Skipped while another live run shares the path. See [Workspace Cleanup](#workspace-cleanup). |
| `purgeAfter` | `String` | No | `5d` | Delete the workspace directory this long after the run ends (e.g. `2h`, `7d`). The record dies with it unless `retain` extends it. |
| `retain` | `Retain?` | No | `null` | What outlives `purgeAfter` — see below. |
| `retain.recordFor` | `String` | **Yes** (inside `retain`) | — | How long to keep the run's **record** — status, steps, exit codes, cost — after `purgeAfter` has removed its directory. Must be `>=` `purgeAfter`. Bounded by `server.maxProcesses`: at the cap, the oldest terminal row is evicted regardless. **The record includes the run's input values, secret-declared ones included**: those are masked in API responses but stored as given, so this is also how long a secret input stays in the database. See [ADR-0048](../docs/adr/0048-split-record-retention-from-workspace-purge.md) and [ADR-0053](../docs/adr/0053-declared-artifacts-ride-the-record-lifetime.md). |
| `retain.artifacts` | `List<String>` | No | `[]` | Globs relative to the artifacts dir; matching files stay in place, served by the same artifact routes, until the record dies. Per-run cap `server.maxRetainedArtifactBytes`; overflow keeps what fits in glob order and sets `retainedTruncated` on the status API. An artifacts dir over 10,000 entries retains nothing (flagged the same way). Retention is skipped (WARN) for a workspace another run's record still references. Requires a per-run `workspace.path` (must contain `{{HMR_PROCESS_ID}}`); rejected at agent load otherwise. Each glob must be non-blank, relative, use forward slashes only, contain no `..`, and parse as a glob, or the agent fails to load. |

> **Migrating from `purgeRecordAfter`:** the `1.17.0` top-level
> `workspace.purgeRecordAfter` field is now `workspace.retain.recordFor`. A
> config still setting the old key is rejected at agent load with a pointer to
> the new one. See [ADR-0053](../docs/adr/0053-declared-artifacts-ride-the-record-lifetime.md).

### Workspace Cleanup

Homer provides three levels of workspace cleanup:

| Field              | Deletes          | When                                    | Default |
|--------------------|------------------|-----------------------------------------|---------|
| `cleanAfterFinish` | `workdir/` and `.homer/`, unless another still-live run (CREATED/WORKING) references the shared path | Immediately after the process reaches a terminal state, for at most two minutes; what is left is deleted by the `purgeAfter` sweep | `true` |
| `purgeAfter`       | Entire `projectRoot/` (workdir + artifacts) + the process record (SQLite row), unless `retain` extends it; with a fixed workspace path, the directory is only deleted when the last record referencing it is purged | After specified duration | `5d` |
| `retain`           | record + matching artifact files | until `recordFor` after the run | not set |

**`purgeAfter` format:** `<number><unit>` where unit is:
- `m` — minutes (e.g. `30m`)
- `h` — hours (e.g. `2h`)
- `d` — days (e.g. `7d`)
- `w` — weeks (e.g. `1w`)

When multiple runs share a workspace (fixed `path`), purging an expired run removes only its process record; the shared directory is deleted once no other run references it.

End-of-run `cleanAfterFinish` cleanup applies the same guard: on a fixed `path` it skips deleting `workdir/` and `.homer/` while another run on the same directory is still live (CREATED or WORKING), so a finishing run cannot remove helper binaries or `mcp.json` out from under a sibling that is still executing them. The check reads the same live-run reference the `purgeAfter` sweep uses; a run whose `path` is unique per run is unaffected and still cleans both directories. The delete itself is time-bounded: a workspace too large to remove in two minutes is left partially deleted for the `purgeAfter` sweep, so a big workspace cannot hold the run's lock and parallel-run slot. See [ADR-0048](../docs/adr/0048-split-record-retention-from-workspace-purge.md).

**Timer starts** when the process enters a terminal state (FINISHED, KILLED, FAILED). A run
interrupted by a server restart (`UNKNOWN`) starts its timers when the restarted server
reconciles it, using the agent's `workspace` settings at that moment (the defaults above if
the agent no longer exists).

**Examples:**
```yaml
workspace:
  cleanAfterFinish: false  # Keep artifacts for review
  purgeAfter: 2h           # Auto-delete everything after 2 hours

workspace:
  cleanAfterFinish: true   # Clean workdir + .homer immediately
  purgeAfter: 7d           # Remove artifacts + process record after 7 days

workspace:
  cleanAfterFinish: true
  purgeAfter: 2h             # workspace (workdir + artifacts) dies after 2 hours
  retain:
    recordFor: 90d           # the run's record survives 90 days
    artifacts: ["decision.json", "summary.md"]   # and so do these files
```

### Workspace Path Templates

The `path` supports variable substitution:

```yaml
# One workspace per process (default)
path: "{{HMR_PROCESS_ID}}"

# Organize by agent (via a custom env var on the agent — HMR_AGENT_NAME
# itself is not usable here; see below)
path: "{{AGENT_LABEL}}/{{HMR_PROCESS_ID}}"

# Include parameters
path: "{{USER_ID}}/{{TASK_TYPE}}/{{HMR_PROCESS_ID}}"
```

The resolved `path` is confined to the server's `workspaceRootPath`. A
template that resolves outside it — including one whose value comes from a
query parameter (e.g. `path: "{{dir}}"` with `?dir=../../etc`) — is
rejected and the run fails. Any `..` in the `path` template itself (even
inside a name such as `v1..2`) is rejected at agent load, before any run
starts. A `path` that resolves to the workspace root itself (e.g. an empty
path, or a template whose variables all resolve to empty) is rejected at run
start — the workspace must be a strict subdirectory of the
root, never the root. The resolved `path` also must not traverse a symbolic
link — a symlink at any component below the root is rejected at run start,
so a run cannot be aliased into another run's directory.

### Workspace Environment Variables

Homer provides built-in environment variables for workspace management:

| Variable | Description | Example |
|----------|-------------|---------|
| `HMR_WORK_DIR` | Working directory | `/workspaces/42/workdir` |
| `HMR_ARTIFACTS_DIR` | Artifacts directory | `/workspaces/42/artifacts` |
| `HMR_PROCESS_ID` | Process identifier (integer, unique per run) | `42` |
| `HMR_AGENT_NAME` | Name of the agent being executed | `deploy_service` |
| `HMR_AGENT_DIR` | Absolute path to the agent's own config directory (the one holding its `agent.yaml` / `agent.json`); use it to read files shipped alongside the definition, e.g. `cat "$HMR_AGENT_DIR/prompt.txt"` | `/srv/homer/agents/deploy_service` |
| `HMR_SERVER_ID` | Identifier of the Homer instance running this process (random UUID, regenerated on every server start) | `3f2b6c1e-8a4d-4c1b-9e2f-7d5a0b6c9e11` |
| `HMR_API_URL` | Base URL of the Homer HTTP API, used by the `.homer/bin` helpers (`homer-ask`, `homer-tag`, `homer-dispatch`, `homer-join`, `homer-result`, `homer-inbox`), the question MCP config, and remote-runner agents to call back into Homer (`RunAgent` dispatches in-process and does not use it). The ssh, kubernetes and external runners inject it from their `homerApiUrl`; the local runner defaults it to `http://127.0.0.1:{server.port}` when not already set | `http://homer:8080` |
| `HMR_CALLBACK_TOKEN` | Per-process random token authorizing this process's callback routes (`/api/v1/callback/*`: questions, tags, dispatches, runs, joins, results, messages) — never a real operator credential. Read by `homer-ask` and the pre-generated MCP config; see [Asking the operator questions](#asking-the-operator-questions) | (random per process) |
| `HMR_MCP_CONFIG` | Absolute path to the pre-generated `.homer/mcp.json` wiring Claude to Homer's question MCP tools. Only set when `server.mcp.enabled` is `true`; reference it via `mcpConfig: "{{HMR_MCP_CONFIG}}"` | `/workspaces/42/.homer/mcp.json` |
| `HMR_INVOCATION_CHAIN` | Comma-separated chain of agent names that led to the current invocation (`""` at the root call, `"parent"` at depth 1, `"root,child"` at depth 2). Threaded from parent to child by `RunAgent`'s in-process dispatch for recursion cycle/depth enforcement | `orchestrator,deploy-service` |
| `HMR_PARENT_PROCESS_ID` | Process id of the run that caused this one — set for runs started by `RunAgent`, `homer-dispatch` / ask-and-dispatch, a continuation, or an HTTP call carrying `X-Homer-Parent-Process-Id`; a retry or resume keeps the source run's parent. **Unset** at the root of a flow (a plain API call, cron, MCP). The same id is served as `parentProcessId` on the status API, so an agent no longer has to forward its own `HMR_PROCESS_ID` as an input to be traceable | `12345` |
| `HMR_ROOT_PROCESS_ID` | Process id of the **first** run in this run's flow — the root of the parent chain, inherited by every run the flow spawns. **Unset** at the root run itself. The same id is served as `rootProcessId` on the status API; `GET /api/v1/status/all?rootProcessId=<id>` lists every run below the flow root, and `?roots=true` lists one row per flow | `12345` |

### Variables visible to agent commands

The Homer host JVM's environment is **not** silently inherited. Each
agent command sees the union of:

1. Host env keys explicitly allowed by server config
   (`parentEnvAllowlist`, default `PATH`/`HOME`/`LANG`/`TZ`) —
   **local runs only**: on a remote runner (`ssh`, `kubernetes`, `external`) the
   allowlist layer is absent entirely, because those values describe
   Homer's host, not the target's (see SERVER.md's
   [`parentEnvAllowlist` → "Remote runners"](SERVER.md#parentenvallowlist)).
2. `globalEnv` from `homer-config.yaml` (after `@SYSENV:` resolution).
3. The runner's own `env:` map (ssh/kubernetes runners and external domains; after
   `@SYSENV:` resolution) — the per-host override point for values the
   target machine needs.
4. The agent's `env:` block (after `@SYSENV:` resolution).
5. Caller-supplied inputs on `POST /api/v1/agent/{name}` — sourced from URL query (`src: query`), JSON body (`src: body`), or HTTP headers (`src: header`).
6. Homer-injected `HMR_*` keys (process/agent identity — see the
   system env-vars section).

Each later layer overrides the previous on key collision. Anything
outside these layers — e.g. a stray credential the operator exported
to Homer's shell — never reaches agent commands. To deliberately
forward a host secret, declare it explicitly (typically via
`@SYSENV:VAR_NAME` in `globalEnv` so the secret stays out of the
config file). See
[ADR-0065](../docs/adr/0065-remote-runner-env-composition.md) for the
remote-runner composition contract.

### Workspace Structure

Each workspace contains:
```
{workspaceRootPath}/{path}/
  ├── workdir/           # HMR_WORK_DIR - working directory for commands
  ├── artifacts/         # HMR_ARTIFACTS_DIR - for storing outputs
  └── .homer/            # Homer-managed tooling: bin/homer-ask, bin/homer-dispatch, bin/homer-result, bin/homer-join, bin/homer-tag, bin/homer-inbox, mcp.json
```

### Workspace Examples

**Default workspace:**
```yaml
workspace: {}
# Uses default path: {{HMR_PROCESS_ID}}
# cleanAfterFinish: true
# purgeAfter: 5d
```

**Auto-cleanup workspace:**
```yaml
workspace:
  cleanAfterFinish: true
```

**Organized workspace:**
```yaml
workspace:
  path: "analysis/{{INPUT_FILE}}/{{HMR_PROCESS_ID}}"
  cleanAfterFinish: false
```

**Complete example:**
```yaml
name: file-processor
inputs:
  - paramName: file
    envName: INPUT_FILE
    type: string
    pattern: "^[A-Za-z0-9._-]+$"
commands:
  - !<script>
    content: cp "$INPUT_FILE" "$HMR_WORK_DIR/"
  - !<script>
    content: process.sh "$HMR_WORK_DIR/$INPUT_FILE" > "$HMR_ARTIFACTS_DIR/result.txt"
workspace:
  path: "processing/{{INPUT_FILE}}/{{HMR_PROCESS_ID}}"
  cleanAfterFinish: true
```

**Retained artifacts:**
```yaml
name: decision-maker
commands:
  - !<script>
    content: decide.sh > "$HMR_ARTIFACTS_DIR/decision.json"
workspace:
  cleanAfterFinish: true
  purgeAfter: 2h
  retain:
    recordFor: 90d
    artifacts: ["decision.json", "summary.md"]
```

<details>
<summary>JSON format</summary>

```json
{
  "name": "decision-maker",
  "commands": [
    { "type": "script", "content": "decide.sh > \"$HMR_ARTIFACTS_DIR/decision.json\"" }
  ],
  "workspace": {
    "cleanAfterFinish": true,
    "purgeAfter": "2h",
    "retain": {
      "recordFor": "90d",
      "artifacts": ["decision.json", "summary.md"]
    }
  }
}
```
</details>

## Parallel-Run Limiting

`maxParallelRuns` caps how many executions of an agent run concurrently.
`onMaxParallelRuns` chooses what happens once that cap is reached: `WAIT`
(the default) queues the surplus run until a slot frees, while `REJECT`
fails it immediately — the start is still accepted (`200` with a
`processId`) and the run ends `FAILED` without executing.

Under `WAIT`, the queue is unbounded by default — a surplus run waits
indefinitely, and each waiting run pins a process record. Set
`maxQueuedRuns` to bound that queue: once the given number of runs are
already waiting, the next one ends `FAILED` immediately (its start still
returns `200` with a `processId`) instead of piling up.
`maxQueuedRuns` has no effect under `REJECT` (which never queues).

```yaml
maxParallelRuns: 5        # at most 5 concurrent runs
onMaxParallelRuns: WAIT   # queue the 6th and beyond
maxQueuedRuns: 50         # ...but fail the 56th once 50 are queued
```

## Lock Strategy

The `lockStrategy` field controls concurrency and prevents conflicting executions:

```yaml
lockStrategy:
  key: "analysis-{{INPUT_FILE}}"
  onCollision: WAIT
```

<details>
<summary>JSON format</summary>

```json
{
  "lockStrategy": {
    "key": "analysis-{{INPUT_FILE}}",
    "onCollision": "WAIT"
  }
}
```
</details>

### Lock Fields

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `key` | `String` | No | `{{HMR_PROCESS_ID}}` | Lock identifier template (supports variable substitution). With the default, every process has its own unique lock, so no two processes ever collide. |
| `onCollision` | `LockCollision` | No | `WAIT` | Behavior when lock is already held |
| `acceptParentLock` | `Boolean` | No | `false` | When `true` and this run's resolved key is already held by an ancestor in its invocation chain (a `RunAgent` flow), skip lock acquisition entirely — the flow does not self-collide on a shared key. Parallel-run limits (`maxParallelRuns`) still apply. Has no effect on top-level runs or when the key does not match an ancestor's. |

### Lock Collision Strategies

| Strategy | Behavior | Use Case |
|----------|----------|----------|
| `KILL_OLD_RUN_CURRENT` | Kill existing process, run new one | Latest execution wins |
| `SKIP_CURRENT` | Skip new execution, keep existing | First execution wins |
| `WAIT` | Wait for lock release, then run | Sequential processing |

### Lock Examples

**Prevent concurrent processing of the same file:**
```yaml
lockStrategy:
  key: "process-{{INPUT_FILE}}"
  onCollision: SKIP_CURRENT
```

**Always use latest execution:**
```yaml
lockStrategy:
  key: "deploy-{{ENVIRONMENT}}"
  onCollision: KILL_OLD_RUN_CURRENT
```

**Sequential processing:**
```yaml
lockStrategy:
  key: "database-migration"
  onCollision: WAIT
```

**Per-user locking:**
```yaml
lockStrategy:
  key: "user-task-{{USER_ID}}"
  onCollision: WAIT
```

**One lock for a whole `RunAgent` flow (no self-collision):**

Give every agent in the flow the same key and set `acceptParentLock: true`
on the agents invoked from within the flow. A sub-agent whose resolved key
matches an ancestor's passes straight through instead of waiting for /
skipping / killing the lock its own flow already holds. Independent
top-level invocations of the flow still contend on the key per `onCollision`.

```yaml
lockStrategy:
  key: "order-{{ORDER_ID}}"
  onCollision: WAIT
  acceptParentLock: true
```

## Cooldown

Homer bounds how many runs happen at once (`lockStrategy`, `maxParallelRuns`)
and how much they may spend (`costLimits`). `cooldown` is the third bound: how
*often* an agent may start.

It exists because the cheap failures are the dangerous ones. A run that dies
before it reaches Claude costs nothing and holds no lock by the time the next
cron tick fires, so a fast-failing agent on a one-minute schedule re-runs
against identical state indefinitely — repeating whatever side effect each
attempt has (a comment on an MR, a message in a channel) every single time.

```yaml
name: mr-watcher
cron:
  expression: "* * * * *"
cooldown:
  afterFailure: 15m     # a healthy watcher still runs every minute
commands:
  - !<script>
    content: check-for-mentions.sh
```

<details>
<summary>JSON format</summary>

```json
{
  "name": "mr-watcher",
  "cron": { "expression": "* * * * *" },
  "cooldown": { "afterFailure": "15m" },
  "commands": [
    { "type": "script", "content": "check-for-mentions.sh" }
  ]
}
```
</details>

### Cooldown Fields

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `minInterval` | `String?` | No | `null` | Minimum time since the previous run of this agent **started**. Spaces out every run, healthy or not. |
| `afterFailure` | `String?` | No | `null` | Minimum time since the previous run **ended**, applied only when that run `FAILED`. Backs off a failing agent without slowing down a healthy one. |

Durations use the same grammar as `workspace.purgeAfter`: an integer plus
`m` / `h` / `d` / `w` (there is no seconds unit). At least one field must be
set — an empty `cooldown: {}` is a load error rather than a silent no-op.

Notes on behaviour:

- **Covered:** every start path — `POST/GET /api/v1/agent/{name}`, cron, MCP
  tools, `!<agent>`, dispatch and continuation fires, and the retry, resume, and
  debug endpoints. The authoritative check runs in the same transaction that
  creates the run's record, so a refused start launches nothing; an HTTP start
  (retry, resume, and debug included) returns `429`. The admin
  `POST /api/v1/agents/{name}/cron-run` behaves like a cron tick instead: the
  refusal is only logged and the response is still `204`. A refused `!<agent>`
  step fails with exit code `255`, an MCP start returns an error tool result,
  and a refused ask-and-dispatch fire ends the dispatch `FAILED` — the
  operator's answer is consumed and the fire is not retried (unlike a
  continuation, below).
- **`minInterval` binds every start; `afterFailure` does not bind a manual one.**
  The retry, resume, and debug endpoints ignore `afterFailure`, because a person
  unblocking a failed run is the way *out* of a failure backoff, not the
  automated repeat that window exists to stop. They still honour `minInterval`,
  so a second start inside it is refused with `429`. Every other start path is
  fully gated by both windows. See
  [ADR-0051](../docs/adr/0051-cooldown-is-enforced-in-the-insert-transaction.md).
- **A continuation refused by a cooldown is retried, but not forever.** Like
  the other momentary refusals it goes back to pending and is re-fired after a
  delay of 60 s, doubling up to 3600 s. After 8 refused attempts in total
  (about two hours) it is marked `FAILED` ("gave up after N attempts"); a TTL,
  if set, can expire it sooner. A cooldown window longer than that loses the
  resume. See [ADR-0023](../docs/adr/0023-completion-continuations.md).
- **The window is measured from the process table**, so it survives a restart —
  unlike the scheduler's in-memory last-run map, which only ever fed the status
  report. It resets when the relevant rows are gone: purged at the record purge
  (`workspace.retain.recordFor`, else `workspace.purgeAfter`) or evicted early
  when the server reaches `server.maxProcesses`.

## Telemetry

Every run produces an OpenTelemetry trace: a root `agent_request` span with the
process, command, and (for `!<claude>`, `!<codex>` and `!<opencode>`) generation spans
beneath it. Where those
traces go is a server setting (`openTelemetryExport` in
[SERVER.md](SERVER.md#opentelemetry-export)); what a *particular agent* puts in
them is the `telemetry` block. See
[ADR-0050](../docs/adr/0050-per-agent-telemetry-block.md).

```yaml
name: mr-watcher
telemetry:
  export: ALL              # default; OFF mutes this agent's traces entirely
tags:
  fromInputs: [project, mr]  # resolved-input values become trace tags AND run tags
inputs:
  - paramName: project
    envName: PROJECT
    type: string
  - paramName: mr
    envName: MR_IID
    type: integer
commands:
  - !<script>
    content: check-for-mentions.sh
```

<details>
<summary>JSON format</summary>

```json
{
  "name": "mr-watcher",
  "telemetry": { "export": "ALL" },
  "tags": { "fromInputs": ["project", "mr"] },
  "inputs": [
    { "paramName": "project", "envName": "PROJECT", "type": "string" },
    { "paramName": "mr", "envName": "MR_IID", "type": "integer" }
  ],
  "commands": [
    { "type": "script", "content": "check-for-mentions.sh" }
  ]
}
```
</details>

### Telemetry Fields

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `export` | `ALL` \| `OFF` | No | `ALL` | Whether this agent's runs reach the collector at all. |
| `traceTags` | list of strings | No | `[]` | **Deprecated** — alias for [`tags.fromInputs`](#run-tags), kept one release. Same meaning: `paramName`s of declared inputs whose resolved values become `paramName:value` trace tags. Using it logs a WARN at agent load; migrate to `tags.fromInputs`. Setting both is accepted (not rejected): the two lists are unioned and de-duplicated, and the WARN still fires. |

> **`traceTags` is deprecated.** It moved out of `telemetry` and became the
> top-level [`tags.fromInputs`](#run-tags), because the same `paramName:value`
> strings now do double duty: they tag the Langfuse trace **and** the run
> itself, so operators can filter runs by subject with `?tag=`. `traceTags`
> stays as an alias for one release; the console and API surface the tags
> regardless of which field you used.

### `export: OFF` — muting an agent

`export: OFF` mutes the whole trace, not just the root span: the run's spans and
every descendant are dropped, so a once-a-minute agent that usually has nothing
to do stops burying the runs you care about. Use it for pollers and watchers
whose steady state is a no-op. It applies to every start path, retry, resume and
debug included.

Two consequences worth knowing before you set it:

- It is **all or nothing per agent**. There is no "export only the interesting
  runs": OTel decides sampling when the trace *starts*, and at that moment a
  run that will turn out interesting is indistinguishable from one that will
  not. If a watcher's own interesting runs matter to you, leave it `ALL` and
  narrow with `tags.fromInputs` and the query-side filters instead.
- A muted run has **no Langfuse trace link** on its process detail
  (`langfuseTraceUrl` is null): there is no exported trace to link to. The run
  itself, its status, its artifacts, and its cost are unaffected — muting
  changes telemetry only.

Agents that dispatch work to other agents keep full visibility either way: a
dispatched run is a separate trace under *its own* agent's setting.

## Run tags

A run can carry `0..n` **subject tags** — `key:value` strings describing *what
it ran over*: `mr:15`, `projectId:4711`, `decision:approved`. Tags are
returned on every run's status (`tags` on `ProcessInfoResponse`), rendered as
clickable chips in the console, and — the point of the feature — let an operator
find runs by their subject:

```
GET /api/v1/status/all?tag=mr:15&tag=projectId:4711
```

Repeated `?tag=` params are **ANDed** (a run must carry every one to match).
Filtering never reveals a run the caller could not otherwise see and never
returns `403` — a run you may not see is simply absent from the page, exactly as
if the tag did not match. See [ADR-0060](../docs/adr/0060-run-tags.md).

A tag has two possible origins, both declared in the top-level `tags` block:

```yaml
name: mr-watcher
tags:
  fromInputs: [projectId, mr]   # resolved from these inputs at start
  runtime: [decision]           # keys the run may declare later via homer-tag
inputs:
  - paramName: projectId
    envName: PROJECT_ID
    type: integer
  - paramName: mr
    envName: MR_IID
    type: integer
commands:
  - !<script>
    content: triage.sh
```

<details>
<summary>JSON format</summary>

```json
{
  "name": "mr-watcher",
  "tags": {
    "fromInputs": ["projectId", "mr"],
    "runtime": ["decision"]
  },
  "inputs": [
    { "paramName": "projectId", "envName": "PROJECT_ID", "type": "integer" },
    { "paramName": "mr", "envName": "MR_IID", "type": "integer" }
  ],
  "commands": [
    { "type": "script", "content": "triage.sh" }
  ]
}
```
</details>

### Tags Fields

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `fromInputs` | list of strings | No | `[]` | `paramName`s of declared inputs whose resolved values become `paramName:value` tags at start. Each named `paramName` must match `^[A-Za-z0-9._-]{1,64}$` — it becomes the tag key — or the agent fails to load. Same list, same derivation, and same secret-exclusion as trace tags — a `fromInputs` value is also emitted as a Langfuse trace tag. Replaces the deprecated [`telemetry.traceTags`](#telemetry-fields). |
| `runtime` | list of strings | No | `[]` | Tag **keys** the running agent is allowed to declare mid-run via [`homer-tag`](#declaring-a-tag-at-run-time-homer-tag). A key not listed here is rejected. Must be disjoint from `fromInputs` — a key in both fails agent load. Each key matches `^[A-Za-z0-9._-]{1,64}$`. |

### `fromInputs` — tagging from the run's inputs

A run started with `?projectId=4711&mr=4211` against the agent above carries
`projectId:4711` and `mr:4211` from the moment it is created — the values are
resolved in the same INSERT that creates the run's row, so the tag is visible the
instant the run is. Rules worth knowing:

- **Every name must match a declared input, must not name a `secret` one, and
  the input's `paramName` must fit the key charset `^[A-Za-z0-9._-]{1,64}$`.**
  All three are checked at agent load and skip the agent with an error — a renamed
  input has to fail loudly rather than silently stop tagging, a tag would leave a
  secret value in the clear, undoing the redaction `secret` asked for, and an
  off-charset or over-long name would mint a tag key no `?tag=` query can match.
  An input that was *ever* declared secret for a run stays excluded even if the
  definition later flips it back.
- **A blank or absent value adds no tag**, and a value over **128 characters is
  skipped with a WARN rather than truncated** — a truncated tag matches no exact
  filter, so it would be worse than absent. Values must match
  `^[A-Za-z0-9._-]{1,128}$`; a value with other characters is dropped with a WARN.
- **Path-shaped subjects are dropped — tag them by numeric id instead.** The charset
  has no `/`, so a `group/repo` project path does *not* pass and is dropped with a
  WARN (this keeps a value from needing URL-encoding in a `?tag=` query). Tag the
  numeric project id (`projectId:4711`) — bounded, stable across renames, and what a
  forge API caller already has.
- **Tag *cardinality* is not bounded, only length.** Every distinct value is a
  distinct tag in your collector and a distinct filter value. Tag things with a
  bounded value set — ticket ids, MR iids, numeric project ids, thread ids — never
  free text, a timestamp, or a per-run UUID.

### Declaring a tag at run time: `homer-tag`

When the subject a run cares about is only known once it is running — a decision
it reached, a resource it discovered — the agent declares it with the `homer-tag`
helper, materialized in every workspace at `{projectRoot}/.homer/bin/homer-tag`
(on `PATH` when the workspace env defines one):

```sh
homer-tag decision:approved
```

The helper takes a single `key:value` argument (split on the first `:`). The
**key must be listed in `tags.runtime`**; declaring a key the agent did not
allow is rejected with `400`. The helper authenticates with the run's own
`HMR_CALLBACK_TOKEN` (`POST /api/v1/callback/tags`) — the token *is* the run
identity, so there is no run id to pass and one run can never tag another. A run
may declare at most **20** runtime tags; past that the call is refused. Re-declaring
the same `key:value` is idempotent (the set collapses). Values obey the same
`^[A-Za-z0-9._-]{1,128}$` charset as `fromInputs`, and — symmetrically with the
`fromInputs` secret exclusion — a value that **contains** any secret Homer knows
for the run is refused with a `400`: a `secret` input's value, a `globalEnv` /
`env` value sourced via `@SYSENV:`, or the run's own `HMR_CALLBACK_TOKEN`. The
match is the same substring redactor that masks telemetry, with the same
8-character floor the artifact-name screen uses (a shorter secret would match
nearly every value and cannot be protected by matching anyway). A refused call
is logged with the run and key, never the value. On read, a tag containing a
value that is secret *now* — including an input flipped to `secret: true` after
the run — is omitted from the status API. An encoded or split secret is not
detected; see [ADR-0060](../docs/adr/0060-run-tags.md) for why that is accepted.

### Things worth knowing

- **Child runs do not inherit their parent's tags.** A dispatched or continued
  run is tagged only by its *own* `fromInputs`/`runtime`. To find a whole flow,
  filter by the parent instead: `GET /api/v1/status/all?parentProcessId=<id>`.
- **Tag keys and values are strings.** A numeric input like `mr:4211` is the
  string `"4211"`; `?tag=mr:4211` matches it exactly, there is no numeric range
  matching.
- **At most 10 `?tag=` filters** may be combined on one request; more is a `400`.

## Cron Scheduling

Agents can be scheduled to run automatically using standard UNIX cron expressions. Manual triggering via the HTTP API always works alongside cron scheduling.

### Cron Configuration

```yaml
cron:
  expression: "0 9 * * 1-5"   # weekdays at 9am
  timezone: "UTC"              # optional, defaults to UTC
```

### Cron Expression Format

Standard 5-field UNIX cron syntax:

```
┌───────────── minute (0–59)
│ ┌───────────── hour (0–23)
│ │ ┌───────────── day of month (1–31)
│ │ │ ┌───────────── month (1–12)
│ │ │ │ ┌───────────── day of week (0–7, Sunday=0 or 7)
│ │ │ │ │
* * * * *
```

### Cron Fields

| Field | Required | Values | Special Characters |
|-------|----------|--------|--------------------|
| minute | Yes | 0–59 | `*` `,` `-` `/` |
| hour | Yes | 0–23 | `*` `,` `-` `/` |
| day of month | Yes | 1–31 | `*` `,` `-` `/` |
| month | Yes | 1–12 or `JAN`–`DEC` | `*` `,` `-` `/` |
| day of week | Yes | 0–7 (0 and 7 are Sunday) or `SUN`–`SAT` | `*` `,` `-` `/` |

### Cron Examples

```yaml
# Every minute
cron:
  expression: "* * * * *"

# Every day at midnight UTC
cron:
  expression: "0 0 * * *"

# Weekdays at 9am in Warsaw time
cron:
  expression: "0 9 * * 1-5"
  timezone: "Europe/Warsaw"

# Every hour at minute 30
cron:
  expression: "30 * * * *"

# Every Monday at 8am UTC
cron:
  expression: "0 8 * * 1"

# First day of every month at noon
cron:
  expression: "0 12 1 * *"
```

### Timezone Support

The `timezone` field accepts any IANA timezone name (e.g., `"UTC"`, `"America/New_York"`, `"Europe/Warsaw"`). Defaults to `"UTC"` if omitted.

A malformed `expression` or an unknown `timezone` is rejected at agent load — the agent is skipped with an error in the reload result, rather than failing later on a scheduler tick.

### Agents API Cron Fields

Scheduled agents include cron status in the `GET /api/v1/agents` response:

```json
{
  "name": "my-scheduled-agent",
  "scriptCount": 2,
  "cron": {
    "expression": "0 9 * * 1-5",
    "timezone": "UTC",
    "nextRunAt": 1700000000,
    "lastRunAt": 1699913600,
    "lastRunProcessId": 42
  }
}
```

- **`nextRunAt`**: Unix epoch seconds for the next scheduled execution
- **`lastRunAt`**: Unix epoch seconds of the last cron-triggered execution
- **`lastRunProcessId`**: Process ID of the last cron-triggered execution (for status/artifact lookup)

Non-scheduled agents return `"cron": null`.

### Notes

- Cron passes no caller inputs: a cron run sees the agent's `env` block and the `default` of each declared input. An agent with a `required` input that has no `default` (or an `inputSchema` `required` field) cannot satisfy a scheduled run, so its schedule is refused at load — the reason appears in the reload result's `errors` and the agent loads with no active schedule, still runnable by hand via `POST /api/v1/agent/{name}` with the input supplied
- Cron runs are owned by `system:cron`
- Overlapping ticks are not skipped: a tick fires even while the previous cron run is still going — overlap is governed by `lockStrategy` and `maxParallelRuns`
- Manual HTTP triggering works normally alongside any cron schedule
- Every tick is aligned to a wall-clock minute boundary, not just the first: each tick waits until the top of the next minute, so a tick whose work takes less than a minute cannot push later ticks off the minute or skip a minute
- Cron state (lastRunAt, lastRunProcessId) resets on server restart

## Environment Variables

Agents have access to multiple sources of environment variables, with a clear precedence order:

### Environment Variable Precedence

Layers are composed from lowest to highest precedence — each later layer
overrides earlier ones on key collision. The full ordering matches what
[`WorkspaceConfigFactory`](app/src/main/kotlin/homer/app/infra/WorkspaceConfigFactory.kt)
builds and what [`AgentExecutor`](app/src/main/kotlin/homer/app/executors/AgentExecutor.kt)
appends per command:

1. **Allowlisted host env** (lowest) — host JVM keys explicitly named in
   `parentEnvAllowlist` (defaults: `PATH`, `HOME`, `LANG`, `TZ`). The
   Homer host environment is otherwise **not** inherited. Local runs
   only: a remote runner (`ssh`, `kubernetes`, `external`) starts from an empty
   layer, because host values describe Homer's machine, not the
   target's — see SERVER.md's
   [`parentEnvAllowlist` → "Remote runners"](SERVER.md#parentenvallowlist).
2. **`globalEnv`** — from `AppConfig` (after `@SYSENV:` resolution).
   `HMR_SERVER_ID` is mixed in here at startup.
3. **Runner `env`** — the runner config's own `env:` map
   (`runners.<alias>.env`, ssh/kubernetes runners and external domains only; after
   `@SYSENV:` resolution). The per-host override point: a value here
   describes *this* runner's host or image (`PATH` with
   `/opt/homebrew/bin` on a macOS ssh box, `JAVA_HOME`), and it
   overrides the same key from `globalEnv`.
4. **Agent `env`** — the agent definition's `env:` block (after
   `@SYSENV:` resolution).
5. **Inputs** — caller-supplied parameters on
   `POST /api/v1/agent/{name}`, validated/transformed per `inputs`
   (or legacy `queryParams`). Source per param: `query` (URL query
   string), `body` (JSON body), or `header` (HTTP header).
6. **`HMR_PROCESS_ID`, `HMR_INVOCATION_CHAIN`, `HMR_PARENT_PROCESS_ID`
   (when the run has a parent), `HMR_ROOT_PROCESS_ID`
   (when the run is not a flow root), `HMR_ARTIFACTS_DIR`, `HMR_WORK_DIR`** —
   injected by `WorkspaceConfigFactory`.
7. **Run-start env** — appended just before commands run, in this order:
   process-scoped env (`HMR_CALLBACK_TOKEN`; `HMR_MCP_CONFIG` when
   `server.mcp.enabled`; the `.homer/bin` prefix on `PATH` when `PATH` is
   set), then the runner session's env (e.g. `HMR_API_URL`) and its remote
   path overrides, then `HMR_AGENT_NAME`.
8. **`resultEnvParam`** (highest) — values extracted from previous
   commands are appended after each step, so they override anything
   below for subsequent commands.

A consequence worth knowing: a query parameter named `HMR_PROCESS_ID`
cannot override the real process ID — the `HMR_*` injection happens
above query params.

Because `HMR_AGENT_NAME` (layer 7) is appended after the workspace path is
already resolved (layer 6), `{{HMR_AGENT_NAME}}` is **not** usable in
`workspace.path` (or `lockStrategy.key`) templates — it stays a literal,
unsubstituted placeholder there.

### Variable Substitution

Use `{{VAR_NAME}}` syntax to substitute variables in:
- Claude/Codex/opencode `prompt` text and `options` values (including Langfuse prompt text)
- the `cmd` of a `!<claude>` / `!<codex>` / `!<opencode>` command (every workspace-env variable resolves there — `HMR_*`, `globalEnv`, the runner's `env`, local-runner `parentEnvAllowlist` values; a declared-input reference fails load)
- `!<agent>` `agentName`
- `workspace.path`
- `lockStrategy.key`
- `artifactNameTemplate`

`{{VAR}}` is **not** substituted in a `!<cmd>` `cmd` or a `!<script>`
`content` — both are passed to the process verbatim. Commands read variables
from the environment instead: a `!<script>` uses `"$VAR"`; a `!<cmd>` has no
shell, so it cannot expand variables at all (use a `!<script>`, or
`sh -c '...'`).

**Example:**
```yaml
env:
  API_ENDPOINT: "https://api.example.com"

commands:
  - !<script>
    content: curl "$API_ENDPOINT/users/$USER_ID"
```

### Referencing System Environment Variables

Values in the agent `env` map can reference host system environment variables using `@SYSENV:VAR_NAME`.
Resolution happens at agent load time — missing variables cause the agent to fail loading with a clear error.

**Example:**
```yaml
env:
  DB_PASSWORD: "@SYSENV:DATABASE_PASSWORD"
  SERVICE_KEY: "@SYSENV:MY_SERVICE_KEY"
```

### Built-in Variables

| Variable | Description |
|----------|-------------|
| `HMR_PROCESS_ID` | Unique process identifier |
| `HMR_WORK_DIR` | Process working directory |
| `HMR_ARTIFACTS_DIR` | Process artifacts directory |
| `HMR_AGENT_NAME` | Name of the agent being executed |
| `HMR_AGENT_DIR` | Absolute path to the agent's own config directory (the one holding its `agent.yaml` / `agent.json`) |
| `HMR_SERVER_ID` | Identifier of the Homer instance running this process |
| `HMR_API_URL` | Base URL of the Homer HTTP API (the ssh, kubernetes and external runners inject it from their `homerApiUrl`; the local runner defaults it to `http://127.0.0.1:{server.port}`) |
| `HMR_CALLBACK_TOKEN` | Per-process token authorizing this process's callback routes: questions (see [Asking the operator questions](#asking-the-operator-questions)), `homer-tag`, `homer-dispatch`/`homer-join`, `homer-result`, and `homer-inbox` (see [Messages from the operator](#messages-from-the-operator)) |
| `HMR_MCP_CONFIG` | Path to the pre-generated question-tools MCP config; set only when `server.mcp.enabled` is `true` |
| `HMR_INVOCATION_CHAIN` | Comma-separated chain of agent names that led to the current invocation (`""` at the root, `"parent"` at depth 1, `"root,child"` at depth 2, …). Threaded from parent to child by `RunAgent`'s in-process dispatch and used for recursion cycle/depth enforcement. |
| `HMR_PARENT_PROCESS_ID` | Process id of the run that caused this one (see [Workspace Environment Variables](#workspace-environment-variables)). **Unset** at the root of a flow. |
| `HMR_ROOT_PROCESS_ID` | Process id of the first run in this run's flow (see [Workspace Environment Variables](#workspace-environment-variables)). **Unset** at the root run itself. |

### Template-only Variables

Unlike the table above, these are **not** exported into any command's process
env — they exist only for `artifactNameTemplate` rendering, resolved fresh for
each command and each retry attempt:

| Variable | Description |
|----------|-------------|
| `HMR_CMD_INDEX` | Zero-based index of the current command (what is baked into the default `cmd_N` name). |
| `HMR_CMD_ATTEMPT` | Zero-based retry attempt within the command. Always `0` for `Cmd`/`Script`/`RunAgent`/other command types; increments across a `!<claude>`, `!<codex>` or `!<opencode>` command's `retry` attempts and keeps counting across its `escalate` tiers. |

`HMR_LLM_OUTCOME` and `HMR_LLM_EXIT_CODE` are likewise never exported into a
command's process env: they exist only for an `escalate[].when` predicate (see
[Claude Escalation](#claude-escalation)).

Secret-classified variables (declared `secret: true` inputs, `@SYSENV:`-sourced
globals, `HMR_CALLBACK_TOKEN`) are **not available** in `artifactNameTemplate`
rendering, and any rendered name that would still contain a secret value
(e.g. one copied into another variable via `resultEnvParam`) fails the command
instead of writing it to a file name.

### Environment Variable Examples

**Global + Agent + Parameter:**
```yaml
# In AppConfig
globalEnv:
  LOG_LEVEL: "info"

# In agent
env:
  API_ENDPOINT: "https://api.example.com"

inputs:
  - paramName: userId
    envName: USER_ID
    type: string

commands:
  - !<script>
    content: |
      echo "Log: $LOG_LEVEL, API: $API_ENDPOINT, User: $USER_ID"
```

**Result passing between commands:**
```yaml
commands:
  - !<cmd>
    cmd: "curl https://api.example.com/token"
    resultEnvParam:
      - src: STD_OUT
        envName: AUTH_TOKEN
        transformer: !<jsonPath>
          path: "$.token"

  - !<script>
    content: |
      curl -H "Authorization: Bearer $AUTH_TOKEN" https://api.example.com/data
```

## Asking the operator questions

Any running command can ask the human operator a question mid-run and wait
for the answer — approvals, choices, missing information. Question text is
Markdown (rendered in the console, where the operator answers); optional
predefined `options` render as buttons, but the operator can always type a
custom answer. The answer comes back to the command verbatim.

Questions authenticate with the per-process `HMR_CALLBACK_TOKEN` — they can
only ever read and ask questions belonging to their own process. A question
lives as long as its run: when the run reaches a terminal state, open
questions expire (waiters resume with status `EXPIRED`). A server restart
marks interrupted runs `UNKNOWN` and expires their open questions; the
callback token is not restored, so a blocked helper exits with an error —
re-trigger the run. Questions registered by `ask-and-dispatch` are the
exception: they outlive the asking run.

### From shell commands: `homer-ask`

Every workspace gets a `homer-ask` helper materialized at
`{projectRoot}/.homer/bin/homer-ask` and prepended to `PATH` when the
workspace env defines one (when it does not — `PATH` excluded from
`parentEnvAllowlist` — invoke it by its absolute path; needs `curl`
and `awk`):

```bash
# Ask and block until answered (most common):
ANSWER=$(homer-ask ask-and-wait "Deploy **v2.1** to production?" --option yes --option no)

# Give up after 10 minutes; resume later with `check`:
homer-ask ask-and-wait "Proceed?" --ttl 600

# Fire-and-forget + poll:
ID=$(homer-ask ask "Which environment?")
homer-ask check "$ID" --wait 55
```

Exit codes: `0` answered (answer on stdout), `2` usage or transport error,
`3` still open / TTL lapsed (the question id stays valid — `check` again),
`4` expired.

### From Claude commands: MCP question tools

When `server.mcp.enabled` is `true`, Homer pre-generates a
`.homer/mcp.json` exposing the question tools and points `HMR_MCP_CONFIG`
at it. Wiring is explicit — reference it from the Claude command's
`mcpConfig`:

```yaml
name: release-approver
commands:
  - !<claude>
    cmd: "claude"
    prompt: !<prompt>
      prompt: |
        Prepare the release notes, then use the ask_user_and_wait tool to
        get the operator's approval before tagging.
    options:
      mcpConfig: "{{HMR_MCP_CONFIG}}"
      allowedTools:
        - "mcp__homer-questions__ask_user"
        - "mcp__homer-questions__check_answer"
        - "mcp__homer-questions__ask_user_and_wait"
      disallowedTools:
        - "AskUserQuestion"
```

> **Tip:** reference the tools by their full `mcp__homer-questions__*`
> names in prompts and disallow the built-in `AskUserQuestion` tool —
> a model may otherwise reach for Claude Code's own question tool,
> which has no operator on the other end in headless runs.

The `homer-questions` MCP server exposes five tools:

| Tool | Behavior |
|------|----------|
| `ask_user` | Asks without blocking; returns the question id immediately |
| `check_answer` | Checks (or briefly waits, `waitSec` up to 50) for the answer to an earlier question |
| `ask_user_and_wait` | Asks and blocks until answered, up to `ttlSec` (default and cap: `server.questions.maxWaitSec`) |
| `ask_and_dispatch` | Asks and registers a dispatch without blocking; returns `dispatchId` and `questionId` (see [Ask and dispatch](#ask-and-dispatch)) |
| `check_messages` | Takes the messages the operator sent this run (see [Messages from the operator](#messages-from-the-operator)) |

Waits are resumable: if a blocking call times out or the connection drops,
the question is not lost — repeated `check_answer` calls converge on the
answer. The tool results instruct the model accordingly.

### Ask and dispatch

`ask-and-dispatch` registers a question together with a target agent. When
the operator answers, Homer starts that agent as a fresh, independent run
with the answer injected as a query parameter (default name `answer`, or
whatever `--answer-param` specifies) alongside any `--param` inputs. The
subcommand returns immediately — the dispatch and question survive the
asking process reaching its terminal state.

```sh
homer-ask ask-and-dispatch "Approve deploy? Which region?" \
    --agent deploy_handler \
    --option us-east --option eu-west \
    --param service=checkout \
    --answer-param region
```

The **target agent must opt in** as a dispatch target by setting
`dispatch: { enabled: true }` in its own definition. Registering a dispatch
against an agent that exists but has not opted in is rejected with `403`. A
target the run's hop is not authorized to reach answers `404`, exactly like an
unknown agent, so it confirms nothing about what exists:

```yaml
name: deploy_handler
dispatch:
  enabled: true            # Opt in as a dispatch target (default: false)
inputs:
  - paramName: region
    envName: REGION
    type: string
  - paramName: service
    envName: SERVICE
    type: string
commands:
  - !<script>
    content: deploy.sh "$SERVICE" "$REGION"
```

`--param` values and the answer reach the target only through `inputs` it
declares with a matching `paramName`; an undeclared name is dropped, and a
`required` input the call cannot cover is rejected with `400` at registration.

Output: two lines — `dispatchId` (integer) then `questionId` (string).

Flags:

| Flag | Required | Default | Description |
|------|----------|---------|-------------|
| `--agent NAME` | yes | — | Agent to start when the operator answers |
| `--answer-param NAME` | no | `answer` | Query-param name the answer is injected under. Cannot be a reserved env name (`PATH`, `HMR_*`, …) other than the LangGraph control markers — rejected with `400` |
| `--param key=value` | no | — | Additional query params passed to the agent run (repeatable) |
| `--option TEXT` | no | — | Predefined answer option rendered as a button (repeatable) |
| `--ttl SEC` | no | `server.dispatch.defaultTtlSec` (unset = no expiry) | Seconds before the question expires unanswered |

The dispatched agent run is independent: like any run it gets its own
freshly-minted `HMR_CALLBACK_TOKEN` and does not inherit the asking
process's.

The MCP equivalent is the `ask_and_dispatch` tool (args: `text`, `agent`,
`answerParam`, `options`, `params`, `ttlSec`) exposed by the
`homer-questions` MCP server alongside `ask_user`, `check_answer`,
`ask_user_and_wait`, and `check_messages`.

### Dispatching another agent

`homer-dispatch` starts another agent **immediately**, without asking the
operator anything — like `ask-and-dispatch` minus the question. Every
workspace gets the helper materialized at
`{projectRoot}/.homer/bin/homer-dispatch` (on `PATH` when the workspace env
defines one). Use it when a command needs to kick off another agent with a
target name and inputs computed at run time:

```sh
pid=$(homer-dispatch --agent deploy_handler --param service=checkout --param region=us-east)
echo "started run $pid"
```

The run inherits the caller's owner and records the caller as its parent
(`HMR_PARENT_PROCESS_ID` in its env, `parentProcessId` on the status API),
plus the *referer* — the asking process id and agent name — on its telemetry
span. Otherwise it is decoupled: a fresh invocation chain (no cycle/depth
linkage back to the caller) and no operator question. It is fire-and-forget:
the calling command does not block on it; the helper prints the new process
id and returns.

The **target agent must opt in** the same way as `ask-and-dispatch`, by
setting `dispatch: { enabled: true }` in its own definition; dispatching to an
agent that has not opted in is rejected with `403`, and one the run's hop may
not reach with `404`, identical to an unknown agent. The dispatched run is
independent: like any run it gets its own freshly-minted `HMR_CALLBACK_TOKEN`
and does not inherit the caller's.

Output: one line — the new process id (integer).

Flags:

| Flag | Required | Default | Description |
|------|----------|---------|-------------|
| `--agent NAME` | yes | — | Agent to start immediately |
| `--param key=value` | no | — | Query param passed to the agent run (repeatable) |
| `--continue-agent NAME` | no | — | Register a completion continuation: start this agent once the dispatched sub reaches a terminal state. Must also opt in with `dispatch: { enabled: true }` |
| `--continue-thread ID` | no | — | Thread id passed to the continuation run under `HMR_LG_THREAD` (requires `--continue-agent`) |
| `--continue-param key=value` | no | — | Param passed to the continuation run, not the sub (repeatable; requires `--continue-agent`). Use for state the *resumed parent* needs — e.g. the LangGraph interrupt id; `--param` would land on the sub and be lost |
| `--resume-param NAME` | no | `HMR_LG_RESUME` | Param name the continuation run receives the sub's result payload under (requires `--continue-agent`). With the default `HMR_LG_RESUME` (a LangGraph control marker) the payload lands in the continuation run's env directly; any other name is delivered through the continuation agent's declared input with that `paramName`. Cannot be a reserved env name (`PATH`, `HMR_*`, …) other than the LangGraph control markers — rejected with `400` |
| `--result-jsonpath PATH` | no | — | JsonPath applied to the sub's stdout; the extracted value becomes the `result` field of the resume payload delivered via `--resume-param`. Without it, `result` is `null` (requires `--continue-agent`) |
| `--ttl SECONDS` | no | `server.dispatch.defaultTtlSec` (unset = no expiry) | Continuation expiry; if the sub never finishes within this window the continuation is swept (requires `--continue-agent`) |

#### Continuing when the sub finishes

With `--continue-agent`, the dispatch also registers a *completion
continuation*: when the dispatched sub-process reaches any terminal state,
Homer fires `--continue-agent`, passing a resume payload (`{ status,
processId, result }`) under `--resume-param`, the thread id under
`HMR_LG_THREAD`, and any `--continue-param` values. This lets a parked workflow
fan out to a sub-agent and wake itself when the sub completes. Note the param
split: `--param` go to the dispatched sub, `--continue-param` go to the
continuation run (the resumed parent). The continuation outlives both the
caller and the sub. See [ADR-0023](../docs/adr/0023-completion-continuations.md).

```sh
# Start a sub and resume the planner thread with the sub's `.summary` field.
homer-dispatch --agent research_worker --param topic=pricing \
  --continue-agent planner --continue-thread plan-42 \
  --result-jsonpath '$.summary' --ttl 3600
```

#### Large results: `resultRef` and `homer-result`

The resume payload's `result` is delivered inline only when it is within the
inline cap (64 KiB). When the sub's stdout exceeds that, the payload arrives
with `result: null` and an additional `resultRef` object instead:

```json
{ "status": "FINISHED", "processId": 99,
  "resultRef": { "processId": 99, "path": "cmd_0.out", "token": "…", "sizeBytes": 1234567 } }
```

Fetch the full (transformed) result by reference with the `homer-result`
helper, materialized at `{projectRoot}/.homer/bin/homer-result`:

```sh
homer-result --token "$TOKEN"            # writes the result body to stdout
homer-result --token "$TOKEN" --out r.txt  # or to a file
```

The token is **reusable within its TTL** (`server.dispatch.resultTokenTtlSec`,
default 3600 s), scoped to that one sub-process's artifact, and audience-bound:
only a process running the continuation's target agent can redeem it — any
other caller gets `403` ("Result token not valid for this agent"). The same
`--result-jsonpath` transform that would have applied to an inline result is
applied server-side at fetch. Results larger than 4 MiB cannot be transformed
server-side (a transform request answers `413`), so when a transform is set
Homer mints no `resultRef` for them and the payload arrives with `result: null`
and no ref — typically a sub that failed on a Claude step, whose stdout is the
whole NDJSON event stream. See
[ADR-0025](../docs/adr/0025-capability-token-result-read.md). The Python
`homer_langgraph` runtime fetches automatically — `homer_fanout` returns a
payload with `result` already populated.

#### Parallel fan-out: `homer-join`

`homer-dispatch --continue-agent` registers a continuation per sub: a graph
that fans out to N subs in parallel resolves them one resume per sub — N
round-trips. `homer-join` instead starts **all N subs at once** and registers a
single continuation that resumes `continueAgent` **once**, after the last sub
reaches a terminal state, carrying every sub's result keyed by its interrupt
id. Wall-clock becomes ≈ the slowest sub instead of the sum. See
[ADR-0026](../docs/adr/0026-join-continuations.md).

Because the request body has a nested `subs` array (awkward to build in POSIX
sh), `homer-join` reads the JSON body from **stdin** rather than taking flags.
The helper is materialized at `{projectRoot}/.homer/bin/homer-join`:

```sh
homer-join <<'JSON'
{ "subs": [ { "agent": "architect", "params": { "topic": "auth" }, "interruptId": "int-a" },
            { "agent": "architect", "params": { "topic": "billing" }, "interruptId": "int-b" } ],
  "continueAgent": "graph_runner", "continueThread": "thread-1",
  "continueParams": { "HMR_LG_INTERRUPT": "int-a,int-b" },
  "resumeParam": "HMR_LG_RESUME", "ttlSec": 600 }
JSON
```

It prints `{ "processIds": [...], "continuationId": N }`. The resume payload
delivered under `resumeParam` is a JSON **map** `{ interruptId: per_sub_payload }`,
where each `per_sub_payload` is byte-for-byte the single-fanout payload
(`{ status, processId, result }`, or `result: null` + `resultRef` over the
inline cap). Every agent involved must opt in with `dispatch.enabled`; the
runtime drives this helper automatically for grouped `homer_fanout` calls.

### Limits

`server.questions` configures the cap on concurrently open questions per
process (`maxOpenPerProcess`, default 10) and the maximum wait
(`maxWaitSec`, default 86400 = 1 day) — see
[SERVER.md](SERVER.md). The `agentdebug` CLI runs agents without the Homer
server, so there the helper is absent and these variables are unset.

## Messages from the operator

An operator can send messages to a running run — from its page in the
console, or with `POST /api/v1/status/{processId}/messages` (see
[README.md](README.md)) — to correct its course without killing it. A
message waits in the run's inbox until a step takes it: the run decides
when, through a hook, an MCP tool or the `homer-inbox` helper. Taking a
message marks it `DELIVERED` (read, not necessarily acted on), and each
message is delivered once. Messages still `QUEUED` when the run ends become
`UNDELIVERED`, so a run that never reads its inbox behaves exactly as it
would without one.

The inbox belongs to the run, not to a step: a message sent while a step
that does not read the inbox runs is taken by a later step that does.
Reading authenticates with the per-process `HMR_CALLBACK_TOKEN`, so a run
only ever reads its own inbox — a run started by `!<agent>`, a dispatch or
a continuation has its own token and its own inbox. `server.messages`
limits the text length and the number of messages per run (see
[SERVER.md](SERVER.md)).

### From shell commands: `homer-inbox`

Every workspace gets a `homer-inbox` helper at
`{projectRoot}/.homer/bin/homer-inbox`, on `PATH` like `homer-ask` (needs
`curl` and `sed`). It takes every queued message, oldest first, and prints
them as JSON; the list is empty when nothing was queued:

```bash
homer-inbox
# {"messages": [{"id": "6f1c…", "text": "Use the staging cluster, not prod.", "createdAt": 1761580000}]}
# (pretty-printed over several lines)
```

Exit codes: `0` ok, `2` on any usage, transport or HTTP error (e.g. `409`
once the run has ended; the reason is on stderr).

### From Claude commands: hooks

Claude Code hooks deliver the messages without the model having to ask.
`homer-inbox --hook <event>` prints the hook output Homer formats for that
event when messages were queued, and nothing otherwise:

| Hook | `homer-inbox` | Effect |
|------|---------------|--------|
| `PostToolUse` | `--hook post-tool-use` | After each tool call, the queued messages are added to the model's context next to the tool result (`additionalContext`). |
| `Stop` | `--hook stop` | When the model is about to finish, queued messages block the stop and reach it as the reason to continue (`decision: "block"`). With nothing queued, it stops as usual, so the hook cannot keep the model running on its own; Claude Code also caps consecutive Stop-hook continuations: it sets `stop_hook_active` on a continued stop and ends the turn after 8 continuations in a row. |

In hook mode the helper never reads stdin, and on any failure it writes
`homer-inbox: <reason>` to stderr and exits `1` — a non-blocking hook error
that Claude Code reports and moves past — never `2`, which Claude Code
would treat as a blocking decision. Claude Code saves hook output over
10,000 characters to a file and gives the model a preview, so keep long
instructions out of messages.

Ship a settings file beside the agent definition, e.g.
`inbox-settings.json`:

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "*",
        "hooks": [{ "type": "command", "command": "homer-inbox --hook post-tool-use" }]
      }
    ],
    "Stop": [
      {
        "hooks": [{ "type": "command", "command": "homer-inbox --hook stop" }]
      }
    ]
  }
}
```

and point the Claude command's `settings` at it (`HMR_AGENT_DIR` is the
agent's own directory):

```yaml
name: library-migration
commands:
  - !<claude>
    cmd: "claude"
    prompt: !<prompt>
      prompt: |
        Migrate the service to the new HTTP client library. The operator may
        send you messages while you work; follow them.
    options:
      settings: "{{HMR_AGENT_DIR}}/inbox-settings.json"
```

The hook command runs with the Claude process's environment, so it finds
`homer-inbox` on `PATH` and its `HMR_API_URL` and `HMR_CALLBACK_TOKEN`; this
needs the workspace env to define `PATH`, as for `homer-ask` above.

### From Claude commands: the `check_messages` MCP tool

For a harness without hooks, the `homer-questions` MCP server (see
[From Claude commands: MCP question tools](#from-claude-commands-mcp-question-tools),
`server.mcp.enabled`) has a `check_messages` tool. It takes no arguments,
takes the queued messages like `homer-inbox`, and returns them as text — or
`No messages from the operator.` Allow `mcp__homer-questions__check_messages`
and tell the model in the prompt when to call it, e.g. between steps of
long work.

## Complete Agent Examples

### Simple Agent - Single Command

```yaml
name: hello
description: Simple greeting agent

commands:
  - !<script>
    content: echo "Hello, $NAME!"

inputs:
  - paramName: name
    envName: NAME
    type: string
    required: true
```

<details>
<summary>JSON format</summary>

```json
{
  "name": "hello",
  "description": "Simple greeting agent",
  "commands": [
    {
      "type": "script",
      "content": "echo \"Hello, $NAME!\""
    }
  ],
  "inputs": [
    {
      "paramName": "name",
      "envName": "NAME",
      "type": "string",
      "required": true
    }
  ]
}
```
</details>

**Usage:**
```bash
curl -X POST "http://localhost:8080/api/v1/agent/hello?name=World"
```

### Multi-Command Agent with Result Extraction

```yaml
name: github-user-info
description: Fetch GitHub user information

commands:
  - !<script>
    content: curl -s "https://api.github.com/users/$USERNAME"
    resultEnvParam:
      - src: STD_OUT
        envName: USER_DATA
        transformer: !<pass> {}

  - !<script>
    content: printf '%s' "$USER_DATA" | jq -r '.name'
    resultEnvParam:
      - src: STD_OUT
        envName: USER_NAME
        transformer: !<pass> {}

  - !<script>
    content: printf '%s' "$USER_DATA" | jq -r '.public_repos'
    resultEnvParam:
      - src: STD_OUT
        envName: REPO_COUNT
        transformer: !<pass> {}

  - !<script>
    content: echo "User $USER_NAME has $REPO_COUNT public repositories"

inputs:
  - paramName: username
    envName: USERNAME
    type: string
    required: true

workspace:
  cleanAfterFinish: true
```

<details>
<summary>JSON format</summary>

```json
{
  "name": "github-user-info",
  "description": "Fetch GitHub user information",
  "commands": [
    {
      "type": "script",
      "content": "curl -s \"https://api.github.com/users/$USERNAME\"",
      "resultEnvParam": [
        {
          "src": "STD_OUT",
          "envName": "USER_DATA",
          "transformer": {
            "type": "pass"
          }
        }
      ]
    },
    {
      "type": "script",
      "content": "printf '%s' \"$USER_DATA\" | jq -r '.name'",
      "resultEnvParam": [
        {
          "src": "STD_OUT",
          "envName": "USER_NAME",
          "transformer": {
            "type": "pass"
          }
        }
      ]
    },
    {
      "type": "script",
      "content": "printf '%s' \"$USER_DATA\" | jq -r '.public_repos'",
      "resultEnvParam": [
        {
          "src": "STD_OUT",
          "envName": "REPO_COUNT",
          "transformer": {
            "type": "pass"
          }
        }
      ]
    },
    {
      "type": "script",
      "content": "echo \"User $USER_NAME has $REPO_COUNT public repositories\""
    }
  ],
  "inputs": [
    {
      "paramName": "username",
      "envName": "USERNAME",
      "type": "string",
      "required": true
    }
  ],
  "workspace": {
    "cleanAfterFinish": true
  }
}
```
</details>

**Usage:**
```bash
curl -X POST "http://localhost:8080/api/v1/agent/github-user-info?username=octocat"
```

### Claude AI Agent with Langfuse Prompt

```yaml
name: code-review
description: AI-powered code review using Claude

commands:
  - !<script>
    content: git diff "$BASE_BRANCH..HEAD" > "$HMR_ARTIFACTS_DIR/diff.txt"

  - !<claude>
    cmd: "claude"
    prompt: !<langfuse>
      promptName: "code-review-prompt"
      version: 2
    options:
      model: sonnet
      agents:
        code-reviewer:
          prompt: "You review pull requests for clarity, bugs, and style."
      allowedTools:
        - Read
        - Grep
        - Glob
    timeout: 600
    retry:
      attempts: 3
    resultEnvParam:
      - src: STD_OUT
        envName: REVIEW_RESULT
        transformer: !<jsonPath>
          path: "$[?(@.type=='result')].result"

  - !<script>
    content: printf '%s' "$REVIEW_RESULT" | jq -r '.[0]' > "$HMR_ARTIFACTS_DIR/review.md"

inputs:
  - paramName: baseBranch
    envName: BASE_BRANCH
    type: string
    required: false

env:
  BASE_BRANCH: "main"

workspace:
  path: "code-review/{{HMR_PROCESS_ID}}"
  cleanAfterFinish: false

lockStrategy:
  key: "code-review"
  onCollision: SKIP_CURRENT

# Each claude retry attempt gets its own artifact instead of overwriting the
# previous attempt's cmd_1.out/.err — a failed attempt stays inspectable.
artifactNameTemplate: "cmd_{{HMR_CMD_INDEX}}_a{{HMR_CMD_ATTEMPT}}"
```

<details>
<summary>JSON format</summary>

```json
{
  "name": "code-review",
  "description": "AI-powered code review using Claude",
  "commands": [
    {
      "type": "script",
      "content": "git diff \"$BASE_BRANCH..HEAD\" > \"$HMR_ARTIFACTS_DIR/diff.txt\""
    },
    {
      "type": "claude",
      "cmd": "claude",
      "prompt": {
        "type": "langfuse",
        "promptName": "code-review-prompt",
        "version": 2
      },
      "options": {
        "model": "sonnet",
        "agents": {
          "code-reviewer": {
            "prompt": "You review pull requests for clarity, bugs, and style."
          }
        },
        "allowedTools": ["Read", "Grep", "Glob"]
      },
      "timeout": 600,
      "retry": {
        "attempts": 3
      },
      "resultEnvParam": [
        {
          "src": "STD_OUT",
          "envName": "REVIEW_RESULT",
          "transformer": {
            "type": "jsonPath",
            "path": "$[?(@.type=='result')].result"
          }
        }
      ]
    },
    {
      "type": "script",
      "content": "printf '%s' \"$REVIEW_RESULT\" | jq -r '.[0]' > \"$HMR_ARTIFACTS_DIR/review.md\""
    }
  ],
  "inputs": [
    {
      "paramName": "baseBranch",
      "envName": "BASE_BRANCH",
      "type": "string",
      "required": false
    }
  ],
  "env": {
    "BASE_BRANCH": "main"
  },
  "workspace": {
    "path": "code-review/{{HMR_PROCESS_ID}}",
    "cleanAfterFinish": false
  },
  "lockStrategy": {
    "key": "code-review",
    "onCollision": "SKIP_CURRENT"
  },
  "artifactNameTemplate": "cmd_{{HMR_CMD_INDEX}}_a{{HMR_CMD_ATTEMPT}}"
}
```
</details>

**Usage:**
```bash
curl -X POST "http://localhost:8080/api/v1/agent/code-review"
curl -X POST "http://localhost:8080/api/v1/agent/code-review?baseBranch=develop"
```

### Advanced Agent with Transformations

```yaml
name: api-data-processor
description: Fetch, transform, and process API data

commands:
  - !<script>
    content: curl -s "$API_ENDPOINT/data"
    resultEnvParam:
      - src: STD_OUT
        envName: API_RESPONSE
        transformer: !<pass> {}

  - !<script>
    content: |
      #!/bin/bash
      set -e
      printf '%s' "$API_RESPONSE" | jq -c '.items[]' | while read item; do
        id=$(echo "$item" | jq -r '.id')
        echo "Processing item $id"
      done
    shell: bash
    resultEnvParam:
      - src: STD_OUT
        envName: PROCESSING_LOG
        transformer: !<pass> {}

  - !<script>
    content: printf '%s' "$API_RESPONSE" | jq '.summary.total'
    resultEnvParam:
      - src: STD_OUT
        envName: TOTAL_COUNT
        transformer: !<jsonPath>
          path: "$"

  - !<script>
    content: echo "Processed $TOTAL_COUNT items" > "$HMR_ARTIFACTS_DIR/summary.txt"

inputs:
  - paramName: endpoint
    envName: API_ENDPOINT
    type: string
    required: true

  - paramName: filter
    envName: FILTER
    type: string
    required: false
    transformer: !<jsonPath>
      path: "$.category"

workspace:
  path: "api-processing/{{HMR_PROCESS_ID}}"
  cleanAfterFinish: true

lockStrategy:
  key: "api-processor-{{API_ENDPOINT}}"
  onCollision: WAIT
```

<details>
<summary>JSON format</summary>

```json
{
  "name": "api-data-processor",
  "description": "Fetch, transform, and process API data",
  "commands": [
    {
      "type": "script",
      "content": "curl -s \"$API_ENDPOINT/data\"",
      "resultEnvParam": [
        {
          "src": "STD_OUT",
          "envName": "API_RESPONSE",
          "transformer": {
            "type": "pass"
          }
        }
      ]
    },
    {
      "type": "script",
      "content": "#!/bin/bash\nset -e\nprintf '%s' \"$API_RESPONSE\" | jq -c '.items[]' | while read item; do\n  id=$(echo \"$item\" | jq -r '.id')\n  echo \"Processing item $id\"\ndone",
      "shell": "bash",
      "resultEnvParam": [
        {
          "src": "STD_OUT",
          "envName": "PROCESSING_LOG",
          "transformer": {
            "type": "pass"
          }
        }
      ]
    },
    {
      "type": "script",
      "content": "printf '%s' \"$API_RESPONSE\" | jq '.summary.total'",
      "resultEnvParam": [
        {
          "src": "STD_OUT",
          "envName": "TOTAL_COUNT",
          "transformer": {
            "type": "jsonPath",
            "path": "$"
          }
        }
      ]
    },
    {
      "type": "script",
      "content": "echo \"Processed $TOTAL_COUNT items\" > \"$HMR_ARTIFACTS_DIR/summary.txt\""
    }
  ],
  "inputs": [
    {
      "paramName": "endpoint",
      "envName": "API_ENDPOINT",
      "type": "string",
      "required": true
    },
    {
      "paramName": "filter",
      "envName": "FILTER",
      "type": "string",
      "required": false,
      "transformer": {
        "type": "jsonPath",
        "path": "$.category"
      }
    }
  ],
  "workspace": {
    "path": "api-processing/{{HMR_PROCESS_ID}}",
    "cleanAfterFinish": true
  },
  "lockStrategy": {
    "key": "api-processor-{{API_ENDPOINT}}",
    "onCollision": "WAIT"
  }
}
```
</details>

**Usage:**
```bash
curl -X POST "http://localhost:8080/api/v1/agent/api-data-processor?endpoint=https://api.example.com"
curl -X POST "http://localhost:8080/api/v1/agent/api-data-processor?endpoint=https://api.example.com&filter=%7B%22category%22%3A%22tech%22%7D"
```

## Best Practices

### Error Handling

**Fail fast by default:**
```yaml
commands:
  - !<cmd>
    cmd: "critical-operation"
    continueIfFail: false  # Default behavior
```

**Allow non-critical failures:**
```yaml
commands:
  - !<cmd>
    cmd: "optional-cleanup"
    continueIfFail: true
```

**Check error output:**
```yaml
commands:
  - !<cmd>
    cmd: "risky-operation"
    resultEnvParam:
      - src: STD_ERR
        envName: ERROR_MSG
        transformer: !<pass> {}
```

**Early exit on a known condition (`finishExitCode`):**

Use `finishExitCode` when a command can return a specific non-zero exit code that means
"nothing to do" — the agent should stop cleanly rather than fail or continue.

```yaml
commands:
  - !<cmd>
    cmd: "check_if_deployed.sh"
    finishExitCode: 127   # 127 = already deployed, nothing to do
    continueIfFail: false
  - !<cmd>
    cmd: "deploy.sh"      # Only runs if check_if_deployed.sh returned != 127
```

Behavior rules:
- When the command exits with `finishExitCode` **and** `continueIfFail: false` → agent finishes successfully, remaining commands are skipped.
- When `continueIfFail: true` → `finishExitCode` has no effect; execution always continues to the next command regardless of exit code.

### Timeout Configuration

**Short timeout for quick operations:**
```yaml
- !<cmd>
  cmd: "git status"
  timeout: 10  # 10 seconds
```

**Long timeout for heavy operations:**
```yaml
- !<cmd>
  cmd: "npm install"
  timeout: 300  # 5 minutes
```

**Very long timeout for AI:**
```yaml
- !<claude>
  prompt: !<prompt>
    prompt: "Review the diff"
  options: {}
  timeout: 1800  # 30 minutes
```

### Lock Strategy Selection

**Use KILL_OLD_RUN_CURRENT for:**
- Deployments (latest version wins)
- Status updates (latest state wins)
- Real-time data processing

**Use SKIP_CURRENT for:**
- Singleton tasks (only one instance ever)
- Resource-intensive operations (avoid overload)
- Already-running long tasks

**Use WAIT for:**
- Sequential processing (maintain order)
- Shared resource access (prevent conflicts)
- Database migrations (ensure completion)

### Workspace Cleanup

**Enable cleanup for:**
- Short-lived data processing
- Temporary analysis tasks
- Development/testing

**Disable cleanup for:**
- Persistent artifacts needed later
- Debugging (preserve state)
- Auditing (keep execution records)

**Use `retain` for:**
- Ticket-linked results that must keep downloading after the workspace is gone
- A long-lived audit record paired with only the few artifacts that matter
- Compliance retention windows longer than the workspace itself needs to live

### Security Considerations

1. **Validate Input**: Use typed parameters with constraints (`values`, `pattern`, `min`/`max`, `minLength`/`maxLength`) and required flags. The legacy `type: ANY` performs no validation at all
2. **Limit Tool Access**: Use `disallowedTools` / `tools` in Claude options (`allowedTools` only pre-approves; it restricts nothing once permissions are skipped)
3. **Review Permission Bypasses**: Audit `permissionMode` and `dangerouslySkipPermissions` in Claude options, `sandbox` / `dangerouslyBypassApprovalsAndSandbox` in Codex options, and `auto` in opencode options — some values grant unrestricted tool and filesystem access
4. **Secure Credentials**: Never hardcode credentials in agents (use environment variables). Telemetry masks `secret: true` *inputs*, every `globalEnv` / `env` value sourced from the host via `@SYSENV:`, and `HMR_CALLBACK_TOKEN` — but not a `globalEnv` / `env` entry whose value is a literal, so route credentials through `@SYSENV:` rather than pasting them in
5. **Control Concurrency**: Use lock strategies to prevent conflicts
6. **Isolate Workspaces**: Use unique workspace paths per execution
7. **Audit Agent Definitions**: Agents execute with full permissions — review carefully before deploying

## See Also

- [README.md](README.md) - Project overview and quick start
- [SERVER.md](SERVER.md) - Server configuration reference
