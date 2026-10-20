/**
 * Content-Security-Policy construction, split out from `middleware.ts` so the
 * policy logic is unit-testable without a request. See the 2026-07-12 security
 * review (H3).
 *
 * The policy is strict everywhere — `script-src 'self' 'nonce-…' 'strict-dynamic'`,
 * no `'unsafe-inline'`/`'unsafe-eval'` — except the single Monaco editor route,
 * which needs `'unsafe-eval'` and `blob:` workers. `connect-src` is pinned to an
 * operator allowlist when configured, otherwise left open so the free-form
 * multi-instance login keeps working.
 */

import { splitCommaList } from '@/lib/utils/list';

/** Monaco (the code editor) is loaded only on `/agents/<name>/edit`. */
export function isEditorPath(pathname: string): boolean {
  return /^\/agents\/[^/]+\/edit\/?$/.test(pathname);
}

export interface CspOptions {
  pathname: string;
  nonce: string;
  /**
   * Operator-configured backend origins the console may connect to. When
   * non-empty, `connect-src` is pinned to `'self'` + these; when empty,
   * `connect-src` stays open (`https:`/`http:`) so an operator can point the
   * console at an ad-hoc backend at login.
   */
  allowedApiOrigins: string[];
}

export function buildCsp({ pathname, nonce, allowedApiOrigins }: CspOptions): string {
  const editor = isEditorPath(pathname);

  const scriptSrc = editor
    ? // Monaco compiles language services via eval and spawns blob: workers.
      "script-src 'self' 'unsafe-inline' 'unsafe-eval' blob:"
    : `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'`;

  const workerSrc = editor ? "worker-src 'self' blob:" : "worker-src 'self'";

  const connectSrc =
    allowedApiOrigins.length > 0
      ? `connect-src 'self' ${allowedApiOrigins.join(' ')}`
      : // No allowlist: the operator may log into any backend they type, so the
        // set of connect targets is unbounded and cannot be enumerated here.
        "connect-src 'self' https: http:";

  return [
    "default-src 'self'",
    scriptSrc,
    // Tailwind/Next inject inline <style>; nonce-ing styles is fragile and style
    // injection is far lower risk than script injection.
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: https:",
    "font-src 'self' data:",
    connectSrc,
    workerSrc,
    "object-src 'none'",
    "base-uri 'self'",
    "frame-ancestors 'none'",
    "form-action 'self'",
  ].join('; ');
}

/** Parses `CONSOLE_ALLOWED_API_ORIGINS` (comma-separated) into a clean list. */
export function parseAllowedApiOrigins(raw: string | undefined): string[] {
  return splitCommaList(raw);
}
