import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SchedulesPage from './page';

const useAgentsMock = vi.hoisted(() => vi.fn());
const useFireAgentCronMock = vi.hoisted(() => vi.fn());

vi.mock('@/lib/api/queries', () => ({
  useAgents: useAgentsMock,
  useFireAgentCron: useFireAgentCronMock,
}));

vi.mock('@/lib/auth/use-can', () => ({ useCan: vi.fn() }));
import { useCan } from '@/lib/auth/use-can';
import { fakeCapabilities } from '@/lib/auth/capabilities-test-utils';

function mockCan(isAdmin: boolean) {
  vi.mocked(useCan).mockReturnValue(fakeCapabilities(isAdmin));
}

function makeCronAgent(name: string) {
  return {
    name,
    description: null,
    inputs: null,
    queryParams: null,
    scriptCount: 0,
    cron: {
      expression: '0 9 * * *',
      timezone: 'UTC',
      nextRunAt: 1_700_000_000,
      lastRunAt: null,
      lastRunProcessId: null,
    },
  };
}

describe('SchedulesPage admin guard', () => {
  beforeEach(() => {
    useAgentsMock.mockReturnValue({ data: { agents: [] }, isLoading: false, error: null });
    useFireAgentCronMock.mockReturnValue({
      mutateAsync: vi.fn(),
      isPending: false,
      error: null,
    });
  });

  it('shows a no-access message for a non-admin', () => {
    mockCan(false);
    render(<SchedulesPage />);
    expect(screen.getByText('You don’t have access to this page.')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Schedules' })).toBeNull();
  });

  it('renders the schedules content for an admin', () => {
    mockCan(true);
    render(<SchedulesPage />);
    expect(screen.getByRole('heading', { name: 'Schedules' })).toBeInTheDocument();
  });
});

describe('SchedulesPage Run button', () => {
  beforeEach(() => {
    useAgentsMock.mockReturnValue({
      data: { agents: [makeCronAgent('nightly')] },
      isLoading: false,
      error: null,
    });
    useFireAgentCronMock.mockReturnValue({
      mutateAsync: vi.fn(),
      isPending: false,
      error: null,
    });
  });

  it('hides the Run button for a non-admin', () => {
    mockCan(false);
    render(<SchedulesPage />);
    expect(screen.queryByRole('button', { name: /run agent nightly now/i })).toBeNull();
  });

  it('opens a confirmation dialog without firing on click', async () => {
    const mutateAsync = vi.fn();
    useFireAgentCronMock.mockReturnValue({ mutateAsync, isPending: false, error: null });
    mockCan(true);
    const user = userEvent.setup();
    render(<SchedulesPage />);

    await user.click(screen.getByRole('button', { name: /run agent nightly now/i }));

    expect(screen.getByText('Run nightly now?')).toBeInTheDocument();
    expect(mutateAsync).not.toHaveBeenCalled();
  });

  it('fires the mutation once on confirm', async () => {
    const mutateAsync = vi.fn().mockResolvedValue(undefined);
    useFireAgentCronMock.mockReturnValue({ mutateAsync, isPending: false, error: null });
    mockCan(true);
    const user = userEvent.setup();
    render(<SchedulesPage />);

    await user.click(screen.getByRole('button', { name: /run agent nightly now/i }));
    await user.click(screen.getByRole('button', { name: /run now/i }));

    expect(mutateAsync).toHaveBeenCalledTimes(1);
    expect(mutateAsync).toHaveBeenCalledWith('nightly');
  });

  it('does not fire when the dialog is cancelled', async () => {
    const mutateAsync = vi.fn();
    useFireAgentCronMock.mockReturnValue({ mutateAsync, isPending: false, error: null });
    mockCan(true);
    const user = userEvent.setup();
    render(<SchedulesPage />);

    await user.click(screen.getByRole('button', { name: /run agent nightly now/i }));
    await user.click(screen.getByRole('button', { name: /cancel/i }));

    expect(mutateAsync).not.toHaveBeenCalled();
  });

  it('surfaces a mutation error in the dialog', async () => {
    const mutateAsync = vi.fn().mockRejectedValue(new Error('boom'));
    useFireAgentCronMock.mockReturnValue({
      mutateAsync,
      isPending: false,
      error: new Error('boom'),
    });
    mockCan(true);
    const user = userEvent.setup();
    render(<SchedulesPage />);

    await user.click(screen.getByRole('button', { name: /run agent nightly now/i }));

    expect(screen.getByText(/Failed to start the run: boom/)).toBeInTheDocument();
  });
});

describe('SchedulesPage ordering', () => {
  beforeEach(() => {
    mockCan(true);
    useFireAgentCronMock.mockReturnValue({ mutateAsync: vi.fn(), isPending: false, error: null });
  });

  it('lists the soonest next run first and an agent without a next run last', () => {
    const at = (name: string, nextRunAt: number | null) => {
      const agent = makeCronAgent(name);
      return { ...agent, cron: { ...agent.cron, nextRunAt } };
    };
    useAgentsMock.mockReturnValue({
      data: {
        agents: [
          at('later', 1_700_000_900),
          at('unscheduled', null),
          { ...makeCronAgent('manual'), cron: null },
          at('sooner', 1_700_000_100),
        ],
      },
      isLoading: false,
      error: null,
    });
    render(<SchedulesPage />);
    const names = screen
      .getAllByRole('link')
      .map((link) => link.textContent)
      .filter((text) => ['later', 'unscheduled', 'manual', 'sooner'].includes(text ?? ''));
    expect(names).toEqual(['sooner', 'later', 'unscheduled']);
  });
});

describe('SchedulesPage last run', () => {
  beforeEach(() => {
    mockCan(true);
    useFireAgentCronMock.mockReturnValue({ mutateAsync: vi.fn(), isPending: false, error: null });
  });

  const withLastRun = (lastRunProcessId: number | null) => {
    const agent = makeCronAgent('nightly');
    return { ...agent, cron: { ...agent.cron, lastRunAt: 1_700_000_000, lastRunProcessId } };
  };

  it('links the last run to its process when the run is known', () => {
    useAgentsMock.mockReturnValue({ data: { agents: [withLastRun(42)] }, isLoading: false, error: null });
    render(<SchedulesPage />);
    expect(screen.getAllByRole('link').some((link) => link.getAttribute('href') === '/processes/42')).toBe(true);
  });

  it('shows the last run as plain text without a process', () => {
    useAgentsMock.mockReturnValue({ data: { agents: [withLastRun(null)] }, isLoading: false, error: null });
    render(<SchedulesPage />);
    expect(screen.getAllByRole('link').some((link) => link.getAttribute('href')?.startsWith('/processes/'))).toBe(false);
    expect(screen.queryByText('Never')).toBeNull();
  });
});

describe('SchedulesPage dialog dismissal', () => {
  it('closes the confirmation dialog on Escape', async () => {
    mockCan(true);
    useAgentsMock.mockReturnValue({ data: { agents: [makeCronAgent('nightly')] }, isLoading: false, error: null });
    useFireAgentCronMock.mockReturnValue({ mutateAsync: vi.fn(), isPending: false, error: null });
    const user = userEvent.setup();
    render(<SchedulesPage />);

    await user.click(screen.getByRole('button', { name: /run agent nightly now/i }));
    expect(screen.getByText('Run nightly now?')).toBeInTheDocument();
    await user.keyboard('{Escape}');

    expect(screen.queryByText('Run nightly now?')).toBeNull();
  });
});
