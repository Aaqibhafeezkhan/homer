# LLM decisions that must carry proof

Homer's maintainers run a security scanner on the Homer repository. A cron
agent turns each open finding into a ticket, and the agents that implement
tickets pick it up like any other. A Claude step reads each finding against
the current `main` first, so the ticket says where the code is now and what
the fix should be.

Some findings describe behaviour the maintainers already decided to live
with. The scanner cannot see that decision and keeps raising them. One such
finding was the output cap that counts kept bytes rather than raw output,
whose acceptance is argued in the consequences of
[ADR-0063](../adr/0063-filter-claude-stdout-at-write-time.md). The intake
checked the changelog and the security policy for earlier decisions, but not
the ADRs. It filed a ticket against the ADR's own accepted-risk bullet, and
the ticket got as far as an implementation plan to undo it.

Telling the model to also read the ADRs fixes that case. It does not fix the
general one: a step that may decide "this needs no ticket" can also decide it
wrongly, and then nothing is left for a person to see.

## The setup

The model may answer "accepted" only with proof that a script can check: the
file that records the decision and the sentence that accepts it, quoted
verbatim. A deterministic step checks the quote at the checked-out `main`
before it acts. Anything it cannot prove becomes an ordinary ticket. A model
cannot invent an acceptance; it can only find one somebody wrote.

```
security-intake   cron "37 * * * *", lock SKIP_CURRENT
  list      !<script>  open findings minus those already ticketed → new.json
  clone     !<script>  when new.json is non-empty: check out main, record the sha
  verify    !<claude>  same guard, continueIfFail: a verdict per finding
  dismiss   !<script>  proves each "accepted" citation; drops it from new.json
  create    !<script>  when new.json is non-empty: one ticket per finding
```

The Claude step writes one verdict per finding:

```json
{"tickets": [{"finding_id": "…",
              "verdict": "ready | accepted | open",
              "summary": "…", "analysis_md": "…",
              "citation": {"file": "docs/adr/0063-….md",
                           "quote": "<the accepting sentence, verbatim>"}}]}
```

- `ready`: the weakness is real on `main`. The ticket goes straight to the
  implementing agents.
- `open`: doubtful, for example the flagged code is gone. The ticket is still
  filed, for a person to triage.
- `accepted`: the repository records a decision to live with exactly this
  behaviour. Only this verdict needs a `citation`, and only this one skips
  the ticket.

The `dismiss` step proves each `accepted` citation before it does anything:

```bash
# for one "accepted" verdict
abs=$(cd "$REPO/$(dirname "$FILE")" 2>/dev/null && pwd -P) || abs=""
if   [[ "$SEVERITY" == critical ]];        then ok=false  # a person closes these
elif (( ${#QUOTE} < 40 ));                 then ok=false  # too short to identify a decision
elif [[ -z "$abs" || "$abs" != "$ROOT"* ]]; then ok=false  # outside the checkout
elif [[ ! -f "$REPO/$FILE" ]];             then ok=false
elif ! grep -qF -- "$QUOTE" "$REPO/$FILE"; then ok=false  # not verbatim at main
else ok=true; fi
```

Only a proven citation dismisses the finding in the scanner, as *accepted*
(never as a false positive: the finding is correct), with the file, the
`main` sha and the quote as the reason. The finding is then removed from the
list the `create` step reads. A citation that fails, or a dismissal the
scanner refuses, leaves the finding in the list and it becomes a ticket,
which is what the intake did before the check existed.

