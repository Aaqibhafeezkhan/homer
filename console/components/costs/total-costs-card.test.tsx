import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { TotalCostsCard } from './total-costs-card';
import type { TotalCostsResponse } from '@/types/homer';

vi.mock('@/lib/api/queries', () => ({
  useTotalCosts: vi.fn(),
}));

import { useTotalCosts } from '@/lib/api/queries';

type HookReturn = {
  data: TotalCostsResponse | undefined;
  isLoading: boolean;
  error: Error | null;
};

function setMock(value: HookReturn) {
  vi.mocked(useTotalCosts).mockReturnValue(
    value as ReturnType<typeof useTotalCosts>,
  );
}

describe('TotalCostsCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows the loading state while the query is pending', () => {
    setMock({ data: undefined, isLoading: true, error: null });
    render(<TotalCostsCard />);
    expect(screen.getByText(/loading total costs/i)).toBeInTheDocument();
  });

  it('shows an error message when the query fails', () => {
    setMock({ data: undefined, isLoading: false, error: new Error('boom') });
    render(<TotalCostsCard />);
    expect(
      screen.getByText(/failed to load total costs: boom/i),
    ).toBeInTheDocument();
  });

  it('renders a row per agent plus a grand-total row', () => {
    setMock({
      data: { perAgentUsd: { reviewer: 3.0, planner: 0.5 }, totalUsd: 3.5 },
      isLoading: false,
      error: null,
    });
    render(<TotalCostsCard />);
    expect(screen.getByText('reviewer')).toBeInTheDocument();
    expect(screen.getByText('$3.0000')).toBeInTheDocument();
    expect(screen.getByText('Total')).toBeInTheDocument();
    expect(screen.getByText('$3.5000')).toBeInTheDocument();
  });

  it('shows an empty state when nothing was ever recorded', () => {
    setMock({
      data: { perAgentUsd: {}, totalUsd: 0 },
      isLoading: false,
      error: null,
    });
    render(<TotalCostsCard />);
    expect(screen.getByText(/no costs recorded yet/i)).toBeInTheDocument();
  });
});
