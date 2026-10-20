import type { homerApi } from '@/lib/api/client';
import type { AuthUser } from '@/lib/auth/auth-context';
import type { Action } from '@/types/homer';

/**
 * What the console hands a plugin at mount time. A plugin bundles its own React
 * and cannot import from the console, so this object is its entire view of the
 * host — extending it is a breaking change for every deployed plugin.
 */
export interface PluginContext {
  /** Authenticated Homer API client, already bound to the active instance. */
  api: typeof homerApi;
  user: AuthUser | null;
  /** Active `next-themes` theme name, so a plugin can branch on it if it must. */
  theme: string | undefined;
  /**
   * Path *below* the plugin's own route: `''` at the plugin root, `/a` on
   * `/myfleet/a`. Always starts with `/` when non-empty.
   *
   * The App Router discards the page subtree when the catch-all params change,
   * so the plugin is **remounted** on every sub-path change and always sees a
   * current value here. State that must outlive in-plugin navigation belongs in
   * module scope (which survives) or `sessionStorage`, not in the plugin's tree.
   */
  path: string;
  /**
   * Client-side navigation within the plugin, relative to its route:
   * `navigate('/a')` goes to `<route>/a`, `navigate('')` to the plugin root.
   * Use this rather than an `<a href>`, which would trigger a full page load
   * and re-import the plugin module.
   */
  navigate(path: string): void;
}

/** Default export a remote plugin module must provide. */
export interface PluginManifest {
  /**
   * Mount point of the plugin, e.g. `/myfleet`. Must match
   * {@link ROUTE_PATTERN} — a single segment. The plugin also receives every
   * path below it (`/myfleet/a`) via {@link PluginContext.path}, so
   * sub-routing is the plugin's own business.
   */
  route: string;
  /** Sidebar label. */
  title: string;
  /**
   * Renders the plugin into `el`. The returned function, if any, is called on
   * unmount — a React plugin returns `() => root.unmount()`.
   *
   * Called again on every sub-path change, and twice per mount under React
   * StrictMode in development, so it must be idempotent and its cleanup must
   * actually release what it took.
   */
  mount(el: HTMLElement, ctx: PluginContext): void | (() => void);
}

/** Capability a caller must hold to see and open a plugin. */
export type PluginRequirement = 'admin' | Action;

export interface LoadedPlugin extends PluginManifest {
  /** Module path this manifest came from; shown in load-failure diagnostics. */
  sourceUrl: string;
  /**
   * Requirement from the plugin's `CONSOLE_PLUGINS` entry, not from its
   * manifest — the operator owns this decision, and it is enforced before the
   * module is fetched. Absent = any authenticated caller.
   */
  requires?: PluginRequirement;
}
