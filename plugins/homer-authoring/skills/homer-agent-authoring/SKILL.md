---
name: homer-agent-authoring
description: Use when authoring or editing a Homer agent definition (agent.yaml / agent.json) — adding commands, inputs, env capture, workspace/lock/cron config, or wiring a Claude command. Triggers on "Homer agent", agent.yaml/agent.json files, kaml tags like !<cmd> / !<claude>, or HMR_* env vars.
---

# Homer Agent Authoring

## Overview

A Homer **agent** is a directory containing an `agent.yaml` (or `agent.json`)
that Homer discovers on the filesystem and loads at startup. Its `commands`
run **in order**; each command can capture output into env vars that later
commands read.

This skill is a cheat-sheet for the common case. Full reference:
[`reference/AGENT.md`](reference/AGENT.md). Contract:
[`../../schemas/homer-agent.schema.json`](../../schemas/homer-agent.schema.json).

## Workflow

1. **Copy the closest pattern** from the cheat-sheet below (or from
   `reference/AGENT.md`) and adapt it. Don't author from scratch — the
   discriminators and field names are easy to get wrong.
2. **Turn on editor autocomplete** (see below) so you get red squiggles as
   you type.
3. **Validate** before shipping (see Validate loop).

## Top-level structure

Fields live at the **top level** — there is no `agent:` wrapper key.

```yaml
name: deploy-notify          # required, regex ^[a-zA-Z0-9_-]+$  (NO spaces)
description: ...             # optional
commands:                    # required, runs in order, ≥1 item
  - !<cmd>
    cmd: echo hello
```

| Field | Required | Notes |
| --- | --- | --- |
| `name` | yes | `^[a-zA-Z0-9_-]+$` — letters, digits, `_`, `-`. No spaces. |
| `commands` | yes | Ordered list, ≥1. Each item is a tagged command (below). |
| `description` | no | Free text. |
| `env` | no | Map of `String: String`. Values support `@SYSENV:VAR` host refs. |
| `inputs` | no | Request params (query/body/header) → env vars. See AGENT.md. |
| `workspace` | no | `{ path, cleanAfterFinish, purgeAfter, retain }`. |
| `lockStrategy` | no | `{ key, onCollision: WAIT\|SKIP_CURRENT\|KILL_OLD_RUN_CURRENT, acceptParentLock }`. |
| `cron` | no | `{ expression: "<5-field cron>", timezone }`. |
| `maxParallelRuns` | no | Default 10; `onMaxParallelRuns: WAIT\|REJECT`. |

## Command types

YAML uses a **kaml tag** (`!<…>`) to pick the command type. JSON uses a
`"type"` field instead. Forget the tag in YAML and kaml fails with
"unknown tag".

| Tag (YAML) | `type` (JSON) | Use when |
| --- | --- | --- |
| `!<cmd>` | `cmd` | Run a single binary with args. |
| `!<script>` | `script` | Run a multi-line shell snippet. |
| `!<claude>` | `claude` | Invoke the Claude CLI with a prompt + options. |
| `!<codex>` | `codex` | Invoke the OpenAI Codex CLI with a prompt (+ optional options). |
| `!<agent>` | `agent` | Call another Homer agent (field is `agentName`). |
| `!<langgraph>` | `langgraph` | Launch a Python `homer_langgraph` module. |
| `!<runnerCmd>` | `runnerCmd` | Run a command on another runner (fields `runner`, `execute`). |

### Minimal valid example per command type

```yaml
name: every-command-type
dispatch:
  enabled: true    # required by the !<langgraph> command below
commands:
  # Single binary. `cmd` is a scalar string (POSIX-tokenized) OR a list of argv.
  - !<cmd>
    cmd: git rev-parse HEAD

  # Multi-line shell.
  - !<script>
    content: |
      echo "building"
      make all

  # Claude CLI. `options` is REQUIRED; model/maxTurns/etc. live INSIDE it.
  # `prompt` is a tagged object, not a bare string.
  - !<claude>
    prompt:
      !<prompt>
      prompt: "Summarize the build output"
    options:
      model: claude-opus-4-8

  # Call another agent. The field is `agentName`, not `agent`.
  - !<agent>
    agentName: send-slack

  # Python LangGraph runtime (agent must set dispatch.enabled: true, as above).
  - !<langgraph>
    module: my_pkg.my_graph
```

