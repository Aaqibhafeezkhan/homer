# Console CLAUDE.md

Dev guide for the Next.js supervisory console. Project-wide rules
(commit hygiene, tests, ADRs, changelog, docs, schema sync, security) live
in the root [`CLAUDE.md`](../CLAUDE.md) and apply here too. This file
covers the console-specific architecture and conventions that repeatedly
bite agents working in `console/`.

User-facing docs:
- [`README.md`](README.md) — features, pages, local dev
- [`DEPLOYMENT.md`](DEPLOYMENT.md) — runtime / Docker
- [`TESTING.md`](TESTING.md) — test layout (Vitest + RTL + jsdom)
- [`../CHANGELOG.md`](../CHANGELOG.md) — user-visible deltas (single project changelog, `### Console` subsection per release)

## Stack

- **Next.js 16 App Router**, React 19, TypeScript strict.
- **Tailwind 4** + **shadcn/ui** (Radix primitives under `components/ui/`).
- **TanStack Query 5** for server state.
- **`@microsoft/fetch-event-source`** for SSE with bearer auth headers
  (native `EventSource` can't send `Authorization`, which is why this
  library was chosen).
- **`next-themes`** — four built-in themes (`light`, `dark`, `terminal`, `win95`) plus a `system` follower, and any operator-supplied themes from `CONSOLE_THEMES` (see § Theming); a `CONSOLE_THEME_MODES` pair can replace the four built-ins with one operator light/dark pair. Tokens live in `globals.css` under `:root`/`.dark`/`.terminal`/`.win95`; the switcher is [`components/layout/theme-switcher.tsx`](components/layout/theme-switcher.tsx).
- **`lucide-react`** icons; no other icon set.

## Directory layout

```
components.json                     # shadcn CLI config (Tailwind 4: no tailwind.config; cn at lib/utils/cn)
middleware.ts                       # per-request nonce + per-route CSP (see lib/security/csp.ts)
app/
  layout.tsx                        # root layout; force-dynamic; injects window.__HOMER_CONFIG__ (nonced)
  providers.tsx                     # QueryClient + ThemeProvider + AuthProvider
  page.tsx                          # redirects to /processes
  (auth)/login/page.tsx             # pre-auth only route
  (dashboard)/layout.tsx            # auth gate; mounts global SSE streams
  (dashboard)/processes/            # list, detail
  (dashboard)/agents/               # list, per-agent detail + history, [name]/edit (Monaco editor)
  (dashboard)/schedules/            # cron overview
  (dashboard)/questions/            # open-question inbox (answer + dispatch annotations)
  (dashboard)/continuations/        # pending + failed completion continuations
  (dashboard)/costs/                # admin LLM cost dashboard
  (dashboard)/runners/              # admin runner / instance / claim-queue overview (read-only)
  (dashboard)/[...pluginRoute]/     # runtime plugin routes; also the unmatched-dashboard-path handler
components/
  ui/                               # shadcn primitives — do not edit, re-generate via CLI
  auth/                             # login form parts: EndpointField, AuthModeToggle
  layout/                           # Header, Sidebar, VersionBadge, InstanceSwitcher, ThemeSwitcher, BackButton, ApiErrorAlert
  processes/                        # ProcessTable, StatusBadge, ArtifactPickerDialog, ArtifactOutputTabs, LlmConversationView
  agents/                           # AgentCard, RunAgentDialog, NewAgentDialog, WorkflowGraph (dagre LangGraph renderer, ADR-0073/0075), WorkflowGraphCard
  continuations/                    # ContinuationItem
  costs/                            # cost dashboard cards
  editor/                           # agent file editor + debug-run panels
  questions/                        # QuestionItem, QuestionPanel
  messages/                         # RunMessagePanel (operator messages to a running run)
  plugins/                          # PluginHost — mounts a runtime plugin into a DOM node
lib/
  api/client.ts                     # HomerApiClient singleton + HomerApiError
  api/errors.ts                     # describeApiError: a failed query's cause in one sentence (auth / unreachable / 404 / status)
  api/queries.ts                    # React Query hooks + queryKeys registry
  api/sse.ts                        # openStream(path, handlers) SSE primitive
  api/streams.ts                    # useProcessStatusStream / useAgentReloadStream / useProcessLogStream / useLlmEventStream
  auth/auth-context.tsx             # login/logout/switchInstance; probes the active instance
  config/app-config.ts              # runtime config resolver (see below)
  themes/loader.ts                  # CONSOLE_THEMES entry validation (ADR-0069)
  instances/store.ts                # active-instance store (base URL / mode / per-instance token)
  instances/use-instances.ts        # useInstances / useActiveInstanceId hooks
  plugins/{types,loader,gate}.ts, plugins/plugins-context.tsx  # plugin contract, loader, access gate, provider
  hooks/                            # shared React hooks (URL query state, auto-scroll, now, in-app navigation marker, sidebar collapse, process-detail LangGraph view, error-boundary logging)
  langgraph/layout.ts               # dagre layout of a LangGraph topology (ADR-0073)
  monaco/setup.ts                   # Monaco editor setup
  security/csp.ts                   # per-route CSP builder
  utils/{cn,format,validation,agent,curl,download,kv,list,html,process,cost-source,agent-name-matcher}.ts
types/homer.ts                      # TS mirrors of backend DTOs
config/default.json                 # defaults; overridable at runtime
scripts/                            # dev/build helpers, not shipped (third-party license generator, CRAP and quality reports)
public/plugins/                     # operator-dropped plugin modules (gitignored); served at /plugins/
public/themes/                      # operator-dropped theme stylesheets + fonts (gitignored); served at /themes/
```

