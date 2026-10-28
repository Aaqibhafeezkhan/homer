import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AgentsPage from './page';

const replace = vi.hoisted(() => vi.fn());
const searchParamsMock = vi.hoisted(() => vi.fn());

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace }),
  usePathname: () => '/agents',
  useSearchParams: () => searchParamsMock(),
}));

function renderWithUrl(query: string) {
  searchParamsMock.mockReturnValue(new URLSearchParams(query));
  return render(<AgentsPage />);
}

const useAgentsMock = vi.hoisted(() => vi.fn());
const useReloadAgentsMock = vi.hoisted(() => vi.fn());

vi.mock('@/lib/api/queries', () => ({
  useAgents: useAgentsMock,
  useReloadAgents: useReloadAgentsMock,
  useExecuteAgent: () => ({ mutateAsync: vi.fn(), isPending: false, error: null }),
  useCreateAgent: () => ({
    mutateAsync: vi.fn(),
    isPending: false,
    error: null,
    reset: vi.fn(),
  }),
}));

vi.mock('@/lib/auth/use-can', () => ({ useCan: vi.fn() }));
import { useCan } from '@/lib/auth/use-can';
import { fakeCapabilities } from '@/lib/auth/capabilities-test-utils';

function mockCan(isAdmin: boolean) {
  vi.mocked(useCan).mockReturnValue(fakeCapabilities(isAdmin));
}

describe('AgentsPage Reload gating', () => {
  beforeEach(() => {
    useAgentsMock.mockReturnValue({ data: { agents: [] }, isLoading: false, error: null });
    useReloadAgentsMock.mockReturnValue({ mutate: vi.fn(), isPending: false });
  });

  it('hides the Reload button for a non-admin', () => {
    mockCan(false);
    renderWithUrl('');
    expect(screen.queryByRole('button', { name: /reload/i })).toBeNull();
  });

  it('shows the Reload button for an admin', () => {
    mockCan(true);
    renderWithUrl('');
    expect(screen.getByRole('button', { name: /reload/i })).toBeInTheDocument();
  });
});

describe('AgentsPage search URL state', () => {
  beforeEach(() => {
    replace.mockReset();
    mockCan(false);
    useAgentsMock.mockReturnValue({
      data: { agents: [{ name: 'build-agent' }, { name: 'deploy-agent' }] },
      isLoading: false,
      error: null,
    });
    useReloadAgentsMock.mockReturnValue({ mutate: vi.fn(), isPending: false });
  });

  it('reads the search term from the q param', () => {
    renderWithUrl('q=deploy');
    expect(screen.getByPlaceholderText('Search agents...')).toHaveValue('deploy');
    expect(screen.getByText('deploy-agent')).toBeInTheDocument();
    expect(screen.queryByText('build-agent')).not.toBeInTheDocument();
  });

  it('typing in the search box writes the q param', async () => {
    const user = userEvent.setup();
    renderWithUrl('');
    await user.type(screen.getByPlaceholderText('Search agents...'), 'x');
    expect(replace).toHaveBeenCalledWith('/agents?q=x', { scroll: false });
  });

  it('clearing the search box deletes the q param', async () => {
    const user = userEvent.setup();
    renderWithUrl('q=deploy');
    await user.clear(screen.getByPlaceholderText('Search agents...'));
    expect(replace).toHaveBeenCalledWith('/agents', { scroll: false });
  });
});

