/** @type {import('next').NextConfig} */

// Static response-hardening headers applied to every route (including static
// assets). The Content-Security-Policy is emitted per-request by middleware.ts
// instead — it needs a per-request nonce and a per-route script-src (strict
// everywhere except the Monaco editor route). See lib/security/csp.ts.
const securityHeaders = [
  { key: 'X-Frame-Options', value: 'DENY' },
  { key: 'X-Content-Type-Options', value: 'nosniff' },
  { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
]

const nextConfig = {
  output: 'standalone',
  reactStrictMode: true,
  // The console renders a single logo; server-side image optimization is not
  // worth the ~33 MB sharp (`@img/*`) binaries it drags into the standalone
  // bundle (and the `.next/cache/images` writes it needs). Serve images as-is.
  // Turning this on also re-opens an LGPL-3.0 question — see the prune in
  // Dockerfile and the CI pipeline's image license gate.
  images: { unoptimized: true },
  async headers() {
    return [{ source: '/:path*', headers: securityHeaders }]
  },
}

module.exports = nextConfig
