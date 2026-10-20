'use client';

import { Suspense, useEffect, useState, type ComponentProps } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/lib/auth/auth-context';
import { useInstances } from '@/lib/instances/use-instances';
import type { Instance, InstanceMode } from '@/lib/instances/store';
import type { LoginOutcome } from '@/lib/auth/auth-context';
import {
  instanceIdFromUrl,
  isMixedContent,
  isOriginAllowed,
  labelFromUrl,
  normalizeBaseUrl,
} from '@/lib/instances/url';
import { appConfig } from '@/lib/config/app-config';
import { EndpointField } from '@/components/auth/endpoint-field';
import { AuthModeToggle } from '@/components/auth/auth-mode-toggle';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';

// The URL's `endpoint`, else the active instance's, else empty.
function initialEndpoint(endpointParam: string | null, active: Instance | undefined): string {
  return endpointParam ?? active?.baseUrl ?? '';
}

function normalizeMode(raw: string | null | undefined): InstanceMode | null {
  return raw === 'cookie' || raw === 'bearer' ? raw : null;
}

// The URL's `mode`, else the saved instance's, else cookie — and always cookie
// unless bearer is allowed.
function initialMode(
  allowBearer: boolean,
  raw: string | null | undefined,
  saved: InstanceMode | undefined,
): InstanceMode {
  return allowBearer ? (normalizeMode(raw) ?? saved ?? 'cookie') : 'cookie';
}

// Client-side courtesy cooldown after a 429 from the login limiter. Matches the
// server's default `loginRateLimit.refillPeriod` (60s); it only gates the button
// so a frustrated user can't keep hammering login and draining the refilling
// bucket. If the server's window differs, a premature retry just re-arms it.
const RATE_LIMIT_COOLDOWN_SECONDS = 60;

// Username (cookie mode only) and password, or the bearer token in its place.
function CredentialFields({
  bearerMode,
  username,
  password,
  onUsernameChange,
  onPasswordChange,
}: {
  bearerMode: boolean;
  username: string;
  password: string;
  onUsernameChange: (value: string) => void;
  onPasswordChange: (value: string) => void;
}) {
  return (
    <>
      {!bearerMode && (
        <div className="space-y-2">
          <Label htmlFor="username">Username</Label>
          <Input
            id="username"
            type="text"
            autoComplete="username"
            placeholder="Enter username"
            value={username}
            onChange={(e) => onUsernameChange(e.target.value)}
            required
          />
        </div>
      )}
      <div className="space-y-2">
        <Label htmlFor="password">{bearerMode ? 'Token' : 'Password'}</Label>
        <Input
          id="password"
          type="password"
          autoComplete={bearerMode ? 'off' : 'current-password'}
          placeholder={bearerMode ? 'Enter bearer token' : 'Enter password'}
          value={password}
          onChange={(e) => onPasswordChange(e.target.value)}
          required
        />
      </div>
    </>
  );
}

// Disabled while a login is in flight, during the post-429 cooldown, or while
// an untrusted link-seeded endpoint is unconfirmed.
function LoginButton({
  isLoading,
  cooldown,
  blocked,
}: {
  isLoading: boolean;
  cooldown: number;
  blocked: boolean;
}) {
  return (
    <Button
      type="submit"
      className="w-full"
      disabled={isLoading || cooldown > 0 || blocked}
    >
      {cooldown > 0 ? `Try again in ${cooldown}s` : isLoading ? 'Authenticating...' : 'Login'}
    </Button>
  );
}

// The trust state of the endpoint. An endpoint supplied via the URL that is not
// one of the operator's saved instances is attacker-suppliable: a link to this
// trusted origin can point the credential POST at any host.
function endpointTrust(
  instances: Instance[],
  endpointParam: string | null,
  endpoint: string,
): { urlSeededUntrusted: boolean; requiresTrustConfirmation: boolean } {
  const seededEndpoint = (() => {
    if (!endpointParam) return null;
    try {
      return normalizeBaseUrl(endpointParam);
    } catch {
      return endpointParam;
    }
  })();
  const urlSeededUntrusted =
    seededEndpoint != null && !instances.some((i) => i.baseUrl === seededEndpoint);
  // Whether the endpoint currently in the field is one the operator already
  // trusts (a saved instance). Editing a link-seeded endpoint to a known
  // instance clears the confirmation requirement below.
  const currentEndpointTrusted = (() => {
    try {
      return instances.some((i) => i.baseUrl === normalizeBaseUrl(endpoint));
    } catch {
      return false;
    }
  })();
  // A link-seeded endpoint that isn't a saved instance must be explicitly
  // confirmed before login: without this, a shared `?endpoint=attacker` link
  // silently redirects the operator's credential/token POST to that origin. A
  // manually-typed endpoint is the operator's own trust decision and is not gated.
  return { urlSeededUntrusted, requiresTrustConfirmation: urlSeededUntrusted && !currentEndpointTrusted };
}

