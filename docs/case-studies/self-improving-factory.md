# A factory that proposes its own fixes

A team that builds a mobile app runs its ticket pipeline on Homer: a LangGraph
graph fans each ticket out over a dozen stage agents and ends with a verified
merge request. Every fix the pipeline itself got came the same way: a person
noticed something. The evidence had usually been there for days. A stage
failed the same way twice in the run history, a reviewer made the same
complaint on three of the pipeline's merge requests, or a Homer release added a
feature that would replace a workaround. Nobody read any of it.

So the team added one more agent. It reads what the pipeline left behind and
opens one merge request per proposed fix on the pipeline's own repository. A
person merges or closes each one, and the agent never merges anything itself.

This case study covers the Homer mechanics around that agent: how it is
started, how it avoids running twice, how it stays out of the way of the work
it studies, and where it keeps its state.

## The trap

The first version ran on a nightly cron. One evening two tickets had been
started half an hour before the tick. The analysis is an hour-long Claude
session, and it ran in the same pod and on the same LLM gateway key as the
ticket stages. It overlapped them for 80 minutes. The tickets' Claude steps ran
3 to 6 times slower than the same stages had run alone that morning, and used
about twice the tokens:

| Claude step | Alone | Next to the analysis |
| --- | --- | --- |
| implementation target | 51–65 s | 323–489 s |
| scoping | 210–216 s | 817–919 s |
| implementation | 813–1267 s | 1897–2730 s |

The analysis hit its own step timeout and was killed with nothing proposed,
which wasted its $14. Moving the cron to another hour would not fix this,
because people start tickets in the evening too. The agent now waits until the
pipeline is idle (see below).

## The setup

```
cron, weekdays 19:17 local ──────┐
pipeline ends on a defect: POST ─┼─▶ improver   lockStrategy SKIP_CURRENT
a person asks: POST ─────────────┘      │
                                        ├─ gather   script  waits for idle, reads the status API;
                                        │                   exit 123 = nothing to do
                                        ├─ analyze  claude  reads files only, writes proposals
                                        └─ publish  script  one branch and merge request per
                                                            proposal, advances the watermark
workspace "improver" (kept across runs): state.json with the watermark, two clones
```

```yaml
# agents/improver/agent.yaml (excerpt)
name: improver
cron:
  expression: "17 19 * * 1-5"
  timezone: Europe/Berlin     # the team's zone; UTC when omitted
lockStrategy:
  key: "improver"
  onCollision: SKIP_CURRENT
maxParallelRuns: 1
inputs:
  - paramName: focus          # free text from a person; empty on a cron run
    envName: IMPROVER_FOCUS
    type: string
    required: false
workspace:
  path: "improver"            # one fixed directory, not {{HMR_PROCESS_ID}}
  cleanAfterFinish: false
commands:
  - !<script>
    label: gather
    timeout: 4500             # up to 3600 s of waiting for idle, plus the gathering
    finishExitCode: 123       # "nothing to do": the run ends FINISHED here
    content: |
      # ...
  # then !<claude> analyze (timeout 4800) and !<script> publish (timeout 900)
```

**Three triggers, one analysis.**

