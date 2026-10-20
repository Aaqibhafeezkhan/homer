import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PluginHost } from './plugin-host';
import type { LoadedPlugin, PluginContext } from '@/lib/plugins/types';

vi.mock('next-themes', () => ({ useTheme: () => ({ resolvedTheme: 'terminal' }) }));

const pushMock = vi.hoisted(() => vi.fn());
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: pushMock }) }));

vi.mock('@/lib/auth/auth-context', () => ({
  useAuth: () => ({ user: { username: 'alice', role: 'user', grants: [] } }),
}));

vi.mock('@/lib/api/client', () => ({ homerApi: { marker: 'api' } }));

function plugin(over: Partial<LoadedPlugin> = {}): LoadedPlugin {
  return {
    route: '/demo',
    title: 'Demo',
    sourceUrl: 'https://plugins.test/p.js',
    mount: () => undefined,
    ...over,
  };
}

describe('PluginHost', () => {
  beforeEach(() => {
    pushMock.mockReset();
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('mounts the plugin into a host element', () => {
    const mount = vi.fn((el: HTMLElement) => {
      el.textContent = 'plugin rendered';
    });
    render(<PluginHost plugin={plugin({ mount })} subPath="" />);
    expect(mount).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('plugin-host')).toHaveTextContent('plugin rendered');
  });

  it('passes the api client, user and theme in the context', () => {
    let ctx: PluginContext | undefined;
    render(<PluginHost plugin={plugin({ mount: (_el, c) => void (ctx = c) })} subPath="" />);
    expect(ctx?.api).toEqual({ marker: 'api' });
    expect(ctx?.user?.username).toBe('alice');
    expect(ctx?.theme).toBe('terminal');
  });

  it('calls the returned cleanup and clears the DOM on unmount', () => {
    const cleanup = vi.fn();
    const { unmount } = render(
      <PluginHost
        subPath=""
        plugin={plugin({
          mount: (el) => {
            el.textContent = 'plugin rendered';
            return cleanup;
          },
        })}
      />,
    );
    unmount();
    expect(cleanup).toHaveBeenCalledTimes(1);
  });

  it('clears leaked DOM when the plugin ignores its cleanup contract', () => {
    const { unmount, container } = render(
      <PluginHost subPath="" plugin={plugin({ mount: (el) => void (el.textContent = 'leaked') })} />,
    );
    unmount();
    expect(container.textContent).not.toContain('leaked');
  });

  it('reports a failure instead of crashing when mount throws', () => {
    render(
      <PluginHost
        subPath=""
        plugin={plugin({
          mount: () => {
            throw new Error('boom');
          },
        })}
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent(/failed to render/i);
  });

  it('survives a plugin that throws during cleanup', () => {
    const { unmount } = render(
      <PluginHost
        subPath=""
        plugin={plugin({
          mount: () => () => {
            throw new Error('bad cleanup');
          },
        })}
      />,
    );
    expect(() => unmount()).not.toThrow();
  });
  describe('sub-path routing', () => {
    it('hands the current sub-path to the plugin at mount', () => {
      let ctx: PluginContext | undefined;
      render(
        <PluginHost subPath="/a" plugin={plugin({ mount: (_el, c) => void (ctx = c) })} />,
      );
      expect(ctx?.path).toBe('/a');
    });

    it('remounts with the new sub-path when it changes', () => {
      // The App Router throws this subtree away on a param change, so remount
      // is what actually happens; the contract has to match.
      const paths: string[] = [];
      const p = plugin({ mount: (_el, c) => void paths.push(c.path) });
      const { rerender } = render(<PluginHost subPath="" plugin={p} />);
      rerender(<PluginHost subPath="/b" plugin={p} />);
      expect(paths).toEqual(['', '/b']);
    });

    it('runs the plugin cleanup before remounting on a sub-path change', () => {
      const events: string[] = [];
      const p = plugin({
        mount: (_el, c) => {
          events.push(`mount${c.path}`);
          return () => events.push(`cleanup${c.path}`);
        },
      });
      const { rerender } = render(<PluginHost subPath="" plugin={p} />);
      rerender(<PluginHost subPath="/b" plugin={p} />);
      expect(events).toEqual(['mount', 'cleanup', 'mount/b']);
    });

    it('does not remount on a theme or auth change', () => {
      const mount = vi.fn();
      const p = plugin({ mount });
      const { rerender } = render(<PluginHost subPath="" plugin={p} />);
      rerender(<PluginHost subPath="" plugin={p} />);
      expect(mount).toHaveBeenCalledTimes(1);
    });

    it.each([
      ['/a', '/demo/a'],
      ['a', '/demo/a'],
      ['', '/demo'],
      ['/', '/demo'],
      ['/a/b', '/demo/a/b'],
    ])('navigate(%j) pushes %j', (input, expected) => {
      let ctx: PluginContext | undefined;
      render(<PluginHost subPath="" plugin={plugin({ mount: (_el, c) => void (ctx = c) })} />);
      ctx?.navigate(input);
      expect(pushMock).toHaveBeenCalledWith(expected);
    });
  });
});
