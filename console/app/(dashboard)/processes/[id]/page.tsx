'use client';

import { use } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
  useProcess,
  useProcesses,
  useKillProcess,
  useDownloadArtifact,
  useRetryProcess,
  useResumeProcess,
} from '@/lib/api/queries';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { StatusBadge } from '@/components/processes/status-badge';
import { TagChips } from '@/components/processes/tag-chips';
import { BackButton } from '@/components/layout/back-button';
import { ExternalLink } from 'lucide-react';
import { formatCost } from '@/lib/utils/format';
import { costSourceLabel, costSourceTitle } from '@/lib/utils/cost-source';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { useCallback, useState } from 'react';
import { toArtifactApiPath } from '@/lib/utils/download';
import { ArtifactPickerDialog } from '@/components/processes/artifact-picker-dialog';
import { RunnerPanel } from '@/components/processes/runner-panel';
import { QuestionPanel } from '@/components/questions/question-panel';
import { RunMessagePanel } from '@/components/messages/run-message-panel';
import { ExecutionItem } from '@/components/processes/execution-item';
import { CurrentCommandBox } from '@/components/processes/current-command-box';
import { ProcessActions } from '@/components/processes/process-actions';
import { FlowSection } from '@/components/processes/flow-section';
import { ActionErrorAlert } from '@/components/processes/action-error-alert';
import { ApiErrorAlert } from '@/components/layout/api-error-alert';
import { StatusTimelineCard } from '@/components/processes/status-timeline-card';
import { ChildRunsCard } from '@/components/processes/child-runs-card';
import { useNow } from '@/lib/hooks/use-now';
import { useProcessLangGraphView } from '@/lib/hooks/use-process-lang-graph-view';
import type { LangGraphRunStatus, ProcessInfo, ProcessListResponse } from '@/types/homer';

// Children listed inline on the run page; the full, paged list lives behind
// `/processes?parent=<id>`. A root shows the whole Flow section instead.
const CHILD_RUNS_SHOWN = 20;

// The run's cost, from the cost ledger via the status API rather than parsed out
// of cmd_N.out — so it is still here after the artifacts are purged.
function CostMeta({ process }: { process: ProcessInfo }) {
  if (!process.costUsd) return null;
  return (
    <span className="text-sm">
      cost <span className="font-medium text-foreground">{formatCost(process.costUsd)}</span>{' '}
      <span className="rounded border px-1 text-xs" title={costSourceTitle(process.costSource)}>
        {costSourceLabel(process.costSource)}
      </span>
    </span>
  );
}

