'use client';

import { useCallback, useSyncExternalStore } from 'react';

/** localStorage key holding '1' while the sidebar is collapsed. */
const SIDEBAR_COLLAPSED_KEY = 'homer:sidebar-collapsed';

function readCollapsed(): boolean {
  if (typeof window === 'undefined') return false;
  try {
    return localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === '1';
  } catch {
    // localStorage unavailable (privacy mode) — fall back to expanded.
    return false;
  }
}

const listeners = new Set<() => void>();

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

// The server (and the first client render before hydration) always renders
// expanded, so the persisted choice cannot cause a hydration mismatch; it
// applies on the next render once the client snapshot is read.
function getServerSnapshot(): boolean {
  return false;
}

function setCollapsed(collapsed: boolean): void {
  if (typeof window !== 'undefined') {
    try {
      localStorage.setItem(SIDEBAR_COLLAPSED_KEY, collapsed ? '1' : '0');
    } catch {
      // Persisting is best-effort; the in-memory toggle still applies.
    }
  }
  listeners.forEach((l) => l());
}

/** Sidebar collapse state, persisted in localStorage. */
export function useSidebarCollapsed(): {
  collapsed: boolean;
  toggle: () => void;
} {
  const collapsed = useSyncExternalStore(subscribe, readCollapsed, getServerSnapshot);
  const toggle = useCallback(() => setCollapsed(!readCollapsed()), []);
  return { collapsed, toggle };
}
