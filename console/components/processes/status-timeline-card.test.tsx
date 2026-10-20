import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { StatusTimelineCard } from './status-timeline-card';
import { formatTimestamp } from '@/lib/utils/format';

const NOW = 1_700_000_000;

describe('StatusTimelineCard', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW * 1000);
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  const history = [
    { timestamp: NOW - 20, status: 'CREATED' as const },
    { timestamp: NOW - 10, status: 'FINISHED' as const },
  ];

  it('lists every status change in order with its time', () => {
    render(<StatusTimelineCard history={history} purgeAt={null} />);
    expect(screen.getByText('Status Timeline')).toBeInTheDocument();
    const created = screen.getByText(formatTimestamp(NOW - 20));
    const finished = screen.getByText(formatTimestamp(NOW - 10));
    expect(created.compareDocumentPosition(finished) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByText('Created')).toBeInTheDocument();
    expect(screen.getByText('Finished')).toBeInTheDocument();
    expect(screen.queryByText(/workspace purge/)).not.toBeInTheDocument();
  });

  it('appends the scheduled workspace purge with its countdown', () => {
    render(<StatusTimelineCard history={history} purgeAt={NOW + 2 * 3600} />);
    expect(screen.getByText(formatTimestamp(NOW + 2 * 3600))).toBeInTheDocument();
    expect(screen.getByText('workspace purge (in 2h 0m)')).toBeInTheDocument();
  });
});
