---
status: accepted
date: 2026-09-26
deciders: Lukáš Vlk
---
# ADR-0069: Console themes are same-origin stylesheets loaded at runtime

## Context and Problem Statement

The console's themes are compiled in: the list is a hardcoded array in
`app/providers.tsx` and the tokens live per theme in `app/globals.css`. Adding a
look therefore means editing console source and rebuilding the image, which
cannot serve a theme that must **not** enter this repository — a company's
internal design system, say, in a repo that is going public.

ADR-0059 solved the same shape of problem for plugin *code*: operator files under
`public/`, named by a deploy-time env var on the `window.__HOMER_CONFIG__` path,
rejected unless same-origin. The question here is whether that mechanism extends
to stylesheets, and what a stylesheet is trusted to do once it loads.

## Decision Drivers

- A theme must be deployable without a console rebuild and without a console
  toolchain — it is a file the operator drops on disk.
- The theme must be able to define the same tokens the built-in themes define,
  and to point at same-origin font files next to it.
- The console's CSP is strict and must not be loosened.
- Root CLAUDE.md §12 makes the trust boundary of an operator-supplied runtime
  file a design input, not a review finding.

## Considered Options

- **Same-origin runtime stylesheets** — theme CSS lives under
  `console/public/themes/` and is named by `CONSOLE_THEMES`; the root layout
  emits one `<link rel="stylesheet">` per entry.
- **Build-time theme compilation** — themes are dropped into a configured
  directory and compiled into the bundle by a prebuild step.
- **A remote stylesheet** — `CONSOLE_THEMES` accepts a URL, so themes are served
  from anywhere.
- **Inline tokens in config** — theme values are carried as data in
  `__HOMER_CONFIG__` and applied as inline custom properties; no file at all.
- **A remote theme bundle of code** — themes ship as JS modules that set tokens,
  reusing the plugin loader verbatim.

## Decision Outcome

Chosen option: **"Same-origin runtime stylesheets"**. It is ADR-0059's decision
extended to a second file type, for the same reason it was made there: the
operator deploying the console is the operator deploying its themes, so a
separate theme origin buys independent *serving* nobody wants, in exchange for a
network fetch to intercept and a foreign origin to trust. It also satisfies the
primary driver directly — a theme is a file, not a rebuild.

The mechanism:

- Theme files live in `console/public/themes/`, served at `/themes/`. The
  directory is gitignored: a theme is not console source.
- `CONSOLE_THEMES` is a comma-separated list of `name:/path.css` entries. It
  rides the existing `window.__HOMER_CONFIG__` path as `themeEntries`, so it is
  a deploy-time env var, never a build input. The entries travel as the raw
  `name:path` strings rather than structured `{ name, path }` objects,
  deliberately: that is how `pluginEntries` already ride the same script, the
  string is the grammar the operator writes, and the first-colon split stays
  in one parser (`resolveThemeEntries`) on every side.
- `checkThemeEntry` accepts a `[a-z0-9-]+` name and a root-relative `.css` path
  and **nothing else**. A remote URL, a protocol-relative `//host/a.css`, a
  `data:`/`blob:` URL, any `..`, a non-`.css` extension, and a name that
  collides with a built-in (`light`, `dark`, `terminal`, `win95`, `system`) are
  rejected. Rejected entries are logged and skipped — one bad entry never keeps
  the console, or the other themes, from rendering.
- The root layout emits one `<link rel="stylesheet">` per surviving entry, with
  React hoisting it into `<head>`. `style-src 'self' 'unsafe-inline'` and
  `font-src 'self' data:` already cover a theme and its fonts, so **the CSP is
  unchanged**.
- `Providers` hands `next-themes` the built-ins plus the configured names, and
  the switcher lists one item per name (label = name). The list reaches
  `Providers` as a prop from the root layout so it is identical on both sides
  of hydration, letting a stored custom theme apply on the first render —
  `next-themes` cannot pre-paint a name it has not been told about.
- A theme file redefines the built-in token contract under `.<name> { … }`
  (`--background`, `--foreground`, `--primary`, `--primary-foreground`,
  `--muted`, `--border`, `--radius`, `--font-family-sans`, …) and may add
  `@font-face` rules pointing at same-origin font files beside it.
