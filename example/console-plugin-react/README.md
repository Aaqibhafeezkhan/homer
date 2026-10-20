# Example console plugin

A Homer console plugin in React, with its own sub-routes, built to a single ES
module and loaded into the console at runtime.

It proves the mechanism end to end — sidebar entry, own route, own sub-routing,
and Homer API calls through the host's authenticated client. See
[ADR-0059](../../docs/adr/0059-console-plugins-are-runtime-loaded-modules.md)
for the design and, importantly, the trust model.

> **A plugin runs in the console's origin with the operator's privileges.**
> Putting a file in `console/public/plugins/` and naming it in `CONSOLE_PLUGINS`
> is the same act of trust as committing to `console/`. Do not put code there
> that you would not merge.

## Layout

| Path | Purpose |
| --- | --- |
| `src/plugin.tsx` | The plugin: manifest + mini app with sub-routes |
| `build.mjs` | esbuild → one self-contained ES module, straight into `console/public/plugins/fleet.js` |

## Run it

```bash
# 1. build the plugin — output lands in console/public/plugins/fleet.js
cd example/console-plugin-react
npm install
npm run build          # `node build.mjs --outfile=<path>` to put it elsewhere

# 2. name it in console/.env.local (gitignored, loaded by Next automatically)
#    CONSOLE_HOMER_API_URL=http://localhost:8080
#    CONSOLE_AUTH_ENABLED=false
#    CONSOLE_PLUGINS=/plugins/fleet.js

# 3. run the console
cd ../../console && npm run dev
```

| | |
| --- | --- |
| Served at | <http://localhost:3000/plugins/fleet.js> |
| Page in the console | <http://localhost:3000/myfleet> |
| Its sub-pages | `/myfleet/a`, `/myfleet/b` |
| Sidebar entry | `My Fleet` |

No plugin server and no CORS: the console imports plugins from its own origin.
`console/public/plugins/` is gitignored, so build output stays out of the repo.

`npm run typecheck` type-checks the plugin — esbuild only transpiles, so the
build alone will not catch type errors.

## Sub-routes

A plugin's `route` is only its **mount point**. Everything below it belongs to
the plugin, so `/myfleet/a` reaches the same plugin with
`ctx.path === '/a'`. Sub-routing is the plugin's own business — including
rendering its own 404 for a sub-path it does not know.

Two context members cover it:

| Member | Use |
| --- | --- |
| `ctx.path` | the current sub-path (`''` at the plugin root) |
| `ctx.navigate('/a')` | client-side navigation, relative to the plugin's route |

Use `ctx.navigate` rather than an `<a href>`: the anchor would trigger a full
page load and drop the whole console out from under you.

### Lifecycle — `mount` runs more than once

A sub-path change **remounts the plugin**: the App Router discards the page
subtree when the route params change. The example makes both halves visible:

- `Page A` / `Page B` keep a click counter in React state — it resets when you
  navigate, because that tree is gone.
- The header shows a `mounts` counter kept in **module scope** — it keeps
  climbing, because `ctx.navigate` is a client-side route change and the module
  is imported only once per page session. That is where a plugin puts state that
  must survive its own navigation (or use `sessionStorage`).

In development React StrictMode invokes effects twice, so `mounts` goes up by
two per navigation. Consequence for real plugins: `mount` must be idempotent and
its cleanup must actually release whatever it took.

## Writing your own

The default export is the manifest. `route` is the console path the plugin
claims; it must be a single lowercase segment and must not collide with a
built-in console route.

```ts
export default {
  route: '/myfleet',        // mount point; the plugin owns everything below it
  title: 'My Fleet',        // sidebar label
  mount(el, ctx) {          // ctx: { api, user, theme, path, navigate }
    const root = createRoot(el);
    root.render(<App ctx={ctx} />);
    return () => root.unmount();   // cleanup: on unmount, incl. every sub-path change
  },
};
```

Two constraints worth knowing before you start:

- **Bundle your own React.** Nothing is shared with the console, which is what
  keeps a plugin from being coupled to the console's React version.
- **Style with the console's CSS variables**, not Tailwind class names. The
  console's stylesheet only contains the utilities its own build scanned, so
  `className="text-muted-foreground"` will not resolve — but
  `style={{ color: 'hsl(var(--muted-foreground))' }}` inherits the active theme
  in every console theme, built-in or operator-supplied.

`src/plugin.tsx` re-declares the context and manifest types locally rather than
importing them from `console/`, because a plugin builds against no console
source. Keep them in sync with `console/lib/plugins/types.ts` by hand.


## Access control

Who may load a plugin is set in `CONSOLE_PLUGINS`, not in the manifest:

```
CONSOLE_PLUGINS=/plugins/fleet.js@admin
```

The console filters on that **before importing the module**, so a user who may
not use the plugin never downloads it. That is why the requirement is not a
manifest field — a manifest cannot be read without importing the module it sits
in, which is the very thing being withheld.