## Routing model

App Router with two route groups:

- `(auth)` — pre-auth screens (currently just `/login`).
- `(dashboard)` — everything else, wrapped in an auth-gated layout that
  also mounts the global SSE subscriptions (`useProcessStatusStream`,
  `useAgentReloadStream`).

`app/page.tsx` redirects `/` → `/processes`.

## Data flow

```
backend (Ktor)
   │  REST / SSE
   ▼
lib/api/client.ts  ───────────────▶  React Query cache
   │                                     │
   ▼                                     ▼
lib/api/sse.ts  (invalidates cache)   components read via hooks
   │                                     │
   └─ lib/api/streams.ts ────────────────┘
```

- **One API client** — `homerApi` (singleton from `lib/api/client.ts`).
  Every backend call goes through it. Centralizes: base URL, auth-header
  injection, timeout, error wrapping in `HomerApiError`. Base URL, auth
  mode, and bearer token are **not** frozen at construction — they are read
  from the active instance (`lib/instances/store`) per request, so switching
  the active instance retargets every call.
- **One React Query registry** — `queryKeys` in `lib/api/queries.ts`.
  When invalidating caches from SSE handlers or mutations, reach for
  these keys; don't hard-code strings.
- **SSE invalidates, doesn't replace queries** — the streams never
  write cache values directly. They call `queryClient.invalidateQueries`
  which triggers React Query's normal refetch path. This keeps one
  source of truth (the REST endpoint) and means a dropped SSE event
  self-heals on the query's next refetch — its poll where it has one,
  otherwise the next mount (`refetchOnWindowFocus` is off in
  `app/providers.tsx`).
- **A per-row query stays outside the blanket SSE invalidation** —
  `useProcessStatusStream` refetches every `['processes']` query on every
  process event (a burst within one animation frame is coalesced into one
  invalidation per key, flushed after the burst so the last event's state
  is always refetched; a hidden tab gets no frames, so it applies them
  when it becomes visible), so a query rendered once per list row multiplies the
  page's API load by the row count on each event. The list page's flow
  summaries (`useFlowSummary`, key `['flow-summary', rootId]`) are the
  case: twenty of them under `['processes']` saturated the backend. The
  stream refreshes only the summaries an event is about — the root's own,
  or one already listing the run — and the rest rely on their 30 s poll,
  which unlike the list's never stops: a continuation can add a child to a
  flow whose runs have all finished, and no event the stream matches
  announces it.
