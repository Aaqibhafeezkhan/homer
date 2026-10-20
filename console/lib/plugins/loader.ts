import { splitCommaList } from '@/lib/utils/list';
import type { LoadedPlugin, PluginManifest, PluginRequirement } from './types';

/** Plugin routes are single lowercase path segments — no nesting, no traversal. */
const ROUTE_PATTERN = /^\/[a-z0-9][a-z0-9-]*$/;

/**
 * Paths a plugin may not claim. Next resolves static routes before the plugin
 * catch-all, so a plugin on one of these would load and then silently never
 * render; rejecting it up front turns that into a visible error.
 */
export const RESERVED_ROUTES = [
  '/processes',
  '/questions',
  '/continuations',
  '/schedules',
  '/agents',
  '/costs',
  '/runners',
  '/login',
] as const;

/** Parses `CONSOLE_PLUGINS` (comma-separated entries) into a clean list. */
export function parsePluginEntries(raw: string | undefined | null): string[] {
  return splitCommaList(raw);
}

/**
 * A plugin path is root-relative and ends in `.js`/`.mjs`: the console imports
 * plugins from its **own origin only**, off files the operator put on disk.
 */
const PLUGIN_PATH_PATTERN = /^\/[A-Za-z0-9._-]+(\/[A-Za-z0-9._-]+)*\.m?js$/;

const REQUIREMENTS: readonly PluginRequirement[] = ['admin', 'run', 'read', 'control', 'edit', 'debug'];

/**
 * One `CONSOLE_PLUGINS` entry: a same-origin path, optionally followed by
 * `@<requirement>` — `/plugins/costs.js@admin`.
 *
 * The requirement lives in operator config rather than in the plugin's own
 * manifest for two reasons. It is enforceable **before** the module is fetched,
 * so a caller who may not use a plugin never learns it exists and never runs its
 * top-level code; and the access decision belongs to the operator deploying the
 * plugin, not to the plugin author, who could simply omit it. See ADR-0059.
 */
export interface PluginEntry {
  path: string;
  /** Absent = any authenticated caller. */
  requires?: PluginRequirement;
}

/** `@` cannot appear in a valid path, so it splits an entry unambiguously. */
function splitEntry(entry: string): [string, string | undefined] {
  const at = entry.indexOf('@');
  return at === -1 ? [entry, undefined] : [entry.slice(0, at), entry.slice(at + 1)];
}

/**
 * Why a `CONSOLE_PLUGINS` entry is not loadable, or `null` when it is fine.
 *
 * Only same-origin paths are accepted, which is what keeps plugin code out of
 * anyone else's hands: there is no network fetch to intercept, no foreign
 * origin to allowlist, and `script-src 'self'` already covers the import.
 *
 * The two path rejections that matter:
 *  - `//evil.example/p.js` is *protocol-relative*. It starts with `/` and reads
 *    as local, but resolves to a foreign origin — accepting it would turn a
 *    typo into a third-party module running with the operator's privileges.
 *  - `..` is rejected even inside an otherwise valid-looking path, so an entry
 *    can never climb out of the directory the operator meant.
 *
 * An unrecognised requirement is rejected rather than ignored: silently
 * dropping it would turn a typo (`@admn`) into a plugin open to everyone.
 */
export function checkPluginEntry(entry: string): string | null {
  const [path, requires] = splitEntry(entry);
  if (/^[a-z][a-z0-9+.-]*:/i.test(path) || path.startsWith('//')) {
    return 'must be a same-origin path like /plugins/name.js, not a URL';
  }
  if (path.includes('..')) return 'must not contain ".."';
  if (!PLUGIN_PATH_PATTERN.test(path)) {
    return `path must match ${String(PLUGIN_PATH_PATTERN)}`;
  }
  if (requires !== undefined && !REQUIREMENTS.includes(requires as PluginRequirement)) {
    return `unknown requirement ${JSON.stringify(requires)} (expected one of ${REQUIREMENTS.join(', ')})`;
  }
  return null;
}

/** Parses an entry that already passed {@link checkPluginEntry}. */
export function toPluginEntry(entry: string): PluginEntry {
  const [path, requires] = splitEntry(entry);
  return requires === undefined ? { path } : { path, requires: requires as PluginRequirement };
}

/** Why a manifest's route is unusable, or `null` when it is fine. */
function routeProblem(route: string | undefined): string | null {
  if (typeof route !== 'string' || !ROUTE_PATTERN.test(route)) {
    return `invalid route ${JSON.stringify(route)} (expected ${String(ROUTE_PATTERN)})`;
  }
  if ((RESERVED_ROUTES as readonly string[]).includes(route)) {
    return `route ${route} is reserved by the console`;
  }
  return null;
}

/** Why a candidate module was rejected, or `null` when the manifest is usable. */
export function validateManifest(candidate: unknown): string | null {
  if (typeof candidate !== 'object' || candidate === null) {
    return 'default export is not an object';
  }
  const m = candidate as Partial<PluginManifest>;
  const routeReason = routeProblem(m.route);
  if (routeReason !== null) return routeReason;
  if (typeof m.title !== 'string' || m.title.trim() === '') {
    return 'missing title';
  }
  if (typeof m.mount !== 'function') {
    return 'mount is not a function';
  }
  return null;
}

export type PluginImporter = (url: string) => Promise<{ default?: unknown }>;

/**
 * Imports a plugin module at runtime. `webpackIgnore` is load-bearing: without
 * it webpack rewrites this into a bundled require and resolves the path at build
 * time, which is exactly what a runtime plugin must not do. The browser
 * resolves the root-relative path against the console's own origin.
 */
const importFromDisk: PluginImporter = (path) =>
  import(/* webpackIgnore: true */ /* @vite-ignore */ path);

/**
 * Loads every configured plugin path, dropping the ones that fail. A broken or
 * missing plugin must never take the console down with it, so failures are
 * logged and skipped rather than thrown. On duplicate routes the first one wins.
 */
export async function loadPlugins(
  entries: PluginEntry[],
  importer: PluginImporter = importFromDisk,
): Promise<LoadedPlugin[]> {
  const settled = await Promise.all(
    entries.map(async ({ path, requires }): Promise<LoadedPlugin | null> => {
      try {
        const mod = await importer(path);
        const reason = validateManifest(mod?.default);
        if (reason !== null) {
          console.error(`[plugins] ignoring ${path}: ${reason}`);
          return null;
        }
        return { ...(mod.default as PluginManifest), sourceUrl: path, requires };
      } catch (e) {
        console.error(`[plugins] failed to load ${path}:`, e);
        return null;
      }
    }),
  );

  const byRoute = new Map<string, LoadedPlugin>();
  for (const plugin of settled) {
    if (plugin === null) continue;
    const existing = byRoute.get(plugin.route);
    if (existing) {
      console.error(
        `[plugins] ignoring ${plugin.sourceUrl}: route ${plugin.route} already claimed by ${existing.sourceUrl}`,
      );
      continue;
    }
    byRoute.set(plugin.route, plugin);
  }
  return [...byRoute.values()];
}
