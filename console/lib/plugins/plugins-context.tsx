'use client';

import { createContext, useContext, useEffect, useState } from 'react';
import { appConfig } from '@/lib/config/app-config';
import { useCan } from '@/lib/auth/use-can';
import { canUsePlugin } from './gate';
import { checkPluginEntry, loadPlugins, toPluginEntry } from './loader';
import type { LoadedPlugin } from './types';

export interface PluginsState {
  plugins: LoadedPlugin[];
  /**
   * True until the configured plugin modules have settled. The plugin route
   * must wait on this before rendering not-found, otherwise a hard refresh on
   * a plugin URL flashes "not found" while the module is still in flight.
   */
  loading: boolean;
}

const PluginsContext = createContext<PluginsState>({ plugins: [], loading: false });

export function PluginsProvider({ children }: { children: React.ReactNode }) {
  // Starts `loading` unconditionally, including when no plugins are configured:
  // on the server `appConfig` has no `window.__HOMER_CONFIG__` to read, so it
  // always sees zero plugin entries. Deriving the initial value from the entry
  // count would therefore render a different tree on the server than on the
  // client and break hydration.
  const [state, setState] = useState<PluginsState>({ plugins: [], loading: true });
  const { isAdmin, can } = useCan();

  useEffect(() => {
    let cancelled = false;

    // The requirement is filtered here, BEFORE the import: a caller who may not
    // use a plugin must not fetch its module, because the fetch itself would
    // disclose that the plugin exists and would run its top-level code in that
    // caller's browser. Gating only at the route would leave both.
    // This is why the requirement lives in the operator's `CONSOLE_PLUGINS`
    // entry and not in the plugin's manifest — a manifest cannot be consulted
    // without first importing the module it is in. See ADR-0059.
    //
    // Safe to read capabilities here: `DashboardLayout` mounts this provider
    // only once the auth probe has settled, so `isAdmin`/`can` are final rather
    // than the anonymous defaults.
    const entries = appConfig.plugins.entries
      .filter((entry) => {
        const reason = checkPluginEntry(entry);
        if (reason !== null) console.error(`[plugins] ignoring ${entry}: ${reason}`);
        return reason === null;
      })
      .map(toPluginEntry)
      .filter((entry) => canUsePlugin(entry.requires, { isAdmin, can }));

    void loadPlugins(entries)
      .then((plugins) => {
        if (!cancelled) setState({ plugins, loading: false });
      })
      // loadPlugins already contains its own per-entry failures, so this only
      // fires on something unforeseen — but without it the dashboard would sit
      // on "Loading plugins..." forever.
      .catch((e: unknown) => {
        console.error('[plugins] loader rejected unexpectedly:', e);
        if (!cancelled) setState({ plugins: [], loading: false });
      });

    return () => {
      cancelled = true;
    };
  }, [isAdmin, can]);

  return <PluginsContext.Provider value={state}>{children}</PluginsContext.Provider>;
}

export function usePlugins(): PluginsState {
  return useContext(PluginsContext);
}
