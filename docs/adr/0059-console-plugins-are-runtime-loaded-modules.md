---
status: accepted
date: 2026-09-07
deciders: Lukáš Vlk
---
# ADR-0059: Console plugins are same-origin ES modules imported at runtime

## Context and Problem Statement

Operators want to add their own pages to the console — own UI, own logic,
reading the Homer API — without forking `console/`. The sidebar is a hardcoded
array and every route is a file under `app/(dashboard)/`, so today the only
extension point is editing console source and rebuilding the image.

The console is not a neutral shell: it holds the operator's session for every
configured Homer instance. So "how does third-party UI code get in" is
inseparable from "what is that code allowed to do once it is in", and the
delivery mechanism has to be chosen with the privilege question answered, not
deferred.

## Decision Drivers

- Plugins must be deployable and updatable **without rebuilding the console**,
  and without the console's toolchain.
- A plugin must be able to call the Homer API as the logged-in user, and look
  like the rest of the console (theme, layout, sidebar).
- The console holds credentials for distinct principals; root CLAUDE.md §12
  makes the privilege boundary a design input.
- Not an iframe: an embedded page shares no session, theme, or API client, and
  `X-Frame-Options: DENY` blocks framing the console itself.
- The console's CSP is strict (`script-src 'self' 'nonce-…' 'strict-dynamic'`)
  and must not be loosened.

## Considered Options

- **Same-origin runtime modules** — plugin files live under the console's
  `public/`, and the console `import()`s the paths listed in its runtime config.
- **Remote runtime modules** — the console `import()`s absolute URLs, so plugins
  are served independently, on their own origins.
- **Build-time source plugins** — plugin sources are dropped into a configured
  directory and compiled into the console bundle by a prebuild codegen step.
- **Sandboxed iframe with a postMessage bridge** — the plugin is a separate
  document; the console proxies API calls and never hands over the credential.
- **Declarative manifests** — the plugin is data (queries + render spec)
  rendered by built-in console components; no third-party code runs.

## Decision Outcome

Chosen option: **"Same-origin runtime modules"**. Runtime import satisfies the
primary driver — a plugin is a file, not a rebuild — while keeping full UI
expressiveness. Restricting it to the console's own origin then costs nothing
that was actually wanted: the operator who deploys the console is the same
person who deploys its plugins, so a separate plugin origin bought independent
*serving* that nobody needed, in exchange for a network fetch to intercept and a
foreign origin to trust.

The mechanism:

- Plugin files live in `console/public/plugins/`, served by the console at
  `/plugins/`. The directory is gitignored: plugins are not console source.
- `CONSOLE_PLUGINS` (comma-separated same-origin paths) rides the existing
  `window.__HOMER_CONFIG__` runtime-config path, so it is a deploy-time env var
  rather than a build input.
- `checkPluginEntry` accepts a root-relative `.js`/`.mjs` path and **nothing
  else**. A remote URL, a protocol-relative `//host/p.js`, a `data:`/`blob:`/
  `javascript:` URL, and any `..` are rejected before the import.
- `PluginsProvider` imports each surviving path once per session via
  `import(/* webpackIgnore: true */ path)` and validates the default export
  against `PluginManifest` (`route`, `title`, `mount`). A malformed, missing, or
  route-colliding module is logged and skipped — one bad plugin must not take
  the console down.
- **The access requirement is part of the entry, not the manifest**, and is
  enforced *before* the import. See "Where the gate lives" below.
- A plugin bundles **its own React**, if it wants React at all, and is handed a
  DOM element plus a `PluginContext` (`api`, `user`, `theme`, `path`,
  `navigate`). No React or React Query singleton is shared, which removes the
  version coupling that makes module federation painful under RSC. React is the
  plugin's choice, not part of the contract: `example/console-plugin-react` and
  `example/console-plugin-vanilla` implement the same interface, one with a
  build step and one with no toolchain at all.
- One group-root catch-all route (`app/(dashboard)/[...pluginRoute]`) resolves
  the plugin, re-checks `requires`, and renders it. A plugin's `route` is a
  mount point, not a leaf: the whole subtree below it is handed to the plugin as
  `ctx.path`, so sub-routing — including the 404 for an unknown sub-path — is the
  plugin's own concern. A sub-path change remounts the plugin, because the App
  Router discards the page subtree on a param change; module scope is what
  survives in-plugin navigation, so `mount` must be idempotent.

### Where the gate lives, and why before the fetch

`CONSOLE_PLUGINS` entries carry the requirement: `/plugins/costs.js@admin`. The
first design put `requires` in the plugin's own manifest, which was wrong twice
over.

**It cannot be enforced in time.** A manifest is inside the module, so reading
it means importing the module — and the import is precisely what has to be
withheld. Gating only at the route would still let any authenticated user see
every gated plugin's path in their browser's network panel, and would run every
gated plugin's top-level code in their browser. That contradicts the
existence-nonexposure property this ADR claims (root CLAUDE.md §12 rule 3), so
the gate has to precede the fetch, and therefore has to live outside the module.

**It belongs to the operator anyway.** A plugin author declaring their own
`requires` can simply omit it. The person deciding who may load a plugin is the
person deploying it.

Consequences: `PluginsProvider` filters entries through `canUsePlugin` before
importing, and must therefore mount only after the auth probe has settled —
`DashboardLayout` already guarantees this, and moving the provider above that
gate would filter against anonymous defaults and hide gated plugins from
everyone. An unrecognised requirement (`@admn`) is rejected rather than ignored,
because ignoring it would publish an admin-only plugin to everybody. The route
handler re-applies the same `canUsePlugin` rather than trusting that the
provider filtered, so the bound is local and explicit (§12 rule 4).

