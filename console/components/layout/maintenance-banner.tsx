'use client';

import { useHealth } from '@/lib/api/queries';

/**
 * The server's maintenance mode, shown on every dashboard page: while it is on the
 * backend skips cron fires and holds new starts in a queue, so a run that seems to
 * do nothing has an explanation on screen. Renders nothing while the mode is off
 * or while health has not answered yet.
 *
 * Fixed brand colors rather than theme tokens, like the status badges: the alarm
 * has to read the same under every theme.
 */
export function MaintenanceBanner() {
  const { data } = useHealth();
  if (!data?.maintenance) return null;

  return (
    <div
      className="border-b border-red-500 bg-red-500/10 px-4 py-2 text-sm font-medium text-red-600"
      role="alert"
      aria-label="Maintenance mode"
    >
      Maintenance mode is on. Scheduled runs are skipped and new runs wait in a queue
      until an admin turns it off.
    </div>
  );
}
