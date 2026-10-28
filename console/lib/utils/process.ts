import type { ProcessInfo, ProcessStatus } from '@/types/homer';
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
 * Derives the "last command" status and label shown for a process from a dots
 * row the caller already computed (`commandDotStates`): the state of the last
 * dot, remapped to the Last Command icon's vocabulary. Taking the dots keeps a
 * row to one classification of the executions, so the dots row and this icon
 * cannot drift — and the caller can share the same array with both.
 */
export function lastCommandFromDots(dots: CommandDot[]): LastCommand {
  const last = dots[dots.length - 1];
  if (!last) {
    return { status: null, label: null };
  }

  return {
    status: last.state === 'ok' ? 'success' : last.state,
    label: last.label,
  };
}

/**
 * The run's elapsed seconds: its first history entry to the last one for a
 * run that ended, to `now` while it is still active. `null` when history is
 * missing, malformed or would make the span negative, so the caller renders a
 * placeholder rather than `NaN`. Before `useNow`'s first tick (`now === 0`) an
 * active run falls back to its last history entry, so the first paint is
 * stable instead of showing a run that started in 1970. The `now === 0`
 * sentinel deliberately reuses `useNow`'s convention rather than typing the
 * clock `number | null`: the hook is the only caller and already holds the
 * sentinel, so a nullable parameter would push the special case onto it.
 */
export function runDurationSeconds(process: ProcessInfo, now: number): number | null {
  const history = process.history ?? [];
  if (history.length === 0) {
    return null;
  }
  const start = history[0].timestamp;
  const last = history[history.length - 1].timestamp;
  if (!Number.isFinite(start) || !Number.isFinite(last)) {
    return null;
  }

  const active = isActiveStatus(process.status) && now > 0;
  return elapsedSeconds(start, active ? now : last);
}

/** `end - start` in seconds when the span is finite and not negative, else null. */
function elapsedSeconds(start: number, end: number): number | null {
  const seconds = end - start;
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : null;
}

export type CommandDotState = 'ok' | 'failed' | 'running';

/** One dot of a run's command-progress row: the command's label and how it ended. */
export interface CommandDot {
  label: string;
  state: CommandDotState;
}

/**
 * One dot per command the run actually executed — `ok` (resultCode 0) or
 * `failed` — with the in-flight command appended as `running` while the run is
 * WORKING. Commands a `when:` condition skipped never ran and get no dot, so the
 * Last Command icon (`lastCommandFromDots`) agrees. Commands not yet run get none
 * either: the list response carries no total command count.
 */
export function commandDotStates(process: ProcessInfo): CommandDot[] {
  const dots: CommandDot[] = (process.executions ?? [])
    .filter((execution) => !execution.skipped)
    .map((execution) => ({
      label: execution.label,
      state: execution.resultCode === 0 ? 'ok' : 'failed',
    }));

  if (process.status === 'WORKING' && process.currentCommand) {
    dots.push({ label: process.currentCommand, state: 'running' });
  }

  return dots;
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
