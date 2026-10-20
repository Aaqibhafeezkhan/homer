/**
 * Normalize a user-entered endpoint into a base URL the API client can prefix
 * onto `/api/...` paths: validates it parses, and strips trailing slashes.
 * Throws on an invalid URL so callers can surface an inline error.
 */
export function normalizeBaseUrl(raw: string): string {
  const trimmed = raw.trim();
  if (!trimmed) throw new Error('Endpoint URL is required');
  // Throws TypeError on anything that is not a parseable absolute URL.
  const url = new URL(trimmed);
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error('Endpoint must be an http(s) URL');
  }
  return trimmed.replace(/\/+$/, '');
}

/**
 * Derive a stable, localStorage-key-safe instance id from a base URL by
 * slugifying its scheme-less form (e.g. `https://homer-b.example.com:8443` →
 * `homer-b-example-com-8443`). Stable so re-logging into the same endpoint
 * updates the existing instance instead of creating a duplicate.
 */
export function instanceIdFromUrl(baseUrl: string): string {
  return baseUrl
    .replace(/^https?:\/\//, '')
    .replace(/[^a-zA-Z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .toLowerCase();
}

/** Default human label for an instance: the URL's host. */
export function labelFromUrl(baseUrl: string): string {
  try {
    return new URL(baseUrl).host;
  } catch {
    return baseUrl;
  }
}

/**
 * True when an HTTPS-served console would call an `http://` backend — the
 * browser blocks such mixed-content requests, so the UI warns before submit.
 */
export function isMixedContent(baseUrl: string): boolean {
  if (typeof window === 'undefined') return false;
  return window.location.protocol === 'https:' && /^http:\/\//i.test(baseUrl.trim());
}

/**
 * True when `baseUrl`'s origin is permitted by the operator allowlist. An empty
 * allowlist means "unrestricted" (returns true). Comparison is by URL origin, so
 * allowlist entries and the endpoint are compared scheme+host+port, ignoring
 * path/trailing-slash differences. Used to reject an off-allowlist login endpoint
 * (2026-07-12 security review, H3/M2).
 */
export function isOriginAllowed(baseUrl: string, allowedOrigins: string[]): boolean {
  if (allowedOrigins.length === 0) return true;
  let origin: string;
  try {
    origin = new URL(baseUrl).origin;
  } catch {
    return false;
  }
  return allowedOrigins.some((entry) => {
    try {
      return new URL(entry).origin === origin;
    } catch {
      return false;
    }
  });
}
