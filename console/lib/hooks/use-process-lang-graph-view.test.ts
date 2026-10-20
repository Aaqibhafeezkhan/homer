import { renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useProcessLangGraphView } from './use-process-lang-graph-view';
import type { LangGraphRunStatus, ProcessInfo } from '@/types/homer';

const useProcessLangGraphMock = vi.hoisted(() => vi.fn());
vi.mock('@/lib/api/queries', () => ({ useProcessLangGraph: useProcessLangGraphMock }));

type Opts = { graph: boolean; enabled: boolean; refetchInterval?: number | false };

function proc(overrides: Partial<ProcessInfo> = {}): ProcessInfo {
  return {
    id: 7,
    status: 'FINISHED',
    agentName: 'demo',
    history: [],
    executions: [],
    currentCommand: null,
    currentCommandStart: null,
    purgeAt: null,
    ...overrides,
  };
}

const probe = { label: 'graph-cmd' } as LangGraphRunStatus;
const status = { label: 'graph-cmd-full' } as LangGraphRunStatus;

/** Answers the probe and the full-status query with the given data. */
function answer(probeData: LangGraphRunStatus | null, statusData: LangGraphRunStatus | null) {
  useProcessLangGraphMock.mockImplementation((_id: number, opts: Opts) => ({
    data: opts.graph ? statusData : probeData,
  }));
}

function optsOf(graph: boolean): Opts {
  const call = useProcessLangGraphMock.mock.calls.findLast((c) => (c[1] as Opts).graph === graph);
  return call![1] as Opts;
}

describe('useProcessLangGraphView', () => {
  beforeEach(() => {
    useProcessLangGraphMock.mockReset();
    answer(null, null);
  });

  it('queries nothing before the process has loaded', () => {
    const { result } = renderHook(() => useProcessLangGraphView(7, undefined, true, true));
    expect(useProcessLangGraphMock).toHaveBeenCalledWith(7, { graph: false, enabled: false });
    expect(optsOf(true)).toEqual({ graph: true, enabled: false, refetchInterval: 5000 });
    expect(result.current.resumeEligible).toBe(false);
    expect(result.current.liveIsLangGraph).toBe(false);
  });

  it.each(['FAILED', 'KILLED', 'UNKNOWN'] as const)('treats a %s run as resume-eligible and probes it', (s) => {
    const { result } = renderHook(() => useProcessLangGraphView(7, proc({ status: s }), false, false));
    expect(result.current.resumeEligible).toBe(true);
    expect(optsOf(false).enabled).toBe(true);
    expect(optsOf(true)).toEqual({ graph: true, enabled: false, refetchInterval: false });
  });

  it('neither probes nor polls an idle finished run with nothing expanded', () => {
    const { result } = renderHook(() => useProcessLangGraphView(7, proc(), false, false));
    expect(result.current.resumeEligible).toBe(false);
    expect(optsOf(false).enabled).toBe(false);
    expect(optsOf(true).enabled).toBe(false);
  });

  it('probes and polls the full status while an execution is expanded', () => {
    answer(probe, status);
    const { result } = renderHook(() => useProcessLangGraphView(7, proc(), true, false));
    expect(optsOf(false).enabled).toBe(true);
    expect(optsOf(true)).toEqual({ graph: true, enabled: true, refetchInterval: 5000 });
    expect(result.current.lgLabel).toBe('graph-cmd-full');
  });

  it('falls back to the probe label until the full status arrives', () => {
    answer(probe, null);
    const { result } = renderHook(() => useProcessLangGraphView(7, proc(), true, false));
    expect(result.current.lgLabel).toBe('graph-cmd');
  });

  it('opens the live workflow only when the running command is the LangGraph one', () => {
    answer(probe, null);
    const running = proc({ status: 'WORKING', currentCommand: 'graph-cmd' });
    const { result } = renderHook(() => useProcessLangGraphView(7, running, false, true));
    expect(optsOf(false).enabled).toBe(true);
    expect(result.current.liveIsLangGraph).toBe(true);
    expect(result.current.liveWorkflowOpen).toBe(true);
    expect(optsOf(true)).toEqual({ graph: true, enabled: true, refetchInterval: 5000 });
  });

  it('keeps the live workflow closed for another running command or when not expanded', () => {
    answer(probe, null);
    const other = proc({ status: 'WORKING', currentCommand: 'other-cmd' });
    const { result } = renderHook(() => useProcessLangGraphView(7, other, false, true));
    expect(result.current.liveIsLangGraph).toBe(false);
    expect(result.current.liveWorkflowOpen).toBe(false);
    expect(optsOf(true).enabled).toBe(false);

    const same = proc({ status: 'WORKING', currentCommand: 'graph-cmd' });
    const collapsed = renderHook(() => useProcessLangGraphView(7, same, false, false));
    expect(collapsed.result.current.liveIsLangGraph).toBe(true);
    expect(collapsed.result.current.liveWorkflowOpen).toBe(false);
  });
});