- **Every refetch is a backend database read** — each poll tick and each
  SSE-driven invalidation turns into one or more SQLite transactions, per
  open tab. Before adding a poll or widening an invalidation, count the
  requests it adds per event and per minute, and prefer targeted keys over
  prefix invalidation. See the SQLite rules in `app/CLAUDE.md`.
- **Polling is per query, not global** — only a few queries poll, with
  `refetchInterval` hard-coded in `lib/api/queries.ts`: costs and the
  runner / claim-queue snapshots (5 s), questions, the open-question count,
  continuations and the pending-continuations count (30 s), plus the
  LangGraph status panel while it is open (5 s) and the health query
  (`useHealth`, 15 s), which the maintenance banner rides — it reads
  in-memory backend state, so unlike the others a tick costs no database
  work. The process list and the
  process detail poll every 30 s as a safety net under SSE; both stop once
  their data is terminal — the detail query on the process's status, the
  list query once the page holds rows and every one is terminal
  (`FINISHED`/`FAILED`/`KILLED`/`UNKNOWN`); an empty page keeps polling, so
  a run created while SSE was down still reaches the view. Each flow
  summary (`useFlowSummary`) also polls every 30 s but never stops: a
  continuation can add a run to a flow whose runs have all finished, and no
  event the stream matches announces it. Of
  `config/default.json:polling`, only `agentsCacheTime` is read.
- **Live conversation view reads the canonical `/llm-events` stream** —
  for a `!<claude>`, `!<codex>` or `!<opencode>` step, `ArtifactOutputTabs` (rendered
  inline by execution items and the live-command box) gates on the
  step's `kind` (`isLlmKind`) and drives its Conversation mode from
  `useLlmEventStream`, which tails `GET
  .../llm-events?cmd=` and replays from the start on every
  (re)subscribe — unlike the old broadcast-only `claude.event` feed it
  replaced, which could not replay history to a view that subscribed
  after the early events fired. Tree (JSON) and Raw modes keep reading
  the raw artifact/log-tail content (`useProcessLogStream` /
  `useArtifactContent`) regardless of kind. When the process reaches a
  terminal status, `useProcessStatusStream` invalidates the process's
  `artifactContent` queries so the view refetches the now-complete
  snapshot and leaves streaming mode (clears the `Live` badge).

## Runtime config — the `window.__HOMER_CONFIG__` pattern

The console ships as a static Next build but needs to point at
different backends at runtime (dev laptop vs Docker vs prod). Path:

1. `app/layout.tsx` has `export const dynamic = 'force-dynamic'` so
   `process.env` is read **per request**, not baked at build time.
2. Root layout reads the `CONSOLE_*` runtime vars (`CONSOLE_HOMER_API_URL`,
   `CONSOLE_AUTH_ENABLED`, `CONSOLE_ALLOW_BEARER`,
   `CONSOLE_ALLOWED_API_ORIGINS`, `CONSOLE_PLUGINS`, `CONSOLE_THEMES`,
   `CONSOLE_THEME_MODES`; see the `DEPLOYMENT.md` env table) and
   serializes them into `window.__HOMER_CONFIG__` via an
   inline `<script>` (with `<` escaping as defense-in-depth against
   injection).
3. Client-side `lib/config/app-config.ts` reads the global (the root
   layout injects it on every render, dev included) over
   `config/default.json`. It never reads `process.env.CONSOLE_*`: a
   browser bundle only sees `NEXT_PUBLIC_*` vars, so the global is the
   only way a runtime value reaches the client.

**Implication:** any new runtime-tunable knob follows the same pattern.
Don't add new `process.env.CONSOLE_*` reads in random client
components — extend `app-config.ts` instead.

Both operator-supplied lists ride this path: `pluginEntries` and `themeEntries`,
resolved to `appConfig.plugins.entries` and `appConfig.themes.entries`, both as
`string[]`. `pluginEntries` is `CONSOLE_PLUGINS` split on commas and trimmed
(`parsePluginEntries`) but otherwise **unvalidated**: the consuming component
validates and logs each entry with the validator it owns (`checkPluginEntry`).
`themeEntries` is validated server-side first: the layout runs
`parseThemeEntries` over `CONSOLE_THEMES`, logs and drops rejected entries, and
injects only the survivors re-serialized as `name:path` strings (the theme
switcher still re-checks them with `checkThemeEntry` via `resolveThemeEntries`).
`CONSOLE_THEME_MODES` is resolved against those survivors and rides along as
`themeModes`.