// The normalized endpoint to sign in against, or why the sign-in must not proceed.
function checkEndpoint(
  endpoint: string,
  allowedOrigins: string[],
  blocked: boolean,
): { baseUrl: string } | { error: string } {
  let baseUrl: string;
  try {
    baseUrl = normalizeBaseUrl(endpoint);
  } catch (err) {
    return { error: err instanceof Error ? err.message : 'Invalid endpoint URL' };
  }

  // When the operator has pinned an allowlist, reject an off-allowlist endpoint
  // here with a clear message (the CSP connect-src would otherwise block the
  // request opaquely). Reinforces M2: an attacker-supplied endpoint is refused,
  // not just warned about.
  if (!isOriginAllowed(baseUrl, allowedOrigins)) {
    return { error: 'This endpoint is not in this console’s allowed backend list.' };
  }

  // A link-seeded, untrusted endpoint must be explicitly confirmed before the
  // credential POST. The button is also disabled until then; this guard is the
  // belt-and-suspenders backstop.
  if (blocked) {
    return { error: 'Confirm you trust this endpoint before signing in.' };
  }
  return { baseUrl };
}

// The error shown for a login that did not succeed.
function loginFailureMessage(outcome: Exclude<LoginOutcome, 'ok'>, bearerMode: boolean): string {
  if (outcome === 'rate-limited') return 'Too many login attempts. Please wait a moment before trying again.';
  if (outcome === 'error') return 'Could not reach the server. Check the instance endpoint and try again.';
  return bearerMode ? 'Invalid token. Please try again.' : 'Invalid credentials. Please try again.';
}

function LoginCardHeader({ bearerMode }: { bearerMode: boolean }) {
  return (
    <CardHeader>
      <CardTitle>Homer Console</CardTitle>
      <CardDescription>
        {bearerMode
          ? 'Choose an instance and enter your authentication token'
          : 'Choose an instance and sign in with your username and password'}
      </CardDescription>
    </CardHeader>
  );
}

function SessionExpiredNotice() {
  return (
    <div
      role="alert"
      className="mb-4 rounded-md border border-yellow-500/40 bg-yellow-500/10 p-3 text-sm text-yellow-700"
    >
      Your session expired. Please log in again.
    </div>
  );
}

// The post-429 cooldown in seconds, ticking down to zero one second at a time.
function useCooldown() {
  const [cooldown, setCooldown] = useState(0);
  useEffect(() => {
    if (cooldown <= 0) return;
    const t = setTimeout(() => setCooldown((c) => c - 1), 1000);
    return () => clearTimeout(t);
  }, [cooldown]);
  return [cooldown, setCooldown] as const;
}

// The endpoint field's value, fold and trust confirmation, and what follows from them.
function useEndpointField(instances: Instance[], endpointParam: string | null, active: Instance | undefined) {
  const [endpoint, setEndpoint] = useState(initialEndpoint(endpointParam, active));
  const [showEndpoint, setShowEndpoint] = useState(false);
  const [trustConfirmed, setTrustConfirmed] = useState(false);
  const mixed = isMixedContent(endpoint);
  // Force the endpoint field open and warn on an untrusted link-seeded endpoint,
  // so the operator confirms the target before typing a password/token.
  const { urlSeededUntrusted, requiresTrustConfirmation } = endpointTrust(
    instances,
    endpointParam,
    endpoint,
  );
  const blocked = requiresTrustConfirmation && !trustConfirmed;
  const reveal = () => setShowEndpoint(true);
  const fieldProps = {
    // Folded by default; force open when something needs the operator's eyes on
    // the endpoint (mixed-content block, or an untrusted URL-seeded endpoint).
    visible: showEndpoint || mixed || urlSeededUntrusted,
    endpoint,
    onEndpointChange: (value: string) => {
      setEndpoint(value);
      setTrustConfirmed(false);
    },
    onShow: reveal,
    requiresTrustConfirmation,
    trustConfirmed,
    onTrustConfirmedChange: setTrustConfirmed,
    mixed,
  };
  return { endpoint, blocked, reveal, fieldProps };
}

