import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { toArtifactApiPath } from '@/lib/utils/download';
import { HomerApiError } from '@/lib/api/client';
import ProcessDetailPage from './page';
import type { ProcessInfo } from '@/types/homer';

// Children that pull query hooks / auth context / Radix state irrelevant
// to the execution-ordering behavior under test.
vi.mock('@/components/questions/question-panel', () => ({
  QuestionPanel: () => null,
}));
vi.mock('@/components/messages/run-message-panel', () => ({
  RunMessagePanel: ({ process }: { process: { id: number; status: string } }) => (
    <div data-testid="run-message-panel">{`${process.id}:${process.status}`}</div>
  ),
}));
vi.mock('@/components/processes/process-actions', () => ({
  ProcessActions: () => null,
}));
// Captures the picker's onSelect so a test can pick an artifact without the dialog.
const pickerSelect = vi.hoisted(() => ({ current: null as ((path: string | null) => void) | null }));
vi.mock('@/components/processes/artifact-picker-dialog', () => ({
  ArtifactPickerDialog: ({ onSelect }: { onSelect: (path: string | null) => void }) => {
    pickerSelect.current = onSelect;
    return null;
  },
}));

const useProcessMock = vi.hoisted(() => vi.fn());
const downloadMock = vi.hoisted(() => vi.fn());
const useProcessesMock = vi.hoisted(() => vi.fn());
// The Flow section's own query, so a perhaps-deep subtree is not queried here.
const flowUseProcessesMock = vi.hoisted(() => vi.fn());

vi.mock('@/components/processes/flow-section', () => ({
  FlowSection: ({ processId, rootAgentName }: { processId: number; rootAgentName: string }) => {
    const { data } = flowUseProcessesMock({ rootProcessId: processId, order: 'asc' });
    return (
      <section aria-label="Flow section">
        Flow of {processId} as {rootAgentName}
        {data ? `: ${data.total} runs` : ''}
      </section>
    );
  },
}));

