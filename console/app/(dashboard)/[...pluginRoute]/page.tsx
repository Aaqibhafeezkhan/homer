'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { PluginHost } from '@/components/plugins/plugin-host';
import { usePlugins } from '@/lib/plugins/plugins-context';
import { useCan } from '@/lib/auth/use-can';
import { canUsePlugin } from '@/lib/plugins/gate';

export const dynamic = 'force-dynamic';

/**
 * Catch-all for runtime plugin routes. Being the group-root catch-all, this is
 * also the handler for any unmatched dashboard path — Next resolves the static
 * routes first.
 */
export default function PluginRoutePage() {
  const params = useParams<{ pluginRoute?: string | string[] }>();
  const { plugins, loading } = usePlugins();
  const { isAdmin, can } = useCan();

  const { route, subPath } = splitPluginPath(params.pluginRoute);
  const plugin = route === null ? undefined : plugins.find((p) => p.route === route);

  if (loading) {
    return <div className="p-6 text-muted-foreground">Loading plugins...</div>;
  }

  // Denied and non-existent render the same thing on purpose: a gated plugin's
  // existence must not leak to callers who cannot open it (root CLAUDE.md §12).
  if (!plugin || !canUsePlugin(plugin.requires, { isAdmin, can })) {
    return <NotFound />;
  }

  return <PluginHost plugin={plugin} subPath={subPath} />;
}

/**
 * A plugin owns its first segment and everything below it, so `/p/a` resolves
 * to the plugin at `/p` with sub-path `/a`. Matching the first segment
 * exactly (rather than by string prefix) is what stops `/pluginx` from
 * resolving to the plugin at `/plugin`.
 */
function splitPluginPath(pluginRoute: string | string[] | undefined): {
  route: string | null;
  subPath: string;
} {
  const segments = Array.isArray(pluginRoute)
    ? pluginRoute
    : pluginRoute
      ? [pluginRoute]
      : [];
  const route = segments.length > 0 ? `/${segments[0]}` : null;
  const subPath = segments.length > 1 ? `/${segments.slice(1).join('/')}` : '';
  return { route, subPath };
}

function NotFound() {
  return (
    <div className="p-6 space-y-3">
      <h2 className="text-2xl font-bold">Page not found</h2>
      <p className="text-muted-foreground">This page does not exist.</p>
      <Link href="/processes" className="underline">
        Back to processes
      </Link>
    </div>
  );
}
