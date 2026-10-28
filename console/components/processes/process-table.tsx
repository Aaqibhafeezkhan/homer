'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { ProcessInfo } from '@/types/homer';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Button } from '@/components/ui/button';
import { StatusBadge } from './status-badge';
import { FailureReason } from './failure-reason';
import { TagChips } from './tag-chips';
import { formatDuration, formatCost } from '@/lib/utils/format';
import {
  commandDotStates,
  getLastCommand,
  isActiveStatus,
  isRetryableStatus,
  runDurationSeconds,
  type LastCommandStatus,
} from '@/lib/utils/process';
import { X, Check, Loader2, MessageCircleQuestion, RotateCcw, Skull } from 'lucide-react';
import { cn } from '@/lib/utils/cn';
import { commandDotColor } from './workflow-dots';
import { useNow } from '@/lib/hooks/use-now';
import type { PodPhase } from '@/types/homer';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { useState } from 'react';
import { useKillProcess, useRetryProcess } from '@/lib/api/queries';
import { useCan } from '@/lib/auth/use-can';

interface ProcessTableProps {
  processes: ProcessInfo[];
  // When set, a run's tag chips call this instead of linking — the list page
  // uses it to AND the tag into the active filter (ADR-0060).
  onTagClick?: (tag: string) => void;
  // When set, adds a Flow column with this cell per row. Only the roots view
  // passes it, so every other caller (e.g. /agents/[name]) renders unchanged.
  flowSummary?: (process: ProcessInfo) => React.ReactNode;
}

const podPhaseDotClass: Record<PodPhase, string> = {
  NONE: 'bg-gray-400',
  CREATING: 'bg-blue-400 animate-pulse',
  PENDING: 'bg-blue-500 animate-pulse',
  RUNNING: 'bg-blue-600',
  READY: 'bg-green-500',
  SUCCEEDED: 'bg-green-600',
  FAILED: 'bg-red-500',
  DELETED: 'bg-gray-500',
  KEPT_FOR_DEBUG: 'bg-orange-500',
};

function renderLastCommandIcon(status: LastCommandStatus) {
  switch (status) {
    case 'success':
      return <Check className="h-3 w-3 text-green-500" aria-hidden="true" />;
    case 'running':
      return <Loader2 className="h-3 w-3 animate-spin text-muted-foreground" aria-hidden="true" />;
    case 'failed':
      return <X className="h-3 w-3 text-red-500" aria-hidden="true" />;
  }
}

/**
 * One square per command the run executed, coloured like the LangGraph dot
 * rows (`workflow-dots.ts`). The tooltip names the command and its state only
 * — no arguments, which the list never shows either (threat model, Secrets).
 */
function CommandDots({ process }: { process: ProcessInfo }) {
  const dots = commandDotStates(process);
  if (dots.length === 0) return null;

  return (
    <span className="inline-flex flex-wrap items-center gap-0.5" aria-label="command progress">
      {dots.map((dot, i) => (
        <span
          key={i}
          title={`${dot.label}: ${dot.state}`}
          className={cn(
            'inline-block h-2 w-2 rounded-sm',
            dot.state === 'running' && 'animate-pulse',
          )}
          style={{ backgroundColor: commandDotColor(dot.state) }}
        />
      ))}
    </span>
  );
}

interface CommandStatusProps {
  status: LastCommandStatus | null;
  label: string | null;
  process: ProcessInfo;
  variant: 'desktop' | 'mobile';
}

/**
 * Renders the command-progress dots above the last-command status fragment.
 * The desktop and mobile layouts intentionally differ in markup: desktop shows
 * a dash fallback and a muted label inside a span; mobile prefixes "Last:",
 * omits the dash fallback, and only renders when a label exists. The dots and
 * the icon logic are the shared parts.
 */
function CommandStatus({ status, label, process, variant }: CommandStatusProps) {
  if (variant === 'desktop') {
    if (!status) {
      return <span className="text-sm text-muted-foreground">-</span>;
    }
    return (
      <div className="space-y-1">
        <CommandDots process={process} />
        <span className="flex items-center gap-1 text-sm">
          {renderLastCommandIcon(status)}
          <span className="text-muted-foreground">{label}</span>
        </span>
      </div>
    );
  }

  if (!label) return null;
  return (
    <div className="space-y-1">
      <CommandDots process={process} />
      <div className="text-sm flex items-center gap-1">
        <span className="text-muted-foreground">Last:</span>
        {status && renderLastCommandIcon(status)}
        <span>{label}</span>
      </div>
    </div>
  );
}

function QuestionBadge({ process }: { process: ProcessInfo }) {
  const count = process.openQuestions ?? 0;
  if (count === 0) return null;
  return (
    <span
      className="inline-flex items-center gap-1 rounded-full bg-amber-500 px-2 py-0.5 text-xs font-medium text-white"
      aria-label={`${count} open question${count === 1 ? '' : 's'} waiting for an answer`}
    >
      <MessageCircleQuestion className="h-3 w-3" aria-hidden="true" />
      {count}
    </span>
  );
}

