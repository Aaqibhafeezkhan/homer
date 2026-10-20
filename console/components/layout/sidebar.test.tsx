import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'vitest-axe';
import { Sidebar } from './sidebar';

const useOpenQuestionsCountMock = vi.hoisted(() => vi.fn());
const usePendingContinuationsCountMock = vi.hoisted(() => vi.fn());
const usePluginsMock = vi.hoisted(() => vi.fn());
const pathnameMock = vi.hoisted(() => ({ current: '/processes' }));

vi.mock('next/navigation', () => ({
  usePathname: () => pathnameMock.current,
}));

vi.mock('@/lib/api/queries', () => ({
  useOpenQuestionsCount: useOpenQuestionsCountMock,
  usePendingContinuationsCount: usePendingContinuationsCountMock,
}));

vi.mock('@/lib/plugins/plugins-context', () => ({ usePlugins: usePluginsMock }));

vi.mock('@/lib/auth/use-can', () => ({ useCan: vi.fn() }));
import { useCan } from '@/lib/auth/use-can';
import { fakeCapabilities } from '@/lib/auth/capabilities-test-utils';

function mockCan(isAdmin: boolean) {
  vi.mocked(useCan).mockReturnValue(fakeCapabilities(isAdmin));
}

describe('Sidebar', () => {
  beforeEach(() => {
    localStorage.clear();
    pathnameMock.current = '/processes';
    useOpenQuestionsCountMock.mockReset();
    useOpenQuestionsCountMock.mockReturnValue({ data: 0 });
    usePendingContinuationsCountMock.mockReset();
    usePendingContinuationsCountMock.mockReturnValue({ data: 0 });
    usePluginsMock.mockReset();
    usePluginsMock.mockReturnValue({ plugins: [], loading: false });
    mockCan(true);
  });

  it('shows the global open-question count on the Questions entry', () => {
    useOpenQuestionsCountMock.mockReturnValue({ data: 3 });
    render(<Sidebar />);
    const badge = screen.getByLabelText('3 open questions waiting for an answer');
    expect(badge).toHaveTextContent('3');
    // badge must be inside the Questions nav link, not Processes
    const questionsLink = screen.getByRole('link', { name: /Questions/ });
    expect(questionsLink).toContainElement(badge);
  });

  it('renders a Questions nav link', () => {
    render(<Sidebar />);
    expect(screen.getByRole('link', { name: /Questions/ })).toBeInTheDocument();
  });

  it('hides the badge when no questions are open', () => {
    useOpenQuestionsCountMock.mockReturnValue({ data: 0 });
    render(<Sidebar />);
    expect(screen.queryByLabelText(/open question/)).toBeNull();
  });

  it('hides the badge while the count is still loading', () => {
    useOpenQuestionsCountMock.mockReturnValue({ data: undefined });
    render(<Sidebar />);
    expect(screen.queryByLabelText(/open question/)).toBeNull();
  });

  it('shows the pending-continuation count on the Continuations entry', () => {
    usePendingContinuationsCountMock.mockReturnValue({ data: 4 });
    render(<Sidebar />);
    const badge = screen.getByLabelText('4 pending continuations waiting to fire');
    expect(badge).toHaveTextContent('4');
    const link = screen.getByRole('link', { name: /Continuations/ });
    expect(link).toContainElement(badge);
  });

  it('hides the Continuations badge when the count is 0', () => {
    usePendingContinuationsCountMock.mockReturnValue({ data: 0 });
    render(<Sidebar />);
    expect(screen.queryByLabelText(/pending continuation/)).toBeNull();
  });

  it('hides the Continuations badge while the count is still loading', () => {
    usePendingContinuationsCountMock.mockReturnValue({ data: undefined });
    render(<Sidebar />);
    expect(screen.queryByLabelText(/pending continuation/)).toBeNull();
  });

  describe('collapsible', () => {
    it('renders expanded by default with names visible', () => {
      render(<Sidebar />);
      expect(screen.getByText('Processes')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Collapse sidebar' })).toBeInTheDocument();
    });

    it('the toggle collapses to icon-only links that keep their name as aria-label', async () => {
      const user = userEvent.setup();
      render(<Sidebar />);

      await user.click(screen.getByRole('button', { name: 'Collapse sidebar' }));

      // Name is no longer visible text, but stays as the accessible name.
      expect(screen.queryByText('Processes')).toBeNull();
      const link = screen.getByRole('link', { name: 'Processes' });
      expect(link).toHaveAttribute('title', 'Processes');
      expect(screen.getByRole('button', { name: 'Expand sidebar' })).toBeInTheDocument();
    });

    it('persists the collapsed choice and restores it on a fresh mount', async () => {
      const user = userEvent.setup();
      const { unmount } = render(<Sidebar />);
      await user.click(screen.getByRole('button', { name: 'Collapse sidebar' }));
      expect(localStorage.getItem('homer:sidebar-collapsed')).toBe('1');
      unmount();

      render(<Sidebar />);
      expect(screen.queryByText('Processes')).toBeNull();
      expect(screen.getByRole('link', { name: 'Processes' })).toBeInTheDocument();
    });

    it('expands again when toggled back', async () => {
      const user = userEvent.setup();
      render(<Sidebar />);
      await user.click(screen.getByRole('button', { name: 'Collapse sidebar' }));
      await user.click(screen.getByRole('button', { name: 'Expand sidebar' }));
      expect(screen.getByText('Processes')).toBeInTheDocument();
      expect(localStorage.getItem('homer:sidebar-collapsed')).toBe('0');
    });

    it('keeps the active-page highlight when collapsed', async () => {
      const user = userEvent.setup();
      render(<Sidebar />);
      await user.click(screen.getByRole('button', { name: 'Collapse sidebar' }));
      // usePathname is mocked to /processes, so that link stays highlighted.
      expect(screen.getByRole('link', { name: 'Processes' })).toHaveClass('bg-primary');
    });

    it('keeps both badges visible on the icon when collapsed', async () => {
      useOpenQuestionsCountMock.mockReturnValue({ data: 3 });
      usePendingContinuationsCountMock.mockReturnValue({ data: 4 });
      const user = userEvent.setup();
      render(<Sidebar />);

      await user.click(screen.getByRole('button', { name: 'Collapse sidebar' }));

      // The count is folded into the link's accessible name — the roleless
      // overlay span itself is aria-hidden, so a screen reader hears it here.
      const questions = screen.getByRole('link', {
        name: 'Questions, 3 open questions waiting for an answer',
      });
      expect(questions).toHaveTextContent('3');
      const continuations = screen.getByRole('link', {
        name: 'Continuations, 4 pending continuations waiting to fire',
      });
      expect(continuations).toHaveTextContent('4');
    });

    it('still hides admin-only items from a non-admin when collapsed', async () => {
      mockCan(false);
      const user = userEvent.setup();
      render(<Sidebar />);
      await user.click(screen.getByRole('button', { name: 'Collapse sidebar' }));
      expect(screen.queryByRole('link', { name: 'Costs' })).toBeNull();
      expect(screen.getByRole('link', { name: 'Processes' })).toBeInTheDocument();
    });

    it('renders a plugin item icon-only when collapsed and highlights it on its route', async () => {
      pathnameMock.current = '/myfleet';
      usePluginsMock.mockReturnValue({
        plugins: [{ route: '/myfleet', title: 'My Fleet' }],
        loading: false,
      });
      const user = userEvent.setup();
      render(<Sidebar />);

      await user.click(screen.getByRole('button', { name: 'Collapse sidebar' }));

      expect(screen.queryByText('My Fleet')).toBeNull();
      const link = screen.getByRole('link', { name: 'My Fleet' });
      expect(link).toHaveAttribute('href', '/myfleet');
      expect(link).toHaveAttribute('title', 'My Fleet');
      expect(link).toHaveClass('bg-primary');
    });
  });

  describe('a11y', () => {
    it('has no violations on the dashboard layout', async () => {
      useOpenQuestionsCountMock.mockReturnValue({ data: 2 });
      const { container } = render(<Sidebar />);
      expect(await axe(container)).toHaveNoViolations();
    });

    it('has no violations when collapsed with both badges', async () => {
      useOpenQuestionsCountMock.mockReturnValue({ data: 2 });
      usePendingContinuationsCountMock.mockReturnValue({ data: 5 });
      const user = userEvent.setup();
      const { container } = render(<Sidebar />);
      await user.click(screen.getByRole('button', { name: 'Collapse sidebar' }));
      expect(await axe(container)).toHaveNoViolations();
    });
  });

  describe('admin gating', () => {
    it('hides Costs, Schedules and Runners for a non-admin', () => {
      mockCan(false);
      render(<Sidebar />);
      expect(screen.queryByText('Costs')).toBeNull();
      expect(screen.queryByText('Schedules')).toBeNull();
      expect(screen.queryByText('Runners')).toBeNull();
      expect(screen.getByText('Processes')).toBeInTheDocument(); // non-admin nav stays
    });

    it('shows Costs, Schedules and Runners for an admin', () => {
      mockCan(true);
      render(<Sidebar />);
      expect(screen.getByText('Costs')).toBeInTheDocument();
      expect(screen.getByText('Schedules')).toBeInTheDocument();
      expect(screen.getByText('Runners')).toBeInTheDocument();
    });
  });
  describe('runtime plugins', () => {
    const plugin = (over = {}) => ({
      route: '/myfleet',
      title: 'My Fleet',
      sourceUrl: 'https://plugins.test/p.js',
      mount: () => undefined,
      ...over,
    });

    it('renders a nav link for a loaded plugin', () => {
      usePluginsMock.mockReturnValue({ plugins: [plugin()], loading: false });
      render(<Sidebar />);
      const link = screen.getByRole('link', { name: /My Fleet/ });
      expect(link).toHaveAttribute('href', '/myfleet');
    });

    it('keeps plugin links after the built-in navigation', () => {
      usePluginsMock.mockReturnValue({ plugins: [plugin()], loading: false });
      render(<Sidebar />);
      const names = screen.getAllByRole('link').map((l) => l.textContent);
      expect(names[names.length - 1]).toContain('My Fleet');
    });

    it('hides an admin-only plugin from a non-admin', () => {
      mockCan(false);
      usePluginsMock.mockReturnValue({ plugins: [plugin({ requires: 'admin' })], loading: false });
      render(<Sidebar />);
      expect(screen.queryByText('My Fleet')).toBeNull();
    });

    it('hides an action-gated plugin the caller lacks the action for', () => {
      mockCan(false);
      usePluginsMock.mockReturnValue({ plugins: [plugin({ requires: 'control' })], loading: false });
      render(<Sidebar />);
      expect(screen.queryByText('My Fleet')).toBeNull();
    });

    it('shows an ungated plugin to a non-admin', () => {
      mockCan(false);
      usePluginsMock.mockReturnValue({ plugins: [plugin()], loading: false });
      render(<Sidebar />);
      expect(screen.getByText('My Fleet')).toBeInTheDocument();
    });
  });
});