## Runtime plugins (ADR-0059)

Operators extend the console with their own pages by dropping ES module files
into `public/plugins/` and naming the same-origin paths in `CONSOLE_PLUGINS`.
Path:

1. `app/layout.tsx` injects the entries into
   `window.__HOMER_CONFIG__.pluginEntries` (same runtime-config pattern as every
   other knob); `app-config.ts` resolves them to `appConfig.plugins.entries`.
2. `PluginsProvider` (mounted in `(dashboard)/layout.tsx`) validates each entry
   with `checkPluginEntry`, **filters by requirement through
   `lib/plugins/gate.ts` before importing anything**, and then imports the
   survivors once per session via `lib/plugins/loader.ts`, which validates each
   default export against `PluginManifest`. Rejected, missing and
   route-colliding modules are logged and skipped, never thrown.
3. `Sidebar` appends an entry per loaded plugin, gated through `useCan()`.
4. `app/(dashboard)/[...pluginRoute]/page.tsx` matches the **first segment**
   against plugin routes and passes everything below it as `subPath`, then
   re-checks the gate and renders `PluginHost`, which calls
   `plugin.mount(el, ctx)`.

**The requirement is gated before the fetch, and it lives in config.** An entry
is `path[@requirement]`; `requires` is deliberately *not* a manifest field,
because a manifest cannot be read without importing the module it is in — and
the import is what discloses the plugin's existence and runs its top-level code.
`canUsePlugin` in `lib/plugins/gate.ts` is the only implementation of the gate;
the provider (pre-fetch) and the route handler (§12 rule 4: the bound must be
local and explicit) both call it, and nothing else may reimplement it. The
provider can read capabilities safely because `DashboardLayout` mounts it only
after the auth probe settles — moving the provider above that gate would filter
against anonymous defaults and hide every gated plugin from everyone.

A plugin owns its whole subtree: `/p/a` resolves to the plugin at `/p` with
`ctx.path === '/a'`, and the plugin renders its own 404 for sub-paths it does not
know. `ctx.navigate` wraps `router.push`.

**Same-origin only.** `checkPluginEntry` accepts a root-relative `.js`/`.mjs`
path and nothing else — a remote URL, a protocol-relative `//host/p.js` (reads
as local, resolves foreign), a `data:`/`blob:`/`javascript:` URL, or any `..` is
rejected before the import. Do not "helpfully" widen this: it is the whole
reason plugin code cannot come from anywhere but the operator's disk, and it is
what makes `script-src 'self'` sufficient.

**A sub-path change is a remount.** The App Router discards the page subtree when
the catch-all params change — measured, not assumed: a plugin counting its own
mounts increments on every `ctx.navigate`. So `subPath` is in the mount effect's
deps and the plugin's tree state resets on in-plugin navigation; module scope
survives, because the module is imported once per page session. `user`, `theme`
and `router` stay in `liveRef` precisely so a theme toggle or auth refresh does
*not* remount. Under StrictMode `mount` also runs twice per mount in dev, so
plugin `mount`/cleanup must be symmetric.

**The gate is a loading gate, not a sandbox or confidentiality.** A loaded plugin
holds the operator's session, and its path and source are readable pre-auth; the
full trust model is in [`README.md`](README.md) § Trust model. That exposure is
deliberate — a static file cannot be withheld inside the console — so don't
"fix" it here. Read
[ADR-0059](../docs/adr/0059-console-plugins-are-runtime-loaded-modules.md)
before changing any of this.

## Content-Security-Policy (`middleware.ts` + `lib/security/csp.ts`)