```yaml
# agents/security-intake/agent.yaml (excerpt)
cron:
  expression: "37 * * * *"

inputs:                     # cron passes none; all three are for manual runs
  - { paramName: dry_run,  envName: DRY_RUN,           type: string, required: false }
  - { paramName: tag,      envName: INTAKE_TAG,        type: string, required: false }
  - { paramName: issue_id, envName: ISSUE_ID_OVERRIDE, type: string, required: false }

lockStrategy:
  key: "security-intake"
  onCollision: SKIP_CURRENT

commands:
  # ... list, clone ...
  - !<claude>
    label: verify
    when: '[ -s "$HMR_WORK_DIR/new.json" ]'
    continueIfFail: true      # create must run even without verdicts
    prompt: !<prompt>
      prompt: ...
    options:
      maxTurns: 160
      addDir: "repo"
    timeout: 5400
  - !<script>
    label: dismiss
    when: '[ -s "$HMR_WORK_DIR/new.json" ] && [ -s "$HMR_WORK_DIR/tickets.json" ]'
    content: ...
  - !<script>
    label: create
    when: '[ -s "$HMR_WORK_DIR/new.json" ]'
    content: ...
```

## The record it reads

Homer's own [`SECURITY.md`](../../SECURITY.md#accepted-risks) has an
*Accepted risks* table: each row names the finding, where it lives, the
decision record that argues the trade-off, and why it was accepted. The ADR
is the authority, the table is the index. The file asks for a risk to be
recorded there at the same time as the decision, not when a scanner first
raises it, because the intake automation reads it and `docs/adr/` to avoid
opening a ticket for something already settled. That table is what the
`accepted` verdict quotes from.

## Details that matter

- **Cron passes no inputs.** A scheduled run sees each input's `default` or
  nothing, and an agent with a required input that has no default gets no
  schedule at all ([Cron scheduling](../../app/AGENT.md#cron-scheduling)).
  So every input is optional, and the run's defaults are the production
  behaviour. A manual run passes them on the query string:
  `POST /api/v1/agent/security-intake?dry_run=true&issue_id=…`. A dry run
  verifies and prints, but files no ticket and dismisses nothing.
- **An empty scan costs no LLM.** The first step writes the list only when
  there is something new, and every later step has a
  [`when`](../../app/AGENT.md#conditional-execution-when) guard on it. Mind
  that `[ -s ]` is true for a file holding `[]`: the `dismiss` step truncates
  the list when it removed the last finding, so `create` is skipped.
- **`continueIfFail` covers a failed exit, not a timeout.** A Claude step that
  exits non-zero lets `create` run, and every finding without a verdict
  becomes a ticket for triage. A timeout still fails the whole run
  ([Claude Fields](../../app/AGENT.md#claude-fields)), so set the step's
  `timeout` well above its worst case.
- **Fail toward the visible outcome.** A malformed verdict file, an unknown
  verdict, a missing citation and a failed write all end in a ticket. The
  only path that hides a finding is the one a script proved.
- **Keep the proof.** The `dismiss` step copies its plan, with the reason for
  every citation it accepted or refused, into `$HMR_WORK_DIR/artifacts/`,
  which outlives the workspace cleanup and can be read with
  `GET /api/v1/artifacts`.
- **The `tag` input routes the tickets.** By default a ticket is tagged for
  the implementing agents. A different tag files tickets that wait for a
  person, without changing the agent.
- **Dedupe before the model runs.** A finding that already has a ticket, in
  any state, is dropped in the first step, by an id in the ticket summary.
  The model never sees it and the run does not pay for it.

## What it does not solve

- **A quote proves the sentence exists, not that it covers this finding.**
  The model can cite a real acceptance of a neighbouring mechanism. The
  prompt says a nearby decision is `open`, the dismissal names the file for
  anyone who reads it, and critical findings are never dismissed by the
  check, but the match itself is still a model's judgement.
- **A decision that was never written down still becomes a ticket.** That is
  the intended direction, and the reason to record a risk when it is
  accepted.
- **A missed acceptance still becomes a ticket.** The check prevents a false
  dismissal, not a false ticket. Triage and the review of the resulting merge
  request remain the guard there.

## Adapting it

Any step where a model may decide that work is not needed, a ticket is a
duplicate or a finding is settled can carry the same kind of proof: a file
and a quote, an id the next step looks up through an API, or a commit that
must exist. Let the model find the evidence, let a script check it, and make
the outcome without proof the one a person will see.
