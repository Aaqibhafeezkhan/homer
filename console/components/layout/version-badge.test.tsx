import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { VersionBadge } from './version-badge';

const heartbeat = vi.fn();

vi.mock('@/lib/api/queries', () => ({
  useHeartbeat: () => heartbeat(),
}));

describe('VersionBadge', () => {
  beforeEach(() => {
    heartbeat.mockReset();
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('shows the console build version and the backend version from the heartbeat', () => {
    vi.stubEnv('NEXT_PUBLIC_CONSOLE_VERSION', '1.2.3');
    heartbeat.mockReturnValue({ data: { version: '4.5.6' } });
    render(<VersionBadge />);
    expect(screen.getByText(/console: v1\.2\.3/)).toHaveTextContent('homer: v4.5.6');
  });

  it('falls back to dev and a placeholder before the heartbeat answers', () => {
    vi.stubEnv('NEXT_PUBLIC_CONSOLE_VERSION', undefined);
    heartbeat.mockReturnValue({ data: undefined });
    render(<VersionBadge />);
    expect(screen.getByText(/console: vdev/)).toHaveTextContent('homer: v...');
  });
});
