import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';

const push = vi.fn();
const authState = vi.hoisted(() => ({ status: 'authed' as string }));
const expiredHandled = vi.hoisted(() => ({ current: false }));
const appConfigMock = vi.hoisted(() => ({ auth: { enabled: true } }));
const streams = vi.hoisted(() => ({
  useProcessStatusStream: vi.fn(),
  useAgentReloadStream: vi.fn(),
}));
const markInAppNavigation = vi.hoisted(() => vi.fn());

vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));
vi.mock('@/lib/auth/auth-context', () => ({ useAuth: () => ({ status: authState.status }) }));
vi.mock('@/lib/config/app-config', () => ({ appConfig: appConfigMock }));
vi.mock('@/lib/auth/use-auth-expired-handler', () => ({
  useAuthExpiredHandler: () => expiredHandled,
}));
vi.mock('@/lib/api/streams', () => streams);
vi.mock('@/lib/hooks/use-mark-in-app-navigation', () => ({
  useMarkInAppNavigation: markInAppNavigation,
}));
vi.mock('@/lib/plugins/plugins-context', () => ({
  PluginsProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('@/components/layout/header', () => ({ Header: () => <div>header</div> }));
vi.mock('@/components/layout/sidebar', () => ({ Sidebar: () => <div>sidebar</div> }));
vi.mock('@/components/layout/version-badge', () => ({
  VersionBadge: () => <div>version</div>,
}));

import DashboardLayout from './layout';

function renderLayout() {
  return render(
    <DashboardLayout>
      <p>page body</p>
    </DashboardLayout>,
  );
}

describe('DashboardLayout', () => {
  beforeEach(() => {
    push.mockReset();
    authState.status = 'authed';
    expiredHandled.current = false;
    appConfigMock.auth.enabled = true;
  });

  it('renders the shell around the page once authenticated', () => {
    renderLayout();
    expect(screen.getByRole('main')).toHaveTextContent('page body');
    expect(screen.getByText('header')).toBeInTheDocument();
    expect(screen.getByText('sidebar')).toBeInTheDocument();
    expect(screen.getByText('version')).toBeInTheDocument();
    expect(push).not.toHaveBeenCalled();
  });

  it('subscribes the global streams and in-app navigation marker', () => {
    renderLayout();
    expect(streams.useProcessStatusStream).toHaveBeenCalled();
    expect(streams.useAgentReloadStream).toHaveBeenCalled();
    expect(markInAppNavigation).toHaveBeenCalled();
  });

  it('shows a loading state and does not redirect while auth is loading', () => {
    authState.status = 'loading';
    renderLayout();
    expect(screen.getByText('Loading...')).toBeInTheDocument();
    expect(screen.queryByText('page body')).not.toBeInTheDocument();
    expect(push).not.toHaveBeenCalled();
  });

  it('redirects an anonymous user to /login and renders nothing', () => {
    authState.status = 'anon';
    const { container } = renderLayout();
    expect(push).toHaveBeenCalledWith('/login');
    expect(container).toBeEmptyDOMElement();
  });

  it('does not push /login again once expiry recovery already redirected', () => {
    authState.status = 'anon';
    expiredHandled.current = true;
    renderLayout();
    expect(push).not.toHaveBeenCalled();
  });

  it('renders the shell for an anonymous user when auth is disabled', () => {
    authState.status = 'anon';
    appConfigMock.auth.enabled = false;
    renderLayout();
    expect(push).not.toHaveBeenCalled();
    expect(screen.getByRole('main')).toHaveTextContent('page body');
  });
});
