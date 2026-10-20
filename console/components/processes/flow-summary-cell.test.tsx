import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { FlowSummaryCell, FLOW_SUMMARY_LIMIT, flowState } from './flow-summary-cell';
import type { ProcessInfo } from '@/types/homer';

const useFlowSummaryMock = vi.hoisted(() => vi.fn());

vi.mock('@/lib/api/queries', () => ({
  useFlowSummary: useFlowSummaryMock,
}));

function makeRun(overrides: Partial<ProcessInfo> = {}): ProcessInfo {
  return {
    id: 10,
    status: 'FINISHED',
    agentName: 'worker',
    history: [],
    executions: [],
    currentCommand: null,
    currentCommandStart: null,
    purgeAt: null,
    ...overrides,
  };
}

function mockFlow(runs: ProcessInfo[], total = runs.length) {
  useFlowSummaryMock.mockReturnValue({
    data: { processes: runs, total, offset: 0, limit: FLOW_SUMMARY_LIMIT },
  });
}

describe('flowState', () => {
  it('reports no state for an empty flow instead of a green finished', () => {
    expect(flowState([])).toBeNull();
  });

  it('reports a question before anything else — a parked run is the actionable one', () => {
    expect(
      flowState([
        makeRun({ id: 1, status: 'WORKING' }),
        makeRun({ id: 2, status: 'WORKING', openQuestions: 1 }),
      ]),
    ).toBe('question');
  });

  it('reports running for a flow with live work and no question', () => {
    expect(flowState([makeRun({ status: 'FINISHED' }), makeRun({ id: 11, status: 'WORKING' })])).toBe(
      'running',
    );
    expect(flowState([makeRun({ status: 'CREATED' })])).toBe('running');
  });

  it('reports failed for a flow whose runs all ended badly', () => {
    expect(flowState([makeRun({ status: 'FINISHED' }), makeRun({ id: 11, status: 'FAILED' })])).toBe(
      'failed',
    );
    // KILLED is not a successful finish either.
    expect(flowState([makeRun({ status: 'KILLED' })])).toBe('failed');
  });

  it('reports finished only when every run finished', () => {
    expect(flowState([makeRun({ status: 'FINISHED' }), makeRun({ id: 11, status: 'FINISHED' })])).toBe(
      'finished',
    );
  });
});

describe('FlowSummaryCell', () => {
  beforeEach(() => {
    useFlowSummaryMock.mockReset();
  });

  it('queries the flow for the row root id, capped', () => {
    mockFlow([]);
    render(<FlowSummaryCell rootProcessId={7} />);
    expect(useFlowSummaryMock).toHaveBeenCalledWith(7, FLOW_SUMMARY_LIMIT);
  });

  it('renders a dash until the flow loads', () => {
    useFlowSummaryMock.mockReturnValue({ data: undefined });
    render(<FlowSummaryCell rootProcessId={7} />);
    expect(screen.getByText('-')).toBeInTheDocument();
  });

  it('renders the run count from the response total, not the fetched page', () => {
    // The cap bounds the cost sum, so `total` is what the operator is told.
    mockFlow([makeRun({ id: 10 }), makeRun({ id: 11 })], 42);
    render(<FlowSummaryCell rootProcessId={7} />);
    expect(screen.getByText('42 runs')).toBeInTheDocument();
  });

  it('singularizes a one-run flow', () => {
    mockFlow([makeRun()], 1);
    render(<FlowSummaryCell rootProcessId={7} />);
    expect(screen.getByText('1 run')).toBeInTheDocument();
  });

  it('sums the cost of the fetched runs', () => {
    mockFlow([
      makeRun({ id: 10, costUsd: 1.25 }),
      makeRun({ id: 11, costUsd: 0.5 }),
      makeRun({ id: 12 }),
    ]);
    render(<FlowSummaryCell rootProcessId={7} />);
    expect(screen.getByText('$1.7500')).toBeInTheDocument();
  });

  it('omits the cost when the flow spent nothing', () => {
    mockFlow([makeRun({ costUsd: 0 })]);
    render(<FlowSummaryCell rootProcessId={7} />);
    expect(screen.queryByText(/^\$/)).not.toBeInTheDocument();
  });

  it('labels a running flow', () => {
    mockFlow([makeRun({ status: 'WORKING' })]);
    render(<FlowSummaryCell rootProcessId={7} />);
    expect(screen.getByText('running')).toBeInTheDocument();
  });

  it('labels a flow waiting on a question', () => {
    mockFlow([makeRun({ status: 'WORKING', openQuestions: 1 })]);
    render(<FlowSummaryCell rootProcessId={7} />);
    expect(screen.getByText('waiting on a question')).toBeInTheDocument();
  });

  it('labels a failed flow', () => {
    mockFlow([makeRun({ status: 'FAILED' })]);
    render(<FlowSummaryCell rootProcessId={7} />);
    expect(screen.getByText('failed')).toBeInTheDocument();
  });

  it('labels a finished flow', () => {
    mockFlow([makeRun({ status: 'FINISHED' })]);
    render(<FlowSummaryCell rootProcessId={7} />);
    expect(screen.getByText('finished')).toBeInTheDocument();
  });

  it('shows no state label for a root with no descendants', () => {
    mockFlow([]);
    render(<FlowSummaryCell rootProcessId={7} />);
    expect(screen.queryByText(/running|finished|failed|waiting/)).not.toBeInTheDocument();
  });

  it('marks a flow whose page was cut short by the fetch cap', () => {
    mockFlow([makeRun({ id: 10 }), makeRun({ id: 11 })], 42);
    render(<FlowSummaryCell rootProcessId={7} />);
    const marker = screen.getByText('partial');
    expect(marker).toHaveAttribute('title', 'Showing the newest 2 of 42 runs');
  });

  it('shows no truncation marker when the whole flow was fetched', () => {
    mockFlow([makeRun({ id: 10 }), makeRun({ id: 11 })], 2);
    render(<FlowSummaryCell rootProcessId={7} />);
    expect(screen.queryByText('partial')).not.toBeInTheDocument();
  });
});