Common per-command fields (all optional): `label`, `timeout` (default 60),
`continueIfFail`, `finishExitCode`, `when`, `resultEnvParam`.

## Capturing output → env (`resultEnvParam`)

Each command may export stdout / stderr / exit code into env vars that later
commands read. The field names are `src` and `envName` (not `source`/`name`):

```yaml
  - !<cmd>
    cmd: git rev-parse HEAD
    resultEnvParam:
      - src: STD_OUT          # STD_OUT | STD_ERR | RESULT_CODE
        envName: COMMIT_SHA   # do NOT use an HMR_ prefix — that namespace is reserved
        transformer:          # optional; default is pass-through
          !<jsonPath>
          path: $.sha
```

Transformers (tagged): `!<pass>` (default), `!<jsonPath>` with `path:`,
`!<regex>` with `regex:` + optional `group:`.

## Editor autocomplete (JSON Schema)

Add this directive to the top of your `agent.yaml`, pointing at the bundled
schema (path relative to your file, or a raw URL):

```yaml
# yaml-language-server: $schema=<path-or-url>/homer-agent.schema.json
name: my-agent
# ...
```

For VS Code, also register the kaml tags in `.vscode/settings.json` so the
YAML extension doesn't flag them as "unknown tag":

```jsonc
{
  "yaml.customTags": [
    "!<cmd> mapping", "!<script> mapping", "!<claude> mapping", "!<codex> mapping",
    "!<agent> mapping", "!<langgraph> mapping", "!<runnerCmd> mapping",
    "!<pass>", "!<jsonPath> mapping", "!<regex> mapping",
    "!<prompt> mapping", "!<langfuse> mapping"
  ]
}
```

## Validate loop (tiered)

1. **Editor schema squiggles** — fastest; catches wrong fields as you type.
2. **`agentdebug validate <name>`** — if you have the Homer CLI
   (`agentdebug.sh`; needs the `agentdebug` JAR + Java 25). Loads the agent
   through the same load-time checks the server runs, then validates it
   against the bundled JSON schema (the server itself skips the schema check).
3. **Homer server** — rejects invalid agents on load. Final gate.

## Common mistakes

| Symptom / mistake | Fix |
| --- | --- |
| `unknown tag` in YAML | Add the kaml tag (`!<cmd>`, `!<claude>`, …) and register it in `yaml.customTags`. |
| Wrapping everything under an `agent:` key | There is no wrapper — `name`/`commands` are top-level. |
| `run:` for a shell command | The field is `cmd:` (for `!<cmd>`) or `content:` (for `!<script>`). |
| `resultEnvParam` with `name`/`source` | Use `envName` and `src`. |
| Capturing into `HMR_...` | The `HMR_*` prefix is reserved for Homer's injected vars. Pick another name. |
| Overwriting host vars (`PATH`, `HOME`, `JAVA_*`, …) | Don't reuse reserved host env names for captures. |
| `!<claude>` with `prompt:` as a bare string and `model:` at top level | `prompt` is a `!<prompt>` object; `model` (and other options) go under the required `options:`. |
| `!<agent>` with `agent:` field / `type: runAgent` | The tag is `!<agent>` and the field is `agentName`. |
| `name` with spaces | Allowed set is `^[a-zA-Z0-9_-]+$`. |
| `cmd` quoting confusion | `cmd` may be a scalar string (POSIX-tokenized) **or** an explicit argv list. |
| `{{VAR}}` inside `!<cmd>` `cmd` / `!<script>` `content` | Not substituted — both run verbatim. Read variables from the env in a `!<script>` (`"$VAR"`); a `!<cmd>` has no shell. `{{VAR}}` works only in Claude/Codex prompts, options and `cmd`, `!<agent>` `agentName`, workspace paths, lock keys and artifact names. |
