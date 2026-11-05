import { describe, it, expect, afterEach, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { config, middleware } from './middleware';
import { buildCsp } from '@/lib/security/csp';

const CSP = 'content-security-policy';

function req(pathname = '/'): NextRequest {
  return new NextRequest(`http://console.test${pathname}`, { headers: { 'x-existing': 'kept' } });
}

/** Request headers the middleware forwards to the app, as Next encodes them on the response. */
function forwarded(response: Response): Map<string, string> {
  const names = (response.headers.get('x-middleware-override-headers') ?? '').split(',');
  return new Map(
    names
      .filter(Boolean)
      .map((n) => [n, response.headers.get(`x-middleware-request-${n}`) ?? ''] as const),
  );
}

describe('middleware', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  describe('outside production', () => {
    it.each(['development', 'test'])('passes the request through untouched in %s', (env) => {
      vi.stubEnv('NODE_ENV', env);
      const response = middleware(req());
      expect(response.headers.get(CSP)).toBeNull();
      expect(response.headers.get('x-middleware-next')).toBe('1');
      expect(forwarded(response).has('x-nonce')).toBe(false);
    });
  });

  describe('in production', () => {
    function production(origins?: string): void {
      vi.stubEnv('NODE_ENV', 'production');
      vi.stubEnv('CONSOLE_ALLOWED_API_ORIGINS', origins ?? '');
    }

    it('sets the same CSP on the response and the forwarded request, with the nonce in it', () => {
      production();
      const response = middleware(req('/processes'));
      const csp = response.headers.get(CSP)!;
      const fwd = forwarded(response);
      const nonce = fwd.get('x-nonce')!;

      expect(nonce).toMatch(/^[A-Za-z0-9+/]{22}==$/);
      expect(fwd.get(CSP)).toBe(csp);
      expect(csp).toContain(`'nonce-${nonce}'`);
      expect(csp).toBe(
        buildCsp({ pathname: '/processes', nonce, allowedApiOrigins: [] }),
      );
      expect(response.headers.get('x-middleware-next')).toBe('1');
    });

    it('keeps the incoming request headers when forwarding', () => {
      production();
      expect(forwarded(middleware(req())).get('x-existing')).toBe('kept');
    });

    it('issues a fresh nonce for every request', () => {
      production();
      const nonces = new Set(
        Array.from({ length: 20 }, () => forwarded(middleware(req())).get('x-nonce')),
      );
      expect(nonces.size).toBe(20);
    });

    it('does not leak the nonce into the response headers', () => {
      production();
      expect(middleware(req()).headers.get('x-nonce')).toBeNull();
    });

    it('builds the policy for the request path: only the editor route gets unsafe-eval', () => {
      production();
      expect(middleware(req('/agents/a/edit')).headers.get(CSP)).toContain("'unsafe-eval'");
      expect(middleware(req('/agents')).headers.get(CSP)).not.toContain("'unsafe-eval'");
    });

    it('pins connect-src to the configured API origins', () => {
      production('https://api.one.test, https://api.two.test');
      const csp = middleware(req()).headers.get(CSP)!;
      expect(csp).toContain("connect-src 'self' https://api.one.test https://api.two.test");
      expect(csp).not.toContain("connect-src 'self' https: http:");
    });

    it('leaves connect-src open without an allowlist', () => {
      production();
      expect(middleware(req()).headers.get(CSP)).toContain("connect-src 'self' https: http:");
    });
  });

  describe('matcher', () => {
    const matches = (p: string): boolean => new RegExp(`^${config.matcher[0]}$`).test(p);

    it.each(['/', '/processes', '/agents/a/edit', '/api/x'])('runs on %s', (p) => {
      expect(matches(p)).toBe(true);
    });

    it.each(['/_next/static/chunk.js', '/_next/image', '/favicon.ico', '/icon.png'])(
      'skips %s',
      (p) => {
        expect(matches(p)).toBe(false);
      },
    );

    it('declares exactly one matcher', () => {
      expect(config).toEqual({ matcher: [expect.any(String)] });
    });
  });
});
