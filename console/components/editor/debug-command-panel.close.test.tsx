import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { DebugCommandPanel } from './debug-command-panel';
import type { AgentInfo } from '@/types/homer';

// Stand-ins that expose the dialog's dismiss and start paths and the log panel's close button.
vi.mock('./debug-run-dialog', () => ({
  DebugRunDialog: ({
    open,
    onOpenChange,
    onStarted,
  }: {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    onStarted: (processId: number, env: [], params: []) => void;
  }) =>
    open ? (
      <div data-testid="debug-dialog">
        <button type="button" onClick={() => onOpenChange(true)}>
          keep-open
        </button>
        <button type="button" onClick={() => onOpenChange(false)}>
          dismiss
        </button>
        <button type="button" onClick={() => onStarted(41, [], [])}>
          start-run
        </button>
      </div>
    ) : null,
}));

vi.mock('./debug-log-panel', () => ({
  DebugLogPanel: ({ processId, onClose }: { processId: number; onClose: () => void }) => (
    <div data-testid="log-panel">
      log-{processId}
      <button type="button" onClick={onClose}>
        close-log
      </button>
    </div>
  ),
}));

vi.mock('@/lib/api/queries', () => ({
  useDebugCommand: () => ({ mutateAsync: vi.fn(), isPending: false, error: null, reset: vi.fn() }),
}));

const agent: AgentInfo = {
  name: 'a',
  description: null,
  inputs: null,
  queryParams: null,
  scriptCount: 1,
  cron: null,
  writable: true,
};

describe('DebugCommandPanel close paths', () => {
  it('closes the run dialog only when it asks to close', () => {
    render(<DebugCommandPanel agentName="a" agent={agent} />);
    fireEvent.click(screen.getByLabelText('Debug command 1'));

    fireEvent.click(screen.getByText('keep-open'));
    expect(screen.getByTestId('debug-dialog')).toBeInTheDocument();

    fireEvent.click(screen.getByText('dismiss'));
    expect(screen.queryByTestId('debug-dialog')).not.toBeInTheDocument();
  });

  it('removes the log panel when it is closed', () => {
    render(<DebugCommandPanel agentName="a" agent={agent} />);
    fireEvent.click(screen.getByLabelText('Debug command 1'));
    fireEvent.click(screen.getByText('start-run'));
    expect(screen.getByTestId('log-panel')).toHaveTextContent('log-41');

    fireEvent.click(screen.getByText('close-log'));
    expect(screen.queryByTestId('log-panel')).not.toBeInTheDocument();
  });
});
