import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { PluginsProvider, usePlugins } from './plugins-context';
import type { Action } from '@/types/homer';
import type { LoadedPlugin } from './types';

const loadPluginsMock = vi.hoisted(() => vi.fn());

vi.mock('./loader', async (importOriginal) => ({
  // Real path/requirement parsing, mocked import — the point of these tests is
  // which entries reach the loader, not what the loader does with them.
  ...(await importOriginal<typeof import('./loader')>()),
  loadPlugins: loadPluginsMock,
}));
vi.mock('@/lib/config/app-config', () => ({ appConfig: { plugins: { entries: [] } } }));
vi.mock('@/lib/auth/use-can', () => ({ useCan: vi.fn() }));
import { appConfig } from '@/lib/config/app-config';
import { useCan } from '@/lib/auth/use-can';
import { fakeCapabilities } from '@/lib/auth/capabilities-test-utils';

function mockCan({ isAdmin, actions = [] }: { isAdmin: boolean; actions?: Action[] }) {
  vi.mocked(useCan).mockReturnValue(
    fakeCapabilities(isAdmin, { can: (action: Action) => isAdmin || actions.includes(action) }),
  );
}

function Probe() {
  const { plugins, loading } = usePlugins();
  return (
    <div>
      <span data-testid="loading">{String(loading)}</span>
      <span data-testid="titles">{plugins.map((p) => p.title).join(',')}</span>
    </div>
  );
}

function renderProvider() {
  return render(
    <PluginsProvider>
      <Probe />
    </PluginsProvider>,
  );
}

function plugin(title: string): LoadedPlugin {
  return { route: '/demo', title, sourceUrl: '/plugins/a.js', mount: () => undefined };
}

/** Paths the loader was actually asked to import. */
function importedPaths(): string[] {
  return (loadPluginsMock.mock.calls[0]?.[0] ?? []).map((e: { path: string }) => e.path);
}

describe('PluginsProvider', () => {
  beforeEach(() => {
    loadPluginsMock.mockReset();
    loadPluginsMock.mockResolvedValue([]);
    vi.mocked(useCan).mockReset();
    mockCan({ isAdmin: true });
    appConfig.plugins.entries = [];
  });

  it('settles to not-loading with no plugins when nothing is configured', async () => {
    renderProvider();
    await waitFor(() => expect(screen.getByTestId('loading')).toHaveTextContent('false'));
    expect(loadPluginsMock).toHaveBeenCalledWith([]);
    expect(screen.getByTestId('titles')).toBeEmptyDOMElement();
  });

  it('exposes the plugins the loader resolved', async () => {
    appConfig.plugins.entries = ['/plugins/a.js'];
    loadPluginsMock.mockResolvedValue([plugin('Demo')]);
    renderProvider();
    await waitFor(() => expect(screen.getByTestId('titles')).toHaveTextContent('Demo'));
    expect(screen.getByTestId('loading')).toHaveTextContent('false');
  });

  it('starts in the loading state even with no plugins configured', async () => {
    // The server always sees zero plugin entries (no window.__HOMER_CONFIG__),
    // so the first client render must match that tree or hydration breaks.
    const seen: boolean[] = [];
    function Recorder() {
      seen.push(usePlugins().loading);
      return null;
    }
    render(
      <PluginsProvider>
        <Recorder />
      </PluginsProvider>,
    );
    await waitFor(() => expect(seen).toContain(false));
    expect(seen[0]).toBe(true);
  });

  it('defaults to no plugins when used without a provider', () => {
    render(<Probe />);
    expect(screen.getByTestId('loading')).toHaveTextContent('false');
    expect(screen.getByTestId('titles')).toBeEmptyDOMElement();
  });

  it('recovers from an unexpected loader rejection instead of hanging on loading', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    appConfig.plugins.entries = ['/plugins/a.js'];
    loadPluginsMock.mockRejectedValue(new Error('boom'));
    renderProvider();
    await waitFor(() => expect(screen.getByTestId('loading')).toHaveTextContent('false'));
    vi.restoreAllMocks();
  });

  it('drops a malformed entry before it reaches the loader', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    appConfig.plugins.entries = ['https://evil.example/p.js', '/plugins/ok.js'];
    renderProvider();
    await waitFor(() => expect(loadPluginsMock).toHaveBeenCalled());
    expect(importedPaths()).toEqual(['/plugins/ok.js']);
    vi.restoreAllMocks();
  });

  /**
   * The gate has to be applied before the import, not only at the route: the
   * module fetch would itself disclose that a gated plugin exists, and would run
   * its top-level code in the browser of a caller who may not use it.
   */
  describe('the requirement is enforced before the module is fetched', () => {
    it('never asks the loader for an admin-only plugin when the caller is not admin', async () => {
      mockCan({ isAdmin: false });
      appConfig.plugins.entries = ['/plugins/open.js', '/plugins/secret.js@admin'];
      renderProvider();
      await waitFor(() => expect(loadPluginsMock).toHaveBeenCalled());
      expect(importedPaths()).toEqual(['/plugins/open.js']);
      expect(importedPaths()).not.toContain('/plugins/secret.js');
    });

    it('asks for the admin-only plugin when the caller is admin', async () => {
      mockCan({ isAdmin: true });
      appConfig.plugins.entries = ['/plugins/secret.js@admin'];
      renderProvider();
      await waitFor(() => expect(loadPluginsMock).toHaveBeenCalled());
      expect(importedPaths()).toEqual(['/plugins/secret.js']);
    });

    it('honours an Action requirement the caller lacks', async () => {
      mockCan({ isAdmin: false, actions: ['read'] });
      appConfig.plugins.entries = ['/plugins/r.js@read', '/plugins/c.js@control'];
      renderProvider();
      await waitFor(() => expect(loadPluginsMock).toHaveBeenCalled());
      expect(importedPaths()).toEqual(['/plugins/r.js']);
    });

    it('passes the requirement through so the route can re-check it', async () => {
      appConfig.plugins.entries = ['/plugins/secret.js@admin'];
      renderProvider();
      await waitFor(() => expect(loadPluginsMock).toHaveBeenCalled());
      expect(loadPluginsMock.mock.calls[0][0]).toEqual([
        { path: '/plugins/secret.js', requires: 'admin' },
      ]);
    });
  });
});
