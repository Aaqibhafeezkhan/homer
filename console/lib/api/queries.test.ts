import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import React from 'react';
import {
  useAgents,
  useSaveAgentFile,
  useDeleteAgentFile,
  useCreateAgent,
  useFireAgentCron,
  useDebugCommand,
  useCosts,
  useMonthlyCosts,
  useTotalCosts,
  useCostsByTag,
  useAgentFiles,
  useAgentFile,
  useQuestions,
  useOpenQuestionsCount,
  useAnswerQuestion,
  useCancelDispatch,
  useContinuations,
  usePendingContinuationsCount,
  useCancelContinuation,
  useProcessLangGraph,
  useProcesses,
  useFlowSummary,
  useProcess,
  useResumeProcess,
  useRunners,
  useRunnerQueue,
  useArtifactContent,
  queryKeys,
} from './queries';
import type { ProcessInfo, ProcessStatus, RunnersStatusResponse } from '@/types/homer';

// Hoisted so the vi.mock factory (also hoisted) can reference it and tests can
// throw real instances that survive `instanceof HomerApiError` checks.
const { HomerApiError } = vi.hoisted(() => {
  class HomerApiError extends Error {
    constructor(
      message: string,
      public status?: number,
      public statusText?: string,
      public body?: unknown,
    ) {
      super(message);
      this.name = 'HomerApiError';
    }
  }
  return { HomerApiError };
});

vi.mock('./client', () => ({
  homerApi: {
    getAgents: vi.fn(),
    writeAgentFile: vi.fn(),
    deleteAgentFile: vi.fn(),
    createAgent: vi.fn(),
    fireAgentCron: vi.fn(),
    debugAgentCommand: vi.fn(),
    getCosts: vi.fn(),
    getMonthlyCosts: vi.fn(),
    getTotalCosts: vi.fn(),
    getCostsByTag: vi.fn(),
    listAgentFiles: vi.fn(),
    readAgentFile: vi.fn(),
    getQuestions: vi.fn(),
    answerQuestion: vi.fn(),
    cancelDispatch: vi.fn(),
    getContinuations: vi.fn(),
    cancelContinuation: vi.fn(),
    getProcessLangGraph: vi.fn(),
    getProcesses: vi.fn(),
    getProcess: vi.fn(),
    resumeProcess: vi.fn(),
    getRunners: vi.fn(),
    getRunnerQueue: vi.fn(),
    fetchArtifactContent: vi.fn(),
  },
  HomerApiError,
}));

import { homerApi } from './client';

function wrapper(client: QueryClient) {
  const Wrapper = ({ children }: { children: React.ReactNode }) =>
    React.createElement(QueryClientProvider, { client }, children);
  Wrapper.displayName = 'QueryClientWrapper';
  return Wrapper;
}

describe('useAgents', () => {
  let client: QueryClient;

  beforeEach(() => {
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    vi.clearAllMocks();
  });

  it('returns agents sorted alphabetically by name', async () => {
    const agent = (name: string) => ({
      name,
      inputs: null,
      queryParams: null,
      scriptCount: 1,
    });
    vi.mocked(homerApi.getAgents).mockResolvedValue({
      agents: [agent('zeta'), agent('alpha'), agent('Mike')],
    });

    const { result } = renderHook(() => useAgents(), { wrapper: wrapper(client) });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.agents.map((a) => a.name)).toEqual([
      'alpha',
      'Mike',
      'zeta',
    ]);
  });
});

