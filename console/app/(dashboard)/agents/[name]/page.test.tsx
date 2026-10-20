import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AgentDetailPage from './page';
import type { AgentInfo, ProcessInfo } from '@/types/homer';

// Both pull in query hooks / Radix state irrelevant to the Edit-link gate.
vi.mock('@/components/agents/run-agent-dialog', () => ({
  RunAgentDialog: () => null,
}));
vi.mock('@/components/agents/workflow-graph-card', () => ({
  WorkflowGraphCard: () => null,
}));
vi.mock('@/components/processes/process-table', () => ({
  ProcessTable: () => null,
}));

const useAgentMock = vi.hoisted(() => vi.fn());
const useProcessesMock = vi.hoisted(() => vi.fn());

vi.mock('@/lib/api/queries', () => ({
  useAgent: useAgentMock,
  useProcesses: useProcessesMock,
}));

const replace = vi.hoisted(() => vi.fn());
const searchParamsMock = vi.hoisted(() => vi.fn());

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace }),
  usePathname: () => '/agents/demo',
  useSearchParams: () => searchParamsMock(),
}));

function renderWithUrl(query: string) {
  searchParamsMock.mockReturnValue(new URLSearchParams(query));
  return render(<AgentDetailPage params={fulfilledParams('demo')} />);
}

vi.mock('@/lib/auth/use-can', () => ({ useCan: vi.fn() }));
import { useCan } from '@/lib/auth/use-can';
import { fakeCapabilities } from '@/lib/auth/capabilities-test-utils';

function mockCan(can: (action: string, name?: string) => boolean, isAdmin = false) {
  vi.mocked(useCan).mockReturnValue(
    fakeCapabilities(isAdmin, { can: can as ReturnType<typeof useCan>['can'] }),
  );
}

// React's `use()` always suspends on a plain Promise, even an already-settled
// one. Next.js's real params promise pre-attaches the {status, value}
// thenable-cache fields React's `use()` reads synchronously. Mirror that here
// so the page renders in one synchronous pass.
function fulfilledParams(name: string): Promise<{ name: string }> {
  const p = Promise.resolve({ name }) as Promise<{ name: string }> & {
    status?: string;
    value?: { name: string };
  };
  p.status = 'fulfilled';
  p.value = { name };
  return p;
}

const writableAgent: AgentInfo = {
  name: 'demo',
  description: null,
  inputs: null,
  queryParams: null,
  scriptCount: 1,
  cron: null,
  writable: true,
};

describe('AgentDetailPage Edit-link gating', () => {
  beforeEach(() => {
    useAgentMock.mockReturnValue({ data: writableAgent, isLoading: false });
    useProcessesMock.mockReturnValue({
      data: { processes: [], total: 0 },
      isLoading: false,
    });
  });

  it('shows the Edit link when the agent is writable and the user can edit', () => {
    mockCan((action) => action === 'edit');
    renderWithUrl('');
    const editLink = screen.getByRole('link', { name: /edit agent demo/i });
    expect(editLink).toHaveAttribute('href', '/agents/demo/edit');
  });

  it('hides the Edit link when the user lacks edit, even if writable', () => {
    mockCan(() => false);
    renderWithUrl('');
    expect(
      screen.queryByRole('link', { name: /edit agent/i }),
    ).not.toBeInTheDocument();
  });

  it('hides the Edit link when the agent is not writable, even if permitted', () => {
    mockCan(() => true);
    useAgentMock.mockReturnValue({
      data: { ...writableAgent, writable: false },
      isLoading: false,
    });
    renderWithUrl('');
    expect(
      screen.queryByRole('link', { name: /edit agent/i }),
    ).not.toBeInTheDocument();
  });
});

const oneProcess: ProcessInfo[] = [
  {
    id: 1,
    status: 'FINISHED',
    agentName: 'demo',
    history: [],
    executions: [],
    currentCommand: null,
    currentCommandStart: null,
    purgeAt: null,
  },
];

