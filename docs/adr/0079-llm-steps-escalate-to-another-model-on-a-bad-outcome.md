---
status: accepted
date: 2026-10-06
deciders: Lukáš Vlk
consulted: ADR-0047, ADR-0061, ADR-0070, ADR-0074
---

# ADR-0079: LLM steps escalate to another model on a bad outcome

## Context and Problem Statement

An agent that runs every `!<claude>` step on the strongest model pays for it on every task,
including the many a much cheaper model would finish. A replay of 26 real tickets of a
coding agent showed a cheap model delivering about 85 % of them for about 1 % of the strong
model's price, with the rest failing in ways a deterministic check can see: a non-zero exit, a
turn cap, an output file that says "hold". Agents can approximate "cheap first, strong when it
fails" today only by duplicating the LLM step per model and gating the copies with `when`, which
copies every option, drifts, and has to be rebuilt in each agent. Homer needs a way to declare
the fallback on the step itself, without changing anything for an agent that does not ask for it.

## Decision Drivers

- **Opt-in only.** An agent without the new field must run exactly as before: same argv, same
  retry loop, same accounting, same telemetry. Teams move over when they choose to.
- **The outcome check is agent-specific.** Only the agent knows that `decision.json` saying
  `hold` is a failure; Homer cannot judge task success.
- **Reuse what authors already know.** Agents already write bash-`test` predicates in `when`.
- **One executor for every harness** (ADR-0070): Claude and Codex get the feature together.
- **Bounded spend** (ADR-0047 rule 4): every escalation multiplies the step's cost and runtime.

## Considered Options

- **A. Duplicate the LLM step per model and gate the copies with `when`** (status quo).
- **B. `escalate` on the LLM step: an ordered list of tiers, each a model plus a post-attempt
  `when` predicate.**
- **C. Model list on `retry`**: retry on the next model when an attempt exits non-zero.
- **D. An external connector** (like a Slack connector) that re-dispatches the agent on another model.

## Decision Outcome

Chosen option: **B**, because it keeps the step declared once, lets the agent define failure with
the predicate language it already uses, and lives in the shared `LlmHarnessExecutor`, so every
harness gets it.

```yaml
- !<claude>
  prompt: !<langfuse> { promptName: "Developer agent prompt", label: production }
  options:
    model: "{{CHEAP_MODEL}}"
    options: ["--continue"]
  escalate:
    - model: "claude-opus-5-5[1m]"
      options: []                       # the stronger model starts its own session
      when:
        any:
          - '[ "$HMR_LLM_OUTCOME" != success ]'
          - '[ "$(jq -r .status decision.json 2>/dev/null)" != implemented ]'
```

Semantics:

- The step runs on its own model first, with its `retry` policy, as today.
- After it returns, the first tier's `when` is evaluated with the step's env plus
  `HMR_LLM_OUTCOME` (`success`, `failed`, `capped`, `malformed`) and `HMR_LLM_EXIT_CODE` (`-1`
  for `malformed`) describing the attempt that just ran, in the workspace directory, with the same evaluator,
  timeout and failure rules as a command's `when`. True runs the tier; false stops escalating and
  the step returns what it has. Each further tier is judged after the previous one in the same way.
- A tier re-runs the same compiled prompt with the step's options, except `model`, and the raw CLI
  `options` list when the tier sets one. Everything the previous model left in the workspace stays.
- The last tier that ran is the step's result: `resultEnvParam`, `continueIfFail` and
  `finishExitCode` apply to it. A `malformed` outcome that no tier takes over is rethrown exactly as
  today. Timeouts and cancellations are never escalated.
- Every attempt of every tier is cost-accounted and reported to Langfuse as it is today, except a
  `malformed` tier the next tier takes over: that tier is accounted but not reported, since it hands
  the step to the next model rather than ending it. Each tier writes its own attempt artifacts.
- At most three tiers per step. A step's `timeout` and `retry` apply to each tier separately, and
  cost caps are checked at the request boundary as before, so escalation spends within a run like
  retries do.

### Consequences

- Good: an agent without `escalate` is untouched; the field defaults to an empty list.
- Good: cheap-first is one block on the existing step, the same for Claude and Codex.
- Good: the failure check stays in the agent, in the predicate language agents already use.
- Bad: one step can now spend up to (tiers + 1) × its timeout and retry budget; the three-tier
  bound and the per-tier accounting make that explicit rather than unbounded.
- Bad: the stronger model starts from whatever the cheaper one left in the workspace; Homer does
  not reset it. An agent that needs a clean tree says so in its prompt.

## Pros and Cons of the Options

### A — Duplicate the step per model

- Good: works on every Homer version.
- Bad: every option is copied per model and drifts; each agent rebuilds the same pattern.

### B — `escalate` tiers on the LLM step

- Good: declared once; agent-defined failure; shared by all harnesses; bounded.
- Neutral: adds a predicate evaluation between tiers, which a command's `when` already does.

### C — Model list on `retry`

- Good: smallest schema change.
- Bad: retry fires only on a non-zero exit or malformed output, so a successful run that produced
  a wrong result (the common cheap-model failure) never escalates; mixing "same model again" and
  "another model" in one policy blurs both.

### D — External connector re-dispatching the agent

- Bad: a connector cannot see inside a step; a re-dispatched run repeats the whole agent, and an
  agent whose lock strategy is `WAIT` would block on its own lock.

## More Information

- Predicate evaluation reuses `WhenEvaluator`; trust boundary as for command `when`: the predicate
  is agent configuration, while the files it reads are agent-written and treated as data
  (ADR-0047 rule 2) — the result is only a boolean.
- A tier's predicate artifacts are `escalate_<commandIndex>_<tier>` with `<tier>` counting from 1,
  the tier whose `when` it is — not the tier that just ran — so `escalate_3_1.out` holds tier 1's
  expressions.
- Cost accounting per attempt: ADR-0061, ADR-0074. Harness boundary: ADR-0070.
