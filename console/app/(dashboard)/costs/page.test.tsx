import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import CostsPage from './page';
import type { CostsResponse } from '@/types/homer';

vi.mock('@/lib/api/queries', () => ({
  useCosts: vi.fn(),
  useMonthlyCosts: vi.fn(),
  useTotalCosts: vi.fn(),
  useCostsByTag: vi.fn(),
}));

import { useCosts, useMonthlyCosts, useTotalCosts, useCostsByTag } from '@/lib/api/queries';

vi.mock('@/lib/auth/use-can', () => ({ useCan: vi.fn() }));
import { useCan } from '@/lib/auth/use-can';
import { fakeCapabilities } from '@/lib/auth/capabilities-test-utils';

function mockCan(isAdmin: boolean) {
  vi.mocked(useCan).mockReturnValue(fakeCapabilities(isAdmin));
}

type UseCostsReturn = {
  data: CostsResponse | undefined;
  isLoading: boolean;
  error: Error | null;
};

function setMock(value: UseCostsReturn) {
  vi.mocked(useCosts).mockReturnValue(value as ReturnType<typeof useCosts>);
}

describe('CostsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockCan(true);
    vi.mocked(useMonthlyCosts).mockReturnValue({
      data: undefined,
      isLoading: true,
      error: null,
    } as ReturnType<typeof useMonthlyCosts>);
    vi.mocked(useTotalCosts).mockReturnValue({
      data: undefined,
      isLoading: true,
      error: null,
    } as ReturnType<typeof useTotalCosts>);
    vi.mocked(useCostsByTag).mockReturnValue({
      data: undefined,
      isLoading: false,
      error: null,
    } as ReturnType<typeof useCostsByTag>);
  });

  it('shows the loading state while the query is pending', () => {
    setMock({ data: undefined, isLoading: true, error: null });
    render(<CostsPage />);
    expect(screen.getByText(/loading costs/i)).toBeInTheDocument();
  });

  it('shows an error banner with the message when the query fails', () => {
    setMock({
      data: undefined,
      isLoading: false,
      error: new Error('boom'),
    });
    render(<CostsPage />);
    expect(screen.getByText(/failed to load costs: boom/i)).toBeInTheDocument();
  });

  it('renders global spend and a row per agent when data is available', () => {
    setMock({
      data: {
        globalUsdLast60s: 1.5,
        globalCapUsdPerMin: 5,
        perAgentUsdLast24h: { 'reviewer': 0.25, 'planner': 1.0 },
        perAgentCapUsdPerDay: { 'reviewer': 10, 'planner': 5 },
        snapshotAtSec: 1_700_000_000,
        costTrackedTagKeys: [],
      },
      isLoading: false,
      error: null,
    });
    render(<CostsPage />);

    expect(screen.getByText(/global spend \(last 60 s\)/i)).toBeInTheDocument();
    expect(screen.getByText('$1.5000 / $5.0000')).toBeInTheDocument();

    expect(screen.getByText('reviewer')).toBeInTheDocument();
    expect(screen.getByText('planner')).toBeInTheDocument();
    expect(screen.getByText('$0.2500')).toBeInTheDocument();
    expect(screen.getByText('$1.0000')).toBeInTheDocument();
  });

  it('sums the displayed per-agent spend into a bold Total row', () => {
    setMock({
      data: {
        globalUsdLast60s: 1.5,
        globalCapUsdPerMin: 5,
        perAgentUsdLast24h: { 'reviewer': 0.25, 'planner': 1.0 },
        perAgentCapUsdPerDay: { 'reviewer': 10, 'planner': 5 },
        snapshotAtSec: 1_700_000_000,
        costTrackedTagKeys: [],
      },
      isLoading: false,
      error: null,
    });
    render(<CostsPage />);

    const table = screen.getByRole('row', { name: /reviewer/i }).closest('table');
    const totalRow = within(table as HTMLElement).getByRole('row', {
      name: /total/i,
    });

    // $0.2500 + $1.0000
    expect(within(totalRow).getByText('$1.2500')).toBeInTheDocument();

    const label = within(totalRow).getByText('Total');
    expect(label).toHaveClass('font-semibold');
    expect(within(totalRow).getByText('$1.2500')).toHaveClass('font-semibold');

    // No global daily cap exists, so Cap is a dash and Usage stays empty.
    expect(within(totalRow).getByText('—')).toBeInTheDocument();
    expect(within(totalRow).queryByRole('progressbar')).toBeNull();

    // The total is the last row, after every agent row.
    expect(totalRow).toBe(table?.lastElementChild?.lastElementChild);
  });

  it('rounds each row before summing, so Total matches the displayed rows', () => {
    setMock({
      data: {
        globalUsdLast60s: 0,
        globalCapUsdPerMin: null,
        // Two sub-half-cent spends: each row shows $0.0000, so the Total must
        // show $0.0000 too rather than $0.0001.
        perAgentUsdLast24h: { 'reviewer': 0.000049, 'planner': 0.000049 },
        perAgentCapUsdPerDay: {},
        snapshotAtSec: 1_700_000_000,
        costTrackedTagKeys: [],
      },
      isLoading: false,
      error: null,
    });
    render(<CostsPage />);

    const table = screen.getByRole('row', { name: /reviewer/i }).closest('table');
    const totalRow = within(table as HTMLElement).getByRole('row', {
      name: /total/i,
    });

    // Rows show $0.0000 each; a raw sum would render $0.0001 here.
    expect(within(totalRow).getByText('$0.0000')).toBeInTheDocument();
  });

  it('shows a placeholder when no per-agent spend has been recorded', () => {
    setMock({
      data: {
        globalUsdLast60s: 0,
        globalCapUsdPerMin: null,
        perAgentUsdLast24h: {},
        perAgentCapUsdPerDay: {},
        snapshotAtSec: 1_700_000_000,
        costTrackedTagKeys: [],
      },
      isLoading: false,
      error: null,
    });
    render(<CostsPage />);
    expect(screen.getByText(/no agent spend recorded yet/i)).toBeInTheDocument();
    expect(screen.queryByRole('row', { name: /total/i })).toBeNull();
  });

  it('lists agents that have a cap configured but zero spend', () => {
    setMock({
      data: {
        globalUsdLast60s: 0,
        globalCapUsdPerMin: null,
        perAgentUsdLast24h: {},
        perAgentCapUsdPerDay: { 'reviewer': 10 },
        snapshotAtSec: 1_700_000_000,
        costTrackedTagKeys: [],
      },
      isLoading: false,
      error: null,
    });
    render(<CostsPage />);
    const row = screen.getByRole('row', { name: /reviewer/i });
    expect(within(row).getByText('$0.0000')).toBeInTheDocument();
    expect(within(row).getByText('$10.0000')).toBeInTheDocument();

    // A cap-only agent contributes 0, so the total stays $0.0000.
    const totalRow = screen.getByRole('row', { name: /total/i });
    expect(within(totalRow).getByText('$0.0000')).toBeInTheDocument();
  });

  it('renders the monthly and all-time cards alongside the snapshot', () => {
    setMock({ data: undefined, isLoading: true, error: null });
    render(<CostsPage />);
    expect(screen.getByText(/monthly costs per agent/i)).toBeInTheDocument();
    expect(screen.getByText(/all-time costs per agent/i)).toBeInTheDocument();
  });

  it('renders the cost-by-tag card when the config lists tracked keys', () => {
    setMock({
      data: {
        globalUsdLast60s: 0,
        globalCapUsdPerMin: null,
        perAgentUsdLast24h: {},
        perAgentCapUsdPerDay: {},
        snapshotAtSec: 1_700_000_000,
        costTrackedTagKeys: ['ticket_id'],
      },
      isLoading: false,
      error: null,
    });
    render(<CostsPage />);
    expect(screen.getByText(/cost by tag/i)).toBeInTheDocument();
  });

  it('omits the cost-by-tag card when no tag keys are tracked', () => {
    setMock({
      data: {
        globalUsdLast60s: 0,
        globalCapUsdPerMin: null,
        perAgentUsdLast24h: {},
        perAgentCapUsdPerDay: {},
        snapshotAtSec: 1_700_000_000,
        costTrackedTagKeys: [],
      },
      isLoading: false,
      error: null,
    });
    render(<CostsPage />);
    expect(screen.queryByText(/cost by tag/i)).toBeNull();
  });

  describe('admin guard', () => {
    beforeEach(() => {
      setMock({ data: undefined, isLoading: true, error: null });
    });

    it('shows a no-access message for a non-admin', () => {
      mockCan(false);
      render(<CostsPage />);
      expect(screen.getByText('You don’t have access to this page.')).toBeInTheDocument();
      expect(screen.queryByRole('heading', { name: 'Costs' })).toBeNull();
    });

    it('renders the costs content for an admin', () => {
      mockCan(true);
      render(<CostsPage />);
      expect(screen.getByRole('heading', { name: 'Costs' })).toBeInTheDocument();
    });
  });
});
