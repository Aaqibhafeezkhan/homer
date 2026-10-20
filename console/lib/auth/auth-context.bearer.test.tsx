import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, act, waitFor } from '@testing-library/react';

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

// Bearer mode lives in its own file because `authProbeDisabled` and the
// mode branch in `probeAuth` are read at module-load time from this mock,
// so the cookie-mode suite can't switch modes per-test.
vi.mock('@/lib/config/app-config', () => ({
  appConfig: {
    auth: { enabled: true, mode: 'bearer', tokenKey: 'homer_auth_token' },
  },
}));

import { useAuth } from './auth-context';
import { renderWithProvider } from './auth-test-utils';
import { resetInstancesStoreForTests } from '@/lib/instances/store';

// Bearer tokens are namespaced per instance; the seeded default instance's key.
const TOKEN_KEY = 'homer_auth_token::default';

describe('AuthProvider (bearer mode)', () => {
  beforeEach(() => {
    getAuthMe.mockReset();
    login.mockReset();
    logout.mockReset();
    getHeartbeat.mockReset();
    resetInstancesStoreForTests();
  });

  it('lands anon when no bearer token is stored (probeAuthBearer throws)', async () => {
    renderWithProvider();
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );
    expect(screen.getByTestId('username')).toHaveTextContent('');
    expect(getHeartbeat).not.toHaveBeenCalled();
  });

  it('lands authed as the synthetic bearer principal when a stored token probes ok', async () => {
    localStorage.setItem(TOKEN_KEY, 'stored-token');
    getHeartbeat.mockResolvedValue(undefined);

    renderWithProvider();

    expect(screen.getByTestId('status')).toHaveTextContent('loading');
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authed'),
    );
    expect(screen.getByTestId('username')).toHaveTextContent('bearer');
    expect(getHeartbeat).toHaveBeenCalled();
    // The bearer principal is synthetic — the backend is never asked for
    // role/grants, so it is always the fixed admin/no-grants shape.
    expect(screen.getByTestId('role')).toHaveTextContent('admin');
    expect(screen.getByTestId('grant-count')).toHaveTextContent('0');
  });

  it('login stores the password as the token, probes /heartbeat, and lands authed', async () => {
    getHeartbeat.mockResolvedValue(undefined);

    renderWithProvider();
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );

    await act(async () => {
      screen.getByText('do-login').click();
    });

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authed'),
    );
    // login() ignores the username and stores the password argument as the token.
    expect(localStorage.getItem(TOKEN_KEY)).toBe('pw');
    expect(getHeartbeat).toHaveBeenCalled();
    expect(login).not.toHaveBeenCalled();
    expect(screen.getByTestId('username')).toHaveTextContent('bearer');
  });

  it('login removes the token and stays anon when /heartbeat rejects', async () => {
    // First probe (mount) and the post-login probe both reject.
    getHeartbeat.mockRejectedValue(new Error('401'));

    let loginResult: string | undefined;
    function LoginResultProbe() {
      const { status, login } = useAuth();
      return (
        <div>
          <div data-testid="status">{status}</div>
          <button
            onClick={() =>
              void login('alice', 'tok').then((r) => {
                loginResult = r;
              })
            }
          >
            do-login
          </button>
        </div>
      );
    }

    renderWithProvider(<LoginResultProbe />);

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );

    await act(async () => {
      screen.getByText('do-login').click();
    });

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    // A plain (non-HomerApiError) rejection classifies as 'error', not a
    // credential rejection — the form maps this to "could not reach the server".
    expect(loginResult).toBe('error');
  });

  it('logout removes the token and lands anon without calling homerApi.logout', async () => {
    localStorage.setItem(TOKEN_KEY, 'stored-token');
    getHeartbeat.mockResolvedValue(undefined);

    renderWithProvider();
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authed'),
    );

    await act(async () => {
      screen.getByText('do-logout').click();
    });

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    // Bearer logout is purely local; the cookie-mode endpoint is not hit.
    expect(logout).not.toHaveBeenCalled();
  });
});
