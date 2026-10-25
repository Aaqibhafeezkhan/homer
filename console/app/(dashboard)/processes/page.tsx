'use client';

import Link from 'next/link';
import { useProcesses } from '@/lib/api/queries';
import { useUrlQueryState } from '@/lib/hooks/use-url-query-state';
import { ProcessTable } from '@/components/processes/process-table';
import { FlowSummaryCell } from '@/components/processes/flow-summary-cell';
import { AgentFilter } from '@/components/processes/agent-filter';
import { SweepStatusBanner } from '@/components/processes/sweep-status-banner';
import { ApiErrorAlert } from '@/components/layout/api-error-alert';
import { Button } from '@/components/ui/button';
import { ProcessInfo, ProcessListResponse, ProcessStatus } from '@/types/homer';
import { Badge } from '@/components/ui/badge';
import { ArrowUpDown, Layers, List, X } from 'lucide-react';
import { appConfig } from '@/lib/config/app-config';
import { formatCost } from '@/lib/utils/format';
import { PROCESS_STATUS_FILTERS, parseProcessListParams, type ProcessListParams } from '@/lib/utils/process';

// Root rows whose Flow cell fetches a summary, in visible-page order. Each
// column cell is one request, so the count is bounded here rather than growing
// with the page (§12 rule 4).
const FLOW_ROWS_WITH_SUMMARY = 20;

// Root runs only vs every run; both buttons are toggles, so each says whether it is pressed.
function RunViewToggle({
  showRoots,
  allRunsView,
  onShowRoots,
  onShowAll,
}: {
  showRoots: boolean;
  allRunsView: boolean;
  onShowRoots: () => void;
  onShowAll: () => void;
}) {
  return (
    <div className="flex items-center gap-2" role="group" aria-label="Run view">
      <Button
        variant={showRoots ? 'default' : 'outline'}
        size="sm"
        onClick={onShowRoots}
        aria-pressed={showRoots}
      >
        <Layers className="h-3 w-3 mr-1" aria-hidden="true" />
        Root runs
      </Button>
      <Button
        variant={allRunsView ? 'default' : 'outline'}
        size="sm"
        onClick={onShowAll}
        aria-pressed={allRunsView}
      >
        <List className="h-3 w-3 mr-1" aria-hidden="true" />
        All runs
      </Button>
    </div>
  );
}

function SortOrderButton({ order, onToggle }: { order: 'asc' | 'desc'; onToggle: () => void }) {
  return (
    <Button
      variant="outline"
      size="sm"
      onClick={onToggle}
      aria-label={`Sort order: ${order === 'desc' ? 'newest first' : 'oldest first'}`}
    >
      <ArrowUpDown className="h-3 w-3 mr-1" aria-hidden="true" />
      {order === 'desc' ? 'Newest first' : 'Oldest first'}
    </Button>
  );
}

// The URL state as list-query filters; an empty one is left out rather than sent.
function listFilters(params: ProcessListParams) {
  return {
    types: params.selectedStatuses.length > 0 ? params.selectedStatuses : undefined,
    agentName: params.selectedAgent || undefined,
    parentProcessId: params.parentProcessId,
    roots: params.showRoots || undefined,
    tags: params.selectedTags.length > 0 ? params.selectedTags : undefined,
  };
}

function pageSummary(data: ProcessListResponse | undefined) {
  return {
    // Sum of the currently filtered/paginated page, not a cross-page total —
    // matches what the table actually shows.
    pageCostUsd: data ? data.processes.reduce((sum, p) => sum + (p.costUsd ?? 0), 0) : 0,
    // Ids of the visible roots whose Flow cell fetches a summary, in page order.
    summaryRootIds: data
      ? data.processes.slice(0, FLOW_ROWS_WITH_SUMMARY).map((p) => p.id)
      : [],
  };
}

function ListLoadState({ isLoading, error }: { isLoading: boolean; error: Error | null }) {
  return (
    <>
      {isLoading && (
        <div className="flex items-center justify-center py-8">
          <div className="text-muted-foreground">Loading processes...</div>
        </div>
      )}

      {error && <ApiErrorAlert title="Failed to load processes" error={error} />}
    </>
  );
}

