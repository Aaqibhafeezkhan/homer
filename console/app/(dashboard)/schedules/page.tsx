'use client';

import { useState } from 'react';
import Link from 'next/link';
import { Play } from 'lucide-react';
import { useAgents, useFireAgentCron } from '@/lib/api/queries';
import { useCan } from '@/lib/auth/use-can';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { formatTimestamp, formatTimeUntil } from '@/lib/utils/format';
import type { AgentCronInfo, AgentInfo } from '@/types/homer';

// The agents with a cron, soonest next run first; one without a next run sorts last.
function scheduledAgents(agents: AgentInfo[] | undefined): AgentInfo[] {
  return (agents ?? [])
    .filter((a) => a.cron)
    .sort((a, b) => {
      const an = a.cron!.nextRunAt ?? Number.POSITIVE_INFINITY;
      const bn = b.cron!.nextRunAt ?? Number.POSITIVE_INFINITY;
      return an - bn;
    });
}

function SchedulesLoadState({ isLoading, error }: { isLoading: boolean; error: Error | null }) {
  return (
    <>
      {isLoading && (
        <div className="flex items-center justify-center py-8">
          <div className="text-muted-foreground">Loading schedules...</div>
        </div>
      )}

      {error && (
        <div className="rounded-lg border border-destructive bg-destructive/10 p-4">
          <p className="text-sm text-destructive">
            Failed to load schedules: {error.message}
          </p>
        </div>
      )}
    </>
  );
}

function NextRunCell({ nextRunAt }: { nextRunAt: number | null | undefined }) {
  if (nextRunAt == null) return <span className="text-muted-foreground">—</span>;
  return (
    <div className="flex flex-col">
      <span>{formatTimeUntil(nextRunAt)}</span>
      <span className="text-xs text-muted-foreground">
        {formatTimestamp(nextRunAt)}
      </span>
    </div>
  );
}

function LastRunCell({ cron }: { cron: AgentCronInfo }) {
  if (cron.lastRunAt == null) return <span className="text-muted-foreground">Never</span>;
  if (cron.lastRunProcessId == null) return <span>{formatTimestamp(cron.lastRunAt)}</span>;
  return (
    <Link
      href={`/processes/${cron.lastRunProcessId}`}
      className="hover:underline"
    >
      {formatTimestamp(cron.lastRunAt)}
    </Link>
  );
}

function ScheduleRow({
  agent,
  runDisabled,
  onRun,
}: {
  agent: AgentInfo;
  runDisabled: boolean;
  onRun: (agent: AgentInfo) => void;
}) {
  const cron = agent.cron!;
  return (
    <TableRow>
      <TableCell>
        <Link
          href={`/agents/${encodeURIComponent(agent.name)}`}
          className="font-medium hover:underline"
        >
          {agent.name}
        </Link>
      </TableCell>
      <TableCell>
        <code className="font-mono text-sm">{cron.expression}</code>
      </TableCell>
      <TableCell className="text-sm text-muted-foreground">
        {cron.timezone}
      </TableCell>
      <TableCell>
        <NextRunCell nextRunAt={cron.nextRunAt} />
      </TableCell>
      <TableCell>
        <LastRunCell cron={cron} />
      </TableCell>
      <TableCell className="text-right">
        <Button
          variant="outline"
          size="sm"
          onClick={() => onRun(agent)}
          disabled={runDisabled}
          aria-label={`Run agent ${agent.name} now`}
        >
          <Play className="h-3 w-3 mr-1" aria-hidden="true" />
          Run
        </Button>
      </TableCell>
    </TableRow>
  );
}

function SchedulesTable({
  scheduled,
  runDisabled,
  onRun,
}: {
  scheduled: AgentInfo[];
  runDisabled: boolean;
  onRun: (agent: AgentInfo) => void;
}) {
  return (
    <div className="rounded-md border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Agent</TableHead>
            <TableHead>Expression</TableHead>
            <TableHead>Timezone</TableHead>
            <TableHead>Next run</TableHead>
            <TableHead>Last run</TableHead>
            <TableHead className="text-right">Actions</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {scheduled.map((agent) => (
            <ScheduleRow key={agent.name} agent={agent} runDisabled={runDisabled} onRun={onRun} />
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

function RunNowDialog({
  agent,
  isPending,
  error,
  onClose,
  onConfirm,
}: {
  agent: AgentInfo | null;
  isPending: boolean;
  error: Error | null;
  onClose: () => void;
  onConfirm: () => void;
}) {
  return (
    <Dialog
      open={agent !== null}
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <DialogContent aria-describedby="run-schedule-desc">
        <DialogHeader>
          <DialogTitle>Run {agent?.name} now?</DialogTitle>
          <DialogDescription id="run-schedule-desc">
            This starts the agent immediately with its cron semantics — no parameters are
            prompted, and the run is owned by the scheduler. A fire refused by the cost cap or
            the cooldown starts no run and is only logged, never surfaced as an error; a lock,
            parallel-run or queued-runs refusal starts a run that ends FAILED.
          </DialogDescription>
        </DialogHeader>
        {error && (
          <p className="text-sm text-destructive">
            Failed to start the run: {error.message}
          </p>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={onConfirm} disabled={isPending}>
            {isPending ? 'Starting…' : 'Run now'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export default function SchedulesPage() {
  const { data, isLoading, error } = useAgents();
  const { isAdmin } = useCan();
  const fireCron = useFireAgentCron();
  const [confirmAgent, setConfirmAgent] = useState<AgentInfo | null>(null);

  if (!isAdmin) {
    return (
      <div className="p-6 text-sm text-muted-foreground">
        You don’t have access to this page.
      </div>
    );
  }

  const scheduled = scheduledAgents(data?.agents);

  const handleConfirmRun = async () => {
    if (!confirmAgent) return;
    try {
      await fireCron.mutateAsync(confirmAgent.name);
      setConfirmAgent(null);
    } catch {
      // Surfaced in the dialog via fireCron.error.
    }
  };

  return (
    <div className="p-6 space-y-4">
      <h2 className="text-3xl font-bold tracking-tight">Schedules</h2>

      <SchedulesLoadState isLoading={isLoading} error={error} />

      {data && scheduled.length === 0 && (
        <div className="flex items-center justify-center py-8">
          <p className="text-muted-foreground">No scheduled agents</p>
        </div>
      )}

      {data && scheduled.length > 0 && (
        <SchedulesTable scheduled={scheduled} runDisabled={fireCron.isPending} onRun={setConfirmAgent} />
      )}

      <RunNowDialog
        agent={confirmAgent}
        isPending={fireCron.isPending}
        error={fireCron.error}
        onClose={() => setConfirmAgent(null)}
        onConfirm={handleConfirmRun}
      />
    </div>
  );
}
