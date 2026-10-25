import type { ProcessInfo, ProcessStatus } from '@/types/homer';
import { formatTimestamp } from './format';
import { splitCommaList } from './list';

/** A run that has not ended yet (`CREATED` or `WORKING`) — the statuses Kill applies to. */
export function isActiveStatus(status: ProcessStatus): boolean {
  return status === 'WORKING' || status === 'CREATED';
}

/** A run that ended normally (`FINISHED`, `FAILED` or `KILLED`) — the statuses Retry applies to. */
export function isRetryableStatus(status: ProcessStatus): boolean {
  return status === 'FINISHED' || status === 'FAILED' || status === 'KILLED';
}

export type LastCommandStatus = 'success' | 'running' | 'failed';

export interface LastCommand {
  status: LastCommandStatus | null;
  label: string | null;
}

/**
 * Derives the "last command" status and label shown for a process.
 *
 * A WORKING process with a current command reports that command as
 * `running`; otherwise the most recent execution that actually ran is
 * classified as `success` (resultCode 0) or `failed` — a command skipped
 * by its `when:` condition never ran, so the trailing skips are passed
 * over. A process with no execution that ran reports null status and
 * label.
 */
export function getLastCommand(process: ProcessInfo): LastCommand {
  if (process.status === 'WORKING' && process.currentCommand) {
    return { status: 'running', label: process.currentCommand };
  }

  const lastRan = (process.executions ?? []).findLast((e) => !e.skipped);
  if (!lastRan) {
    return { status: null, label: null };
  }

  return {
    status: lastRan.resultCode === 0 ? 'success' : 'failed',
    label: lastRan.label,
  };
}

/**
 * Returns the formatted timestamp of the process's most recent history
 * entry, or '-' when there is no history.
 */
export function getStatusTime(process: ProcessInfo): string {
  if (!process.history || process.history.length === 0) {
    return '-';
  }
  const lastHistory = process.history[process.history.length - 1];
  return formatTimestamp(lastHistory.timestamp);
}

/** The status filters the process list offers, in display order. */
export const PROCESS_STATUS_FILTERS: ProcessStatus[] = ['WORKING', 'FINISHED', 'FAILED', 'KILLED', 'CREATED'];

/** The process list's filters, sort and paging as read from its URL. */
export interface ProcessListParams {
  selectedStatuses: ProcessStatus[];
  selectedAgent: string;
  order: 'asc' | 'desc';
  offset: number;
  parentProcessId: number | undefined;
  rootsView: boolean;
  showRoots: boolean;
  selectedTags: string[];
  /** Any filter (status, agent, parent or tag) is set. */
  hasFilters: boolean;
}

/** `raw` as an integer of at least `min`, or undefined when it is missing, malformed or smaller. */
function intAtLeast(raw: string | null, min: number): number | undefined {
  const parsed = parseInt(raw ?? '', 10);
  return Number.isNaN(parsed) || parsed < min ? undefined : parsed;
}

/** The comma-joined `tag` value as trimmed, non-empty, de-duplicated tags. */
function tagFilters(raw: string | null): string[] {
  return [...new Set(splitCommaList(raw))];
}

function anyFilter(
  selectedStatuses: ProcessStatus[],
  selectedAgent: string,
  parentProcessId: number | undefined,
  selectedTags: string[],
): boolean {
  return (
    selectedStatuses.length > 0 ||
    selectedAgent !== '' ||
    parentProcessId !== undefined ||
    selectedTags.length > 0
  );
}

/** Reads the process list's state from its URL; malformed values fall back to the defaults. */
export function parseProcessListParams(searchParams: Pick<URLSearchParams, 'get'>): ProcessListParams {
  const selectedStatuses = (searchParams
    .get('status')
    ?.split(',')
    .filter((s) => PROCESS_STATUS_FILTERS.includes(s as ProcessStatus)) ?? []) as ProcessStatus[];
  const selectedAgent = searchParams.get('agent') ?? '';
  const order = searchParams.get('order') === 'asc' ? 'asc' : 'desc';
  const offset = intAtLeast(searchParams.get('offset'), 0) ?? 0;
  // `parent` narrows the list to the direct children of one run (ADR-0058).
  const parentProcessId = intAtLeast(searchParams.get('parent'), 1);
  // The flat all-runs list is the default; `?view=roots` shows only the first
  // run of each flow, with a Flow column (ADR-0058). A `parent` filter narrows
  // to one run's children, which never have a flow to summarize, so it drops
  // the roots filter on its own — the child list is the whole point there.
  const rootsView = searchParams.get('view') === 'roots';
  const showRoots = rootsView && parentProcessId === undefined;
  // `tag` narrows the list to runs carrying every listed subject tag (ADR-0060).
  // Repeated in the URL as one comma-joined value; de-duped so a chip clicked
  // twice does not add a redundant filter.
  const selectedTags = tagFilters(searchParams.get('tag'));
  const hasFilters = anyFilter(selectedStatuses, selectedAgent, parentProcessId, selectedTags);
  return {
    selectedStatuses,
    selectedAgent,
    order,
    offset,
    parentProcessId,
    rootsView,
    showRoots,
    selectedTags,
    hasFilters,
  };
}
