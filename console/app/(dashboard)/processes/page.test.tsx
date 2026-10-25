import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ProcessesPage from './page';
import type { ProcessInfo } from '@/types/homer';
import { HomerApiError } from '@/lib/api/client';

// Children that pull query hooks / rendering irrelevant to the URL-state
// behavior under test. ProcessTable stands in for the real one, rendering the
// Flow render slot it is handed so the page's wiring of it stays observable.
vi.mock('@/components/processes/process-table', () => ({
  ProcessTable: ({
    processes,
    flowSummary,
  }: {
    processes: ProcessInfo[];
    flowSummary?: (p: ProcessInfo) => React.ReactNode;
  }) => (
    <ul>
      {processes.map((p) => (
        <li key={p.id}>{flowSummary?.(p)}</li>
      ))}
    </ul>
  ),
}));
vi.mock('@/components/processes/sweep-status-banner', () => ({
  SweepStatusBanner: () => null,
}));
const useProcessesMock = vi.hoisted(() => vi.fn());
const useFlowSummaryMock = vi.hoisted(() => vi.fn());
const useAgentsMock = vi.hoisted(() => vi.fn());

vi.mock('@/lib/api/queries', () => ({
  useProcesses: useProcessesMock,
  useFlowSummary: useFlowSummaryMock,
  useAgents: useAgentsMock,
}));

const replace = vi.hoisted(() => vi.fn());
const searchParamsMock = vi.hoisted(() => vi.fn());

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace }),
  usePathname: () => '/processes',
  useSearchParams: () => searchParamsMock(),
}));

const processes: ProcessInfo[] = [];

function renderWithUrl(query: string) {
  searchParamsMock.mockReturnValue(new URLSearchParams(query));
  return render(<ProcessesPage />);
}

