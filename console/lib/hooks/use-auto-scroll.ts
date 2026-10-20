'use client';

import { useEffect, type RefObject } from 'react';

// The nearest scrollable ancestor (the dialog's overflow-auto pane), or null.
function scrollContainerOf(element: HTMLElement): HTMLElement | null {
  let container = element.parentElement;
  while (container) {
    const oy = getComputedStyle(container).overflowY;
    if (oy === 'auto' || oy === 'scroll') break;
    container = container.parentElement;
  }
  return container;
}

/**
 * Sticky auto-scroll: when `enabled` is true, scrolls the given sentinel into
 * view on every change to `deps`, but only if the user is already near the
 * bottom of the nearest scrollable ancestor (within `threshold` px). This keeps
 * a live stream pinned to the bottom without yanking the viewport away from a
 * user who has scrolled up to read earlier content.
 *
 * The sentinel is a zero-height element rendered at the end of the scrollable
 * content; pass its ref. When no scrollable ancestor is found (e.g. jsdom with
 * no layout), it falls back to a best-effort `scrollIntoView`.
 */
export function useAutoScroll(
  sentinelRef: RefObject<HTMLElement | null>,
  enabled: boolean,
  deps: unknown[],
  threshold = 120
): void {
  useEffect(() => {
    if (!enabled) return;
    const sentinel = sentinelRef.current;
    if (!sentinel) return;
    const container = scrollContainerOf(sentinel);
    // No scroll container found (or jsdom with no layout): best-effort scroll.
    if (!container) {
      sentinel.scrollIntoView?.({ block: 'end' });
      return;
    }
    // Sticky: only follow the stream if the user is already near the bottom.
    const distanceFromBottom =
      container.scrollHeight - container.scrollTop - container.clientHeight;
    if (distanceFromBottom < threshold) {
      sentinel.scrollIntoView?.({ block: 'end' });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- caller supplies the dependency list explicitly
  }, [enabled, ...deps]);
}