type InstancesStore = ReturnType<typeof useInstances>;

// Reuse an instance already pointed at this base URL (e.g. the seeded
// default) rather than creating a duplicate; otherwise mint a new one.
function activateInstance(store: InstancesStore, baseUrl: string, mode: InstanceMode) {
  const existing = store.instances.find((i) => i.baseUrl === baseUrl);
  const id = existing?.id ?? instanceIdFromUrl(baseUrl);
  store.upsertInstance({ id, label: existing?.label ?? labelFromUrl(baseUrl), baseUrl, mode });
  store.setActiveInstance(id);
}

interface LoginAttempt {
  endpoint: string;
  blocked: boolean;
  mode: InstanceMode;
  username: string;
  password: string;
}

// The form's submit handler with its error and in-flight state: checks the
// endpoint, activates its instance, then logs in.
function useLoginSubmit(
  store: InstancesStore,
  attempt: LoginAttempt,
  revealEndpoint: () => void,
  startCooldown: () => void,
) {
  const { login } = useAuth();
  const queryClient = useQueryClient();
  const router = useRouter();
  const [error, setError] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const allowedOrigins = appConfig.api.allowedOrigins;

  const handleSubmit: NonNullable<ComponentProps<'form'>['onSubmit']> = async (e) => {
    e.preventDefault();
    setError('');

    const checked = checkEndpoint(attempt.endpoint, allowedOrigins, attempt.blocked);
    if ('error' in checked) {
      revealEndpoint(); // reveal the field so the operator can fix it
      setError(checked.error);
      return;
    }
    activateInstance(store, checked.baseUrl, attempt.mode);
    // Drop anything cached for a previously-active instance before logging in.
    queryClient.clear();

    setIsLoading(true);
    const outcome = await login(attempt.username, attempt.password);
    setIsLoading(false);

    if (outcome === 'ok') {
      router.push('/processes');
      return;
    }
    if (outcome === 'rate-limited') startCooldown();
    setError(loginFailureMessage(outcome, attempt.mode === 'bearer'));
  };
  return { error, isLoading, handleSubmit };
}

function LoginForm() {
  const store = useInstances();
  const searchParams = useSearchParams();
  const expired = searchParams?.get('expired') === '1';

  // Bearer mode is opt-in (CONSOLE_ALLOW_BEARER): its token lives in localStorage
  // and is XSS-exfiltratable, so the console is cookie-only unless the operator
  // enables it. When off, ignore any bearer request (URL param or saved instance)
  // and never offer the toggle. See the 2026-07-12 security review (H3).
  const allowBearer = appConfig.auth.allowBearer;
  const active = store.instances.find((i) => i.id === store.activeInstanceId);
  const endpointParam = searchParams?.get('endpoint') ?? null;
  const endpointField = useEndpointField(store.instances, endpointParam, active);
  const [mode, setMode] = useState<InstanceMode>(
    initialMode(allowBearer, searchParams?.get('mode'), active?.mode),
  );
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [cooldown, setCooldown] = useCooldown();
  const { error, isLoading, handleSubmit } = useLoginSubmit(
    store,
    { endpoint: endpointField.endpoint, blocked: endpointField.blocked, mode, username, password },
    endpointField.reveal,
    () => setCooldown(RATE_LIMIT_COOLDOWN_SECONDS),
  );
  const bearerMode = mode === 'bearer';

  return (
    <Card className="w-full max-w-md">
      <LoginCardHeader bearerMode={bearerMode} />
      <CardContent>
        {expired && <SessionExpiredNotice />}
        <form onSubmit={handleSubmit} className="space-y-4">
          <EndpointField {...endpointField.fieldProps} bearerMode={bearerMode} />

          {allowBearer && <AuthModeToggle mode={mode} onModeChange={setMode} />}

          <CredentialFields
            bearerMode={bearerMode}
            username={username}
            password={password}
            onUsernameChange={setUsername}
            onPasswordChange={setPassword}
          />
          {error && <p className="text-sm text-destructive">{error}</p>}
          <LoginButton
            isLoading={isLoading}
            cooldown={cooldown}
            blocked={endpointField.blocked}
          />
        </form>
      </CardContent>
    </Card>
  );
}

export default function LoginPage() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-background p-4">
      <Suspense fallback={null}>
        <LoginForm />
      </Suspense>
    </div>
  );
}
