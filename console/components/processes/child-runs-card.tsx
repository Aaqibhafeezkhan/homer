import Link from 'next/link';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { StatusBadge } from '@/components/processes/status-badge';
import { TagChips } from '@/components/processes/tag-chips';
import type { ProcessListResponse } from '@/types/homer';

/**
 * The first page of a run's direct children (ADR-0058), with a link to the full
 * list when there are more; renders nothing until the run has a child.
 */
export function ChildRunsCard({
  parentProcessId,
  runs,
}: {
  parentProcessId: number;
  runs: ProcessListResponse | undefined;
}) {
  if (!runs || runs.total <= 0) return null;
  return (
    <Card>
      <CardHeader>
        <CardTitle>Child runs</CardTitle>
        <CardDescription>Runs started by this process</CardDescription>
      </CardHeader>
      <CardContent>
        <ul className="space-y-2">
          {runs.processes.map((child) => (
            <li key={child.id} className="flex flex-wrap items-center gap-3 text-sm">
              <Link
                href={`/processes/${child.id}`}
                className="font-mono underline-offset-2 hover:underline"
                aria-label={`Open child process ${child.id} details`}
              >
                #{child.id}
              </Link>
              <span className="font-medium">{child.agentName}</span>
              <StatusBadge status={child.status} />
              {child.tags && child.tags.length > 0 && <TagChips tags={child.tags} />}
            </li>
          ))}
        </ul>
        {runs.total > runs.processes.length && (
          <Link
            href={`/processes?parent=${parentProcessId}`}
            className="mt-3 inline-block text-sm underline underline-offset-4 hover:text-foreground"
          >
            View all {runs.total} child runs
          </Link>
        )}
      </CardContent>
    </Card>
  );
}
