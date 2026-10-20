'use client';
import { useEffect, useRef } from 'react';
import { usePathname } from 'next/navigation';

export const IN_APP_NAV_KEY = 'homer:in-app-nav';

/** Marks (in sessionStorage) that a client-side navigation happened in this
 * tab, so BackButton can tell in-app history from a deep link. The first
 * render after a full page load is not a navigation and sets nothing. */
export function useMarkInAppNavigation() {
  const pathname = usePathname();
  const isFirst = useRef(true);
  useEffect(() => {
    if (isFirst.current) {
      isFirst.current = false;
      return;
    }
    try {
      sessionStorage.setItem(IN_APP_NAV_KEY, '1');
    } catch {
      // sessionStorage unavailable (privacy mode) — BackButton falls back to its link behavior
    }
  }, [pathname]);
}
