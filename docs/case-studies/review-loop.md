# A review → fix loop that converges

Homer's maintainers develop Homer with Homer. When an agent opens a merge
request, a review agent posts its findings as review threads, a fix agent
addresses the threads and pushes, and the review runs again. A cron agent,
the watcher, drives the loop: every minute it looks at the open merge
requests and starts whatever each one needs next.

On one merge request the loop did not converge. Three rounds resolved 12
threads and opened 11 new ones, then hit the round cap and stopped. Sorting
every new thread by the commit that caused it showed why:

| Round | New threads | Caused by |
| --- | --- | --- |
| 1 | 7 | the original commit |
| 2 | 5 | **5 of 5** by the round-1 fix commit |
| 3 | 6 | **6 of 6** by the round-2 fix commit |

After round 1 the review found nothing further in the original work. It was
reviewing the fixes. A fix commit is new code with its own test, docs and
changelog obligations, and the fixer had no way to refuse a request it
thought was wrong, no limit on work nobody asked for, and no point at which a
minor finding stopped blocking the merge.

## The setup

```
mr-watcher     cron "* * * * *", lock SKIP_CURRENT
  ├─ reviewed, not approved → POST /api/v1/agent/mr-fix?mr_iid=…&round=N
  └─ round cap reached      → hold the MR, then
                              homer-ask ask-and-dispatch → mr-cap-gate

mr-fix         one run per merge request, at most two at a time
  seed        !<script>  open threads; picks FIX_MODEL and ARB_MODEL
  fix         !<claude>  {{FIX_MODEL}}; fixes, replies, or rejects with a ground
  arbitrate   !<claude>  only when there are rejections; {{ARB_MODEL}}
  plan        !<script>  the rails: overturns what the arbiter may not decide
  finalize    !<claude>  only when a rejection was upheld
  ship        !<script>  scope gate, commit, push, replies
```

- **The fixer may reject a thread, on a stated ground.** Its report lists
  the threads it addressed, the files each change belongs to, and any
  rejections, each with a ground: wrong, forbidden by a recorded constraint,
  out of scope, or disproportionate.
- **An arbiter on the other model rules on each rejection.** The seed step
  picks the fixer's model (a stronger one for blocker or major threads, or
  from round 3) and gives the arbiter whichever of the two the fixer is not
  using. A refusal is never graded by the model that wrote it.
- **Upheld means fixed in the same round.** `finalize` runs only when the
  plan holds an uphold and implements it, so a failed rejection does not cost
  a review round.

