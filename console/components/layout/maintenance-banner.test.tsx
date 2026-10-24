import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MaintenanceBanner } from './maintenance-banner';
import type { HealthResponse } from '@/types/homer';

const useHealthMock = vi.hoisted(() => vi.fn());

vi.mock('@/lib/api/queries', () => ({
  useHealth: useHealthMock,
}));

function setHealth(data: HealthResponse | undefined) {
  useHealthMock.mockReturnValue({ data });
}

describe('MaintenanceBanner', () => {
  beforeEach(() => {
    useHealthMock.mockReset();
  });

  it('alerts while the server is in maintenance mode', () => {
    setHealth({ status: 'ok', runners: [], maintenance: true });
    render(<MaintenanceBanner />);

    const banner = screen.getByRole('alert');
    expect(banner).toHaveTextContent(/Maintenance mode is on/);
    expect(banner).toHaveTextContent(/Scheduled runs are skipped/);
  });

  it('renders nothing when the mode is off', () => {
    setHealth({ status: 'ok', runners: [], maintenance: false });
    const { container } = render(<MaintenanceBanner />);
    expect(container.firstChild).toBeNull();
  });

  it('renders nothing when the server predates the flag', () => {
    // An older backend omits `maintenance` entirely; the banner must stay off.
    setHealth({ status: 'ok', runners: [] });
    const { container } = render(<MaintenanceBanner />);
    expect(container.firstChild).toBeNull();
  });

  it('renders nothing while health is still loading', () => {
    setHealth(undefined);
    const { container } = render(<MaintenanceBanner />);
    expect(container.firstChild).toBeNull();
  });
});