describe('AgentDetailPage history offset URL state', () => {
  beforeEach(() => {
    replace.mockReset();
    useAgentMock.mockReturnValue({ data: writableAgent, isLoading: false });
  });

  it('falls back to offset 0 for a non-numeric offset param', () => {
    useProcessesMock.mockReturnValue({ data: { processes: [], total: 0 }, isLoading: false });
    renderWithUrl('offset=not-a-number');
    expect(useProcessesMock).toHaveBeenCalledWith(expect.objectContaining({ offset: 0 }));
  });

  it('falls back to offset 0 for a negative offset param', () => {
    useProcessesMock.mockReturnValue({ data: { processes: [], total: 0 }, isLoading: false });
    renderWithUrl('offset=-5');
    expect(useProcessesMock).toHaveBeenCalledWith(expect.objectContaining({ offset: 0 }));
  });

  it('falls back to offset 0 when the offset param is absent', () => {
    useProcessesMock.mockReturnValue({ data: { processes: [], total: 0 }, isLoading: false });
    renderWithUrl('');
    expect(useProcessesMock).toHaveBeenCalledWith(expect.objectContaining({ offset: 0 }));
  });

  it('Next writes the offset param', async () => {
    const user = userEvent.setup();
    useProcessesMock.mockReturnValue({ data: { processes: oneProcess, total: 200 }, isLoading: false });
    renderWithUrl('');
    await user.click(screen.getByRole('button', { name: /next/i }));
    expect(replace).toHaveBeenCalledWith('/agents/demo?offset=50', { scroll: false });
  });

  it('Previous deletes the offset param when it lands back on the first page', async () => {
    const user = userEvent.setup();
    useProcessesMock.mockReturnValue({ data: { processes: oneProcess, total: 200 }, isLoading: false });
    renderWithUrl('offset=50');
    await user.click(screen.getByRole('button', { name: /previous/i }));
    expect(replace).toHaveBeenCalledWith('/agents/demo', { scroll: false });
  });
});

describe('AgentDetailPage states and sections', () => {
  beforeEach(() => {
    mockCan(() => false);
    useAgentMock.mockReturnValue({ data: writableAgent, isLoading: false });
    useProcessesMock.mockReturnValue({ data: { processes: [], total: 0 }, isLoading: false });
  });

  it('shows a loading line while the agent loads', () => {
    useAgentMock.mockReturnValue({ data: undefined, isLoading: true });
    renderWithUrl('');
    expect(screen.getByText('Loading agent...')).toBeInTheDocument();
  });

  it('shows not-found with a link back when there is no such agent', () => {
    useAgentMock.mockReturnValue({ data: undefined, isLoading: false });
    renderWithUrl('');
    expect(screen.getByRole('heading', { name: 'Agent not found' })).toBeInTheDocument();
    expect(screen.getByText('demo', { selector: 'code' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to agents' })).toHaveAttribute('href', '/agents');
  });

  it('shows the scheduled badge, the description and every declared input', () => {
    useAgentMock.mockReturnValue({
      data: {
        ...writableAgent,
        description: 'Builds the thing',
        cron: { expression: '0 * * * *', timezone: 'UTC' },
        inputs: [
          { paramName: 'branch', envName: 'BRANCH', src: 'query', type: 'string', required: true, transformer: { type: 'pass' } },
          { paramName: 'payload', envName: 'PAYLOAD', src: 'body', type: 'ANY', required: false, transformer: { type: 'jsonPath', path: '$.x' } },
        ],
      },
      isLoading: false,
    });
    renderWithUrl('');
    expect(screen.getByText('Scheduled')).toBeInTheDocument();
    expect(screen.getByText('Builds the thing')).toBeInTheDocument();
    expect(screen.getByText('Parameters')).toBeInTheDocument();
    expect(screen.getByText('branch')).toBeInTheDocument();
    expect(screen.getByText('Env: BRANCH')).toBeInTheDocument();
    expect(screen.getByText('Direct pass-through')).toBeInTheDocument();
    expect(screen.getByText('Env: PAYLOAD')).toBeInTheDocument();
    expect(screen.getByText('Extract JSON path: $.x')).toBeInTheDocument();
    expect(screen.getAllByText('required')).toHaveLength(1);
  });

  it('omits the badge, description and parameters card when the agent has none', () => {
    renderWithUrl('');
    expect(screen.queryByText('Scheduled')).not.toBeInTheDocument();
    expect(screen.queryByText('Parameters')).not.toBeInTheDocument();
  });

  it('shows a loading line while the run history loads and an empty note once it has none', () => {
    useProcessesMock.mockReturnValue({ data: undefined, isLoading: true });
    const { unmount } = renderWithUrl('');
    expect(screen.getByText('Loading...')).toBeInTheDocument();
    expect(screen.queryByText('No runs yet.')).not.toBeInTheDocument();
    unmount();

    useProcessesMock.mockReturnValue({ data: { processes: [], total: 0 }, isLoading: false });
    renderWithUrl('');
    expect(screen.queryByText('Loading...')).not.toBeInTheDocument();
    expect(screen.getByText('No runs yet.')).toBeInTheDocument();
  });

  it('shows the page range and disables Previous on the first page and Next on the last', () => {
    useProcessesMock.mockReturnValue({ data: { processes: oneProcess, total: 30 }, isLoading: false });
    renderWithUrl('');
    expect(screen.getByText('Showing 1 to 30 of 30')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /previous/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /next/i })).toBeDisabled();
  });
});