describe('agent-file mutation hooks', () => {
  let client: QueryClient;

  beforeEach(() => {
    client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
    vi.clearAllMocks();
  });

  it('useSaveAgentFile invalidates agents + agent-files + agent-file keys on success', async () => {
    vi.mocked(homerApi.writeAgentFile).mockResolvedValue({
      path: 'agent.yaml',
      ok: true,
    });
    const invalidate = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useSaveAgentFile('my-agent'), {
      wrapper: wrapper(client),
    });

    await result.current.mutateAsync({ path: 'agent.yaml', content: 'name: x' });

    expect(homerApi.writeAgentFile).toHaveBeenCalledWith(
      'my-agent',
      'agent.yaml',
      'name: x',
    );
    const keys = invalidate.mock.calls.map((c) => c[0]?.queryKey);
    expect(keys).toContainEqual(queryKeys.agents());
    expect(keys).toContainEqual(queryKeys.agentFiles('my-agent'));
    expect(keys).toContainEqual(queryKeys.agentFile('my-agent', 'agent.yaml'));
  });

  it('useDeleteAgentFile invalidates agents + files list on success', async () => {
    // deleteAgentFile resolves to void; the test only asserts cache invalidation.
    vi.mocked(homerApi.deleteAgentFile).mockResolvedValue(undefined);
    const invalidate = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useDeleteAgentFile('my-agent'), {
      wrapper: wrapper(client),
    });
    await result.current.mutateAsync('other.txt');

    const keys = invalidate.mock.calls.map((c) => c[0]?.queryKey);
    expect(keys).toContainEqual(queryKeys.agents());
    expect(keys).toContainEqual(queryKeys.agentFiles('my-agent'));
  });

  it('useCreateAgent invalidates the agents list', async () => {
    vi.mocked(homerApi.createAgent).mockResolvedValue({ name: 'new' });
    const invalidate = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useCreateAgent(), {
      wrapper: wrapper(client),
    });
    await result.current.mutateAsync('new');

    const keys = invalidate.mock.calls.map((c) => c[0]?.queryKey);
    expect(keys).toContainEqual(queryKeys.agents());
  });

  it('useFireAgentCron fires the cron path and invalidates agents + processes', async () => {
    vi.mocked(homerApi.fireAgentCron).mockResolvedValue(undefined);
    const invalidate = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useFireAgentCron(), {
      wrapper: wrapper(client),
    });
    await result.current.mutateAsync('nightly');

    expect(homerApi.fireAgentCron).toHaveBeenCalledWith('nightly');
    const keys = invalidate.mock.calls.map((c) => c[0]?.queryKey);
    expect(keys).toContainEqual(queryKeys.agents());
    expect(keys).toContainEqual(['processes']);
  });

  it('useDebugCommand invalidates processes on success', async () => {
    vi.mocked(homerApi.debugAgentCommand).mockResolvedValue({ processId: 99 });
    const invalidate = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useDebugCommand('my-agent'), {
      wrapper: wrapper(client),
    });
    await result.current.mutateAsync({
      commandIndex: 0,
      env: { X: '1' },
      queryParams: {},
    });

    await waitFor(() => {
      expect(homerApi.debugAgentCommand).toHaveBeenCalledWith('my-agent', {
        commandIndex: 0,
        env: { X: '1' },
        queryParams: {},
      });
    });
    const keys = invalidate.mock.calls.map((c) => c[0]?.queryKey);
    expect(keys).toContainEqual(['processes']);
  });
});

describe('useAgentFiles', () => {
  let client: QueryClient;

  beforeEach(() => {
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    vi.clearAllMocks();
  });

  it('serves the cached file list on remount instead of refetching (audit P2 #25)', async () => {
    vi.mocked(homerApi.listAgentFiles).mockResolvedValue({ entries: [] });

    const first = renderHook(() => useAgentFiles('my-agent'), {
      wrapper: wrapper(client),
    });
    await waitFor(() => expect(first.result.current.isSuccess).toBe(true));
    first.unmount();

    // Remount well within staleTime — agent file lists rarely change, so this
    // must not trigger a fresh fetch on every navigation into the edit page.
    const second = renderHook(() => useAgentFiles('my-agent'), {
      wrapper: wrapper(client),
    });
    await waitFor(() => expect(second.result.current.isSuccess).toBe(true));

    expect(homerApi.listAgentFiles).toHaveBeenCalledTimes(1);
  });
});

