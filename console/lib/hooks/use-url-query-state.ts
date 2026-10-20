'use client';

import { useCallback } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';

/** Read-side: the current URLSearchParams. Write-side: patch params via
 * router.replace (scroll preserved); null/empty string deletes a key. */
export function useUrlQueryState() {
  const searchParams = useSearchParams();
  const pathname = usePathname();
  const router = useRouter();
  const setParams = useCallback(
    (patch: Record<string, string | null>) => {
      const next = new URLSearchParams(searchParams.toString());
      for (const [key, value] of Object.entries(patch)) {
        if (value === null || value === '') next.delete(key);
        else next.set(key, value);
      }
      const qs = next.toString();
      router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
    },
    [searchParams, pathname, router]
  );
  return { searchParams, setParams };
}