describe('AgentsPage reload result', () => {
  const mutate = vi.fn();

  beforeEach(() => {
    mutate.mockReset();
    mockCan(true);
    useAgentsMock.mockReturnValue({ data: { agents: [] }, isLoading: false, error: null });
    useReloadAgentsMock.mockReturnValue({ mutate, isPending: false });
  });

  type ReloadCallbacks = {
    onSuccess: (result: { added: string[]; updated: string[]; removed: string[]; errors: string[] }) => void;
    onError: (err: Error) => void;
  };

  async function reload(settle: (callbacks: ReloadCallbacks) => void) {
    mutate.mockImplementation((_vars: unknown, callbacks: ReloadCallbacks) => settle(callbacks));
    const user = userEvent.setup();
    renderWithUrl('');
    await user.click(screen.getByRole('button', { name: /reload/i }));
    return user;
  }

  it('summarizes added, updated and removed agents', async () => {
    await reload(({ onSuccess }) =>
      onSuccess({ added: ['a', 'b'], updated: ['c'], removed: ['d'], errors: [] }),
    );
    expect(screen.getByText('Reloaded — 2 added · 1 updated · 1 removed')).toBeInTheDocument();
    expect(screen.getByText('Added:').parentElement).toHaveTextContent('Added: a, b');
    expect(screen.getByText('Updated:').parentElement).toHaveTextContent('Updated: c');
    expect(screen.getByText('Removed:').parentElement).toHaveTextContent('Removed: d');
  });

  it('lists only the change kinds that occurred, plus any load errors', async () => {
    await reload(({ onSuccess }) =>
      onSuccess({ added: [], updated: ['c'], removed: [], errors: ['bad.yaml: invalid'] }),
    );
    expect(screen.getByText('Updated:')).toBeInTheDocument();
    expect(screen.queryByText('Added:')).not.toBeInTheDocument();
    expect(screen.queryByText('Removed:')).not.toBeInTheDocument();
    expect(screen.getByText('bad.yaml: invalid')).toBeInTheDocument();
  });

  it('shows no change list for a no-op reload and can be dismissed', async () => {
    const user = await reload(({ onSuccess }) =>
      onSuccess({ added: [], updated: [], removed: [], errors: [] }),
    );
    const summary = screen.getByText('Reloaded — 0 added · 0 updated · 0 removed');
    expect(screen.queryByText('Updated:')).not.toBeInTheDocument();
    await user.click(summary.parentElement!.querySelector('button')!);
    expect(screen.queryByText(/Reloaded —/)).not.toBeInTheDocument();
  });

  it('shows a dismissible reload failure', async () => {
    const user = await reload(({ onError }) => onError(new Error('forbidden')));
    const message = screen.getByText('Reload failed: forbidden');
    await user.click(message.parentElement!.querySelector('button')!);
    expect(screen.queryByText(/Reload failed/)).not.toBeInTheDocument();
  });
});