// The agent link, cost, parent/root run links, Langfuse trace and tags under
// the page title.
function ProcessMeta({ process }: { process: ProcessInfo }) {
  return (
    <>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-muted-foreground">
        <Link
          href={`/agents/${encodeURIComponent(process.agentName)}`}
          className="text-sm underline underline-offset-4 hover:text-foreground"
        >
          {process.agentName}
        </Link>
        {/* The agent's group, from the current definition; nothing for an ungrouped agent. */}
        {process.group && (
          <span className="text-sm">
            group <span className="font-medium text-foreground">{process.group}</span>
          </span>
        )}
        <CostMeta process={process} />
        {process.parentProcessId != null && (
          <Link
            href={`/processes/${process.parentProcessId}`}
            className="text-sm underline underline-offset-4 hover:text-foreground"
          >
            Parent run #{process.parentProcessId}
          </Link>
        )}
        {/* The direct parent above is one hop; this jumps to the first run
            of the whole flow (ADR-0058). Null only on the root, which
            links nowhere — its own page is the flow view. */}
        {process.rootProcessId != null && (
          <Link
            href={`/processes/${process.rootProcessId}`}
            className="text-sm underline underline-offset-4 hover:text-foreground"
          >
            Root run #{process.rootProcessId}
          </Link>
        )}
        {process.langfuseTraceUrl && (
          <a
            href={process.langfuseTraceUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1 text-sm underline underline-offset-4 hover:text-foreground"
          >
            Langfuse trace
            <ExternalLink className="h-3 w-3" aria-hidden="true" />
          </a>
        )}
      </div>
      {process.tags && process.tags.length > 0 && (
        <TagChips tags={process.tags} className="mt-2" />
      )}
    </>
  );
}

// The live command's workflow panel: the status once fetched, a loading state
// while the first fetch is in flight, and nothing while the panel is closed.
function liveWorkflow(open: boolean, status: { data?: LangGraphRunStatus | null; isFetching: boolean }) {
  return {
    workflow: open ? status.data ?? undefined : undefined,
    workflowLoading: open && !status.data && status.isFetching,
  };
}

// A flow root holds no descendants, so it lists the whole flow instead of the
// direct-children card (ADR-0058). Absence of rootProcessId is read only on
// the root's own row — a descendant always carries one.
function RelatedRuns({
  process,
  processId,
  runs,
}: {
  process: ProcessInfo;
  processId: number;
  runs: ProcessListResponse | undefined;
}) {
  return process.rootProcessId == null ? (
    <FlowSection processId={processId} rootAgentName={process.agentName} />
  ) : (
    <ChildRunsCard parentProcessId={process.id} runs={runs} />
  );
}

// Kill (behind its confirmation dialog), Retry and Resume; Retry and Resume
// open the run they start.
function useRunActions(processId: number) {
  const router = useRouter();
  const killProcess = useKillProcess();
  const retryProcess = useRetryProcess();
  const resumeProcess = useResumeProcess();
  const [killDialogOpen, setKillDialogOpen] = useState(false);

  const handleKill = async () => {
    await killProcess.mutateAsync(processId);
    setKillDialogOpen(false);
  };

  const handleRetry = async () => {
    try {
      const { processId: newId } = await retryProcess.mutateAsync(processId);
      router.push(`/processes/${newId}`);
    } catch (err) {
      console.error('Retry failed:', err);
    }
  };

  const handleResume = async () => {
    try {
      const { processId: newId } = await resumeProcess.mutateAsync(processId);
      router.push(`/processes/${newId}`);
    } catch (err) {
      console.error('Resume failed:', err);
    }
  };

  return { retryProcess, resumeProcess, killDialogOpen, setKillDialogOpen, handleKill, handleRetry, handleResume };
}

type RunActions = ReturnType<typeof useRunActions>;

interface ArtifactDownload {
  onDownloadArtifact: (artifactPath: string | null) => void;
  downloadPending: boolean;
}

function useArtifactDownload(processId: number): ArtifactDownload {
  const downloadArtifact = useDownloadArtifact();
  // Stable callbacks: the 1 s `useNow` tick re-renders this page, and the
  // memoized ExecutionItems skip that render only while their props hold still.
  const downloadArtifactAsync = downloadArtifact.mutateAsync;
  const handleArtifactDownload = useCallback(
    async (artifactPath: string | null) => {
      if (!artifactPath) return;

      try {
        await downloadArtifactAsync({
          processId,
          artifactPath: toArtifactApiPath(artifactPath),
        });
      } catch (error) {
        console.error('Download failed:', error);
      }
    },
    [downloadArtifactAsync, processId],
  );
  return { onDownloadArtifact: handleArtifactDownload, downloadPending: downloadArtifact.isPending };
}

// The indexes of the expanded execution items, with a stable toggle.
function useExpandedExecutions() {
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const toggle = useCallback((index: number) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(index)) {
        next.delete(index);
      } else {
        next.add(index);
      }
      return next;
    });
  }, []);
  return { expanded, toggle };
}

// Which executions are expanded and whether the live command's workflow panel
// is open, plus the LangGraph view that both drive.
function useExecutionsView(processId: number, process: ProcessInfo | undefined) {
  const expansion = useExpandedExecutions();
  const [liveWorkflowExpanded, setLiveWorkflowExpanded] = useState(false);
  const lg = useProcessLangGraphView(processId, process, expansion.expanded.size > 0, liveWorkflowExpanded);
  return { expansion, lg, toggleLiveWorkflow: () => setLiveWorkflowExpanded((v) => !v) };
}

// Direct children of this run (ADR-0058). The SSE process stream invalidates
// ['processes'], so a child appearing or finishing updates this card live.
// Gated on a real id: with NaN the filter would drop and the card would list
// unrelated runs as children.
function useChildRuns(processId: number) {
  return useProcesses(
    { parentProcessId: processId, order: 'asc', limit: CHILD_RUNS_SHOWN },
    Number.isFinite(processId) && processId > 0,
  );
}

type LangGraphView = ReturnType<typeof useProcessLangGraphView>;

// A finished execution's workflow panel: the status when it belongs to this
// execution's label, and a loading state while the LangGraph command's first
// status fetch is in flight.
function executionWorkflow(label: string, lg: LangGraphView) {
  return {
    workflow: lg.lgStatus.data && label === lg.lgStatus.data.label ? lg.lgStatus.data : undefined,
    workflowLoading: label === lg.lgLabel && !lg.lgStatus.data && lg.lgStatus.isFetching,
  };
}

function ProcessHeader({
  process,
  actions,
  canResume,
  onDownloadClick,
}: {
  process: ProcessInfo;
  actions: RunActions;
  canResume: boolean;
  onDownloadClick: () => void;
}) {
  return (
    <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
      <div>
        <h2 className="text-3xl font-bold tracking-tight">Process {process.id}</h2>
        <ProcessMeta process={process} />
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <StatusBadge status={process.status} />
        <ProcessActions
          process={process}
          onDownloadArtifactClick={onDownloadClick}
          onRetryClick={actions.handleRetry}
          onKillClick={() => actions.setKillDialogOpen(true)}
          retryPending={actions.retryProcess.isPending}
          canResume={canResume}
          onResumeClick={actions.handleResume}
          resumePending={actions.resumeProcess.isPending}
        />
      </div>
    </div>
  );
}

