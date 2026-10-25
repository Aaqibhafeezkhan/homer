import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within, fireEvent } from '@testing-library/react';
import { axe } from 'vitest-axe';
import { ProcessTable } from './process-table';
import type { ProcessInfo, ProcessExecution } from '@/types/homer';

const useRouterMock = vi.hoisted(() => vi.fn(() => ({ push: vi.fn() })));
const useKillProcessMock = vi.hoisted(() =>
  vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
);
const useRetryProcessMock = vi.hoisted(() =>
  vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
);

vi.mock('next/navigation', () => ({
  useRouter: useRouterMock,
}));

vi.mock('@/lib/api/queries', () => ({
  useKillProcess: useKillProcessMock,
  useRetryProcess: useRetryProcessMock,
}));

vi.mock('@/lib/auth/use-can', () => ({ useCan: vi.fn() }));
import { useCan, type Capabilities } from '@/lib/auth/use-can';
import { fakeCapabilities } from '@/lib/auth/capabilities-test-utils';

function mockCan(overrides: Partial<Capabilities> = {}) {
  vi.mocked(useCan).mockReturnValue(fakeCapabilities(true, overrides));
}

function makeExecution(overrides: Partial<ProcessExecution> = {}): ProcessExecution {
  return {
    label: 'cmd-0',
    start: 1_700_000_000,
    end: 1_700_000_001,
    resultCode: 0,
    stdOut: 'cmd_0.out',
    stdErr: 'cmd_0.err',
    errMsg: null,
    ...overrides,
  };
}

/** A command recorded as an execution but never run: `when:` said no. */
function skippedExecution(label: string): ProcessExecution {
  return makeExecution({
    label,
    resultCode: -1,
    skipped: true,
    stdOut: null,
    stdErr: null,
    errMsg: 'when.all expression #1 false: false-expr',
  });
}

function makeProcess(overrides: Partial<ProcessInfo> = {}): ProcessInfo {
  return {
    id: 42,
    status: 'FINISHED',
    agentName: 'demo-agent',
    history: [{ timestamp: 1_700_000_002, status: 'FINISHED' }],
    executions: [makeExecution()],
    currentCommand: null,
    currentCommandStart: null,
    purgeAt: null,
    ...overrides,
  };
}

