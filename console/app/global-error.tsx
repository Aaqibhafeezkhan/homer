'use client';

import { useBoundaryError, type ErrorBoundaryProps } from '@/lib/hooks/use-boundary-error';

/**
 * Last-resort error boundary. Next.js renders this only when the root layout
 * itself throws, which means the app's providers, theme tokens, and global
 * styles are not available — so this uses inline styles and renders its own
 * `<html>`/`<body>`. Segment errors under `(dashboard)` are handled by the
 * styled boundary in `(dashboard)/error.tsx`; this catches the rare case that
 * would otherwise blank the entire app.
 */
export default function GlobalError({ error, reset }: ErrorBoundaryProps) {
  const details = useBoundaryError(error);

  return (
    <html lang="en">
      <body
        style={{
          fontFamily: 'system-ui, sans-serif',
          display: 'flex',
          minHeight: '100vh',
          alignItems: 'center',
          justifyContent: 'center',
          margin: 0,
        }}
      >
        <div style={{ maxWidth: '32rem', padding: '1.5rem', textAlign: 'center' }}>
          <h1 style={{ fontSize: '1.25rem', fontWeight: 600 }}>Something went wrong</h1>
          <p style={{ color: '#6b7280', fontSize: '0.875rem' }}>
            The console failed to load. Try again, or reload the page if the problem persists.
          </p>
          <pre
            style={{
              overflow: 'auto',
              borderRadius: '0.375rem',
              background: '#f3f4f6',
              padding: '0.75rem',
              fontSize: '0.75rem',
              textAlign: 'left',
            }}
          >
            {details}
          </pre>
          <button
            type="button"
            onClick={reset}
            style={{
              marginTop: '1rem',
              padding: '0.5rem 1rem',
              borderRadius: '0.375rem',
              border: '1px solid #d1d5db',
              cursor: 'pointer',
            }}
          >
            Reload
          </button>
        </div>
      </body>
    </html>
  );
}
