# Example console plugin — no framework, no build

A Homer console plugin in one file. `plugin.js` is already an ES module, so
there is nothing to install and nothing to compile — the console imports it
exactly as it sits on disk.

It shows that React is a choice a plugin makes, not part of the contract: the
console hands the plugin a DOM element and calls `mount`, and what fills that
element is the plugin's business.

See [ADR-0059](../../docs/adr/0059-console-plugins-are-runtime-loaded-modules.md)
for the design and the trust model.

> **A plugin runs in the console's origin with the operator's privileges.**
> Putting a file in `console/public/plugins/` and naming it in `CONSOLE_PLUGINS`
> is the same act of trust as committing to `console/`. Do not put code there
> that you would not merge.

## Run it

The console imports plugins from its own `public/plugins/`, so "deploying" this
one is a copy:

```bash
# from the repo root
cp example/console-plugin-vanilla/plugin.js console/public/plugins/html.js
```

Then name it in `console/.env.local` (gitignored, loaded by Next automatically):

```
CONSOLE_HOMER_API_URL=http://localhost:8080
CONSOLE_AUTH_ENABLED=false
CONSOLE_PLUGINS=/plugins/html.js
```

`cd console && npm run dev`, and:

| | |
| --- | --- |
| Served at | <http://localhost:3000/plugins/html.js> |
| Page in the console | <http://localhost:3000/pluginhtml> |
| Sidebar entry | `Plain HTML` |

No plugin server, no CORS, no build. `console/public/plugins/` is gitignored, so
what you copy in stays out of the repo.

## What to copy from it

**Every value from the API goes in through `textContent`, never `innerHTML`.**
Agent descriptions are free text written by whoever authored the agent, so
treating them as markup would turn the plugin into an injection sink. The `el()`
helper exists to make that the path of least resistance.

**`mount` must be idempotent and its cleanup must be real.** The console calls
`mount` again on every sub-path change, and React StrictMode causes a second
call per mount in development even for a plugin that uses no React. This plugin
returns a cleanup that removes its click listener; the console clears the host
element itself.

The plugin imports nothing from the console — a plugin builds against no
console source. The authoritative manifest and context shapes live in
[`console/lib/plugins/types.ts`](../../console/lib/plugins/types.ts).
