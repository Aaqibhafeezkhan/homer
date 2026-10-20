# A ticket-to-merge-request pipeline on LangGraph

Two deployments built the same thing on Homer, each on its own. Homer's
maintainers turn tickets into draft merge requests for Homer itself. A team
that builds a mobile app turns tickets into verified merge requests for the
app. In both, one orchestrator agent runs a LangGraph graph, every stage is a
worker agent, and a human approves the plan before the expensive stage runs.

One pipeline run is about eleven Homer processes and stays open as long as
the slowest human: the approval gate waits up to 7 days in one setup and 24
hours in the other. Both hit the same traps. This case study describes the
shape they converged on and those traps.

## The setup

```
caller ─▶ orchestrator, segment 1 ── homer_fanout ──▶ worker run (context)
          orchestrator, segment 2 ◀─ continuation ───┘   (worker ended)
          ...
          orchestrator, segment k ── homer_interrupt ─▶ question (approve / decline)
          orchestrator, segment k+1 ◀─ ask-and-dispatch ┘ (answered)
          ...
checkpoint: <server.langGraph.stateDir>/<owner>/checkpoints.sqlite, keyed by thread
```

- **One orchestrator, thin workers.** The orchestrator's only real command is
  [`!<langgraph>`](../../app/AGENT.md#commandlanggraph---langgraph-runtime-launcher).
  Its nodes call `homer_fanout(agent, params=…, result_jsonpath=…, ttl=…)`,
  which parks the graph and starts the worker. The segment then exits. When
  the worker ends, a continuation starts the orchestrator again with the
  worker's status and result in `HMR_LG_RESUME`
  ([`homer_langgraph`](../../clients/python/homer_langgraph/README.md)). Each
  worker is an ordinary agent with its own runner, lock, timeout and
  `dispatch.enabled: true`.
- **Parallel stages resume once.** Review and tests, or the three
  end-to-end checks, are `homer_fanout` calls with the same `group=`. The
  runtime starts them together and resumes the graph a single time after the
  last one ends ([ADR-0026](../adr/0026-join-continuations.md)).
- **The human gate is a question.** `homer_interrupt(text, options=[…],
  ttl=…)` registers an [ask-and-dispatch](../../app/SERVER.md#ask-and-dispatch):
  the question appears in the console and in `GET /api/v1/questions`, and the
  answer starts the next segment. Both teams also answer from chat, through
  their own bridge on that API.
- **Terminal notices are fire-and-forget.** A node with no interrupt in it
  calls `homer_dispatch("park", {...})` to hand the ticket back with a reason.

The orchestrator agent, combining the two setups:

```yaml
name: pipeline
dispatch:
  enabled: true              # required for !<langgraph>: resumes arrive as dispatches

inputs:                      # all optional: a resume run carries none of them
  - { paramName: ticket_id, envName: TICKET_ID, type: string, required: false }

env:
  PYTHONPATH: /app/agents/pipeline
workspace:
  purgeAfter: 8d             # longer than the 7-day approval TTL
# No lockStrategy: see "Details that matter".
commands:
  - !<script>
    label: graph-input
    shell: bash
    when: '[ -z "$HMR_LG_THREAD" ]'      # fresh runs only
    content: |
      #!/bin/bash
      set -euo pipefail
      [[ -n "${TICKET_ID:-}" ]] || { echo "ticket_id is required" >&2; exit 1; }
      jq -cn --arg t "$TICKET_ID" '{ticket_id: $t}'
    resultEnvParam:
      - src: STD_OUT
        envName: GRAPH_INPUT
        transformer: !<pass> {}
  - !<langgraph>
    module: pipeline_graph.graph
    inputEnv: GRAPH_INPUT
    timeout: 300             # one segment: start Python, read the checkpoint, run to
                             # the next park; the 60 s default was too tight
```

The graph, trimmed to one worker stage and the gate:

```python
from homer_langgraph.dispatch import homer_dispatch
from homer_langgraph.gates import homer_fanout, homer_interrupt

GATE_TTL_SEC = 7 * 24 * 3600   # a plan parked on Friday evening survives the weekend

def implement(state):
    r = homer_fanout("implement", params={"ticket_id": state["ticket_id"]},
                     result_jsonpath="$.payload", ttl=6 * 3600)
    if r["status"] != "FINISHED":        # FAILED, KILLED, UNKNOWN: the tooling broke
        return {"route": "crash", "step_attempts": state["step_attempts"] + 1}
    return {"route": "verify", "step_attempts": 0, "branch": r["result"]["branch"]}

def gate(state):
    return {"gate": homer_interrupt(plan_text(state), options=["approve", "decline"],
                                    ttl=GATE_TTL_SEC)}

def park_decline(state):     # no interrupt in this node, so it runs exactly once
    homer_dispatch("park", {"ticket_id": state["ticket_id"], "kind": "decline"})
    return {}
```

And in the server config:

```yaml
server:
  agentGrants:
    pipeline:                # the orchestrator may start its workers and itself
      - agents: [pipeline, context, plan, implement, review, test-verify, mr-creation, park]
        actions: [run]
  dispatch:
    defaultTtlSec: 86400     # applies to every fan-out and gate that sets no ttl
```

## Salvaging a turn cap

One implement run on the maintainers' setup stopped at Claude's `maxTurns`
after about 66 minutes and $36. The plan was implemented, the tests were green
and the commit message was written. At the time Homer failed the step at the
cap whatever `continueIfFail` said, so the step that commits and pushes never
ran, `cleanAfterFinish` deleted the working tree, and nothing of the paid work
survived.

Homer gained an opt-in for this, `maxTurnsExitCode`
([`app/AGENT.md`](../../app/AGENT.md#claudecmdoptions-fields)). A cap stop now
returns that exit code like any other command result, so `continueIfFail` and
`resultEnvParam` apply, and a deterministic step decides what happens to the
work:

```yaml
  - !<claude>
    label: implement         # edits and tests; never commits or pushes itself
    prompt: !<prompt>
      prompt: "..."
    options:
      maxTurns: 400
      maxTurnsExitCode: 75   # a stop at maxTurns returns 75 instead of failing the run
    timeout: 7200
    continueIfFail: true     # let the ship step decide
    resultEnvParam:
      - src: RESULT_CODE
        envName: IMPLEMENT_EXIT
        transformer: !<pass> {}

  - !<script>
    label: ship
    shell: bash
    content: |
      #!/bin/bash
      set -euo pipefail
      case "${IMPLEMENT_EXIT:-0}" in
        0)  commit_and_push "$TICKET_ID" "feat($TICKET_ID): implementation"
            jq -n --arg b "$TICKET_ID" '{payload: {branch: $b}}' ;;
        75) commit_and_push "$TICKET_ID" "wip($TICKET_ID): partial, stopped at the turn cap"
            jq -n --arg b "$TICKET_ID" '{payload: {branch: $b, stopped: "max_turns"}}' ;;
        *)  echo "implement exited $IMPLEMENT_EXIT; pushing nothing" >&2; exit 1 ;;
      esac
```

The graph routes `stopped: "max_turns"` to a second gate,
`homer_interrupt(…, options=["continue", "stop"], ttl=GATE_TTL_SEC)`.
`continue` runs another implement round on the pushed branch, with a flag that
tells the agent to diff against the main branch and finish rather than start
over. After two continues, or on `stop`, the ticket goes back to a human with
the branch kept. A crashed Claude step still exits non-zero, the ship step
fails, and the graph takes its crash path.

## Details that matter

- **Every input is optional.** A resume run, started by a continuation or by
  an answered question, carries only the LangGraph markers (`HMR_LG_THREAD`,
  `HMR_LG_RESUME`, `HMR_LG_INTERRUPT`) and the answer, never the caller's
  inputs. A `required: true` input rejects that start, the continuation is
  marked `FAILED`, and the thread never resumes. Both teams mark the comment
  on `required: false` as load-bearing and check presence on the fresh run
  instead.
- **Guard fresh-run steps with `HMR_LG_THREAD`.** Homer resumes a thread by
  re-running the whole agent from the top
  ([`app/AGENT.md`](../../app/AGENT.md#resume-re-runs-the-whole-agent)).
  `HMR_LG_THREAD` is reserved: callers cannot set it, it is absent on a fresh
  run and set on every resume, so `when: '[ -z "$HMR_LG_THREAD" ]'` reliably
  means "fresh run". Caller inputs exist only in that first segment, so fold
  everything later stages need into the graph input. The mobile team's last
  node reads where to report the outcome from state written ten processes
  earlier. Reading `TICKET_ID` from the environment in the graph's first node
  works too, as the maintainers do, because that node runs once.
- **No lock on the orchestrator.** A resume has no `TICKET_ID`, so a key such
  as `pipeline-{{TICKET_ID}}` keeps its placeholder and is the same string for
  every resume of every ticket. With `WAIT`, resumes of unrelated tickets
  queue behind each other. With `SKIP_CURRENT`, a resume that collides ends
  `FAILED` without running, and nothing starts it again. Put per-ticket locks
  on the workers, which receive the ticket on every dispatch
  (`key: "implement-{{TICKET_ID}}"`), and let the first stage claim the ticket
  so a second trigger is turned away.
- **Grant the orchestrator its workers and itself.** Each fan-out is a hop
  from the orchestrator to a worker, and each continuation is a hop back onto
  the orchestrator. A hop is allowed when the run's root principal holds `run`
  on the target, or when `server.agentGrants` lists it
  ([Grants and roles](../../app/SERVER.md#grants-and-roles),
  [ADR-0039](../adr/0039-nested-dispatch-authority.md)). The maintainers'
  pipeline works because the token that starts it holds `run` on every agent.
  The mobile team lists the workers and the orchestrator in `agentGrants`, so
  it does not matter who started the run: a console user, a narrow token, or
  Homer's own cron, which has no root principal at all. A denied hop answers
  `404` at registration and marks a pending continuation `FAILED`.
- **Keep the parked segment's record as long as its question.** `purgeAfter`
  removes a segment's workspace and its record. The checkpoint lives in
  `server.langGraph.stateDir`, outside the workspace, and the question bound
  to an ask-and-dispatch is not deleted with the record either. But Homer
  finds a question's owner through the asking process, so once that record is
  purged the run's owner can no longer see or answer it; only an admin or a
  principal with `read` and `control` on the orchestrator can. The
  maintainers keep the record longer than the gate (`purgeAfter: 8d` against
  a 7-day TTL). The mobile team keeps `purgeAfter: 2h` and gives the principal
  its chat bridge answers with `read` and `control` on the orchestrator.
- **A caller that waits for the end must follow the chain.** The first
  segment parks within seconds and ends `FINISHED`; its output is not the
  pipeline's result. A fan-out park leaves a continuation
  (`GET /api/v1/continuations`, `originProcessId` → `firedProcessId`). A gate
  park leaves a dispatch instead (`GET /api/v1/dispatches`,
  `dispatchedProcessId`); a follower that reads only continuations reports
  the run finished at the gate.
  `GET /api/v1/status/all?rootProcessId=<first segment>` lists the whole flow
  ([ADR-0058](../adr/0058-parent-child-run-linkage.md)). Following
  needs `read` on the orchestrator, and the follower must wait longer than an
  approval may take: the mobile team waits 25 hours for a 24-hour gate.
- **A fan-out TTL bounds the worker's wall time.** A continuation that passes
  its TTL expires and the thread never resumes. `server.dispatch.defaultTtlSec`
  applies to every fan-out without its own `ttl`, and it is 24 hours in both
  configs. The mobile team sets each worker's TTL above its worst case,
  queueing included: 30 minutes for one API read, 11 hours for the stage on
  the macOS build host.
- **Budgets live in graph state.** The maintainers' first node reads `MAX_*`
  from the environment once and stores the limits in state, so a changed agent
  env cannot move them under a running thread. Counters change only in a node's
  returned update: the interrupted node re-runs from its start on resume, and
  anything it did before `homer_fanout` happens again. Parallel members write
  their own keys and the join node owns the counters. A worker that did not
  finish (`status` other than `FINISHED`) uses a separate step budget, two
  attempts and then a human, as "the tooling broke". It never uses a fix
  round, because a crashed run proves nothing about the code.
- **Answers are free text.** `options` are suggestions; Homer accepts any
  non-blank answer. Both gates treat anything without `approve` in it as a
  decline, so a stray reply builds nothing.
- **A parked pipeline is not a running process.** Between hops there is only
  a `PENDING` continuation, and at the gate only an open question. The
  maintainers' intake job starts one pipeline at a time: it counts
  `CREATED`/`WORKING` runs of the pipeline's agents plus `PENDING`
  continuations, so a pipeline waiting at the gate does not block the queue.

## What it does not solve

- **An unanswered gate parks the thread for good.** When the TTL passes, the
  dispatch is cancelled and a `question.expired` event is emitted, but the
  graph is never resumed, so it cannot take its own "nobody answered" branch.
  The ticket stays claimed until something outside the graph notices. The
  maintainers run an hourly cron agent that hands back a claimed ticket with
  no live run, no open question and no `PENDING` continuation. The mobile
  team works on the other end: a cron agent reads `GET /api/v1/questions` and
  reminds whoever started the run while the question is still open.
- **A broken pipeline does not continue on the next trigger.** The checkpoint
  is keyed by thread, and a new trigger is a new thread.
  [Keeping long runs from losing work](long-run-salvage.md) describes what the
  mobile team built for that, and for a worker killed mid-run, where
  `maxTurnsExitCode` does not help.
- **The orchestrator itself is not serialized per ticket.** Two triggers for
  one ticket start two graphs, and only the claiming stage turns the second
  away. Its check runs against the issue tracker, not against Homer.
