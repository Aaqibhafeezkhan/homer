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

vi.mock('@/lib/config/app-config', () => ({
  appConfig: {
    auth: { enabled: true, mode: 'cookie', tokenKey: 'homer_auth_token' },
  },
}));

import { useAuth } from './auth-context';
import { notifyAuthExpired } from './auth-expired-bus';
import { StatusProbe, renderWithProvider } from './auth-test-utils';
import { resetInstancesStoreForTests } from '@/lib/instances/store';

describe('AuthProvider (cookie mode)', () => {
  beforeEach(() => {
    getAuthMe.mockReset();
    login.mockReset();
    logout.mockReset();
    getHeartbeat.mockReset();
    resetInstancesStoreForTests();
  });

  it('starts in loading then transitions to authed once /auth/me resolves', async () => {
    getAuthMe.mockResolvedValue({ username: 'alice', role: 'admin', grants: [] });
    renderWithProvider();

    expect(screen.getByTestId('status')).toHaveTextContent('loading');
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authed'),
    );
    expect(screen.getByTestId('username')).toHaveTextContent('alice');
  });

  it('falls through to anon when /auth/me rejects (401-like)', async () => {
    getAuthMe.mockRejectedValue(new Error('401'));
    renderWithProvider();
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );
    expect(screen.getByTestId('username')).toHaveTextContent('');
  });

  it('login posts credentials, fetches /me, seeds the cache, and lands authed', async () => {
    let probeShouldSucceed = false;
    getAuthMe.mockImplementation(() => {
      if (probeShouldSucceed) {
        return Promise.resolve({ username: 'alice', role: 'admin', grants: [] });
      }
      return Promise.reject(new Error('401'));
    });
    login.mockImplementation(() => {
      probeShouldSucceed = true;
      return Promise.resolve(undefined);
    });

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
    expect(login).toHaveBeenCalledWith('alice', 'pw');
    expect(getAuthMe).toHaveBeenCalled();
  });

  it('login never transitions through loading after the initial anon state', async () => {
    // Pins the fix for the post-login "Loading..." stuck screen: once the
    // auth-me probe has resolved once (success or failure), the AuthProvider
    // must never bounce back into status='loading' on subsequent login()
    // calls. The synchronous override flips status to 'authed' without
    // depending on the React Query observer's re-render landing first.
    let probeShouldSucceed = false;
    getAuthMe.mockImplementation(() => {
      if (probeShouldSucceed) {
        return Promise.resolve({ username: 'alice', role: 'admin', grants: [] });
      }
      return Promise.reject(new Error('401'));
    });
    login.mockImplementation(() => {
      probeShouldSucceed = true;
      return Promise.resolve(undefined);
    });

    const seenStatuses: string[] = [];
    function StatusRecorder() {
      const { status } = useAuth();
      seenStatuses.push(status);
      return null;
    }

    renderWithProvider(
      <>
        <StatusRecorder />
        <StatusProbe />
      </>,
    );

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );
    // Drop the initial transient 'loading' (first render before the probe
    // settles) — we only care about post-anon behavior.
    const indexOfAnon = seenStatuses.indexOf('anon');
    expect(indexOfAnon).toBeGreaterThanOrEqual(0);
    const postAnon = seenStatuses.slice(indexOfAnon);
    expect(postAnon).not.toContain('loading');
    seenStatuses.length = 0;

    await act(async () => {
      screen.getByText('do-login').click();
    });

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authed'),
    );
    // The post-login render sequence must go anon → authed without bouncing
    // through loading.
    expect(seenStatuses).not.toContain('loading');
    expect(seenStatuses).toContain('authed');
  });

  it('exposes the principal synchronously after login (no flicker through user=undefined)', async () => {
    // The override carries the user object so subscribers don't have to wait
    // for the next query observer re-render to read query.data. This pins
    // that login() seeds *both* status and user in one synchronous update.
    let probeShouldSucceed = false;
    getAuthMe.mockImplementation(() => {
      if (probeShouldSucceed) {
        return Promise.resolve({ username: 'alice', role: 'admin', grants: [] });
      }
      return Promise.reject(new Error('401'));
    });
    login.mockImplementation(() => {
      probeShouldSucceed = true;
      return Promise.resolve(undefined);
    });

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
    expect(screen.getByTestId('username')).toHaveTextContent('alice');
  });

  it('clears the authed override when the auth-expired bus fires', async () => {
    // A 401 on any other API call notifies the auth-expired bus. The
    // override must drop back to 'anon' so a later remount doesn't keep
    // showing the user as authed after the session got nuked.
    let probeShouldSucceed = false;
    getAuthMe.mockImplementation(() => {
      if (probeShouldSucceed) {
        return Promise.resolve({ username: 'alice', role: 'admin', grants: [] });
      }
      return Promise.reject(new Error('401'));
    });
    login.mockImplementation(() => {
      probeShouldSucceed = true;
      return Promise.resolve(undefined);
    });

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

    await act(async () => {
      notifyAuthExpired('http-401');
    });

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );
  });

  it('logout calls homerApi.logout, clears cache, and forces anon', async () => {
    getAuthMe.mockResolvedValue({ username: 'alice', role: 'admin', grants: [] });
    logout.mockResolvedValue(undefined);

    const { client } = renderWithProvider();
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authed'),
    );
    client.setQueryData(['scratch'], { keep: true });

    await act(async () => {
      screen.getByText('do-logout').click();
    });

    expect(logout).toHaveBeenCalled();
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('anon'),
    );
    expect(client.getQueryData(['scratch'])).toBeUndefined();
  });

  it('login returns rate-limited when the login endpoint responds 429', async () => {
    // The distinct outcome is what lets the login form show a "wait" message
    // instead of "invalid credentials" — mislabeling a 429 makes users retry
    // harder and drain the login limiter's shared bucket.
    getAuthMe.mockRejectedValue(new Error('401')); // mount lands anon
    login.mockRejectedValue(new HomerApiError('Too Many Requests', 429));

    let outcome: string | undefined;
    function OutcomeProbe() {
      const { login: doLogin } = useAuth();
      return (
        <button onClick={() => void doLogin('alice', 'pw').then((r) => { outcome = r; })}>
          do-login-capture
        </button>
      );
    }

    renderWithProvider(<OutcomeProbe />);
    await act(async () => {
      screen.getByText('do-login-capture').click();
    });
    await waitFor(() => expect(outcome).toBe('rate-limited'));
  });

  it('maps /me role and grants into the auth user', async () => {
    getAuthMe.mockResolvedValue({
      username: 'bob',
      role: null,
      grants: [{ agents: ['deploy-*'], actions: ['run', 'read'] }],
    });

    renderWithProvider();

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authed'),
    );
    expect(screen.getByTestId('username')).toHaveTextContent('bob');
    expect(screen.getByTestId('role')).toHaveTextContent('');
    expect(screen.getByTestId('grant-count')).toHaveTextContent('1');
  });
});
