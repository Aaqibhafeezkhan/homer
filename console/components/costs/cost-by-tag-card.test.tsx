import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { CostByTagCard } from './cost-by-tag-card';
import type { CostByTagResponse } from '@/types/homer';

vi.mock('@/lib/api/queries', () => ({
  useCostsByTag: vi.fn(),
}));

import { useCostsByTag } from '@/lib/api/queries';

type HookReturn = {
  data: CostByTagResponse | undefined;
  isLoading: boolean;
  error: Error | null;
};

function setMock(value: HookReturn) {
  vi.mocked(useCostsByTag).mockReturnValue(
    value as ReturnType<typeof useCostsByTag>,
  );
}

const ticketBreakdown: CostByTagResponse = {
  key: 'ticket_id',
  valuesUsd: { 'T-123': 1.42, 'T-456': 0.31 },
  totalUsd: 1.73,
  truncated: false,
};

describe('CostByTagCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders nothing when no tag keys are configured', () => {
    setMock({ data: undefined, isLoading: false, error: null });
    const { container } = render(<CostByTagCard tagKeys={[]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows the loading state while the query is pending', () => {
    setMock({ data: undefined, isLoading: true, error: null });
    render(<CostByTagCard tagKeys={['ticket_id']} />);
    expect(screen.getByText(/loading cost by tag/i)).toBeInTheDocument();
  });

  it('shows an error message when the query fails', () => {
    setMock({ data: undefined, isLoading: false, error: new Error('boom') });
    render(<CostByTagCard tagKeys={['ticket_id']} />);
    expect(
      screen.getByText(/failed to load cost by tag: boom/i),
    ).toBeInTheDocument();
  });

  it('renders a row per tag value plus a total row', () => {
    setMock({ data: ticketBreakdown, isLoading: false, error: null });
    render(<CostByTagCard tagKeys={['ticket_id']} />);
    expect(screen.getByText('T-123')).toBeInTheDocument();
    expect(screen.getByText('$1.4200')).toBeInTheDocument();
    expect(screen.getByText('T-456')).toBeInTheDocument();
    expect(screen.getByText('$0.3100')).toBeInTheDocument();
    expect(screen.getByText('Total')).toBeInTheDocument();
    expect(screen.getByText('$1.7300')).toBeInTheDocument();
  });

  it('shows an empty state for a key with no matching values', () => {
    setMock({
      data: { key: 'ticket_id', valuesUsd: {}, totalUsd: 0, truncated: false },
      isLoading: false,
      error: null,
    });
    render(<CostByTagCard tagKeys={['ticket_id']} />);
    expect(
      screen.getByText(/no costs recorded for this tag/i),
    ).toBeInTheDocument();
  });

  it('does not show a truncation note when the total is complete', () => {
    setMock({ data: ticketBreakdown, isLoading: false, error: null });
    render(<CostByTagCard tagKeys={['ticket_id']} />);
    expect(screen.queryByText(/newest 10,000 runs/i)).not.toBeInTheDocument();
  });

  it('shows a truncation note when the backend reports a partial total', () => {
    setMock({
      data: { ...ticketBreakdown, truncated: true },
      isLoading: false,
      error: null,
    });
    render(<CostByTagCard tagKeys={['ticket_id']} />);
    expect(screen.getByText(/newest 10,000 runs/i)).toBeInTheDocument();
  });

  it('re-queries with the picked key when the selector changes', () => {
    setMock({ data: ticketBreakdown, isLoading: false, error: null });
    render(<CostByTagCard tagKeys={['ticket_id', 'project']} />);

    fireEvent.change(screen.getByLabelText(/select tag key/i), {
      target: { value: 'project' },
    });

    expect(vi.mocked(useCostsByTag)).toHaveBeenLastCalledWith('project');
  });

  it('offers every configured tag key in the selector', () => {
    setMock({ data: ticketBreakdown, isLoading: false, error: null });
    render(<CostByTagCard tagKeys={['ticket_id', 'project']} />);
    const options = screen
      .getAllByRole('option')
      .map((o) => (o as HTMLOptionElement).value);
    expect(options).toEqual(['ticket_id', 'project']);
  });
});
