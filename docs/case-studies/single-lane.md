# One lane at a time, without parked runs blocking it

Homer's maintainers develop Homer with Homer. A cron agent picks a ticket, and
a LangGraph orchestrator takes it through context, planning, implementation,
review, tests and a merge request, dispatching one worker agent per stage.
The cluster quota they run in has room for one such pipeline computing at a
time.

"One at a time" has a catch. A risky plan parks at an approval question that
may stay open for up to 7 days, over a weekend. A parked run computes nothing,
so it must not hold the lane while it waits for a human, or one unanswered
question stops all work for a week. And a run that dies with its pod must
not leave its ticket marked in progress forever, with nothing in Homer still
working on it.

## Why Homer's own limits do not express it

- **`maxParallelRuns` counts one agent's runs.** The workers are different
  agents and run as their own processes, so a cap on the orchestrator does not
  count them
  ([Parallel-Run Limiting](../../app/AGENT.md#parallel-run-limiting)).
- **A lock is held per run.** A LangGraph pipeline is a chain of runs: each
  segment ends when the graph parks, and a resume is a new run
  ([Resume re-runs the whole agent](../../app/AGENT.md#resume-re-runs-the-whole-agent)).
  No lock spans the gap between segments, and a templated key renders the same
  on every resume (see [One lock across several agents](cross-agent-locks.md)).
- **A parked run is not a process.** At the human gate the graph asks with
  ask-and-dispatch and the segment ends. What is left is an `OPEN` question
  bound to a dispatch ([Ask-and-dispatch](../../app/SERVER.md#ask-and-dispatch)),
  not a `CREATED` or `WORKING` run.

So the lane is computed from Homer's API before every pick.

## The setup

```
picker (cron :07,:17,…)  ── lane free? ── claim ticket ── POST /api/v1/agent/pipeline
watchdog (cron :23)      ── ticket stale >2h and lane empty? ── POST /api/v1/agent/park
lane = live pipeline runs + PENDING continuations   (open questions do not count)
```

The picker is a script-only cron agent. It holds a constant lock with
`SKIP_CURRENT`, so a tick that fires while the previous one is still running
does nothing:

```yaml
# agents/picker/agent.yaml (excerpt)
cron:
  expression: "7-59/10 * * * *"   # another intake cron runs at 4-59/10

lockStrategy:
  key: "dev-picker"
  onCollision: SKIP_CURRENT

runnerTags: [light]

env:
  HOMER_TOKEN: "@SYSENV:HOMER_AUTH_CRON_TOKEN"   # a principal with read on every pipeline agent

commands:
  - !<script>
    shell: bash
    timeout: 120
    content: |
      set -euo pipefail
      source "$SCRIPTS_DIR/lane.sh"
      if ! LANE=$(lane_state "$HMR_API_URL" "$HOMER_TOKEN"); then
        echo "lane state unreadable, treating it as busy" >&2; exit 0
      fi
      if lane_busy "$LANE"; then echo "lane busy: $LANE"; exit 0; fi
      # pick the oldest eligible ticket, mark it in progress, then:
      curl -fsS -X POST -H "Authorization: Bearer $HOMER_TOKEN" \
        "$HMR_API_URL/api/v1/agent/pipeline?ticket_id=$TICKET_ID"
```

The lane is three reads. Every one fails the function on any error, and
callers treat an unreadable lane as busy:

```bash
# scripts/lane.sh (excerpt)
PIPELINE_AGENTS='["pipeline","context","plan","implement","code-review","test-verify","mr-creation","park"]'

lane_state() {
  local api="$1" auth="Authorization: Bearer $2" live open pending
  live=$(curl -fsS -H "$auth" "$api/api/v1/status/all?types=CREATED,WORKING&limit=200" \
    | jq --argjson a "$PIPELINE_AGENTS" \
        '[.processes[] | select(.agentName as $n | $a | index($n))] | length') || return 1
  open=$(curl -fsS -H "$auth" "$api/api/v1/questions?status=OPEN&limit=1000" \
    | jq '[.questions[] | select(.agentName == "pipeline")] | length') || return 1
  pending=$(curl -fsS -H "$auth" \
      "$api/api/v1/continuations?status=PENDING&agentName=pipeline&limit=1" \
    | jq '.total') || return 1
  jq -cn --argjson l "$live" --argjson q "$open" --argjson p "$pending" \
    '{live: $l, open_questions: $q, pending: $p}'
}

# Busy while a run computes or is about to. A parked run (open question) is not busy.
lane_busy() { jq -e '.live > 0 or .pending > 0' <<< "$1" >/dev/null; }
```

The watchdog runs hourly with the same lane function. A ticket marked in
progress, untouched in the tracker for more than 2 hours, is stranded when no
pipeline run is live, no pipeline question is open and no continuation is
pending. The watchdog then starts a `park` agent that marks the ticket as
crashed and tells a human how to retry it:

```yaml
# agents/watchdog/agent.yaml (excerpt)
cron:
  expression: "23 * * * *"

lockStrategy:
  key: "watchdog"
  onCollision: SKIP_CURRENT
```

```bash
# the watchdog's script, after selecting the stale tickets
LANE=$(lane_state "$HMR_API_URL" "$HOMER_TOKEN")   # set -e: unreadable = park nothing
if [ "$(jq '.live + .open_questions + .pending' <<< "$LANE")" != 0 ]; then
  echo "pipeline still alive or parked: $LANE"; exit 0
fi
curl -fsS -X POST -H "Authorization: Bearer $HOMER_TOKEN" \
  "$HMR_API_URL/api/v1/agent/park?ticket_id=$TID&kind=crashed"
```

## Details that matter

- **Count every agent of the pipeline, not just the orchestrator.** A
  fan-out child is its own process with its own agent name. While a long
  implementation run works, the orchestrator has no live process at all.
- **Count pending continuations.** A continuation is `PENDING` from the
  moment a segment registers it until it fires
  ([`app/README.md`](../../app/README.md#api-endpoints)). Between a segment
  ending and the next run starting, nothing is `CREATED` or `WORKING`; without
  this read the picker sees a free lane in the middle of a handoff.
- **Fail closed.** A failed read (`curl -f`, or a body `jq` cannot parse)
  makes the lane busy and the picker skips one cycle. The cost of a wrong
  "busy" is ten minutes; the cost of a wrong "free" is two pipelines in a
  quota sized for one.
- **Read with a principal that sees everything.** `GET /api/v1/status/all`,
  `/questions` and `/continuations` return only what the caller may see. A run
  outside the caller's grants is simply absent, never a `403`
  ([Grants and roles](../../app/SERVER.md#grants-and-roles)). A token without
  `read` on every pipeline agent makes a busy lane look free. Fail closed does
  not catch that.
- **Filter on the server.** `GET /api/v1/questions` lists oldest first and
  pages at 100 by default. Without `?status=OPEN`, the open questions can sit
  on a page the script never reads once enough answered ones pile up.
- **Claim first, then dispatch.** The picker marks the ticket in progress
  before `POST /api/v1/agent/pipeline`. A failed dispatch leaves a claimed
  ticket that the watchdog recovers. The reverse order can start two runs on
  one ticket.
- **Stagger the crons.** Cron ticks align to the minute and fire even while
  the previous run is still going
  ([Cron Scheduling](../../app/AGENT.md#cron-scheduling)). Two crons that act
  on the same tickets in the same minute act on the same state at once, so
  each cron owns its minutes (`4-59/10`, `7-59/10`, `23`). A tick skipped by
  `SKIP_CURRENT` is recorded as a `FAILED` run that never started; that is
  expected noise, not an error.
- **The 2-hour floor protects quiet runs.** A long implementation run may not
  touch the ticket for an hour. The floor keeps it out of the candidate list;
  the lane check is the second line.
- **An expired question releases the ticket.** When the 7-day gate expires,
  the question is no longer `OPEN`, and the next watchdog sweep parks the
  ticket as crashed.

## What it does not solve

- **A parked run resumes into a busy lane.** When the human answers, the
  resumed run starts whether or not another pipeline is computing. For a
  while two pipelines compute at once. The lane bounds what the picker
  starts, not what a human resumes.
- **The watchdog is coarse.** It checks whether *any* pipeline is alive, not
  whether the stale ticket's is. A stranded ticket waits until the lane is
  quiet before it is parked. It never parks a ticket whose run is alive.
- **There is a window between the read and the dispatch.** Two picker ticks
  cannot race (the lock), but a manual `POST /api/v1/agent/pipeline` during
  the picker's read can.

## Adapting it

- Make the watchdog per ticket with [run tags](../../app/AGENT.md#run-tags):
  `tags.fromInputs: [ticket_id]` on the workers and
  `GET /api/v1/status/all?tag=ticket_id:<id>` in the check. A LangGraph
  resume carries no caller inputs, so the orchestrator's own runs would have
  to declare the tag at run time with `homer-tag`.
- For more lanes, compare `live` with a number instead of zero.