- A stylesheet that fails to load (404) is non-fatal: the theme's class applies
  no tokens, so every token falls back to the `:root` (light) values, and
  `Providers` logs the missing path once.

### The trust boundary: redress without code execution

**A theme crosses the same operator-trust boundary as a plugin, and a
different kind of risk.** A stylesheet cannot execute script, so it gets no
`@<requirement>` gate — there is nothing to authorize *against* the caller, and
the operator who deploys it is the only party who could. What it can do is
**redress the console**: overlay or reposition UI — a fake login form, a hidden
banner, a doctored status badge — so that an operator acting on what they see
acts on what the theme author decided they should see. It can also fetch
same-origin URLs through `url()` and read the console's own assets, and its
`Referer` reaches whatever it points at.

Two exposures are accepted, exactly as for plugins:

- `CONSOLE_THEMES` is serialized into `window.__HOMER_CONFIG__` by the root
  layout, which renders on every route including pre-auth `/login`. Any client
  can read every configured theme path.
- `public/themes/` is served statically with no authentication
  (`middleware.ts` adds CSP headers only), so anyone who can reach the console
  can download a theme and any fonts beside it.

Neither can be fixed inside the console — it has no server-side notion of the
user to filter by, and a static file cannot be withheld from an anonymous
client. An operator who needs a theme path or file to stay private must put the
console behind an authenticating proxy.

What same-origin buys, and what it does not:

- **It removes the network from the trust chain.** No theme origin to
  compromise, no CORS to configure, no remote host that can change a theme's
  contents after the fact. `style-src 'self'` covers the `<link>` on its own
  merits, and `font-src 'self'` covers the fonts beside it.
- **It is not confidentiality, and it is not a sandbox.** A theme is trusted to
  the same degree as `public/plugins/` is — it is a file that changes what the
  console's own users see, so the directory is the boundary. `public/themes/`
  must be writable only by the operator: anything that can write there — an
  agent workspace, a shared volume, a build step taking untrusted input — can
  redress the console for every one of its users.

If a theme must come from an author who should not hold that trust, this
mechanism is the wrong tool. Prefer a theme the operator reviews and copies in,
or revisit the "inline tokens in config" option below, which carries data rather
than a document.

### Consequences

- Good: a theme ships and updates as a file, with no console rebuild, no
  packaging format, and no toolchain.
- Good: the CSP is unchanged, and no nonce is needed for a same-origin
  stylesheet.
- Good: the console degrades cleanly — zero themes configured behaves exactly as
  before, and a rejected or missing theme is skipped with a logged reason.
- Good: the built-in themes are untouched; an external theme adds a name, and
  cannot redefine `light`/`dark`/… for a switch the user thinks they know.
- Bad: **redress, not containment.** A themed console is one whose appearance a
  non-console author controls; a user cannot tell a doctored screen from an
  authentic one. Acceptable only while theme authors are the operators
  themselves.
- Bad: a theme must reach the console's filesystem — an image rebuild, a volume
  mount, or a copy step — so it is not independently deployable the way a remote
  stylesheet would be.
- Bad: a theme can restyle the console into an unreadable state. Recovery is
  removing the env var and reloading, so the failure mode is visible rather than
  silent.
- Bad: theme fonts are the operator's problem — the token contract names a font
  family, but nothing ships the file.

## Pros and Cons of the Options

### Build-time theme compilation

- Good: a theme is validated by the console's own build, and could be
  type-checked against the token contract.
- Neutral: the CSP and trust picture are unchanged from today.
- Bad: it needs the console toolchain and a rebuild, which is the whole problem
  being solved.
- Bad: the theme source still lands in the repository or its build context.

### A remote stylesheet

- Good: genuinely independent deployment — a theme updates without touching the
  console host at all.
- Bad: `style-src 'self'` would have to be widened to an origin allowlist, and
  that origin becomes able to redress every console by changing a file.
- Bad: it adds a network fetch, CORS, and DNS/TLS to the trust chain, in
  exchange for independent serving nobody asked for.

