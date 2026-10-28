import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, act, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// Capture the handlers passed to openStream so the test can drive
// log.chunk / log.end events synchronously.
const { openStreamMock } = vi.hoisted(() => ({
  openStreamMock: vi.fn(),
}));

vi.mock('./sse', () => ({
  openStream: (...args: unknown[]) => openStreamMock(...args),
}));

import { useProcessLogStream, useProcessStatusStream, type LogStreamState } from './streams';

describe('useProcessStatusStream', () => {
  // Animation frames run only when a test calls nextFrame, so a test decides which
  // events share a frame.
  const frames = new Map<number, FrameRequestCallback>();
  let lastFrameId = 0;
  const nextFrame = () =>
    act(() => {
      const due = [...frames.values()];
      frames.clear();
      due.forEach((cb) => cb(0));
    });

  beforeEach(() => {
    openStreamMock.mockReset();
    frames.clear();
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => {
      frames.set(++lastFrameId, cb);
      return lastFrameId;
    });
    vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id));
  });
  afterEach(() => vi.unstubAllGlobals());

  function StatusHarness() {
    useProcessStatusStream();
    return null;
  }

  it('invalidates the process artifact content when its status changes', () => {
    let captured: { onEvent: (n: string, d: unknown) => void } | undefined;
    openStreamMock.mockImplementation((_p: string, h: { onEvent: (n: string, d: unknown) => void }) => {
      captured = h;
      return () => {};
    });
    const qc = new QueryClient();
    const invalidate = vi.spyOn(qc, 'invalidateQueries');
    render(
      <QueryClientProvider client={qc}>
        <StatusHarness />
      </QueryClientProvider>
    );
    if (!captured) throw new Error('openStream not called');

    act(() => captured!.onEvent('process.status', { processId: 7 }));
    nextFrame();

    // The artifact snapshot for the finished process must be invalidated so a
    // dialog showing it leaves "streaming" mode (clears the stale Live badge).
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['artifact-content', 7] });
  });

  function mountWithSpy() {
    let captured: { onEvent: (n: string, d: unknown) => void } | undefined;
    openStreamMock.mockImplementation((_p: string, h: { onEvent: (n: string, d: unknown) => void }) => {
      captured = h;
      return () => {};
    });
    const qc = new QueryClient();
    const invalidate = vi.spyOn(qc, 'invalidateQueries');
    const { unmount } = render(
      <QueryClientProvider client={qc}>
        <StatusHarness />
      </QueryClientProvider>
    );
    if (!captured) throw new Error('openStream not called');
    const keys = () => invalidate.mock.calls.map((c) => c[0]?.queryKey);
    return {
      emit: (name: string, data: unknown) => act(() => captured!.onEvent(name, data)),
      keys,
      count: (key: unknown[]) => keys().filter((k) => JSON.stringify(k) === JSON.stringify(key)).length,
      unmount,
    };
  }

  it('coalesces process events within one frame into one invalidation per query', () => {
    const { emit, keys, count } = mountWithSpy();

    emit('process.status', { processId: 7, status: 'WORKING' });
    emit('process.cmd', { processId: 7 });
    emit('process.status', { processId: 8, status: 'WORKING' });
    // The burst is collected, not invalidated event by event.
    expect(keys()).toEqual([]);

    nextFrame();

    expect(count(['processes'])).toBe(1);
    expect(count(['process', 7])).toBe(1);
    expect(count(['process', 8])).toBe(1);
    expect(count(['artifact-content', 7])).toBe(1);
    expect(count(['artifact-content', 8])).toBe(1);
    expect(count(['flow-summary'])).toBe(1);
  });

  it('invalidates once per frame for events in separate frames, so the last event is never dropped', () => {
    const { emit, count } = mountWithSpy();

    emit('process.status', { processId: 7, status: 'WORKING' });
    nextFrame();
    emit('process.status', { processId: 7, status: 'FINISHED' });
    nextFrame();

    expect(count(['processes'])).toBe(2);
    expect(count(['process', 7])).toBe(2);
    expect(count(['artifact-content', 7])).toBe(2);
  });

  it('cancels a pending flush on unmount', () => {
    const { emit, keys, unmount } = mountWithSpy();

    emit('process.status', { processId: 7, status: 'WORKING' });
    unmount();
    nextFrame();

    expect(keys()).toEqual([]);
    expect(frames.size).toBe(0);
  });

  it('question events invalidate question + process views', () => {
    const { emit, keys } = mountWithSpy();

    emit('question.asked', { processId: 7, questionId: 'q-1', agentName: 'a' });

    expect(keys()).toContainEqual(['questions']);
    expect(keys()).toContainEqual(['processes']);
    expect(keys()).toContainEqual(['process', 7]);
  });

  it('question.expired invalidates question + process views', () => {
    const { emit, keys } = mountWithSpy();

    emit('question.expired', { processId: 7, questionId: 'q-1' });

    expect(keys()).toContainEqual(['questions']);
    expect(keys()).toContainEqual(['processes']);
    expect(keys()).toContainEqual(['process', 7]);
  });

  it.each(['message.queued', 'message.delivered', 'message.undelivered'])(
    '%s invalidates only that run\'s messages',
    (name) => {
      const { emit, keys } = mountWithSpy();

      emit(name, { processId: 7, messageId: 'm-1' });

      expect(keys()).toEqual([['run-messages', 7]]);
    },
  );

  it('a process.status event leaves question views alone (expiry has its own event)', () => {
    const { emit, keys } = mountWithSpy();

    emit('process.status', { processId: 7, status: 'KILLED' });
    nextFrame();

    expect(keys()).toContainEqual(['processes']);
    expect(keys()).not.toContainEqual(['questions']);
  });

  it('a process event refetches only the flow summaries it is about', () => {
    let captured: { onEvent: (n: string, d: unknown) => void } | undefined;
    openStreamMock.mockImplementation((_p: string, h: { onEvent: (n: string, d: unknown) => void }) => {
      captured = h;
      return () => {};
    });
    const qc = new QueryClient();
    const flow = (id: number) => ({ processes: [{ id }], total: 1, offset: 0, limit: 100 });
    qc.setQueryData(['flow-summary', 1], flow(11));
    qc.setQueryData(['flow-summary', 2], flow(21));
    qc.setQueryData(['flow-summary', 3], { processes: [], total: 0, offset: 0, limit: 100 });
    render(
      <QueryClientProvider client={qc}>
        <StatusHarness />
      </QueryClientProvider>
    );
    const invalidated = (root: number) => qc.getQueryState(['flow-summary', root])?.isInvalidated;

    // A run the summary already lists.
    act(() => captured!.onEvent('process.cmd', { processId: 21 }));
    nextFrame();
    expect([invalidated(1), invalidated(2), invalidated(3)]).toEqual([false, true, false]);

    // The root itself, whose summary lists only its descendants.
    act(() => captured!.onEvent('question.asked', { processId: 3, questionId: 'q-1', agentName: 'a' }));
    expect([invalidated(1), invalidated(3)]).toEqual([false, true]);
  });
});