`middleware.ts` mints a per-request nonce and emits a per-route CSP (policy
built in `lib/security/csp.ts`, unit-tested there). The policy is **strict**
(`script-src 'self' 'nonce-… ' 'strict-dynamic'`, no `unsafe-eval`/`unsafe-inline`)
on every route **except** the Monaco editor route `/agents/*/edit`, which is
relaxed to `script-src 'self' 'unsafe-inline' 'unsafe-eval' blob:` (no nonce,
no `'strict-dynamic'`) + `blob:` workers. The nonce reaches the inline
runtime-config `<script>` via the `x-nonce` request header, read in
`layout.tsx` with `await headers()`; Next.js reads it from the CSP header to
nonce its own scripts.

- CSP is **skipped in development** (React Refresh needs eval/inline) — it only
  applies under `NODE_ENV=production`.
- `connect-src` is pinned to `CONSOLE_ALLOWED_API_ORIGINS` when set, else left
  open (multi-instance login can target any backend).
- Adding a component that needs `eval`/inline scripts elsewhere means either
  making it CSP-clean or extending `isEditorPath` — don't loosen the global policy.

## Authentication

- Auth is scoped to the **active instance** (`lib/instances/store`):
  base URL and mode (`cookie` | `bearer`) come from it, not from frozen
  `appConfig`. `appConfig.auth.enabled` stays the deployment-wide master
  switch.
- Bearer tokens live in `localStorage` namespaced per instance
  (`homer_auth_token::{instanceId}`). A legacy un-namespaced token is
  migrated into the seeded default instance on first load.
- `AuthProvider` probes the active instance on mount (`/auth/me` in cookie
  mode, `/heartbeat` in bearer mode). `switchInstance(id)` repoints the
  active id, clears the React Query cache, and re-probes; global SSE streams
  reconnect on the active-instance id change.
- The login screen carries the **endpoint URL** + mode toggle: on submit it
  upserts + activates the instance, then runs the normal login. The mode toggle
  (cookie/bearer) is shown **only** when `appConfig.auth.allowBearer` is on
  (`CONSOLE_ALLOW_BEARER=true`); otherwise the console is cookie-only and any
  `?mode=bearer` in the URL is ignored (bearer tokens in `localStorage` are
  XSS-exfiltratable — see the 2026-07-12 review, H3). With bearer allowed the
  form also **opens** in Token mode (URL `?mode=`, then the active instance's
  saved mode, win over that default), and `seedDefault` seeds the default
  instance `mode: 'bearer'` for the same reason — a bearer-only backend cannot
  accept a password.
