import { describe, it, expect, afterEach } from 'vitest';
import {
  instanceIdFromUrl,
  isMixedContent,
  isOriginAllowed,
  labelFromUrl,
  normalizeBaseUrl,
} from './url';

describe('isOriginAllowed', () => {
  it('permits anything when the allowlist is empty', () => {
    expect(isOriginAllowed('https://anything.example', [])).toBe(true);
  });

  it('matches by origin, ignoring path/trailing slash', () => {
    const allow = ['https://homer.example.com'];
    expect(isOriginAllowed('https://homer.example.com', allow)).toBe(true);
    expect(isOriginAllowed('https://homer.example.com/homer/', allow)).toBe(true);
    expect(isOriginAllowed('https://evil.example.com', allow)).toBe(false);
  });

  it('distinguishes scheme and port', () => {
    const allow = ['https://homer.example.com'];
    expect(isOriginAllowed('http://homer.example.com', allow)).toBe(false);
    expect(isOriginAllowed('https://homer.example.com:8443', allow)).toBe(false);
  });

  it('rejects an unparseable endpoint', () => {
    expect(isOriginAllowed('not a url', ['https://homer.example.com'])).toBe(false);
  });
});

describe('normalizeBaseUrl', () => {
  it('strips trailing slashes', () => {
    expect(normalizeBaseUrl('https://homer.example.com/')).toBe('https://homer.example.com');
    expect(normalizeBaseUrl('https://homer.example.com//')).toBe('https://homer.example.com');
  });

  it('preserves a path prefix (reverse-proxied deployments)', () => {
    expect(normalizeBaseUrl('https://x.example.com/homer/')).toBe('https://x.example.com/homer');
  });

  it('throws on a blank or non-http(s) URL', () => {
    expect(() => normalizeBaseUrl('   ')).toThrow();
    expect(() => normalizeBaseUrl('not a url')).toThrow();
    expect(() => normalizeBaseUrl('ftp://x.example.com')).toThrow();
  });
});

describe('instanceIdFromUrl', () => {
  it('slugifies the scheme-less origin', () => {
    expect(instanceIdFromUrl('https://homer-b.example.com:8443')).toBe('homer-b-example-com-8443');
    expect(instanceIdFromUrl('http://localhost:8080')).toBe('localhost-8080');
  });
});

describe('labelFromUrl', () => {
  it('returns the host', () => {
    expect(labelFromUrl('https://homer.example.com:8443')).toBe('homer.example.com:8443');
  });
});

describe('isMixedContent', () => {
  const original = window.location;
  function setProtocol(protocol: string) {
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...original, protocol },
    });
  }
  afterEach(() => {
    Object.defineProperty(window, 'location', { configurable: true, value: original });
  });

  it('flags an http endpoint under an https page', () => {
    setProtocol('https:');
    expect(isMixedContent('http://homer.example.com')).toBe(true);
    expect(isMixedContent('https://homer.example.com')).toBe(false);
  });

  it('is false under an http page', () => {
    setProtocol('http:');
    expect(isMixedContent('http://homer.example.com')).toBe(false);
  });
});
