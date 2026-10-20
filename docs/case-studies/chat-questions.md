# Answering operator questions from chat

Homer's maintainers run agents that park on a Homer question until a human
decides: an approval between the plan and the implementation, a gate when
the AI review loop on a merge request runs out of rounds, and a gate when an
implementation stops at its turn cap. Each question waits up to 7 days.
Nobody watched the console, so a parked run sat for the full 7 days and its
work was lost to the timeout.

A team that builds a mobile app with Homer had the same problem with its
approval between scoping and development. One approval was asked at 23:02 and
answered at 23:40: 38 minutes of a run whose machine time was 80 minutes, and
the one segment no cache or parallelism can shorten.

Two independent deployments built the same fix. Every open question is
mirrored into chat, with its options as buttons, and a click answers it
through Homer's API. Both teams use their own chat bridge built on Homer's
public API. This case study covers the Homer side of that bridge, and a
reminder agent for questions nobody answers.

## The setup

```
gate (LangGraph interrupt, or homer-ask ask-and-dispatch)
  │  question: Markdown text, options, TTL
  ▼
Homer ── SSE question.* events ─────────────► chat bridge, principal "chat"
      ◄─ GET  /api/v1/questions?status=OPEN ─┤ posts text + one button per option
      ◄─ POST /api/v1/questions/{id}/answer ─┘ on a click
  │  the answer fires the bound dispatch: the parked graph resumes as a new run
```