interface CapturedHandlers {
  onEvent: (name: string, data: unknown) => void;
}

function Harness({
  enabled,
  onState,
}: {
  enabled: boolean;
  onState: (s: LogStreamState) => void;
}) {
  const s = useProcessLogStream(1, 0, 'out', enabled);
  onState(s);
  return null;
}

describe('useProcessLogStream', () => {
  beforeEach(() => {
    openStreamMock.mockReset();
  });

  function mountAndCapture() {
    let lastState: LogStreamState = { content: '', offset: 0, ended: false };
    let captured: CapturedHandlers | undefined;
    openStreamMock.mockImplementation((_path: string, handlers: CapturedHandlers) => {
      captured = handlers;
      return () => {};
    });
    render(<Harness enabled onState={(s) => { lastState = s; }} />);
    if (!captured) throw new Error('openStream was not called');
    return {
      emit: (name: string, data: unknown) => act(() => captured!.onEvent(name, data)),
      read: () => lastState,
    };
  }

  it('appends a strictly-continuing chunk', async () => {
    const { emit, read } = mountAndCapture();
    emit('log.chunk', { offset: 5, content: 'hello' });
    await waitFor(() => expect(read().offset).toBe(5));
    expect(read().content).toBe('hello');
    emit('log.chunk', { offset: 11, content: ' world' });
    await waitFor(() => expect(read().offset).toBe(11));
    expect(read().content).toBe('hello world');
  });

  it('drops a chunk whose offset is at-or-behind our buffer (reconnect echo)', async () => {
    const { emit, read } = mountAndCapture();
    emit('log.chunk', { offset: 5, content: 'hello' });
    await waitFor(() => expect(read().offset).toBe(5));
    // Reconnect re-emits bytes we already have. Backend sends offset=5,
    // content='hello' from byte 0 again. Must not duplicate.
    emit('log.chunk', { offset: 5, content: 'hello' });
    expect(read().content).toBe('hello');
    expect(read().offset).toBe(5);
  });

  it('after a reconnect, when the file has grown, replaces buffer with the new snapshot', async () => {
    const { emit, read } = mountAndCapture();
    emit('log.chunk', { offset: 5, content: 'hello' });
    await waitFor(() => expect(read().offset).toBe(5));
    // Reconnect: file is now 11 bytes, backend sends from 0.
    emit('log.chunk', { offset: 11, content: 'hello world' });
    await waitFor(() => expect(read().offset).toBe(11));
    // Replaced, not appended. If we'd appended we'd see 'hellohello world' (16 chars).
    expect(read().content).toBe('hello world');
  });

  it('handles multi-byte UTF-8 — byte length, not char length, drives continuation', async () => {
    // "café" is 5 bytes UTF-8 (c=1, a=1, f=1, é=2). Buffer offset reflects bytes.
    const { emit, read } = mountAndCapture();
    emit('log.chunk', { offset: 5, content: 'café' });
    await waitFor(() => expect(read().offset).toBe(5));
    expect(read().content).toBe('café');
    // Append a strict continuation: 6 more bytes (" road" — 5 chars, 5 bytes).
    emit('log.chunk', { offset: 10, content: ' road' });
    await waitFor(() => expect(read().offset).toBe(10));
    expect(read().content).toBe('café road');
  });

  it('marks ended on log.end', async () => {
    const { emit, read } = mountAndCapture();
    expect(read().ended).toBe(false);
    emit('log.end', {});
    await waitFor(() => expect(read().ended).toBe(true));
  });
});
