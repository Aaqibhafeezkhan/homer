# Containing a Claude step: disallowedTools, not allowedTools

A team that builds a mobile app runs Homer in production to turn tickets into
verified merge requests. Every stage is an agent with one or more
[`!<claude>`](../../app/AGENT.md#commandclaude---claude-ai-execution) steps,
and every one of those steps carried an `allowedTools` list that read like a
sandbox. The verifier, for example, could `Read`, `Glob`, `Grep`, `Write` and
run seven `git` subcommands, and nothing else. It also carried
`dangerouslySkipPermissions: true`, because the stages run unattended.

The list constrained nothing. In one run the verifier ran seven `python3 -c`
commands, although its shell was limited to `git`. Then it called the `Agent`
tool, which was not on its list, to start two background subagents to "audit
the merge request". Each of them invoked a code-review skill, which is itself a
multi-agent workflow and started one review agent per angle. That made nine
agents, three levels deep. In about three minutes they used roughly 28M cached
input tokens, 22M of them in a two-minute window, and pushed the team's key on
the shared LLM gateway past its budget cap. The run died on HTTP 429 without a
verdict, and the pipeline stopped there.

## The problem

Two settings looked like limits, and neither limited this run.

- **`allowedTools` is an approval list, not a restriction.** Homer passes it
  to the Claude CLI as `--allowedTools`, and the CLI documents that flag as
  tools that run *without prompting*. With
  `dangerouslySkipPermissions: true`, Homer passes
  `--dangerously-skip-permissions`, nothing prompts, and every tool runs. The
  list then only records intent. The CLI's
  [reference](https://code.claude.com/docs/en/cli-reference) is explicit: to
  restrict which tools exist, use `--tools` or `--disallowedTools`.
- **`maxTurns` bounds one loop.** Homer always passes `--max-turns` (default
  `40`). The verifier had used 78 of its 100 turns when it died, but every
  subagent has a turn budget of its own, so the cap on the parent says
  nothing about the total.

Dropping the skip flag would not have stopped the fan-out either. Claude Code
does not ask permission for the `Agent` tool, so a headless run can start
subagents with the permission gate on
([ADR-0017](../adr/0017-claude-skip-permissions-default.md) describes Homer's
side of that gate).

## The setup

Every `!<claude>` step now has a `disallowedTools` list that names the tools
that turn one agent into many. Homer passes it as `--disallowedTools`, and a
bare tool name there removes the tool from the model's context. The model
never sees it, so there is no prompt for the skip flag to bypass and nothing
to call.

```yaml
# agents/verifier/agent.yaml (excerpt)
- !<claude>
  label: review
  prompt: !<prompt>
    prompt: |
      Review the diff in {{HMR_WORK_DIR}}/repo and write verification.json.
  options:
    model: sonnet
    maxTurns: 100
    dangerouslySkipPermissions: true
    disallowedTools:
      - Agent          # subagents: the fan-out itself
      - Task           # the same tool under its older name
      - Skill          # a skill can be a multi-agent workflow
      - Workflow       # orchestrates many subagents by design
      - ListAgents     # reach into other sessions
      - SendMessage
    allowedTools:      # intent only while the skip flag is set
      - Read
      - Write
      - Glob
      - Grep
      - "Bash(git diff:*)"
      - "Bash(git log:*)"
      - "Bash(git show:*)"
  timeout: 3600
```

Homer joins each list with commas and builds this command line from it
(trimmed):

```
claude --model sonnet --dangerously-skip-permissions \
  --allowedTools Read,Write,Glob,Grep,Bash(git diff:*),… \
  --disallowedTools Agent,Task,Skill,Workflow,ListAgents,SendMessage \
  --max-turns 100 --output-format stream-json --print --verbose
```

Stages that only read go one step further and deny `Bash` too. Without it,
the two stages that resolve a ticket's scope kept a shell and made 20 and 22
shell calls on one ticket (`grep -r`, `cat`, `find`) over a tree with large
binary assets.
`Read`, `Glob` and `Grep` do the same work and skip binaries. The one stage
that analyses the deployment's own configuration also denies `WebFetch` and
`WebSearch`, so everything it reasons about was fetched by an earlier,
deterministic step.

## Details that matter

- **A deny list is the one that survives the skip flag.** `allowedTools` was
  kept on every step. It documents what the step is meant to use, and it
  becomes real if the skip flag is ever dropped. But only `disallowedTools`
  and `tools` change what the model can call while the flag is set.
- **Deny names the CLI may not ship.** Denying a tool the CLI does not have
  is harmless, which is why `Task`, the older name of `Agent`, stays on the
  list next to it.
- **Keep `Skill` where the prompt depends on it.** The stage that writes the
  code is told to use the app repository's own skills, so it keeps `Skill`
  and denies the rest. A skill built around a subagent then runs inside the
  step's own context and turn budget. That was accepted on purpose: an
  unbounded `Agent` on the most expensive stage was the larger risk.
- **Inline subagents become dead weight.** Homer's `agents` option defines
  subagents for `--agents`. With `Agent` denied, nothing can start them, so
  drop the option from a step that denies it.
- **Check the argv, not the YAML.** `agentdebug run <agent> --dry-run`
  ([`app/agentdebug`](../../app/agentdebug/README.md)) prints the command line
  Homer would run, built by the same code as a real run. It shows which
  permission flag a step really gets, for example that `permissionMode`
  suppresses `--dangerously-skip-permissions`
  ([Claude Command Options](../../app/AGENT.md#claude-command-options)).
- **Homer's cost caps act before a run, not during it.**
  [`costLimits`](../../app/SERVER.md#cost-limits) refuse a run whose window is
  already over its cap. A run that has passed the check runs to completion,
  however much it spends. They stop the next run after a runaway, not the
  runaway itself.

## What it does not solve

- **A deny list ages.** A future CLI may ship a new delegation tool under a
  new name. Revisit the list whenever the Claude Code version in the runner
  image changes.
- **Everything else is still allowed.** With the skip flag set, a step can
  still run any shell command and reach every MCP server its `mcpConfig`
  loads. `disallowedTools` closes the fan-out, not the sandbox. Closing that
  means dropping `dangerouslySkipPermissions` in favour of a `permissionMode`
  and explicit permission rules, which is a larger change.
- **The step can still spend its own budget.** `maxTurns` and `timeout` bound
  one loop, and a long loop on an expensive model is still expensive.

## Adapting it

- Start with the delegation tools on every step, whatever else it does. They
  are the ones whose cost multiplies.
- `tools` is the allowlist the CLI does enforce: Homer passes it as
  `--tools`, which restricts the built-in tools to the ones named (MCP tools
  are not affected). It suits a step whose tool set is small and stable. A
  deny list is easier to add to many existing steps without changing what
  they do.
- `maxBudgetUsd` (`--max-budget-usd`) caps one step's spend, and the CLI
  counts subagent spend toward it. It is an estimate checked by the CLI, so
  leave headroom, but it is the one per-step limit that sees the whole tree.
