'use client';

import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useRunnerQueue, useRunners } from '@/lib/api/queries';
import { useCan } from '@/lib/auth/use-can';
import { formatRelativeTime, formatTimestamp } from '@/lib/utils/format';
import type { RunnerFilterView, RunnerInstanceStatus, RunnerQueueStatusResponse, RunnerStatus } from '@/types/homer';

function capacity(inUse: number, concurrency?: number | null) {
  return `${inUse} / ${concurrency ?? 'unbounded'}`;
}

function describeFilter(filter: RunnerFilterView) {
  return filter.alias ? `runner ${filter.alias}` : `tags ${(filter.tags ?? []).join(', ')}`;
}

// The claim queue's error, its empty state, or the pending claims grouped by filter.
function ClaimQueueBody({
  error,
  data,
}: {
  error: Error | null;
  data: RunnerQueueStatusResponse | undefined;
}) {
  return (
    <>
      {error && <p className="text-sm text-destructive">Failed to load the claim queue: {error.message}</p>}
      {data && data.pending === 0 && <p className="text-sm text-muted-foreground">No pending claims.</p>}
      {data && data.pending > 0 && (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Waiting for</TableHead>
              <TableHead className="text-right">Pending</TableHead>
              <TableHead className="text-right">Oldest</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.byFilter.map((g) => (
              <TableRow key={describeFilter(g.filter)}>
                <TableCell>{describeFilter(g.filter)}</TableCell>
                <TableCell className="text-right font-mono text-sm">{g.pending}</TableCell>
                <TableCell className="text-right font-mono text-sm">{g.oldestAgeSec} s</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </>
  );
}

function ConfiguredRunnersCard({ runners }: { runners: RunnerStatus[] }) {
  return (
    <Card>
      <CardHeader><CardTitle>Configured runners</CardTitle></CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Alias</TableHead><TableHead>Kind</TableHead><TableHead>Tags</TableHead>
              <TableHead className="text-right">In use / concurrency</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {runners.map((r) => (
              <TableRow key={r.alias}>
                <TableCell className="font-medium">{r.alias}</TableCell>
                <TableCell>{r.kind}</TableCell>
                <TableCell>{r.tags.length ? r.tags.join(', ') : <span className="text-muted-foreground">—</span>}</TableCell>
                <TableCell className="text-right font-mono text-sm">
                  {r.kind === 'external' ? `${r.inUse} active` : capacity(r.inUse, r.concurrency)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}

type InstanceRow = RunnerInstanceStatus & { domain: string };

function ExternalInstancesCard({ instances }: { instances: InstanceRow[] }) {
  return (
    <Card>
      <CardHeader><CardTitle>External runner instances</CardTitle></CardHeader>
      <CardContent>
        {instances.length === 0 ? (
          <p className="text-sm text-muted-foreground">No external runner instances registered.</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead><TableHead>Domain</TableHead><TableHead>Tags</TableHead>
                <TableHead className="text-right">Sessions / concurrency</TableHead>
                <TableHead>Last seen</TableHead><TableHead>Registered</TableHead><TableHead>State</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {instances.map((i) => (
                <TableRow key={i.instanceId}>
                  <TableCell className="font-medium">{i.name}</TableCell>
                  <TableCell>{i.domain}</TableCell>
                  <TableCell>{i.tags.join(', ')}</TableCell>
                  <TableCell className="text-right font-mono text-sm">{capacity(i.activeSessions, i.concurrency)}</TableCell>
                  <TableCell>{formatRelativeTime(i.lastSeenAt)}</TableCell>
                  <TableCell>{formatTimestamp(i.registeredAt)}</TableCell>
                  <TableCell>
                    {i.draining ? <Badge variant="outline">Draining</Badge> : <Badge variant="outline">Active</Badge>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
}

export default function RunnersPage() {
  const { isAdmin } = useCan();
  const runners = useRunners(isAdmin);
  const queue = useRunnerQueue(isAdmin);

  if (!isAdmin) {
    return <div className="p-6 text-sm text-muted-foreground">You don’t have access to this page.</div>;
  }

  const all: RunnerStatus[] = runners.data?.runners ?? [];
  const external = all.filter((r) => r.kind === 'external');
  const instances = external.flatMap((r) => (r.instances ?? []).map((i) => ({ domain: r.alias, ...i })));

  return (
    <div className="p-6 space-y-6">
      <div>
        <h2 className="text-3xl font-bold tracking-tight">Runners</h2>
        <p className="text-sm text-muted-foreground">
          Configured runners, registered pull-mode instances and runs waiting for a runner. Read-only.
        </p>
      </div>

      {runners.isLoading && <div className="text-muted-foreground">Loading runners...</div>}
      {runners.error && (
        <div className="rounded-lg border border-destructive bg-destructive/10 p-4">
          <p className="text-sm text-destructive">Failed to load runners: {runners.error.message}</p>
        </div>
      )}

      <ConfiguredRunnersCard runners={all} />

      {external.length > 0 && <ExternalInstancesCard instances={instances} />}

      <Card>
        <CardHeader><CardTitle>Claim queue</CardTitle></CardHeader>
        <CardContent>
          <ClaimQueueBody error={queue.error} data={queue.data} />
        </CardContent>
      </Card>
    </div>
  );
}