type SetParams = ReturnType<typeof useUrlQueryState>['setParams'];

// The URL writes behind the filter, sort and view controls; each resets paging.
function listActions(params: ProcessListParams, setParams: SetParams) {
  const { selectedStatuses, selectedTags, order } = params;

  const setTags = (tags: string[]) => {
    setParams({ tag: tags.length > 0 ? tags.join(',') : null, offset: null });
  };

  const addTag = (tag: string) => {
    if (selectedTags.includes(tag)) return;
    setTags([...selectedTags, tag]);
  };

  const removeTag = (tag: string) => {
    setTags(selectedTags.filter((t) => t !== tag));
  };

  const toggleStatus = (status: ProcessStatus) => {
    const next = selectedStatuses.includes(status)
      ? selectedStatuses.filter((s) => s !== status)
      : [...selectedStatuses, status];
    setParams({ status: next.length > 0 ? next.join(',') : null, offset: null });
  };

  const handleAgentChange = (value: string) => {
    setParams({ agent: value || null, offset: null });
  };

  const toggleOrder = () => {
    setParams({ order: order === 'desc' ? 'asc' : null, offset: null });
  };

  const clearParent = () => {
    setParams({ parent: null, offset: null });
  };

  // Switching views changes what a row means, so it resets paging. Going to
  // the roots view also drops the `parent` filter, which overrides it.
  const showAllRuns = () => {
    setParams({ view: null, offset: null });
  };

  const showRootsOnly = () => {
    setParams({ view: 'roots', parent: null, offset: null });
  };

  const clearFilters = () => {
    setParams({ status: null, agent: null, parent: null, tag: null, offset: null });
  };

  return {
    addTag, removeTag, toggleStatus, handleAgentChange, toggleOrder,
    clearParent, showAllRuns, showRootsOnly, clearFilters,
  };
}

type ListActions = ReturnType<typeof listActions>;

// Render prop for the Flow cell: a summary for the capped rows, a dash past the
// cap. Passing it only for the roots view is what keys both the column and its
// header.
function flowSummaryRenderer(showRoots: boolean, summaryRootIds: number[]) {
  return showRoots
    ? (process: ProcessInfo) =>
        summaryRootIds.includes(process.id) ? (
          <FlowSummaryCell rootProcessId={process.id} />
        ) : (
          <span className="text-muted-foreground">-</span>
        )
    : undefined;
}

function ParentFilterBadge({ parentProcessId, onClear }: { parentProcessId: number; onClear: () => void }) {
  return (
    <Badge variant="secondary" className="gap-1">
      Children of{' '}
      <Link href={`/processes/${parentProcessId}`} className="underline underline-offset-2">
        #{parentProcessId}
      </Link>
      <button
        type="button"
        onClick={onClear}
        aria-label="Clear parent filter"
        className="ml-1 rounded-sm hover:text-foreground"
      >
        <X className="h-3 w-3" aria-hidden="true" />
      </button>
    </Badge>
  );
}

function TagFilterBadge({ tag, onClear }: { tag: string; onClear: (tag: string) => void }) {
  return (
    <Badge variant="secondary" className="gap-1 font-mono font-normal" title={tag}>
      <span className="max-w-[16rem] truncate">tag: {tag}</span>
      <button
        type="button"
        onClick={() => onClear(tag)}
        aria-label={`Clear tag filter ${tag}`}
        className="ml-1 rounded-sm hover:text-foreground"
      >
        <X className="h-3 w-3" aria-hidden="true" />
      </button>
    </Badge>
  );
}

// The status toggles, then the active parent and tag filters and Clear.
function FilterBar({ params, actions }: { params: ProcessListParams; actions: ListActions }) {
  const { selectedStatuses, parentProcessId, selectedTags, hasFilters } = params;
  return (
    <div className="flex items-center gap-2 flex-wrap">
      <span className="text-sm font-medium">Filter by status:</span>
      {PROCESS_STATUS_FILTERS.map((status) => (
        <Badge
          key={status}
          variant={selectedStatuses.includes(status) ? 'default' : 'outline'}
          className="cursor-pointer"
          onClick={() => actions.toggleStatus(status)}
        >
          {status}
        </Badge>
      ))}
      {parentProcessId !== undefined && (
        <ParentFilterBadge parentProcessId={parentProcessId} onClear={actions.clearParent} />
      )}
      {selectedTags.map((tag) => (
        <TagFilterBadge key={tag} tag={tag} onClear={actions.removeTag} />
      ))}
      {hasFilters && (
        <Button variant="ghost" size="sm" onClick={actions.clearFilters} aria-label="Clear filters">
          <X className="h-3 w-3 mr-1" aria-hidden="true" />
          Clear
        </Button>
      )}
    </div>
  );
}

