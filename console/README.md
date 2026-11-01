# Homer Console

A modern, self-hosted web application for monitoring and managing Homer processes. Built with Next.js, TypeScript, and Tailwind CSS.

## Features

- **Real-time Process Monitoring**: Live updates via Server-Sent Events (`/api/v1/stream/*`) — process status, current command, and log tail stream in over SSE, with the process list and detail views also polling every 30 s as a fallback (the detail poll stops once a run is terminal, the list poll once every row on the page is terminal). Event channels: `process.status`, `process.cmd`, `agent.reload`, `log.chunk`, `log.end`, `llm.event`, `llm.end`, `question.asked`, `question.answered`, `question.expired`, `message.queued`, `message.delivered`, `message.undelivered`. Uses [`@microsoft/fetch-event-source`](https://github.com/Azure/fetch-event-source) so requests can carry the bearer `Authorization` header (the native `EventSource` API cannot).
- **Process Management**: View detailed process information, retry finished/failed/killed processes, and kill running processes
- **Agents Overview**: Browse available agents with parameter details; create, edit, and debug-run agents from the console
- **Themes**: Light, Dark, System, Terminal, and Windows 95 — dark by default, switchable from the header; operator themes via `CONSOLE_THEMES` join the same dropdown, and `CONSOLE_THEME_MODES` can replace the built-ins with one approved light/dark pair
- **Optional Authentication**: username + password (cookie-based, default) or legacy bearer-token mode — chosen per instance on the login screen (bearer mode only offered when `CONSOLE_ALLOW_BEARER=true`)
- **Multiple Instances**: point one console at several Homer backends — enter the endpoint URL on the login screen and switch the active instance from the header picker (one active at a time)
- **Runtime Plugins**: add your own pages to the console without rebuilding it — drop an ES module into `console/public/plugins/`, name it in `CONSOLE_PLUGINS`, and it contributes a sidebar entry and its own route and calls the Homer API through the console's authenticated client. See [Plugins](#plugins).
- **Custom themes**: add your own look to the console without rebuilding it — drop a stylesheet into `console/public/themes/` and name it in `CONSOLE_THEMES`; it joins the theme dropdown and its tokens apply like a built-in theme's. Name one light and one dark theme in `CONSOLE_THEME_MODES` and they replace the built-ins entirely. See [Custom themes](#custom-themes).
- **Runners**: Read-only admin view of configured runners, registered pull-mode instances and the claim queue
- **Maintenance banner**: A red alert bar across every dashboard page while the backend's maintenance mode is on — scheduled runs are skipped and new runs wait in a queue until an admin turns it off. See [Maintenance banner](#maintenance-banner).
- **Collapsible sidebar**: A toggle at the bottom of the sidebar collapses it to icons only (the name stays as a tooltip and `aria-label`) so the page gets more room; the choice is saved in `localStorage` and restored on the next visit. The Questions and Continuations badges stay visible on the icon when collapsed.
- **Responsive Design**: Works on desktop, tablet, and mobile devices
- **Resilient Error Handling**: A render error in one dashboard page shows a recoverable error card in the content area while the header and sidebar stay usable; a last-resort boundary catches anything that would otherwise blank the whole app. When the API query a page renders from fails (the process list and detail, the Questions inbox), the page shows an alert naming the cause instead of loading forever or showing an empty list: the API rejected the request (sign in, or check the auth configuration); the API could not be reached (down, or its `corsAllowedOrigins` does not allow this console's origin), did not answer in time, or sent a response the console cannot read; the process was not found; or the HTTP status of any other failure.

## Accessibility

The console targets WCAG 2.1 AA. Tooling: `eslint-plugin-jsx-a11y` at
lint time (`npm run lint`) and `vitest-axe` at test time (`npm test`)
catch missing accessible names and other axe-detectable violations on
every commit. Keyboard navigation follows browser defaults — `Tab` /
`Shift+Tab` for traversal, `Enter` or `Space` to activate, `Esc` to
dismiss dialogs (Radix manages the focus trap). Status badges expose
their state as a screen-reader label (`aria-label="Status: …"`).
Status colors are intentionally theme-independent because status
semantics are; the rest of the UI uses semantic tokens that adapt to
the active theme.

`vitest-axe` runs in jsdom, so it cannot evaluate real CSS contrast —
contrast verification belongs to a browser-driven e2e pass and is out
of scope here.

## Quick Start

### Development

1. Install dependencies:
```bash
npm install
```

2. Create a `.env.local` file:
```bash
cp .env.example .env.local
```

3. Configure your environment variables in `.env.local`:
```env
CONSOLE_HOMER_API_URL=http://localhost:8080
CONSOLE_AUTH_ENABLED=false
```

4. Start the development server (`next dev` ignores `PORT` in `.env.local`;
   use `PORT=3001 npm run dev` for another port):
```bash
npm run dev
```

5. Open [http://localhost:3000](http://localhost:3000) in your browser.

### Docker

Image builds, the standalone container, and the full stack with the backend
are covered in [`DEPLOYMENT.md`](DEPLOYMENT.md) and
[`../DOCKER.md`](../DOCKER.md#quick-start). The root compose stack needs
`HOMER_ADMIN_TOKEN` set in `.env`, and its `.env.example` turns on Token
sign-in with that value; to use the console without logging in, set
`CONFIG_PATH=config/config-local.json` and `AUTH_ENABLED=false` instead (an
unsecured backend bound to loopback).

## Configuration

### Environment Variables

Runtime settings (`CONSOLE_HOMER_API_URL`, `CONSOLE_AUTH_ENABLED`,
`CONSOLE_ALLOW_BEARER`, `CONSOLE_ALLOWED_API_ORIGINS`, `CONSOLE_PLUGINS`,
`CONSOLE_THEMES`, `CONSOLE_THEME_MODES`) are read per request, so changing them needs no
rebuild; `PORT` is read when the server starts. See
[`DEPLOYMENT.md` § Environment Configuration](DEPLOYMENT.md#environment-configuration)
for the full reference. There is no runtime knob for the auth mode: the
console is cookie-only unless `CONSOLE_ALLOW_BEARER=true` adds the token
option to the login screen. **Each `CONSOLE_PLUGINS` entry is code that runs
with the operator's privileges in the console's origin** — see
[Plugins](#plugins). **Each `CONSOLE_THEMES` entry is a stylesheet that restyles
the console for everyone who uses it** — see [Custom themes](#custom-themes).

### Configuration File

Default settings are defined in `config/default.json`:

```json
{
  "api": {
    "baseUrl": "http://localhost:8080",
    "timeout": 30000,
    "allowedOrigins": []
  },
  "auth": {
    "enabled": false,
    "mode": "cookie",
    "allowBearer": false,
    "tokenKey": "homer_auth_token"
  },
  "polling": {
    "processListInterval": 5000,
    "processDetailActiveInterval": 2000,
    "processDetailInactiveInterval": 10000,
    "agentsCacheTime": 300000
  },
  "pagination": {
    "defaultLimit": 50,
    "maxLimit": 100
  },
  "ui": {
    "defaultTheme": "dark"
  },
  "plugins": {
    "entries": []
  },
  "themes": {
    "entries": []
  }
}
```

These defaults are compiled into the build; runtime env vars override them.
The UI reads every `api.*` field (`baseUrl` and `allowedOrigins`, fed by
`CONSOLE_HOMER_API_URL` / `CONSOLE_ALLOWED_API_ORIGINS`; `timeout`, the request
timeout in ms), every `auth.*` field (`enabled` and `allowBearer`, fed by
`CONSOLE_AUTH_ENABLED` / `CONSOLE_ALLOW_BEARER`; `mode`, the default instance's
login mode; `tokenKey`, the `localStorage` key prefix for bearer tokens),
`polling.agentsCacheTime` (React Query cache time for the
agents list and agent file listings), `pagination.defaultLimit` (page size for
process lists), `plugins.entries` (fed by `CONSOLE_PLUGINS`),
`themes.entries` (fed by `CONSOLE_THEMES`) and `themes.modes` (fed by
`CONSOLE_THEME_MODES`). The other `polling.*` fields, `pagination.maxLimit`,
and `ui.defaultTheme` are unused (the theme default is `dark`, or `System` when
a light/dark pair is configured).

## User Guide

### Viewing Processes

1. Navigate to **Processes** from the sidebar
2. Use status filters to show only specific process states:
   - **WORKING**: Currently executing processes
   - **FINISHED**: Successfully completed processes
   - **FAILED**: Processes that encountered errors
   - **KILLED**: Manually terminated processes
   - **CREATED**: Newly created processes
3. Use the **agent** filter — a searchable combobox — to narrow the
   list to one agent; type to filter the agent names, or pick **All
   agents** to clear it
4. Click on any process row to view detailed information
5. Use pagination controls to navigate through process pages

Each row shows a **Cost** column with the run's LLM harness (Claude/Codex/opencode) spend (a dash
when it has not spent anything); the filter row shows a **Total cost**
figure summing the currently filtered/paginated page — not every run
matching the filter across other pages.

A **Duration** column shows how long the run has been going: from its first
status-history entry to the last one once it has ended, and to now while it
still runs. The **Last Command** cell carries one dot per command that
executed — green for success, red for failure, a pulsing blue dot for the one
running now — so a run's progress reads at a glance. Status is a single icon
whose tooltip and accessible name give the status word; the failure-reason and
open-question badges still sit beside it. There are no **Status Time** or
**Purge** columns; the run's own page keeps its status timeline and purge time.
**Kill** and **Retry** are icon-only buttons with tooltips.

Status filters, sort order, and paging are kept in the URL, so the
list view is shareable as a link and browser Back restores it. A
`parent=<id>` URL parameter narrows the list to the direct children of
one run and shows a removable **Children of #id** chip.

The list opens as the flat list of **all runs**. **Root runs** / **All
runs** buttons switch to the **roots view** (`view=roots` in the URL): one
row per flow, each showing the flow's run count (its descendants below the
root), their total cost and the flow's aggregate state — running, waiting
on a question, failed, or finished. The choice is kept in the URL like the
other filters. A **Children of #id** filter drops the roots filter and its
Flow column; **Root runs** clears it and returns to the flow view.

Each run shows its **subject tags** (`key:value`) as chips beside the
agent name. A run whose agent declares a `group` also shows that group
beside the agent name. Clicking a tag chip adds it to the filter (`tag=` in the
URL as one comma-separated value, ANDed, so a run must carry every selected tag); each
active tag appears as a removable badge in the filter row, cleared
individually or via **Clear**.

A run that **failed before it started** — a claim timeout, a `SKIP_CURRENT` lock
collision, a parallel-run cap or a full queue — shows a short reason beside its
failed status icon, so the list says why without opening the run. The row truncates
it; hover for the full sentence, which the run's own page shows in full.

### Process Details

- **Back button**: A **Processes** back button above the title returns to the
  process list, restoring its filters and paging (via browser history
  where the console has in-app navigation history in this tab, otherwise
  a plain link to the unfiltered list — e.g. a shared link opened directly)
- **Agent**: The agent name in the header links to that agent's detail
  page; a run whose agent has since been deleted still links, and the
  target shows an "Agent not found" state. Beside the name, the header
  shows the agent's `group` when the agent has one, and nothing when it
  is ungrouped; the group is read from the agent's current definition,
  so it can change or disappear on past runs
- **Parent run**: When another run started this one (a `RunAgent`
  command, dispatch, continuation, or an external caller's
  `X-Homer-Parent-Process-Id` header), the header links to that parent;
  a retry or resume keeps the source run's parent. Beside it, **Root
  run #N** links to the first run of the flow, when one is recorded
- **Failed before it started**: A run that ended `FAILED` without running a
  single command shows a **Failed before it started** box under the header with
  the stored reason — a claim timeout, a `SKIP_CURRENT` lock collision, a
  parallel-run cap or a full queue. The box appears only when the server stored
  a reason, so a run that failed while it was running shows none
- The run's cost carries a source label — `proxy` when priced by the
  server's `llmPrice.sessionSpend` endpoint, `CLI estimate` when it is
  the harness's own figure, or `mixed` when a run had both. An attempt
  with no cost source charges nothing, so the run header never shows
  one. Each step's cost carries its own label, which reads `not
  reported` when the harness reported no cost and no session-spend
  endpoint answered (nothing was charged). A step whose figure is
  degraded also shows why: `fallback: <reason>` when the endpoint could
  not be used, or the reason alone (`incomplete: 3 of 5 calls ingested`)
  when a session figure was charged before the endpoint had ingested
  every call
- **Tags**: The run's subject tags appear as chips under the header;
  clicking one opens the process list filtered to that tag
- **Child runs**: Runs started by this one are listed with their agent,
  status and tags, each linking to its own detail page; a **View all**
  link opens the filtered process list when there are more than fit inline
- **Flow**: On a root run, a flat list of every visible descendant in id
  order — agent, status, cost and parent — replacing the capped direct-children
  card, with a toggle that hides the orchestrator's own resume segments
  (runs of the same agent as the root). A descendant's page keeps the
  child-runs card and links up to its root beside the parent link
- **Status Timeline**: See the history of status changes with timestamps
- **Executions**: View all command executions in run order (the currently
  running command appears last, with a live spinner) with:
  - Exit-state icon (✓ success, ✗ failure, or a "Skipped" dash icon for
    commands skipped by a condition) next to the exit code, with the
    start time, end time, and duration shown alongside it in the header row
  - **View Output**: Click an execution to expand it and see Stdout/Stderr
    tabs inline
    - Switch between stdout and stderr using tabs
    - The tab bar has a download button (labelled with the file name) for
      the active tab's artifact, and a manual **Refresh** button to reload
      content; a **Live** badge shows while the process is still running,
      and the tabs pick up the completed snapshot automatically once the
      process reaches a terminal status
  - Error messages (if any)
- Click on an execution to expand and see full details. LangGraph runs show
  a per-node workflow status overlay (drawn natively from the graph's
  `topology`, following the console theme) inside the command's execution
  detail (fetched only while expanded), including per-sub progress with per-command
  indicator dots and a finished counter for fan-out nodes — a fan-out member whose sub-agent run has
  finished turns green individually (amber-dashed border until the join
  resolves), and a node keeps the sub runs it dispatched, so the counter and
  the in-graph dots cover the whole run rather than only the live park. A node
  that has served more fan-outs than the runtime's per-node history cap shows
  only its most recent ones, and the overlay says so ("Older sub history not
  shown for: …"). The
  per-sub lines sit behind the counter as a collapsible list (collapsed by
  default; the graph's dots carry the same progress). Edges the run has
  actually taken are drawn green. Clicking a node that
  dispatched sub-agent runs opens a popover listing them, each linking to its
  agent and its process. The console draws at most 200 nodes and 300 edges
  (labels truncate at 40 characters); above that the overlay falls back to the
  node-status list. That is a console rendering cap, separate from the API's
  200-node / 1000-edge bound. Failed, killed and
  unknown-status LangGraph runs offer a Resume action. While the LangGraph command is still running, the live-command box
  offers a "Show workflow" toggle with the same overlay.
- **Custom Artifacts**: Use the "Download Artifact" button to download any artifact file by path

### Messages to a running run

A running run's page has a **Messages** card with a text box and **Send**,
shown only while the run is `CREATED` or `WORKING` and only to a user who may
also kill it (`admin`, the run's owner, or `read` + `control` grants on its
agent). A sent message waits in the run's inbox as **Queued** until the run
takes it (**Delivered**) or the run ends first (**Undelivered**); the card
lists every message oldest first with its status, sender and time, and keeps
the history once the run has ended. A refused send (run ended, limit reached,
text too long) shows the server's reason under the box and keeps what you
typed.

### Questions inbox

Navigate to **Questions** from the sidebar to see the open questions
across every running process in one place, oldest first. It lists up to
100; with more, a note says how many are waiting, and answering the shown
ones brings the rest into view. Questions with a dispatch
annotation show which agent will start when you answer, so you can
review the intent before committing. You can also click **Cancel question**
on an open dispatch-bound question, which expires it and prevents the
target agent from starting.

### Continuations

Navigate to **Continuations** from the sidebar to see completion
continuations — parked workflows that start a follow-up agent once a
watched run finishes. The page lists pending continuations and, for
each, shows the watched run, the target agent that will fire, and the
origin run that registered it. Failed continuations appear in a
separate **Failed to fire** card with their error. You can click **Cancel
continuation** on a pending continuation (with a confirmation prompt),
which stops the target agent from starting when the watched run
finishes. Each card shows the newest 100 rows; when more exist, it says
"Showing the newest N of total" so no pending or failed continuation is
silently left out. The sidebar's **Continuations** entry carries a badge
with the number of pending continuations, hidden when none are pending;
like the Questions badge it stays visible when the sidebar is collapsed.

### Schedules

Navigate to **Schedules** from the sidebar (admin only) for an overview
of every agent with a `cron` field: its cron expression, timezone, next
run time, and last run (linking to that run's process detail page when
one exists). Sorted by soonest next run.

Each row's **Run** button fires that agent immediately through the same
path a cron tick uses — source `cron`, owner `system:cron`, and no
parameter prompt. A required input without a default never shows a Run
button at all: such a schedule is refused when the agent is loaded, so
the agent has no cron to list. A fire refused by the cost cap or the
cooldown starts no run and is only logged, never surfaced — the dialog
closes with no error. A lock collision (`SKIP_CURRENT`), a `REJECT`
parallel-run cap or a full `maxQueuedRuns` queue does start a run, which
ends `FAILED`; the row's Last run cell links to it. A confirmation dialog guards the
click, since the run starts with no parameter dialog. Once the run is
the agent's most recent, the row's **Last run** cell links to its
process id.

### Runners

Navigate to **Runners** from the sidebar (admin only) for a read-only
snapshot of the claim queue system: a table of every configured runner
with its kind, tags and permits in use; a table of each external
runner's registered pull-mode instances, with their tags, session
capacity, last-seen and registered times, and a Draining badge for an
instance winding down; and a breakdown of runs currently waiting for a
runner, by the alias or tags they need, with the pending count and the
oldest wait. Both endpoints poll every 5 s. No actions — this page
shows state, it does not change it.

### Maintenance banner

While the backend is in maintenance mode (an admin turns it on through
`POST /api/v1/maintenance`), a red alert bar sits at the top of every
dashboard page, above the header:

> Maintenance mode is on. Scheduled runs are skipped and new runs wait in a
> queue until an admin turns it off.

It reads the `maintenance` flag on `GET /api/v1/health`, which is polled
every 15 s, so it appears and clears on its own without a reload. While the
mode is off the bar renders nothing at all; a backend older than the flag
omits it, and the bar stays hidden. It is read-only — the console has no
control to toggle the mode (out of scope for the feature; use the API).

What the mode means for what you see: a run already `WORKING` keeps running
and finishes normally; a cron schedule does not fire and a fire missed during
the window is not replayed when it ends; any other start — the Run button, the
API, MCP, a `!<agent>` hop, a dispatch, a continuation — waits in a queue and
starts, in arrival order within one release drain, once an admin turns the
mode off (rapid re-toggles overlap drains, so the order across drains is not
guaranteed). A start that
arrives after the queue is full is refused with `429`, and one that waits
longer than the configured limit with `503`; neither creates a run.

### Answering agent questions

A running command can ask the operator a question (see the backend
`AGENT.md` "Asking the operator questions"). Questions appear both in
the **Questions** inbox and in the **Questions** panel on the process
detail page:

- Question text renders as Markdown (raw HTML is escaped, never executed;
  images are not rendered — their alt text is shown instead, so agent-authored
  text cannot beacon your browser to a remote URL)
- Predefined options render as one-click answer buttons; a free-text
  field is always available
- Open questions bound to a dispatch can be cancelled (with a confirmation
  prompt); cancelling expires the question and the target agent never starts
- Answered questions show the submitted answer; questions whose run
  ended unanswered grey out as **Expired**
- The panel updates live via the `question.asked` / `question.answered` /
  `question.expired` SSE events; answering an already-answered question
  (e.g. from a stale second window) surfaces an inline conflict notice

### Live LLM output

For a `!<claude>`, `!<codex>` or `!<opencode>` step, the "Show output" toggle on the
live-command box (or clicking a finished step's execution entry to
expand it) expands an inline Conversation view built from the server's
canonical event stream (`GET .../llm-events`) — live while the command
runs, or the completed run once it has finished. Live shows a Live
header with the model, turn count, elapsed time, and a spinner on the
in-flight tool call; opening it late still shows the whole conversation
history. The summary shows the model, a status badge, token usage
(with reasoning tokens shown separately when the harness reports them),
and the cost the step was charged, the same figure as the step header (the
session-spend or CLI figure) — or "not reported" when nothing priced the step. Conversation /
JSON / Raw view toggles are available both live and post-mortem. The
event buffer is capped at 5000 events and 16 Mi characters per step; once a
long-running or looping command exceeds either, the oldest events are dropped
and the view shows a muted "Earlier events not shown" notice. A single event
over 64 Ki characters shows a note in place of its input, output, text or result
payload; an event oversized in an id, name, model or similar identity field
becomes a generic error note instead; the command's raw output artifact keeps the
full content either way.

### Killing Processes

1. Click the **Kill** button next to a WORKING or CREATED process
2. Confirm the action in the dialog
3. The process status will update automatically

### Retrying Processes

A **Retry** button is available on the process list and on the process
detail page for any process in a `FINISHED`, `FAILED`, or `KILLED` state.
It calls `POST /api/v1/processes/{id}/retry` and navigates to the newly
created process.

### Runner details

Every run that got a session and created its workspace shows a
**Runner** panel on the process
detail page with the backend that served it (local, ssh, external or
Kubernetes). An **admin** sees the operator-chosen backend alias; any
other viewer gets the generic backend type under a **Backend** label
instead, because the alias can name a host, tenant or environment. For a
Kubernetes run the panel additionally
shows the pod's name (copyable), phase (Creating / Pending / Running /
Ready / Succeeded / Failed / Deleted / Kept for debug) and timestamps,
plus any startup failure details; a non-Kubernetes run has no pod, so it
shows the backend alone. The process list mirrors this in its **Runner**
column: the alias for an admin and the generic backend for everyone
else, with a pod-phase dot only when there is a pod. The list also shows
an **orphan pod sweep** status banner (scanned / deleted / error counts
and last-run time) whenever a Kubernetes runner is configured and the
backend has swept at least once.

### Browsing Agents

1. Navigate to **Agents** from the sidebar
2. Pick a tab: **All**, plus one tab per distinct agent `group`, sorted by
   name. **All** lists every agent, *including the ones that declare no
   group* — an ungrouped agent appears under no group tab. The selected tab
   is kept in the URL's `group` param (a group that no longer exists falls
   back to **All**), so a grouped view is shareable
3. Use the search bar to filter agents by name **within the selected tab**;
   the search term is kept in the URL's `q` param, so the filtered list is
   shareable and browser Back restores it
4. Click **Reload** to have the backend re-scan the agents directory; the
   result banner lists agents added, updated, and removed
5. View agent details:
   - A back button above the title returns to the agents list, restoring
     the search (via browser history where the console has in-app
     navigation history in this tab, otherwise a plain link to the
     unfiltered list — e.g. a shared link opened directly)
   - Description (displayed below the agent name when provided)
   - Required and optional parameters
   - Parameter types (legacy `STRING`/`NUMBER`/`ANY`, or typed `string`/`integer`/`number`/`boolean`)
   - Transformer details:
     - **Direct pass-through**
     - **Extract JSON path: `{path}`**
     - **Regex match: `{pattern}` (group N)**
   - **Workflow graph** — for agents with a `!<langgraph>` command, the detail page
     renders the workflow from its `topology` (nodes and edges), fetched from
     `GET /api/v1/agents/{name}/langgraph`, in a collapsible section that is
     collapsed by default. Solid edges are fixed (`add_edge`), dashed ones are
     conditional (a router's choice, labelled with its route key when it has
     one); a legend under the graph explains fixed vs conditional edges. A node
     that can route back to itself carries a small loop on its right side. A graph above the console's 200-node / 300-edge
     drawing cap shows a "Graph could not be drawn" note instead.
   - **Run history** — a paginated table of processes created by this agent;
     the current page is kept in the URL's `offset` param

### Running an agent

Click **Run** on an agent card, or on the agent detail page, to open the
Run dialog. Each input shows its declared default and allowed values (a
secret input's default and allowed values are never shown). Fill in the declared query/body/header parameters (or switch
to the **Export Curl** tab to copy an equivalent `curl` command) and
submit to call `POST /api/v1/agent/{name}`; the console then navigates to
the new process's detail page. Before submitting, the dialog pre-checks each
value against its declared type only, as a convenience: constraint fields
(`values`, `pattern`, `min`/`max`, …) are checked server-side, and the
server's `400` is authoritative.

The exported `curl` command carries the auth header as a
`Authorization: Bearer $HOMER_AUTH_TOKEN` placeholder rather than your real
token — export `HOMER_AUTH_TOKEN` in the shell environment before pasting so
it resolves. For an agent whose body is defined by an `inputSchema` (which
the console cannot enumerate), the command omits the JSON body and the tab
notes that you must add it manually. The command targets the active
instance's backend URL.

### Authoring agents

Click **New agent** on the Agents page to create an agent directory with
a minimal `agent.yaml` (name must match `^[a-zA-Z0-9_-]+$`); you're
redirected to that agent's **Edit** page. Writable agents also show an
**Edit** button on their detail page. The editor has three panes:

- A **file tree** of the agent's directory (create, select, delete files)
- A **Monaco-based** code editor for the selected file, with Save and
  schema-validation error reporting on save
- A **debug run** panel — run any one command of the agent directly, with
  editable env vars and query params, and inspect its live log output

### Viewing Costs

1. Navigate to **Costs** from the sidebar
2. The **Global spend (last 60 s)** card shows the rolling spend across
   all agents, with a percent-of-cap progress bar when
   `costLimits.globalUsdPerMin` is configured server-side
3. The **Per-agent spend (last 24 h)** table shows one row per agent
   that has either spent in the last 24 h or has a configured
   `perAgentUsdPerDay` cap, sorted alphabetically, and ends with a bold
   **Total** row summing the displayed spend
4. The page polls `GET /api/v1/costs` every 5 s; the 60 s / 24 h
   windows are rolling, but spend is recorded in a persistent cost
   ledger and survives server restarts
5. The **Monthly costs per agent (UTC)** card shows one row per agent
   for a selected UTC month, plus a total row, backed by the
   persistent cost ledger (`GET /api/v1/costs/monthly`); a month
   picker (fed by `availableMonths`) lets you switch months
6. The **All-time costs per agent** card shows the same layout for
   all-time per-agent totals from the persistent cost ledger
   (`GET /api/v1/costs/totals`)
7. If `costTrackedTagKeys` lists one or more keys, a **Cost by
   tag** card appears with a key selector and a per-value spend table
   (backed by `GET /api/v1/costs/by-tag/{key}`); a note appears under
   the total when the backend summed only the newest 10,000 runs
   carrying the key; the card is absent when no keys are configured

### Authentication

If authentication is enabled (`CONSOLE_AUTH_ENABLED=true`), the console is
cookie-only by default; the login screen only offers the bearer/token
toggle when `CONSOLE_ALLOW_BEARER=true`:

- **`cookie` (default, recommended)** — username + password. The backend
  must be configured with `apiAuth: !<password>` (or `!<combined>`
  containing a `password:` scheme). A successful login mints an
  `HttpOnly` session cookie (`Secure` and `SameSite=Lax` by default —
  both configurable server-side via `cookieSecure` / `cookieSameSite`,
  and cross-origin deployments use `SameSite=none`); no token is ever
  exposed to JavaScript. Operators generate bcrypt hashes for
  `homer-config.yaml` with `app/agentdebug.sh hash-password`. See
  [`../app/SERVER.md`](../app/SERVER.md) → "Password Authentication" and
  [`../docs/adr/0011-cookie-based-console-auth.md`](../docs/adr/0011-cookie-based-console-auth.md).
- **`bearer`** (requires `CONSOLE_ALLOW_BEARER=true`) — legacy
  single-token flow. The username field is hidden and the token is
  stored in `localStorage`. Use for transition only; CLI / MCP / webhook
  clients are the supported audience for bearer.

Opening a console page without a session sends you to the login screen with
the page you asked for and a neutral "Sign in to continue" prompt. The "Your
session expired" banner is reached only when the console itself redirected
for a rejected session — the only thing that puts `?expired=1` on the login
URL. The flag is part of the address, so typing or bookmarking it shows the
banner with no session behind it; it changes the wording, nothing else.
Signing in returns to that page — the return path is accepted only
as a same-origin relative console path, so an absolute or protocol-relative
value is ignored rather than followed. `CONSOLE_ALLOW_BEARER=true` also makes
**Token** the mode the form opens in, because it marks a bearer-only backend;
an explicit `?mode=` parameter or the active instance's saved mode still wins.

The console reads the caller's capabilities from `GET /api/v1/auth/me`
(`{username, role, grants}`) and hides controls the user can't use:
admin-only nav (Costs, Schedules, Reload, Runners), per-agent Edit/Debug (require
`edit`/`debug` on that agent), per-process Kill (requires `control` on
the agent, or ownership of the run), and Retry (requires `run` on the
agent). This is a UX convenience only — the backend enforces the same
rules and returns
403/404 regardless of what the UI shows.

The Processes page is shown after a successful login. A cookie session lasts
`sessionTtlMin` (12 h by default) and survives backend restarts; rotating
`sessionSecret` without `sessionSecretPrevious` ends it early. There is no
logout button in the UI; when a session is rejected, the console redirects to
the login screen.

### Multiple instances

One console deployment can talk to several Homer backends and switch the
active one (one active at a time — not a merged dashboard).

- **Login screen** — defaults to the active instance (endpoint folded behind
  a **Change** disclosure); expand it to point at a different backend and pick
  the auth mode (Password ↔ Token). On submit the console upserts the instance,
  makes it active, and runs the normal login. An https console warns inline if
  you enter an `http://` endpoint (the browser blocks mixed content).
- **Instance picker** — the server icon in the header lists known instances,
  switches the active one (resetting cached data and re-probing auth), and
  offers "Add instance" (routes to the login screen). Cookie sessions are
  per-origin in the browser, so switching back to an already-authed instance
  needs no re-login.
- **Per-instance tokens** — bearer tokens are namespaced per instance
  (`homer_auth_token::{instanceId}`); a legacy single-instance token is
  migrated into the seeded default instance on first load, so upgrades don't
  log bearer users out.
- **Backend setup for cross-origin** — each instance must allow the console
  origin in `server.corsAllowedOrigins`, and cookie-mode instances must run
  HTTPS with `cookieSameSite: none`. See
  [`../app/SERVER.md`](../app/SERVER.md) → "Cross-origin / multi-instance
  consoles" and
  [`../docs/adr/0028-cross-origin-cookie-auth-samesite.md`](../docs/adr/0028-cross-origin-cookie-auth-samesite.md).

### Theme Toggle

- Click the theme icon in the header to open a dropdown with the five built-in
  options: Light, Dark, System, Terminal, and Windows 95
- Your preference is saved and persists across sessions
- Any theme configured through `CONSOLE_THEMES` appears in the same dropdown,
  below the built-ins, labelled with its configured name — see
  [Custom themes](#custom-themes)
- When the operator has named a light/dark pair in `CONSOLE_THEME_MODES`, the
  built-in options are replaced: the dropdown lists only the `CONSOLE_THEMES`
  entries plus `System`, which follows your OS colour scheme

## Custom themes

The console can load **themes** — stylesheets you write and deploy yourself,
without rebuilding the console. A theme is a CSS file placed under
`console/public/themes/`, and `CONSOLE_THEMES` lists the same-origin paths that
define them:

```
CONSOLE_THEMES=brand-dark:/themes/brand-dark.css,acme:/themes/acme.css
```

An entry is `<name>:<path>`. The name is what appears in the theme dropdown and
what `next-themes` stores in `localStorage`, so it is restricted to `[a-z0-9-]+`
and may not collide with a built-in (`light`, `dark`, `terminal`, `win95`) or
with `system`.

**Only same-origin `.css` paths are accepted.** A remote URL, a protocol-relative
`//host/a.css`, a `data:`/`blob:` URL, anything containing `..`, a path that does
not end in `.css`, a name outside `[a-z0-9-]+`, and a name that is already taken
are each rejected and logged with a reason; the remaining entries still load.
Theme files come off the console's own disk — there is no network fetch to
intercept, no foreign origin to allowlist, and `style-src 'self'` already covers
the `<link>`.

### Replacing the built-ins with a light/dark pair

A company that wants only its approved themes selectable sets
`CONSOLE_THEME_MODES` to name one of its `CONSOLE_THEMES` entries as the light
theme and one as the dark theme:

```
CONSOLE_THEMES=corp-light:/themes/corp-light.css,corp-dark:/themes/corp-dark.css
CONSOLE_THEME_MODES=light:corp-light,dark:corp-dark
```

With that pair in place the theme dropdown and the theme list `next-themes`
knows contain **only** the `CONSOLE_THEMES` entries plus `System`; Light, Dark,
Terminal and Windows 95 are gone. `System` follows the operating system's
colour scheme and applies `corp-light` under a light scheme and `corp-dark`
under a dark one, and it is the default — so a visitor who never picks a theme
gets the pair's theme, never a hidden built-in. A stored preference for a
built-in falls back to `System` the next time the console loads.

Both names must be entries `CONSOLE_THEMES` actually defines; the setting is
**all-or-nothing**. A missing `light:` or `dark:` prefix, an unknown name, a
name that is not a `CONSOLE_THEMES` entry, a built-in name, a duplicate or an
extra segment discards the whole setting: the built-ins stay, the dropdown is
unchanged, and the reason is logged once server-side under `[themes]`. Half a
pair never takes effect, so a typo cannot hide themes a user is currently on.
Leaving the variable unset (or empty) is not an error and changes nothing.

### Writing a theme file

A theme redefines the same tokens the built-in themes define in
[`app/globals.css`](app/globals.css), under a selector matching its name. The
tokens are HSL triples without the `hsl()` wrapper, plus two non-colour values:

```css
.brand-dark {
  --background: 222 47% 8%;
  --foreground: 0 0% 100%;
  --primary: 262 83% 58%;
  --primary-foreground: 0 0% 100%;
  --muted: 222 30% 16%;
  --muted-foreground: 220 9% 70%;
  --border: 222 25% 20%;
  --radius: 0.25rem;
  --font-family-sans: "Brand Sans", sans-serif;
}

@font-face {
  font-family: "Brand Sans";
  src: url("/themes/fonts/BrandSans-Regular.woff2") format("woff2");
}
```

The full token list is whatever `globals.css` defines per theme — colour tokens
(`--card`, `--accent`, `--destructive`, `--ring`, …) as well as `--radius` and
`--font-family-sans`. **A theme file that omits a token inherits the `:root`
value**, which is the light theme's, not the previously selected theme's — so
define the whole set rather than the few you intend to change.

Components read semantic classes (`bg-primary`, `text-foreground`, …), so they
follow a theme without changes. `@font-face` rules must point at same-origin
files: put the font next to the CSS under `public/themes/`, and note that
`font-src 'self'` is what allows it — no CSP change is involved.

### Getting the file onto disk

`console/public/themes/` is served by the console at `/themes/`. Anything in it
is gitignored, so themes are not part of the console's source.

- **Local dev** — drop the file in, refresh.
- **Docker** — the directory is inside the build context, so a file copied in
  before `docker build` ships in the image. Mounting a volume over
  `/app/public/themes` works too — the standalone server reads `public/` from
  disk per request, and the Dockerfile copies that directory explicitly (Next's
  standalone output would otherwise leave it out) — see `/DOCKER.md`
  § Console themes in the image.

Behavior worth knowing:

- A theme whose stylesheet is **missing (404) is skipped with a logged reason**
  and its name applies no tokens, so every token falls back to the `:root`
  (light) values — the same partial-token rule as above. A broken theme never
  breaks the console. Check the browser console for `[themes]` messages.
- Rejected entries are logged where the console runs: server-side by the root
  layout, so they surface in the container's logs.

### Trust model — read before configuring this

**A theme restyles the console for every user of it.** A stylesheet cannot run
code, so a theme gets no access requirement — there is nothing to authorize
against the caller, and the operator deploying the theme is the only party who
could. What it can do is **redress** the console: position, hide or overlay UI
so that a user acting on what they see acts on what the theme author decided
they should see. Putting a file in `public/themes/` and naming it in
`CONSOLE_THEMES` is therefore an act of trust in its author, not a styling
preference.

- **A theme is not confidential.** `CONSOLE_THEMES` is serialized into
  `window.__HOMER_CONFIG__` on every page, so any client — including an
  unauthenticated one on `/login` — can read the configured names and paths; and
  `public/themes/` is static, so anyone who can reach the console can download a
  theme and its fonts. This is the same exposure the console already has for its
  own pages and for plugins. If a theme's path or source must stay private, put
  the console behind an authenticating proxy — nothing inside the console can
  withhold a static file from an anonymous client.
- **A missing theme file is not an error you have to hunt for**, but it is
  silent by construction: the browser drops a 404'd stylesheet, its class
  defines no tokens, and every token falls back to the `:root` (light) values.
  The browser console reports it under `[themes]`
  once per load; the container logs report rejected entries.
- **`public/themes/` must not be writable by anything but the operator.** A
  process that can write there — an agent workspace, a shared volume, a build
  step taking untrusted input — can redress the console for every user.

If you need a theme from an author who should not be able to restyle the
console, this mechanism is the wrong tool — see
[ADR-0069](../docs/adr/0069-console-themes-are-runtime-loaded-stylesheets.md),
which records the data-only alternative to revisit first.

## Plugins

The console can load **plugins** — pages you write and deploy yourself, without
rebuilding the console. A plugin is a single ES module file placed under
`console/public/plugins/`, and `CONSOLE_PLUGINS` lists the same-origin paths to
import:

```
CONSOLE_PLUGINS=/plugins/fleet.js,/plugins/costs.js@admin
```

An entry is a path, optionally followed by `@<requirement>` — `admin`, or one of
the Homer Actions (`read`, `run`, `control`, `edit`, `debug`). Without one, any
authenticated user can open the plugin.

**Only same-origin `.js`/`.mjs` paths are accepted.** A remote URL, a protocol-relative
`//host/p.js`, a `data:` URL, or anything containing `..` is rejected and
logged. Plugin code comes off the console's own disk — there is no network fetch
to intercept, no foreign origin to allowlist, and `script-src 'self'` already
covers the import.

Each module is imported once per session. Its default export declares the route
it claims, its sidebar label, and a `mount` function that renders it (access
requirements come from `CONSOLE_PLUGINS`, never from the module):

```ts
export default {
  route: '/myfleet',   // mount point: single lowercase segment, must not collide with a console route
  title: 'My Fleet',   // sidebar label
  mount(el, ctx) {
    el.textContent = `hello ${ctx.user?.username ?? 'anonymous'}`;
    return () => {};   // cleanup, called when the user navigates away
  },
};
```

`ctx.api` is the console's own Homer API client, already authenticated and bound
to the active instance, so a plugin's calls carry the logged-in user's grants.
`ctx` also carries `user`, `theme`, and the sub-path members below.

**React is not required.** The console hands the plugin a DOM element and calls
`mount`; whether that element is filled by `createRoot(el).render(...)`, by
`document.createElement`, or by anything else is the plugin's choice. A React
plugin bundles its own React and is not coupled to the console's version.

### Getting the file onto disk

`console/public/plugins/` is served by the console at `/plugins/`. Anything in
it is gitignored, so plugins are not part of the console's source.

- **Local dev** — drop the file in, refresh.
- **Docker** — the directory is inside the build context, so a file committed or
  copied in before `docker build` ships in the image. Mounting a volume over
  `/app/public/plugins` works too, since the standalone server reads `public/`
  from disk per request.

### Sub-routes

`route` is only the plugin's **mount point** — every path below it reaches the
same plugin, so `/myfleet/a` arrives with `ctx.path === '/a'`. Routing
below that point, including its own 404, is the plugin's business.

Two context members cover navigation:

| Member | Use |
| --- | --- |
| `ctx.path` | the current sub-path; `''` at the plugin root |
| `ctx.navigate('/a')` | client-side navigation relative to the plugin's route |

Use `ctx.navigate` rather than an `<a href>` — the anchor triggers a full page
load of the whole console and re-imports the plugin module.

**`mount` is called again on every sub-path change.** The App Router discards
the page subtree when the route params change, so the plugin is remounted and
its tree state resets. Module scope survives (the module is imported once per
page session), so that — or `sessionStorage` — is where state that must outlive
in-plugin navigation belongs. In development React StrictMode also invokes
`mount` twice per mount, so it must be idempotent and its cleanup must actually
release what it took.

Two working examples:

- [`example/console-plugin-react/`](../example/console-plugin-react/README.md)
  — React, with its own sub-routes and a build step.
- [`example/console-plugin-vanilla/`](../example/console-plugin-vanilla/README.md)
  — one file, no framework, no build, no dependencies.

Behavior worth knowing:

- A plugin that is missing, malformed, or claims a taken route is **skipped with
  a logged reason**; it never breaks the console. Check the browser console for
  `[plugins]` messages when a plugin does not appear.
- A requirement is enforced **before the module is imported**: the console never
  loads a gated plugin into the session of a user who lacks the requirement, so
  its code never runs with their credentials, it gets no sidebar entry, and its
  route renders the same not-found page as a nonexistent plugin.
- The requirement lives in `CONSOLE_PLUGINS`, not in the plugin's manifest. It
  has to: a manifest cannot be consulted without importing the module it is in,
  which is the very thing being gated. It also puts the access decision with the
  operator deploying the plugin rather than with its author.
- Plugin routes are resolved after all built-in routes, so a plugin can never
  shadow a console page.

### Trust model — read before configuring this

**A plugin runs in the console's own origin with the operator's privileges.** It
can read the per-instance bearer tokens in `localStorage` and act as the
operator against every configured Homer instance. Putting a file in
`public/plugins/` and naming it in `CONSOLE_PLUGINS` is the same act of trust as
committing code to `console/`.

- A requirement is **not** a sandbox — it decides who may load a plugin, and
  grants nothing beyond that. Once loaded, plugin code has the operator's
  privileges, and the backend authorizer stays the real boundary for the data it
  reads.
- **A requirement is not confidentiality either.** `CONSOLE_PLUGINS` is
  serialized into `window.__HOMER_CONFIG__` on every page, so any client —
  including an unauthenticated one on `/login` — can read the configured paths
  and their requirements; and `public/plugins/` is static, so anyone who can
  reach the console can download a plugin's source. This is the same exposure
  the console already has for its own pages: `/costs` and `/schedules` are
  hidden from non-admins, but their code ships in the client bundle to everyone
  and `/_next/static/**` needs no credentials. If a plugin's path or source
  must stay private, put the console behind an authenticating proxy — nothing
  inside the console can withhold a static file from an anonymous client.
- **`public/plugins/` must not be writable by anything but the operator.** It is
  a directory whose contents execute with the operator's session; a process that
  can write there — an agent workspace, a shared volume, a build step taking
  untrusted input — can take over the console.
- A plugin that calls a non-Homer origin needs that origin in
  `CONSOLE_ALLOWED_API_ORIGINS`, which pins `connect-src`.

If you need plugins from authors who should *not* hold the operator's session,
this mechanism is the wrong tool — see
[ADR-0059](../docs/adr/0059-console-plugins-are-runtime-loaded-modules.md),
which records the sandboxed-iframe option to revisit first.

## API Endpoints Used

| Endpoint | Purpose |
|----------|---------|
| `GET /api/v1/status/all` | Fetch process list with filters |
| `GET /api/v1/status/:id` | Fetch single process details |
| `POST /api/v1/processes/:id/retry` | Retry a finished/failed/killed process |
| `GET /api/v1/agents` | Fetch agents list |
| `POST /api/v1/agent/:name` | Run an agent |
| `POST /api/v1/agents` | Create a new agent directory (New agent dialog) |
| `POST /api/v1/agents/reload` | Re-scan the agents directory |
| `POST /api/v1/agents/:name/cron-run` | Fire an agent as its cron would (Schedules page Run button) |
| `GET /api/v1/agents/:name/langgraph` | Fetch the LangGraph workflow topology for an agent |
| `GET/PUT/DELETE /api/v1/agents/:name/files[/:path]` | List, read, write, and delete an agent's files (Edit page) |
| `GET /api/v1/processes/:id/langgraph` | LangGraph per-node run status (workflow overlay) |
| `POST /api/v1/processes/:id/resume` | Resume a failed, killed or unknown LangGraph run |
| `POST /api/v1/agents/:name/debug` | Run a single agent command for debugging |
| `GET /api/v1/schemas/agent` | Fetch the agent JSON schema (Edit page validation) |
| `DELETE /api/v1/kill/:id` | Kill a process |
| `GET /api/v1/heartbeat` | Validate authentication token |
| `GET /api/v1/health` | Runner/health status (used for the orphan-pod-sweep banner) |
| `POST /api/v1/auth/login` | Mint a session cookie (cookie auth mode) |
| `POST /api/v1/auth/logout` | Clear the session cookie (idempotent) |
| `GET /api/v1/auth/me` | Probe the cookie session and return `{ username, role, grants }` |
| `GET /api/v1/artifacts` | Fetch or download artifact files from process executions |
| `GET /api/v1/artifacts/list` | List artifact files for a process |
| `GET /api/v1/costs` | Rolling-window LLM harness (Claude/Codex/opencode) spend snapshot (used by `/costs` page) |
| `GET /api/v1/costs/monthly` | Persistent per-agent costs for a UTC month (used by `/costs` page) |
| `GET /api/v1/costs/totals` | Persistent all-time per-agent costs (used by `/costs` page) |
| `GET /api/v1/costs/by-tag/{key}` | Spend broken down by the values of one configured subject-tag key (used by the Cost by tag card on `/costs`) |
| `GET /api/v1/runners` | Configured runners and external instances (used by `/runners` page) |
| `GET /api/v1/runners/queue` | Pending runner claims (used by `/runners` page) |
| `GET /api/v1/continuations` | Fetch continuations with optional status/agentName filter |
| `DELETE /api/v1/continuations/:id` | Cancel a pending continuation |
| `GET /api/v1/questions` | Fetch questions with optional status/processId filter |
| `POST /api/v1/questions/:id/answer` | Answer an open question |
| `GET /api/v1/status/:id/messages` | List a run's operator messages |
| `POST /api/v1/status/:id/messages` | Send a message to a running run |
| `DELETE /api/v1/dispatches/:id` | Cancel an open dispatch-bound question |
| `GET /api/v1/stream/processes` | SSE: `process.status`, `process.cmd`, `question.asked`, `question.answered`, `question.expired`, `message.queued`, `message.delivered`, `message.undelivered` |
| `GET /api/v1/stream/processes/:id/logs` | SSE: `log.chunk` / `log.end` log tail for one command (replays from byte 0) |
| `GET /api/v1/stream/processes/:id/llm-events` | SSE: `llm.event` / `llm.end` canonical events of one Claude, Codex or opencode command, for the Conversation view (replays from byte 0) |
| `GET /api/v1/stream/agents` | SSE: `agent.reload` |

## Technology Stack

- **Framework**: Next.js 16 (App Router)
- **Language**: TypeScript 6.x
- **Styling**: Tailwind CSS 4
- **Components**: Shadcn/ui (Radix UI primitives)
- **State Management**: React Query (TanStack Query)
- **Theme**: next-themes
- **Icons**: Lucide React
- **Runtime**: Node.js 24 (as in the Dockerfile), React 19
- **Editor / diagrams**: Monaco (`monaco-yaml`), dagre (graph layout)
- **Tests**: Vitest + React Testing Library (see [`TESTING.md`](TESTING.md))

## Project Structure

The directory layout is described in
[`CLAUDE.md` § Directory layout](CLAUDE.md#directory-layout).

## Development

### Available Scripts

- `npm run dev` - Start development server
- `npm run build` - Build for production
- `npm run start` - Start production server
- `npm run lint` - Run ESLint
- `npm test` - Run the Vitest test suite once
- `npm run test:watch` - Run Vitest in watch mode
- `npm run licenses:generate` - Regenerate the console section of `/THIRD-PARTY-LICENSES.md`
- `npm run licenses:check` - Fail if that section is out of date (runs in CI)

See [`TESTING.md`](TESTING.md) for the test stack and layout.

### Building for Production

```bash
npm run build
cp -r public .next/standalone/ && cp -r .next/static .next/standalone/.next/
node .next/standalone/server.js
```

The build uses Next.js standalone output (what the Dockerfile ships);
`npm run start` still works for a quick local check but prints a standalone
warning.

## Troubleshooting

### Cannot connect to Homer API

- Verify `CONSOLE_HOMER_API_URL` is correct
- Check that the Homer backend is running and accessible
- The console calls the API from the browser, so `CONSOLE_HOMER_API_URL` must be reachable from the browser (not just from the container), and the backend's `server.corsAllowedOrigins` must list the console origin. See [`../DOCKER.md`](../DOCKER.md#cors-errors-in-the-browser-console).

### Authentication not working

- Verify `CONSOLE_AUTH_ENABLED=true`
- In `cookie` mode: confirm the backend's `apiAuth` lists the username
  you typed and the bcrypt hash in `homer-config.yaml` matches the
  password. Clear the session cookie (`homer_session` unless `cookieName` is set) and log in again.
- In `bearer` mode: check that your token is valid by testing against
  `/api/v1/heartbeat`. Clear localStorage and log in again.

### Processes not updating

- Freshness is driven by SSE invalidation — check the browser
  console/network tab for a dropped or failing `/api/v1/stream/processes`
  connection
- The process list and detail also poll every 30 s as a fallback, so a
  stale view resolves on its own within ~30 s even with a dead stream
  (a poll stops once the data it shows is terminal — the detail query on
  the run's status, the list query once the page holds runs and all of
  them are terminal; an empty list keeps polling). Each flow summary in
  the list keeps its own 30 s poll even after its runs have all finished,
  so a run a continuation adds to the flow still shows up
- Verify the Homer API is returning data correctly
- Reload the page to force a fresh fetch if the SSE connection is stuck

### Port conflicts

- Change the `PORT` environment variable to use a different port (set it
  in the shell or container environment; `next dev` ignores it in `.env.local`)
- Update the port mapping in your Docker run command or docker-compose.yml

## Contributing

This is part of the Homer project. See [../CONTRIBUTING.md](../CONTRIBUTING.md) for contribution guidelines.

## License

Apache License 2.0, same as the Homer project — see the [LICENSE](../LICENSE) file at the repository root.
