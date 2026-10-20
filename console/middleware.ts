import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';
import { buildCsp, parseAllowedApiOrigins } from '@/lib/security/csp';

/**
 * Emits a per-request nonce and a per-route Content-Security-Policy (see
 * `lib/security/csp.ts`). The nonce is forwarded on the `x-nonce` request header
 * so the root layout can stamp its inline runtime-config <script>, and Next.js
 * reads it from the CSP header to nonce its own scripts.
 *
 * Skipped in development: React Refresh / HMR rely on eval + inline scripts a
 * strict policy would break.
 */
export function middleware(request: NextRequest): NextResponse {
  if (process.env.NODE_ENV !== 'production') {
    return NextResponse.next();
  }

  const arr = new Uint8Array(16);
  crypto.getRandomValues(arr);
  const nonce = btoa(String.fromCharCode(...arr));

  const csp = buildCsp({
    pathname: request.nextUrl.pathname,
    nonce,
    allowedApiOrigins: parseAllowedApiOrigins(process.env.CONSOLE_ALLOWED_API_ORIGINS),
  });

  const requestHeaders = new Headers(request.headers);
  requestHeaders.set('x-nonce', nonce);
  // Next.js reads the nonce from the CSP on the request headers to nonce its
  // own scripts, so set it on both request and response.
  requestHeaders.set('Content-Security-Policy', csp);

  const response = NextResponse.next({ request: { headers: requestHeaders } });
  response.headers.set('Content-Security-Policy', csp);
  return response;
}

export const config = {
  // Run on document routes; skip static assets and the image optimizer.
  matcher: ['/((?!_next/static|_next/image|favicon.ico|icon.png).*)'],
};