vi.mock('@/lib/api/queries', () => ({
  useProcess: useProcessMock,
  useProcesses: useProcessesMock,
  useKillProcess: () => ({ mutateAsync: vi.fn() }),
  useRetryProcess: () => ({
    mutateAsync: vi.fn(),
    isPending: false,
    isError: false,
    error: null,
  }),
  useResumeProcess: () => ({
    mutateAsync: vi.fn(),
    isPending: false,
    isError: false,
    error: null,
  }),
  useProcessLangGraph: () => ({ data: null }),
  useDownloadArtifact: () => ({ mutateAsync: downloadMock, isPending: false }),
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

vi.mock('@/lib/hooks/use-now', () => ({
  useNow: () => 1_700_000_100,
}));

// React's `use()` always suspends on a plain Promise, even an already-settled
// one. Next.js's real params promise pre-attaches the {status, value}
// thenable-cache fields React's `use()` reads synchronously. Mirror that here
// so the page renders in one synchronous pass.
function fulfilledParams(id: string): Promise<{ id: string }> {
  const p = Promise.resolve({ id }) as Promise<{ id: string }> & {
    status?: string;
    value?: { id: string };
  };
  p.status = 'fulfilled';
  p.value = { id };
  return p;
}

const runningProcess: ProcessInfo = {
  id: 7,
  status: 'WORKING',
  agentName: 'demo',
  history: [{ timestamp: 1_700_000_000, status: 'WORKING' }],
  executions: [
    {
      label: 'first-cmd',
      start: 1_700_000_000,
      end: 1_700_000_010,
      resultCode: 0,
      stdOut: null,
      stdErr: null,
      errMsg: null,
    },
    {
      label: 'second-cmd',
      start: 1_700_000_010,
      end: 1_700_000_020,
      resultCode: 0,
      stdOut: null,
      stdErr: null,
      errMsg: null,
    },
  ],
  currentCommand: 'third-cmd',
  currentCommandStart: 1_700_000_020,
  purgeAt: null,
};

describe('ProcessDetailPage execution ordering', () => {
  beforeEach(() => {
    useProcessMock.mockReturnValue({ data: runningProcess });
    useProcessesMock.mockReturnValue({ data: { processes: [], total: 0, offset: 0, limit: 20 } });
    flowUseProcessesMock.mockReset();
    flowUseProcessesMock.mockReturnValue({ data: undefined });
  });

  it('renders executions in run order with the running command last', () => {
    render(<ProcessDetailPage params={fulfilledParams('7')} />);

    const first = screen.getByText('first-cmd');
    const second = screen.getByText('second-cmd');
    const running = screen.getByText('third-cmd');

    expect(
      first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      second.compareDocumentPosition(running) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(screen.getByText('Running...')).toBeInTheDocument();
  });

  it('renders the message panel with the run\'s id and status', () => {
    render(<ProcessDetailPage params={fulfilledParams('7')} />);
    expect(screen.getByTestId('run-message-panel')).toHaveTextContent(`7:${runningProcess.status}`);
  });

  it('shows only the running command box when nothing has finished yet', () => {
    useProcessMock.mockReturnValue({
      data: { ...runningProcess, executions: [] },
    });
    render(<ProcessDetailPage params={fulfilledParams('7')} />);

    expect(screen.getByText('third-cmd')).toBeInTheDocument();
    expect(screen.queryByText('No executions yet')).not.toBeInTheDocument();
  });

  describe('run cost and Langfuse trace link', () => {
    it('shows the billed cost from the status API', () => {
      useProcessMock.mockReturnValue({
        data: { ...runningProcess, costUsd: 0.1234 },
      });
      render(<ProcessDetailPage params={fulfilledParams('7')} />);
      expect(screen.getByText('$0.1234')).toBeInTheDocument();
    });

    it('omits the cost when the run never billed anything', () => {
      useProcessMock.mockReturnValue({ data: { ...runningProcess, costUsd: 0 } });
      render(<ProcessDetailPage params={fulfilledParams('7')} />);
      expect(screen.queryByText(/^\$/)).not.toBeInTheDocument();
    });

    it('links to the Langfuse trace when the server supplied a URL', () => {
      useProcessMock.mockReturnValue({
        data: { ...runningProcess, langfuseTraceUrl: 'https://lf.test/project/p1/traces/abc' },
      });
      render(<ProcessDetailPage params={fulfilledParams('7')} />);
      const link = screen.getByRole('link', { name: /langfuse trace/i });
      expect(link).toHaveAttribute('href', 'https://lf.test/project/p1/traces/abc');
    });

    it('omits the trace link when the server did not supply one', () => {
      useProcessMock.mockReturnValue({ data: { ...runningProcess, langfuseTraceUrl: null } });
      render(<ProcessDetailPage params={fulfilledParams('7')} />);
      expect(screen.queryByRole('link', { name: /langfuse trace/i })).not.toBeInTheDocument();
    });

    it('labels a proxy-priced cost', () => {
      useProcessMock.mockReturnValue({ data: { ...runningProcess, costUsd: 0.18, costSource: 'session' } });
      render(<ProcessDetailPage params={fulfilledParams('7')} />);
      expect(screen.getByText('proxy')).toBeInTheDocument();
    });

    it('labels a CLI-estimated cost and explains it', () => {
      useProcessMock.mockReturnValue({ data: { ...runningProcess, costUsd: 0.18, costSource: 'cli' } });
      render(<ProcessDetailPage params={fulfilledParams('7')} />);
      const label = screen.getByText('CLI estimate');
      expect(label).toHaveAttribute('title', expect.stringMatching(/Claude CLI/));
    });

    it('labels a mixed-source cost', () => {
      useProcessMock.mockReturnValue({ data: { ...runningProcess, costUsd: 0.18, costSource: 'mixed' } });
      render(<ProcessDetailPage params={fulfilledParams('7')} />);
      expect(screen.getByText('mixed')).toBeInTheDocument();
    });

    it('treats a missing costSource as a CLI estimate', () => {
      useProcessMock.mockReturnValue({ data: { ...runningProcess, costUsd: 0.18 } });
      render(<ProcessDetailPage params={fulfilledParams('7')} />);
      expect(screen.getByText('CLI estimate')).toBeInTheDocument();
    });
  });

  describe('parent and child runs', () => {
    it('links to the parent run when the process has one', () => {
      useProcessMock.mockReturnValue({ data: { ...runningProcess, parentProcessId: 3 } });
      render(<ProcessDetailPage params={fulfilledParams('7')} />);
      expect(screen.getByRole('link', { name: 'Parent run #3' })).toHaveAttribute('href', '/processes/3');
    });

    it('shows no parent link at the root of a flow', () => {
      useProcessMock.mockReturnValue({ data: { ...runningProcess, parentProcessId: null } });
      render(<ProcessDetailPage params={fulfilledParams('7')} />);
      expect(screen.queryByRole('link', { name: /parent run/i })).not.toBeInTheDocument();
    });

    it('queries the direct children of this run', () => {
      render(<ProcessDetailPage params={fulfilledParams('7')} />);
      expect(useProcessesMock).toHaveBeenCalledWith(expect.objectContaining({ parentProcessId: 7 }), true);
    });

    it('does not query children for a non-numeric route id', () => {
      useProcessMock.mockReturnValue({ data: undefined });
      render(<ProcessDetailPage params={fulfilledParams('abc')} />);
      expect(useProcessesMock).toHaveBeenCalledWith(expect.objectContaining({ parentProcessId: NaN }), false);
    });

    it('lists child runs with links and offers the full list when truncated', () => {
      useProcessMock.mockReturnValue({ data: { ...runningProcess, rootProcessId: 3 } });
      useProcessesMock.mockReturnValue({
        data: {
          processes: [
            { ...runningProcess, id: 8, agentName: 'child-a', status: 'FINISHED', parentProcessId: 7 },
            { ...runningProcess, id: 9, agentName: 'child-b', status: 'WORKING', parentProcessId: 7 },
          ],
          total: 25,
          offset: 0,
          limit: 20,
        },
      });
      render(<ProcessDetailPage params={fulfilledParams('7')} />);

      expect(screen.getByText('Child runs')).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'Open child process 8 details' })).toHaveAttribute('href', '/processes/8');
      expect(screen.getByText('child-b')).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'View all 25 child runs' })).toHaveAttribute(
        'href',
        '/processes?parent=7',
      );
    });

    it('omits the child runs card when the run has no children', () => {
      render(<ProcessDetailPage params={fulfilledParams('7')} />);
      expect(screen.queryByText('Child runs')).not.toBeInTheDocument();
    });
  });

  describe('flow view', () => {
    it('shows the Flow section for a root instead of the capped children card', () => {
      useProcessMock.mockReturnValue({
        data: { ...runningProcess, parentProcessId: null, rootProcessId: null },
      });
      useProcessesMock.mockReturnValue({
        data: {
          processes: [{ ...runningProcess, id: 8, parentProcessId: 7 }],
          total: 25,
          offset: 0,
          limit: 20,
        },
      });
      render(<ProcessDetailPage params={fulfilledParams('7')} />);

      expect(screen.getByLabelText('Flow section')).toHaveTextContent('Flow of 7 as demo');
      expect(screen.queryByText('Child runs')).not.toBeInTheDocument();
      expect(screen.queryByRole('link', { name: 'View all 25 child runs' })).not.toBeInTheDocument();
    });

    it('queries the whole flow for a root', () => {
      useProcessMock.mockReturnValue({
        data: { ...runningProcess, parentProcessId: null, rootProcessId: null },
      });
      render(<ProcessDetailPage params={fulfilledParams('7')} />);
      expect(flowUseProcessesMock).toHaveBeenCalledWith({ rootProcessId: 7, order: 'asc' });
    });

    it('shows the children card, not the Flow section, for a descendant', () => {
      useProcessMock.mockReturnValue({ data: { ...runningProcess, rootProcessId: 3 } });
      useProcessesMock.mockReturnValue({
        data: {
          processes: [{ ...runningProcess, id: 8, parentProcessId: 7 }],
          total: 1,
          offset: 0,
          limit: 20,
        },
      });
      render(<ProcessDetailPage params={fulfilledParams('7')} />);

      expect(screen.getByText('Child runs')).toBeInTheDocument();
      expect(screen.queryByLabelText('Flow section')).not.toBeInTheDocument();
    });

    it('links to the root run when this run is a descendant', () => {
      useProcessMock.mockReturnValue({
        data: { ...runningProcess, parentProcessId: 3, rootProcessId: 3 },
      });
      render(<ProcessDetailPage params={fulfilledParams('7')} />);
      expect(screen.getByRole('link', { name: 'Root run #3' })).toHaveAttribute(
        'href',
        '/processes/3',
      );
    });

    it('shows no root link on the root itself', () => {
      useProcessMock.mockReturnValue({
        data: { ...runningProcess, parentProcessId: null, rootProcessId: null },
      });
      render(<ProcessDetailPage params={fulfilledParams('7')} />);
      expect(screen.queryByRole('link', { name: /root run/i })).not.toBeInTheDocument();
    });
  });

  describe('agent name link', () => {
    it('links the header agent name to its agent detail page', () => {
      useProcessMock.mockReturnValue({ data: { ...runningProcess, agentName: 'triage-decision' } });
      render(<ProcessDetailPage params={fulfilledParams('7')} />);
      expect(screen.getByRole('link', { name: 'triage-decision' })).toHaveAttribute(
        'href',
        '/agents/triage-decision',
      );
    });

    it('encodes agent names that need it in the link target', () => {
      useProcessMock.mockReturnValue({ data: { ...runningProcess, agentName: 'team/reviewer v2' } });
      render(<ProcessDetailPage params={fulfilledParams('7')} />);
      expect(screen.getByRole('link', { name: 'team/reviewer v2' })).toHaveAttribute(
        'href',
        '/agents/team%2Freviewer%20v2',
      );
    });
  });

});

