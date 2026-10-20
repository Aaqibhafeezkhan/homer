'use client';

import { useHeartbeat } from '@/lib/api/queries';

export function VersionBadge() {
  const consoleVersion = process.env.NEXT_PUBLIC_CONSOLE_VERSION ?? 'dev';
  const { data } = useHeartbeat();
  const homerVersion = data?.version ?? '...';

  return (
    <div className="fixed bottom-3 right-4 text-xs text-muted-foreground/50 select-none pointer-events-none">
      console: v{consoleVersion} &nbsp; homer: v{homerVersion}
    </div>
  );
}
