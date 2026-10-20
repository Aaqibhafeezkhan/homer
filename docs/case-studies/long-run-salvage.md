# Keeping long runs from losing work

A team that builds a mobile app runs the ticket pipeline described in
[A ticket-to-merge-request pipeline on LangGraph](durable-pipeline.md): one
LangGraph orchestrator, about eleven Homer processes per ticket, and a
developer stage that can spend several hours of a large model. Two kinds of
breakage kept throwing that work away.

A pipeline that broke anywhere (a worker that died, a build host that was
unreachable, a resumed segment that exited 1 after a second) could only be
triggered again, and the new run started from the beginning: admission
checks, scoping, the human approval and the developer stage, all redone. And
one developer run ended `KILLED` two hours into its Claude step. Nothing had
been pushed, the workspace was deleted, and a re-run would have started on an
empty branch.

## The problem

What Homer keeps, and when it is gone:

| Work | Where Homer keeps it | Lost when |
| --- | --- | --- |
| Graph state | the checkpoint in `server.langGraph.stateDir`, keyed by thread | a new trigger: a fresh run gets a new random thread id, and no caller can set `HMR_LG_THREAD` to an old one |
| A failed orchestrator segment | `POST /api/v1/processes/{id}/resume` | the segment ended `FINISHED`, or its record was purged |
| A worker's uncommitted work | its `workdir/` | the run ends: `cleanAfterFinish` deletes it |
| A salvage step after the Claude step | `continueIfFail` on a non-zero exit | a timeout or a kill: the run ends before the next step |