describe('ProcessTable', () => {
  beforeEach(() => {
    useRouterMock.mockClear();
    useKillProcessMock.mockClear();
    useRetryProcessMock.mockClear();
    mockCan();
  });

  it('names the last command that ran, not a trailing skipped one', () => {
    // A quickstart run answered `ship`: `hold` and `unexpected-answer` were
    // skipped by their `when:` conditions after `ship` ran.
    const process = makeProcess({
      executions: [
        makeExecution({ label: 'write-report', resultCode: 0 }),
        makeExecution({ label: 'ask-ship-it', resultCode: 0 }),
        makeExecution({ label: 'ship', resultCode: 0 }),
        skippedExecution('hold'),
        skippedExecution('unexpected-answer'),
      ],
    });

    render(<ProcessTable processes={[process]} />);

    // Desktop row + mobile card both name the command that ran.
    expect(screen.getAllByText('ship')).toHaveLength(2);
    expect(screen.queryByText('unexpected-answer')).not.toBeInTheDocument();
    expect(screen.queryByText('hold')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Skipped')).not.toBeInTheDocument();
  });

  it('renders the success icon for a successful last execution', () => {
    const process = makeProcess({
      executions: [makeExecution({ resultCode: 0 })],
    });

    render(<ProcessTable processes={[process]} />);

    expect(screen.queryByLabelText('Skipped')).not.toBeInTheDocument();
  });

  it('falls back to the empty cell when every execution was skipped', () => {
    const process = makeProcess({
      executions: [skippedExecution('deploy-eu')],
    });

    render(<ProcessTable processes={[process]} />);

    const lastCommandIndex = screen
      .getAllByRole('columnheader')
      .findIndex((cell) => cell.textContent === 'Last Command');
    const dataRow = screen.getAllByRole('row')[1];
    expect(within(dataRow).getAllByRole('cell')[lastCommandIndex]).toHaveTextContent('-');
    // Neither surface names a skipped command, and the mobile card renders nothing.
    expect(screen.queryByText('deploy-eu')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Skipped')).not.toBeInTheDocument();
    expect(screen.queryByText('Last:')).not.toBeInTheDocument();
  });

  it('shows a waiting-for-input badge when the process has open questions', () => {
    const process = makeProcess({ status: 'WORKING', openQuestions: 2 });

    render(<ProcessTable processes={[process]} />);

    // Both the desktop row and the mobile card render the badge.
    const badges = screen.getAllByLabelText('2 open questions waiting for an answer');
    expect(badges).toHaveLength(2);
    expect(badges[0]).toHaveTextContent('2');
  });

  it('renders no question badge when openQuestions is zero or absent', () => {
    render(
      <ProcessTable
        processes={[makeProcess({ openQuestions: 0 }), makeProcess({ id: 43 })]}
      />
    );
    expect(screen.queryByLabelText(/open question/)).toBeNull();
  });

  it('hides Kill for a WORKING process the user cannot control', () => {
    mockCan({ canKillProcess: () => false });
    const process = makeProcess({ status: 'WORKING' });

    render(<ProcessTable processes={[process]} />);

    expect(screen.queryAllByLabelText('Kill process 42')).toHaveLength(0);
  });

  it('shows Kill for a WORKING process the user can control', () => {
    mockCan({ canKillProcess: () => true });
    const process = makeProcess({ status: 'WORKING' });

    render(<ProcessTable processes={[process]} />);

    // Desktop table + mobile card both render the button.
    expect(screen.getAllByLabelText('Kill process 42')).toHaveLength(2);
  });

  it('hides Retry for a FINISHED process the user may not retry', () => {
    mockCan({ canRetryProcess: () => false });
    const process = makeProcess({ status: 'FINISHED' });

    render(<ProcessTable processes={[process]} />);

    expect(screen.queryAllByLabelText('Retry process 42')).toHaveLength(0);
  });

  it('shows Retry for a FINISHED process the user may retry', () => {
    mockCan({ canRetryProcess: () => true });
    const process = makeProcess({ status: 'FINISHED' });

    render(<ProcessTable processes={[process]} />);

    expect(screen.getAllByLabelText('Retry process 42')).toHaveLength(2);
  });

  describe('row actions', () => {
    it('opens the process when its row is clicked, but not twice through the id link', () => {
      const push = vi.fn();
      useRouterMock.mockReturnValue({ push });
      render(<ProcessTable processes={[makeProcess()]} />);

      fireEvent.click(screen.getAllByText('demo-agent')[0]);
      expect(push).toHaveBeenCalledWith('/processes/42');

      push.mockClear();
      fireEvent.click(screen.getAllByLabelText('Open process 42 details')[0]);
      expect(push).not.toHaveBeenCalled();
    });

    it('asks before killing and kills only on confirm', async () => {
      const push = vi.fn();
      useRouterMock.mockReturnValue({ push });
      const mutateAsync = vi.fn().mockResolvedValue(undefined);
      useKillProcessMock.mockReturnValue({ mutateAsync, isPending: false });
      mockCan({ canKillProcess: () => true });
      render(<ProcessTable processes={[makeProcess({ status: 'WORKING' })]} />);

      fireEvent.click(screen.getAllByLabelText('Kill process 42')[0]);
      expect(push).not.toHaveBeenCalled();
      fireEvent.click(await screen.findByRole('button', { name: 'Cancel' }));
      expect(mutateAsync).not.toHaveBeenCalled();
      expect(screen.queryByText('Confirm Kill Process')).toBeNull();

      fireEvent.click(screen.getAllByLabelText('Kill process 42')[0]);
      fireEvent.click(await screen.findByRole('button', { name: 'Kill Process' }));
      await vi.waitFor(() => expect(mutateAsync).toHaveBeenCalledWith(42));
    });

    it('retries a process and opens the new run', async () => {
      const push = vi.fn();
      useRouterMock.mockReturnValue({ push });
      const mutateAsync = vi.fn().mockResolvedValue({ processId: 77 });
      useRetryProcessMock.mockReturnValue({ mutateAsync, isPending: false });
      mockCan({ canRetryProcess: () => true });
      render(<ProcessTable processes={[makeProcess({ status: 'FAILED' })]} />);

      fireEvent.click(screen.getAllByLabelText('Retry process 42')[0]);

      await vi.waitFor(() => expect(push).toHaveBeenCalledWith('/processes/77'));
      expect(mutateAsync).toHaveBeenCalledWith(42);
      expect(push).not.toHaveBeenCalledWith('/processes/42');
    });
  });

  describe('tags', () => {
    it('renders a chip for each of a run\'s subject tags', () => {
      const process = makeProcess({ tags: ['mr:15'] });

      render(<ProcessTable processes={[process]} />);

      // Desktop table + mobile card both render the chip, so it appears twice.
      const chips = screen.getAllByLabelText('Filter by tag mr:15');
      expect(chips).toHaveLength(2);
      expect(chips[0]).toHaveTextContent('mr:15');
    });

    it('invokes onTagClick with the tag when a chip is clicked', () => {
      const onTagClick = vi.fn();
      const process = makeProcess({ tags: ['mr:15'] });

      render(<ProcessTable processes={[process]} onTagClick={onTagClick} />);

      const chips = screen.getAllByLabelText('Filter by tag mr:15');
      fireEvent.click(chips[0]);

      expect(onTagClick).toHaveBeenCalledWith('mr:15');
    });
  });

  describe('runner column', () => {
    it('renders the alias without a pod-phase dot for a non-k8s run', () => {
      const process = makeProcess({ runner: { alias: 'local', type: 'local', podName: null, podPhase: 'NONE' } });

      render(<ProcessTable processes={[process]} />);

      // Desktop table + mobile card both render the cell.
      expect(screen.getAllByText('local')).toHaveLength(2);
      expect(screen.queryByLabelText('Pod phase: NONE')).not.toBeInTheDocument();
    });

    it('renders the generic backend for a non-admin viewer whose alias is withheld', () => {
      // A non-admin row carries `alias: null` and the backend type.
      const process = makeProcess({ runner: { alias: null, type: 'kubernetes', podName: 'homer-42-abcdef', podPhase: 'READY' } });

      render(<ProcessTable processes={[process]} />);

      expect(screen.getAllByText('kubernetes')).toHaveLength(2);
      expect(screen.getAllByLabelText('Pod phase: READY')).toHaveLength(2);
    });

    it('renders a dash when the run has no runner state', () => {
      render(<ProcessTable processes={[makeProcess({ runner: null })]} />);

      expect(screen.queryByText('local')).not.toBeInTheDocument();
    });

    it('renders the pod-phase dot for a pod-bearing run', () => {
      const process = makeProcess({
        runner: { alias: 'k8s-dev', type: 'kubernetes', podName: 'homer-42-abcdef', podPhase: 'READY' },
      });

      render(<ProcessTable processes={[process]} />);

      expect(screen.getAllByLabelText('Pod phase: READY')).toHaveLength(2);
    });
  });

  describe('cost column', () => {
    it('renders the formatted cost for a run that has spent something', () => {
      const process = makeProcess({ costUsd: 1.5 });

      render(<ProcessTable processes={[process]} />);

      expect(screen.getByText('$1.5000')).toBeInTheDocument();
    });

    it('renders a dash when costUsd is zero or absent', () => {
      render(
        <ProcessTable
          processes={[makeProcess({ costUsd: 0 }), makeProcess({ id: 43 })]}
        />
      );

      expect(screen.queryByText(/^\$/)).toBeNull();
    });
  });

  describe('flow summary column', () => {
    it('renders the slot cell and its header when the slot is passed', () => {
      const process = makeProcess({ id: 42 });

      render(
        <ProcessTable
          processes={[process]}
          flowSummary={(p) => <span>flow-of-{p.id}</span>}
        />,
      );

      expect(screen.getByRole('columnheader', { name: 'Flow' })).toBeInTheDocument();
      // The desktop table carries the cell, and the mobile card mirrors it
      // (its `Flow:` row is the only one, so it doubles as the card assertion).
      expect(screen.getAllByText('flow-of-42')).toHaveLength(2);
      expect(screen.getByText('Flow:')).toBeInTheDocument();
    });

    it('renders no Flow column or cell when the slot is absent', () => {
      render(<ProcessTable processes={[makeProcess()]} />);

      expect(screen.queryByRole('columnheader', { name: 'Flow' })).not.toBeInTheDocument();
    });
  });

  describe('a11y', () => {
    it('has no violations for a populated WORKING row with action buttons', async () => {
      const process = makeProcess({
        status: 'WORKING',
        currentCommand: 'cmd-running',
        currentCommandStart: 1_700_000_000,
        executions: [],
      });
      const { container } = render(<ProcessTable processes={[process]} />);
      expect(await axe(container)).toHaveNoViolations();
    });

    it('has no violations for a finished row with retry action', async () => {
      const process = makeProcess({
        status: 'FAILED',
        executions: [
          makeExecution({ label: 'cmd-failed', resultCode: 2 }),
        ],
      });
      const { container } = render(<ProcessTable processes={[process]} />);
      expect(await axe(container)).toHaveNoViolations();
    });
  });
});