### Inline tokens in config

- Good: no file, no directory, no static-serving surface — the theme is data in
  `__HOMER_CONFIG__`, applied as inline custom properties.
- Good: the strongest option for confidentiality, since no theme document is
  ever served.
- Bad: a real theme is far more than a token set — `@font-face`, selectors,
  media queries — and expressing it as structured config is a worse language than
  CSS with no validator behind it.
- Bad: it diverges from ADR-0059's mechanism for no benefit the operator
  notices.

### A remote theme bundle of code

- Good: reuses the plugin loader verbatim, so there is one runtime-file mechanism
  rather than two.
- Bad: it upgrades a stylesheet into **code running with the operator's session**
  — strictly more privilege for the same visual outcome, and it drags in the
  whole `PluginManifest`/gate apparatus for something that cannot be gated on
  anything meaningful.
- Bad: theme authors would then need the plugin contract, which is a public
  interface with deployed consumers.

## More Information

- [ADR-0059](0059-console-plugins-are-runtime-loaded-modules.md) — the mechanism
  this extends, and the plugin trust model this one mirrors.
- [ADR-0047](0047-security-by-design-and-threat-model.md) — the threat model
  behind the §12 rules applied here.
- [ADR-0056](0056-dependency-license-policy.md) — why a theme's design-system
  package does not become a console dependency.
- `console/README.md` § Custom themes — the operator-facing how-to.

## Amendment (2026-10-08): a named light/dark pair hides the built-ins

The consequence above — "the built-in themes are untouched; an external theme
adds a name" — no longer holds unconditionally. An operator that wants only its
approved corporate themes selectable sets `CONSOLE_THEME_MODES` to
`light:<name>,dark:<name>`, naming two of its own `CONSOLE_THEMES` entries.
When that value parses and both names resolve against the surviving entries, the
theme switcher and the `next-themes` list drop `light`/`dark`/`terminal`/`win95`
and offer the configured entries plus `System`, which follows the OS colour
scheme and applies the named light or dark theme. Unset, nothing changes.

Nothing else moves. The name rule stays: the pair may only name themes
`CONSOLE_THEMES` already defined, so a built-in name is rejected here exactly as
it is in `checkThemeEntry`, and no custom theme can reuse `light`/`dark`/… by
naming it in the pair. Stylesheet loading and validation are untouched. The
setting is all-or-nothing — a malformed value, an unknown name, or a
half-specified pair discards it whole, keeps the built-ins, and is logged once —
so a typo can never leave a user with a switcher that hides a theme they are
currently on and no way back.

Two consequences of the next-themes contract make this more than a list edit.
`next-themes` never validates the stored theme against `themes`, and its
pre-paint script applies the raw stored name as a class before React runs, so a
stored hidden built-in would otherwise stay applied with nothing checked in the
switcher; `Providers` resets such a value to `System` once on mount. That reset
is post-paint, not preventive: the stored built-in's class applies until React
hydrates, so a user who last picked Terminal sees Terminal paint once and then
flash to the pair's theme — accepted, because the pre-paint script cannot be
taught the pair without shipping console state into it. And because
`system` resolves to the literal `light`/`dark` before the `value` map is
applied, the pair rides next-themes' `value` ValueObject (there is no
`lightTheme`/`darkTheme` prop in 0.4.6), whose keys also stay in the
class-removal set so a stale built-in class is dropped on the next switch.
The map's keys are deliberately redundant: every custom name appears both as a
`CONSOLE_THEMES` entry and as a `value` key mapping to itself, and the built-in
names appear as keys (`terminal: 'terminal'`, `win95: 'win95'`) solely so they
stay in the class-removal set — which works because next-themes **0.4.6** builds
that set from `Object.values(value)` in insertion order (built-in names first,
then custom names).
A next-themes bump that changes either the removal set or its ordering silently
stops dropping a stale built-in class; check both on upgrade.

The built-ins therefore remain the default for Homer's own deployment; a
deployment that names a pair opts into a closed theme list, and does so at deploy
time through the same `window.__HOMER_CONFIG__` path as `CONSOLE_THEMES`.

