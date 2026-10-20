import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { StatusBadge } from '@/components/processes/status-badge';
import { formatTimestamp, formatTimeUntil } from '@/lib/utils/format';
import type { ProcessInfo } from '@/types/homer';

/** A run's status history, followed by its scheduled workspace purge when there is one. */
export function StatusTimelineCard({
  history,
  purgeAt,
}: {
  history: ProcessInfo['history'];
  purgeAt: ProcessInfo['purgeAt'];
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Status Timeline</CardTitle>
        <CardDescription>History of status changes</CardDescription>
      </CardHeader>
      <CardContent>
        <div className="space-y-2">
          {history.map((entry, index) => (
            <div key={index} className="flex items-center gap-3 text-sm">
              <span className="text-muted-foreground font-mono">
                {formatTimestamp(entry.timestamp)}
              </span>
              <StatusBadge status={entry.status} />
            </div>
          ))}
          {purgeAt && (
            <div className="flex items-center gap-3 text-sm mt-2 pt-2 border-t border-dashed">
              <span className="text-muted-foreground font-mono">
                {formatTimestamp(purgeAt)}
              </span>
              <span className="text-xs text-muted-foreground bg-muted px-2 py-0.5 rounded">
                workspace purge ({formatTimeUntil(purgeAt)})
              </span>
            </div>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
