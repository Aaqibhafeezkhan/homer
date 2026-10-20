'use client';

import { useEffect, useState } from 'react';

/**
 * Returns the current time in whole seconds (`Math.floor(Date.now() / 1000)`),
 * refreshed once per second. Returns 0 until the first tick, and stays 0 while
 * `enabled` is falsy (the interval is not started). The clock re-seeds whenever
 * `enabled` changes, so passing a value (e.g. a start timestamp) re-syncs
 * immediately when it changes. Seeding happens in an effect so render stays pure.
 */
export function useNow(enabled: unknown = true): number {
  const [now, setNow] = useState(0);

  useEffect(() => {
    if (!enabled) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- seed current time on mount; Date.now() in render would violate purity
    setNow(Math.floor(Date.now() / 1000));
    const interval = setInterval(() => setNow(Math.floor(Date.now() / 1000)), 1000);
    return () => clearInterval(interval);
  }, [enabled]);

  return now;
}
