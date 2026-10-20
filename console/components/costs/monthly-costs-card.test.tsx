import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MonthlyCostsCard } from './monthly-costs-card';
import type { MonthlyCostsResponse } from '@/types/homer';

vi.mock('@/lib/api/queries', () => ({
  useMonthlyCosts: vi.fn(),
}));

import { useMonthlyCosts } from '@/lib/api/queries';

type HookReturn = {
  data: MonthlyCostsResponse | undefined;
  isLoading: boolean;
  error: Error | null;
};

function setMock(value: HookReturn) {
  vi.mocked(useMonthlyCosts).mockReturnValue(
    value as ReturnType<typeof useMonthlyCosts>,
  );
}

const july: MonthlyCostsResponse = {
  month: '2026-07',
  perAgentUsd: { reviewer: 2.0, planner: 0.5 },
  totalUsd: 2.5,
  availableMonths: ['2026-07', '2026-06'],
};

describe('MonthlyCostsCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows the loading state while the query is pending', () => {
    setMock({ data: undefined, isLoading: true, error: null });
    render(<MonthlyCostsCard />);
    expect(screen.getByText(/loading monthly costs/i)).toBeInTheDocument();
  });

  it('shows an error message when the query fails', () => {
    setMock({ data: undefined, isLoading: false, error: new Error('boom') });
    render(<MonthlyCostsCard />);
    expect(
      screen.getByText(/failed to load monthly costs: boom/i),
    ).toBeInTheDocument();
  });

  it('renders a row per agent plus a total row', () => {
    setMock({ data: july, isLoading: false, error: null });
    render(<MonthlyCostsCard />);
    expect(screen.getByText('reviewer')).toBeInTheDocument();
    expect(screen.getByText('$2.0000')).toBeInTheDocument();
    expect(screen.getByText('planner')).toBeInTheDocument();
    expect(screen.getByText('$0.5000')).toBeInTheDocument();
    expect(screen.getByText('Total')).toBeInTheDocument();
    expect(screen.getByText('$2.5000')).toBeInTheDocument();
  });

  it('shows an empty state for a month without entries', () => {
    setMock({
      data: { month: '2026-05', perAgentUsd: {}, totalUsd: 0, availableMonths: ['2026-07'] },
      isLoading: false,
      error: null,
    });
    render(<MonthlyCostsCard />);
    expect(
      screen.getByText(/no costs recorded for this month/i),
    ).toBeInTheDocument();
  });

  it('re-queries with the picked month when the selector changes', () => {
    setMock({ data: july, isLoading: false, error: null });
    render(<MonthlyCostsCard />);

    fireEvent.change(screen.getByLabelText(/select month/i), {
      target: { value: '2026-06' },
    });

    // Last render must have asked the hook for the picked month.
    expect(vi.mocked(useMonthlyCosts)).toHaveBeenLastCalledWith('2026-06');
  });

  it('offers every available month in the picker', () => {
    setMock({ data: july, isLoading: false, error: null });
    render(<MonthlyCostsCard />);
    const options = screen
      .getAllByRole('option')
      .map((o) => (o as HTMLOptionElement).value);
    expect(options).toEqual(['2026-07', '2026-06']);
  });
});
