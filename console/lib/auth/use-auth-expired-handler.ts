'use client';

import { useEffect, useRef, type RefObject } from 'react';
import { useRouter, usePathname } from 'next/navigation';
import { useQueryClient } from '@tanstack/react-query';
import { onAuthExpired } from './auth-expired-bus';
import { removeActiveToken } from '@/lib/instances/store';

/**
 * Subscribes to auth-expired events and performs the standard
 * recovery: clear the active instance's bearer token, clear all React Query caches
 * (avoids leaking stale per-user data into a fresh login), and
 * redirect to /login with ?expired=1. Idempotency comes from a one-shot
 * latch (the recovery runs at most once per mount); the /login check
 * covers events that arrive when the hook mounts on a route transition
 * where pathname is already /login. Audit P0 #6.
 *
 * Returns the latch ref: `true` once the recovery has fired, so callers
 * can avoid issuing a second, competing navigation.
 */
export function useAuthExpiredHandler(): RefObject<boolean> {
  const router = useRouter();
  const pathname = usePathname();
  const queryClient = useQueryClient();
  const handled = useRef(false);

  useEffect(() => {
    return onAuthExpired(() => {
      // Latch: queryClient.clear() below refetches still-mounted queries;
      // their 401s re-fire this event before the /login navigation commits
      // (usePathname only updates on commit), which looped clear+replace
      // hundreds of times. Run the recovery exactly once per mount.
      if (handled.current) return;
      if (pathname?.startsWith('/login')) return;
      handled.current = true;
      try {
        removeActiveToken();
      } catch {
        /* SSR or denied storage; ignore */
      }
      queryClient.clear();
      router.replace('/login?expired=1');
    });
  }, [pathname, queryClient, router]);

  return handled;
}
