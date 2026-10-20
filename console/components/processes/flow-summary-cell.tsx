'use client';

import { useFlowSummary } from '@/lib/api/queries';
import type { ProcessInfo } from '@/types/homer';
import { formatCost } from '@/lib/utils/format';
import { cn } from '@/lib/utils/cn';
import { isActiveStatus } from '@/lib/utils/process';

/**
 * Descendants fetched per root row. The count comes from the response's
 * `total`, so the cap only bounds the cost sum and the state aggregate.
 */
export const FLOW_SUMMARY_LIMIT = 100;

export type FlowState = 'running' | 'question' | 'failed' | 'finished';

// Fixed brand colors, like StatusBadge: status semantics are theme-independent.
const FLOW_STATE_STYLE: Record<FlowState, { label: string; className: string }> = {
  question: { label: 'waiting on a question', className: 'bg-amber-500 text-white' },
  running: { label: 'running', className: 'bg-blue-500 text-white' },
  failed: { label: 'failed', className: 'bg-red-500 text-white' },
  finished: { label: 'finished', className: 'bg-green-500 text-white' },
};

/**
 * Aggregate state of a flow's runs, most actionable first: a question blocking
 * a run is what the operator has to act on, then anything still running, then a
 * failure, and only then a clean finish. The question check must come first —
 * a question exists only while its run is WORKING, so ranking `running` above
 * it would make that state unreachable.
 */
export function flowState(processes: ProcessInfo[]): FlowState | null {
  // An empty flow has no state to report; callers render nothing rather than a
  // green `finished` badge for a flow with nothing in it.
  if (processes.length === 0) return null;
  if (processes.some((p) => (p.openQuestions ?? 0) > 0)) return 'question';
  if (processes.some((p) => isActiveStatus(p.status))) return 'running';
  // KILLED reads as failed: neither is a successful finish, and the aggregate
  // vocabulary has no state of its own for it.
  if (processes.some((p) => p.status === 'FAILED' || p.status === 'KILLED')) return 'failed';
  return 'finished';
}

/**
 * One root row's flow summary: how many runs the flow holds beyond the root,
 * what they cost together, and their aggregate state.
 *
 * The row itself already shows the root's own status and cost, so this
 * summarizes its descendants — the backend's `?rootProcessId=` filter matches
 * `root_process_id = N` and the root's own column is null. A flow whose page
 * was cut short by [FLOW_SUMMARY_LIMIT] is marked `partial` instead of silently
 * reporting a cheaper, smaller flow.
 */
export function FlowSummaryCell({ rootProcessId }: { rootProcessId: number }) {
  const { data } = useFlowSummary(rootProcessId, FLOW_SUMMARY_LIMIT);

  if (!data) return <span className="text-sm text-muted-foreground">-</span>;

  const runs = data.processes;
  const costUsd = runs.reduce((sum, p) => sum + (p.costUsd ?? 0), 0);
  const truncated = data.total > runs.length;
  const flowStateKey = flowState(runs);
  const state = flowStateKey ? FLOW_STATE_STYLE[flowStateKey] : null;

  return (
    <span className="flex flex-wrap items-center gap-2 text-sm">
      <span className="font-mono">
        {data.total} {data.total === 1 ? 'run' : 'runs'}
      </span>
      {costUsd > 0 && (
        <span className="font-mono text-muted-foreground">{formatCost(costUsd)}</span>
      )}
      {state && (
        <span className={cn('rounded px-1.5 py-0.5 text-xs', state.className)}>{state.label}</span>
      )}
      {truncated && (
        <span
          className="text-xs text-muted-foreground"
          title={`Showing the newest ${runs.length} of ${data.total} runs`}
        >
          partial
        </span>
      )}
    </span>
  );
}
