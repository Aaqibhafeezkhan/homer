import { describe, it, expect, vi, beforeEach } from 'vitest';
import { generateAgentCurlCommand } from './curl';
import { activeBaseUrl } from '@/lib/instances/store';

vi.mock('@/lib/instances/store', () => ({ activeBaseUrl: vi.fn() }));

describe('generateAgentCurlCommand', () => {
  beforeEach(() => {
    vi.mocked(activeBaseUrl).mockReturnValue('http://localhost:8080');
  });

  it('targets the active instance, not a build-time default', () => {
    vi.mocked(activeBaseUrl).mockReturnValue('https://homer-b.example.com');
    const cmd = generateAgentCurlCommand('foo', { queryParams: { id: '42' } });
    expect(cmd).toContain(`curl -X POST 'https://homer-b.example.com/api/v1/agent/foo?id=42'`);
    expect(cmd).not.toContain('localhost');
  });

  it('does not let shell metacharacters in the instance URL expand', () => {
    vi.mocked(activeBaseUrl).mockReturnValue("https://evil.example.com/$(whoami)'`id`");
    const cmd = generateAgentCurlCommand('foo', {});
    expect(cmd).toContain(`curl -X POST 'https://evil.example.com/$(whoami)'\\''\`id\`/api/v1/agent/foo'`);
  });

  it('query-only emits POST with query string', () => {
    const cmd = generateAgentCurlCommand('foo', { queryParams: { id: '42' } });
    expect(cmd).toContain('-X POST');
    expect(cmd).toContain('?id=42');
    expect(cmd).not.toContain('-d ');
    expect(cmd).not.toContain('Content-Type');
  });

  it('body-only emits POST with -d and Content-Type', () => {
    const cmd = generateAgentCurlCommand('foo', { body: { user: 'alice' } });
    expect(cmd).toContain("-H 'Content-Type: application/json'");
    expect(cmd).toContain(`-d '{"user":"alice"}'`);
  });

  it('headers-only emits -H per header', () => {
    const cmd = generateAgentCurlCommand('foo', { headers: { 'X-Trace-Id': 'abc' } });
    expect(cmd).toContain("-H 'X-Trace-Id: abc'");
  });

  it('all three combined', () => {
    const cmd = generateAgentCurlCommand(
      'foo',
      { queryParams: { id: '42' }, body: { user: 'alice' }, headers: { 'X-Trace-Id': 'abc' } },
    );
    expect(cmd).toContain('?id=42');
    expect(cmd).toContain('-H "Authorization: Bearer $HOMER_AUTH_TOKEN"');
    expect(cmd).toContain("-H 'X-Trace-Id: abc'");
    expect(cmd).toContain(`-d '{"user":"alice"}'`);
  });

  it('always emits the auth placeholder header regardless of auth mode', () => {
    // No auth token is ever passed in (formerly the cookie-mode path where
    // getAuthToken() returned null and the header was dropped entirely).
    const cmd = generateAgentCurlCommand('foo', { queryParams: { id: '42' } });
    expect(cmd).toContain('-H "Authorization: Bearer $HOMER_AUTH_TOKEN"');
  });

  it('never emits a real bearer token value (ADR-0047 rule 8 denial path)', () => {
    const cmd = generateAgentCurlCommand(
      'foo',
      { queryParams: { id: '42' }, body: { user: 'alice' }, headers: { 'X-Trace-Id': 'abc' } },
    );
    expect(cmd).toContain('$HOMER_AUTH_TOKEN');
    // No token-looking value may follow "Bearer " — only the shell placeholder.
    expect(cmd).not.toMatch(/Bearer\s+[A-Za-z0-9._-]{8,}/);
  });

  it('escapes single quote in JSON body using POSIX safe sequence', () => {
    const cmd = generateAgentCurlCommand('foo', { body: { name: "o'reilly" } });
    // Single quotes are closed and re-opened around an escaped literal,
    // so the body stays entirely in single-quote context.
    expect(cmd).toContain(`-d '{"name":"o'\\''reilly"}'`);
  });

  it('does not let shell metacharacters in the body expand', () => {
    const cmd = generateAgentCurlCommand(
      'foo',
      { body: { cmd: "it's $(whoami) and `id`" } },
    );
    // The whole payload is wrapped in single quotes; only the embedded `'`
    // is escaped. `$`, `(`, and backticks are inert inside single quotes.
    expect(cmd).toContain(
      `-d '{"cmd":"it'\\''s $(whoami) and \`id\`"}'`,
    );
  });
});
