'use client';

import { useEffect, useRef, type RefObject } from 'react';
import { useRouter, usePathname } from 'next/navigation';
import { useQueryClient } from '@tanstack/react-query';
import { onAuthExpired } from './auth-expired-bus';
import { loginHref } from './return-path';
import { activeMode, getActiveToken, removeActiveToken } from '@/lib/instances/store';
import type { AuthStatus } from './auth-context';

/**
 * Whether the 401 that just landed ended a session. A bearer token still in the
 * active instance's storage is a credential the backend has just rejected, and
 * a cookie session that expired mid-use was `status === 'authed'` at some point
 * (`wasAuthed`). A session-less first visit is neither, so it gets the neutral
 * prompt rather than "session expired". The cookie itself is not
 * client-readable, so mid-use is the only way a cookie expiry is recognisable.
 */
function sessionExisted(wasAuthed: boolean): boolean {
  return wasAuthed || (activeMode() === 'bearer' && getActiveToken() !== null);
}

/**
 * Subscribes to auth-expired events and performs the standard
 * recovery: clear the active instance's bearer token, clear all React Query caches
 * (avoids leaking stale per-user data into a fresh login), and
 * redirect to /login carrying the requested path as `next`. The `expired=1`
 * marker — and so the "session expired" wording — is added only when the
 * rejected request carried a session credential; a 401 on a session-less first
 * visit is not an expiry. Idempotency comes from a one-shot
 * latch (the recovery runs at most once per mount); the /login check
 * covers events that arrive when the hook mounts on a route transition
 * where pathname is already /login. Audit P0 #6.
 *
 * Returns the latch ref: `true` once the recovery has fired, so callers
 * can avoid issuing a second, competing navigation.
 */
export function useAuthExpiredHandler(status: AuthStatus): RefObject<boolean> {
  const router = useRouter();
  const pathname = usePathname();
  const queryClient = useQueryClient();
  const handled = useRef(false);
  // Latched rather than read from `status` when the event arrives: the 401 that
  // ends a session lands before the auth context re-renders to 'anon', and a
  // credential that was already dead on page load never renders 'authed' at all
  // — that case is caught by the stored token instead.
  const wasAuthed = useRef(false);

  useEffect(() => {
    if (status === 'authed') wasAuthed.current = true;
  }, [status]);

  useEffect(() => {
    return onAuthExpired(() => {
      // Latch: queryClient.clear() below refetches still-mounted queries;
      // their 401s re-fire this event before the /login navigation commits
      // (usePathname only updates on commit), which looped clear+replace
      // hundreds of times. Run the recovery exactly once per mount.
      if (handled.current) return;
      if (pathname?.startsWith('/login')) return;
      handled.current = true;
      // Read the credential before clearing it: it is what separates an expired
      // session from a session-less first visit.
      const hadSession = sessionExisted(wasAuthed.current);
      try {
        removeActiveToken();
      } catch {
        /* SSR or denied storage; ignore */
      }
      queryClient.clear();
      router.replace(loginHref(pathname, hadSession));
    });
  }, [pathname, queryClient, router]);

  return handled;
}