describe('useAgentFile', () => {
  let client: QueryClient;

  beforeEach(() => {
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    vi.clearAllMocks();
  });

  it('reads the selected file under its agentFile key', async () => {
    vi.mocked(homerApi.readAgentFile).mockResolvedValue({ path: 'a.sh', content: 'x' });
    const { result } = renderHook(() => useAgentFile('my-agent', 'a.sh'), {
      wrapper: wrapper(client),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(homerApi.readAgentFile).toHaveBeenCalledWith('my-agent', 'a.sh');
    expect(client.getQueryData(queryKeys.agentFile('my-agent', 'a.sh'))).toEqual({
      path: 'a.sh',
      content: 'x',
    });
  });

  it('stays idle without a path, an agent name, or when disabled', () => {
    renderHook(() => useAgentFile('my-agent', null), { wrapper: wrapper(client) });
    renderHook(() => useAgentFile('', 'a.sh'), { wrapper: wrapper(client) });
    renderHook(() => useAgentFile('my-agent', 'a.sh', false), { wrapper: wrapper(client) });
    expect(homerApi.readAgentFile).not.toHaveBeenCalled();
    expect(client.getQueryCache().find({ queryKey: queryKeys.agentFile('my-agent', '') })).toBeDefined();
  });
});

describe('question hooks', () => {
  let client: QueryClient;

  const openQuestion = {
    id: 'q-1',
    processId: 7,
    agentName: 'deploy',
    text: 'Proceed?',
    options: ['yes', 'no'],
    status: 'OPEN' as const,
    answer: null,
    createdAt: 1_700_000_000,
    answeredAt: null,
  };

  beforeEach(() => {
    client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    vi.clearAllMocks();
  });

  it('useQuestions forwards filters to the client', async () => {
    vi.mocked(homerApi.getQuestions).mockResolvedValue({ questions: [openQuestion], total: 1, offset: 0, limit: 100 });

    const { result } = renderHook(
      () => useQuestions({ status: 'OPEN', processId: 7 }),
      { wrapper: wrapper(client) },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(homerApi.getQuestions).toHaveBeenCalledWith({ status: 'OPEN', processId: 7 });
    expect(result.current.data?.questions).toEqual([openQuestion]);
  });

  it('useOpenQuestionsCount selects the total, not the page length', async () => {
    vi.mocked(homerApi.getQuestions).mockResolvedValue({
      questions: [openQuestion, { ...openQuestion, id: 'q-2' }],
      total: 140,
      offset: 0,
      limit: 100,
    });

    const { result } = renderHook(() => useOpenQuestionsCount(), {
      wrapper: wrapper(client),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(homerApi.getQuestions).toHaveBeenCalledWith({ status: 'OPEN' });
    expect(result.current.data).toBe(140);
  });

  it('useAnswerQuestion invalidates questions + process views on settle', async () => {
    vi.mocked(homerApi.answerQuestion).mockResolvedValue({
      ...openQuestion,
      status: 'ANSWERED',
      answer: 'yes',
      answeredAt: 1_700_000_100,
    });
    const invalidate = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useAnswerQuestion(), {
      wrapper: wrapper(client),
    });
    await result.current.mutateAsync({ id: 'q-1', answer: 'yes' });

    expect(homerApi.answerQuestion).toHaveBeenCalledWith('q-1', 'yes');
    const keys = invalidate.mock.calls.map((c) => c[0]?.queryKey);
    expect(keys).toContainEqual(['questions']);
    expect(keys).toContainEqual(['processes']);
    expect(keys).toContainEqual(['process']);
  });

  it('useAnswerQuestion still refetches when the answer conflicts (409 stale view)', async () => {
    vi.mocked(homerApi.answerQuestion).mockRejectedValue(new Error('409'));
    const invalidate = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useAnswerQuestion(), {
      wrapper: wrapper(client),
    });
    await expect(
      result.current.mutateAsync({ id: 'q-1', answer: 'yes' }),
    ).rejects.toThrow('409');

    const keys = invalidate.mock.calls.map((c) => c[0]?.queryKey);
    expect(keys).toContainEqual(['questions']);
  });

  it('useCancelDispatch invalidates questions + process views on settle', async () => {
    vi.mocked(homerApi.cancelDispatch).mockResolvedValue(undefined);
    const invalidate = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useCancelDispatch(), {
      wrapper: wrapper(client),
    });
    await result.current.mutateAsync(3);

    expect(homerApi.cancelDispatch).toHaveBeenCalledWith(3);
    const keys = invalidate.mock.calls.map((c) => c[0]?.queryKey);
    expect(keys).toContainEqual(['questions']);
    expect(keys).toContainEqual(['processes']);
    expect(keys).toContainEqual(['process']);
  });

  it('useCancelDispatch still refetches when the dispatch conflicts (409 stale view)', async () => {
    vi.mocked(homerApi.cancelDispatch).mockRejectedValue(new Error('409'));
    const invalidate = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useCancelDispatch(), {
      wrapper: wrapper(client),
    });
    await expect(result.current.mutateAsync(3)).rejects.toThrow('409');

    const keys = invalidate.mock.calls.map((c) => c[0]?.queryKey);
    expect(keys).toContainEqual(['questions']);
  });
});

describe('useCosts', () => {
  let client: QueryClient;

  beforeEach(() => {
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    vi.clearAllMocks();
  });

  it('calls homerApi.getCosts and exposes the snapshot under queryKeys.costs()', async () => {
    const snapshot = {
      globalUsdLast60s: 0.42,
      globalCapUsdPerMin: 5,
      perAgentUsdLast24h: { reviewer: 0.1 },
      perAgentCapUsdPerDay: { reviewer: 10 },
      snapshotAtSec: 1_700_000_000,
      costTrackedTagKeys: [],
    };
    vi.mocked(homerApi.getCosts).mockResolvedValue(snapshot);

    const { result } = renderHook(() => useCosts(), { wrapper: wrapper(client) });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(homerApi.getCosts).toHaveBeenCalledTimes(1);
    expect(result.current.data).toEqual(snapshot);
    expect(client.getQueryData(queryKeys.costs())).toEqual(snapshot);
  });
});

describe('useMonthlyCosts', () => {
  let client: QueryClient;

  beforeEach(() => {
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    vi.clearAllMocks();
  });

  it('fetches the current month by default and caches under queryKeys.costsMonthly()', async () => {
    const monthly = {
      month: '2026-07',
      perAgentUsd: { reviewer: 2.0 },
      totalUsd: 2.0,
      availableMonths: ['2026-07', '2026-06'],
    };
    vi.mocked(homerApi.getMonthlyCosts).mockResolvedValue(monthly);

    const { result } = renderHook(() => useMonthlyCosts(), { wrapper: wrapper(client) });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(homerApi.getMonthlyCosts).toHaveBeenCalledWith(undefined);
    expect(result.current.data).toEqual(monthly);
    expect(client.getQueryData(queryKeys.costsMonthly())).toEqual(monthly);
  });

  it('passes an explicit month through to the client and the query key', async () => {
    const monthly = {
      month: '2026-06',
      perAgentUsd: { reviewer: 1.0 },
      totalUsd: 1.0,
      availableMonths: ['2026-07', '2026-06'],
    };
    vi.mocked(homerApi.getMonthlyCosts).mockResolvedValue(monthly);

    const { result } = renderHook(() => useMonthlyCosts('2026-06'), { wrapper: wrapper(client) });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(homerApi.getMonthlyCosts).toHaveBeenCalledWith('2026-06');
    expect(client.getQueryData(queryKeys.costsMonthly('2026-06'))).toEqual(monthly);
  });
});

describe('useTotalCosts', () => {
  let client: QueryClient;

  beforeEach(() => {
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    vi.clearAllMocks();
  });

  it('fetches all-time totals and caches under queryKeys.costsTotals()', async () => {
    const totals = { perAgentUsd: { reviewer: 3.0, planner: 0.5 }, totalUsd: 3.5 };
    vi.mocked(homerApi.getTotalCosts).mockResolvedValue(totals);

    const { result } = renderHook(() => useTotalCosts(), { wrapper: wrapper(client) });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(homerApi.getTotalCosts).toHaveBeenCalledTimes(1);
    expect(result.current.data).toEqual(totals);
    expect(client.getQueryData(queryKeys.costsTotals())).toEqual(totals);
  });
});

describe('useCostsByTag', () => {
  let client: QueryClient;

  beforeEach(() => {
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    vi.clearAllMocks();
  });

  it('does not fetch until a key is provided', () => {
    const { result } = renderHook(() => useCostsByTag(undefined), { wrapper: wrapper(client) });

    expect(homerApi.getCostsByTag).not.toHaveBeenCalled();
    expect(result.current.fetchStatus).toBe('idle');
  });

  it('fetches the breakdown for the given key and caches under queryKeys.costsByTag(key)', async () => {
    const breakdown = { key: 'ticket_id', valuesUsd: { 'T-123': 1.5 }, totalUsd: 1.5, truncated: false };
    vi.mocked(homerApi.getCostsByTag).mockResolvedValue(breakdown);

    const { result } = renderHook(() => useCostsByTag('ticket_id'), { wrapper: wrapper(client) });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(homerApi.getCostsByTag).toHaveBeenCalledWith('ticket_id');
    expect(result.current.data).toEqual(breakdown);
    expect(client.getQueryData(queryKeys.costsByTag('ticket_id'))).toEqual(breakdown);
  });
});

describe('continuation hooks', () => {
  let client: QueryClient;

  const pendingContinuation = {
    id: 5,
    watchedProcessId: 12,
    status: 'PENDING' as const,
    agentName: 'follow-up',
    resumeParam: 'RESULT',
    params: {},
    originProcessId: 7,
    originAgentName: 'starter',
    expiresAt: null,
    createdAt: 1_700_000_000,
    firedAt: null,
    firedProcessId: null,
    error: null,
  };

  beforeEach(() => {
    client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    vi.clearAllMocks();
  });

  it('useContinuations forwards filters to the client', async () => {
    vi.mocked(homerApi.getContinuations).mockResolvedValue({
      continuations: [pendingContinuation],
      total: 1,
      offset: 0,
      limit: 100,
    });

    const { result } = renderHook(
      () => useContinuations({ status: 'PENDING', agentName: 'follow-up' }),
      { wrapper: wrapper(client) },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(homerApi.getContinuations).toHaveBeenCalledWith({
      status: 'PENDING',
      agentName: 'follow-up',
    });
    expect(result.current.data?.continuations).toEqual([pendingContinuation]);
  });

  it('usePendingContinuationsCount queries PENDING and selects total, not the page length', async () => {
    vi.mocked(homerApi.getContinuations).mockResolvedValue({
      // total counts every matching row; the page holds only one of them.
      continuations: [pendingContinuation],
      total: 42,
      offset: 0,
      limit: 100,
    });

    const { result } = renderHook(() => usePendingContinuationsCount(), {
      wrapper: wrapper(client),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(homerApi.getContinuations).toHaveBeenCalledWith({ status: 'PENDING' });
    expect(result.current.data).toBe(42);
    expect(client.getQueryData(queryKeys.continuationsPendingCount())).toEqual({
      continuations: [pendingContinuation],
      total: 42,
      offset: 0,
      limit: 100,
    });
  });

  it('useCancelContinuation invalidates continuations + process views on settle', async () => {
    vi.mocked(homerApi.cancelContinuation).mockResolvedValue(undefined);
    const invalidate = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useCancelContinuation(), {
      wrapper: wrapper(client),
    });
    await result.current.mutateAsync(5);

    expect(homerApi.cancelContinuation).toHaveBeenCalledWith(5);
    const keys = invalidate.mock.calls.map((c) => c[0]?.queryKey);
    expect(keys).toContainEqual(['continuations']);
    expect(keys).toContainEqual(['processes']);
    expect(keys).toContainEqual(['process']);
  });

  it('useCancelContinuation still refetches when the cancel conflicts (409 stale view)', async () => {
    vi.mocked(homerApi.cancelContinuation).mockRejectedValue(new Error('409'));
    const invalidate = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useCancelContinuation(), {
      wrapper: wrapper(client),
    });
    await expect(result.current.mutateAsync(5)).rejects.toThrow('409');

    const keys = invalidate.mock.calls.map((c) => c[0]?.queryKey);
    expect(keys).toContainEqual(['continuations']);
  });
});

describe('useProcessLangGraph', () => {
  let client: QueryClient;

  beforeEach(() => {
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    vi.clearAllMocks();
  });

  it('resolves to null when the backend 404s (process is not a LangGraph run)', async () => {
    vi.mocked(homerApi.getProcessLangGraph).mockRejectedValue(
      new HomerApiError('Not Found', 404),
    );

    const { result } = renderHook(
      () => useProcessLangGraph(7, { graph: false, enabled: true }),
      { wrapper: wrapper(client) },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeNull();
  });

  it('rethrows non-404 errors', async () => {
    vi.mocked(homerApi.getProcessLangGraph).mockRejectedValue(
      new HomerApiError('Server Error', 500),
    );

    const { result } = renderHook(
      () => useProcessLangGraph(7, { graph: false, enabled: true }),
      { wrapper: wrapper(client) },
    );

    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  it('passes graph through to the client and resolves the run status', async () => {
    const status = {
      label: 'Deploy',
      module: 'deploy.graph',
      threadId: 't-1',
      topology: { nodes: [{ id: 'a' }], edges: [] },
    };
    vi.mocked(homerApi.getProcessLangGraph).mockResolvedValue(status);

    const { result } = renderHook(
      () => useProcessLangGraph(7, { graph: true, enabled: true }),
      { wrapper: wrapper(client) },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(homerApi.getProcessLangGraph).toHaveBeenCalledWith(7, { graph: true });
    expect(result.current.data).toEqual(status);
  });
});

describe('process hooks', () => {
  let client: QueryClient;

  const processInfo = (status: ProcessStatus): ProcessInfo => ({
    id: 7,
    status,
    agentName: 'deploy',
    history: [],
    executions: [],
    currentCommand: null,
    currentCommandStart: null,
    purgeAt: null,
  });

  beforeEach(() => {
    vi.useFakeTimers();
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  // Flush the pending queryFn promise (and any state updates it triggers) while
  // the fake clock stays put.
  async function flush() {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
  }

  it('useProcesses refetches on the 30 s safety-net interval', async () => {
    vi.mocked(homerApi.getProcesses).mockResolvedValue({
      processes: [processInfo('WORKING')],
      total: 1,
      offset: 0,
      limit: 50,
    });

    const { result } = renderHook(() => useProcesses(), { wrapper: wrapper(client) });
    await flush();
    expect(result.current.isSuccess).toBe(true);
    expect(homerApi.getProcesses).toHaveBeenCalledTimes(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    expect(homerApi.getProcesses).toHaveBeenCalledTimes(2);
  });

  it('useProcesses keeps polling while at least one row is non-terminal', async () => {
    vi.mocked(homerApi.getProcesses).mockResolvedValue({
      processes: [processInfo('FINISHED'), processInfo('WORKING')],
      total: 2,
      offset: 0,
      limit: 50,
    });

    renderHook(() => useProcesses(), { wrapper: wrapper(client) });
    await flush();
    expect(homerApi.getProcesses).toHaveBeenCalledTimes(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    expect(homerApi.getProcesses).toHaveBeenCalledTimes(3);
  });

  it('useProcesses keeps polling while the page is empty', async () => {
    // `[].every(...)` is true: an empty page must not read as settled, or a
    // run that appears while SSE is down never enters the list.
    vi.mocked(homerApi.getProcesses).mockResolvedValue({
      processes: [],
      total: 0,
      offset: 0,
      limit: 50,
    });

    renderHook(() => useProcesses(), { wrapper: wrapper(client) });
    await flush();
    expect(homerApi.getProcesses).toHaveBeenCalledTimes(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    expect(homerApi.getProcesses).toHaveBeenCalledTimes(3);
  });

  it.each<ProcessStatus>(['FINISHED', 'FAILED', 'KILLED', 'UNKNOWN'])(
    'useProcesses stops polling once every row is %s',
    async (status) => {
      vi.mocked(homerApi.getProcesses).mockResolvedValue({
        processes: [processInfo(status)],
        total: 1,
        offset: 0,
        limit: 50,
      });

      renderHook(() => useProcesses(), { wrapper: wrapper(client) });
      await flush();
      expect(homerApi.getProcesses).toHaveBeenCalledTimes(1);

      await act(async () => {
        await vi.advanceTimersByTimeAsync(60_000);
      });
      expect(homerApi.getProcesses).toHaveBeenCalledTimes(1);
    },
  );

  it('useFlowSummary reads one root flow under its own key, outside the processes prefix', async () => {
    vi.mocked(homerApi.getProcesses).mockResolvedValue({
      processes: [processInfo('FINISHED')],
      total: 1,
      offset: 0,
      limit: 100,
    });

    renderHook(() => useFlowSummary(7, 100), { wrapper: wrapper(client) });
    await flush();

    expect(homerApi.getProcesses).toHaveBeenCalledWith({ rootProcessId: 7, limit: 100 });
    expect(client.getQueryData(queryKeys.flowSummary(7))).toBeDefined();
    expect(client.getQueryCache().findAll({ queryKey: ['processes'] })).toHaveLength(0);
  });

  it('useFlowSummary keeps the 30 s safety net while its flow is empty', async () => {
    vi.mocked(homerApi.getProcesses).mockResolvedValue({ processes: [], total: 0, offset: 0, limit: 100 });

    renderHook(() => useFlowSummary(7, 100), { wrapper: wrapper(client) });
    await flush();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });

    expect(homerApi.getProcesses).toHaveBeenCalledTimes(3);
  });

  it.each<ProcessStatus>(['FINISHED', 'FAILED', 'KILLED', 'UNKNOWN'])(
    'useFlowSummary keeps polling when every run in the flow is %s — a continuation can still add one',
    async (status) => {
      vi.mocked(homerApi.getProcesses).mockResolvedValue({
        processes: [processInfo(status)],
        total: 1,
        offset: 0,
        limit: 100,
      });

      renderHook(() => useFlowSummary(7, 100), { wrapper: wrapper(client) });
      await flush();
      await act(async () => {
        await vi.advanceTimersByTimeAsync(60_000);
      });

      expect(homerApi.getProcesses).toHaveBeenCalledTimes(3);
    },
  );

  it('useProcess refetches on the interval while the process is non-terminal', async () => {
    vi.mocked(homerApi.getProcess).mockResolvedValue(processInfo('WORKING'));

    const { result } = renderHook(() => useProcess(7), { wrapper: wrapper(client) });
    await flush();
    expect(result.current.data?.status).toBe('WORKING');
    expect(homerApi.getProcess).toHaveBeenCalledTimes(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    expect(homerApi.getProcess).toHaveBeenCalledTimes(2);
  });

  it('useProcess exposes the failure, so the page can say why nothing loaded', async () => {
    const failure = new Error('Failed to fetch');
    vi.mocked(homerApi.getProcess).mockRejectedValue(failure);

    const { result } = renderHook(() => useProcess(7), { wrapper: wrapper(client) });
    await flush();

    expect(result.current.data).toBeUndefined();
    expect(result.current.error).toBe(failure);
  });

  it.each<ProcessStatus>(['FINISHED', 'FAILED', 'KILLED', 'UNKNOWN'])(
    'useProcess stops polling once the process is %s',
    async (status) => {
      vi.mocked(homerApi.getProcess).mockResolvedValue(processInfo(status));

      const { result } = renderHook(() => useProcess(7), { wrapper: wrapper(client) });
      await flush();
      expect(result.current.data?.status).toBe(status);
      expect(homerApi.getProcess).toHaveBeenCalledTimes(1);

      await act(async () => {
        await vi.advanceTimersByTimeAsync(60_000);
      });
      expect(homerApi.getProcess).toHaveBeenCalledTimes(1);
    },
  );
});

describe('useResumeProcess', () => {
  let client: QueryClient;

  beforeEach(() => {
    client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
    vi.clearAllMocks();
  });

  it('invalidates processes on success', async () => {
    vi.mocked(homerApi.resumeProcess).mockResolvedValue({ processId: 7 });
    const invalidate = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useResumeProcess(), {
      wrapper: wrapper(client),
    });
    await result.current.mutateAsync(7);

    expect(homerApi.resumeProcess).toHaveBeenCalledWith(7);
    const keys = invalidate.mock.calls.map((c) => c[0]?.queryKey);
    expect(keys).toContainEqual(['processes']);
  });
});

describe('useRunners / useRunnerQueue', () => {
  let client: QueryClient;
  beforeEach(() => {
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  });

  it('fetches the runner list under its query key', async () => {
    const snapshot: RunnersStatusResponse = {
      runners: [{ alias: 'local', kind: 'local', tags: [], concurrency: null, inUse: 0, instances: null }],
    };
    vi.mocked(homerApi.getRunners).mockResolvedValue(snapshot);
    const { result } = renderHook(() => useRunners(), { wrapper: wrapper(client) });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(client.getQueryData(queryKeys.runners())).toEqual(snapshot);
  });

  it('does not fetch when disabled', async () => {
    renderHook(() => useRunnerQueue(false), { wrapper: wrapper(client) });
    await new Promise((r) => setTimeout(r, 20));
    expect(homerApi.getRunnerQueue).not.toHaveBeenCalled();
  });
});

describe('useArtifactContent', () => {
  let client: QueryClient;
  beforeEach(() => {
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    vi.clearAllMocks();
  });

  it('fetches the artifact under its artifactContent key', async () => {
    const artifact = { content: 'hello', isComplete: true, processStatus: 'COMPLETED', filename: 'stdout.txt' };
    vi.mocked(homerApi.fetchArtifactContent).mockResolvedValue(artifact);
    const { result } = renderHook(() => useArtifactContent(7, 'out/stdout.txt'), {
      wrapper: wrapper(client),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(homerApi.fetchArtifactContent).toHaveBeenCalledWith(7, 'out/stdout.txt');
    expect(client.getQueryData(queryKeys.artifactContent(7, 'out/stdout.txt'))).toEqual(artifact);
  });

  it('stays idle without a path or when disabled', () => {
    renderHook(() => useArtifactContent(7, null), { wrapper: wrapper(client) });
    renderHook(() => useArtifactContent(7, 'a.txt', false), { wrapper: wrapper(client) });
    expect(homerApi.fetchArtifactContent).not.toHaveBeenCalled();
    expect(client.getQueryCache().find({ queryKey: queryKeys.artifactContent(7, '') })).toBeDefined();
  });
});