describe('ProcessDetailPage artifact download', () => {
  beforeEach(() => {
    useProcessMock.mockReturnValue({ data: runningProcess });
    useProcessesMock.mockReturnValue({ data: { processes: [], total: 0, offset: 0, limit: 20 } });
    flowUseProcessesMock.mockReturnValue({ data: undefined });
    downloadMock.mockReset();
  });

  it('downloads the picked artifact through its API path', async () => {
    downloadMock.mockResolvedValue(undefined);
    render(<ProcessDetailPage params={fulfilledParams('7')} />);
    await act(async () => {
      pickerSelect.current?.('out/report.txt');
    });
    expect(downloadMock).toHaveBeenCalledWith({
      processId: 7,
      artifactPath: toArtifactApiPath('out/report.txt'),
    });
  });

  it('ignores an empty pick and logs a failed download', async () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    downloadMock.mockRejectedValue(new Error('boom'));
    render(<ProcessDetailPage params={fulfilledParams('7')} />);
    await act(async () => {
      pickerSelect.current?.(null);
    });
    expect(downloadMock).not.toHaveBeenCalled();
    await act(async () => {
      pickerSelect.current?.('cmd_0.out');
    });
    expect(error).toHaveBeenCalledWith('Download failed:', expect.any(Error));
    error.mockRestore();
  });
});