- **Where the questions come from.** A LangGraph human gate parks the graph
  and registers an ask-and-dispatch: the answer starts the same agent again
  with the thread id and the answer
  ([`homer_langgraph`](../../clients/python/homer_langgraph/README.md#durable-checkpoint--dispatch-resume-model)).
  The review-cap gate is a script in a cron agent that calls
  `homer-ask ask-and-dispatch` with `--option continue --option park` and
  `--ttl 604800`. The answer starts a small agent that applies it
  ([Ask and dispatch](../../app/AGENT.md#ask-and-dispatch)).
- **The bridge only uses the public API.** It posts each open question it
  has not posted yet, and on a click posts the option text as the answer.
  The first answer wins and a second gets `409`, so the console keeps
  working. `GET /api/v1/stream/processes` sends `question.asked`,
  `question.answered` and `question.expired`
  ([SSE](../../app/SERVER.md#get-apiv1streamprocesses)), but the events
  carry only ids, so the bridge reads text and options from the list.

An illustrative bridge loop:

```bash
API=https://homer.example.com/api/v1
AUTH="Authorization: Bearer $HOMER_CHAT_TOKEN"

sync_questions() {          # post every open question not posted yet
  curl -fsS -H "$AUTH" "$API/questions?status=OPEN&limit=1000" \
    | jq -c '.questions[] | {id, agentName, text, options, createdAt}' \
    | while read -r q; do
        id=$(jq -r .id <<< "$q")
        grep -qxF "$id" posted.txt 2>/dev/null && continue
        chat_post_question "$q" && echo "$id" >> posted.txt   # your chat client
      done
}

answer() {                  # called by the chat platform's button callback
  curl -sS -o /dev/null -w '%{http_code}' -X POST -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d "$(jq -n --arg a "$2" '{answer: $a}')" "$API/questions/$1/answer"
}   # 200 answered, 409 answered or expired already, 404 gone or not visible

sync_questions
curl -fsSN -H "$AUTH" "$API/stream/processes" \
  | grep --line-buffered '^event: question\.' \
  | while read -r _; do sync_questions; done
```

Run `sync_questions` on a timer as well, and after every reconnect. The
stream has no replay, and it misses some events (see below).

The bridge's principal:

```yaml
apiAuth: !<bearer>
  tokens:
    chat:
      token: "@SYSENV:HOMER_CHAT_TOKEN"
      grants:
        - agents: ["*"]
          actions: [run]
        - agents: [factory, review-watcher]   # every agent that asks questions
          actions: [read, control]
```

## Details that matter

- **`run` alone sees an empty list.** `GET /api/v1/questions` returns only
  questions whose asking run the caller owns, or whose agent it holds `read`
  on. Answering needs the owner, or `read` plus `control` on the agent
  ([Grants and roles](../../app/SERVER.md#grants-and-roles)). `run` makes the
  caller the owner only of the runs it starts. In the maintainers'
  deployment every question comes from a cron run (owner `system:cron`) or
  a run it started, so the bridge would poll without error and see nothing.
  Grant `read` and `control` by agent name: `read` on `*` would let the
  bridge's token list every run on the server. The name that counts is the
  agent whose run asks (the graph agent for a LangGraph gate, the cron
  watcher for its `ask-and-dispatch`), not the agent the answer starts. A
  gate in a new agent needs a new grant, or its questions never reach chat.
- **`workspace.purgeAfter` against the question's TTL.** A plain question
  expires when its run ends, and is deleted when the run's record is purged.
  A question registered by ask-and-dispatch, which includes every LangGraph
  gate, is different: it outlives the asking run and its purge, until it is
  answered, cancelled or its TTL runs out
  ([Ask-and-dispatch](../../app/SERVER.md#ask-and-dispatch)). Its visibility
  through the owner does not outlive the purge, though: Homer looks the
  owner up on the run's record. The mobile team's graph agent purges 2 hours
  after a segment ends, and its approval waits 24 hours. Without the `read`
  grant the question would drop off the bridge's list after 2 hours, while
  the console still shows it open. Either set `purgeAfter` longer than the TTL
  (the maintainers use `8d` against `7d`), or grant `read`. Once the asking
  run is purged, the SSE stream no longer sends that question's events to a
  non-admin caller either, so the `question.expired` at the TTL never
  arrives. The periodic sync catches it.
- **A parked LangGraph run is not finished.** The segment that parks ends
  `FINISHED` within seconds. Its status shows `openQuestions: 0`, because
  that count leaves out ask-and-dispatch questions. The graph continues later
  as a new run. A chat integration that starts a graph and reports its
  result at the first `FINISHED` posts a half-done graph's stdout as the
  answer. Follow the whole flow instead:
  `GET /api/v1/status/all?rootProcessId=<id>` lists every run below the one
  you started, and `GET /api/v1/dispatches` and `GET /api/v1/continuations`
  show what is still waiting to resume it. For this reason the maintainers
  do not start their graph from chat. Only its questions go there.
- **Put the decision in the question.** The text is Markdown, up to
  `server.questions.maxTextLength` (10 000 characters by default,
  [Operator questions](../../app/SERVER.md#operator-questions)). The
  maintainers' gates start with a short brief built by a script: a header
  naming the ticket or merge request, then a few labelled lines. A plan
  approval shows why the plan was flagged, its goal and its scope. A turn-cap
  gate shows the planned files done and not started. A review-cap gate shows
  the review rounds, open threads by severity, the last verdict and the
  pipeline. One line then says what each button does and what happens if
  nobody answers ("Unanswered for 7 days: the MR stays held."). If the
  script fails, the gate asks its plain question: a brief never blocks a
  gate. With it, deciding needs no trip to the issue tracker.
- **The answer is free text.** A button sends its option text, but any
  caller with `control` can send anything. The agent the answer starts acts
  only on an exact option and treats everything else as the safe default.
  For the review-cap gate that means the merge request stays held.
- **Homer sees the bridge, not the person.** Every answer is the bridge's
  principal. Whoever can see the chat message can click the button, so the
  place the bridge posts to decides who approves. The maintainers post to a
  direct message, which is also the one place a chat platform always
  notifies. The mobile team posts into the thread that started the run.
- **One bridge per bot app.** A chat platform that delivers each event to
  one connection of an app splits the button clicks between two bridges on
  the same app, and the bridge that gets a click for a question it does not
  know drops it.

## Reminding about open questions

One message is easy to miss. The mobile team added a script-only cron agent
that nudges about approvals nobody has answered:

```yaml
name: approval-reminder
cron:
  expression: "2,12,22,32,42,52 8-18 * * 1-5"   # every 10 min, working hours
  timezone: Europe/Berlin                      # Homer evaluates it in this zone
lockStrategy:
  key: "approval-reminder"
  onCollision: SKIP_CURRENT
maxParallelRuns: 1
workspace:
  path: "approval-reminder"    # one directory for every run
  cleanAfterFinish: false      # keeps the state file
env:
  HOMER_REMINDER_TOKEN: "@SYSENV:HOMER_REMINDER_TOKEN"   # read on the gate agents
commands:
  - !<script>
    shell: bash
    timeout: 120
    content: |
      set -euo pipefail
      cd "$HMR_WORK_DIR"
      # A half-written state file must not break every later run.
      jq -e 'type == "object"' reminded.json >/dev/null 2>&1 || echo '{}' > reminded.json
      curl -fsS -H "Authorization: Bearer $HOMER_REMINDER_TOKEN" \
        "$HMR_API_URL/api/v1/questions?status=OPEN" > open.json
      # (trimmed) An approval is due when it is 20+ min old, the last nudge
      # is 60+ min old and fewer than 3 were sent. Post the nudges, then write
      # {id: {last, count}} for the OPEN set only: answered ones drop out.
      remind_due_approvals open.json reminded.json
```

- **The first version nudged all night.** It ran every 10 minutes, around the
  clock, and repeated hourly until the question was answered. One approval
  asked at 17:46 got 15 nudges in the team's channel, from 18:12 to 08:12.
  In the morning the question was hard to find under the nudges about it.
- **Working hours come from `cron.timezone`.** Homer evaluates the expression
  in the named IANA zone ([Cron Scheduling](../../app/AGENT.md#cron-scheduling)),
  so no script has to compute local time. A question asked at 18:50 is first
  nudged at 08:02 on the next working day.
- **Once per interval, at most three times.** The state file maps question id
  to the time of the last nudge and the count. It lives in a fixed workspace
  path with `cleanAfterFinish: false`. Homer deletes a fixed path only when
  the last record that references it is purged, and a run every 10 minutes
  keeps one alive. Losing the file costs at most three more nudges per open
  question.
- **The nudge goes where the question is.** A Homer question carries no chat
  thread. The reminder reads the thread from state the graph already writes
  for its own reports, replies there and mentions the person who started the
  run. A run with no thread is nudged in the team's channel.

## What it does not solve

- **Homer has no reminders of its own.** An unanswered question only expires
  at its TTL. The reminder agent covers that. Delete it if your bridge gets
  native reminders, since two nudges are worse than one.
- **A question has no addressee, and the answer no author.** Homer does not
  say who should answer, and records the bridge as the one who did. Routing
  and the audit trail of who clicked live in the bridge and the chat.
- **The stream is per server and has no replay.** Events are broadcast in
  process, so a bridge that is disconnected misses them, and the list is the
  source of truth.
- **A weekend can outlast the TTL.** With working-hours nudges, a 24-hour
  approval asked on Friday evening expires before anyone is nudged. The
  maintainers chose 7 days for their gates so that a plan parked on Friday
  survives the weekend.