- **Nightly**, from the agent's own `cron:` block
  ([Cron Scheduling](../../app/AGENT.md#cron-scheduling)). `timezone` takes
  an IANA name, and Homer rejects an unknown one when it loads the agent. A
  cron run passes no caller inputs, so every input here is optional. If an
  agent has a required input with no default, Homer still loads it but turns
  off its schedule.
- **When a pipeline run ends on a defect** (a stage failed, the gate refused,
  or the run stopped for a person). The pipeline's last step is a script that
  reports the outcome. It already runs once at the end of every run, so it
  starts the improver with a plain POST and does not wait for it:

  ```bash
  if [ "$IMPROVE_TRIGGER" = 1 ]; then
    curl -sS --max-time 30 -X POST \
      -H "Authorization: Bearer $HOMER_IMPROVER_TOKEN" \
      "$HMR_API_URL/api/v1/agent/improver" \
      || echo "WARN: could not start the improver" >&2   # never fatal
  fi
  ```

  It is not a `homer_fanout` from the graph. A fan-out parks the graph until
  the worker returns, so the pipeline would report its outcome an hour later.
  The POST sends no `X-Homer-Parent-Process-Id`, so the analysis is a root run
  of its own (`HMR_PARENT_PROCESS_ID` is unset) and outlives the pipeline run
  that started it.
- **On demand.** A person asks in chat ("improve: the verifier keeps timing
  out"). The team's own chat bridge, built on Homer's API, sends the same POST
  with `?focus=...`. A run with a focus always analyzes. An admin can also
  fire the agent exactly as a tick would, with
  `POST /api/v1/agents/improver/cron-run`
  ([ADR-0080](../adr/0080-manual-cron-fire-endpoint.md)).

**One analysis at a time.** Cron ticks fire even while the previous run is
still going, and the default lock key is unique per run
([Lock Strategy](../../app/AGENT.md#lock-strategy)). `SKIP_CURRENT` drops a
trigger that arrives during an analysis, so five tickets failing in a row
produce one analysis. `WAIT` would queue a second one with nothing new to
read; `KILL_OLD_RUN_CURRENT` would throw away an hour of work.

**Wait for an idle pipeline.** The gather step polls the status API once a
minute and starts only when no other agent has a run `CREATED` or `WORKING`:

```bash
WAITED=0
while :; do
  BUSY=$(curl -sS --max-time 30 -H "Authorization: Bearer $HOMER_IMPROVER_TOKEN" \
      "$HMR_API_URL/api/v1/status/all?types=CREATED,WORKING&limit=100" 2>/dev/null \
    | jq -r '[.processes[]? | select(.agentName != "improver") | .agentName]
             | unique | join(", ")' 2>/dev/null || true)
  [ -n "$BUSY" ] || break                    # idle, or the poll failed
  if (( WAITED >= 3600 )); then
    echo "Still busy after ${WAITED}s ($BUSY); these runs wait for the next trigger."
    exit 123
  fi
  sleep 60; WAITED=$(( WAITED + 60 ))
done
```

A LangGraph graph parked on an approval does not count as busy: a parked
segment exits and its run is `FINISHED`. A failed poll counts as idle, so the
idle check can never be the thing that stops the improver.

**`finishExitCode` turns "nothing to do" into success.** The gather step exits
123 in two cases: the pipeline was still busy after an hour, or there are no
new runs since the watermark and no focus was given. The step declares
`finishExitCode: 123`, so the run ends `FINISHED` and the analyze and publish
steps are skipped. Exit 0 would continue to the next step, and any other code
would fail the run ([Script Fields](../../app/AGENT.md#script-fields)).

**Read runs and cost from the status API.** For each watched agent, the gather
step pages through
`/api/v1/status/all?agentName=<agent>&types=FINISHED,FAILED,KILLED&order=desc`
and keeps the ids above the watermark. It then fetches
`/api/v1/status/{id}` for each one, which has every step's exit code, timing
and the run's `costUsd`. A step's entry names its stderr artifact
(`executions[].stdErr`) but does not include it. For the steps that failed,
the gather step fetches the last 20 kB with
`GET /api/v1/artifacts?processId=<id>&path=<name>`
([`app/README.md`](../../app/README.md#api-endpoints)). The analyst works from
those files. A stage whose cost doubled is as much a finding as one that
failed.

**A persistent workspace holds the watermark.** `workspace.path` is a fixed
`improver`, not `{{HMR_PROCESS_ID}}`, so every run gets the same directory. It
holds `state.json` (the highest process id already analyzed) and two clones
that are refreshed instead of cloned again. Only the publish step advances the
watermark, and it does so on the no-op path too, because those runs were
analyzed. A run that ends early leaves the watermark alone, so its runs are
read by the next trigger.

**Merge requests hold the memory.** What was already proposed is not kept in
the workspace. Every proposal is a merge request with a fixed label; the next
run lists them by that label and reads their state (open, merged, or closed
for good). Losing the workspace costs a re-analysis of the newest 20 runs per
agent, not a duplicate merge request.

## Details that matter

- **The token needs `read` on every agent.** `/status/all` silently leaves
  out runs the caller cannot see; it never answers `403`
  ([Process ownership and visibility](../../app/SERVER.md#process-ownership-and-visibility)).
  With a token that holds only `run`, the idle check sees an idle pipeline
  every time, and the run dump comes back empty.
- **A dropped trigger shows up as a failed run.** A `SKIP_CURRENT` collision
  is still accepted (`200` with a `processId`), and the run ends `FAILED`
  without executing. The improver's run list therefore has `FAILED` rows that
  mean "already running". The improver also leaves its own runs out of the
  dump, so it never reads its own output as evidence.
- **Leaving out `purgeAfter` does not mean "never".** The default is `5d`. On
  a fixed path, the directory is deleted only when the last run record that
  references it is purged
  ([Workspace Cleanup](../../app/AGENT.md#workspace-cleanup)), so it survives
  as long as runs keep coming. A pause longer than `purgeAfter` deletes the
  watermark. Set `purgeAfter` explicitly if losing the watermark is
  expensive.
- **Read cost from Homer, not from the transcript.** A Claude transcript's
  `total_cost_usd` is the CLI's own estimate. The status API's `costUsd` is
  what Homer charged, and that can come from the gateway
  ([Accurate cost through an LLM gateway](cost-accounting.md)).
- **Gather by script, publish by script.** The analyze step reads review
  comments anyone could write, so it gets no shell and no network:
  `disallowedTools: [Bash, WebFetch, WebSearch]` and no MCP config. The
  scripts around it make every API call and every git change.

## What it does not solve

- **The idle check runs only at start.** A ticket started one minute after
  the analysis begins still shares the pod and the gateway key with it.
- **Homer validates nothing before the merge request exists.** An edited
  `agent.yaml` could be one the deployed Homer refuses. The analyst checks its
  edits against the deployed `GET /api/v1/schemas/agent`. CI lints every
  branch ([Linting agent definitions in CI](agent-linting.md)). The reviewer is
  asked to run `agentdebug validate` before merging.
- **The run holds a Homer token that can read every run.** Run output from
  all agents passes through the analysis. Keep the analyst without a shell
  and without network access, and keep a person in front of every merge.

## Adapting it

- Scope the token to what the improver and its triggers need:

  ```yaml
  server:
    apiAuth: !<bearer>
      tokens:
        improver:
          token: "@SYSENV:HOMER_IMPROVER_TOKEN"
          grants:
            - agents: ["*"]
              actions: [read]
            - agents: [improver]
              actions: [run]
  ```

- Inside a graph, the fire-and-forget alternative to the POST is
  `homer_dispatch` (see
  [`homer_langgraph`](../../clients/python/homer_langgraph/README.md)). It
  does not park the graph either, but the target needs
  `dispatch: { enabled: true }`, a cron-started pipeline needs an
  `agentGrants` entry for the hop, and the run becomes a child of the pipeline
  run.
