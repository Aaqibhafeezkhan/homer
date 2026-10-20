import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

type Row = Record<string, unknown> & { status: string };

let mockContinuations: Row[] = [];
// Per-status `total` the mock reports; unset means "every matching row is on the page".
let mockTotals: Record<string, number> = {};
vi.mock('@/lib/api/queries', () => ({
  // Filter-aware: the page asks for PENDING and FAILED separately, so a mock that
  // ignored the filter would hand the same rows to both sections.
  useContinuations: (filters?: { status?: string }) => {
    const continuations = mockContinuations.filter((c) => !filters?.status || c.status === filters.status);
    return {
      data: {
        continuations,
        total: mockTotals[filters?.status ?? ''] ?? continuations.length,
        offset: 0,
        limit: 100,
      },
    };
  },
  useCancelContinuation: () => ({ mutateAsync: vi.fn(), isPending: false, isError: false, error: null }),
}));

import ContinuationsPage from './page';

const row = (overrides: Partial<Row> & { id: number; status: string }): Row => ({
  watchedProcessId: 12,
  agentName: 'follow-up',
  resumeParam: 'RESULT',
  params: {},
  originProcessId: 7,
  originAgentName: 'starter',
  expiresAt: null,
  createdAt: 1_700_000_000,
  firedAt: null,
  firedProcessId: null,
  error: null,
  ...overrides,
});

describe('ContinuationsPage', () => {
  beforeEach(() => {
    mockTotals = {};
  });

  it('lists pending continuations with the target agent', () => {
    mockContinuations = [row({ id: 5, status: 'PENDING' })];
    render(<ContinuationsPage />);
    expect(screen.getByText('follow-up')).toBeInTheDocument();
  });

  it('shows empty state when none', () => {
    mockContinuations = [];
    render(<ContinuationsPage />);
    expect(screen.getByText('No pending continuations.')).toBeInTheDocument();
  });

  it('surfaces continuations that failed to fire, with the reason', () => {
    mockContinuations = [
      row({ id: 9, status: 'FAILED', agentName: 'notifier', error: 'cost cap exceeded (gave up after 8 attempts)' }),
    ];
    render(<ContinuationsPage />);
    expect(screen.getByText('Failed to fire')).toBeInTheDocument();
    expect(
      screen.getByText(/cost cap exceeded \(gave up after 8 attempts\)/),
    ).toBeInTheDocument();
  });

  it('does not show the failure section when nothing failed', () => {
    mockContinuations = [row({ id: 5, status: 'PENDING' })];
    render(<ContinuationsPage />);
    expect(screen.queryByText('Failed to fire')).not.toBeInTheDocument();
  });

  it('says how many pending continuations are missing when the page is cut short', () => {
    mockContinuations = [row({ id: 5, status: 'PENDING' }), row({ id: 4, status: 'PENDING' })];
    mockTotals = { PENDING: 150 };
    render(<ContinuationsPage />);
    expect(screen.getByText('Showing the newest 2 of 150.')).toBeInTheDocument();
  });

  it('says how many failed continuations are missing when the page is cut short', () => {
    mockContinuations = [row({ id: 5, status: 'PENDING' }), row({ id: 9, status: 'FAILED', error: 'boom' })];
    mockTotals = { FAILED: 240 };
    render(<ContinuationsPage />);
    expect(screen.getByText('Showing the newest 1 of 240.')).toBeInTheDocument();
    // The pending card has everything, so it carries no notice.
    expect(screen.getAllByText(/^Showing the newest/)).toHaveLength(1);
  });

  it('shows no notice when every row is on the page', () => {
    mockContinuations = [row({ id: 5, status: 'PENDING' }), row({ id: 9, status: 'FAILED', error: 'boom' })];
    render(<ContinuationsPage />);
    expect(screen.queryByText(/^Showing the newest/)).not.toBeInTheDocument();
  });
});