describe('ProcessDetailPage before the process loads', () => {
  beforeEach(() => {
    useProcessesMock.mockReturnValue({ data: { processes: [], total: 0, offset: 0, limit: 20 } });
    flowUseProcessesMock.mockReturnValue({ data: undefined });
  });

  it('shows the loading line while the query is pending', () => {
    useProcessMock.mockReturnValue({ data: undefined, error: null });
    render(<ProcessDetailPage params={fulfilledParams('7')} />);
    expect(screen.getByText('Loading process details...')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it.each([
    ['an auth rejection', new HomerApiError('x', 401, 'Unauthorized'), /The API rejected the request — sign in/],
    ['an unreachable or CORS-blocked API', Object.assign(new HomerApiError('Failed to fetch'), { failure: 'unreachable' as const }), /Cannot reach the Homer API at .* corsAllowedOrigins/],
    ['a missing process', new HomerApiError('x', 404, 'Not Found'), /Process 7 does not exist, or you cannot see it\./],
    ['a server error', new HomerApiError('x', 500, 'Internal Server Error'), /HTTP 500 Internal Server Error/],
  ])('shows why instead of loading forever on %s', (_case, error, message) => {
    useProcessMock.mockReturnValue({ data: undefined, error });
    render(<ProcessDetailPage params={fulfilledParams('7')} />);
    expect(screen.getByRole('alert')).toHaveTextContent(message);
    expect(screen.queryByText('Loading process details...')).not.toBeInTheDocument();
  });

  it('keeps showing a loaded process when a later refetch fails', () => {
    useProcessMock.mockReturnValue({ data: runningProcess, error: new HomerApiError('Failed to fetch') });
    render(<ProcessDetailPage params={fulfilledParams('7')} />);
    expect(screen.getByText('first-cmd')).toBeInTheDocument();
    expect(screen.queryByText(/Cannot reach the Homer API/)).not.toBeInTheDocument();
  });
});
