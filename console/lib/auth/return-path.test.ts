import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import { DEFAULT_RETURN_PATH, loginHref, safeReturnPath } from './return-path';

describe('safeReturnPath', () => {
  it('accepts a same-origin relative console path', () => {
    expect(safeReturnPath('/processes/1')).toBe('/processes/1');
    expect(safeReturnPath('/agents/my-agent/edit')).toBe('/agents/my-agent/edit');
    expect(safeReturnPath('/')).toBe('/');
  });

  const rejected: [string, string][] = [
    ['an absolute URL', 'https://evil.test/x'],
    ['a scheme-only URL', 'https://evil.test'],
    ['a protocol-relative URL', '//evil.test'],
    ['a backslash-smuggled URL', '/\\evil.test'],
    ['a percent-encoded protocol-relative URL', '/%2F%2Fevil.test'],
    ['a percent-encoded backslash URL', '/%5Cevil.test'],
    ['a tab-smuggled path', '/\t/evil.test'],
    ['a newline-smuggled path', '/\n/evil.test'],
    ['a NUL-smuggled path', '/\u0000/evil.test'],
    ['a javascript: URL', 'javascript:alert(1)'],
    ['a data: URL', 'data:text/html,<script>alert(1)</script>'],
    ['a relative path without a leading slash', 'processes/1'],
    ['a login-loop target', '/login'],
    ['a login-loop target with a query', '/login?x=1'],
    ['a query string', '/processes?endpoint=https://evil.test'],
    ['a fragment', '/processes#mode=bearer'],
    ['a percent-encoded login target', '/%6Cogin'],
    ['malformed percent-encoding', '/%'],
    ['a path-traversal segment', '/../evil.test'],
    ['an empty value', ''],
    ['a dot', '.'],
    ['a value over the length cap', `/${'a'.repeat(2048)}`],
  ];

  it.each(rejected)('falls back to the default for %s', (_label, raw) => {
    expect(safeReturnPath(raw)).toBe(DEFAULT_RETURN_PATH);
  });

  it('falls back to the default for a missing value', () => {
    expect(safeReturnPath(null)).toBe(DEFAULT_RETURN_PATH);
    expect(safeReturnPath(undefined)).toBe(DEFAULT_RETURN_PATH);
  });

  it('accepts a path exactly at the length cap', () => {
    const raw = `/${'a'.repeat(2047)}`;
    expect(raw).toHaveLength(2048);
    expect(safeReturnPath(raw)).toBe(raw);
  });

  it('accepts a percent-encoded path that does not decode to a rejection', () => {
    expect(safeReturnPath('/agents/my%20agent')).toBe('/agents/my%20agent');
  });

  it('always returns the default or a same-origin relative path', () => {
    fc.assert(
      fc.property(fc.string(), (raw) => {
        const out = safeReturnPath(raw);
        if (out === DEFAULT_RETURN_PATH) return;
        expect(out.startsWith('/')).toBe(true);
        expect(out.startsWith('//')).toBe(false);
        expect(out.startsWith('/\\')).toBe(false);
        const parsed = new URL(out, 'http://console.test');
        expect(parsed.origin).toBe('http://console.test');
        expect(parsed.pathname).toBe(out);
      }),
      { numRuns: 500 },
    );
  });

  it('never lets a crafted fragment produce an off-origin target', () => {
    const fragments = [
      '/', '//', '\\', '?', '#', '..', ':', '%2F', '%5C', '%00', '%',
      'https:', 'javascript:', 'data:', 'login', 'processes', 'evil.test',
      '\t', '\n', ' ', '@',
    ];
    fc.assert(
      fc.property(
        fc.array(fc.constantFrom(...fragments), { maxLength: 8 }).map((parts) => parts.join('')),
        (raw) => {
          const out = safeReturnPath(raw);
          if (out === DEFAULT_RETURN_PATH) return;
          expect(out.startsWith('/')).toBe(true);
          expect(out.startsWith('//')).toBe(false);
          expect(new URL(out, 'http://console.test').origin).toBe('http://console.test');
        },
      ),
      { numRuns: 500 },
    );
  });
});

describe('loginHref', () => {
  it('carries the requested path as an encoded next parameter', () => {
    expect(loginHref('/processes/1', false)).toBe('/login?next=%2Fprocesses%2F1');
  });

  it('adds the expired marker only when asked', () => {
    expect(loginHref('/processes/1', true)).toBe('/login?next=%2Fprocesses%2F1&expired=1');
  });

  it('falls back to the default destination for an unsafe path', () => {
    expect(loginHref('https://evil.test/x', false)).toBe('/login?next=%2Fprocesses');
    expect(loginHref(null, false)).toBe('/login?next=%2Fprocesses');
  });
});