describe('ProcessesPage URL state', () => {
  beforeEach(() => {
    replace.mockReset();
    useProcessesMock.mockReset();
    useProcessesMock.mockReturnValue({
      data: { processes, total: 0 },
      isLoading: false,
      error: null,
    });
    useAgentsMock.mockReturnValue({ data: { agents: [] } });
    useFlowSummaryMock.mockReset();
    useFlowSummaryMock.mockReturnValue({ data: undefined });
  });

  it('falls back to no status filter for a junk status param', () => {
    renderWithUrl('status=BOGUS,ALSO_BOGUS');
    expect(useProcessesMock).toHaveBeenCalledWith(
      expect.objectContaining({ types: undefined }),
    );
  });

  it('keeps only the recognized statuses out of a mixed junk status param', () => {
    renderWithUrl('status=BOGUS,FAILED');
    expect(useProcessesMock).toHaveBeenCalledWith(
      expect.objectContaining({ types: ['FAILED'] }),
    );
  });

  it('falls back to offset 0 for a non-numeric offset param', () => {
    renderWithUrl('offset=not-a-number');
    expect(useProcessesMock).toHaveBeenCalledWith(expect.objectContaining({ offset: 0 }));
  });

  it('falls back to offset 0 for a negative offset param', () => {
    renderWithUrl('offset=-5');
    expect(useProcessesMock).toHaveBeenCalledWith(expect.objectContaining({ offset: 0 }));
  });

  it('forwards a parent param as the parentProcessId filter and shows the chip', () => {
    renderWithUrl('parent=7');
    expect(useProcessesMock).toHaveBeenCalledWith(expect.objectContaining({ parentProcessId: 7 }));
    expect(screen.getByText('Children of')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '#7' })).toHaveAttribute('href', '/processes/7');
  });

  it('ignores a junk or non-positive parent param', () => {
    renderWithUrl('parent=abc');
    expect(useProcessesMock).toHaveBeenCalledWith(expect.objectContaining({ parentProcessId: undefined }));
    renderWithUrl('parent=0');
    expect(useProcessesMock).toHaveBeenLastCalledWith(expect.objectContaining({ parentProcessId: undefined }));
    expect(screen.queryByText('Children of')).not.toBeInTheDocument();
  });

  it('clearing the parent chip drops parent and offset from the URL', async () => {
    const user = userEvent.setup();
    renderWithUrl('parent=7&offset=50&status=FAILED');
    await user.click(screen.getByRole('button', { name: 'Clear parent filter' }));
    expect(replace).toHaveBeenCalledWith('/processes?status=FAILED', { scroll: false });
  });

  it('toggling a status writes the status param and clears offset', async () => {
    const user = userEvent.setup();
    renderWithUrl('offset=50');
    await user.click(screen.getByText('FAILED'));
    expect(replace).toHaveBeenCalledWith('/processes?status=FAILED', { scroll: false });
  });

  it('toggling an already-selected status off removes it from the status param', async () => {
    const user = userEvent.setup();
    renderWithUrl('status=FAILED,KILLED');
    await user.click(screen.getByText('FAILED'));
    expect(replace).toHaveBeenCalledWith('/processes?status=KILLED', { scroll: false });
  });

  it('forwards de-duplicated tags as the tags filter and shows a chip per tag', () => {
    renderWithUrl('tag=mr:15,mr:15,env:prod');
    expect(useProcessesMock).toHaveBeenCalledWith(
      expect.objectContaining({ tags: ['mr:15', 'env:prod'] }),
    );
    expect(screen.getByText('tag: mr:15')).toBeInTheDocument();
    expect(screen.getByText('tag: env:prod')).toBeInTheDocument();
  });

  it('passes no tags filter when the tag param is absent', () => {
    renderWithUrl('');
    expect(useProcessesMock).toHaveBeenCalledWith(expect.objectContaining({ tags: undefined }));
  });

  it('clearing a tag chip drops just that tag and resets offset', async () => {
    const user = userEvent.setup();
    renderWithUrl('tag=mr:15,env:prod&offset=50');
    await user.click(screen.getByRole('button', { name: 'Clear tag filter mr:15' }));
    expect(replace).toHaveBeenCalledWith('/processes?tag=env%3Aprod', { scroll: false });
  });

  it('clearFilters drops the tag param too', async () => {
    const user = userEvent.setup();
    renderWithUrl('tag=mr:15&order=asc');
    await user.click(screen.getByRole('button', { name: /clear filters/i }));
    expect(replace).toHaveBeenCalledWith('/processes?order=asc', { scroll: false });
  });

  it('reads order=asc back into the sort button label', () => {
    renderWithUrl('order=asc');
    expect(
      screen.getByRole('button', { name: /sort order: oldest first/i }),
    ).toHaveTextContent('Oldest first');
  });

  it('defaults to newest-first sort when the order param is absent or junk', () => {
    renderWithUrl('order=bogus');
    expect(
      screen.getByRole('button', { name: /sort order: newest first/i }),
    ).toHaveTextContent('Newest first');
  });

  it('toggling the sort order sets order=asc', async () => {
    const user = userEvent.setup();
    renderWithUrl('');
    await user.click(screen.getByRole('button', { name: /sort order/i }));
    expect(replace).toHaveBeenCalledWith('/processes?order=asc', { scroll: false });
  });

  it('toggling the sort order back from asc deletes the order param', async () => {
    const user = userEvent.setup();
    renderWithUrl('order=asc');
    await user.click(screen.getByRole('button', { name: /sort order/i }));
    expect(replace).toHaveBeenCalledWith('/processes', { scroll: false });
  });

  it('clearFilters clears status, agent and offset but keeps order', async () => {
    const user = userEvent.setup();
    renderWithUrl('status=FAILED&agent=my-agent&offset=50&order=asc');
    await user.click(screen.getByRole('button', { name: /clear filters/i }));
    expect(replace).toHaveBeenCalledWith('/processes?order=asc', { scroll: false });
  });

  it('pagination Next patches only offset', async () => {
    const user = userEvent.setup();
    useProcessesMock.mockReturnValue({
      data: { processes, total: 200 },
      isLoading: false,
      error: null,
    });
    renderWithUrl('status=FAILED');
    await user.click(screen.getByRole('button', { name: /next/i }));
    expect(replace).toHaveBeenCalledWith('/processes?status=FAILED&offset=50', {
      scroll: false,
    });
  });

  it('changing the agent filter writes the agent param and clears offset', async () => {
    const user = userEvent.setup();
    useAgentsMock.mockReturnValue({ data: { agents: [{ name: 'my-agent' }] } });
    renderWithUrl('offset=50');
    await user.click(screen.getByRole('combobox', { name: 'Filter by agent' }));
    await user.click(screen.getByRole('option', { name: 'my-agent' }));
    expect(replace).toHaveBeenCalledWith('/processes?agent=my-agent', { scroll: false });
  });

  it('selecting "All agents" clears the agent param', async () => {
    const user = userEvent.setup();
    useAgentsMock.mockReturnValue({ data: { agents: [{ name: 'my-agent' }] } });
    renderWithUrl('agent=my-agent');
    await user.click(screen.getByRole('combobox', { name: 'Filter by agent' }));
    await user.click(screen.getByRole('option', { name: 'All agents' }));
    expect(replace).toHaveBeenCalledWith('/processes', { scroll: false });
  });

  it('pagination Previous deletes offset when it lands back on the first page', async () => {
    const user = userEvent.setup();
    useProcessesMock.mockReturnValue({
      data: { processes, total: 200 },
      isLoading: false,
      error: null,
    });
    renderWithUrl('offset=50');
    await user.click(screen.getByRole('button', { name: /previous/i }));
    expect(replace).toHaveBeenCalledWith('/processes', { scroll: false });
  });

  describe('roots and all-runs views', () => {
    it('defaults to the all-runs view', () => {
      renderWithUrl('');
      expect(useProcessesMock).toHaveBeenCalledWith(expect.objectContaining({ roots: undefined }));
      expect(screen.getByRole('button', { name: 'All runs' })).toHaveAttribute(
        'aria-pressed',
        'true',
      );
    });

    it('applies the roots filter in the roots view', () => {
      renderWithUrl('view=roots');
      expect(useProcessesMock).toHaveBeenCalledWith(expect.objectContaining({ roots: true }));
    });

    it('treats a legacy view=all link as the all-runs view', () => {
      renderWithUrl('view=all');
      expect(useProcessesMock).toHaveBeenCalledWith(expect.objectContaining({ roots: undefined }));
    });

    it('drops the roots filter when a parent filter is set, even with view=roots', () => {
      renderWithUrl('view=roots&parent=7');
      expect(useProcessesMock).toHaveBeenCalledWith(expect.objectContaining({ roots: undefined }));
    });

    it('marks the roots view active and switches back to all runs', async () => {
      const user = userEvent.setup();
      renderWithUrl('status=FAILED&view=roots');
      expect(screen.getByRole('button', { name: 'Root runs' })).toHaveAttribute(
        'aria-pressed',
        'true',
      );
      expect(screen.getByRole('button', { name: 'All runs' })).toHaveAttribute(
        'aria-pressed',
        'false',
      );
      await user.click(screen.getByRole('button', { name: 'All runs' }));
      expect(replace).toHaveBeenCalledWith('/processes?status=FAILED', { scroll: false });
    });

    it('switching to root runs drops the parent filter and offset', async () => {
      const user = userEvent.setup();
      renderWithUrl('parent=7&offset=50');
      await user.click(screen.getByRole('button', { name: 'Root runs' }));
      expect(replace).toHaveBeenCalledWith('/processes?view=roots', { scroll: false });
    });

    it('keeps the parent chip clearable in the default all-runs view', () => {
      renderWithUrl('parent=7');
      expect(screen.getByRole('button', { name: 'Clear parent filter' })).toBeInTheDocument();
    });

    it('renders each root row\'s flow count, cost and aggregate state', () => {
      useProcessesMock.mockReturnValue({
        data: { processes: [{ id: 7 } as ProcessInfo], total: 1 },
        isLoading: false,
        error: null,
      });
      useFlowSummaryMock.mockImplementation((rootProcessId: number) =>
        rootProcessId === 7
          ? {
              data: {
                processes: [
                  { id: 8, status: 'FINISHED', costUsd: 0.5 } as ProcessInfo,
                  { id: 9, status: 'WORKING', costUsd: 1.25 } as ProcessInfo,
                ],
                total: 2,
              },
            }
          : { data: undefined },
      );
      renderWithUrl('view=roots');

      // The cell is one span of count / cost / state fragments; `getByText`
      // lands on the count's own inner span, so read its wrapper.
      const cell = screen.getByText('2 runs').parentElement as HTMLElement;
      expect(cell).toHaveTextContent('2 runs');
      expect(cell).toHaveTextContent('$1.7500');
      expect(cell).toHaveTextContent('running');
    });

    it('shows no flow column at all in the all-runs view', () => {
      useProcessesMock.mockReturnValue({
        data: { processes: [{ id: 7 } as ProcessInfo], total: 1 },
        isLoading: false,
        error: null,
      });
      renderWithUrl('');
      // Only the page's own list query ran — no per-row flow query followed.
      expect(useProcessesMock).toHaveBeenCalledTimes(1);
      expect(useFlowSummaryMock).not.toHaveBeenCalled();
    });

    it('summarizes only the first capped rows of a root page', () => {
      useProcessesMock.mockReturnValue({
        data: {
          processes: Array.from({ length: 21 }, (_, i) => ({ id: i + 1 }) as ProcessInfo),
          total: 21,
        },
        isLoading: false,
        error: null,
      });
      useFlowSummaryMock.mockReturnValue({ data: { processes: [], total: 0 } });
      renderWithUrl('view=roots');
      // 20 capped rows, each one flow summary query, beside the page's list.
      expect(useFlowSummaryMock).toHaveBeenCalledTimes(20);
      // Past the cap the row shows a dash rather than firing a request.
      expect(screen.getAllByText('-')).toHaveLength(1);
    });
  });

  it('shows the sum of the current page\'s costUsd, not a cross-page total', () => {
    useProcessesMock.mockReturnValue({
      data: {
        processes: [
          { id: 1, costUsd: 1.5 } as ProcessInfo,
          { id: 2, costUsd: 0.25 } as ProcessInfo,
          { id: 3 } as ProcessInfo,
        ],
        total: 200,
      },
      isLoading: false,
      error: null,
    });
    renderWithUrl('view=roots');
    // Scoped to the page-total span: each root's Flow cell sums the same runs,
    // so a bare text query would match those too.
    expect(screen.getByText('Total cost:').parentElement).toHaveTextContent('$1.7500');
  });

  it('says the API cannot be reached when the list request gets no response', () => {
    useProcessesMock.mockReturnValue({ data: undefined, isLoading: false, error: new HomerApiError('Failed to fetch') });
    renderWithUrl('');
    expect(screen.getByRole('alert')).toHaveTextContent(
      /Failed to load processes: Cannot reach the Homer API at .* corsAllowedOrigins allows this console's origin\./,
    );
  });
});
