import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const getAuthMe = vi.fn();
const login = vi.fn();
const logout = vi.fn();
const getHeartbeat = vi.fn();

// Hoisted so the vi.mock factory (also hoisted) can reference it and tests can
// construct instances that `instanceof` matches inside auth-context.
const { HomerApiError } = vi.hoisted(() => {
  class HomerApiError extends Error {
    status?: number;
    constructor(message: string, status?: number) {
      super(message);
      this.status = status;
      this.name = 'HomerApiError';
    }
  }
  return { HomerApiError };
});

vi.mock('@/lib/api/client', () => ({
  homerApi: {
    getAuthMe: () => getAuthMe(),
    login: (u: string, p: string) => login(u, p),
    logout: () => logout(),
    getHeartbeat: () => getHeartbeat(),
  },
  HomerApiError,
}));

// Mutable so the two shapes of "auth is off" can share this file; the provider
// re-reads it on every re-import (see renderWithCurrentConfig).
const config = vi.hoisted(() => ({ enabled: false, mode: 'disabled' }));

// The mock factory runs once per file and its result is cached — vi.resetModules()
// re-evaluates the modules that import it, not the factory itself — so the fields
// below are getters. A snapshot would freeze whichever shape ran first and every
// later row would silently be testing that one.
vi.mock('@/lib/config/app-config', () => ({
  appConfig: {
    api: { baseUrl: 'http://localhost:8080' },
    auth: {
      get enabled() {
        return config.enabled;
      },
      get mode() {
        return config.mode;
      },
      allowBearer: false,
      tokenKey: 'homer_auth_token',
    },
  },
}));

// `authProbeDisabled` is a module-load constant, so each deployment shape is
// pinned by re-importing the provider (and the instance store it reads) after
// the config is set. This suite therefore cannot use auth-test-utils, whose
// AuthProvider is bound at load time.
async function renderWithCurrentConfig() {
  vi.resetModules();
  const [{ AuthProvider, useAuth }, { resetInstancesStoreForTests }] = await Promise.all([
    import('./auth-context'),
    import('@/lib/instances/store'),
  ]);
  resetInstancesStoreForTests();

  function Probe() {
    const { status, user, login: doLogin, logout: doLogout } = useAuth();
    return (
      <div>
        <div data-testid="status">{status}</div>
        <div data-testid="username">{user?.username ?? ''}</div>
        <button onClick={() => void doLogin('alice', 'pw')}>do-login</button>
        <button onClick={() => void doLogout()}>do-logout</button>
      </div>
    );
  }

  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <AuthProvider>
        <Probe />
      </AuthProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  getAuthMe.mockReset();
  login.mockReset();
  logout.mockReset();
  getHeartbeat.mockReset();
  localStorage.clear();
});

describe.each([
  ['CONSOLE_AUTH_ENABLED=false', { enabled: false, mode: 'cookie' }],
  ['auth.mode=disabled', { enabled: true, mode: 'disabled' }],
])('AuthProvider (auth off: %s)', (_label, auth) => {
  beforeEach(() => {
    config.enabled = auth.enabled;
    config.mode = auth.mode;
  });

  it('pins authed with no principal and probes nothing', async () => {
    // The unsecured opt-in (ADR-0036): the console is signed in without a
    // session, so no credential is asked for and no probe is sent.
    await renderWithCurrentConfig();

    expect(screen.getByTestId('status')).toHaveTextContent('authed');
    expect(screen.getByTestId('username')).toHaveTextContent('');
    expect(getAuthMe).not.toHaveBeenCalled();
    expect(getHeartbeat).not.toHaveBeenCalled();
  });

  it('stays authed across logout and calls no logout endpoint', async () => {
    // With auth off there is no session to end, so logout must not reach the
    // backend; the pinned state outranks it either way.
    await renderWithCurrentConfig();
    expect(screen.getByTestId('status')).toHaveTextContent('authed');

    await act(async () => {
      screen.getByText('do-logout').click();
    });

    expect(screen.getByTestId('status')).toHaveTextContent('authed');
    expect(logout).not.toHaveBeenCalled();
  });
});

describe('AuthProvider (auth on: control)', () => {
  it('probes the backend instead of pinning authed', async () => {
    // Control for the lazy config above: with auth on the same re-import path
    // must resolve from the probe. If the mock ever froze a shape again, this
    // would keep reading the "auth off" one and fail.
    config.enabled = true;
    config.mode = 'cookie';
    getAuthMe.mockRejectedValue(new HomerApiError('Unauthorized', 401));

    await renderWithCurrentConfig();

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('anon'));
    expect(getAuthMe).toHaveBeenCalled();
  });
});