describe('AgentsPage run dialog', () => {
  beforeEach(() => {
    mockCan(false);
    useAgentsMock.mockReturnValue({
      data: { agents: [{ name: 'build-agent' }, { name: 'deploy-agent' }] },
      isLoading: false,
      error: null,
    });
    useReloadAgentsMock.mockReturnValue({ mutate: vi.fn(), isPending: false });
  });

  it('opens the run dialog for the agent whose Run button was clicked', async () => {
    const user = userEvent.setup();
    renderWithUrl('');
    expect(screen.queryByRole('dialog')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Run agent deploy-agent' }));
    expect(screen.getByRole('dialog')).toHaveTextContent('Run Agent: deploy-agent');
  });
});

describe('AgentsPage group tabs', () => {
  const agents = [
    { name: 'build-agent', group: 'Platform' },
    { name: 'deploy-agent', group: 'Platform' },
    { name: 'lint-agent', group: 'Quality' },
    { name: 'orphan-agent', group: null },
  ];

  beforeEach(() => {
    replace.mockReset();
    mockCan(false);
    useAgentsMock.mockReturnValue({ data: { agents }, isLoading: false, error: null });
    useReloadAgentsMock.mockReturnValue({ mutate: vi.fn(), isPending: false });
  });

  function tabNames() {
    return screen.getAllByRole('tab').map((tab) => tab.textContent);
  }

  it('renders an All tab plus one tab per group, sorted by name', () => {
    renderWithUrl('');
    expect(tabNames()).toEqual(['All', 'Platform', 'Quality']);
  });

  it('lists every agent under All, including the ungrouped one', () => {
    renderWithUrl('');
    for (const agent of agents) {
      expect(screen.getByText(agent.name)).toBeInTheDocument();
    }
  });

  it('states that All also contains ungrouped agents', () => {
    renderWithUrl('');
    expect(screen.getByText(/without a group appear under "All" only/i)).toBeInTheDocument();
  });

  it('lists only the selected group, leaving the ungrouped agent out', () => {
    renderWithUrl('group=Quality');
    expect(screen.getByText('lint-agent')).toBeInTheDocument();
    expect(screen.queryByText('build-agent')).not.toBeInTheDocument();
    expect(screen.queryByText('orphan-agent')).not.toBeInTheDocument();
  });

  it('marks the group from the URL as the active tab', () => {
    renderWithUrl('group=Platform');
    expect(screen.getByRole('tab', { name: 'Platform' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'All' })).toHaveAttribute('aria-selected', 'false');
  });

  it('applies the name search within the selected tab', () => {
    renderWithUrl('group=Platform&q=build');
    expect(screen.getByText('build-agent')).toBeInTheDocument();
    expect(screen.queryByText('deploy-agent')).not.toBeInTheDocument();
    // The search does not reach outside the tab: lint-agent matches nothing here anyway.
    expect(screen.queryByText('lint-agent')).not.toBeInTheDocument();
  });

  it('writes the group param when a group tab is selected', async () => {
    const user = userEvent.setup();
    renderWithUrl('');
    await user.click(screen.getByRole('tab', { name: 'Quality' }));
    expect(replace).toHaveBeenCalledWith('/agents?group=Quality', { scroll: false });
  });

  it('removes the group param when All is selected', async () => {
    const user = userEvent.setup();
    renderWithUrl('group=Quality');
    await user.click(screen.getByRole('tab', { name: 'All' }));
    expect(replace).toHaveBeenCalledWith('/agents', { scroll: false });
  });

  it('falls back to All for a group that no longer exists', () => {
    renderWithUrl('group=Gone');
    expect(screen.getByRole('tab', { name: 'All' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByText('orphan-agent')).toBeInTheDocument();
  });
});

describe('AgentsPage group tab value and URL mapping', () => {
  beforeEach(() => {
    replace.mockReset();
    mockCan(false);
    useReloadAgentsMock.mockReturnValue({ mutate: vi.fn(), isPending: false });
  });

  function setupAgents(agents: { name: string; group: string }[]) {
    useAgentsMock.mockReturnValue({ data: { agents }, isLoading: false, error: null });
  }

  function tabNames() {
    return screen.getAllByRole('tab').map((tab) => tab.textContent);
  }

  it('keeps a group literally named "all" selectable and distinct from the All tab', async () => {
    setupAgents([{ name: 'all-agent', group: 'all' }]);
    const user = userEvent.setup();
    renderWithUrl('');
    expect(tabNames()).toEqual(['All', 'all']);
    await user.click(screen.getByRole('tab', { name: 'all' }));
    expect(replace).toHaveBeenCalledWith('/agents?group=all', { scroll: false });
  });

  it('selects the "all" group tab from the URL param, not the All tab', () => {
    setupAgents([{ name: 'all-agent', group: 'all' }]);
    renderWithUrl('group=all');
    expect(screen.getByRole('tab', { name: 'all' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'All' })).toHaveAttribute('aria-selected', 'false');
    expect(screen.getByText('all-agent')).toBeInTheDocument();
  });

  it('keeps the tab active across the URL round-trip of a group with a space and a slash', async () => {
    const group = 'Platform / Billing';
    setupAgents([{ name: 'pb-agent', group }]);
    const user = userEvent.setup();
    renderWithUrl('');
    await user.click(screen.getByRole('tab', { name: group }));
    // URLSearchParams.toString() encodes the space as `+` and the slash as %2F.
    expect(replace).toHaveBeenCalledWith('/agents?group=Platform+%2F+Billing', { scroll: false });
  });

  it('selects a group with a space and a slash from the URL param', () => {
    const group = 'Platform / Billing';
    setupAgents([{ name: 'pb-agent', group }]);
    renderWithUrl(`group=${encodeURIComponent(group)}`);
    expect(screen.getByRole('tab', { name: group })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByText('pb-agent')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'All' })).toHaveAttribute('aria-selected', 'false');
  });

  it('matches the group param exactly when two groups differ only by case', () => {
    setupAgents([
      { name: 'upper-agent', group: 'Platform' },
      { name: 'lower-agent', group: 'platform' },
    ]);
    renderWithUrl('group=platform');
    expect(screen.getByRole('tab', { name: 'platform' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByText('lower-agent')).toBeInTheDocument();
    expect(screen.queryByText('upper-agent')).not.toBeInTheDocument();
  });
});
