import { describe, it, expect } from 'vitest';
import { buildCsp, isEditorPath, parseAllowedApiOrigins } from './csp';

describe('isEditorPath', () => {
  it('matches only the agent edit route', () => {
    expect(isEditorPath('/agents/my-agent/edit')).toBe(true);
    expect(isEditorPath('/agents/my-agent/edit/')).toBe(true);
    expect(isEditorPath('/agents/my-agent')).toBe(false);
    expect(isEditorPath('/agents')).toBe(false);
    expect(isEditorPath('/processes')).toBe(false);
    expect(isEditorPath('/agents/a/edit/extra')).toBe(false);
  });
});

describe('parseAllowedApiOrigins', () => {
  it('splits, trims, and drops empties', () => {
    expect(parseAllowedApiOrigins(undefined)).toEqual([]);
    expect(parseAllowedApiOrigins('')).toEqual([]);
    expect(parseAllowedApiOrigins('https://a.example, https://b.example ,')).toEqual([
      'https://a.example',
      'https://b.example',
    ]);
  });
});

describe('buildCsp', () => {
  const base = { nonce: 'NONCE123', allowedApiOrigins: [] as string[] };

  it('is strict on non-editor routes: nonce + strict-dynamic, no unsafe-eval/inline in script-src', () => {
    const csp = buildCsp({ ...base, pathname: '/processes' });
    expect(csp).toContain("script-src 'self' 'nonce-NONCE123' 'strict-dynamic'");
    expect(csp).not.toContain('unsafe-eval');
    // script-src must not carry unsafe-inline (style-src legitimately does).
    const scriptDirective = csp.split('; ').find((d) => d.startsWith('script-src'))!;
    expect(scriptDirective).not.toContain("'unsafe-inline'");
    expect(csp).toContain("worker-src 'self'");
    expect(csp).toContain("object-src 'none'");
    expect(csp).toContain("frame-ancestors 'none'");
  });

  it('relaxes only the editor route for Monaco (unsafe-eval + blob workers)', () => {
    const csp = buildCsp({ ...base, pathname: '/agents/foo/edit' });
    const scriptDirective = csp.split('; ').find((d) => d.startsWith('script-src'))!;
    expect(scriptDirective).toContain("'unsafe-eval'");
    expect(scriptDirective).toContain('blob:');
    expect(csp).toContain("worker-src 'self' blob:");
  });

  it('leaves connect-src open when no allowlist is configured', () => {
    const csp = buildCsp({ ...base, pathname: '/processes' });
    expect(csp).toContain("connect-src 'self' https: http:");
  });

  it('pins connect-src to the allowlist when configured', () => {
    const csp = buildCsp({
      ...base,
      pathname: '/processes',
      allowedApiOrigins: ['https://homer.example.com'],
    });
    expect(csp).toContain("connect-src 'self' https://homer.example.com");
    expect(csp).not.toContain('https: http:');
  });
});