// Why a run failed before it started, next to its status. Only the list's short form: the
// full text is the badge's tooltip and lives on the run's own page.
function FailureReasonBadge({ process }: { process: ProcessInfo }) {
  if (!process.failureReason) return null;
  return <FailureReason reason={process.failureReason} variant="inline" />;
}

function CostCell({ process }: { process: ProcessInfo }) {
  if (!process.costUsd) return <span className="text-muted-foreground">-</span>;
  return <span className="font-mono text-sm">{formatCost(process.costUsd)}</span>;
}

/** How long the whole run has been going, or a placeholder when its history says nothing usable. */
function DurationCell({ process, now }: { process: ProcessInfo; now: number }) {
  const seconds = runDurationSeconds(process, now);
  return <span>{seconds === null ? '—' : formatDuration(0, seconds)}</span>;
}

/** The agent's group beside its name. Callers render it only when the run's agent has one. */
function GroupLabel({ group }: { group: string }) {
  return (
    <span className="ml-2 rounded border px-1 text-xs font-normal text-muted-foreground">{group}</span>
  );
}

function RunnerCell({ process }: { process: ProcessInfo }) {
  if (!process.runner) return <span className="text-muted-foreground">-</span>;
  const { alias, type, podName, podPhase } = process.runner;
  const dotClass = podPhaseDotClass[podPhase] ?? podPhaseDotClass.NONE;
  return (
    <span className="flex items-center gap-2 text-sm">
      {podName && (
        <span
          className={cn('h-2 w-2 rounded-full', dotClass)}
          aria-label={`Pod phase: ${podPhase}`}
        />
      )}
      {/* Admin viewers get the raw alias; everyone else the generic backend type. */}
      <span className="font-mono text-muted-foreground">{alias ?? type}</span>
    </span>
  );
}

// The row actions' state and handlers: the kill confirmation and the retry that opens the new run.
function useProcessActions() {
  const router = useRouter();
  const cap = useCan();
  const [killDialogOpen, setKillDialogOpen] = useState(false);
  const [selectedProcessId, setSelectedProcessId] = useState<number | null>(null);
  const killProcess = useKillProcess();
  const retryProcess = useRetryProcess();

  const handleKillClick = (e: React.MouseEvent, processId: number) => {
    e.stopPropagation();
    setSelectedProcessId(processId);
    setKillDialogOpen(true);
  };

  const handleRetryClick = async (e: React.MouseEvent, processId: number) => {
    e.stopPropagation();
    try {
      const { processId: newId } = await retryProcess.mutateAsync(processId);
      router.push(`/processes/${newId}`);
    } catch (err) {
      console.error('Retry failed:', err);
    }
  };

  const handleConfirmKill = async () => {
    if (selectedProcessId !== null) {
      await killProcess.mutateAsync(selectedProcessId);
      setKillDialogOpen(false);
      setSelectedProcessId(null);
    }
  };

  return {
    cap,
    openProcess: (processId: number) => router.push(`/processes/${processId}`),
    killDialogOpen,
    setKillDialogOpen,
    selectedProcessId,
    retryPending: retryProcess.isPending,
    handleKillClick,
    handleRetryClick,
    handleConfirmKill,
  };
}

type ProcessActions = ReturnType<typeof useProcessActions>;

function RowActions({ process, actions }: { process: ProcessInfo; actions: ProcessActions }) {
  const { cap } = actions;
  return (
    <>
      {isActiveStatus(process.status) && cap.canKillProcess(process) && (
        <Button
          variant="destructive"
          size="icon"
          className="h-8 w-8"
          onClick={(e) => actions.handleKillClick(e, process.id)}
          title="Kill process"
          aria-label={`Kill process ${process.id}`}
        >
          <Skull className="h-4 w-4" aria-hidden="true" />
        </Button>
      )}
      {isRetryableStatus(process.status) && cap.canRetryProcess(process) && (
        <Button
          variant="outline"
          size="icon"
          className="h-8 w-8"
          onClick={(e) => actions.handleRetryClick(e, process.id)}
          disabled={actions.retryPending}
          title="Retry process"
          aria-label={`Retry process ${process.id}`}
        >
          <RotateCcw className="h-4 w-4" aria-hidden="true" />
        </Button>
      )}
    </>
  );
}

interface ProcessRowProps {
  process: ProcessInfo;
  onTagClick?: (tag: string) => void;
  flowSummary?: (process: ProcessInfo) => React.ReactNode;
  actions: ProcessActions;
  /** Whole seconds, ticking once a second while any row is active; 0 before the first tick. */
  now: number;
}

