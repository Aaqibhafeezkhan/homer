import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider, useAuth } from './auth-context';

/**
 * Helpers shared by the cookie- and bearer-mode AuthProvider suites. The mode
 * itself comes from each suite's own vi.mock of app-config (read at module
 * load), which is why only these helpers — never the mocks — can be shared.
 */
export function StatusProbe() {
  const { status, user, login, logout } = useAuth();
  return (
    <div>
      <div data-testid="status">{status}</div>
      <div data-testid="username">{user?.username ?? ''}</div>
      <div data-testid="role">{user?.role ?? ''}</div>
      <div data-testid="grant-count">{user?.grants?.length ?? ''}</div>
      <button onClick={() => void login('alice', 'pw')}>do-login</button>
      <button onClick={() => void logout()}>do-logout</button>
    </div>
  );
}

/**
 * A probe whose login() outcome the test reads back, for the login-outcome
 * classification cases shared by the cookie- and bearer-mode suites.
 */
export function LoginOutcomeProbe({
  onResult,
  password = 'pw',
}: {
  onResult: (outcome: string) => void;
  password?: string;
}) {
  const { status, login } = useAuth();
  return (
    <div>
      <div data-testid="status">{status}</div>
      <button onClick={() => void login('alice', password).then(onResult)}>
        do-login
      </button>
    </div>
  );
}

export function renderWithProvider(probe: React.ReactElement = <StatusProbe />) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <AuthProvider>{probe}</AuthProvider>
      </QueryClientProvider>,
    ),
  };
}
