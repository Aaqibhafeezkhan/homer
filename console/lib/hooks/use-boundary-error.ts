'use client';

import { useEffect } from 'react';

/** The props Next.js passes an App Router error boundary (`error.tsx`, `global-error.tsx`). */
export interface ErrorBoundaryProps {
  error: Error & { digest?: string };
  reset: () => void;
}

/**
 * Logs the error an error boundary caught (once per error, not per render) and
 * returns the text the boundary shows for it: the message, plus the digest when
 * Next.js attached one, so a server-side error can be correlated with its log.
 * Holds no styling, so `global-error.tsx` can use it without the root layout.
 */
export function useBoundaryError(error: ErrorBoundaryProps['error']): string {
  useEffect(() => {
    console.error(error);
  }, [error]);

  const message = error.message || 'Unknown error';
  return error.digest ? `${message}\n\nDigest: ${error.digest}` : message;
}
