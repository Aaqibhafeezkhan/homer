'use client';

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { useContinuations } from '@/lib/api/queries';
import { ContinuationItem } from '@/components/continuations/continuation-item';

export default function ContinuationsPage() {
  const { data: pendingData } = useContinuations({ status: 'PENDING' });
  // A continuation that never fires is the failure mode worth seeing: agents use
  // continuations as their crash-reporting channel, so a lost one is a report
  // nobody gets. Failures were previously only a WARN line in the server log.
  const { data: failedData } = useContinuations({ status: 'FAILED' });

  const pending = pendingData?.continuations ?? [];
  const failed = failedData?.continuations ?? [];

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle>Continuations</CardTitle>
          <CardDescription>
            Parked workflows waiting on a watched run to finish before starting a follow-up agent
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <PageNotice shown={pending.length} total={pendingData?.total} />
          {pending.length === 0 ? (
            <p className="text-sm text-muted-foreground">No pending continuations.</p>
          ) : (
            pending.map((continuation) => (
              <ContinuationItem key={continuation.id} continuation={continuation} />
            ))
          )}
        </CardContent>
      </Card>

      {failed.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Failed to fire</CardTitle>
            <CardDescription>
              Continuations that never started their follow-up agent, with the reason
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <PageNotice shown={failed.length} total={failedData?.total} />
            {failed.map((continuation) => (
              <ContinuationItem key={continuation.id} continuation={continuation} />
            ))}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

// The list endpoint returns one page (the newest 100); say so when rows are missing.
function PageNotice({ shown, total }: { shown: number; total?: number }) {
  if (total === undefined || total <= shown) return null;
  return (
    <p className="text-sm text-muted-foreground">
      Showing the newest {shown} of {total}.
    </p>
  );
}
