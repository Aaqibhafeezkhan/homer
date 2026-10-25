import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const login = vi.fn();
const push = vi.fn();
const upsertInstance = vi.fn();
const setActiveInstance = vi.fn();
const searchParamsMock = vi.hoisted(() => vi.fn());
// Mutable per-test auth config; default cookie-only (bearer off) matches prod default.
const appConfigMock = vi.hoisted(() => ({
  api: { baseUrl: 'http://localhost:8080', timeout: 30000, allowedOrigins: [] as string[] },
  auth: { enabled: false, mode: 'cookie', allowBearer: false, tokenKey: 'homer_auth_token' },
}));
// Mutable per-test active instance; the seeded default is cookie-mode unless the
// deployment offers bearer sign-in (see lib/instances/store.ts seedDefault).
const instancesMock = vi.hoisted(() => ({
  instances: [
    { id: 'default', label: 'Default', baseUrl: 'http://localhost:8080', mode: 'cookie' },
  ] as { id: string; label: string; baseUrl: string; mode: 'cookie' | 'bearer' }[],
  activeInstanceId: 'default',
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push }),
  useSearchParams: () => searchParamsMock(),
}));
vi.mock('@/lib/config/app-config', () => ({ appConfig: appConfigMock }));
vi.mock('@/lib/auth/auth-context', () => ({ useAuth: () => ({ login }) }));
vi.mock('@/lib/instances/use-instances', () => ({
  useInstances: () => ({ ...instancesMock, upsertInstance, setActiveInstance }),
}));

import LoginPage from './page';

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <LoginPage />
    </QueryClientProvider>,
  );
}

/** The active instance as `seedDefault` leaves it for the given deployment. */
function setActiveInstanceMode(mode: 'cookie' | 'bearer') {
  instancesMock.instances = [
    { id: 'default', label: 'Default', baseUrl: 'http://localhost:8080', mode },
  ];
}

/** Fill in and submit the sign-in form. */
async function signIn(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText(/username/i), 'alice');
  await user.type(screen.getByLabelText(/^password$/i), 'pw');
  await user.click(screen.getByRole('button', { name: /^login$/i }));
}

