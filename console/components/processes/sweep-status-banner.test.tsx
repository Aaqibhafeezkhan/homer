import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { SweepStatusBanner } from './sweep-status-banner';
import type { HealthResponse } from '@/types/homer';

const useHealthMock = vi.hoisted(() => vi.fn());

vi.mock('@/lib/api/queries', () => ({
  useHealth: useHealthMock,
}));

function setHealth(data: HealthResponse | undefined) {
  useHealthMock.mockReturnValue({ data });
}

describe('SweepStatusBanner', () => {
  beforeEach(() => {
    useHealthMock.mockReset();
  });

  it('renders nothing when no kubernetes runner is configured', () => {
    setHealth({
      status: 'ok',
      runners: [{ alias: 'local', type: 'local' }],
      sweep: null,
    });
    const { container } = render(<SweepStatusBanner />);
    expect(container.firstChild).toBeNull();
  });

  it('renders nothing when sweep has not run yet', () => {
    setHealth({
      status: 'ok',
      runners: [{ alias: 'k8s-dev', type: 'kubernetes' }],
      sweep: null,
    });
    const { container } = render(<SweepStatusBanner />);
    expect(container.firstChild).toBeNull();
  });

  it('renders scanned/deleted counts when kubernetes runner and sweep are present', () => {
    setHealth({
      status: 'ok',
      runners: [{ alias: 'k8s-dev', type: 'kubernetes' }],
      sweep: {
        ranAt: 1_700_000_000,
        scanned: 4,
        deleted: [{ namespace: 'homer', name: 'homer-1-aaa' }],
        errors: [],
      },
    });
    render(<SweepStatusBanner />);
    expect(screen.getByText(/scanned 4, deleted 1/)).toBeInTheDocument();
  });

  it('shows errors when the sweep reported any', () => {
    setHealth({
      status: 'ok',
      runners: [{ alias: 'k8s-dev', type: 'kubernetes' }],
      sweep: {
        ranAt: 1_700_000_000,
        scanned: 1,
        deleted: [],
        errors: ['list pods failed: 403 forbidden'],
      },
    });
    render(<SweepStatusBanner />);
    expect(screen.getByText(/errors 1/)).toBeInTheDocument();
    expect(screen.getByText(/list pods failed/)).toBeInTheDocument();
  });

  it('renders nothing while health is still loading', () => {
    setHealth(undefined);
    const { container } = render(<SweepStatusBanner />);
    expect(container.firstChild).toBeNull();
  });
});
