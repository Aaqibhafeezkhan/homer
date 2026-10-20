import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { DebugCommandPanel } from './debug-command-panel';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { AgentInfo } from '@/types/homer';

vi.mock('./debug-run-dialog', () => ({
  DebugRunDialog: ({
    open,
    commandIndex,
  }: {
    open: boolean;
    commandIndex: number;
  }) =>
    open ? (
      <div data-testid="debug-dialog">dialog-for-{commandIndex}</div>
    ) : null,
}));

vi.mock('./debug-log-panel', () => ({
  DebugLogPanel: ({ processId }: { processId: number }) => (
    <div data-testid="log-panel">log-{processId}</div>
  ),
}));

vi.mock('@/lib/api/queries', () => ({
  useDebugCommand: () => ({
    mutateAsync: vi.fn(),
    isPending: false,
    error: null,
    reset: vi.fn(),
  }),
}));

function make(overrides: Partial<AgentInfo> = {}): AgentInfo {
  return {
    name: 'a',
    description: null,
    inputs: null,
    queryParams: null,
    scriptCount: 3,
    cron: null,
    writable: true,
    ...overrides,
  };
}

function renderPanel(agent: AgentInfo) {
  const client = new QueryClient();
  return render(
    <QueryClientProvider client={client}>
      <DebugCommandPanel agentName={agent.name} agent={agent} />
    </QueryClientProvider>,
  );
}

describe('DebugCommandPanel', () => {
  beforeEach(() => vi.clearAllMocks());

  it('renders one run row per command', () => {
    renderPanel(make({ scriptCount: 3 }));
    expect(screen.getByText('Command #1')).toBeDefined();
    expect(screen.getByText('Command #2')).toBeDefined();
    expect(screen.getByText('Command #3')).toBeDefined();
  });

  it('shows an empty-state when scriptCount is 0', () => {
    renderPanel(make({ scriptCount: 0 }));
    expect(screen.getByText(/No commands defined/i)).toBeDefined();
  });

  it('opens the run dialog for the clicked command index', () => {
    renderPanel(make({ scriptCount: 2 }));
    fireEvent.click(screen.getByLabelText('Debug command 2'));
    expect(screen.getByTestId('debug-dialog').textContent).toBe(
      'dialog-for-1',
    );
  });

  it('renders a recall button per command, disabled until a run happens', () => {
    renderPanel(make({ scriptCount: 2 }));
    const recall = screen.getByLabelText(
      'Recall last params for command 1',
    ) as HTMLButtonElement;
    expect(recall.disabled).toBe(true);
    expect(recall.title).toMatch(/No previous run/i);
  });
});
