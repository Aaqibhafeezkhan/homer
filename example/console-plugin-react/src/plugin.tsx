import { useCallback, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';

/**
 * Mirrors `console/lib/plugins/types.ts`. Duplicated on purpose: a plugin is
 * built and deployed independently and must not import console source.
 */
interface PluginContext {
  api: {
    getProcesses(filters?: { limit?: number }): Promise<{ processes: unknown[]; total?: number }>;
    getAgents(): Promise<{ agents: { name: string; description?: string }[] }>;
  };
  user: { username: string; role: string } | null;
  theme?: string;
  /** `''` at the plugin root, `/a` on `<route>/a`. Fresh on every (re)mount. */
  path: string;
  navigate(path: string): void;
}

/** Console theme tokens are plain CSS vars, so a plugin inherits the active theme. */
const s = {
  card: {
    border: '1px solid hsl(var(--border))',
    borderRadius: 'var(--radius)',
    background: 'hsl(var(--card))',
    color: 'hsl(var(--card-foreground))',
    padding: '1rem',
    marginBottom: '1rem',
  },
  muted: { color: 'hsl(var(--muted-foreground))', fontSize: '0.875rem' },
  button: {
    border: '1px solid hsl(var(--border))',
    borderRadius: 'var(--radius)',
    background: 'hsl(var(--primary))',
    color: 'hsl(var(--primary-foreground))',
    padding: '0.5rem 0.75rem',
    cursor: 'pointer',
  },
  tab: (active: boolean) => ({
    border: '1px solid hsl(var(--border))',
    borderRadius: 'var(--radius)',
    background: active ? 'hsl(var(--primary))' : 'transparent',
    color: active ? 'hsl(var(--primary-foreground))' : 'hsl(var(--foreground))',
    padding: '0.375rem 0.75rem',
    marginRight: '0.5rem',
    cursor: 'pointer',
  }),
} as const;

function Overview({ ctx }: { ctx: PluginContext }) {
  const [agents, setAgents] = useState<string[] | null>(null);
  const [processCount, setProcessCount] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const [agentList, processes] = await Promise.all([
        ctx.api.getAgents(),
        ctx.api.getProcesses({ limit: 5 }),
      ]);
      setAgents(agentList.agents.map((a) => a.name));
      setProcessCount(processes.total ?? processes.processes.length);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [ctx]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div style={s.card}>
      <h3 style={{ fontWeight: 600, marginBottom: '0.5rem' }}>Homer API through the host client</h3>
      {error !== null && <p style={{ color: 'hsl(var(--destructive))' }}>API error: {error}</p>}
      {error === null && agents === null && <p style={s.muted}>Loading...</p>}
      {agents !== null && (
        <>
          <p>
            processes: <strong>{processCount}</strong>
          </p>
          <p>
            agents ({agents.length}): <strong>{agents.join(', ') || 'none'}</strong>
          </p>
        </>
      )}
      <button type="button" style={{ ...s.button, marginTop: '0.75rem' }} onClick={() => void load()}>
        Reload
      </button>
    </div>
  );
}

function SubPage({ label }: { label: string }) {
  const [clicks, setClicks] = useState(0);
  return (
    <div style={s.card}>
      <h3 style={{ fontWeight: 600, marginBottom: '0.5rem' }}>Sub-page {label}</h3>
      <p style={s.muted}>
        Rendered from the URL, not from component state. Deep-link or reload this address and you
        land back here.
      </p>
      <p style={{ marginTop: '0.5rem' }}>
        clicks: <strong>{clicks}</strong> {'—'} resets on navigation, because the console remounts
        the plugin when the sub-path changes.
      </p>
      <button type="button" style={{ ...s.button, marginTop: '0.5rem' }} onClick={() => setClicks((c) => c + 1)}>
        Click
      </button>
    </div>
  );
}

const TABS: { path: string; label: string }[] = [
  { path: '', label: 'Overview' },
  { path: '/a', label: 'Page A' },
  { path: '/b', label: 'Page B' },
];

/**
 * Module scope outlives the React tree: `ctx.navigate` is a client-side route
 * change, so the module is imported once while the tree is rebuilt each time.
 * This is where a plugin puts state that must survive its own navigation.
 */
let mountCount = 0;

function App({ ctx }: { ctx: PluginContext }) {
  const path = ctx.path;

  return (
    <div>
      <h2 style={{ fontSize: '1.875rem', fontWeight: 700, marginBottom: '1rem' }}>My Fleet</h2>

      <div style={s.card}>
        <p style={s.muted}>
          Runtime plugin, own React {'—'} a file on the console's own disk, imported at runtime.
        </p>
        <p style={s.muted}>
          user: <strong>{ctx.user?.username ?? 'anonymous'}</strong> {'·'} role:{' '}
          <strong>{ctx.user?.role ?? 'n/a'}</strong> {'·'} theme:{' '}
          <strong>{ctx.theme ?? 'unknown'}</strong>
        </p>
        <p style={s.muted}>
          sub-path: <strong>{path === '' ? '(root)' : path}</strong> {'·'} module-scope mounts:{' '}
          <strong>{mountCount}</strong>
        </p>
      </div>

      <nav style={{ marginBottom: '1rem' }}>
        {TABS.map((tab) => (
          <button
            key={tab.path}
            type="button"
            style={s.tab(path === tab.path)}
            onClick={() => ctx.navigate(tab.path)}
          >
            {tab.label}
          </button>
        ))}
      </nav>

      {path === '' && <Overview ctx={ctx} />}
      {path === '/a' && <SubPage label="A" />}
      {path === '/b' && <SubPage label="B" />}
      {!TABS.some((t) => t.path === path) && (
        <div style={s.card}>
          <h3 style={{ fontWeight: 600 }}>Unknown sub-page</h3>
          <p style={s.muted}>
            Nothing is registered for <strong>{path}</strong>. Sub-routing below the plugin&apos;s
            own route is the plugin&apos;s business, so this 404 is ours to render, not the
            console&apos;s.
          </p>
        </div>
      )}
    </div>
  );
}

export default {
  route: '/myfleet',
  title: 'My Fleet',
  mount(el: HTMLElement, ctx: PluginContext) {
    mountCount += 1;
    const root = createRoot(el);
    root.render(<App ctx={ctx} />);
    return () => root.unmount();
  },
};
