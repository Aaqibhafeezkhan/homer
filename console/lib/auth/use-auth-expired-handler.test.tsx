import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useAuthExpiredHandler } from './use-auth-expired-handler';
import type { AuthStatus } from './auth-context';
import { notifyAuthExpired } from './auth-expired-bus';
import {
  DEFAULT_INSTANCE_ID,
  resetInstancesStoreForTests,
  tokenKeyFor,
  upsertInstance,
} from '@/lib/instances/store';

const ACTIVE_TOKEN_KEY = tokenKeyFor(DEFAULT_INSTANCE_ID);

const replace = vi.fn();
const usePathname = vi.fn(() => '/processes');

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace }),
  usePathname: () => usePathname(),
}));

/** The active instance's mode decides whether a stored token counts as a
 * credential the backend could have rejected; the test config defaults to cookie. */
function activateBearerInstance() {
  upsertInstance({
    id: DEFAULT_INSTANCE_ID,
    label: 'Default',
    baseUrl: 'http://localhost:8080',
    mode: 'bearer',
  });
}

function HarnessProbe({
  onRef,
  status = 'loading',
}: {
  onRef?: (ref: ReturnType<typeof useAuthExpiredHandler>) => void;
  status?: AuthStatus;
}) {
  const handled = useAuthExpiredHandler(status);
  onRef?.(handled);
  return null;
}

function renderHarness(client: QueryClient = new QueryClient(), status: AuthStatus = 'loading') {
  return render(
    <QueryClientProvider client={client}>
      <HarnessProbe status={status} />
    </QueryClientProvider>,
  );
}

describe('useAuthExpiredHandler', () => {
  beforeEach(() => {
    replace.mockReset();
    usePathname.mockReset().mockReturnValue('/processes');
    resetInstancesStoreForTests();
  });

  it('redirects to /login with the requested path and the expired marker', () => {
    activateBearerInstance();
    localStorage.setItem(ACTIVE_TOKEN_KEY, 'stale-bearer');
    renderHarness();
    act(() => notifyAuthExpired('http-401'));
    expect(replace).toHaveBeenCalledWith('/login?next=%2Fprocesses&expired=1');
    expect(localStorage.getItem(ACTIVE_TOKEN_KEY)).toBeNull();
  });

  it('marks an expiry for a bearer token the backend rejected on page load', () => {
    // The reader returns with a token that has already expired: no render of
    // this mount ever said 'authed', so the stored credential is the only
    // evidence that there was a session to expire.
    activateBearerInstance();
    localStorage.setItem(ACTIVE_TOKEN_KEY, 'stale-bearer');
    renderHarness(new QueryClient(), 'loading');
    act(() => notifyAuthExpired('http-401'));
    expect(replace).toHaveBeenCalledWith('/login?next=%2Fprocesses&expired=1');
  });

  it('drops the expired marker when the reader had no credential at all', () => {
    activateBearerInstance();
    renderHarness(new QueryClient(), 'loading');
    act(() => notifyAuthExpired('sse-401'));
    expect(replace).toHaveBeenCalledWith('/login?next=%2Fprocesses');
  });

  it('ignores a stored bearer token while the active instance is in cookie mode', () => {
    // The default instance is cookie-mode here, so a leftover token is not the
    // credential this request carried.
    localStorage.setItem(ACTIVE_TOKEN_KEY, 'stale-bearer');
    renderHarness(new QueryClient(), 'loading');
    act(() => notifyAuthExpired('http-401'));
    expect(replace).toHaveBeenCalledWith('/login?next=%2Fprocesses');
  });

  it('marks an expiry for a cookie session that expired mid-use', () => {
    // No client-readable credential in cookie mode: having been 'authed' is
    // the only evidence of the session that just ended.
    renderHarness(new QueryClient(), 'authed');
    act(() => notifyAuthExpired('http-401'));
    expect(replace).toHaveBeenCalledWith('/login?next=%2Fprocesses&expired=1');
  });

  it('keeps the expired marker after the auth context has flipped to anon', () => {
    const client = new QueryClient();
    const { rerender } = render(
      <QueryClientProvider client={client}>
        <HarnessProbe status="authed" />
      </QueryClientProvider>,
    );
    // AuthProvider's own listener flips the override to 'anon' on the same
    // event, so a status read at event time would already say "no session".
    rerender(
      <QueryClientProvider client={client}>
        <HarnessProbe status="anon" />
      </QueryClientProvider>,
    );
    act(() => notifyAuthExpired('http-401'));
    expect(replace).toHaveBeenCalledWith('/login?next=%2Fprocesses&expired=1');
  });

  it('carries the path the reader was on', () => {
    usePathname.mockReturnValue('/processes/1');
    renderHarness(new QueryClient(), 'authed');
    act(() => notifyAuthExpired('http-401'));
    expect(replace).toHaveBeenCalledWith('/login?next=%2Fprocesses%2F1&expired=1');
  });

  it('no-ops when already on /login', () => {
    usePathname.mockReturnValue('/login');
    renderHarness();
    act(() => notifyAuthExpired('http-401'));
    expect(replace).not.toHaveBeenCalled();
  });

  it('clears the React Query cache', () => {
    const client = new QueryClient();
    client.setQueryData(['probe'], { stale: true });
    renderHarness(client);
    act(() => notifyAuthExpired('http-401'));
    expect(client.getQueryData(['probe'])).toBeUndefined();
  });

  it('runs the recovery only once for repeated expiry events', () => {
    const client = new QueryClient();
    const clearSpy = vi.spyOn(client, 'clear');
    renderHarness(client);
    // Simulates the production feedback loop: queryClient.clear() refetches
    // still-mounted queries, whose 401s re-fire the bus before the /login
    // navigation commits (pathname still the dashboard route).
    act(() => {
      notifyAuthExpired('http-401');
      notifyAuthExpired('http-401');
      notifyAuthExpired('sse-401');
    });
    expect(replace).toHaveBeenCalledTimes(1);
    expect(clearSpy).toHaveBeenCalledTimes(1);
  });

  it('returns a ref that starts false and flips true once the expiry is handled', () => {
    let handledRef: ReturnType<typeof useAuthExpiredHandler> | undefined;
    render(
      <QueryClientProvider client={new QueryClient()}>
        <HarnessProbe
          onRef={(ref) => {
            handledRef = ref;
          }}
        />
      </QueryClientProvider>,
    );
    expect(handledRef?.current).toBe(false);
    act(() => notifyAuthExpired('http-401'));
    expect(handledRef?.current).toBe(true);
  });

  it('leaves the returned ref false when already on /login (latch not consumed)', () => {
    usePathname.mockReturnValue('/login');
    let handledRef: ReturnType<typeof useAuthExpiredHandler> | undefined;
    render(
      <QueryClientProvider client={new QueryClient()}>
        <HarnessProbe
          onRef={(ref) => {
            handledRef = ref;
          }}
        />
      </QueryClientProvider>,
    );
    act(() => notifyAuthExpired('http-401'));
    expect(handledRef?.current).toBe(false);
  });

  it('resets the latch on remount', () => {
    const { unmount } = renderHarness();
    act(() => notifyAuthExpired('http-401'));
    expect(replace).toHaveBeenCalledTimes(1);

    unmount();
    renderHarness();
    act(() => notifyAuthExpired('http-401'));
    expect(replace).toHaveBeenCalledTimes(2);
  });
});
