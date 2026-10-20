'use client';

import { useHealth } from '@/lib/api/queries';
import { formatTimestamp } from '@/lib/utils/format';

export function SweepStatusBanner() {
  const { data } = useHealth();
  if (!data) return null;

  const hasKubernetesRunner = data.runners.some((r) => r.type === 'kubernetes');
  if (!hasKubernetesRunner || !data.sweep) return null;

  const { sweep } = data;
  const hasErrors = sweep.errors.length > 0;

  return (
    <div
      className="rounded-lg border bg-muted/40 p-3 text-sm"
      role="status"
      aria-label="Orphan pod sweep status"
    >
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-medium">Orphan pod sweep:</span>
        <span className="text-muted-foreground">
          scanned {sweep.scanned}, deleted {sweep.deleted.length}
          {hasErrors ? `, errors ${sweep.errors.length}` : ''}
        </span>
        <span className="text-muted-foreground font-mono">
          @ {formatTimestamp(sweep.ranAt)}
        </span>
      </div>
      {hasErrors && (
        <ul className="mt-2 list-disc space-y-1 pl-5 font-mono text-xs text-destructive">
          {sweep.errors.map((err, index) => (
            <li key={index}>{err}</li>
          ))}
        </ul>
      )}
    </div>
  );
}