- The **return path** is the `next` parameter both redirects into `/login` put on
  the URL (the dashboard layout's anon push and the auth-expired handler).
  `safeReturnPath` (`lib/auth/return-path.ts`) accepts a same-origin relative
  console path and nothing else — no scheme, no `//` or `/\`, no control
  character, no `?`/`#`, no `/login` — and the login form re-checks it at the
  point of use, right before `router.push`, so an attacker-crafted `next` cannot
  become an open redirect (ADR-0047).
- `?expired=1` — and with it the "Your session expired" banner — is emitted only
  by the auth-expired handler, and only when the rejected request carried a
  session: a bearer token still in the active instance's storage, or a mount that
  was ever `authed` (the cookie itself is not client-readable, so mid-use is the
  only way a cookie expiry is recognisable). A session-less first visit gets the
  neutral "Sign in to continue" prompt instead: a 401 on the global SSE streams
  (`sse.ts` is not `AUTH_BUS_EXEMPT`) is not an expiry.
- Dashboard layout redirects to `/login` when
  `appConfig.auth.enabled && !isAuthenticated`.
- `appConfig.auth.enabled === false` bypasses everything and sets
  `isAuthenticated = true` — matches the backend's `unsecured` mode
  for local dev.

## Theming

- `next-themes`, `attribute="class"`, `defaultTheme="dark"`,
  `enableSystem` on, `themes={built-ins + configured names}`. With a
  `CONSOLE_THEME_MODES` pair the list is the configured names only,
  `defaultTheme="system"`, and a `value` map sends `system` to the pair's
  light/dark theme (see § Operator themes).
- Tokens (HSL CSS vars + `--font-family-sans` + `--radius`) are
  defined per theme in `globals.css`. Components should use semantic
  classes (`bg-primary`, `text-foreground`, `bg-card`, `border-border`)
  so they adapt automatically. Avoid `dark:` Tailwind prefixes for new
  work — they only activate under the `.dark` class and silently
  fall through under `terminal` / `win95` and under an operator theme.
- `body` gets its font only from `--font-family-sans`. Inter is loaded
  as `next/font`'s `--font-inter` variable on `<html>`, where `:root`
  resolves it, and `:root` lists it first. Never put a `next/font`
  `className` (or any `font-*` class) on `<body>`: it is unlayered CSS,
  so it outranks the `@layer base` body rule, and then no theme can set
  its own font.
- Status badges intentionally use fixed brand colors
  (`bg-blue-500`, `bg-green-500`, `bg-red-500`, …) because status
  semantics are theme-independent.

### Operator themes (ADR-0069)

A theme is an operator-supplied stylesheet, not console source, so the built-in
set is no longer the whole list. Path:

1. Root layout parses `CONSOLE_THEMES` (`name:/path.css` entries) with
   `lib/themes/loader.ts`, logs every rejected entry, injects the survivors into
   `window.__HOMER_CONFIG__.themeEntries`, and emits one same-origin
   `<link rel="stylesheet">` per entry at the top of `<body>`; `app-config.ts`
   resolves them to `appConfig.themes.entries`. It then parses
   `CONSOLE_THEME_MODES` (`light:<name>,dark:<name>`) with `parseThemeModes`
   against the **surviving** names, so a mode can only ever name a theme that
   actually loads; a rejected value is logged once under `[themes]` and ignored
   whole. The pair rides `window.__HOMER_CONFIG__.themeModes` and reaches
   `appConfig.themes.modes`.
2. `Providers` (`app/providers.tsx`) takes those entries and the pair as
   **props from the root layout**, resolves them, and appends the names to the
   `themes` prop. The props are load-bearing, twice over. `next-themes` renders
   its pre-paint `<script>` with the theme list inlined, so reading the list from
   `appConfig` inside the component would render four themes on the server (which
   has no `window.__HOMER_CONFIG__`) and five on the client, and fail hydration.
   And the pre-paint script has to know a custom name for a stored custom theme
   to apply without a flash of the default look. Do not "simplify" this back to a
   config read.
3. `theme-switcher.tsx` appends one item per name (label = name, `Palette`
   icon); `system`, the built-ins, and the configured names are never
   re-labelled. It reads `appConfig` directly, because the dropdown only opens
   after hydration.
4. The link tags sit ahead of `Providers` in document order, so the browser
   starts fetching a theme's stylesheet before `next-themes` applies the class
   below it. Keep them above `<Providers>`; moving them after reintroduces a
   flash of the default look.
5. With a pair active the built-ins leave both lists: `Providers` passes the
   configured names only, `defaultTheme="system"`, and a `value` map of
   `{light: a, dark: b, <custom>: <itself>, terminal: 'terminal', win95:
   'win95'}` — `next-themes` 0.4.6 has no `lightTheme`/`darkTheme` props, and
   `system` resolves to the literal `light`/`dark` before `value` is applied.
   The map's values are also the class-removal set, so a stale built-in class is
   dropped on the next switch. Because `next-themes` never validates a stored
   theme and its pre-paint script applies the raw stored name, `Providers` mounts
   a `HiddenThemeFallback` child that resets a stored built-in to `system` once —
   without it AC "a stored hidden built-in falls back to System" is not met.

`checkThemeEntry` is the `.css` twin of `checkPluginEntry`: a root-relative
`.css` path and a `[a-z0-9-]+` name, and nothing else. Remote URLs,
protocol-relative `//host/a.css`, `data:`/`blob:`, `..`, non-`.css`, names
outside the pattern, and built-in names (`light`/`dark`/`terminal`/`win95`/
`system`) are rejected. **Do not widen it** or fold the two checkers together —
`checkPluginEntry` accepts `.js`/`.mjs` and a `@requirement`, and widening either
one past its own file type is the mistake this pair exists to prevent. A missing
stylesheet is non-fatal: `Providers` reports it once via `missingThemeStylesheets`
and the theme's class applies no tokens, so every token falls back to the
`:root` (light) values.

**A theme is trusted, but it is not code.** It gets no `@<requirement>` — there
is nothing to authorize against a caller, and the operator deploying it is the
only party who could. It can still redress the UI, so `public/themes/` is the
trust boundary in the same sense `public/plugins/` is: writable only by the
operator. See
[ADR-0069](../docs/adr/0069-console-themes-are-runtime-loaded-stylesheets.md).

## Types & backend coupling

- `types/homer.ts` hand-mirrors backend DTOs. **There is no codegen.**
- Adding / changing a backend endpoint requires a same-commit update
  to:
  1. `types/homer.ts` (DTO shape).
  2. `lib/api/client.ts` (method signature).
  3. `lib/api/queries.ts` (hook + `queryKeys` entry if the endpoint
     is new).
  4. If SSE: `lib/api/sse.ts` / `streams.ts` (event-name handler).
- **The `dto-contract` check compares the two** (ADR-0082). `DtoContractTest` in
  `:app:test` exports the serial shape of the backend DTOs, `npm run quality:dto-types`
  (part of `quality:report`) the interfaces and type aliases of `types/homer.ts`, and
  `tools/quality/dto-contract.mjs` reports: a field missing on either side; a response
  field Kotlin declares nullable whose TS type does not allow `null` (the backend sends
  explicit nulls, so `x?: string` is not enough); a request field TS may leave out or
  `null` that Kotlin requires; a coarse type mismatch; enum or union variants that differ.
  A TS type is matched to the Kotlin class of the same name, or through `RENAMES` in that
  script; a type with no backend DTO needs a `CONSOLE_ONLY` reason there, and a new
  response DTO goes into the root list in `DtoContract.kt` (`app/CLAUDE.md` § Console DTO
  contract). Locally: `app/gradlew -p app :app:test --tests '*DtoContractTest'`,
  `npm run quality:dto-types`, then `node tools/quality/dto-contract.mjs` from the repo
  root.
- Known SSE event names today: `process.status`, `process.cmd`,
  `agent.reload`, `log.chunk`, `log.end`, `llm.event`, `llm.end`,
  `question.asked`, `question.answered`, `question.expired`, `message.queued`,
  `message.delivered`, `message.undelivered`.
- Known response headers consumed by the client:
  `X-Process-Status`, `X-File-Complete` (artifact download endpoint).

## Adding features — checklists

**New page under `(dashboard)`**

1. Create `app/(dashboard)/your-route/page.tsx`.
2. Add a sidebar entry in `components/layout/sidebar.tsx`.
3. Add the route to `RESERVED_ROUTES` in `lib/plugins/loader.ts`, so a
   plugin claiming it is rejected instead of silently never rendering.
4. Use existing query hooks from `lib/api/queries.ts`; do not call
   `fetch` directly.
5. Add a Vitest + RTL test for the page (root §6).
6. README + root `/CHANGELOG.md` (`### Console` subsection) per root §8 / §9.

**New API call**

1. Add the DTO to `types/homer.ts`.
2. Add a method on `HomerApiClient` in `lib/api/client.ts`.
3. Wrap it in a React Query hook in `lib/api/queries.ts`; register a
   `queryKeys` entry for list/detail endpoints.
4. For mutations: invalidate the right `queryKeys` in `onSuccess` or
   `onSettled`; use optimistic updates only where the outcome is
   predictable (see `useKillProcess` for the pattern).

**New SSE event**

1. Add handling in `lib/api/streams.ts` (existing stream) or extend
   `openStream` with a new subscribing hook.
2. The handler should **invalidate** React Query keys, not write
   cache values directly.
3. Subscribe from `(dashboard)/layout.tsx` if it's a global stream;
   otherwise from the specific page/component that needs it.

## Testing

- **Stack:** Vitest + React Testing Library + jsdom. `npm test` runs
  the suite once; `npm run test:watch` is watch mode. See
  [`TESTING.md`](TESTING.md) for layout and conventions.
- **Bar (per root §6):** every new component, hook, or utility ships
  with ≥1 test. Co-locate as `*.test.tsx` next to the file under test.

## Build & local dev

| Command | Purpose |
| --- | --- |
| `npm run dev` | Local dev server at `http://localhost:3000` |
| `npm run lint` | ESLint (eslint-config-next, plus this file's architecture rules at `warn`); fails on errors only |
| `npm test` | Vitest single run |
| `npm run test:watch` | Vitest watch mode |
| `npm run test:coverage` | Vitest single run with V8 coverage → `coverage/` (what CI runs) |
| `npm run crap` | CRAP score per function from that coverage → `coverage/crap/` ([ADR-0081](../docs/adr/0081-crap-score-gate.md)) |
| `npm run quality:report` | Report-only ESLint warnings, dependency-cruiser layering, knip unused code and file / function size (`size-console`: files over 600 and functions over 60 lines, in an ESLint run of its own, so not lint warnings) → `reports/quality/`, plus the `types/homer.ts` extract the `dto-contract` check reads → `reports/dto-contract/` ([ADR-0082](../docs/adr/0082-quality-reports-report-first.md)) |
| `npm run mutation` / `npm run mutation:changed` | Stryker mutation testing, full or of the lines changed since `MUTATE_BASE` (from the working tree with `MUTATE_WORKTREE=1`) → `reports/quality/mutation-console/` (ADR-0082) |
| `npm run build` | Production build |
| `npm run start` | Serve the production build |
| `npm run licenses:generate` | Rewrite the console region of `/THIRD-PARTY-LICENSES.md` from `package-lock.json` |
| `npm run licenses:check` | Fail if that region has drifted; runs in CI right after `npm ci` |

Env for local dev:

```
CONSOLE_HOMER_API_URL=http://localhost:8080
CONSOLE_AUTH_ENABLED=false
```

The same vars drive the Docker runtime, alongside the optional
`CONSOLE_ALLOW_BEARER`, `CONSOLE_ALLOWED_API_ORIGINS`, `CONSOLE_PLUGINS`,
`CONSOLE_THEMES` and `CONSOLE_THEME_MODES` (see the env table in
`DEPLOYMENT.md`, and `/DOCKER.md`).

## Gotchas

- `export const dynamic = 'force-dynamic'` in `app/layout.tsx` is
  **load-bearing** — removing it breaks runtime env injection.
- `EventSource` cannot send an `Authorization` header, which is why
  the console uses `@microsoft/fetch-event-source`. Don't swap it for
  native `EventSource`.
- `components/ui/` files come from the shadcn CLI. Regenerate via the
  CLI (`npx shadcn@latest add <component>`, run from `console/`, which
  reads `components.json`) rather than hand-editing, or updates will
  conflict.
- React Query `staleTime`/`gcTime` tuning lives in per-hook options
  (e.g. `useAgents`, `useArtifactContent`); `providers.tsx` sets only
  `refetchOnWindowFocus: false` and `retry: 1` as defaults.
- `reloadAgents` mutation still calls `POST /api/v1/agents/reload`;
  confirm the backend route still exists before exposing new UI
  relying on it.
- `Dockerfile` copies `public/` explicitly — `output: 'standalone'` does
  **not** include it. Without that COPY every `CONSOLE_PLUGINS` path 404s in
  the image while working fine locally.
- `app/(dashboard)/[...pluginRoute]` is the group-root catch-all, so it
  handles **every** unmatched dashboard path and renders the not-found
  state itself. Static routes always win over it, but a new dynamic route
  near the group root can collide — check this file when routing behaves
  oddly.
- Rules in this file that a tool can check are encoded as `warn` rules in
  `eslint.config.mjs` (direct `fetch`, `CONSOLE_*` reads, `dark:` classes,
  literal query keys, …) and `.dependency-cruiser.mjs` (layering, cycles).
  Changing such a rule here means changing it there too.
- The SSE sidecar in `(dashboard)/layout.tsx` runs for every
  dashboard page, not just the list views. New pages get
  cache-invalidation on process / agent changes for free — don't
  re-subscribe per page.