// The agent filter and sort order, with the visible page's total cost once loaded.
function ListToolbar({
  selectedAgent,
  order,
  pageCostUsd,
  actions,
}: {
  selectedAgent: string;
  order: 'asc' | 'desc';
  pageCostUsd: number | null;
  actions: ListActions;
}) {
  return (
    <div className="flex items-center justify-between gap-2 flex-wrap">
      <div className="flex items-center gap-2 flex-wrap">
        <AgentFilter value={selectedAgent} onChange={actions.handleAgentChange} />
        <SortOrderButton order={order} onToggle={actions.toggleOrder} />
      </div>
      {pageCostUsd !== null && (
        <span className="text-sm text-muted-foreground">
          Total cost: <span className="font-mono font-medium text-foreground">{formatCost(pageCostUsd)}</span>
        </span>
      )}
    </div>
  );
}

function ListPagination({
  offset,
  limit,
  total,
  setParams,
}: {
  offset: number;
  limit: number;
  total: number;
  setParams: SetParams;
}) {
  const handleNextPage = () => {
    setParams({ offset: String(offset + limit) });
  };

  const handlePrevPage = () => {
    const prevOffset = Math.max(0, offset - limit);
    setParams({ offset: prevOffset > 0 ? String(prevOffset) : null });
  };

  return (
    <div className="flex items-center justify-between">
      <p className="text-sm text-muted-foreground">
        Showing {offset + 1} to {Math.min(offset + limit, total)} of {total} processes
      </p>
      <div className="flex gap-2">
        <Button
          variant="outline"
          size="sm"
          onClick={handlePrevPage}
          disabled={offset === 0}
        >
          Previous
        </Button>
        <Button
          variant="outline"
          size="sm"
          onClick={handleNextPage}
          disabled={offset + limit >= total}
        >
          Next
        </Button>
      </div>
    </div>
  );
}

export default function ProcessesPage() {
  const { searchParams, setParams } = useUrlQueryState();
  const limit = appConfig.pagination.defaultLimit;

  const listParams = parseProcessListParams(searchParams);
  const { selectedAgent, order, offset, rootsView, showRoots } = listParams;
  const allRunsView = !rootsView;

  const { data, isLoading, error } = useProcesses({
    ...listFilters(listParams),
    limit,
    offset,
    order,
  });

  const { pageCostUsd, summaryRootIds } = pageSummary(data);
  const renderFlowSummary = flowSummaryRenderer(showRoots, summaryRootIds);
  const actions = listActions(listParams, setParams);

  return (
    <div className="p-6 space-y-4">
      <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
        <h2 className="text-3xl font-bold tracking-tight">Processes</h2>
        <RunViewToggle
          showRoots={showRoots}
          allRunsView={allRunsView}
          onShowRoots={actions.showRootsOnly}
          onShowAll={actions.showAllRuns}
        />
      </div>

      <SweepStatusBanner />

      <FilterBar params={listParams} actions={actions} />

      <ListToolbar
        selectedAgent={selectedAgent}
        order={order}
        pageCostUsd={data ? pageCostUsd : null}
        actions={actions}
      />

      <ListLoadState isLoading={isLoading} error={error} />

      {data && (
        <>
          <div className="rounded-md border">
            <ProcessTable
              processes={data.processes}
              onTagClick={actions.addTag}
              flowSummary={renderFlowSummary}
            />
          </div>

          <ListPagination offset={offset} limit={limit} total={data.total} setParams={setParams} />
        </>
      )}

      {data && data.processes.length === 0 && (
        <div className="flex items-center justify-center py-8">
          <p className="text-muted-foreground">No processes found</p>
        </div>
      )}
    </div>
  );
}