function DesktopRow({ process, onTagClick, flowSummary, actions, now }: ProcessRowProps) {
  const lastCmd = getLastCommand(process);
  return (
    <TableRow
      className="cursor-pointer"
      onClick={() => actions.openProcess(process.id)}
    >
      <TableCell className="font-mono">
        <Link
          href={`/processes/${process.id}`}
          className="underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          aria-label={`Open process ${process.id} details`}
          onClick={(e) => e.stopPropagation()}
        >
          {process.id}
        </Link>
      </TableCell>
      <TableCell className="font-medium">
        {process.agentName}
        {process.group && <GroupLabel group={process.group} />}
        {process.tags && process.tags.length > 0 && (
          <TagChips tags={process.tags} onTagClick={onTagClick} className="mt-1" />
        )}
      </TableCell>
      <TableCell>
        <span className="flex items-center gap-2">
          <StatusBadge status={process.status} variant="icon" />
          <FailureReasonBadge process={process} />
          <QuestionBadge process={process} />
        </span>
      </TableCell>
      <TableCell className="text-sm text-muted-foreground">
        <DurationCell process={process} now={now} />
      </TableCell>
      <TableCell>
        <CommandStatus
          status={lastCmd.status}
          label={lastCmd.label}
          process={process}
          variant="desktop"
        />
      </TableCell>
      <TableCell><CostCell process={process} /></TableCell>
      <TableCell><RunnerCell process={process} /></TableCell>
      {flowSummary && <TableCell>{flowSummary(process)}</TableCell>}
      <TableCell><RowActions process={process} actions={actions} /></TableCell>
    </TableRow>
  );
}

function DesktopTable({
  processes,
  ...rowProps
}: Omit<ProcessRowProps, 'process'> & { processes: ProcessInfo[] }) {
  return (
    <div className="hidden md:block">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Process ID</TableHead>
            <TableHead>Agent Name</TableHead>
            <TableHead>Status</TableHead>
            <TableHead>Duration</TableHead>
            <TableHead>Last Command</TableHead>
            <TableHead>Cost</TableHead>
            <TableHead>Runner</TableHead>
            {rowProps.flowSummary && <TableHead>Flow</TableHead>}
            <TableHead>Actions</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {processes.map((process) => (
            <DesktopRow key={process.id} process={process} {...rowProps} />
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

function MobileCard({ process, onTagClick, flowSummary, actions, now }: ProcessRowProps) {
  const lastCmd = getLastCommand(process);
  return (
    <div className="rounded-lg border p-3 space-y-2 bg-card">
      <div className="flex items-center justify-between">
        <Link
          href={`/processes/${process.id}`}
          className="font-mono font-medium hover:underline"
          aria-label={`Open process ${process.id} details`}
        >
          #{process.id}
        </Link>
        <span className="flex items-center gap-2">
          <QuestionBadge process={process} />
          <StatusBadge status={process.status} variant="icon" />
          <FailureReasonBadge process={process} />
        </span>
      </div>
      <div className="text-sm">
        <span className="text-muted-foreground">Agent:</span> {process.agentName}
        {process.group && <GroupLabel group={process.group} />}
      </div>
      {process.tags && process.tags.length > 0 && (
        <TagChips tags={process.tags} onTagClick={onTagClick} />
      )}
      {process.runner && (
        <div className="text-sm flex items-center gap-2">
          <span className="text-muted-foreground">Runner:</span> <RunnerCell process={process} />
        </div>
      )}
      <CommandStatus
        status={lastCmd.status}
        label={lastCmd.label}
        process={process}
        variant="mobile"
      />
      <div className="text-sm text-muted-foreground">
        Duration: <DurationCell process={process} now={now} />
      </div>
      {flowSummary && (
        <div className="text-sm">
          <span className="text-muted-foreground">Flow:</span> {flowSummary(process)}
        </div>
      )}
      <div className="flex gap-2 pt-1"><RowActions process={process} actions={actions} /></div>
    </div>
  );
}

function KillDialog({ actions }: { actions: ProcessActions }) {
  return (
    <Dialog open={actions.killDialogOpen} onOpenChange={actions.setKillDialogOpen}>
      <DialogContent aria-describedby="kill-process-desc">
        <DialogHeader>
          <DialogTitle>Confirm Kill Process</DialogTitle>
          <DialogDescription id="kill-process-desc">
            Are you sure you want to kill process {actions.selectedProcessId}? This action cannot be undone.
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={() => actions.setKillDialogOpen(false)}>
            Cancel
          </Button>
          <Button variant="destructive" onClick={actions.handleConfirmKill}>
            Kill Process
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export function ProcessTable({ processes, onTagClick, flowSummary }: ProcessTableProps) {
  const actions = useProcessActions();
  // The clock only ticks while some row is still running: a list of finished
  // runs has nothing to re-render for.
  const now = useNow(processes.some((process) => isActiveStatus(process.status)));
  const rowProps = { onTagClick, flowSummary, actions, now };

  return (
    <>
      <DesktopTable processes={processes} {...rowProps} />

      <div className="md:hidden space-y-3 p-2">
        {processes.map((process) => (
          <MobileCard key={process.id} process={process} {...rowProps} />
        ))}
      </div>

      <KillDialog actions={actions} />
    </>
  );
}
