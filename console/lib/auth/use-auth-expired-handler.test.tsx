import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useAuthExpiredHandler } from './use-auth-expired-handler';
import { notifyAuthExpired } from './auth-expired-bus';
import {
  DEFAULT_INSTANCE_ID,
  resetInstancesStoreForTests,
  tokenKeyFor,
} from '@/lib/instances/store';

const ACTIVE_TOKEN_KEY = tokenKeyFor(DEFAULT_INSTANCE_ID);

const replace = vi.fn();
const usePathname = vi.fn(() => '/processes');

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace }),
  usePathname: () => usePathname(),
}));

function HarnessProbe({
  onRef,
  hadSession = true,
}: {
  onRef?: (ref: ReturnType<typeof useAuthExpiredHandler>) => void;
  hadSession?: boolean;
}) {
  const handled = useAuthExpiredHandler(hadSession);
  onRef?.(handled);
  return null;
}

function renderHarness(client: QueryClient = new QueryClient(), hadSession = true) {
  return render(
    <QueryClientProvider client={client}>
      <HarnessProbe hadSession={hadSession} />
    </QueryClientProvider>,
  );
}

describe('useAuthExpiredHandler', () => {
  beforeEach(() => {
    replace.mockReset();
    usePathname.mockReset().mockReturnValue('/processes');
    resetInstancesStoreForTests();
    localStorage.setItem(ACTIVE_TOKEN_KEY, 'stale-bearer');
  });

  it('redirects to /login with the requested path and the expired marker', () => {
    renderHarness();
    act(() => notifyAuthExpired('http-401'));
    expect(replace).toHaveBeenCalledWith('/login?next=%2Fprocesses&expired=1');
    expect(localStorage.getItem(ACTIVE_TOKEN_KEY)).toBeNull();
  });

  it('drops the expired marker when no session existed', () => {
    renderHarness(new QueryClient(), false);
    act(() => notifyAuthExpired('sse-401'));
    expect(replace).toHaveBeenCalledWith('/login?next=%2Fprocesses');
  });

  it('carries the path the reader was on', () => {
    usePathname.mockReturnValue('/processes/1');
    renderHarness();
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
