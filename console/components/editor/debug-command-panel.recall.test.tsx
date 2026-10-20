import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { DebugCommandPanel } from './debug-command-panel';
import type { AgentInfo } from '@/types/homer';

// A dialog stand-in whose button reports a started run, so a recall has last
// params to replay.
vi.mock('./debug-run-dialog', () => ({
  DebugRunDialog: ({
    open,
    onStarted,
  }: {
    open: boolean;
    onStarted: (
      processId: number,
      env: { key: string; value: string }[],
      params: { key: string; value: string }[],
    ) => void;
  }) =>
    open ? (
      <button
        type="button"
        onClick={() =>
          onStarted(
            41,
            [{ key: ' TOKEN ', value: 'abc' }],
            [{ key: 'branch', value: 'main' }, { key: ' ', value: 'dropped' }],
          )
        }
      >
        start-run
      </button>
    ) : null,
}));

vi.mock('./debug-log-panel', () => ({
  DebugLogPanel: ({ processId, commandIndex }: { processId: number; commandIndex: number }) => (
    <div data-testid="log-panel">
      log-{processId}-{commandIndex}
    </div>
  ),
}));

const mutateAsync = vi.hoisted(() => vi.fn());
vi.mock('@/lib/api/queries', () => ({
  useDebugCommand: () => ({ mutateAsync, isPending: false, error: null, reset: vi.fn() }),
}));

const agent: AgentInfo = {
  name: 'a',
  description: null,
  inputs: null,
  queryParams: null,
  scriptCount: 2,
  cron: null,
  writable: true,
};

function startFirstCommandRun() {
  render(<DebugCommandPanel agentName="a" agent={agent} />);
  fireEvent.click(screen.getByLabelText('Debug command 2'));
  fireEvent.click(screen.getByText('start-run'));
}

describe('DebugCommandPanel recall', () => {
  beforeEach(() => mutateAsync.mockReset());

  it('enables recall once a run started and replays its last params', async () => {
    mutateAsync.mockResolvedValue({ processId: 99 });
    startFirstCommandRun();
    expect(screen.getByTestId('log-panel').textContent).toBe('log-41-1');

    const recall = screen.getByLabelText('Recall last params for command 2') as HTMLButtonElement;
    expect(recall.disabled).toBe(false);
    expect(recall.title).toBe('Re-run with last submitted params');
    fireEvent.click(recall);

    await waitFor(() => expect(screen.getByTestId('log-panel').textContent).toBe('log-99-1'));
    expect(mutateAsync).toHaveBeenCalledWith({
      commandIndex: 1,
      env: { TOKEN: 'abc' },
      queryParams: { branch: 'main' },
    });
    expect(recall.disabled).toBe(false);
  });

  it('shows the error message when the recall run fails', async () => {
    mutateAsync.mockRejectedValueOnce(new Error('backend down'));
    startFirstCommandRun();
    fireEvent.click(screen.getByLabelText('Recall last params for command 2'));
    expect(await screen.findByText('backend down')).toBeInTheDocument();
    expect(screen.getByTestId('log-panel').textContent).toBe('log-41-1');
  });

  it('falls back to a generic message when the failure is not an Error', async () => {
    mutateAsync.mockRejectedValueOnce('nope');
    startFirstCommandRun();
    fireEvent.click(screen.getByLabelText('Recall last params for command 2'));
    expect(await screen.findByText('Recall run failed')).toBeInTheDocument();
  });

  it('clears an earlier recall error when a later recall starts', async () => {
    mutateAsync.mockRejectedValueOnce(new Error('backend down')).mockResolvedValueOnce({ processId: 7 });
    startFirstCommandRun();
    const recall = screen.getByLabelText('Recall last params for command 2');
    fireEvent.click(recall);
    expect(await screen.findByText('backend down')).toBeInTheDocument();
    fireEvent.click(recall);
    await waitFor(() => expect(screen.queryByText('backend down')).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId('log-panel').textContent).toBe('log-7-1'));
  });
});
