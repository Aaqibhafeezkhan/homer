import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock @microsoft/fetch-event-source BEFORE importing sse.ts so the
// mock applies to the module under test. Use vi.hoisted so the mock
// functions are defined before vi.mock's hoisted call site.
const { fetchEventSourceMock, notifyAuthExpiredMock } = vi.hoisted(() => ({
  fetchEventSourceMock: vi.fn(),
  notifyAuthExpiredMock: vi.fn(),
}));

vi.mock('@microsoft/fetch-event-source', () => ({
  fetchEventSource: fetchEventSourceMock,
  EventStreamContentType: 'text/event-stream',
}));

vi.mock('@/lib/config/app-config', () => ({
  appConfig: {
    api: { baseUrl: 'http://test' },
    auth: { enabled: true, tokenKey: 'tok' },
  },
}));

vi.mock('./client', () => ({
  homerApi: { authorizationHeader: () => ({ Authorization: 'Bearer fake-bearer' }) },
  CSRF_HEADER: 'X-Homer-CSRF',
}));

vi.mock('@/lib/auth/auth-expired-bus', () => ({
  notifyAuthExpired: (...args: unknown[]) => notifyAuthExpiredMock(...args),
}));

import { openStream } from './sse';

// Minimal shape of the fetchEventSource opts our SUT installs. We
// only assert against the callbacks it wires up.
interface CapturedOpts {
  onopen: (res: Response) => Promise<void>;
  onerror: (err: unknown) => number | void;
  onmessage: (msg: { event: string; data: string; id: string }) => void;
  headers: Record<string, string>;
}

describe('openStream', () => {
  beforeEach(() => {
    fetchEventSourceMock.mockReset();
    notifyAuthExpiredMock.mockReset();
  });

  it('returns exponential backoff for transient errors, capped at 30000ms', async () => {
    let captured: CapturedOpts | undefined;
    fetchEventSourceMock.mockImplementation((_url, opts) => {
      captured = opts as CapturedOpts;
      return Promise.resolve();
    });
    openStream('/api/v1/stream/processes', { onEvent: () => {} });

    const transient = new Error('boom');
    // attempt counter is internal; calling onerror N times should
    // yield 1000, 2000, 4000, 8000, 16000, 30000, 30000, …
    const delays = Array.from({ length: 7 }, () => captured!.onerror(transient));
    expect(delays).toEqual([1000, 2000, 4000, 8000, 16000, 30000, 30000]);
  });

  it('throws (no retry) after max attempts', async () => {
    let captured: CapturedOpts | undefined;
    fetchEventSourceMock.mockImplementation((_url, opts) => {
      captured = opts as CapturedOpts;
      return Promise.resolve();
    });
    openStream('/api/v1/stream/processes', { onEvent: () => {} });
    const transient = new Error('boom');
    // First 10 calls return a delay; 11th must throw.
    for (let i = 0; i < 10; i++) captured!.onerror(transient);
    expect(() => captured!.onerror(transient)).toThrow();
  });

  it('fires notifyAuthExpired and throws on 401 in onopen', async () => {
    let captured: CapturedOpts | undefined;
    fetchEventSourceMock.mockImplementation((_url, opts) => {
      captured = opts as CapturedOpts;
      return Promise.resolve();
    });
    openStream('/api/v1/stream/processes', { onEvent: () => {} });

    const res401 = new Response(null, { status: 401, statusText: 'Unauthorized' });
    await expect(captured!.onopen(res401)).rejects.toThrow();
    expect(notifyAuthExpiredMock).toHaveBeenCalledWith('sse-401');
  });

  it('sends the X-Homer-CSRF header so cookie-mode SSE passes the guard (ADR-0016)', () => {
    let captured: CapturedOpts | undefined;
    fetchEventSourceMock.mockImplementation((_url, opts) => {
      captured = opts as CapturedOpts;
      return Promise.resolve();
    });
    openStream('/api/v1/stream/processes', { onEvent: () => {} });
    expect(captured!.headers['X-Homer-CSRF']).toBe('1');
  });

  it("carries the client's Authorization header", () => {
    let captured: CapturedOpts | undefined;
    fetchEventSourceMock.mockImplementation((_url, opts) => {
      captured = opts as CapturedOpts;
      return Promise.resolve();
    });
    openStream('/api/v1/stream/processes', { onEvent: () => {} });
    expect(captured!.headers['Authorization']).toBe('Bearer fake-bearer');
  });

  it('opens normally for valid event-stream', async () => {
    const onOpen = vi.fn();
    let captured: CapturedOpts | undefined;
    fetchEventSourceMock.mockImplementation((_url, opts) => {
      captured = opts as CapturedOpts;
      return Promise.resolve();
    });
    openStream('/api/v1/stream/processes', { onEvent: () => {}, onOpen });

    const okRes = new Response(null, {
      status: 200,
      headers: { 'content-type': 'text/event-stream' },
    });
    await captured!.onopen(okRes);
    expect(onOpen).toHaveBeenCalled();
    expect(notifyAuthExpiredMock).not.toHaveBeenCalled();
  });

  it('parses JSON event data and falls back to the raw string', () => {
    const onEvent = vi.fn();
    let captured: CapturedOpts | undefined;
    fetchEventSourceMock.mockImplementation((_url, opts) => {
      captured = opts as CapturedOpts;
      return Promise.resolve();
    });
    openStream('/api/v1/stream/processes', { onEvent });

    captured!.onmessage({ event: 'process.status', data: '{"id":3}', id: '17' });
    captured!.onmessage({ event: '', data: 'not json', id: '' });
    expect(onEvent).toHaveBeenNthCalledWith(1, 'process.status', { id: 3 }, '17');
    expect(onEvent).toHaveBeenNthCalledWith(2, 'message', 'not json', undefined);
  });
});