### Accepted risk: plugin paths and source are not confidential

The requirement gates *loading*, not reading. Two exposures are accepted:

- `CONSOLE_PLUGINS` is serialized into `window.__HOMER_CONFIG__` by the root
  layout, which renders for every route including pre-auth `/login`. Any client
  can therefore read every configured path and its requirement.
- `public/plugins/` is served statically with no authentication (`middleware.ts`
  adds CSP headers only), so anyone who can reach the console can download a
  plugin's source.

Accepted rather than fixed, for two reasons. It cannot be fixed inside the
console: filtering the injected list per user needs a server-side notion of the
user, and the console has none — the session cookie belongs to the Homer
backend's origin and the auth probe runs in the browser. And it would not be
worth it if it could: the console already exposes exactly this much for its own
pages. `/costs` and `/schedules` are hidden from non-admins by `useCan`, yet
their code ships in the shared client bundle and `/_next/static/**` requires no
credentials. Plugin source is the same class of artifact.

An operator who needs a plugin's path or source to stay private must put the
console behind an authenticating proxy; that is the only layer able to withhold
a static file from an anonymous client.

What the gate does buy, and what the tests assert: a gated plugin is never
imported into the session of a user who lacks the requirement, so its code never
executes with their credentials, it never appears in their sidebar, and its
route is indistinguishable from a nonexistent one.

### Trust model — the part that must not be misread

A plugin runs **in the console's own origin with the operator's privileges**. It
can read the per-instance bearer tokens in `localStorage` and act as the
operator against every configured instance. Putting a file in `public/plugins/`
and naming it in `CONSOLE_PLUGINS` is therefore the same act of trust as
committing to `console/`.

What same-origin buys, and what it does not:

- **It removes the network from the trust chain.** There is no plugin origin to
  compromise, no CORS to configure, no DNS or TLS to trust, and no remote host
  that can change a plugin's contents after the fact. `script-src 'self'`
  covers the import on its own merits.
- **It does not sandbox anything.** A requirement decides who may *load* a
  plugin and nothing more. Once loaded, the plugin has the operator's
  privileges; the backend authorizer remains the real boundary for the data it
  reads, and a plugin's API calls carry the caller's own grants.
- **The plugin directory is now the boundary.** `public/plugins/` contains code
  that executes with the operator's session, so anything that can write there —
  an agent workspace, a shared volume, a build step fed untrusted input — owns
  the console. This replaces "who controls the plugin URL" with "who can write
  this directory", which is a boundary an operator can actually inspect.

### Consequences

- Good: plugins ship and update as files, with no console rebuild, no packaging
  format, and no plugin server to run.
- Good: no CSP change, and no reliance on `strict-dynamic` permitting a
  cross-origin import (it does — verified against a production build — but
  depending on that was a fragile place to stand).
- Good: the console degrades cleanly — zero plugins configured behaves exactly
  as before, and a rejected or broken plugin is skipped with a logged reason.
- Bad: **no privilege containment.** A malicious or compromised plugin file is a
  full console compromise. Acceptable only while plugin authors are the
  operators themselves; it does not scale to plugins from parties who should not
  hold the operator's session.
- Bad: a plugin must reach the console's filesystem — an image rebuild, a volume
  mount, or a copy step — so it is not independently *deployable* the way a
  remote URL would be.
- Bad: each React plugin ships its own React, so bundle size is duplicated.
- Bad: `PluginContext` is a public contract with deployed consumers — extending
  it is a breaking change, and nothing in this repo will catch a plugin that no
  longer matches.
- `Dockerfile` must copy `public/` explicitly; `output: 'standalone'` omits it.
  Before this ADR the console shipped without `public/` at all — it happened to
  be unused — so the omission was invisible.

## Pros and Cons of the Options

### Remote runtime modules

- Good: genuinely independent deployment — a plugin updates without touching the
  console host at all.
- Neutral: the privilege picture inside the browser is identical; the module runs
  in the console's origin either way.
- Bad: adds the network to the trust chain — a plugin host, its TLS, and its
  ability to change the served bytes at any time, all with the operator's
  session at stake. Also needs CORS on the plugin host, and leans on
  `strict-dynamic` allowing cross-origin dynamic imports.

### Build-time source plugins

- Good: plugin code is pinned in a reviewable, immutable artifact.
- Neutral: the privilege picture is **identical** — bundled code also runs with
  full console privileges. The difference is change control, not containment.
- Bad: fails the primary driver — every plugin change needs a console rebuild,
  and plugin authors need the console toolchain. In practice a fork with extra
  steps.

### Sandboxed iframe with a postMessage bridge

- Good: the only option with real containment — the plugin never receives the
  credential, and declared scopes can be enforced at the bridge.
- Bad: RPC boilerplate for every API surface; the plugin cannot use console
  React components; theme integration has to be pushed across the boundary.
- Bad: needs `frame-src` in the CSP (frames currently fall under
  `default-src 'self'`).
- **Revisit this option first** if plugins ever come from authors who should not
  hold the operator's session.

### Declarative manifests

- Good: no third-party code execution at all; composes with the permission
  model.
- Bad: expressiveness is capped by what the render spec implements, which does
  not meet the "own code, own logic" requirement.

## More Information

- Runtime-config pattern: `console/CLAUDE.md`, "Runtime config" section.
- Threat model behind the §12 rules invoked above:
  [ADR-0047](0047-security-by-design-and-threat-model.md).
- Worked examples: `example/console-plugin-react/` (React, sub-routes, build
  step) and `example/console-plugin-vanilla/` (one file, no framework, no build).