The model choice is made in a script and handed to the Claude steps through
[`resultEnvParam`](../../app/AGENT.md#result-to-environment-mapping):

```yaml
# agents/mr-fix/agent.yaml (excerpt)
env:
  DEFAULT_MODEL: sonnet
  STRONG_MODEL: opus

commands:
  - !<script>
    label: seed
    content: |
      #!/bin/bash
      set -euo pipefail
      # ... count the open blocker/major threads into HARD_THREADS ...
      # Always print both: a {{VAR}} that no step set stays literal and
      # reaches the CLI as a model name.
      FIX_MODEL=$DEFAULT_MODEL
      if (( HARD_THREADS > 0 || ROUND >= 3 )); then FIX_MODEL=$STRONG_MODEL; fi
      ARB_MODEL=$STRONG_MODEL
      if [ "$FIX_MODEL" = "$ARB_MODEL" ]; then ARB_MODEL=$DEFAULT_MODEL; fi
      echo "FIX_MODEL=$FIX_MODEL"
      echo "ARB_MODEL=$ARB_MODEL"
      # ... collect the open threads, clone the branch ...
    resultEnvParam:
      - src: STD_OUT
        envName: FIX_MODEL
        transformer: !<regex>
          regex: '(?m)^FIX_MODEL=(\S+)$'   # (?m): ^ and $ match per line
          group: 1
      - src: STD_OUT
        envName: ARB_MODEL
        transformer: !<regex>
          regex: '(?m)^ARB_MODEL=(\S+)$'
          group: 1

  - !<claude>
    label: fix
    prompt: !<prompt>
      prompt: ...
    options:
      model: "{{FIX_MODEL}}"
      maxTurns: 200
      maxTurnsExitCode: 75     # a turn-cap stop ships the partial work
    continueIfFail: true
    resultEnvParam:
      - src: RESULT_CODE
        envName: FIX_EXIT
        transformer: !<pass> {}

  - !<claude>
    label: arbitrate
    when: '[ "$(jq ".rejections | length" "$HMR_WORK_DIR/fix-report.json" 2>/dev/null || echo 0)" != 0 ]'
    prompt: !<prompt>
      prompt: ...
    options:
      model: "{{ARB_MODEL}}"
      maxTurns: 80
    continueIfFail: true       # a broken arbiter leaves every rejection upheld
```

## Rails the model cannot talk its way past

The `plan` step is a script that reads the fix report, the arbiter's
decisions and the threads, and writes the plan every later step acts on.
Nothing downstream reads the model's raw decisions again.

- **A human's thread is never arbitrable.** A rejection of one is dropped and
  becomes a plain reply. A person's request is settled with the person.
- **Anything malformed counts as an uphold.** A missing decision, a file that
  does not parse, or an arbiter step that failed all mean the finding stands.
  Silence never dismisses a finding.
- **A blocker can only be rejected as wrong or forbidden,** never as out of
  scope or disproportionate, and a thread can be rejected once, ever.
- **Only attributed changes ship.** Before the commit, `ship` compares every
  changed path with the files the report attributed to a thread and reverts
  the rest. The extra changelog line nobody asked for, which caused two
  round-3 threads, would not have survived.

From round 3, minor and documentation-only findings go into one note on the
merge request instead of threads, so they no longer block the merge. Blockers
never decay. Replayed against round 3 of that merge request, two findings stay
threads and four go into the note.

## The round cap and the human gate

After three rounds `mr-watcher` stops starting fixes. It puts the merge
request on hold first, which is the safe state if nobody ever answers, and
then asks:

```bash
homer-ask ask-and-dispatch "$BRIEF" \
  --agent mr-cap-gate --option continue --option park \
  --param "mr_iid=$IID" --param "round=$ROUND" \
  --answer-param answer --ttl 604800      # 7 days
```

The watcher run ends seconds later. The question does not: a question
registered by [ask-and-dispatch](../../app/AGENT.md#ask-and-dispatch) outlives
the run that asked it. When someone answers, Homer starts `mr-cap-gate` with
the answer as an input. `continue` grants three more rounds and lifts the
hold. Any other answer parks the merge request for a person to finish.

```yaml
# agents/mr-cap-gate/agent.yaml (excerpt)
dispatch:
  enabled: true              # without it, registering the question is a 403
inputs:
  - { paramName: mr_iid, envName: MR_IID, type: string, required: true }
  - { paramName: round,  envName: ROUND,  type: string, required: true }
  - { paramName: answer, envName: ANSWER, type: string, required: false }
lockStrategy:
  key: "mr-cap-gate-{{MR_IID}}"
  onCollision: SKIP_CURRENT
```

```yaml
# Homer config (excerpt)
server:
  agentGrants:
    mr-watcher:                      # a cron run has no root principal
      - agents: [mr-cap-gate]
        actions: [run]
  apiAuth: !<bearer>
    tokens:
      chat-bridge:                   # answers questions in chat
        token: "@SYSENV:HOMER_CHAT_TOKEN"
        grants:
          - agents: [mr-watcher]
            actions: [read, control]
```

The maintainers answer in chat, through their own bridge built on Homer's
API: it lists open questions with `GET /api/v1/questions` and posts the
answer with `POST /api/v1/questions/{id}/answer`. The question can also be
answered in Homer's console.

## Details that matter

- **A cron-owned hop rides on `agentGrants` alone.** A cron run is owned by
  `system:cron` and has no root principal, so the hop from the watcher to
  `mr-cap-gate` is authorised only by `server.agentGrants`
  ([nested dispatch](../../app/SERVER.md#grants-and-roles),
  [ADR-0039](../adr/0039-nested-dispatch-authority.md)). Without the entry,
  registering the question answers `404`, the same as an unknown agent.
- **Answering needs `read` and `control` on the asking agent.** The bridge did
  not start the watcher's run, so `run` is not enough to see or answer its
  questions
  ([process ownership](../../app/SERVER.md#process-ownership-and-visibility)).
  Name the agents instead of `"*"`: `read` on every agent lets a token list
  every run. A new agent that asks questions has to be added here, or its
  questions never reach chat.
- **Declare the answer as an input, with a type that takes spaces.** `--param`
  values and the answer reach the target only through inputs it declares. The
  legacy `STRING` type rejects any value with a space, and a typed answer
  usually has one. Use `string`.
- **Set the TTL on the question.** Without `--ttl`, the question takes
  `server.dispatch.defaultTtlSec`, which may be a day or never
  ([Ask-and-dispatch](../../app/SERVER.md#ask-and-dispatch)).
- **Overlapping ticks are not skipped.** Cron fires every minute even while
  the previous tick runs, so the watcher's `lockStrategy` uses
  `SKIP_CURRENT`.
- **Two writers at most.** `mr-fix` has `maxParallelRuns: 2` and a lock keyed
  on the merge request, so no branch has two writers and no more than two
  builds compete for the build pool
  ([Parallel-run limiting](../../app/AGENT.md#parallel-run-limiting)). The
  watcher also counts live fix runs with
  `GET /api/v1/status/all?types=CREATED,WORKING` and starts at most one per
  tick.
- **`continueIfFail` where a failure is recoverable.** A broken arbiter
  defaults to uphold and a turn-capped fix ships what it has. `ship` reads
  `FIX_EXIT` and fails the run only on a real crash.
- **Keep the plan as an artifact.** `plan` copies its output into
  `$HMR_WORK_DIR/artifacts/`, which outlives the workspace cleanup, so every
  ruling can be read later with `GET /api/v1/artifacts`.

## What it does not solve

- **Duplicate findings.** Two reviewers that ask for the same change in
  different words still open two threads.
- **The cap is fixed.** New threads per round went 7, 5, 6. A trend like that
  could stop or escalate a loop earlier than a fixed cap; the loop does not
  use it yet.
- **The arbiter is a model.** The rails decide what it may not do, and the
  default is always the finding. A wrong dismissal of a minor thread is still
  possible; a person who reopens the thread overrides it.
- **A gate nobody answers parks the work.** After the TTL the question
  expires and the merge request stays held until someone finishes it by hand.