The resume endpoint looks like the answer and is not
([`app/README.md`](../../app/README.md#post-apiv1processesidresume),
[ADR-0044](../adr/0044-langgraph-resume-and-run-introspection.md)). It accepts
a `FAILED`, `KILLED` or `UNKNOWN` LangGraph run, starts the same agent with
the original inputs and the recover markers, and re-enters the thread at its
latest checkpoint. In most breakages, though, the orchestrator did not fail.
A dead worker reaches it as a resume value with `status: "KILLED"`, the graph
routes that to its `failed` outcome, and the segment ends `FINISHED`, which
the endpoint refuses with `409`. When a segment did fail, its record had to
still exist, and this orchestrator purges records after two hours. The
endpoint also gives the operator no choice between continuing and starting
over, and it cannot bring back a worker's working tree.

## The setup

Three pieces, each covering one gap.

**A journal beside the checkpoint.** The graph keeps a second record of what
it has done, keyed by ticket instead of by thread, in one JSON file per
ticket. It lives on the volume that holds Homer's database and the derived
`stateDir`, outside `workspaceRootPath`, so it survives pod restarts and
`purgeAfter`. The orchestrator has no `runner`, so it runs on Homer's own host
and its graph code can reach that directory. The file is an ordered log of
node visits, each one the exact state update a stage returned. A log rather
than a snapshot, because the pipeline loops: only the order says which
developer result belongs to which fix round. Every stage goes through one
wrapper:

```python
def _stage(state, node, dispatch):
    pos, replay = state.get("replay_pos", 0), state.get("replay") or []
    if pos < len(replay) and replay[pos]["node"] == node:
        update, pos = replay[pos]["update"], pos + 1   # replayed: nothing dispatched
    else:
        update = dispatch()                            # homer_fanout(...) runs here
        pos = len(replay)                              # the first real work ends replay
    visits = (state.get("visits") or []) + [{"node": node, "update": update}]
    journal.save(state["ticket_id"], visits)           # never raises
    return {**update, "visits": visits, "replay_pos": pos}
```

The journal is kept for a run that broke or stopped to ask a human, dropped
for one that delivered or was rejected, and swept after 30 days.

**A resume question.** The graph's first node, before the stage that claims
the ticket, looks for a journal. Usually there is none and it does nothing.
Otherwise it asks through the same gate the approval uses, so the question
shows up in the console and in the team's chat bridge:

```python
def entry(state):
    prior = journal.load(state["ticket_id"])
    if not prior or not journal.resumable(prior):
        return {}
    answer = homer_interrupt(resume_text(prior), options=["continue", "start over"],
                             ttl=86400)
    if "start over" in answer.lower():
        journal.clear(state["ticket_id"])
        return {"resumed": False}
    return {"resumed": True, "replay": journal.replay_log(prior), "replay_pos": 0}
```

`replay_log` cuts two things. The claiming stage is never replayed: it always
runs, and takes over the claim the dead run left. Everything from the first
visit whose worker did not end `FINISHED` is dropped, because that stage is
the breakage. The routing functions then see the payloads the dead run saw and
walk the new run to the stage that broke, where real work starts again.

**Snapshots while the developer works.** A save after the Claude step does not
run when the step is killed, so the developer agent saves during it. A Claude
`PostToolUse` hook pushes the working tree to a separate branch at most every
ten minutes, and the next attempt restores it:

```yaml
name: developer
inputs:
  - { paramName: ticket_id, envName: TICKET_ID, type: string, required: true }
  - { paramName: resume,    envName: RESUME,    type: string, required: false }
lockStrategy:
  key: "ticket-{{TICKET_ID}}"
  onCollision: SKIP_CURRENT
workspace:
  cleanAfterFinish: true
env:
  WIP_CHECKPOINT: "1"        # turns the shared hook on for this agent only
commands:
  - !<script>
    label: checkout          # clone, check out the branch, then:
    shell: bash
    content: |
      wip_restore_or_discard "$TICKET_ID" "${RESUME:-}"
  - !<claude>
    label: develop
    prompt: !<prompt>
      prompt: "..."
    options:
      maxTurns: 300
      settings: "{{HMR_AGENT_DIR}}/../shared/settings.json"
    timeout: 5400
  - !<script>
    label: push              # push the branch, then delete the snapshot branch
    shell: bash
    content: |
      push_branch "$TICKET_ID" && wip_drop "$TICKET_ID"
```

```json
{
  "hooks": {
    "PostToolUse": [{
      "matcher": "Edit|Write|MultiEdit|NotebookEdit|Bash",
      "hooks": [{ "type": "command", "timeout": 180,
                  "command": "[ -f \"$SCRIPTS_DIR/wip.sh\" ] && bash \"$SCRIPTS_DIR/wip.sh\" checkpoint; exit 0" }]
    }]
  }
}
```

`wip.sh checkpoint` does nothing unless `WIP_CHECKPOINT=1`, or if less than
600 seconds have passed since the last snapshot, or if the tree has not
changed. Otherwise it builds the tree in a private copy of the git index
(`GIT_INDEX_FILE`), writes one commit on top of `HEAD` with `git commit-tree`,
and force-pushes it to `<branch>_wip` with CI skipped. The agent's own index,
`HEAD` and branch are never touched. The graph sends `resume=1` only to the
stage that broke, and only on a run the operator chose to continue. The
restore then puts the snapshot's tree back into the checkout, uncommitted,
and a file in `$HMR_WORK_DIR` tells the agent that the diff is its own
unfinished work.

## Details that matter

- **`continueIfFail` does not run on a timeout or a kill.** It applies only
  to a non-zero exit; a timeout, an output-cap kill or a thrown error ends
  the run regardless ([`app/AGENT.md`](../../app/AGENT.md#cmd-fields)). A run
  ends `KILLED` when it is killed from the console or the API, or by a
  `KILL_OLD_RUN_CURRENT` lock. For a stop at `maxTurns`, `maxTurnsExitCode`
  hands the decision to a later step (see
  [Salvaging a turn cap](durable-pipeline.md#salvaging-a-turn-cap)). Nothing
  later in the agent runs after a kill, so the save has to happen inside the
  step.
- **The hook sees the run's environment.** Homer starts Claude with the
  workspace env, so the hook knows `HMR_WORK_DIR`, the ticket and the agent's
  `env:`. Every Claude stage shares one settings file and only the developer
  sets `WIP_CHECKPOINT`, which keeps the hook a no-op everywhere else. The
  hook never fails: errors go to a log under `$HMR_WORK_DIR` and it exits 0.
  It does block the agent for a few seconds per snapshot, capped at 180.
- **Restore only onto the same parent.** A snapshot whose parent is not the
  checked-out `HEAD` is dropped: the branch moved on since, and applying it
  would revert whatever moved it. A run that is not a resume drops any
  snapshot it finds, so a fresh run or a `start over` does not inherit work
  nobody asked to keep.
- **Keep the snapshot off the real branch.** It goes to its own branch with
  CI skipped, so the merge request, its pipeline and its reviewers never see
  it. The suffix comes after the ticket id, so code that finds a ticket's
  branch by a name ending in that id does not pick the snapshot.
- **Write the journal so a replayed node writes the same thing.** Homer
  re-runs an interrupted node from its start on resume
  ([`homer_langgraph`](../../clients/python/homer_langgraph/README.md)). The
  write comes after `homer_fanout` has returned, and it writes the whole log
  derived from checkpointed state, so a node that runs again produces the same
  file rather than a doubled entry. Writes use an atomic rename, and a failed
  write costs the next trigger a fresh start, nothing more.
- **Pick each gate's default on purpose.** Answers are free text. The approval
  treats anything without `approve` as no: the safe default is to build
  nothing. The resume question treats anything but `start over` as continue:
  the safe default is to keep what exists.
- **Ask before taking the claim.** The question comes before the claiming
  stage, so an unanswered one holds no claim and blocks no other run; it
  expires after 24 hours. A run started from the console or cron on a ticket
  with a journal parks on a question that nobody may be watching.

## What it does not solve

- **It works around Homer's resume semantics.** Homer resumes a thread, not
  a ticket, and only from a failed orchestrator segment whose record still
  exists. A pipeline that ended `FINISHED` with a `failed` outcome, or whose
  record is gone, has no Homer-side way back into its thread. The journal is
  a second copy of what the checkpoint already holds, written by the graph,
  and the two can disagree: a crash between the checkpoint write and the
  journal write loses that one stage, which then runs again.
- **The granularity is a stage and ten minutes.** A broken stage restarts
  from its beginning, and the developer loses up to ten minutes of edits. A
  snapshot can catch the agent mid-change; the restored tree is a starting
  point, not a commit anyone reviewed.
- **Budgets are not reset.** A resumed run keeps the fix rounds it had used.
  A ticket that does not fit into one developer attempt gets a fresh timeout
  with a head start, which may still not be enough.
- **The hook pushes with the step's own credentials.** The Claude step that
  is told not to push has a hook that does. In a setup where runs hold no
  tokens, as in [Runs that hold no credentials](credential-proxy.md), the
  push goes through the same proxy as every other.
- **Abandoned snapshots stay.** A snapshot nobody continues remains a branch
  until the ticket's next run drops it.

## Adapting it

- Put the journal where `server.langGraph.stateDir` lives, and run the
  orchestrator where that volume is mounted. A journal in the workspace is
  deleted by `purgeAfter` along with everything else.
- Homer's maintainers use a lighter form for their implement worker: its
  first step counts the commits an earlier run left on the ticket branch, and
  the prompt tells the agent to continue from them instead of redoing them.
  That covers work that reached a push, not work a kill interrupted.