interface ExecutionsCardProps {
  process: ProcessInfo;
  processId: number;
  now: number;
  expansion: ReturnType<typeof useExpandedExecutions>;
  download: ArtifactDownload;
  lg: LangGraphView;
  onToggleLiveWorkflow: () => void;
}

// The finished executions, then the command running now, or an empty state.
function ExecutionsCard({ process, processId, now, expansion, download, lg, onToggleLiveWorkflow }: ExecutionsCardProps) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Executions</CardTitle>
        <CardDescription>Command execution history</CardDescription>
      </CardHeader>
      <CardContent>
        {process.executions.length === 0 && !process.currentCommand ? (
          <p className="text-sm text-muted-foreground">No executions yet</p>
        ) : (
          <div className="space-y-3">
            {process.executions.map((execution, index) => (
              <ExecutionItem
                key={index}
                execution={execution}
                index={index}
                expanded={expansion.expanded.has(index)}
                onToggle={expansion.toggle}
                processId={processId}
                {...download}
                {...executionWorkflow(execution.label, lg)}
              />
            ))}
            {process.currentCommand && (
              <CurrentCommandBox
                command={process.currentCommand}
                commandStart={process.currentCommandStart}
                executionsLength={process.executions.length}
                currentCommandIndex={process.currentCommandIndex}
                commandKind={process.currentCommandKind}
                now={now}
                processId={processId}
                {...download}
                showWorkflowToggle={lg.liveIsLangGraph}
                workflowExpanded={lg.liveWorkflowOpen}
                onToggleWorkflow={onToggleLiveWorkflow}
                {...liveWorkflow(lg.liveWorkflowOpen, lg.lgStatus)}
              />
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

/** What the page shows before the process loads: the loading line, or why it cannot load. */
function ProcessPlaceholder({ processId, error }: { processId: number; error: Error | null }) {
  if (error) {
    return (
      <div className="p-6 space-y-6">
        <BackButton fallbackHref="/processes" label="Processes" />
        <ApiErrorAlert
          title="Failed to load the process"
          error={error}
          notFound={`Process ${processId} does not exist, or you cannot see it.`}
        />
      </div>
    );
  }
  return (
    <div className="flex items-center justify-center p-8">
      <div className="text-muted-foreground">Loading process details...</div>
    </div>
  );
}

function KillProcessDialog({
  processId,
  open,
  onOpenChange,
  onConfirm,
}: {
  processId: number;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onConfirm: () => void;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent aria-describedby="kill-process-detail-desc">
        <DialogHeader>
          <DialogTitle>Confirm Kill Process</DialogTitle>
          <DialogDescription id="kill-process-detail-desc">
            Are you sure you want to kill process {processId}? This action cannot be undone.
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button variant="destructive" onClick={onConfirm}>
            Kill Process
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export default function ProcessDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const resolvedParams = use(params);
  const processId = parseInt(resolvedParams.id);
  const { data: process, error } = useProcess(processId);
  const actions = useRunActions(processId);
  const download = useArtifactDownload(processId);
  const [downloadDialogOpen, setDownloadDialogOpen] = useState(false);
  const { expansion, lg, toggleLiveWorkflow } = useExecutionsView(processId, process);
  const now = useNow();
  const { data: children } = useChildRuns(processId);

  if (!process) return <ProcessPlaceholder processId={processId} error={error} />;

  return (
    <div className="p-6 space-y-6">
      <BackButton fallbackHref="/processes" label="Processes" />
      <ProcessHeader
        process={process}
        actions={actions}
        canResume={lg.resumeEligible && !!lg.lgProbe.data}
        onDownloadClick={() => setDownloadDialogOpen(true)}
      />

      {process.runner && <RunnerPanel runner={process.runner} />}

      <QuestionPanel processId={processId} />

      <RunMessagePanel process={process} />

      <ActionErrorAlert action="Retry" isError={actions.retryProcess.isError} error={actions.retryProcess.error} />

      <ActionErrorAlert action="Resume" isError={actions.resumeProcess.isError} error={actions.resumeProcess.error} />

      <StatusTimelineCard history={process.history} purgeAt={process.purgeAt} />

      <RelatedRuns process={process} processId={processId} runs={children} />

      <ExecutionsCard
        process={process}
        processId={processId}
        now={now}
        expansion={expansion}
        download={download}
        lg={lg}
        onToggleLiveWorkflow={toggleLiveWorkflow}
      />

      <KillProcessDialog
        processId={processId}
        open={actions.killDialogOpen}
        onOpenChange={actions.setKillDialogOpen}
        onConfirm={actions.handleKill}
      />

      <ArtifactPickerDialog
        processId={process.id}
        open={downloadDialogOpen}
        onOpenChange={setDownloadDialogOpen}
        onSelect={(path) => {
          download.onDownloadArtifact(path);
        }}
      />
    </div>
  );
}
