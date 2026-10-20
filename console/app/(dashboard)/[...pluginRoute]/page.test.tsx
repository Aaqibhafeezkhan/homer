import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import PluginRoutePage from './page';
import type { Action } from '@/types/homer';
import type { LoadedPlugin } from '@/lib/plugins/types';

const useParamsMock = vi.hoisted(() => vi.fn());
const usePluginsMock = vi.hoisted(() => vi.fn());

vi.mock('next/navigation', () => ({ useParams: useParamsMock }));
vi.mock('@/lib/plugins/plugins-context', () => ({ usePlugins: usePluginsMock }));
vi.mock('@/lib/auth/use-can', () => ({ useCan: vi.fn() }));
import { useCan } from '@/lib/auth/use-can';
import { fakeCapabilities } from '@/lib/auth/capabilities-test-utils';

// The host is exercised in its own test; here it only has to prove which
// plugin the route resolved.
vi.mock('@/components/plugins/plugin-host', () => ({
  PluginHost: ({ plugin, subPath }: { plugin: LoadedPlugin; subPath: string }) => (
    <div>
      mounted:{plugin.title}
      <span data-testid="sub-path">{subPath === '' ? '(empty)' : subPath}</span>
    </div>
  ),
}));

function plugin(over: Partial<LoadedPlugin> = {}): LoadedPlugin {
  return {
    route: '/demo',
    title: 'Demo',
    sourceUrl: 'https://plugins.test/p.js',
    mount: () => undefined,
    ...over,
  };
}

function mockCan({ isAdmin, actions = [] }: { isAdmin: boolean; actions?: Action[] }) {
  vi.mocked(useCan).mockReturnValue(
    fakeCapabilities(isAdmin, { can: (action: Action) => isAdmin || actions.includes(action) }),
  );
}

describe('plugin catch-all route', () => {
  beforeEach(() => {
    vi.mocked(useCan).mockReset();
    usePluginsMock.mockReset();
    useParamsMock.mockReset();
    mockCan({ isAdmin: true });
    usePluginsMock.mockReturnValue({ plugins: [plugin()], loading: false });
    useParamsMock.mockReturnValue({ pluginRoute: ['demo'] });
  });

  it('renders the plugin whose route matches', () => {
    render(<PluginRoutePage />);
    expect(screen.getByText('mounted:Demo')).toBeInTheDocument();
  });

  it('accepts a single string param as well as an array', () => {
    useParamsMock.mockReturnValue({ pluginRoute: 'demo' });
    render(<PluginRoutePage />);
    expect(screen.getByText('mounted:Demo')).toBeInTheDocument();
  });

  it('shows not-found for an unmatched path', () => {
    useParamsMock.mockReturnValue({ pluginRoute: ['nope'] });
    render(<PluginRoutePage />);
    expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
  });

  describe('sub-paths', () => {
    it('routes a path below the plugin to the plugin', () => {
      useParamsMock.mockReturnValue({ pluginRoute: ['demo', 'a'] });
      render(<PluginRoutePage />);
      expect(screen.getByText('mounted:Demo')).toBeInTheDocument();
    });

    it('passes the empty sub-path at the plugin root', () => {
      render(<PluginRoutePage />);
      expect(screen.getByTestId('sub-path')).toHaveTextContent('(empty)');
    });

    it('passes a single-segment sub-path', () => {
      useParamsMock.mockReturnValue({ pluginRoute: ['demo', 'a'] });
      render(<PluginRoutePage />);
      expect(screen.getByTestId('sub-path')).toHaveTextContent('/a');
    });

    it('passes a nested sub-path whole', () => {
      useParamsMock.mockReturnValue({ pluginRoute: ['demo', 'a', 'b', 'c'] });
      render(<PluginRoutePage />);
      expect(screen.getByTestId('sub-path')).toHaveTextContent('/a/b/c');
    });

    it('does not let a longer first segment match a shorter plugin route', () => {
      useParamsMock.mockReturnValue({ pluginRoute: ['demox'] });
      render(<PluginRoutePage />);
      expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
    });

    it('still denies a gated plugin reached through a sub-path', () => {
      mockCan({ isAdmin: false });
      usePluginsMock.mockReturnValue({ plugins: [plugin({ requires: 'admin' })], loading: false });
      useParamsMock.mockReturnValue({ pluginRoute: ['demo', 'a'] });
      render(<PluginRoutePage />);
      expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
    });
  });

  it('waits for plugins to settle rather than flashing not-found', () => {
    usePluginsMock.mockReturnValue({ plugins: [], loading: true });
    render(<PluginRoutePage />);
    expect(screen.getByText(/Loading plugins/)).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Page not found' })).not.toBeInTheDocument();
  });

  describe('denial', () => {
    it('hides an admin-only plugin from a non-admin behind the same not-found', () => {
      mockCan({ isAdmin: false });
      usePluginsMock.mockReturnValue({
        plugins: [plugin({ requires: 'admin' })],
        loading: false,
      });
      render(<PluginRoutePage />);
      expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
      expect(screen.queryByText('mounted:Demo')).not.toBeInTheDocument();
    });

    it('denies an action-gated plugin when the caller lacks the action', () => {
      mockCan({ isAdmin: false, actions: ['read'] });
      usePluginsMock.mockReturnValue({
        plugins: [plugin({ requires: 'control' })],
        loading: false,
      });
      render(<PluginRoutePage />);
      expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
    });

    it('allows an action-gated plugin when the caller holds the action', () => {
      mockCan({ isAdmin: false, actions: ['read'] });
      usePluginsMock.mockReturnValue({ plugins: [plugin({ requires: 'read' })], loading: false });
      render(<PluginRoutePage />);
      expect(screen.getByText('mounted:Demo')).toBeInTheDocument();
    });

    it('allows an ungated plugin for a non-admin', () => {
      mockCan({ isAdmin: false });
      render(<PluginRoutePage />);
      expect(screen.getByText('mounted:Demo')).toBeInTheDocument();
    });
  });
});
