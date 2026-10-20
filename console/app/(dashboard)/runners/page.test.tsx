import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import RunnersPage from './page';

vi.mock('@/lib/api/queries', () => ({ useRunners: vi.fn(), useRunnerQueue: vi.fn() }));
import { useRunners, useRunnerQueue } from '@/lib/api/queries';
vi.mock('@/lib/auth/use-can', () => ({ useCan: vi.fn() }));
import { useCan } from '@/lib/auth/use-can';
import { fakeCapabilities } from '@/lib/auth/capabilities-test-utils';

function mockCan(isAdmin: boolean) {
  vi.mocked(useCan).mockReturnValue(fakeCapabilities(isAdmin));
}
function mockRunners(value: object) {
  vi.mocked(useRunners).mockReturnValue(value as ReturnType<typeof useRunners>);
}
function mockQueue(value: object) {
  vi.mocked(useRunnerQueue).mockReturnValue(value as ReturnType<typeof useRunnerQueue>);
}

const now = Math.floor(Date.now() / 1000);

describe('RunnersPage', () => {
  beforeEach(() => {
    mockCan(true);
    mockQueue({ data: { pending: 0, oldestAgeSec: null, byFilter: [] }, isLoading: false, error: null });
  });

  it('lists configured runners with kind and permits in use', () => {
    mockRunners({
      data: { runners: [
        { alias: 'local', kind: 'local', tags: ['e2e'], concurrency: null, inUse: 0, instances: null },
        { alias: 'mac', kind: 'ssh', tags: [], concurrency: 2, inUse: 1, instances: null },
      ] },
      isLoading: false, error: null,
    });
    render(<RunnersPage />);
    expect(screen.getByRole('heading', { name: 'Runners' })).toBeInTheDocument();
    expect(screen.getByText('mac')).toBeInTheDocument();
    expect(screen.getByText('1 / 2')).toBeInTheDocument();
    expect(screen.getByText('0 / unbounded')).toBeInTheDocument();
  });

  it('shows each external instance with its name, tags and a draining badge', () => {
    mockRunners({
      data: { runners: [{ alias: 'workers', kind: 'external', tags: [], concurrency: null, inUse: 1, instances: [
        { instanceId: 'i-1', name: 'host-1', tags: ['gpu', 'linux'], concurrency: 2, activeSessions: 1,
          lastSeenAt: now - 5, registeredAt: now - 60, draining: true },
      ] }] },
      isLoading: false, error: null,
    });
    render(<RunnersPage />);
    expect(screen.getByText('host-1')).toBeInTheDocument();
    expect(screen.getByText('gpu, linux')).toBeInTheDocument();
    expect(screen.getByText('Draining')).toBeInTheDocument();
  });

  it('says so when no external instance is registered and nothing is pending', () => {
    mockRunners({ data: { runners: [
      { alias: 'workers', kind: 'external', tags: [], concurrency: null, inUse: 0, instances: [] },
    ] }, isLoading: false, error: null });
    render(<RunnersPage />);
    expect(screen.getByText('No external runner instances registered.')).toBeInTheDocument();
    expect(screen.getByText('No pending claims.')).toBeInTheDocument();
  });

  it('shows the queue breakdown by filter', () => {
    mockRunners({ data: { runners: [] }, isLoading: false, error: null });
    mockQueue({ data: { pending: 3, oldestAgeSec: 42, byFilter: [
      { filter: { alias: 'mac', tags: null }, pending: 2, oldestAgeSec: 42 },
      { filter: { alias: null, tags: ['gpu'] }, pending: 1, oldestAgeSec: 7 },
    ] }, isLoading: false, error: null });
    render(<RunnersPage />);
    expect(screen.getByText('runner mac')).toBeInTheDocument();
    expect(screen.getByText('tags gpu')).toBeInTheDocument();
    expect(screen.getByText('42 s')).toBeInTheDocument();
  });

  it('shows an error banner when the runner query fails', () => {
    mockRunners({ data: undefined, isLoading: false, error: new Error('boom') });
    render(<RunnersPage />);
    expect(screen.getByText(/failed to load runners: boom/i)).toBeInTheDocument();
  });

  it('refuses a non-admin and disables both queries', () => {
    mockCan(false);
    mockRunners({ data: undefined, isLoading: false, error: null });
    render(<RunnersPage />);
    expect(screen.getByText('You don’t have access to this page.')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Runners' })).not.toBeInTheDocument();
    expect(useRunners).toHaveBeenCalledWith(false);
    expect(useRunnerQueue).toHaveBeenCalledWith(false);
  });
});