describe('LoginPage', () => {
  beforeEach(() => {
    login.mockReset();
    push.mockReset();
    upsertInstance.mockReset();
    setActiveInstance.mockReset();
    searchParamsMock.mockReturnValue(new URLSearchParams(''));
    appConfigMock.auth.allowBearer = false;
    appConfigMock.api.allowedOrigins = [];
    setActiveInstanceMode('cookie');
  });

  it('folds the endpoint by default, summarizing the active instance host', async () => {
    const user = userEvent.setup();
    renderPage();
    // Endpoint input is hidden behind a disclosure that names the host.
    expect(screen.queryByRole('textbox', { name: /instance endpoint/i })).not.toBeInTheDocument();
    const change = screen.getByRole('button', { name: /change instance endpoint/i });
    expect(change).toHaveTextContent('localhost:8080');
    expect(screen.getByLabelText(/username/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/^password$/i)).toBeInTheDocument();
    // Expanding reveals the input prefilled with the active base URL.
    await user.click(change);
    expect(screen.getByLabelText(/instance endpoint/i)).toHaveValue('http://localhost:8080');
  });

  it('forces the endpoint open and warns when seeded from the URL with an unknown host', () => {
    searchParamsMock.mockReturnValue(new URLSearchParams('endpoint=https://attacker.example&mode=bearer'));
    renderPage();
    // Field is revealed (not folded behind the disclosure button) and prefilled.
    expect(screen.getByLabelText(/instance endpoint/i)).toHaveValue('https://attacker.example');
    // A warning names the credential-redirection risk.
    expect(screen.getByText(/came from the page link/i)).toBeInTheDocument();
  });

  it('blocks login on a link-seeded untrusted endpoint until trust is confirmed', async () => {
    searchParamsMock.mockReturnValue(new URLSearchParams('endpoint=https://attacker.example'));
    login.mockResolvedValue('ok');
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText(/username/i), 'alice');
    await user.type(screen.getByLabelText(/^password$/i), 'pw');

    // Login is disabled until the operator ticks the trust checkbox, so a shared
    // ?endpoint= link can't silently redirect the credential POST.
    const submit = screen.getByRole('button', { name: /^login$/i });
    expect(submit).toBeDisabled();
    expect(login).not.toHaveBeenCalled();

    await user.click(screen.getByRole('checkbox', { name: /trust this endpoint/i }));
    expect(submit).toBeEnabled();
    await user.click(submit);

    expect(login).toHaveBeenCalledWith('alice', 'pw');
    expect(upsertInstance).toHaveBeenCalledWith(
      expect.objectContaining({ baseUrl: 'https://attacker.example' }),
    );
  });

  it('does not warn when the URL endpoint matches a saved instance', () => {
    searchParamsMock.mockReturnValue(new URLSearchParams('endpoint=http://localhost:8080'));
    renderPage();
    expect(screen.queryByText(/came from the page link/i)).not.toBeInTheDocument();
    // Known endpoint stays folded behind the disclosure.
    expect(screen.queryByRole('textbox', { name: /instance endpoint/i })).not.toBeInTheDocument();
  });

  it('logs into the matched instance and routes to /processes on success', async () => {
    login.mockResolvedValue('ok');
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText(/username/i), 'alice');
    await user.type(screen.getByLabelText(/^password$/i), 'pw');
    await user.click(screen.getByRole('button', { name: /^login$/i }));

    expect(upsertInstance).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'default', baseUrl: 'http://localhost:8080', mode: 'cookie' }),
    );
    expect(setActiveInstance).toHaveBeenCalledWith('default');
    expect(login).toHaveBeenCalledWith('alice', 'pw');
    await waitFor(() => expect(push).toHaveBeenCalledWith('/processes'));
  });

  it('shows an invalid-credentials message when login rejects the password', async () => {
    login.mockResolvedValue('invalid-credentials');
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText(/username/i), 'alice');
    await user.type(screen.getByLabelText(/^password$/i), 'wrong');
    await user.click(screen.getByRole('button', { name: /^login$/i }));

    expect(await screen.findByText(/invalid credentials/i)).toBeInTheDocument();
    expect(push).not.toHaveBeenCalled();
  });

  it('shows a wait message and disables the button when login is rate-limited', async () => {
    login.mockResolvedValue('rate-limited');
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText(/username/i), 'alice');
    await user.type(screen.getByLabelText(/^password$/i), 'pw');
    await user.click(screen.getByRole('button', { name: /^login$/i }));

    // Distinct from the invalid-credentials copy — the user must not think it's a typo.
    expect(await screen.findByText(/too many login attempts/i)).toBeInTheDocument();
    expect(screen.queryByText(/invalid credentials/i)).not.toBeInTheDocument();
    // Button is disabled with a countdown so it can't be hammered during the window.
    const submit = screen.getByRole('button', { name: /try again in/i });
    expect(submit).toBeDisabled();
    expect(push).not.toHaveBeenCalled();
  });

  it('surfaces a server-unreachable message on a non-auth error', async () => {
    login.mockResolvedValue('error');
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText(/username/i), 'alice');
    await user.type(screen.getByLabelText(/^password$/i), 'pw');
    await user.click(screen.getByRole('button', { name: /^login$/i }));

    expect(await screen.findByText(/could not reach the server/i)).toBeInTheDocument();
    expect(push).not.toHaveBeenCalled();
  });

  it('hides the username field and submits a token in bearer mode', async () => {
    appConfigMock.auth.allowBearer = true;
    login.mockResolvedValue('ok');
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('radio', { name: /token/i }));
    expect(screen.queryByLabelText(/username/i)).not.toBeInTheDocument();

    await user.type(screen.getByLabelText(/token/i), 'my-token');
    await user.click(screen.getByRole('button', { name: /^login$/i }));

    expect(login).toHaveBeenCalledWith('', 'my-token');
    await waitFor(() => expect(push).toHaveBeenCalledWith('/processes'));
  });

  it('creates a new instance when the endpoint is unknown', async () => {
    login.mockResolvedValue('ok');
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /change instance endpoint/i }));
    const endpoint = screen.getByLabelText(/instance endpoint/i);
    await user.clear(endpoint);
    await user.type(endpoint, 'https://homer-b.example.com');
    await user.type(screen.getByLabelText(/username/i), 'bob');
    await user.type(screen.getByLabelText(/^password$/i), 'pw');
    await user.click(screen.getByRole('button', { name: /^login$/i }));

    expect(upsertInstance).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'homer-b-example-com', baseUrl: 'https://homer-b.example.com' }),
    );
    expect(setActiveInstance).toHaveBeenCalledWith('homer-b-example-com');
  });

  it('shows an error and does not log in on an invalid endpoint', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /change instance endpoint/i }));
    const endpoint = screen.getByLabelText(/instance endpoint/i);
    await user.clear(endpoint);
    await user.type(endpoint, 'not a url');
    await user.type(screen.getByLabelText(/username/i), 'alice');
    await user.type(screen.getByLabelText(/^password$/i), 'pw');
    await user.click(screen.getByRole('button', { name: /^login$/i }));

    expect(login).not.toHaveBeenCalled();
    expect(screen.getByText(/invalid|required|http/i)).toBeInTheDocument();
  });

  it('warns about mixed content when an https console targets an http endpoint', () => {
    const original = window.location;
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...original, protocol: 'https:' },
    });
    renderPage();
    // Default endpoint is http://localhost:8080 → mixed content under https.
    expect(screen.getByText(/mixed content/i)).toBeInTheDocument();
    Object.defineProperty(window, 'location', { configurable: true, value: original });
  });

  it('shows an expired-session banner when ?expired=1 is in the URL', () => {
    searchParamsMock.mockReturnValue(new URLSearchParams('expired=1'));
    renderPage();
    expect(screen.getByRole('alert')).toHaveTextContent(/session expired/i);
  });

  it('hides the auth-mode toggle and stays cookie-only by default', () => {
    renderPage();
    // No Token radio / auth-mode radiogroup when bearer is disabled.
    expect(screen.queryByRole('radio', { name: /token/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('radiogroup', { name: /authentication mode/i })).not.toBeInTheDocument();
    // Cookie (password) fields are shown.
    expect(screen.getByLabelText(/username/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/^password$/i)).toBeInTheDocument();
  });

  it('shows the auth-mode toggle when bearer is allowed', () => {
    appConfigMock.auth.allowBearer = true;
    renderPage();
    expect(screen.getByRole('radio', { name: /token/i })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /password/i })).toBeInTheDocument();
  });

  it('ignores ?mode=bearer from the URL when bearer is disabled', () => {
    searchParamsMock.mockReturnValue(new URLSearchParams('mode=bearer'));
    renderPage();
    // Forced to cookie mode: the username (password-mode) field is present and no token field.
    expect(screen.getByLabelText(/username/i)).toBeInTheDocument();
    expect(screen.queryByRole('radio', { name: /token/i })).not.toBeInTheDocument();
  });

  it('rejects an off-allowlist endpoint on submit and does not log in', async () => {
    appConfigMock.api.allowedOrigins = ['http://localhost:8080'];
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /change instance endpoint/i }));
    const endpoint = screen.getByLabelText(/instance endpoint/i);
    await user.clear(endpoint);
    await user.type(endpoint, 'https://attacker.example');
    await user.type(screen.getByLabelText(/username/i), 'alice');
    await user.type(screen.getByLabelText(/^password$/i), 'pw');
    await user.click(screen.getByRole('button', { name: /^login$/i }));

    expect(screen.getByText(/allowed backend list/i)).toBeInTheDocument();
    expect(login).not.toHaveBeenCalled();
  });

  it('allows an allowlisted endpoint to log in', async () => {
    appConfigMock.api.allowedOrigins = ['http://localhost:8080'];
    login.mockResolvedValue('ok');
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText(/username/i), 'alice');
    await user.type(screen.getByLabelText(/^password$/i), 'pw');
    await user.click(screen.getByRole('button', { name: /^login$/i }));

    expect(login).toHaveBeenCalledWith('alice', 'pw');
    await waitFor(() => expect(push).toHaveBeenCalledWith('/processes'));
  });

  it('returns to the requested console path after a successful sign-in', async () => {
    searchParamsMock.mockReturnValue(new URLSearchParams({ next: '/processes/1' }));
    login.mockResolvedValue('ok');
    const user = userEvent.setup();
    renderPage();

    await signIn(user);

    await waitFor(() => expect(push).toHaveBeenCalledWith('/processes/1'));
  });

  it.each([
    ['an absolute URL', 'https://evil.test/x'],
    ['a protocol-relative URL', '//evil.test'],
    ['a login-loop target', '/login'],
  ])('refuses %s as a return path and lands on /processes', async (_label, next) => {
    searchParamsMock.mockReturnValue(new URLSearchParams({ next }));
    login.mockResolvedValue('ok');
    const user = userEvent.setup();
    renderPage();

    await signIn(user);

    await waitFor(() => expect(push).toHaveBeenCalledWith('/processes'));
  });

  it('shows a neutral prompt to a reader who never had a session', () => {
    searchParamsMock.mockReturnValue(new URLSearchParams({ next: '/processes/1' }));
    renderPage();
    expect(screen.getByRole('status')).toHaveTextContent(/sign in to continue/i);
    expect(screen.queryByText(/session expired/i)).not.toBeInTheDocument();
  });

  it('shows the expired banner, and no neutral prompt, for a real expiry', () => {
    searchParamsMock.mockReturnValue(
      new URLSearchParams({ next: '/processes/1', expired: '1' }),
    );
    renderPage();
    expect(screen.getByRole('alert')).toHaveTextContent(/session expired/i);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('shows no prompt at all when the reader arrived at /login directly', () => {
    renderPage();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('lets a forged expired flag change the wording only', async () => {
    searchParamsMock.mockReturnValue(new URLSearchParams('expired=1'));
    login.mockResolvedValue('ok');
    const user = userEvent.setup();
    renderPage();

    // Wording only: the mode and the post-login destination are untouched.
    expect(screen.getByRole('alert')).toHaveTextContent(/session expired/i);
    expect(screen.getByLabelText(/username/i)).toBeInTheDocument();

    await signIn(user);

    await waitFor(() => expect(push).toHaveBeenCalledWith('/processes'));
  });

  it('preselects Token when bearer is the deployment default', () => {
    appConfigMock.auth.allowBearer = true;
    // No saved mode resolves (no active instance), so the `?? 'bearer'`
    // deployment default alone decides — a saved mode has the sibling test
    // above.
    instancesMock.instances = [];
    renderPage();
    expect(screen.getByRole('radio', { name: /token/i })).toBeChecked();
    expect(screen.queryByLabelText(/username/i)).not.toBeInTheDocument();
  });

  it('lets a saved cookie instance win over the bearer default', () => {
    appConfigMock.auth.allowBearer = true;
    setActiveInstanceMode('cookie');
    renderPage();
    expect(screen.getByRole('radio', { name: /password/i })).toBeChecked();
    expect(screen.getByLabelText(/username/i)).toBeInTheDocument();
  });

  it('lets an explicit ?mode= param win over the bearer default', () => {
    appConfigMock.auth.allowBearer = true;
    setActiveInstanceMode('bearer');
    searchParamsMock.mockReturnValue(new URLSearchParams('mode=cookie'));
    renderPage();
    expect(screen.getByRole('radio', { name: /password/i })).toBeChecked();
    expect(screen.getByLabelText(/username/i)).toBeInTheDocument();
  });
});
