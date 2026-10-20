import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { FlowSection, FLOW_RUNS_LIMIT } from './flow-section';
import type { ProcessInfo } from '@/types/homer';

const useProcessesMock = vi.hoisted(() => vi.fn());

vi.mock('@/lib/api/queries', () => ({
  useProcesses: useProcessesMock,
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
  useProcessesMock.mockReturnValue({
    data: { processes: runs, total, offset: 0, limit: FLOW_RUNS_LIMIT },
  });
}

describe('FlowSection', () => {
  beforeEach(() => {
    useProcessesMock.mockReset();
  });

  it('queries every run of the flow in id order', () => {
    mockFlow([]);
    render(<FlowSection processId={7} rootAgentName="orchestrator" />);
    expect(useProcessesMock).toHaveBeenCalledWith({
      rootProcessId: 7,
      order: 'asc',
      limit: FLOW_RUNS_LIMIT,
    });
  });

  it('renders nothing until the flow loads', () => {
    useProcessesMock.mockReturnValue({ data: undefined });
    const { container } = render(<FlowSection processId={7} rootAgentName="orchestrator" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('lists the descendants in id order with agent, status, cost and parent', () => {
    mockFlow([
      makeRun({ id: 8, agentName: 'worker', status: 'FINISHED', costUsd: 0.5, parentProcessId: 7 }),
      makeRun({ id: 9, agentName: 'reviewer', status: 'WORKING', costUsd: 1.5, parentProcessId: 8 }),
    ]);
    render(<FlowSection processId={7} rootAgentName="orchestrator" />);

    const first = screen.getByRole('link', { name: 'Open flow run 8 details' });
    const second = screen.getByRole('link', { name: 'Open flow run 9 details' });
    expect(first).toHaveAttribute('href', '/processes/8');
    expect(
      first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();

    expect(screen.getByText('worker')).toBeInTheDocument();
    expect(screen.getByText('reviewer')).toBeInTheDocument();
    expect(screen.getByLabelText('Status: Working')).toBeInTheDocument();
    expect(screen.getByText('$0.5000')).toBeInTheDocument();
    expect(screen.getByText('$1.5000')).toBeInTheDocument();
    expect(screen.getByText('parent 7')).toBeInTheDocument();
  });

  it('renders a dash for a run with no parent and zero cost', () => {
    mockFlow([makeRun({ id: 8, parentProcessId: null })]);
    render(<FlowSection processId={7} rootAgentName="orchestrator" />);
    expect(screen.getByText('parent —')).toBeInTheDocument();
    expect(screen.getByText('$0.0000')).toBeInTheDocument();
  });

  it('hides the orchestrator\'s own resume segments by default', () => {
    mockFlow([
      makeRun({ id: 8, agentName: 'worker' }),
      makeRun({ id: 9, agentName: 'orchestrator', parentProcessId: 8 }),
    ]);
    render(<FlowSection processId={7} rootAgentName="orchestrator" />);

    expect(screen.getByRole('link', { name: 'Open flow run 8 details' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Open flow run 9 details' })).not.toBeInTheDocument();
  });

  it('shows the hidden segments when the toggle is pressed, and hides them again', async () => {
    const user = userEvent.setup();
    mockFlow([
      makeRun({ id: 8, agentName: 'worker' }),
      makeRun({ id: 9, agentName: 'orchestrator', parentProcessId: 8 }),
    ]);
    render(<FlowSection processId={7} rootAgentName="orchestrator" />);

    await user.click(screen.getByRole('button', { name: 'Show resume segments' }));
    expect(screen.getByRole('link', { name: 'Open flow run 9 details' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Hide resume segments' }));
    expect(screen.queryByRole('link', { name: 'Open flow run 9 details' })).not.toBeInTheDocument();
  });

  it('offers no segment toggle when no run shares the root\'s agent', () => {
    mockFlow([makeRun({ id: 8, agentName: 'worker' })]);
    render(<FlowSection processId={7} rootAgentName="orchestrator" />);
    expect(screen.queryByRole('button', { name: /resume segments/i })).not.toBeInTheDocument();
  });

  it('offers no segment toggle when the root\'s agent name is unknown', () => {
    mockFlow([makeRun({ id: 8, agentName: 'worker' })]);
    render(<FlowSection processId={7} rootAgentName={null} />);
    expect(screen.queryByRole('button', { name: /resume segments/i })).not.toBeInTheDocument();
  });

  it('reports the flow size from the response total', () => {
    mockFlow([makeRun({ id: 8 }), makeRun({ id: 9 })], 5);
    render(<FlowSection processId={7} rootAgentName="orchestrator" />);
    expect(screen.getByText(/5 runs in this flow/)).toBeInTheDocument();
    expect(screen.getByText(/first 2 shown/)).toBeInTheDocument();
  });

  it('says nothing about truncation when the whole flow fits', () => {
    mockFlow([makeRun({ id: 8 }), makeRun({ id: 9 })], 2);
    render(<FlowSection processId={7} rootAgentName="orchestrator" />);
    expect(screen.getByText('2 runs in this flow')).toBeInTheDocument();
  });
});
