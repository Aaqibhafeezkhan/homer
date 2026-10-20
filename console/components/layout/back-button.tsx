'use client';

import { useRouter } from 'next/navigation';
import { Button } from '@/components/ui/button';
import { ArrowLeft } from 'lucide-react';
import { IN_APP_NAV_KEY } from '@/lib/hooks/use-mark-in-app-navigation';

// Ghost button: ArrowLeft icon + label. Prefers real history-back so the
// list page's URL params (filters, paging) survive; falls back to a
// pushed link for deep links with no in-app history. `window.history.length`
// can't tell the two apart — a shared link opened in a tab with existing
// external history also reports length > 1 — so we track in-app navigation
// ourselves via sessionStorage (see useMarkInAppNavigation).
function hasInAppHistory() {
  try {
    return sessionStorage.getItem(IN_APP_NAV_KEY) === '1';
  } catch {
    return false;
  }
}

export function BackButton({ fallbackHref, label }: { fallbackHref: string; label: string }) {
  const router = useRouter();
  const handleClick = () => {
    if (hasInAppHistory()) router.back();
    else router.push(fallbackHref);
  };
  return (
    <Button
      variant="ghost"
      size="sm"
      onClick={handleClick}
      className="text-muted-foreground hover:text-foreground -ml-2"
    >
      <ArrowLeft className="h-4 w-4 mr-1" aria-hidden="true" />
      {label}
    </Button>
  );
}
